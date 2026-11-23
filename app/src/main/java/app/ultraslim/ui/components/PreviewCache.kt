package app.ultraslim.ui.components

import android.app.ActivityManager
import android.content.Context
import app.ultraslim.convert.ConversionBudget
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.annotation.RequiresApi
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.ImageWork
import app.ultraslim.convert.MediaStoreSaver
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.withLock

/**
 * Decoded preview bitmaps from [UltraHdrBridge.nativeDecodePreviewBitmap], kept in memory
 * and under cacheDir/previews (newest 200 files).
 */
internal object PreviewCache {
    private const val THUMB_MAX_DIM = 512
    private const val THUMB_MAX_BYTES = 12 * 1024 * 1024
    // Two 2048x2048 ARGB_8888 detail bitmaps occupy 33.55 MiB; 34 MiB avoids mutual eviction.
    private const val DETAIL_MAX_BYTES = 34 * 1024 * 1024
    private const val MAX_DISK_FILES = 200
    private const val TRIM_INTERVAL = 16
    private const val CACHE_VERSION = "v7"

    // 列表缩略图（<=512）与详情页大图（1600/2048）分池管理：
    // 防止详情页两张 2048px RGBA（约 25MB）把整页列表缩略图挤出内存缓存。
    private val thumbMemory = object : LruCache<String, Bitmap>(THUMB_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    private val detailMemory = object : LruCache<String, Bitmap>(DETAIL_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    private fun cacheFor(maxDim: Int): LruCache<String, Bitmap> =
        if (maxDim <= THUMB_MAX_DIM) thumbMemory else detailMemory

    private val diskLock = Any()
    private val writesSinceTrim = java.util.concurrent.atomic.AtomicInteger(0)
    private val requestLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }
    // 档位必须是真实存在的档：详情页放大请求 2048，缺档会被 firstOrNull 抬到 4096，
    // 而 4096 长边 RGBA 超过 MAX_BYTES，LruCache.put 单条超限会先清空整表再丢弃自己。
    private val resolutions = listOf(128, 384, 512, 1600, 2048, 4096)

    // content URI 的 version（size:mtime）每次都要一次 provider round-trip；
    // 列表滚动是秒级窗口，2 秒 TTL 足以消掉重复查询。
    private const val VERSION_TTL_MS = 2000L
    private val versionCache = object : LruCache<String, Pair<String, Long>>(512) {}

    private fun versionOf(context: Context, uriString: String): String? {
        val now = System.currentTimeMillis()
        if (uriString.startsWith("content://")) {
            versionCache.get(uriString)?.let { (cached, at) ->
                if (now - at < VERSION_TTL_MS) return cached
            }
        }
        val version = getVersion(context, uriString) ?: return null
        versionCache.put(uriString, version to now)
        return version
    }

    /** Zero-IO memory lookup for Compose initial state, preventing spinner flicker on scroll-back. */
    fun peek(uriString: String, requestedDim: Int): Bitmap? {
        val (version, _) = versionCache.get(uriString) ?: return null
        val maxDim = resolutions.firstOrNull { it >= requestedDim } ?: 4096
        val key = cacheKey("$uriString:$version:$CACHE_VERSION", maxDim)
        return cacheFor(maxDim).get(key)
    }

    suspend fun load(context: Context, uriString: String, requestedDim: Int): Bitmap? {
        val maxDim = resolutions.firstOrNull { it >= requestedDim } ?: 4096
        val version = versionOf(context, uriString) ?: return null
        val key = cacheKey("$uriString:$version:$CACHE_VERSION", maxDim)

        val mem = cacheFor(maxDim)
        // 1. Fast-path: In-memory cache hit (LruCache is internally synchronized; no diskLock needed)
        mem.get(key)?.let { return it }

        // 同一 URI 的 miss 合并到一起；等候期间可能已产生更大的预览，取得锁后
        // 必须完整重查。磁盘解码仍在全局 diskLock 外，不串行化所有图片的加载。
        return requestLocks[(uriString.hashCode() and Int.MAX_VALUE) % requestLocks.size].withLock {
            mem.get(key)?.let { return@withLock it }
            val dir = File(context.cacheDir, "previews")
            readCached(dir, key, maxDim)?.let { return@withLock it }
            // Detail previews can supply list thumbnails without decoding the original again.
            for (dimension in resolutions.filter { it > maxDim }) {
                val largerKey = cacheKey("$uriString:$version:$CACHE_VERSION", dimension)
                val larger = cacheFor(dimension).get(largerKey)
                    ?: readCached(dir, largerKey, dimension, sampleTo = maxDim)
                if (larger != null) {
                    val edge = maxOf(larger.width, larger.height)
                    val bitmap = if (edge <= maxDim) larger else Bitmap.createScaledBitmap(larger,
                        (larger.width.toLong() * maxDim / edge).toInt().coerceAtLeast(1),
                        (larger.height.toLong() * maxDim / edge).toInt().coerceAtLeast(1), true)
                    mem.put(key, bitmap)
                    return@withLock bitmap
                }
            }

            // Open FD outside ImageWork so slow provider calls never stall the native lane.
            val stage = if (maxDim <= THUMB_MAX_DIM) "thumb $maxDim" else "preview $maxDim"
            val bitmap = decodeBitmap(context, uriString, maxDim, stage) ?: return@withLock null
            mem.put(key, bitmap)
            val hasAlpha = bitmap.hasAlpha()
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val file = File(dir, if (hasAlpha) "$key.png" else "$key.jpg")
            // Compress outside diskLock so a detail preview never stalls other disk writes.
            val encoded = runCatching {
                ByteArrayOutputStream(32 * 1024).use { buf ->
                    if (bitmap.compress(format, 82, buf)) buf.toByteArray() else null
                }
            }.getOrNull()
            if (encoded != null && encoded.isNotEmpty()) {
                synchronized(diskLock) {
                    dir.mkdirs()
                    val tmp = File(dir, "$key.tmp")
                    try {
                        tmp.writeBytes(encoded)
                        if (!tmp.renameTo(file)) {
                            tmp.copyTo(file, overwrite = true)
                        }
                    } catch (_: Exception) {
                        // Disk cache is best-effort; memory cache already holds the decoded bitmap.
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                    val n = writesSinceTrim.incrementAndGet()
                    if (n == 1 || n % TRIM_INTERVAL == 0) trim(dir)
                }
            }
            bitmap
        }
    }

    /**
     * Decodes a software [Bitmap] carrying an Android 14+ [Gainmap] for interactive HDR preview
     * in DetailScreen. Android 16+ can use platform [ImageDecoder] before falling back to native
     * gain-map extraction, which also covers JXL and strict ISO 21496-1 HEIC/AVIF.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun loadHdr(context: Context, uriString: String, requestedDim: Int): Bitmap? {
        val maxDim = resolutions.firstOrNull { it >= requestedDim } ?: 4096
        // Older ImageDecoder versions cannot tell us if the map uses a different gamut.
        if (Build.VERSION.SDK_INT >= 36 && !uriString.endsWith(".jxl", ignoreCase = true)) {
            val platformDecoded = runCatching {
                val source = if (uriString.startsWith("content://")) {
                    ImageDecoder.createSource(context.contentResolver, Uri.parse(uriString))
                } else {
                    ImageDecoder.createSource(File(uriString))
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val edge = maxOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(maxOf(1, (edge + maxDim - 1) / maxDim))
                    decoder.setOnPartialImageListener { false }
                }
            }.getOrNull()
            if (platformDecoded != null) {
                if (platformDecoded.hasGainmap()) return platformDecoded
                platformDecoded.recycle()
            }
        }
        val sdr = load(context, uriString, maxDim) ?: return null
        val baseCopy = sdr.copy(Bitmap.Config.ARGB_8888, false) ?: return sdr
        val attached = runCatching {
            val pfd = if (uriString.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
            } else {
                ParcelFileDescriptor.open(File(uriString), ParcelFileDescriptor.MODE_READ_ONLY)
            } ?: return@runCatching false
            val meta = FloatArray(18)
            val gmBitmap = pfd.use { fd ->
                ImageWork.run("hdr-gainmap $maxDim") {
                    val info = ActivityManager.MemoryInfo().also {
                        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
                    }
                    val budget = ConversionBudget.previewBytes(info.availMem, info.totalMem, info.threshold, info.lowMemory)
                    if (budget == 0L) null else UltraHdrBridge.nativeDecodeGainmapBitmap(fd.fd, maxDim, budget, meta)
                }
            } ?: return@runCatching false
            val alternatePrimaries = when (meta[17].toInt()) {
                1 -> ColorSpace.get(ColorSpace.Named.SRGB)
                9 -> ColorSpace.get(ColorSpace.Named.BT2020)
                12 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                else -> null
            }
            if (alternatePrimaries != null && Build.VERSION.SDK_INT < 36) {
                // Older Android releases can only calculate the gain map in the base gamut.
                val base = baseCopy.colorSpace as? ColorSpace.Rgb
                val alternate = alternatePrimaries as ColorSpace.Rgb
                if (base == null || !base.primaries.contentEquals(alternate.primaries)) {
                    gmBitmap.recycle()
                    return@runCatching false
                }
            }
            val minHdrRatio = meta[15].coerceAtLeast(1f)
            val gainmap = Gainmap(gmBitmap).apply {
                if (alternatePrimaries != null && Build.VERSION.SDK_INT >= 36) {
                    setAlternativeImagePrimaries(alternatePrimaries)
                }
                setRatioMin(meta[0], meta[1], meta[2])
                setRatioMax(meta[3], meta[4], meta[5])
                // libultrahdr stores the encoding exponent. Android takes the decoding exponent.
                setGamma(1f / meta[6], 1f / meta[7], 1f / meta[8])
                setEpsilonSdr(meta[9], meta[10], meta[11])
                setEpsilonHdr(meta[12], meta[13], meta[14])
                setMinDisplayRatioForHdrTransition(minHdrRatio)
                setDisplayRatioForFullHdr(maxOf(meta[16], minHdrRatio))
            }
            baseCopy.setGainmap(gainmap)
            true
        }.getOrDefault(false)
        return if (attached) baseCopy else {
            baseCopy.recycle()
            sdr
        }
    }

    // Decoded outside diskLock; corrupt entries are removed and decoded again on demand.
    // sampleTo > 0 时按目标尺寸带 inSampleSize 解码，且不按原 key 入内存缓存
    // （降采样结果不应占大图档位的缓存位）。
    private fun readCached(dir: File, key: String, maxDim: Int, sampleTo: Int = 0): Bitmap? {
        val png = File(dir, "$key.png")
        val file = if (png.isFile) png else File(dir, "$key.jpg")
        if (!file.isFile || file.length() == 0L) return null
        val mayUseRgb565 = file.extension == "jpg"
        val bitmap = if (sampleTo > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val edge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (edge / (sample * 2) >= sampleTo) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                if (mayUseRgb565 && sampleTo <= THUMB_MAX_DIM) inPreferredConfig = Bitmap.Config.RGB_565
            })
        } else if (mayUseRgb565 && maxDim <= THUMB_MAX_DIM) {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
        if (bitmap == null) file.delete() else if (sampleTo <= 0) {
            val now = System.currentTimeMillis()
            if (now - file.lastModified() > 60_000L) file.setLastModified(now)
            cacheFor(maxDim).put(key, bitmap)
        }
        return bitmap
    }

    private fun getVersion(context: Context, uriString: String): String? = runCatching {
        if (uriString.startsWith("content://")) {
            val uri = Uri.parse(uriString)
            var modified = 0L
            var size = 0L
            var queried = false
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, android.provider.MediaStore.MediaColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    queried = true
                    val modIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                    if (modIdx >= 0) modified = cursor.getLong(modIdx)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            if (!queried || (size == 0L && modified == 0L)) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { "${it.statSize}:$modified" } ?: return null
            } else {
                "$size:$modified"
            }
        } else {
            val source = File(uriString)
            if (!source.isFile) return null
            "${source.length()}:${source.lastModified()}"
        }
    }.getOrNull()

    private suspend fun decodeBitmap(context: Context, uriString: String, maxDim: Int, stage: String): Bitmap? = runCatching {
        val pfd = if (uriString.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
        } else {
            ParcelFileDescriptor.open(File(uriString), ParcelFileDescriptor.MODE_READ_ONLY)
        } ?: return@runCatching null
        pfd.use { fd ->
            ImageWork.run(stage) {
                // Measure after entering ImageWork: a request may have waited behind an encode.
                val info = ActivityManager.MemoryInfo().also {
                    context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
                }
                val budget = ConversionBudget.previewBytes(info.availMem, info.totalMem, info.threshold, info.lowMemory)
                if (budget == 0L) null else UltraHdrBridge.nativeDecodePreviewBitmap(fd.fd, maxDim, budget)
            }
        }
    }.getOrNull()

    private fun trim(dir: File) {
        val allFiles = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        val files = ArrayList<File>(allFiles.size)
        for (f in allFiles) {
            if (f.name.endsWith(".tmp")) {
                if (now - f.lastModified() > 60_000L) f.delete()
            } else {
                files += f
            }
        }
        var bytes = files.sumOf { it.length() }
        var count = files.size
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (count > MAX_DISK_FILES || bytes > 128L * 1024 * 1024) {
                val n = file.length()
                if (file.delete()) { bytes -= n; count-- }
            }
        }
    }

    /** Cleans leftover `.tmp` files and enforces the 200-file / 128 MiB cache bound. */
    fun cleanStartup(context: Context) {
        synchronized(diskLock) {
            val dir = File(context.cacheDir, "previews")
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { if (it.name.endsWith(".tmp")) it.delete() }
            trim(dir)
        }
    }

    private fun cacheKey(uriString: String, maxDim: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$maxDim\n$uriString".toByteArray(Charsets.UTF_8))
        return MediaStoreSaver.hex(bytes)
    }

    fun clear(context: Context) {
        synchronized(diskLock) {
            thumbMemory.evictAll()
            detailMemory.evictAll()
            versionCache.evictAll()
            writesSinceTrim.set(0)
            val dir = File(context.cacheDir, "previews")
            dir.deleteRecursively()
        }
    }

    /** Drops in-memory entries under system memory pressure; disk cache is kept for a later hit. */
    fun trimMemory() {
        thumbMemory.evictAll()
        detailMemory.evictAll()
        versionCache.evictAll()
    }
}
