package app.ultraslim

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.history.historyDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class HistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
    private val ids = mutableListOf<Long>()
    private lateinit var file: File
    private val now = System.currentTimeMillis()

    @Before fun seed() = runBlocking {
        file = File(app.cacheDir, "history-ui-fixture.jpg")
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff386b81.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        for (state in listOf("SAVED", "PENDING", "FAILED")) {
            ids += app.database.historyDao().insert(HistoryEntity(
                displayName = "UI-$state.jpg", inputUri = file.path, outputUri = file.path,
                inputBytes = 4096000, outputBytes = 2048000, width = 96, height = 96,
                gainmapWidth = 0, gainmapHeight = 0, hadGainMap = false, format = "JPEG", inputFormat = "JPEG",
                durationMs = 100.0, maxContentBoost = 1f, createdAt = now + ids.size, status = "DONE", saveState = state))
        }
        compose.onNodeWithText("历史", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-date-${historyDate(now)}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${ids.last()}"))
        Unit
    }
    @After fun cleanup() = runBlocking {
        ids.forEach { app.database.historyDao().deleteById(it) }
        if (::file.isInitialized) file.delete()
    }
    @Test fun selectionActionsAndFilteringStayConsistent() {
        compose.onNodeWithText("选择", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${ids[0]}"))
        compose.onNodeWithTag("history-row-${ids[0]}").performClick()
        compose.onNodeWithText("已选择 1 张").assertExists()
        compose.onAllNodesWithText("保存 1 张").assertCountEquals(0)
        compose.onNodeWithText("分享").assertIsEnabled()
        compose.onNodeWithText("删除记录").assertIsEnabled()
        val bar = compose.onNodeWithTag("history-selection-bar").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.width, bar.width, 1f)
        File(app.filesDir, "history-selection.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("history-list").performScrollToIndex(0)
        compose.onNodeWithText("待保存", useUnmergedTree = true).performClick()
        compose.onNodeWithText("已选择 0 张").assertExists()
        compose.onNodeWithText("分享").assertIsNotEnabled()
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${ids[1]}"))
        compose.onNodeWithTag("history-row-${ids[1]}").performClick()
        compose.onNodeWithText("保存 1 张").assertExists()
        File(app.filesDir, "history-pending.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("history-list").performScrollToIndex(0)
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("已选择 0 张").assertExists()
    }
    @Test fun dateDeletionIncludesRecordsBeyondTheLoadedPage() {
        runBlocking {
            val template = app.database.historyDao().byId(ids.first())!!
            repeat(105) { ids += app.database.historyDao().insert(template.copy(id = 0, createdAt = now - 1000)) }
        }
        compose.onNodeWithTag("history-date-${historyDate(now)}").performScrollTo().performTouchInput { swipeRight() }
        compose.onNodeWithText("删除 108 条记录？").assertExists()
        compose.onNodeWithText("取消").performClick()
        runBlocking { ids.forEach { assertNotNull(app.database.historyDao().byId(it)) } }
    }

    @Test fun selectionsSurviveWindowNavigationAndFilterResetsToLatest() {
        runBlocking {
            val template = app.database.historyDao().byId(ids.first())!!
            app.database.withTransaction {
                repeat(600) { ids += app.database.historyDao().insert(template.copy(id = 0, createdAt = now - 1000)) }
            }
        }
        compose.onNodeWithText("选择", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${ids.first()}"))
        compose.onNodeWithTag("history-row-${ids.first()}").performClick()
        compose.onNodeWithText("已选择 1 张").assertExists()
        repeat(4) { page ->
            compose.onNodeWithTag("history-list").performScrollToNode(hasText("加载更多记录"))
            compose.onNodeWithText("加载更多记录").performClick()
            compose.waitUntil(10_000) {
                compose.onNodeWithTag("history-list").fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.StateDescription) == "已显示 ${(page + 2) * 100} 条记录"
            }
        }
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("查看更早记录"))
        compose.onNodeWithText("查看更早记录").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("返回最新记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已选择 1 张").assertExists()
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${ids[10]}"))
        compose.onNodeWithTag("history-row-${ids[10]}").performClick()
        compose.onNodeWithText("已选择 2 张").assertExists()
        compose.onNodeWithText("删除记录").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("删除 2 条记录？").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("history-list").performScrollToIndex(0)
        compose.onNodeWithText("已保存", useUnmergedTree = true).performClick()
        compose.onNodeWithText("已选择 0 张").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("返回最新记录").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${ids.first()}"))
        compose.onNodeWithTag("history-row-${ids.first()}").assertExists()
    }
    @Test fun rightSwipeDateRequestsConfirmationAndCancelKeepsRecords() {
        val tag = "history-date-${historyDate(now)}"
        compose.onNodeWithTag(tag).performScrollTo().performTouchInput { swipeRight() }
        compose.onNodeWithText("删除 3 条记录？").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag(tag).assertExists()
        runBlocking { ids.forEach { assertNotNull(app.database.historyDao().byId(it)) } }
        compose.onNodeWithTag(tag).performTouchInput { swipeRight() }
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { runBlocking { ids.all { app.database.historyDao().byId(it) == null } } }
    }
}
