package app.ultraslim

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.FolderImages
import app.ultraslim.convert.InputStore
import app.ultraslim.convert.QueueStatus
import app.ultraslim.ui.home.HomeEvent
import app.ultraslim.ui.home.HomeViewModel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.*
import org.junit.Assert.*

class FolderImportTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UltraSlimApp
    private val tree = DocumentsContract.buildTreeDocumentUri("${instrumentation.context.packageName}.documents", "root")
    private val store = ViewModelStore()
    private lateinit var vm: HomeViewModel
    private var originalIds = emptySet<String>()
    @Before fun setup() = runBlocking {
        Assume.assumeTrue(android.os.Build.MODEL.contains("sdk"))
        // Grant through the test provider UID, just as DocumentsUI grants the chosen tree.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.context.packageName}/app.ultraslim.FolderGrantActivity --es target ${app.packageName}"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        app.conversionRepository.awaitReady()
        originalIds = app.conversionRepository.queue.value.map { it.id }.toSet()
        vm = withContext(Dispatchers.Main) { HomeViewModel(app).also { store.put("folder", it) } }
    }
    @After fun cleanup() = runBlocking {
        if (::vm.isInitialized) {
            withContext(Dispatchers.Main) { store.clear() }
            val added = app.conversionRepository.queue.value.filter { it.id !in originalIds }
            app.conversionRepository.removeIds(added.map { it.id })
            app.conversionRepository.flush()
            added.forEach { File(app.filesDir, "inputs/${it.id}").delete() }
        }
        runCatching { app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }
    private suspend fun import(recursive: Boolean) = coroutineScope {
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(120_000) { vm.events.first { it is HomeEvent.Info && it.message.startsWith("已导入") } as HomeEvent.Info }
        }
        withContext(Dispatchers.Main) { vm.importFolder(tree, recursive) }
        result.await().message
    }
    @Test fun recursivelyImportsBeyondOneThousandAndDeduplicatesCycles() = runBlocking {
        val shallow = FolderImages.images(app.contentResolver, tree, false).toList()
        assertEquals(1001, shallow.size)
        val message = import(true)
        assertTrue(message, message.startsWith("已导入 1003 张照片"))
        assertTrue(message, message.contains("1 个子文件夹无法读取"))
        val added = app.conversionRepository.queue.value.filter { it.id !in originalIds }
        assertEquals(1003, added.size)
        assertTrue(added.all { it.status == QueueStatus.READY && it.probe?.isSuccess == true })
        assertTrue(added.any { it.displayName == "grandchild.jpg" })
        assertTrue("Folder sources must retain their per-document metadata fallback", added.all { it.sizeBytes > 0 })
        assertTrue("A tree grant must avoid a private copy per image", added.all { Uri.parse(it.uri).authority == tree.authority })
        assertEquals(1, app.contentResolver.persistedUriPermissions.count { it.uri.authority == tree.authority })
        assertTrue(import(true).startsWith("已导入 0 张照片"))
    }
    @Test fun singlePhotoGrantsSurviveCrossingTheCleanupThresholdMidBatch() = runBlocking {
        val testPackage = instrumentation.context.packageName
        val uris = (0 until 370).map { DocumentsContract.buildDocumentUri("$testPackage.documents", "photo-$it") }
        fun permissions(action: String) {
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n $testPackage/app.ultraslim.FolderGrantActivity --es target ${app.packageName} --es singlePhotos $action"
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        try {
            permissions("grant")
            coroutineScope {
                val finished = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(120_000) { vm.events.first { it is HomeEvent.Info && it.message.startsWith("已导入") } }
                }
                withContext(Dispatchers.Main) { vm.addItems(uris) }
                finished.await()
            }
            val persisted = app.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
            assertTrue("Missing persisted grants: ${uris.count { it !in persisted }}", persisted.containsAll(uris))
            val queued = app.conversionRepository.queue.value.map { it.uri }.toSet()
            assertTrue(queued.containsAll(uris.map(Uri::toString)))
        } finally { permissions("revoke") }
    }

    @Test fun albumImportDoesNotTruncateAtOneThousand() = runBlocking {
        val resolver = app.contentResolver
        val created = mutableListOf<Uri>()
        val folder = "Pictures/FolderImportTest-${java.util.UUID.randomUUID()}/"
        val jpeg = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "photo-0"))!!.use { it.readBytes() }
        try {
            repeat(1001) { index ->
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$index.jpg")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, folder)
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    })!!
                created += uri
                resolver.openOutputStream(uri)!!.use { it.write(jpeg) }
                resolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            val bucket = resolver.query(created.first(), arrayOf(android.provider.MediaStore.Images.Media.BUCKET_ID), null, null, null)!!.use {
                check(it.moveToFirst()); it.getString(0)
            }
            val message = coroutineScope {
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(120_000) { vm.events.first { it is HomeEvent.Info && it.message.startsWith("已导入") } as HomeEvent.Info }
                }
                withContext(Dispatchers.Main) { vm.importAlbum(HomeViewModel.AlbumBucket(bucket, "test", 1001, created.first().toString(), 0)) }
                result.await().message
            }
            assertEquals("已导入 1001 张照片", message)
            val added = app.conversionRepository.queue.value.filter { it.id !in originalIds }
            assertEquals(1001, added.size)
            assertEquals((0..1000).map { "$it.jpg" }.toSet(), added.map { it.displayName }.toSet())
            assertTrue("Album cursor must retain each source's size", added.all { it.sizeBytes == jpeg.size.toLong() })
            assertTrue(added.all { it.status == QueueStatus.READY && it.probe?.isSuccess == true })
        } finally { created.forEach { resolver.delete(it, null, null) } }
    }
    @Test fun cancellationKeepsCommittedBatchesAndDoesNotImportTheWholeTree() = runBlocking {
        withContext(Dispatchers.Main) { vm.importFolder(tree) }
        withTimeout(30_000) { app.conversionRepository.queue.first { items -> items.count { it.id !in originalIds } >= 32 } }
        withContext(Dispatchers.Main) { vm.cancelImport() }
        withTimeout(10_000) { vm.importing.first { !it } }
        withTimeout(30_000) { app.conversionRepository.queue.first { items -> items.filter { it.id !in originalIds }.all { it.status == QueueStatus.READY } } }
        val count = app.conversionRepository.queue.value.count { it.id !in originalIds }
        assertTrue("Cancelled count $count", count in 32..96)
    }
}
