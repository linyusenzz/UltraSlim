package app.ultraslim

import android.graphics.Bitmap
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.FoldingFeature
import androidx.window.layout.FoldingFeature.Orientation
import androidx.window.layout.FoldingFeature.State
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.adaptive.*
import app.ultraslim.ui.theme.AppTheme
import app.ultraslim.ui.components.PreviewCache
import app.ultraslim.ui.components.UltraAsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import app.ultraslim.data.ConversionDefaults
import org.junit.*
import org.junit.Assert.*
import java.io.File

class AdaptiveUiTest {
    @get:Rule(order = 0) val windowLayout = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UltraSlimApp
    private var emulatorVerified = false
    private var originalDefaults: ConversionDefaults? = null
    private val ids = mutableListOf<Long>()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
    }
    private fun resize(width: Int, height: Int) {
        shell("wm size ${width}x$height")
        shell("wm density 160")
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode().boundsInRoot.width.toInt() == width }
        compose.waitForIdle()
    }
    @Before fun guard() {
        Assume.assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        emulatorVerified = true
        originalDefaults = runBlocking { app.settingsRepository.defaults.first() }
    }
    @After fun cleanup() {
        if (!emulatorVerified) return
        runBlocking {
            ids.forEach { app.database.historyDao().deleteById(it) }
            originalDefaults?.let { app.settingsRepository.setDefaults(it) }
        }
        shell("settings put system font_scale 1.0")
        shell("wm size reset"); shell("wm density reset")
    }
    private fun capture(name: String) {
        File(app.filesDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun nativePreviewReloadsWhenZoomOrHdrChanges() {
        val sample = File.createTempFile("zoom-preview", ".jpg", app.cacheDir)
        try {
            instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { input -> sample.outputStream().use { input.copyTo(it) } }
            var dimension by mutableIntStateOf(128)
            var hdr by mutableStateOf(false)
            compose.runOnUiThread {
                compose.activity.setContent {
                    AppTheme {
                        UltraAsyncImage(sample.path, "预览", ContentScale.Fit,
                            modifier = Modifier.size(240.dp), maxDim = dimension, forceSdr = !hdr, enableHdr = hdr)
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("预览").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { dimension = 512 }
            compose.waitUntil(10_000) { PreviewCache.peek(sample.path, 512) != null }
            // Force the next HDR request to fail. Reusing the previous SDR bitmap would hide this failure.
            assertTrue(sample.delete())
            compose.runOnIdle { hdr = true }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("图片不可访问", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } finally { sample.delete() }
    }

    @Test fun workspaceReflowsAndSettingsRemainReachable() {
        resize(420, 900)
        compose.onNodeWithTag("workspace-compact").assertExists()
        capture("adaptive-phone")
        resize(900, 1000)
        compose.onNodeWithTag("workspace-expanded").assertExists()
        compose.onNodeWithText("本次参数").assertIsDisplayed()
        capture("adaptive-fold")
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("默认转码偏好").assertIsDisplayed()
        compose.onNodeWithText("外观与显示").assertIsDisplayed()
        capture("adaptive-settings")
        compose.onNodeWithText("开源许可与声明").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("原创代码：GPL-3.0-only", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("原创代码：GPL-3.0-only", substring = true).assertIsDisplayed()
        capture("open-source-notices")
        compose.onNodeWithText("关闭").performClick()
        resize(420, 900)
        compose.onNodeWithTag("workspace-compact").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("UltraSlim").assertIsDisplayed()
    }
    @Test fun historyDetailSurvivesWindowChangesAndRecreation() = runBlocking {
        val image = File(app.cacheDir, "adaptive-sample.jpg")
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff176b52.toInt()) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        ids += app.database.historyDao().insert(HistoryEntity(displayName = "Adaptive sample.jpg", inputUri = image.path, outputUri = image.path,
            inputBytes = 4096000, outputBytes = 1024000, width = 128, height = 96, gainmapWidth = 0, gainmapHeight = 0,
            hadGainMap = false, format = "JPEG", inputFormat = "JPEG", durationMs = 100.0, maxContentBoost = 1f,
            createdAt = System.currentTimeMillis(), status = "DONE", saveState = "PENDING"))
        resize(1200, 900)
        compose.onNodeWithText("历史", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("detail-placeholder").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-row-${ids[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${ids[0]}"))
        compose.onNodeWithTag("history-row-${ids[0]}").performClick()
        compose.onNodeWithText("转换结果").assertIsDisplayed()
        compose.onNodeWithTag("history-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        capture("adaptive-history-detail")
        resize(420, 900)
        compose.onNodeWithText("转换结果").assertIsDisplayed()
        compose.onNodeWithTag("history-list").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("转换结果").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("history-list").assertIsDisplayed()
        image.delete()
        Unit
    }
@Test fun largeFontAndShortLandscapeKeepActionsReachable() {
    resize(360, 840)
    shell("settings put system font_scale 2.0")
    compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.9f }
    compose.onNodeWithText("选择照片").assertIsDisplayed()
    capture("adaptive-large-font")
    compose.onNodeWithContentDescription("设置").performClick()
    compose.onNodeWithText("JPEG").performScrollTo().assertIsDisplayed().performClick()
    capture("adaptive-settings-large-font")
    compose.onNodeWithContentDescription("返回").performClick()
    shell("settings put system font_scale 1.0")
    resize(900, 420)
    compose.onNodeWithText("选择照片").performScrollTo().assertIsDisplayed()
    capture("adaptive-landscape")
}
@Test fun reportedFoldingFeatureReflowsProductionScreens() {
    resize(1000, 1000)
    windowLayout.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(FoldingFeature(
        activity = compose.activity, size = 32, state = State.HALF_OPENED, orientation = Orientation.HORIZONTAL))))
    compose.waitUntil(10_000) {
        compose.onNodeWithTag("workspace-primary").fetchSemanticsNode().boundsInRoot.bottom <= 484
    }
    assertTrue(compose.onNodeWithTag("workspace-secondary").fetchSemanticsNode().boundsInRoot.top >= 516)
    capture("adaptive-tabletop-home")
    compose.onNodeWithContentDescription("设置").performClick()
    compose.waitUntil(10_000) { compose.onNodeWithTag("workspace-primary").fetchSemanticsNode().boundsInRoot.bottom <= 484 }
    assertTrue(compose.onNodeWithTag("workspace-secondary").fetchSemanticsNode().boundsInRoot.top >= 516)
    capture("adaptive-tabletop-settings")
    compose.onNodeWithContentDescription("返回").performClick()
    compose.onNodeWithText("历史", useUnmergedTree = true).performClick()
    compose.waitUntil(10_000) { compose.onNodeWithTag("history-list").fetchSemanticsNode().boundsInRoot.bottom <= 484 }
    capture("adaptive-tabletop-history")
    windowLayout.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(FoldingFeature(
        activity = compose.activity, size = 32, state = State.HALF_OPENED, orientation = Orientation.VERTICAL))))
    compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail-placeholder").fetchSemanticsNodes().isNotEmpty() }
    assertTrue(compose.onNodeWithTag("detail-placeholder").fetchSemanticsNode().boundsInRoot.left >= 516)
    assertTrue(compose.onNodeWithTag("history-list").fetchSemanticsNode().boundsInRoot.right <= 484)
    capture("adaptive-book-history")
}
    @Test fun tabletopAndBookKeepContentOutsideHinge() {
        resize(1000, 1000)
        var horizontal by mutableStateOf(true)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                AppTheme {
                    val density = LocalDensity.current.density
                    val fold = if (horizontal) WorkspaceFold(Rect(0f, 480f * density, 1000f * density, 520f * density), true)
                        else WorkspaceFold(Rect(480f * density, 0f, 520f * density, 1000f * density), false)
                    CompositionLocalProvider(LocalWorkspaceFolds provides listOf(fold)) {
                        AdaptiveWorkspace(primary = { Text("照片预览") }, secondary = { Text("参数与操作") })
                    }
                }
            }
        }
        compose.waitForIdle()
        val top = compose.onNodeWithTag("workspace-primary").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("workspace-secondary").fetchSemanticsNode().boundsInRoot
        assertTrue(top.bottom <= 480); assertTrue(bottom.top >= 520)
        capture("adaptive-tabletop-simulated")
        compose.runOnIdle { horizontal = false }
        val left = compose.onNodeWithTag("workspace-primary").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("workspace-secondary").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= 480); assertTrue(right.left >= 520)
    }
}
