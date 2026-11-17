// Adapted for YINU on 2026-11-05. See THIRD_PARTY.md.
package com.haooz.chedule.ui.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.interactive.DragSelectAxis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick as onSemanticsClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.haooz.chedule.ui.effects.liquidglass.InteractiveHighlight
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.interactive.computeDragTransform
import top.yukonga.miuix.kmp.interactive.DropdownPanelDragSelectState
import top.yukonga.miuix.kmp.interactive.dropdownPanelDragSelect
import top.yukonga.miuix.kmp.interactive.dropdownPanelEntry
import top.yukonga.miuix.kmp.internal.drawBlurredPathShadow
import androidx.compose.material3.MaterialTheme

private val MenuEnterEasing = CubicBezierEasing(0.3f, 1.25f, 0.32f, 1f)
val LocalShortcutMenuLabels = staticCompositionLocalOf { false }
private val MenuExitEasing = CubicBezierEasing(0.3f, 1f, 0.3f, 1f)
/**
 * 菜单布局四周的阴影留白：面板可见区比测量尺寸四边各小这么多。
 * 调用方算「与卡片的间距 / 右缘对齐」要按它换算（见 OverlayLayers 的 offset）。
 */
val ShortcutMenuShadowPadding = 12.dp

data class ShortcutMenuItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val iconSize: Dp = 26.dp
)

@Composable
fun ShortcutMenu(
    show: Boolean,
    items: List<ShortcutMenuItem>,
    modifier: Modifier = Modifier,
    backdrop: Backdrop,
    onDismiss: () -> Unit = {},
) {
    val density = LocalDensity.current
    val showLabels = LocalShortcutMenuLabels.current

    val containerColor = MaterialTheme.colorScheme.surface.copy(
        alpha = com.haooz.chedule.ui.utils.AppMaterialSettings.solidSurfaceAlpha(0.6f)
    )

    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    val hapticFeedback = LocalHapticFeedback.current
    // 跟手滑选：横排按 X 轴命中，与下拉菜单共用同一套状态 / 遮罩 / 手势
    // （DropdownPanelDragSelect：按下命中 → 逐帧改命中 → 松手执行）
    val dragSelectState = remember { DropdownPanelDragSelectState(DragSelectAxis.Horizontal) }
    // 点按高光与形变共用一个实例：gestureModifier 驱动，modifier 画光，
    // pressProgress / offset 喂给 computeDragTransform 做按压缩放与跟手位移
    val dragScope = rememberCoroutineScope()
    val interactiveHighlight = remember(dragScope) {
        InteractiveHighlight(
            animationScope = dragScope,
            radiusScale = { 0.5f / 1.5f },
            radiusBaseDp = 150.dp,
            fixedRadius = true,
        )
    }

    LaunchedEffect(show) {
        if (show) {
            launch {
                scale.animateTo(1f, tween(420, easing = MenuEnterEasing))
            }
            launch {
                alpha.animateTo(1f, tween(240))
            }
        } else {
            launch {
                scale.animateTo(0.24f, tween(240, easing = MenuExitEasing))
            }
            launch {
                alpha.animateTo(0f, tween(120))
            }
        }
    }

    if (alpha.value <= 0f && !show) return

    val itemSize = when {
        items.size >= 9 -> 27.dp
        items.size >= 8 -> 30.dp
        items.size >= 7 -> 32.dp
        else -> 36.dp
    }
    val horizontalPadding = if (items.size >= 7) 8.dp else if (items.size >= 5) 10.dp else 16.dp
    val verticalPadding = 8.dp
    val spacing = if (items.size >= 7) 4.dp else if (items.size >= 5) 6.dp else 16.dp

    Box(
        modifier = modifier
            .wrapContentSize()
            .graphicsLayer {
                val drag = computeDragTransform(
                    width = size.width,
                    height = size.height,
                    fraction = 1f,
                    pressProgress = interactiveHighlight.pressProgress,
                    offset = interactiveHighlight.offset,
                    density = this,
                    shapeAspectRatio = 1f,
                )
                scaleX = scale.value * drag.scaleX
                scaleY = scale.value * drag.scaleY
                this.alpha = alpha.value
                // 需要左移时 pivot 改为右下角(向左展开)，整体向左平移对齐目标右边缘
                transformOrigin = TransformOrigin(0.85f, 1f)
                translationX = drag.translationX
                translationY = drag.translationY
                clip = false
            }
            .drawBehind {
                val blurRadius = 16f * density.density
                val cornerRadiusPx = 20f * density.density
                // 跨平台阴影：compose Path + miuix expect/actual（原为 Paint+BlurMaskFilter+"#0A000000"）
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(
                                ShortcutMenuShadowPadding.toPx(),
                                ShortcutMenuShadowPadding.toPx(),
                                size.width - ShortcutMenuShadowPadding.toPx(),
                                size.height - ShortcutMenuShadowPadding.toPx(),
                            ),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        ),
                    )
                }
                drawBlurredPathShadow(
                    path = path,
                    color = Color(0x0A000000),
                    blurRadius = blurRadius,
                )
            }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .then(if (showLabels) Modifier.widthIn(max = 344.dp).fillMaxWidth() else Modifier.wrapContentSize())
                .padding(ShortcutMenuShadowPadding)
                // 面板内容区在 root 里的位置：项把 boundsInRoot 换算成面板局部坐标。
                // 与手势同一段链条，两个坐标系同源，ShortcutMenuShadowPadding 不掺进差值
                .onGloballyPositioned {
                    dragSelectState.panelTopInRoot = it.boundsInRoot().top
                    dragSelectState.panelLeftInRoot = it.boundsInRoot().left
                }
                .then(if (showLabels) Modifier else Modifier.dropdownPanelDragSelect(
                    state = dragSelectState,
                    fraction = { 1f },
                    hapticFeedback = hapticFeedback,
                ))
                // 按压/拖动位移源：纯视觉观察者，不消费事件（与下拉菜单同一挂法）；
                // 光跟手、位移喂给上面的 computeDragTransform 做形变
                .then(interactiveHighlight.gestureModifier)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousRoundedRectangle(18.dp) },
                    effects = {
                        // 无材质档整体关（这组是硬编码参数，不读档位模糊值）
                        if (com.haooz.chedule.ui.utils.AppMaterialSettings.glassEffectsEnabled()) {
                            vibrancy()
                            blur(4f.dp.toPx())
                            lens(6f.dp.toPx(), 36f.dp.toPx())
                        }
                    },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = {
                        drawRect(containerColor)
                    }
                )
                .edgeLight(shape = ContinuousRoundedRectangle(18.dp), edgeLight = rememberDefaultEdgeLight(degradedBaseColor = containerColor))
                .clip(ContinuousRoundedRectangle(18.dp))
                .then(interactiveHighlight.modifier)
        ) {
            if (showLabels) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { item ->
                                Column(
                                    Modifier.weight(1f).heightIn(min = 64.dp)
                                        .clip(ContinuousRoundedRectangle(12.dp))
                                        .clickable(enabled = show, role = Role.Button) {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                            onDismiss()
                                            item.onClick()
                                        }.padding(horizontal = 2.dp, vertical = 7.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(item.icon, item.label, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                                    Text(item.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp,
                                        lineHeight = 14.sp, maxLines = 2, textAlign = TextAlign.Center)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                return@BoxWithConstraints
            }
            val availableWidth = maxWidth - horizontalPadding * 2 - spacing * (items.size - 1)
            val slotSize = minOf(itemSize, (availableWidth / items.size.coerceAtLeast(1)).coerceAtLeast(0.dp))
            Row(
                modifier = Modifier
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    // 松手时由面板手势层统一触发（与下拉菜单一致）
                    val activate = {
                        if (show) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onDismiss()
                            item.onClick()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(slotSize)
                            .clip(ContinuousCapsule())
                            // 登记横向区间 + 命中高亮（遮罩色与 150ms 淡变都取菜单同一套）
                            .dropdownPanelEntry(
                                enabled = true,
                                state = dragSelectState,
                                highlightShape = ContinuousCapsule(),
                                action = activate,
                            )
                            // 面板手势层没有语义动作，读屏/键盘仍要能触发
                            .semantics {
                                role = Role.Button
                                onSemanticsClick {
                                    activate()
                                    true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(item.iconSize)
                        )
                    }
                }
            }
        }
    }
}
