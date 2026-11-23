package app.ultraslim.ui.adaptive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.adaptive.collectFoldingFeaturesAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.window.layout.FoldingFeature
import kotlin.math.roundToInt

/** Window coordinates, matching WindowManager's FoldingFeature bounds. Also used by previews/tests. */
data class WorkspaceFold(val bounds: Rect, val horizontal: Boolean)
val LocalWorkspaceFolds = staticCompositionLocalOf<List<WorkspaceFold>?> { null }

data class WorkspacePlan(val primary: Rect, val secondary: Rect? = null) {
    val expanded: Boolean get() = secondary != null
}

/** Geometry is independent of a device model: resizing, split-screen and folds use the same rules. */
fun workspacePlan(width: Float, height: Float, density: Float, folds: List<WorkspaceFold>, minPaneWidthDp: Float = 320f, expandedWidthDp: Float = 720f): WorkspacePlan {
    val full = Rect(0f, 0f, width, height)
    val gap = 24f * density
    for (fold in folds) {
        val b = fold.bounds
        if (fold.horizontal && b.top > 0 && b.bottom < height && b.right > 0 && b.left < width) {
            val top = Rect(0f, 0f, width, (b.top - gap / 2).coerceAtLeast(0f))
            val bottom = Rect(0f, (b.bottom + gap / 2).coerceAtMost(height), width, height)
            if (top.height >= 180 * density && bottom.height >= 180 * density) return WorkspacePlan(top, bottom)
            return WorkspacePlan(if (top.height >= bottom.height) top else bottom)
        }
        if (!fold.horizontal && b.left > 0 && b.right < width && b.bottom > 0 && b.top < height) {
            val left = Rect(0f, 0f, (b.left - gap / 2).coerceAtLeast(0f), height)
            val right = Rect((b.right + gap / 2).coerceAtMost(width), 0f, width, height)
            if (left.width >= 280 * density && right.width >= 280 * density) return WorkspacePlan(left, right)
            return WorkspacePlan(if (left.width >= right.width) left else right)
        }
    }
    if (width >= maxOf(expandedWidthDp, 2 * minPaneWidthDp + 24) * density && height >= 240 * density) {
        // The secondary panel grows with the window but remains readable on a desktop-sized screen.
        val secondaryWidth = (width * .40f).coerceIn(minPaneWidthDp * density, maxOf(minPaneWidthDp, 480f) * density)
        return WorkspacePlan(Rect(0f, 0f, width - secondaryWidth - gap, height),
            Rect(width - secondaryWidth, 0f, width, height))
    }
    return WorkspacePlan(full)
}

@Composable
private fun currentWorkspaceFolds(): List<WorkspaceFold> {
    LocalWorkspaceFolds.current?.let { return it }
    val deviceFolds by collectFoldingFeaturesAsState()
    return deviceFolds.filter {
        it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL || it.state == FoldingFeature.State.HALF_OPENED
    }.map {
        WorkspaceFold(Rect(it.bounds.left.toFloat(), it.bounds.top.toFloat(), it.bounds.right.toFloat(), it.bounds.bottom.toFloat()),
            it.orientation == FoldingFeature.Orientation.HORIZONTAL)
    }
}

/** Collect once above navigation, so a new destination starts with the current posture. */
@Composable
fun WorkspaceFoldProvider(content: @Composable () -> Unit) {
    val folds = currentWorkspaceFolds()
    CompositionLocalProvider(LocalWorkspaceFolds provides folds, content = content)
}

/** Two regions within one screen, not two navigation destinations. */
@Composable
fun AdaptiveWorkspace(
    modifier: Modifier = Modifier,
    minPaneWidth: Dp = 320.dp,
    expandedWidth: Dp = 720.dp,
    primary: @Composable (expanded: Boolean) -> Unit,
    secondary: @Composable () -> Unit,
) {
    val folds = currentWorkspaceFolds()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val positionedModifier = if (folds.isNotEmpty()) {
        modifier.fillMaxSize().onGloballyPositioned {
            val pos = it.positionInWindow()
            if (pos != origin) origin = pos
        }
    } else {
        modifier.fillMaxSize()
    }
    BoxWithConstraints(positionedModifier) {
        val plan = workspacePlan(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density.density,
            folds.map { it.copy(bounds = it.bounds.translate(-origin)) }, minPaneWidth.value, expandedWidth.value)
        Layout(
            modifier = Modifier.fillMaxSize().testTag(if (plan.expanded) "workspace-expanded" else "workspace-compact"),
            content = {
                Box(Modifier.fillMaxSize().testTag("workspace-primary")) { primary(plan.expanded) }
                if (plan.expanded) Box(Modifier.fillMaxSize().testTag("workspace-secondary")) { secondary() }
            },
        ) { measurables, constraints ->
            val regions = listOfNotNull(plan.primary, plan.secondary)
            val placeables = measurables.mapIndexed { i, m ->
                val r = regions[i]
                m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, p -> p.place(regions[i].left.roundToInt(), regions[i].top.roundToInt()) }
            }
        }
    }
}
