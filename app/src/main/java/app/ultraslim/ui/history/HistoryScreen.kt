package app.ultraslim.ui.history

import app.ultraslim.ui.components.appTopBarColors
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.ultraslim.ui.adaptive.AdaptiveWorkspace
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import app.ultraslim.ui.theme.Motion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.convert.MediaStoreTrashHelper
import app.ultraslim.convert.OutputStore
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.*
import app.ultraslim.util.*
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(onOpenDetail: (Long) -> Unit, onSelectingChange: (Boolean) -> Unit = {}, activeHistoryId: Long? = null) {
    val vm: HistoryViewModel = viewModel(factory = appViewModelFactory { HistoryViewModel(it) })
    val state by vm.uiState.collectAsStateWithLifecycle()
    val deleting by vm.deleteCandidates.collectAsStateWithLifecycle()
    val trashableOriginals by vm.trashableOriginals.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // rememberSaveable：进详情再返回（或深色模式切换）后筛选/选择/折叠不丢。
    var filter by rememberSaveable { mutableStateOf("全部") }
    var selected by rememberSaveable(
        stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(setOf<Long>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var confirmBusyLeave by remember { mutableStateOf(false) }
    var collapsedDates by rememberSaveable(
        stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<LocalDate>()) }
    var lastTrashCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(filter) { vm.setFilter(historyFilterSaveState(filter)) }
    LaunchedEffect(selecting) { onSelectingChange(selecting) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.onSelectedOriginalsTrashed(lastTrashCount)
        }
    }
    fun finishSelection() { selecting = false; selected = emptySet() }
    // busy 期间返回键不再直接退出页面：先问要不要停止当前操作。
    BackHandler(enabled = selecting || state.busyNow) {
        if (state.busyNow) confirmBusyLeave = true else finishSelection()
    }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    val visible = remember(state.items, filter) { state.items.filter { it.matchesHistoryFilter(filter) } }
    val selectedItems = remember(visible, selected) { visible.filter { it.id in selected } }
    val pendingCount = remember(selectedItems) { selectedItems.count { it.saveState != "SAVED" } }
    LaunchedEffect(visible) {
        selected = selected.intersect(visible.map { it.id }.toSet())
        vm.ensureTrashableResolved(visible)
    }
    val groups = remember(visible) { visible.groupBy { historyDate(it.createdAt) } }
    val railMode = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2()) == NavigationSuiteType.WideNavigationRailCollapsed
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(colors = appTopBarColors(), scrollBehavior = if (selecting) null else scrollBehavior, title = {
                Column {
                    Text(if (selecting) "已选择 ${selectedItems.size} 张" else "成品库", style = MaterialTheme.typography.titleLarge)
                    Text(if (selecting) "点击图片勾选，可批量分享或删除" else "每一次减负，都看得见",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, actions = {
                TextButton(onClick = { if (selecting) finishSelection() else selecting = true }, enabled = !state.busyNow) {
                    Text(if (selecting) "完成" else "选择")
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !state.busyNow) { Icon(Icons.Rounded.MoreVert, "历史操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (selecting) DropdownMenuItem(text = { Text("删除所选记录") }, enabled = selectedItems.isNotEmpty(),
                            onClick = { menu = false; vm.requestDelete(selectedItems.map { it.id }.toSet()) })
                        else DropdownMenuItem(text = { Text("清空历史记录") }, enabled = state.items.isNotEmpty(),
                            onClick = { menu = false; confirmClear = true })
                    }
                }
            })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(visible = selecting, modifier = Modifier.fillMaxWidth()) {
                Surface(modifier = Modifier.fillMaxWidth().testTag("history-selection-bar"), tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth()
                        .then(if (railMode) Modifier.navigationBarsPadding() else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pendingCount > 0) {
                                Button(onClick = { vm.saveSelected(selectedItems.map { it.id }.toSet()) }, enabled = !state.busyNow) {
                                    Text(if (state.busyNow) "处理中…" else "保存 $pendingCount 张")
                                }
                            }
                            if (MediaStoreTrashHelper.isTrashSupported) {
                                val trashableCount = selectedItems.count { it.id in trashableOriginals }
                                if (trashableCount > 0) {
                                    FilledTonalButton(
                                        onClick = {
                                            lastTrashCount = trashableCount
                                            scope.launch {
                                                val sender = vm.requestTrashSelected(selectedItems.map { it.id }.toSet())
                                                if (sender != null) trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                            }
                                        },
                                        enabled = !state.busyNow,
                                    ) {
                                        Icon(Icons.Rounded.DeleteSweep, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("原图移至回收站 ($trashableCount)")
                                    }
                                }
                            }
                            OutlinedButton(onClick = {
                                if (!OutputStore.share(context, selectedItems)) scope.launch { snackbar.showSnackbar("分享失败，文件不可访问") }
                            }, enabled = !state.busyNow && selectedItems.isNotEmpty()) { Text("分享") }
                            OutlinedButton(onClick = { vm.requestDelete(selectedItems.map { it.id }.toSet()) },
                                enabled = !state.busyNow && selectedItems.isNotEmpty()) { Text("删除记录") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        AdaptiveWorkspace(modifier = Modifier.padding(padding).consumeWindowInsets(padding), primary = { expanded ->
        LazyColumn(Modifier.fillMaxSize().testTag("history-list"),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp,
                bottom = 20.dp + if (!selecting) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!expanded) item { StatsCard(state.stats) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "待保存", "已保存", "保存失败").forEach { label ->
                        FilterChip(selected = filter == label, enabled = !state.busyNow,
                            onClick = { filter = label; selected = emptySet() }, label = { Text(label) })
                    }
                }
                if (selecting) {
                    val allSelected = visible.isNotEmpty() && visible.all { it.id in selected }
                    TextButton(enabled = !state.busyNow && visible.isNotEmpty(), onClick = {
                        selected = if (allSelected) emptySet() else visible.map { it.id }.toSet()
                    }) { Text(if (allSelected) "取消全选" else "全选当前页") }
                } else if (visible.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "点击日期可折叠 · 右滑可删除当天",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (groups.size > 1) {
                            val allCollapsed = collapsedDates.containsAll(groups.keys)
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                onClick = {
                                    collapsedDates = if (allCollapsed) emptySet<LocalDate>() else groups.keys
                                }
                            ) {
                                Text(if (allCollapsed) "展开全部" else "折叠全部", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) item { EmptyState(Icons.Rounded.History, "暂无此类记录", "完成的成品和保存状态会显示在这里") }
            // 长操作进度：独立于选择栏，批量保存/删除/清空都显示 x/y 且可停止。
            state.busy?.let { b ->
                item(key = "busy-banner") {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (b.total > 0) LinearProgressIndicator(progress = { b.done.toFloat() / b.total }, modifier = Modifier.weight(1f))
                            else LinearProgressIndicator(Modifier.weight(1f))
                            Text("${b.label} ${b.done}/${b.total}", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = vm::cancelBusy) { Text("停止") }
                        }
                    }
                }
            }
            // 行级虚拟化：展开态的每条记录是独立 item；单个自然日 500 张也不会一次性组合。
            groups.forEach { (date, records) ->
                val isCollapsed = date in collapsedDates
                item(key = "date-group-$date", contentType = "history-header") {
                    app.ultraslim.ui.components.CommitSwipeRow(
                        modifier = Modifier.fillMaxWidth().animateItem(placementSpec = Motion.itemPlacement()).testTag("history-date-$date"),
                        enabled = !selecting && !state.busyNow,
                        toRight = true,
                        onCommit = { vm.requestDeleteDate(date, filter) },
                        background = {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.DeleteOutline, null)
                                    Spacer(Modifier.width(8.dp)); Text("删除当天记录")
                                }
                            }
                        }) {
                        Surface(
                            color = if (isCollapsed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BatchGroupHeader(
                                title = historyDateTitle(date),
                                count = records.size,
                                savedBytes = records.sumOf { it.inputBytes - it.outputBytes },
                                collapsible = true,
                                collapsed = isCollapsed,
                                onToggleCollapse = {
                                    collapsedDates = if (isCollapsed) collapsedDates - date else collapsedDates + date
                                },
                                action = {
                                    if (selecting) {
                                        val ids = records.map { it.id }.toSet()
                                        val allSelected = selected.containsAll(ids)
                                        TextButton(enabled = !state.busyNow, onClick = {
                                            selected = if (allSelected) selected - ids else selected + ids
                                            if (isCollapsed) collapsedDates = collapsedDates - date
                                        }) {
                                            Text(if (allSelected) "取消当天" else "选择当天")
                                        }
                                    } else IconButton(onClick = { vm.requestDeleteDate(date, filter) }, enabled = !state.busyNow) {
                                        Icon(Icons.Rounded.DeleteOutline, "删除${historyDateTitle(date)}的记录")
                                    }
                                }
                            )
                        }
                    }
                }
                if (!isCollapsed) {
                    items(records, key = { it.id }, contentType = { "history-row" }) { entity ->
                        val checked = entity.id in selected
                        HistoryRow(
                            entity = entity,
                            selecting = selecting,
                            checked = checked,
                            active = entity.id == activeHistoryId,
                            enabled = !state.busyNow,
                            onClick = {
                                if (selecting) selected = if (checked) selected - entity.id else selected + entity.id
                                else onOpenDetail(entity.id)
                            },
                            modifier = Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.itemPlacement(), fadeOutSpec = Motion.exit(Motion.Fast)),
                        )
                    }
                }
            }
            if (state.items.size < state.convertedCount) item {
                TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多记录") }
            }
        }
        }, secondary = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatsCard(state.stats)
                Text("成品一览", style = MaterialTheme.typography.headlineSmall)
                Text("选择一张照片查看画质对比；点击「选择」可批量操作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }
    if (deleting.isNotEmpty()) AlertDialog(onDismissRequest = vm::cancelDelete,
        title = { Text("删除 ${deleting.size} 条记录？") },
        text = { Text("删除这些转换记录及未保存的应用内成品。已保存到相册的图片和原图不受影响。") },
        confirmButton = { TextButton(onClick = { selected = selected - deleting.map { it.id }.toSet(); vm.deleteConfirmed() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("取消") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空历史？") },
        text = { Text("删除全部转换记录和未保存的应用内成品。已保存到相册的图片不受影响。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; finishSelection(); vm.clearAll() }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    if (confirmBusyLeave) AlertDialog(onDismissRequest = { confirmBusyLeave = false },
        title = { Text("要停止当前操作吗？") },
        text = { Text(state.busy?.let { "${it.label} ${it.done}/${it.total}。停止后已完成的保持不变，未处理的会被跳过。" } ?: "") },
        confirmButton = { TextButton(onClick = { confirmBusyLeave = false; vm.cancelBusy() }) { Text("停止", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmBusyLeave = false }) { Text("继续") } })
}

@Composable
internal fun HistoryRow(entity: HistoryEntity, selecting: Boolean, checked: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().testTag("history-row-${entity.id}"),
        border = if (checked || active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            UltraAsyncImage(entity.outputUri, null, ContentScale.Crop, Modifier.padding(start = if (selecting) 8.dp else 0.dp)
                .size(72.dp).clip(RoundedCornerShape(14.dp)), 128)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entity.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text("${entity.format} · ${entity.width} × ${entity.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("${formatBytes(entity.inputBytes)} → ${formatBytes(entity.outputBytes)}", style = MaterialTheme.typography.titleSmall)
                Text("体积 ${formatSavedPercent(entity.inputBytes, entity.outputBytes)}", style = MaterialTheme.typography.labelMedium,
                    color = if (entity.outputBytes > entity.inputBytes) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(when (entity.saveState) { "SAVED" -> "已保存到相册"; "FAILED" -> "保存失败 · 可重试 / 导出"; else -> "待保存 · 可直接分享" },
                    style = MaterialTheme.typography.labelSmall, color = if (entity.saveState == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}
