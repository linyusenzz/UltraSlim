package app.ultraslim.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import app.ultraslim.data.ReplacementEntity
import app.ultraslim.convert.selectReplacementPage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.AutoReplacement
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.MediaStoreTrashHelper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReplacementReview(
    val count: Int,
    val checking: Boolean,
    val canKeep: Boolean,
    val onConfirm: () -> Unit,
    val onKeepOriginals: () -> Unit,
)

/** Publication is automatic; every system trash request requires an explicit review action. */
@Composable
internal fun AutoReplacementHost(): ReplacementReview? {
    val app = LocalContext.current.applicationContext as UltraSlimApp
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var activeIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    var approval by remember { mutableStateOf(emptySet<Long>()) }
    var reviewRows by remember { mutableStateOf(emptyList<ReplacementEntity>()) }
    var blockedForReview by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var keeping by remember { mutableStateOf(false) }
    var cancelReview by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ids = activeIds.toList()
        scope.launch {
            val rows = app.database.replacementDao().byIds(ids)
            val confirmed = result.resultCode == Activity.RESULT_OK
            if (!confirmed) {
                val remaining = app.database.replacementDao().ready().first()
                AutoReplacement.finish(app, remaining, "CANCELLED", "已取消待处理替换，原图保留")
            }
            val trashed = withContext(Dispatchers.IO) {
                if (!confirmed) emptyList()
                else {
                    val states = MediaStoreTrashHelper.originalStates(app, rows.map { Uri.parse(it.sourceUri) })
                    rows.filter { states[Uri.parse(it.sourceUri)] == MediaStoreTrashHelper.OriginalState.TRASHED }
                }
            }
            AutoReplacement.finish(app, trashed, "DONE", "原图已移入系统回收站")
            AutoReplacement.finish(app, rows - trashed.toSet(), "CANCELLED", "未确认替换，原图保留")
            activeIds = longArrayOf()
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (trashed.isEmpty()) "未完成替换，原图保留" else "${trashed.size} 张原图已移入系统回收站", Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(owner) {
        app.awaitReplacementRecovery()
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val blocked = combine(app.galleryPublishingAllowed, app.galleryPublicationRunning,
                app.conversionServiceRunning, app.historyRepository.pendingPublicationBatches) { allowed, publishing, service, pending ->
                // A pending publication also blocks review before DeferredOutputHost starts its loop.
                val awaitingSave = pending.isNotEmpty()
                !allowed || publishing || service || awaitingSave
            }
            combine(app.database.replacementDao().ready(), app.settingsRepository.defaults,
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME),
                snapshotFlow { activeIds.toList() to approval }, blocked) { rows, defaults, work, action, blocked ->
                Triple(rows, defaults.autoReplaceOriginals,
                    (action.first.isNotEmpty() || blocked || work.any { !it.state.isFinished }) to action.second)
            }.collect { (rows, enabled, action) ->
                val (busy, approved) = action
                reviewRows = rows
                blockedForReview = busy || !enabled
                if (rows.isEmpty()) { approval = emptySet(); return@collect }
                if (!enabled) {
                    approval = emptySet()
                    AutoReplacement.finish(app, rows, "CANCELLED", "自动替换已关闭，原图保留")
                    return@collect
                }
                if (busy) { approval = emptySet(); return@collect }
                if (approved.isEmpty() || approved != approval) return@collect
                // Consume this click exactly once. Remaining pages never inherit authorization.
                approval = emptySet()
                checking = true
                try {
                    val selected = rows.filter { it.historyId in approved }
                    // 设置/批次/原图状态按轮共享，避免 2000 行逐条重复 provider 与 DataStore 往返。
                    val pass = AutoReplacement.Pass(app)
                    // Resolve external deletions before hashing/decoding. Already trashed files may still be readable.
                    val verified = mutableListOf<app.ultraslim.data.ReplacementEntity>()
                    for (row in selected) {
                        if (cancelReview || !app.galleryPublishingAllowed.value || app.galleryPublicationRunning.value || app.conversionServiceRunning.value) return@collect
                        val state = withContext(Dispatchers.IO) { pass.originalState(Uri.parse(row.sourceUri)) }
                        when (state) {
                            MediaStoreTrashHelper.OriginalState.TRASHED -> AutoReplacement.finish(app, listOf(row), "DONE", "原图已在系统回收站，无需再次确认")
                            MediaStoreTrashHelper.OriginalState.UNAVAILABLE -> AutoReplacement.finish(app, listOf(row), "SKIPPED", "原图已删除或不可访问，不再请求替换")
                            MediaStoreTrashHelper.OriginalState.PRESENT -> try { AutoReplacement.validate(app, row, pass = pass); verified += row }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { AutoReplacement.finish(app, listOf(row), "SKIPPED", "原图保留：${e.message ?: "校验未通过"}") }
                        }
                    }
                    if (verified.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(app, "没有可替换的原图，处理结果可在历史详情查看", Toast.LENGTH_LONG).show()
                        }
                        return@collect
                    }
                    // Cancellation before launch leaves READY rows retryable on the next foreground entry.
                    try {
                        val request = withContext(Dispatchers.IO) { MediaStoreTrashHelper.prepareTrashRequest(app, verified.map { Uri.parse(it.sourceUri) }) }
                        val sender = request.sender ?: error(request.message ?: "无法创建回收站请求")
                        val requested = verified.filter { Uri.parse(it.sourceUri) in request.uris }
                        AutoReplacement.finish(app, verified - requested.toSet(), "SKIPPED", "原图状态已变化，不再请求替换")
                        if (cancelReview || !app.galleryPublishingAllowed.value || app.galleryPublicationRunning.value || app.conversionServiceRunning.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                        // The system dialog pauses our activity. Do not let that cancellation lose its result ids.
                        withContext(NonCancellable + Dispatchers.Main) {
                            activeIds = requested.map { it.historyId }.toLongArray()
                            app.database.replacementDao().finish(activeIds.toList(), "REQUESTED", "等待系统确认")
                            launcher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        activeIds = longArrayOf()
                        AutoReplacement.finish(app, verified, "SKIPPED", "${e.message ?: "无法发起系统确认"}，原图保留")
                    }
                } finally { checking = false }
            }
        }
    }
    if (reviewRows.isEmpty() || blockedForReview || activeIds.isNotEmpty()) return null
    val snapshot = reviewRows
    return ReplacementReview(
        count = snapshot.map { it.sourceUri }.distinct().size,
        checking = checking || keeping || approval.isNotEmpty(),
        canKeep = !keeping,
        onConfirm = {
            if (!checking && !keeping && approval.isEmpty() && activeIds.isEmpty()) {
                cancelReview = false
                approval = selectReplacementPage(snapshot).mapTo(mutableSetOf()) { it.historyId }
            }
        },
        onKeepOriginals = {
            if (!keeping && activeIds.isEmpty()) {
                cancelReview = true
                approval = emptySet()
                keeping = true
                scope.launch {
                    try { AutoReplacement.finish(app, snapshot, "CANCELLED", "已选择保留原图，不再自动请求替换") }
                    finally { keeping = false }
                }
            }
        },
    )
}
