package app.ultraslim.ui.detail

import app.ultraslim.ui.components.appTopBarColors
import app.ultraslim.ui.adaptive.AdaptiveWorkspace
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import app.ultraslim.convert.OutputStore
import app.ultraslim.model.OutputFormat
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.BadgeChip
import app.ultraslim.ui.components.InfoRow
import app.ultraslim.ui.components.SectionHeader
import app.ultraslim.ui.components.UltraAsyncImage
import app.ultraslim.ui.theme.Motion
import app.ultraslim.util.formatBoost
import app.ultraslim.util.formatBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.ultraslim.util.formatDuration
import app.ultraslim.util.formatResolution
import app.ultraslim.util.formatSavedPercent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(historyId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(factory = appViewModelFactory { DetailViewModel(it, historyId) })
    val state by vm.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAppleExport by remember { mutableStateOf(false) }
    val appleExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportAppleLiveTo) }
    if (showAppleExport) AlertDialog(
        onDismissRequest = { showAppleExport = false },
        title = { Text("导出 Apple Live Photo") },
        text = { Text("将 HEIC 成品中的照片和动态视频打包。请在 Mac 上用 UltraSlim Live 打开 ZIP，检查预览后导入「照片」。开启 iCloud 照片即可同步到 iPhone。\n\n直接将 ZIP 或 HEIC 保存到 iPhone 不会自动变为 Live Photo。") },
        confirmButton = { TextButton(onClick = { showAppleExport = false; appleExport.launch("AppleLive_${state.entity?.displayName?.substringBeforeLast('.') ?: "photo"}.zip") }) { Text("导出给 Mac") } },
        dismissButton = { TextButton(onClick = { showAppleExport = false }) { Text("取消") } },
    )
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::relinkSource) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OutputFormat.parse(state.entity?.format).mimeType)) { it?.let(vm::exportTo) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.onOriginalTrashed()
        }
    }
    val trashScope = rememberCoroutineScope()
    val trashOriginal: () -> Unit = {
        trashScope.launch {
            val sender = vm.requestTrashOriginal()
            if (sender != null) {
                trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
                DetailEvent.Deleted -> onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(colors = appTopBarColors(),
                title = { Text("转换结果", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (LocalListDetailSceneScope.current?.scaffoldTransitionScope?.scaffoldStateTransition?.targetState?.let { it[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded && it[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded } != true) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.entity != null) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("导出到文件…") },
                                    leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                                    onClick = {
                                        menu = false
                                        export.launch(state.entity?.displayName ?: "image.heic")
                                    }
                                )
                                if (state.entity?.format.equals("HEIC", ignoreCase = true)) DropdownMenuItem(
                                    text = { Text("导出 Apple Live Photo…") },
                                    enabled = !state.saving,
                                    leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                                    onClick = { menu = false; showAppleExport = true },
                                )
                                if (state.canTrashOriginal) {
                                    DropdownMenuItem(
                                        text = { Text("原图移至相册回收站…") },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                        onClick = {
                                            menu = false
                                            trashOriginal()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("重新关联原图…") },
                                    leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                    onClick = {
                                        menu = false
                                        sourcePicker.launch(arrayOf("image/*"))
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("删除记录", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menu = false
                                        showDeleteConfirm = true
                                    }
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            state.entity?.let { entity ->
                val saved = entity.outputUri.startsWith("content://")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!saved && android.os.Build.VERSION.SDK_INT >= 29) {
                            Button(
                                onClick = { vm.saveToAlbum() },
                                enabled = !state.saving,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.saving) "保存中…" else "保存到相册")
                            }
                        } else {
                            Button(
                                onClick = { viewInAlbum(context, entity) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("在相册中查看")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (!OutputStore.share(context, listOf(entity))) {
                                    android.widget.Toast.makeText(context, "分享失败：文件不可访问", android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            enabled = !state.saving,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("分享")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        val entity = state.entity
        if (entity == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if(state.loading) CircularProgressIndicator() else Text(
                    "记录不存在或已删除",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            DetailContent(
                entity = entity,
                canTrashOriginal = state.canTrashOriginal,
                originalTrashed = state.originalTrashed,
                onTrashOriginal = trashOriginal,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这条记录？") },
            text = { Text("删除记录及尚未保存的应用内成品；已保存到相册的图片不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        vm.deleteRecord()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun DetailContent(
    entity: HistoryEntity,
    canTrashOriginal: Boolean,
    originalTrashed: Boolean,
    onTrashOriginal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saved = entity.outputUri.startsWith("content://")

    val overview: @Composable () -> Unit = {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(entity.outputBytes < entity.inputBytes) "为这张照片省下" else "本次转换体积增加", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(formatBytes(kotlin.math.abs(entity.inputBytes - entity.outputBytes)), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    val grew = entity.outputBytes > entity.inputBytes
                    BadgeChip(formatSavedPercent(entity.inputBytes, entity.outputBytes),
                        if (grew) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                        if (grew) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary)
                }
                Text("${formatBytes(entity.inputBytes)} → ${formatBytes(entity.outputBytes)} · ${entity.format}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (canTrashOriginal) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("相册空间一键释放", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "高画质瘦身图已就绪。原图可安全移入系统相册回收站，释放 ${formatBytes(entity.inputBytes)}（可在系统回收站恢复，期限由系统决定）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = onTrashOriginal) {
                        Text("移入回收站")
                    }
                }
            }
        } else if (originalTrashed) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "原图已移入系统相册回收站，相册已减负 ${formatBytes(entity.inputBytes)}（恢复期限以系统回收站为准）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

    }
    val metadata: @Composable () -> Unit = {
        if (!saved) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (entity.saveState == "FAILED") MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (entity.saveState == "FAILED") Icons.Rounded.DeleteOutline else Icons.Rounded.SaveAlt,
                        null,
                        Modifier.size(18.dp),
                        tint = if (entity.saveState == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (entity.saveState == "FAILED") "保存到输出目录失败；成品仍保留，可重新授权原目录后重试或分享。" else "成品已在应用内保留，可点击下方按钮保存到此批次的输出目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entity.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(entity.settingsSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MetadataCard(entity)

    }
    AdaptiveWorkspace(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        primary = { expanded ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!expanded) overview()
        SectionHeader("画质细节对比", "拖动分界线查看对比；双击 2.5× 放大；双指可捏合缩放")
        Text("当前页面为 SDR 预览；完整 HDR 高动态亮度请在系统相册中查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HeroCompareCard(entity = entity)


                if (!expanded) metadata()
                Spacer(Modifier.height(16.dp))
            }
        },
        secondary = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                overview()
                metadata()
                Spacer(Modifier.height(16.dp))
            }
        },
    )
}

@Composable
private fun HeroCompareCard(entity: HistoryEntity, modifier: Modifier = Modifier) {
    var compareMode by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) } // 0: 卷帘, 1: 按住
    var isHoldingOriginal by remember { mutableStateOf(false) }
    val sliderState = rememberSliderState(value = 0.5f)
    var viewSize by remember { mutableStateOf(IntSize(1, 1)) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    // 只在真正跨越阈值时改变图像请求档位。直接在组合期用 zoomScale > 1f 会在双击动画的
    // 某一帧翻转 key，取消正在进行的解码并从头再来一次整幅解码。
    var highResZoom by remember { mutableStateOf(false) }
    // zoomedIn 用 derivedStateOf：捏合期间每帧变化的 zoomScale 不应每帧重组整张卡片。
    val zoomedIn by remember { derivedStateOf { zoomScale > 1f } }
    LaunchedEffect(Unit) {
        snapshotFlow { zoomScale }.collect { z ->
            if (!highResZoom && z >= 1.05f) highResZoom = true
            else if (highResZoom && z <= 0.98f) highResZoom = false
        }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // 双击缩放动画的唯一宿主：捏合/拖动随时 cancel 它，杜绝两处并发写同一状态。
    var zoomAnim by remember { mutableStateOf<Job?>(null) }
    fun cancelZoomAnim() {
        zoomAnim?.cancel()
        zoomAnim = null
    }
    fun maxPan(scaleNow: Float): Offset = Offset(
        (scaleNow - 1f) * viewSize.width / 2f,
        (scaleNow - 1f) * viewSize.height / 2f,
    )

    val previewHeight = with(density) { (LocalWindowInfo.current.containerSize.height.toDp() * .4f).coerceIn(220.dp, 420.dp) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        cancelZoomAnim()
        val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
        zoomScale = newScale
        val max = maxPan(newScale)
        panOffset = if(newScale <= 1f) Offset.Zero else Offset(
            (panOffset.x + pan.x).coerceIn(-max.x, max.x),
            (panOffset.y + pan.y).coerceIn(-max.y, max.y),
        )
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(24.dp))
                .onSizeChanged {
                    viewSize = IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1))
                }
                .pointerInput(compareMode) {
                    detectTapGestures(
                        onPress = {
                            if (compareMode == 1) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isHoldingOriginal = true
                                val released = tryAwaitRelease()
                                isHoldingOriginal = false
                                if (released) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDoubleTap = {
                            cancelZoomAnim()
                            val target = if (zoomScale > 1f) 1f else 2.5f
                            val startScale = zoomScale
                            val startOffset = panOffset
                            zoomAnim = scope.launch {
                                animate(0f, 1f, animationSpec = tween(Motion.Standard, easing = Motion.StandardEasing)) { t, _ ->
                                    zoomScale = startScale + (target - startScale) * t
                                    panOffset = if (target == 1f) startOffset * (1f - t) else startOffset
                                }
                                if (target == 1f) panOffset = Offset.Zero
                            }
                        },
                    )
                }
                // 未放大且卷帘模式：整图横向拖动 = 移动分割线；纵向让给页面滚动
                .pointerInput(zoomedIn && compareMode == 0) {
                    if (!zoomedIn && compareMode == 0) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            sliderState.value = (sliderState.value + dragAmount / viewSize.width).coerceIn(0f, 1f)
                        }
                    }
                }
                .transformable(state = transform, canPan = { zoomScale > 1f })
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
        ) {
            // 压缩后（右侧/底层）
            UltraAsyncImage(
                uriString = entity.outputUri,
                contentDescription = "压缩后 SDR",
                contentScale = ContentScale.Fit,
                forceSdr = true,
                maxDim = if(highResZoom) 2048 else 1600,
                modifier = Modifier.fillMaxSize(),
            )

            // 原图（左侧/按分割比例裁剪）
            val outputLong = maxOf(entity.width, entity.height)
            UltraAsyncImage(
                uriString = entity.inputUri,
                contentDescription = "原图 SDR",
                contentScale = ContentScale.Fit,
                forceSdr = true,
                maxDim = if(highResZoom) 2048 else if(outputLong in 1 until 1600) outputLong else 1600,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    val effectiveFraction = if (compareMode == 1) {
                        if (isHoldingOriginal) 1f else 0f
                    } else {
                        sliderState.value
                    }
                    clipRect(right = size.width * effectiveFraction) { this@drawWithContent.drawContent() }
                },
            )

            if (compareMode == 0) {
                // 分割线
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { androidx.compose.ui.unit.IntOffset((viewSize.width * sliderState.value - with(density) { 1.dp.toPx() }).toInt(), 0) }
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.9f)),
                )

                // 拖拽手柄
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { androidx.compose.ui.unit.IntOffset((viewSize.width * sliderState.value - with(density) { 18.dp.toPx() }).toInt(), 0) }
                        .size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                CompareLabel("原图", Modifier.align(Alignment.TopStart).padding(12.dp))
                CompareLabel("压缩后", Modifier.align(Alignment.TopEnd).padding(12.dp))
            } else {
                CompareLabel(
                    text = if (isHoldingOriginal) "原图（查看中）" else "压缩后",
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    active = isHoldingOriginal,
                )
            }
        }

        // 模式切换与缩放复位栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = compareMode == 0,
                    onClick = { compareMode = 0 },
                    label = { Text("卷帘对比") },
                )
                FilterChip(
                    selected = compareMode == 1,
                    onClick = { compareMode = 1 },
                    label = { Text("按住对比") },
                )
            }
            if (zoomedIn) {
                AssistChip(
                    onClick = {
                        cancelZoomAnim()
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    },
                    label = { Text(String.format(java.util.Locale.ROOT, "%.1f× 复位", zoomScale)) },
                )
            }
        }

        if (compareMode == 0) {
            Slider(
                modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "原图与成品比较比例" },
                state = sliderState,
                onValueChange = { sliderState.value = it },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isHoldingOriginal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isHoldingOriginal) "松开恢复压缩图" else "按住画面任何位置查看原图",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isHoldingOriginal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompareLabel(text: String, modifier: Modifier = Modifier, active: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MetadataCard(entity: HistoryEntity) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("图片信息")
            InfoRow("格式", "${entity.inputFormat} → ${entity.format}")
            InfoRow("分辨率", formatResolution(entity.width, entity.height))
            InfoRow(
                "Gain Map 尺寸",
                if (entity.hadGainMap) formatResolution(entity.gainmapWidth, entity.gainmapHeight) else "无",
            )
            InfoRow(
                "最大亮度增益",
                if (entity.hadGainMap) formatBoost(entity.maxContentBoost) else "无",
            )
            InfoRow("原大小", formatBytes(entity.inputBytes))
            InfoRow("新大小", formatBytes(entity.outputBytes))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "体积变化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val grew = entity.outputBytes > entity.inputBytes
                BadgeChip(
                    text = formatSavedPercent(entity.inputBytes, entity.outputBytes),
                    containerColor = if (grew) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    contentColor = if (grew) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
            }
            InfoRow("耗时", formatDuration(entity.durationMs))
            InfoRow("输出 EXIF", if(entity.hasExif) "已保留" else "未保留")
        }
    }
}

private fun viewInAlbum(context: Context, entity: HistoryEntity) {
    runCatching {
        val uri = Uri.parse(entity.outputUri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                OutputFormat.parse(entity.format).mimeType,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure { android.widget.Toast.makeText(context, "无法打开文件，请导出或选择其他查看应用", android.widget.Toast.LENGTH_LONG).show() }
}
