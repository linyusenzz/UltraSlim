package app.ultraslim

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDecodeTest {

    /**
     * Decodes a big gain map AVIF pushed to the device through the exact preview
     * path the app uses, and dumps the JPEG next to it for inspection.
     */
    @Test fun decodeBigAvifPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "preview_probe.avif",
        )
        org.junit.Assume.assumeTrue("opt-in diagnostic: pass -e previewSample true",
            InstrumentationRegistry.getArguments().getString("previewSample") == "true")
        assertTrue("push preview_probe.avif first", source.exists())

        // Stage 1: raw Y plane straight out of heif_decode_image (no conversion).
        val packed = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            UltraHdrBridge.nativeDebugPlaneY(pfd.fd)
        }
        assertNotNull("nativeDebugPlaneY returned null", packed)
        val bb = java.nio.ByteBuffer.wrap(packed).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = bb.int
        val h = bb.int
        val yPlane = ByteArray(packed!!.size - 8)
        System.arraycopy(packed, 8, yPlane, 0, yPlane.size)
        File(instrumentation.targetContext.cacheDir, "yplane.bin").writeBytes(yPlane)
        val staleY = staleRowRatio(yPlane, w, h)
        android.util.Log.i("UltraSlimVerify", "Y plane ${w}x$h staleRatio=$staleY")

        // Stage 2: the converted preview jpeg.
        val jpeg = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            UltraHdrBridge.nativeDecodePreview(pfd.fd, 1600)
        }
        assertNotNull("nativeDecodePreview returned null", jpeg)
        val out = File(instrumentation.targetContext.cacheDir, "preview_probe.jpg")
        out.writeBytes(jpeg!!)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val staleJ = staleRowRatioBitmap(bmp)
        android.util.Log.i("UltraSlimVerify", "preview jpeg staleRatio=$staleJ")
        assertTrue(
            "Y smeared=$staleY jpeg smeared=$staleJ",
            staleY < 0.5 && staleJ < 0.5,
        )
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun jxlHdrPreviewRestoresTheAndroidDecodingExponent() = kotlinx.coroutines.runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val jpg = File.createTempFile("preview-gamma", ".jpg", ctx.cacheDir)
        val jxl = File.createTempFile("preview-gamma", ".jxl", ctx.cacheDir)
        val base = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888)
        val map = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            base.eraseColor(android.graphics.Color.GRAY)
            map.eraseColor(android.graphics.Color.LTGRAY)
            base.gainmap = android.graphics.Gainmap(map).apply {
                setRatioMax(4f, 4f, 4f)
                setGamma(0.5f, 0.5f, 0.5f)
                setDisplayRatioForFullHdr(4f)
            }
            jpg.outputStream().use { assertTrue(base.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }
            ParcelFileDescriptor.open(jpg, ParcelFileDescriptor.MODE_READ_ONLY).use { src ->
                ParcelFileDescriptor.open(jxl, ParcelFileDescriptor.MODE_READ_WRITE).use { dst ->
                    val result = UltraHdrBridge.nativeTranscode(src.fd, dst.fd, 3, 90, 60, 4, false, true, true, 2, 100, 1)
                    assertTrue(result.error, result.isSuccess && result.hasGainMap)
                }
            }
            val preview = app.ultraslim.ui.components.PreviewCache.loadHdr(ctx, jxl.path, 128)
            val gamma = requireNotNull(preview?.gainmap).gamma
            org.junit.Assert.assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), gamma, 0.001f)
        } finally {
            base.recycle(); map.recycle(); jpg.delete(); jxl.delete()
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 36)
    @Test fun jxlHdrPreviewUsesAlternateGainMapPrimaries() = kotlinx.coroutines.runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val jpg = File.createTempFile("preview-gamut", ".jpg", ctx.cacheDir)
        val jxl = File.createTempFile("preview-gamut", ".jxl", ctx.cacheDir)
        val base = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888)
        val map = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val p3 = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        try {
            base.eraseColor(android.graphics.Color.rgb(215, 80, 20))
            map.eraseColor(android.graphics.Color.rgb(70, 100, 180))
            base.gainmap = android.graphics.Gainmap(map).apply {
                setRatioMax(3f, 4f, 5f)
                setDisplayRatioForFullHdr(4f)
                setAlternativeImagePrimaries(p3)
            }
            jpg.outputStream().use { assertTrue(base.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }
            val source = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(jpg)) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            assertEquals(p3, source.gainmap?.alternativeImagePrimaries)
            source.recycle()

            for (reconstruct in listOf(false, true)) {
                ParcelFileDescriptor.open(jpg, ParcelFileDescriptor.MODE_READ_ONLY).use { src ->
                    ParcelFileDescriptor.open(jxl, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { dst ->
                        val result = UltraHdrBridge.nativeTranscode(
                            src.fd, dst.fd, 3, 90, 60, 4, false, true, true, 2, 100, 1,
                            jxlJpegReconstruction = reconstruct,
                        )
                        assertTrue(result.error, result.isSuccess && result.hasGainMap)
                    }
                }
                val preview = requireNotNull(app.ultraslim.ui.components.PreviewCache.loadHdr(ctx, jxl.path, 128))
                assertEquals("reconstruction = $reconstruct", p3, preview.gainmap?.alternativeImagePrimaries)
            }
        } finally {
            base.recycle()
            map.recycle()
            jpg.delete()
            jxl.delete()
        }
    }

    @Test fun transparentPreviewKeepsAlphaOnDisk() = kotlinx.coroutines.runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File.createTempFile("preview-alpha", ".jxl", ctx.cacheDir)
        val cache = app.ultraslim.ui.components.PreviewCache
        // 12x8 lossless JXL with alpha=(37*x+61*y) mod 256, from preview_probe.cpp.
        val source = android.util.Base64.decode(
                "/woBwUoIEBAAhAdLEsWCBVJgBZKRx9TpeIoKquRKbesi9gICBjykUhYAb3u6Eb+xzgHv07hhqRK/x54RN1r74JDkt+M2" +
                "dEX8BpCa3w7uzxtwA/7Kbwfn0434/VYKuAHH7zfgBkgo3YjfCmCAG+lD8JQiXQ/NvJl8oJx4yav1vrQ58VzgNKXoKWSP" +
                "kuG3cPvr39OBXXP0wntuKab5tme0t+R2MOi7fac0Q6MDc243jgFr2HK6w7rtzGWFxjvuuWinBDINpn+sFeHBLFCNV31n" +
                "GWHZCcKyFZvHBus36oaHk5yglC2ujGQa43vD2fPgLUfmlIuQbZ0ZHTT9BoowPJ0ETl1EdxFcgtEF100JIaZM99mnd5ut" +
                "1pPxdx/7tzcUUwd9X618dWNMy8UiAxy3xlqv+Bri8r6GWny286i0YwlOoqiJfTH45vae4nq6rtjn55xGzZXwqwxdUxyM" +
                "7sUDMU4sHXNEu40QjvgBdcVMkp3xdTChgciTGoQtPhId1NKZccw0PcWPPOha1KkaQgQ/6LACklBuSUgIAvRCL0QUIimu" +
                "jFYV4pHHgDkGOQYYZ4EJAghoGVqerSZBRJD7lXTEwRZFFENWrlJFFEGGW1CIFzRYia0aIGGFDCoKQtjSIhB8UoUnR2Qo" +
                "qAEiKYCAoGJD",
            android.util.Base64.DEFAULT,
        )
        try {
            input.writeBytes(source)
            val first = requireNotNull(cache.load(ctx, input.path, 128))
            assertEquals(98, android.graphics.Color.alpha(first.getPixel(1, 1)))
            val version = input.lastModified()
            cache.trimMemory()
            // Keep the size/mtime key stable but require a disk hit, since the replacement cannot decode.
            input.writeBytes(ByteArray(source.size))
            assertTrue(input.setLastModified(version))
            val fromDisk = requireNotNull(cache.load(ctx, input.path, 128))
            assertEquals(first.getPixel(1, 1), fromDisk.getPixel(1, 1))
            assertEquals(0, android.graphics.Color.alpha(fromDisk.getPixel(0, 0)))
        } finally {
            input.delete()
            cache.trimMemory()
        }
    }

    private fun staleRowRatio(plane: ByteArray, w: Int, h: Int): Double {
        var stale = 0
        for (y in 1 until h) {
            var sum = 0L
            var prevSum = 0L
            for (x in w / 2 until w) {
                sum += plane[y * w + x].toInt() and 0xFF
                prevSum += plane[(y - 1) * w + x].toInt() and 0xFF
            }
            val n = (w - w / 2)
            if (kotlin.math.abs(sum.toDouble() / n - prevSum.toDouble() / n) < 0.35) stale++
        }
        return stale.toDouble() / h
    }

    private fun staleRowRatioBitmap(bmp: android.graphics.Bitmap): Double {
        var stale = 0
        val px = IntArray(bmp.width)
        var prevMean = DoubleArray(3)
        for (y in 0 until bmp.height) {
            bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
            var r = 0.0; var g = 0.0; var b = 0.0
            for (x in bmp.width / 2 until bmp.width) {
                val p = px[x]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val n = (bmp.width - bmp.width / 2).toDouble()
            val mean = doubleArrayOf(r / n, g / n, b / n)
            if (y > 0) {
                val d = kotlin.math.abs(mean[0] - prevMean[0]) +
                    kotlin.math.abs(mean[1] - prevMean[1]) +
                    kotlin.math.abs(mean[2] - prevMean[2])
                if (d < 0.35) stale++
            }
            prevMean = mean
        }
        return stale.toDouble() / bmp.height
    }
}
