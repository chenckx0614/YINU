package com.yinu.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

internal class DockMotion(
    val reveal: State<Float>,
    val bottomInsets: WindowInsets,
    val keyboardActive: Boolean,
)

private data class ImeMotion(val occupiesWindow: Boolean, val closing: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberDockMotion(
    editorFocused: Boolean,
    dockHeight: Dp,
    onKeyboardClosed: () -> Unit,
): DockMotion {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val imeMotion by remember(density, ime, imeSource, imeTarget) {
        derivedStateOf {
            val target = imeTarget.getBottom(density)
            ImeMotion(
                occupiesWindow = ime.getBottom(density) > 0 || target > 0,
                closing = target == 0 && imeSource.getBottom(density) > 0,
            )
        }
    }
    // isImeVisible also covers a floating keyboard that has no bottom inset.
    val keyboardActive = WindowInsets.isImeVisible || imeMotion.occupiesWindow
    var sawKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardActive) {
        if (keyboardActive) {
            sawKeyboard = true
        } else if (sawKeyboard) {
            onKeyboardClosed()
            sawKeyboard = false
        }
    }

    // Start the return during keyboard exit. Clear focus only once it finishes so a
    // cancelled keyboard gesture can reverse the dock without dismissing the editor.
    val showDock = imeMotion.closing || (!keyboardActive && (!editorFocused || sawKeyboard))
    val reveal = animateFloatAsState(
        targetValue = if (showDock) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 580f, visibilityThreshold = 0.001f),
        label = "Floating dock reveal",
    )
    val dockInsets = remember(dockHeight, reveal) { AnimatedDockInsets(dockHeight, reveal) }
    val systemInsets = WindowInsets.navigationBars.union(ime).only(WindowInsetsSides.Bottom)
    val contentInsets = remember(systemInsets, dockInsets) { systemInsets.union(dockInsets) }
    return DockMotion(reveal, contentInsets, keyboardActive)
}

private class AnimatedDockInsets(private val height: Dp, private val reveal: State<Float>) : WindowInsets {
    override fun getBottom(density: Density): Int = with(density) {
        (height.toPx() * reveal.value.coerceIn(0f, 1f)).roundToInt()
    }

    override fun getTop(density: Density) = 0
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
}
