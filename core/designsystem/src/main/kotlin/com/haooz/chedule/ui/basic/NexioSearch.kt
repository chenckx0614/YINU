// SearchBar, InputField and Card adapted from the NexioSchedule MiuiX fork.
// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.haooz.chedule.ui.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.haooz.chedule.ui.utils.isAppDarkTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.designsystem.YinuMenuIcons

private val SearchCapsule = ContinuousCapsule()
private val SearchCapsuleShape: () -> Shape = { SearchCapsule }
private val SearchCardShape = ContinuousRoundedRectangle(20.dp)
private val SearchFieldHeight = 45.dp
private val SearchGlassEffects: BackdropEffectScope.() -> Unit = {
    vibrancy()
    blur(8.dp.toPx())
    lens(6.dp.toPx(), 36.dp.toPx())
}

@Composable
fun NexioSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }
    val glass = isRenderEffectSupported()
    val dark = isAppDarkTheme()
    val activeSurface = if (dark) colors.surface else Color(0xFFFAFAFA)
    val idleSurface = if (dark) colors.surfaceVariant else Color(0xFFE7E5DF)
    val fieldColor by animateColorAsState(
        if (focused) activeSurface.copy(alpha = if (glass) 0.76f else 1f) else idleSurface,
        tween(220), label = "Nexio search field surface",
    )
    val backdropAlpha = animateFloatAsState(
        if (focused) 1f else 0f, tween(220), label = "Nexio search glass",
    )
    val showFieldBlur by remember { derivedStateOf { backdropAlpha.value > 0f } }
    val glassLayer: GraphicsLayerScope.() -> Unit = remember { { alpha = backdropAlpha.value } }
    val textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }.semantics { contentDescription = "搜索正文、链接或文件名" },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { input ->
                Box {
                    if (glass) {
                        Box(
                            Modifier.matchParentSize().then(
                                if (showFieldBlur) Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = SearchCapsuleShape,
                                    effects = SearchGlassEffects,
                                    highlight = null,
                                    shadow = null,
                                    layerBlock = glassLayer,
                                ) else Modifier,
                            )
                                .edgeLight(shape = SearchCapsule, edgeLight = rememberDefaultEdgeLight()),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().background(fieldColor, SearchCapsule)
                            .heightIn(min = SearchFieldHeight).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchIcon(YinuMenuIcons.search, colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Box(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && !focused) {
                                BasicText(
                                    "搜索正文、链接或文件名",
                                    style = textStyle.copy(color = colors.onSurfaceVariant),
                                    maxLines = 1,
                                )
                            }
                            input()
                        }
                        AnimatedVisibility(value.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                            Box(
                                Modifier.size(44.dp).clip(SearchCapsule)
                                    .clickable(interactionSource = null, indication = null, role = Role.Button) {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        onValueChange("")
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(18.dp)
                                        .background(colors.onSurface.copy(alpha = 0.10f), SearchCapsule),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SearchIcon(YinuMenuIcons.close, colors.onSurfaceVariant, "清空关键词", Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            },
        )
        AnimatedVisibility(
            visible = focused,
            enter = expandHorizontally() + slideInHorizontally { it },
            exit = shrinkHorizontally() + slideOutHorizontally { it },
        ) {
            val scale by transition.animateFloat(transitionSpec = { tween(300) }, label = "Nexio close scale") {
                if (it == EnterExitState.Visible) 1f else 0.6f
            }
            val blurRadius by transition.animateDp(transitionSpec = { tween(300) }, label = "Nexio close blur") {
                if (it == EnterExitState.Visible) 0.dp else 7.dp
            }
            LiquidTopBarButton(
                onClick = onCancel,
                backdrop = backdrop,
                icon = YinuMenuIcons.close,
                contentDescription = "取消搜索",
                modifier = Modifier.padding(start = 8.dp).blur(blurRadius).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = scale
                },
                buttonHeight = SearchFieldHeight,
                iconSize = 23.dp,
                shadowAlpha = 0f,
            )
        }
    }
}

/** Filter tab surface and sizing from NexioSchedule's EducationalImportScreen. */
@Composable
fun NexioSearchFilter(
    label: String,
    active: Boolean,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (active) colors.primary else colors.onSurface.copy(alpha = 0.06f),
        tween(200), label = "Nexio filter surface",
    )
    val ink = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.45f)
        active -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    Row(
        Modifier.clip(SearchCardShape).background(fill)
            .semantics { selected = active }
            .clickable(enabled = enabled, interactionSource = null, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .heightIn(min = 35.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SearchIcon(icon, ink, modifier = Modifier.size(16.dp))
        BasicText(label, style = TextStyle(color = ink, fontSize = 14.sp))
    }
}

/** MiuiX Card's continuous surface, with YINU's inherited content colors. */
@Composable
fun NexioSearchCard(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val fill by animateColorAsState(
        if (active) colors.primaryContainer else colors.surface,
        tween(200), label = "Nexio card surface",
    )
    Column(
        modifier.clip(SearchCardShape).background(fill)
            .then(
                if (onClick != null) Modifier.semantics { selected = active }
                    .clickable(interactionSource = null, indication = null, role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onClick()
                    } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun NexioSearchLabel(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(
        text, modifier,
        style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun NexioSearchStatus(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    BasicText(
        text,
        modifier.then(
            if (onRetry != null) Modifier.clip(SearchCardShape).background(colors.surface)
                .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onRetry)
            else Modifier,
        ).padding(horizontal = 20.dp, vertical = 12.dp),
        style = TextStyle(
            color = if (onRetry == null) colors.onSurfaceVariant else colors.primary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
private fun SearchIcon(
    icon: ImageVector,
    ink: Color,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Image(icon, description, modifier, colorFilter = ColorFilter.tint(ink))
}
