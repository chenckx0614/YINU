package com.yinu.app.records

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Leave room for the shared panel's title, padding and action row on small screens. */
@Composable
internal fun dialogBodyMaxHeight(cap: Dp, extra: Dp = 0.dp): Dp {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val bottom = maxOf(safe.calculateBottomPadding(), WindowInsets.ime.asPaddingValues().calculateBottomPadding())
    return (height - safe.calculateTopPadding() - bottom - 180.dp - extra).coerceIn(64.dp, cap)
}
