package app.ultraslim

import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.encode
import java.io.File
import org.junit.Test
import org.junit.Assert.*

class NativeCodecTest {
    @Test fun compatibilityValidationRejectsAnSdrOnlyOutput() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "missing-gainmap.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            try {
                app.ultraslim.convert.HdrOutputValidation.requireReadableGainmap(file)
                fail("An SDR bitmap must not be accepted as verified HDR")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("HDR 增益图"))
            }
        } finally { bitmap.recycle(); file.delete() }
    }
    /** Optional camera sample placed in the QA app's private files directory. */
    @Test fun avifCompatibilityCameraSampleThroughJni() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("avifCameraSample") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "avif-camera-source.jpg")
        assertTrue("Stage the camera sample in the QA app first", source.isFile)
        val output = File(context.filesDir, "avif-camera-compatible.avif")
        // Exercise the same persisted options and JNI argument as ConversionWorker.
        val options = app.ultraslim.data.decodeDefaults(app.ultraslim.data.ConversionDefaults(avifGainmapCompatibility = true).encode())
        assertTrue(options.avifGainmapCompatibility)
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { b ->
                val result = UltraHdrBridge.nativeTranscode(a.fd, b.fd, options.format.codecId, options.quality,
                    options.gainmapQuality, options.gainmapScale, options.multiChannelGainmap, options.keepExif,
                    options.allowSdr, options.speedLevel, options.resizePercent, 4, options.keepLocation,
                    retrySmaller = options.onlySmaller, avifGainmapCompatibility = options.avifGainmapCompatibility)
                assertTrue(result.error, result.isSuccess)
                val input = UltraHdrBridge.nativeProbe(a.fd)
                val probe = UltraHdrBridge.nativeProbe(b.fd)
                assertTrue(probe.isSuccess && probe.hasGainMap)
                assertEquals(input.gainmapWidth * input.gainmapHeight, probe.gainmapWidth * probe.gainmapHeight)
                android.util.Log.i("UltraSlimVerify", "compatible AVIF bytes=${result.outputBytes} map=${probe.gainmapWidth}x${probe.gainmapHeight}")
            }
        }
        // Platform readability is checked separately: older emulators can encode
        // the same bytes while their system decoder has no AVIF gain-map support.
    }

    /** Optional camera sample placed in the QA app's private files directory. */
    @Test fun heicCompatibilityCameraSampleThroughJni() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("heicCameraSample") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "heic-camera-source.jpg")
        assertTrue("Stage the camera sample in the QA app first", source.isFile)
        val output = File(context.filesDir, "heic-camera-compatible.heic")
        // Exercise the same persisted options and JNI argument as ConversionWorker.
        val options = app.ultraslim.data.decodeDefaults(app.ultraslim.data.ConversionDefaults(format = app.ultraslim.model.OutputFormat.HEIC, heicGainmapCompatibility = true).encode())
        assertTrue(options.heicGainmapCompatibility)
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { b ->
                val result = UltraHdrBridge.nativeTranscode(a.fd, b.fd, options.format.codecId, options.quality,
                    options.gainmapQuality, options.gainmapScale, options.multiChannelGainmap, options.keepExif,
                    options.allowSdr, options.speedLevel, options.resizePercent, 4, options.keepLocation,
                    retrySmaller = options.onlySmaller, heicGainmapCompatibility = options.heicGainmapCompatibility)
                assertTrue(result.error, result.isSuccess)
                val input = UltraHdrBridge.nativeProbe(a.fd)
                val probe = UltraHdrBridge.nativeProbe(b.fd)
                assertTrue(probe.isSuccess && probe.hasGainMap)
                assertEquals(input.gainmapWidth * input.gainmapHeight, probe.gainmapWidth * probe.gainmapHeight)
                android.util.Log.i("UltraSlimVerify", "compatible HEIC bytes=${result.outputBytes} map=${probe.gainmapWidth}x${probe.gainmapHeight}")
            }
        }
        // Platform readability is checked separately: older emulators can encode
        // the same bytes while their system decoder has no HEIC gain-map support.
    }

    @Test fun avifCameraPlatformValidationMatchesDeviceCapability() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedAvifPlatformHdr")
        org.junit.Assume.assumeTrue(expected == "true" || expected == "false")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.filesDir, "avif-camera-compatible.avif")
        assertTrue("Run the camera JNI test first", output.isFile)
        var readable = true
        try { app.ultraslim.convert.HdrOutputValidation.requireReadableGainmap(output) }
        catch (e: IllegalStateException) { readable = false }
        assertEquals(expected == "true", readable)
    }

    @Test fun alignedGraphicsJniLoads() {
        // JNI_OnLoad registers the unchanged 1.0.1 API on the rebuilt native component.
        System.loadLibrary("androidx.graphics.path")
    }

    @Test fun hdrRoundtripAllFormatsThroughJni() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir,"native-regression").apply { mkdirs() }
        try {
            val source = File(dir,"source.jpg")
            instrumentation.context.assets.open("apple_gainmap_new.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
            for (codec in 0..3) {
                var input = source
                repeat(2) { pass ->
                    val output = File(dir,"out-$codec-$pass")
                    ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
                        ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { b ->
                            val result = UltraHdrBridge.nativeTranscode(a.fd,b.fd,codec,85,60,4,true,true,true,2,50,2,false)
                            assertTrue("codec=$codec pass=$pass ${result.error}",result.isSuccess)
                            val probe = UltraHdrBridge.nativeProbe(b.fd)
                            assertTrue(probe.error,probe.isSuccess); assertTrue(probe.hasGainMap); assertTrue(probe.hasExif)
                            assertEquals("codec=$codec pass=$pass", codec, probe.container)
                            assertEquals(result.gainmapWidth,probe.gainmapWidth)
                            assertNotNull(UltraHdrBridge.nativeDecodePreview(b.fd,128))
                        }
                    }
                    input = output
                }
            }
        } finally { dir.deleteRecursively() }
    }
    /** Opt-in regression against only the photos the user has already queued. No history/gallery writes. */
    @Test fun selectedPhotoPreservation() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("selectedPhotos") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        app.conversionRepository.awaitReady()
        val dir = File(app.cacheDir, "selected-preservation").apply { mkdirs() }
        val items = app.conversionRepository.queue.value.filter { it.probe?.hasGainMap == true }.take(3)
        assertTrue("No selected HDR photos", items.isNotEmpty())
        for ((index, item) in items.withIndex()) {
            val source = File(dir, "source-$index.${if(item.probe!!.container == 0) "jpg" else "heic"}")
            app.contentResolver.openInputStream(android.net.Uri.parse(item.uri))!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            val output = File(dir, "output-$index.avif")
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { b ->
                    val result = UltraHdrBridge.nativeTranscode(a.fd, b.fd, 2, 82, 1, 128, false, true, true, 1, 100, 2, true)
                    assertTrue(result.error, result.isSuccess)
                    val input = UltraHdrBridge.nativeProbe(a.fd)
                    val probe = UltraHdrBridge.nativeProbe(b.fd)
                    assertTrue(probe.isSuccess && probe.hasGainMap)
                    assertEquals(input.gainmapWidth.toLong() * input.gainmapHeight, probe.gainmapWidth.toLong() * probe.gainmapHeight)
                    android.util.Log.i("UltraSlimVerify", "sample=$index input=${result.inputBytes} output=${result.outputBytes} map=${probe.gainmapWidth}x${probe.gainmapHeight} ms=${result.durationMs}")
                }
            }
        }
    }

}
