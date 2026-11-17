package com.yinu.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.stateDescription

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.ui.basic.LiquidTopBarButton
import com.haooz.chedule.ui.components.LiquidBottomTab
import com.haooz.chedule.ui.components.LiquidBottomTabs
import com.haooz.chedule.ui.components.LiquidBottomTrailing
import com.haooz.chedule.ui.utils.bottomFloatingPadding
import com.kyant.backdrop.Backdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.layout.ProgressiveBlurTopBar

private val DockHeight = 56.dp
private val TopBarHeight = 56.dp

@Composable
fun rememberTopBarMaterialVisible(listState: LazyListState): Boolean {
    val threshold = with(LocalDensity.current) { 24.dp.roundToPx() }
    val visible by remember(listState, threshold) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= threshold
        }
    }
    return visible
}

@Composable
fun YinuDock(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSettings: () -> Unit,
    backdrop: Backdrop,
    recordIcon: ImageVector,
    searchIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val selectTab: (Int) -> Unit = { index ->
        if (index != selectedTab) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onTabSelected(index)
    }
    val trailing = remember(onSettings) {
        LiquidBottomTrailing(
            icon = MiuixIcons.Settings,
            contentDescription = "设置",
            onClick = onSettings,
        )
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(bottom = bottomFloatingPadding()).height(DockHeight),
    ) {
        // Geometry and hit area follow NexioSchedule's ScheduleBottomBar.
        val pillWidth = maxWidth * 0.42f
        val visibleWidth = pillWidth + trailing.gap + DockHeight
        LiquidBottomTabs(
            selectedTabIndex = { selectedTab },
            onTabSelected = selectTab,
            backdrop = backdrop,
            tabsCount = 2,
            containerHeight = DockHeight,
            trailing = trailing,
            modifier = Modifier.offset(x = (maxWidth - visibleWidth) / 2)
                .width(visibleWidth + trailing.dragRoom)
                .height(DockHeight),
        ) {
            LiquidBottomTab(Modifier.tabAction(0, selectedTab, selectTab)) {
                DockItem(recordIcon, "记录")
            }
            LiquidBottomTab(Modifier.tabAction(1, selectedTab, selectTab)) {
                DockItem(searchIcon, "搜索")
            }
        }
    }
}

private fun Modifier.tabAction(index: Int, selectedTab: Int, onTabSelected: (Int) -> Unit) =
    semantics(mergeDescendants = true) {
        selected = index == selectedTab
        onClick {
            onTabSelected(index)
            true
        }
    }

@Composable
private fun DockItem(icon: ImageVector, label: String) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    Image(icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
    Text(label, color = tint, fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
fun YinuTopBar(
    title: String,
    backdrop: Backdrop,
    onBack: (() -> Unit)? = null,
    blurHeight: Dp = Dp.Unspecified,
    materialVisible: Boolean = true,
) {
    val materialAlpha by animateFloatAsState(
        targetValue = if (materialVisible) 1f else 0f,
        animationSpec = tween(250),
        label = "Top bar material",
    )
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = statusBarHeight).height(TopBarHeight)
                .semantics { stateDescription = if (materialVisible) "顶栏材质已显示" else "顶栏材质已隐藏" },
            contentAlignment = Alignment.Center,
        ) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (onBack != null) {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                    LiquidTopBarButton(
                        onClick = onBack,
                        backdrop = backdrop,
                        icon = MiuixIcons.ChevronBackward,
                        contentDescription = "返回",
                        iconSize = 25.dp,
                        iconOffset = DpOffset((-2).dp, 0.dp),
                        backdropAlpha = materialAlpha,
                        shadowAlpha = materialAlpha,
                    )
                }
            }
        }
    }
    // Search can supply one continuous blur for both the title and its filters.
    if (blurHeight == 0.dp) content()
    else ProgressiveBlurTopBar(
        backdrop = backdrop,
        height = if (blurHeight == Dp.Unspecified) statusBarHeight + 80.dp else blurHeight,
        maskAlpha = { materialAlpha },
    ) { content() }
}

@Composable
fun YinuBottomBlur(
    backdrop: Backdrop,
    bottomPadding: Dp,
    keyboardBottom: Dp,
    modifier: Modifier = Modifier,
) {
    ProgressiveBlurTopBar(
        backdrop = backdrop,
        modifier = modifier.padding(bottom = keyboardBottom),
        height = (bottomPadding - keyboardBottom).coerceAtLeast(0.dp) + 32.dp,
        tintIntensity = 0.40f,
        edgeFadeStart = 0.67f,
        fromBottom = true,
    ) {}
}

@Composable
fun yinuTopContentPadding() =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight

@Composable
fun yinuDockContentPadding() = DockHeight + bottomFloatingPadding()
