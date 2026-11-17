package com.yinu.app.records

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.haooz.chedule.ui.basic.LiquidGlassDropdownMenu
import com.haooz.chedule.ui.basic.LiquidGlassDropdownMenuItem
import com.haooz.chedule.ui.basic.LiquidGlassDropdownShadowPadding
import com.haooz.chedule.ui.basic.ShortcutMenu
import com.haooz.chedule.ui.basic.ShortcutMenuItem
import com.haooz.chedule.ui.basic.ShortcutMenuShadowPadding
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.designsystem.YinuMenuIcons
import com.yinu.data.Record
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal sealed interface RecordMenu {
    sealed interface Anchored : RecordMenu {
        val anchor: Rect
    }

    data class Add(override val anchor: Rect, val pick: (String) -> Unit) : Anchored
    data class Quote(override val anchor: Rect, val locate: () -> Unit) : Anchored

    data class Message(
        override val anchor: Rect,
        val items: List<ShortcutMenuItem>,
        val bubble: @Composable () -> Unit,
    ) : Anchored

    data class TextSelection(val text: String, val copy: (String) -> Unit) : RecordMenu
    data class Merge(val ids: List<String>, val model: RecordsViewModel, val done: () -> Unit) : RecordMenu
    data class Organize(val records: List<Record>, val model: RecordsViewModel, val done: () -> Unit) : RecordMenu
    data class FavoriteGroup(val record: Record, val model: RecordsViewModel) : RecordMenu
    data class Information(val text: String, val notify: (String) -> Unit) : RecordMenu
    data class Edit(val record: Record, val save: suspend (String) -> Boolean) : RecordMenu
    data class Reminder(val record: Record, val model: RecordsViewModel) : RecordMenu
    data class Relations(val record: Record?, val model: RecordsViewModel, val locate: (String) -> Unit) : RecordMenu
    data class Export(val records: List<Record>) : RecordMenu
}

internal class RecordMenuHost {
    var target by mutableStateOf<RecordMenu?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    fun open(menu: RecordMenu.Anchored) {
        if (target != null || menu.anchor.isEmpty) return
        target = menu
        visible = true
    }

    fun selectText(text: String, copy: (String) -> Unit) {
        target = RecordMenu.TextSelection(text, copy)
        visible = true
    }

    fun showMerge(ids: List<String>, model: RecordsViewModel, done: () -> Unit) { target = RecordMenu.Merge(ids, model, done); visible = true }
    fun showOrganize(records: List<Record>, model: RecordsViewModel, done: () -> Unit) {
        target = RecordMenu.Organize(records, model, done)
        visible = true
    }
    fun showFavoriteGroup(record: Record, model: RecordsViewModel) { target = RecordMenu.FavoriteGroup(record, model); visible = true }

    fun showInformation(text: String, notify: (String) -> Unit) {
        target = RecordMenu.Information(text, notify)
        visible = true
    }

    fun editRecord(record: Record, save: suspend (String) -> Boolean) {
        target = RecordMenu.Edit(record, save)
        visible = true
    }

    fun dismiss() {
        visible = false
    }
    fun showReminder(record: Record, model: RecordsViewModel) { target = RecordMenu.Reminder(record, model); visible = true }
    fun showRelations(record: Record, model: RecordsViewModel, locate: (String) -> Unit) {
        target = RecordMenu.Relations(record, model, locate); visible = true
    }
    fun showRelationsDraft(model: RecordsViewModel, locate: (String) -> Unit) {
        target = RecordMenu.Relations(null, model, locate); visible = true
    }
    fun showExport(records: List<Record>) { target = RecordMenu.Export(records); visible = true }

    fun removeClosed(menu: RecordMenu) {
        if (!visible && target === menu) target = null
    }
}

internal val LocalRecordMenus = staticCompositionLocalOf<RecordMenuHost> { error("Record menu host is missing") }

private val QuoteMenuShape = ContinuousRoundedRectangle(14.dp)
private val QuoteMenuGlass: BackdropEffectScope.() -> Unit = { blur(14.dp.toPx()) }

@Composable
internal fun blurredRecordPage(host: RecordMenuHost): Modifier {
    val show = host.visible && host.target is RecordMenu.Message
    val radius = animateFloatAsState(if (show) 16f else 0f, tween(if (show) 280 else 250), label = "Message menu blur")
    if (!isRenderEffectSupported() || (host.target !is RecordMenu.Message && radius.value == 0f)) return Modifier
    return Modifier.graphicsLayer {
        val blur = radius.value.dp.toPx()
        renderEffect = if (blur > 0.01f) BlurEffect(blur, blur, TileMode.Clamp) else null
    }
}

@Composable
internal fun RecordMenuOverlay(host: RecordMenuHost, backdrop: Backdrop) {
    val show = host.visible
    val target = when (val menu = host.target) {
        null -> return
        is RecordMenu.Reminder -> {
            ReminderDialog(menu.record, menu.model, show, backdrop, host::dismiss, { host.removeClosed(menu) })
            return
        }
        is RecordMenu.Relations -> {
            RecordRelationsDialog(menu.record, menu.model, menu.locate, show, backdrop, host::dismiss, { host.removeClosed(menu) })
            return
        }
        is RecordMenu.Export -> {
            LongExportDialog(menu.records, show, backdrop, host::dismiss, { host.removeClosed(menu) })
            return
        }
        is RecordMenu.TextSelection -> {
            RecordTextSelectionDialog(
                text = menu.text,
                show = show,
                backdrop = backdrop,
                onDismiss = host::dismiss,
                onDismissFinished = { host.removeClosed(menu) },
                onCopy = menu.copy,
            )
            return
        }
        is RecordMenu.Merge -> {
            MergeRecordDialog(menu, show, backdrop, host::dismiss, { host.removeClosed(menu) })
            return
        }
        is RecordMenu.Organize -> {
            com.yinu.app.ai.AiOrganizeDialog(menu, show, backdrop, host::dismiss, { host.removeClosed(menu) })
            return
        }
        is RecordMenu.FavoriteGroup -> {
            FavoriteGroupDialog(menu, show, backdrop, host::dismiss, { host.removeClosed(menu) })
            return
        }
        is RecordMenu.Information -> {
            RecordInformationDialog(menu.text, show, backdrop, host::dismiss, { host.removeClosed(menu) }, menu.notify)
            return
        }
        is RecordMenu.Edit -> {
            RecordEditDialog(
                record = menu.record,
                show = show,
                backdrop = backdrop,
                onDismiss = host::dismiss,
                onDismissFinished = { host.removeClosed(menu) },
                onSave = menu.save,
            )
            return
        }
        is RecordMenu.Anchored -> menu
    }
    BackHandler(enabled = show, onBack = host::dismiss)
    LaunchedEffect(target, show) {
        if (!show) {
            delay(if (target is RecordMenu.Add) 380 else 260)
            host.removeClosed(target)
        }
    }
    val dim = animateFloatAsState(
        if (show && target is RecordMenu.Message) 0.18f else 0f,
        tween(220), label = "Message menu dim",
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = dim.value }
                .background(Color.Black),
        )
        // A separate hit layer stays active even while the transparent add menu is open.
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = "关闭菜单",
                onClick = host::dismiss,
            ),
        )
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).union(WindowInsets.ime),
            ),
        ) {
            var origin by remember { mutableStateOf<Offset?>(null) }
            Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
                val rootOrigin = origin ?: return@Box
                val anchor = target.anchor.translate(-rootOrigin)
                when (target) {
                    is RecordMenu.Add -> {
                        fun pick(mimeType: String) {
                            if (!host.visible) return
                            host.dismiss()
                            target.pick(mimeType)
                        }
                        LiquidGlassDropdownMenu(
                            show = show,
                            backdrop = backdrop,
                            triggerIcon = YinuMenuIcons.add,
                            triggerContentDescription = "添加附件",
                            modifier = Modifier.placeOverPage(anchor, MenuPosition.Add),
                        ) {
                            LiquidGlassDropdownMenuItem("选择图片", { pick("image/*") }) {
                                Icon(YinuMenuIcons.image, null, Modifier.size(21.dp))
                            }
                            LiquidGlassDropdownMenuItem("选择文件", { pick("*/*") }) {
                                Icon(YinuMenuIcons.file, null, Modifier.size(21.dp))
                            }
                        }
                    }
                    is RecordMenu.Quote -> {
                        val transition = remember { MutableTransitionState(false) }.apply { targetState = show }
                        AnimatedVisibility(
                            visibleState = transition,
                            modifier = Modifier.placeOverPage(anchor, MenuPosition.Quote),
                            enter = fadeIn(tween(120)) + scaleIn(tween(170), initialScale = 0.86f),
                            exit = fadeOut(tween(115)) + scaleOut(tween(130), targetScale = 0.92f),
                        ) {
                            val glass = isRenderEffectSupported()
                            val haptics = LocalHapticFeedback.current
                            val surface = MaterialTheme.colorScheme.surface.copy(alpha = if (glass) 0.76f else 1f)
                            Row(
                                Modifier
                                    .then(
                                        if (glass) Modifier.drawBackdrop(
                                            backdrop = backdrop,
                                            shape = { QuoteMenuShape },
                                            effects = QuoteMenuGlass,
                                            highlight = null,
                                            shadow = null,
                                        ) else Modifier,
                                    )
                                    .edgeLight(shape = QuoteMenuShape, edgeLight = rememberDefaultEdgeLight())
                                    .clip(QuoteMenuShape)
                                    .background(surface)
                                    .clickable(
                                        enabled = show,
                                        interactionSource = null,
                                        indication = null,
                                        role = Role.Button,
                                    ) {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        host.dismiss()
                                        target.locate()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(YinuMenuIcons.reply, null, Modifier.size(17.dp))
                                Text("查看引用", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    is RecordMenu.Message -> {
                        val lift = animateFloatAsState(
                            if (show) 1f else 0f, tween(if (show) 240 else 220), label = "Lifted message",
                        )
                        Box(
                            Modifier.placeOverPage(anchor, MenuPosition.Bubble)
                                .graphicsLayer {
                                    alpha = lift.value
                                    scaleX = 1f + 0.025f * lift.value
                                    scaleY = scaleX
                                }
                                .clipToBounds()
                                .clearAndSetSemantics { hideFromAccessibility() },
                        ) { target.bubble() }
                        ShortcutMenu(
                            show = show,
                            items = target.items,
                            backdrop = backdrop,
                            modifier = Modifier.placeOverPage(anchor, MenuPosition.Shortcut),
                            onDismiss = host::dismiss,
                        )
                    }
                }
            }
        }
    }
}

private enum class MenuPosition { Add, Bubble, Shortcut, Quote }

private fun Modifier.placeOverPage(anchor: Rect, position: MenuPosition) = layout { measurable, limits ->
    val pageWidth = limits.maxWidth
    val pageHeight = limits.maxHeight
    val child = if (position == MenuPosition.Bubble) {
        measurable.measure(Constraints.fixed(
            anchor.width.roundToInt().coerceIn(0, pageWidth),
            anchor.height.roundToInt().coerceIn(0, pageHeight),
        ))
    } else {
        measurable.measure(limits.copy(minWidth = 0, minHeight = 0))
    }
    val gap = 8.dp.toPx()
    val shadow = when (position) {
        MenuPosition.Add -> LiquidGlassDropdownShadowPadding.toPx()
        MenuPosition.Shortcut -> ShortcutMenuShadowPadding.toPx()
        MenuPosition.Bubble, MenuPosition.Quote -> 0f
    }
    val x = when (position) {
        MenuPosition.Add -> anchor.center.x - 21.dp.toPx() - shadow
        MenuPosition.Shortcut, MenuPosition.Quote -> anchor.right - child.width + shadow
        MenuPosition.Bubble -> anchor.left
    }
    val y = when (position) {
        MenuPosition.Add -> anchor.center.y + 21.dp.toPx() + shadow - child.height
        MenuPosition.Shortcut, MenuPosition.Quote -> {
            val above = anchor.top - gap + shadow - child.height
            if (above >= -shadow) above else anchor.bottom + gap - shadow
        }
        MenuPosition.Bubble -> anchor.top
    }
    layout(child.width, child.height) {
        child.place(
            x.roundToInt().coerceIn(-shadow.roundToInt(), (pageWidth - child.width + shadow.roundToInt()).coerceAtLeast(0)),
            y.roundToInt().coerceIn(-shadow.roundToInt(), (pageHeight - child.height + shadow.roundToInt()).coerceAtLeast(0)),
        )
    }
}
