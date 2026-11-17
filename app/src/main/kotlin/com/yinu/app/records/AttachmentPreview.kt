package com.yinu.app.records

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.haooz.chedule.ui.basic.LiquidTopBarButton
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.yinu.designsystem.YinuMenuIcons
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DocumentHeaderHeight = 58.dp
private val PreviewTopLeft = TransformOrigin(0f, 0f)

@Composable
internal fun AttachmentPreview(
    target: AttachmentPreviewTarget,
    onClose: () -> Unit,
    notify: (String) -> Unit,
) {
    val context = LocalContext.current
    val attachment = target.attachment
    val progress = remember(target) { Animatable(0f) }
    var closing by remember(target) { mutableStateOf(false) }
    var returnBounds by remember(target) { mutableStateOf<Rect?>(null) }
    var openExternally by remember(target) { mutableStateOf(false) }
    var viewerBounds by remember { mutableStateOf(Rect.Zero) }

    fun close(externally: Boolean = false) {
        if (closing) return
        returnBounds = if (progress.value < 1f) target.startBounds else target.origin()
        openExternally = externally
        closing = true
    }

    BackHandler { close() }
    LaunchedEffect(target, closing) {
        if (closing) {
            progress.animateTo(0f, PreviewExit)
            onClose()
            if (openExternally) sendAttachment(context, attachment, share = false, notify)
        } else {
            progress.animateTo(1f, PreviewEnter)
        }
    }
    // The preview owns the surface behind the system bars, including three-button navigation.
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val restoreContrast = window.isNavigationBarContrastEnforced
            window.isNavigationBarContrastEnforced = false
            onDispose { window.isNavigationBarContrastEnforced = restoreContrast }
        } else onDispose {}
    }
    val backdrop = rememberLayerBackdrop()
    val colors = MaterialTheme.colorScheme
    val cardColor = if (target.inSearch) colors.onSurface.copy(alpha = 0.06f).compositeOver(colors.surface) else colors.surface
    val file = attachment.localFile
    val viewport = Rect(Offset.Zero, viewerBounds.size)
    val origin = if (closing) returnBounds else target.startBounds
    val card = origin?.translate(-viewerBounds.topLeft)?.let {
        if (viewport.isEmpty) it else it.intersect(viewport).takeUnless(Rect::isEmpty)
    }
    val frame by remember(card, viewport, progress) {
        derivedStateOf {
            val fraction = progress.value
            if (card == null || viewport.isEmpty || fraction == 1f) viewport.takeUnless(Rect::isEmpty) ?: card
            else Rect(
                lerp(card.left, viewport.left, fraction),
                lerp(card.top, viewport.top, fraction),
                lerp(card.right, viewport.right, fraction),
                lerp(card.bottom, viewport.bottom, fraction),
            )
        }
    }
    val showSourceCard by remember(progress) { derivedStateOf { progress.value < 0.74f } }
    val mediaReady by remember(progress) { derivedStateOf { progress.value >= 0.99f } }
    val media = attachment.isVideo || attachment.isAudio
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize()
            .semantics { paneTitle = if (media) "媒体预览" else "文档预览" }
            .onGloballyPositioned { viewerBounds = Rect(it.positionInRoot(), it.size.toSize()) }
            // Block touches on the timeline behind this layer without consuming text selection.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        if (card != null) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Color.Black.copy(alpha = 0.20f * progress.value))
            }
        }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (card == null) progress.value else 1f }
                .drawWithContent {
                    val bounds = frame
                    val fraction = progress.value
                    if (card == null || bounds == null || fraction == 1f) {
                        drawContent()
                    } else {
                        val startRadius = if (target.inSearch) 14.dp else 12.dp
                        val edgeJoin = ((1f - fraction) / 0.06f).coerceIn(0f, 1f)
                        val radius = CornerRadius(lerp(startRadius.toPx(), 26.dp.toPx(), fraction) * edgeJoin)
                        val path = Path().apply { addRoundRect(RoundRect(bounds, radius)) }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    }
                },
        ) {
            // Only the surface is captured for the viewer's glass buttons.
            Box(
                Modifier.matchParentSize()
                    .then(if (isRenderEffectSupported()) Modifier.layerBackdrop(backdrop) else Modifier)
                    .drawBehind {
                        drawRect(if (card == null) colors.background else lerpColor(cardColor, colors.background, progress.value))
                    },
            )
            Column(
                Modifier.fillMaxSize()
                    .absoluteOffset {
                        val position = frame?.topLeft ?: Offset.Zero
                        IntOffset(position.x.roundToInt(), position.y.roundToInt())
                    }
                    .graphicsLayer {
                        val bounds = frame
                        if (card != null && bounds != null && viewport.width > 0f) {
                            // The page follows the frame without stretching text as the aspect ratio changes.
                            transformOrigin = PreviewTopLeft
                            scaleX = bounds.width / viewport.width
                            scaleY = scaleX
                            alpha = ((progress.value - 0.14f) / 0.72f).coerceIn(0f, 1f)
                        }
                    }
                    .safeDrawingPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(DocumentHeaderHeight).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidTopBarButton(
                        onClick = { close() },
                        backdrop = backdrop,
                        icon = YinuMenuIcons.back,
                        contentDescription = "返回",
                        iconOffset = DpOffset((-2).dp, 0.dp),
                    )
                    Text(
                        attachment.fileName,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LiquidTopBarButton(
                        onClick = { close(externally = true) },
                        backdrop = backdrop,
                        icon = YinuMenuIcons.externalOpen,
                        contentDescription = "外部打开",
                        iconSize = 21.dp,
                        enabled = file != null && !closing,
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        file == null -> Text("本地文件不存在", Modifier.align(Alignment.Center))
                        attachment.isDocx -> DocxPreview(file)
                        attachment.isPdf -> PdfPreview(file)
                        attachment.isPlainText -> PlainTextPreview(file)
                        media && mediaReady && !closing -> MediaPreview(file, attachment.isVideo)
                        else -> Unit
                    }
                }
            }
            if (card != null && showSourceCard) {
                AttachmentCardContent(
                    attachment = attachment,
                    inSearch = target.inSearch,
                    modifier = Modifier.width(with(density) { card.width.toDp() })
                        .clearAndSetSemantics { hideFromAccessibility() }
                        .graphicsLayer {
                            val bounds = frame ?: card
                            translationX = bounds.left
                            translationY = bounds.top
                            alpha = 1f - ((progress.value - 0.18f) / 0.55f).coerceIn(0f, 1f)
                        },
                )
            }
        }
    }
}

private sealed interface PdfState {
    data object Loading : PdfState
    data object Failed : PdfState
    data class Loaded(val bitmap: Bitmap, val pageCount: Int) : PdfState
}

@Composable
private fun PdfPreview(file: File) {
    val haptics = LocalHapticFeedback.current
    var page by rememberSaveable(file.path) { mutableIntStateOf(0) }
    val state by produceState<PdfState>(PdfState.Loading, file, page) {
        value = PdfState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                renderPdfPage(file, page)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                PdfState.Failed
            }
        }
    }
    val loaded = state as? PdfState.Loaded
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(12.dp), contentAlignment = Alignment.Center) {
            when (val preview = state) {
                PdfState.Loading -> CircularProgressIndicator()
                PdfState.Failed -> Text("无法预览此 PDF")
                is PdfState.Loaded -> Image(
                    preview.bitmap.asImageBitmap(), "第 ${page + 1} 页", Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(enabled = page > 0 && state != PdfState.Loading, onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                page--
            }) { Text("上一页") }
            Text(if (loaded == null) "第 ${page + 1} 页" else "${page + 1} / ${loaded.pageCount}")
            TextButton(enabled = loaded != null && page + 1 < loaded.pageCount, onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                page++
            }) { Text("下一页") }
        }
    }
}

private fun renderPdfPage(file: File, index: Int): PdfState.Loaded {
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(index).use { page ->
                val scale = 1800f / maxOf(page.width, page.height)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val transform = Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                PdfState.Loaded(bitmap, renderer.pageCount)
            }
        }
    }
}
