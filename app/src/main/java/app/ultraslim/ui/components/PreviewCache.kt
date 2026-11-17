package app.ultraslim.ui.components

import android.app.ActivityManager
import android.content.Context
import app.ultraslim.convert.ConversionBudget
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.ImageWork
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.withLock

/**
 * JPEG bytes from [UltraHdrBridge.nativeDecodePreview], kept in memory (~32MB)
 * and under cacheDir/previews (newest 200 files).
 */
internal object PreviewCache {
    private const val THUMB_MAX_DIM = 512
    private const val THUMB_MAX_BYTES = 12 * 1024 * 1024
    private const val DETAIL_MAX_BYTES = 28 * 1024 * 1024
    private const val MAX_DISK_FILES = 200
    private const val TRIM_INTERVAL = 16

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
        if (uriString.startsWith("content://")) {
            versionCache.get(uriString)?.let { (cached, at) ->
                if (System.currentTimeMillis() - at < VERSION_TTL_MS) return cached
            }
        }
        val version = getVersion(context, uriString) ?: return null
        if (uriString.startsWith("content://")) versionCache.put(uriString, version to System.currentTimeMillis())
        return version
    }

    suspend fun load(context: Context, uriString: String, requestedDim: Int): Bitmap? {
        val maxDim = resolutions.firstOrNull { it >= requestedDim } ?: 4096
        val version = versionOf(context, uriString) ?: return null
        val key = cacheKey("$uriString:$version:v6", maxDim)

        val mem = cacheFor(maxDim)
        // 1. Fast-path: In-memory cache hit (LruCache is internally synchronized; no diskLock needed)
        mem.get(key)?.let { return it }

        // 同一 URI 的 miss 合并到一起；等候期间可能已产生更大的预览，取得锁后
        // 必须完整重查。磁盘解码仍在全局 diskLock 外，不串行化所有图片的加载。
        return requestLocks[(uriString.hashCode() and Int.MAX_VALUE) % requestLocks.size].withLock {
            mem.get(key)?.let { return@withLock it }
            val dir = File(context.cacheDir, "previews")
            val file = File(dir, "$key.jpg")
            readCached(file, key, maxDim)?.let { return@withLock it }
            // Detail previews can supply list thumbnails without decoding the original again.
            for (dimension in resolutions.filter { it > maxDim }) {
                val largerKey = cacheKey("$uriString:$version:v6", dimension)
                val larger = cacheFor(dimension).get(largerKey)
                    ?: readCached(File(dir, "$largerKey.jpg"), largerKey, dimension, sampleTo = maxDim)
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
            synchronized(diskLock) {
                dir.mkdirs()
                runCatching {
                    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }
                }
                val n = writesSinceTrim.incrementAndGet()
                if (n == 1 || n % TRIM_INTERVAL == 0) trim(dir)
            }
            bitmap
        }
    }

    // Decoded outside diskLock; corrupt entries are removed and decoded again on demand.
    // sampleTo > 0 时按目标尺寸带 inSampleSize 解码，且不按原 key 入内存缓存
    // （降采样结果不应占大图档位的缓存位）。
    private fun readCached(file: File, key: String, maxDim: Int, sampleTo: Int = 0): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        val bitmap = if (sampleTo > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val edge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (edge / (sample * 2) >= sampleTo) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                if (sampleTo <= THUMB_MAX_DIM) inPreferredConfig = Bitmap.Config.RGB_565
            })
        } else if (maxDim <= THUMB_MAX_DIM) {
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
        val files = dir.listFiles() ?: return
        var bytes = files.sumOf { it.length() }
        var count = files.size
        files.sortedBy { it.lastModified() }.forEach { file ->
            if(count > MAX_DISK_FILES || bytes > 128L * 1024 * 1024) { val n=file.length(); if(file.delete()){bytes-=n;count--;} }
        }
    }

    private fun cacheKey(uriString: String, maxDim: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$maxDim\n$uriString".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
