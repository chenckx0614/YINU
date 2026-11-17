// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.haooz.chedule.ui.basic

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.haooz.chedule.ui.utils.isAppDarkTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlin.math.pow
import kotlinx.coroutines.launch

private val DecelerateEasing = Easing { 1f - (1f - it).pow(3) }
private val DialogEffects: BackdropEffectScope.() -> Unit = {
    vibrancy()
    blur(24.dp.toPx())
}

/**
 * Phone branch of the NexioSchedule MiuiX DialogContentLayout, hosted in YINU's
 * root overlay so the panel can sample the screen below it.
 */
@Composable
fun NexioOverlayDialog(
    show: Boolean,
    title: String,
    summary: String? = null,
    backdrop: Backdrop,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val animationProgress = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    val dimProgress = remember { Animatable(0f) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val dismissFinished by rememberUpdatedState(onDismissFinished)

    LaunchedEffect(show) {
        if (show) {
            launch { dimProgress.animateTo(1f, tween(300, easing = DecelerateEasing)) }
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f),
            )
        } else {
            launch { dimProgress.animateTo(0f, tween(250, easing = DecelerateEasing)) }
            animationProgress.animateTo(0f, tween(260, easing = DecelerateEasing))
            dimProgress.snapTo(0f)
            dismissFinished()
        }
    }
    BackHandler { if (show) dismiss() }

    val windowInfo = LocalWindowInfo.current
    val view = LocalView.current
    val cornerRadiusPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    } else 0
    val cornerRadius = with(LocalDensity.current) { (cornerRadiusPx.toDp() - 12.dp).coerceAtLeast(32.dp) }
    val shape = remember(cornerRadius) { ContinuousRoundedRectangle(cornerRadius) }
    val glassActive = isRenderEffectSupported()
    val colors = MaterialTheme.colorScheme
    val glassAlpha = if (isAppDarkTheme()) 0.87f else 0.78f
    val bottomOutsideMargin = maxOf(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 2.dp,
        22.dp,
    )

    Box(
        Modifier.fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.3f * dimProgress.value * 0.6f)) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures(onTap = { dismiss() }) })
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = bottomOutsideMargin)
                .graphicsLayer {
                    translationY = (1f - animationProgress.value) * windowInfo.containerSize.height
                }
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .semantics { paneTitle = title }
                // The panel keeps taps away from the scrim without taking long presses from its content.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .clip(shape)
                .then(
                    if (glassActive) Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = DialogEffects,
                        highlight = null,
                    ) else Modifier,
                )
                .edgeLight(shape = shape, edgeLight = rememberDefaultEdgeLight(baseColor = colors.surface))
                .background(colors.surface.copy(alpha = if (glassActive) glassAlpha else 1f), shape)
                .padding(24.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                color = colors.onSurface,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            )
            if (summary != null) {
                Text(
                    text = summary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    color = colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 16.sp, textAlign = TextAlign.Center),
                )
            }
            content()
        }
    }
}

/** NexioSchedule's paired dialog actions and MiuiX TextButton defaults. */
@Composable
fun NexioDeleteDialogActions(
    confirmLabel: String,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NexioDialogButton("取消", colors.onSurface, Modifier.weight(1f), enabled, onCancel)
        NexioDialogButton(confirmLabel, colors.error, Modifier.weight(1f), enabled, onConfirm)
    }
}

/** Neutral cancel and a filled primary action, as in NexioSchedule's date jump. */
@Composable
fun NexioPickerActions(
    confirmLabel: String,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NexioDialogButton("取消", colors.onSurface, Modifier.weight(1f), enabled, onCancel)
        NexioDialogButton(
            confirmLabel,
            if (confirmEnabled) colors.onPrimary else colors.onSurfaceVariant,
            Modifier.weight(1f),
            enabled && confirmEnabled,
            onConfirm,
            fill = if (confirmEnabled) colors.primary else colors.onSurface.copy(alpha = 0.06f),
        )
    }
}

@Composable
private fun NexioDialogButton(
    label: String,
    color: Color,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    fill: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
) {
    val haptics = LocalHapticFeedback.current
    val shape = remember { ContinuousRoundedRectangle(16.dp) }
    Box(
        modifier.background(fill, shape)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .defaultMinSize(minWidth = 58.dp, minHeight = 40.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium))
    }
}
