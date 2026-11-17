package com.yinu.app.records

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.haooz.chedule.ui.basic.LiquidTopBarButton
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.yinu.data.Attachment
import com.yinu.designsystem.YinuMenuIcons
import kotlin.math.pow

internal class AttachmentPreviewTarget(
    val attachment: Attachment,
    val origin: () -> Rect?,
    val thumbnail: Painter?,
    val inSearch: Boolean,
) {
    val startBounds = origin()
    var hideOriginal by mutableStateOf(true)
}

internal val LocalHiddenPreviewAttachment = staticCompositionLocalOf<String?> { null }

private val ViewerBackground = Color(0xFF1A1A18)
private val ViewerInk = Color(0xFFF7F6F0)
private val ViewerControl = Color(0xE6333330)

// Course card morph timings from NexioSchedule.
internal val PreviewEnter = tween<Float>(540, easing = Easing { 1f - (1f - it).pow(4.5f) })
internal val PreviewExit = tween<Float>(320, easing = Easing { 1f - (1f - it).pow(3) })

@Composable
internal fun ImagePreview(
    target: AttachmentPreviewTarget,
    records: RecordsViewModel,
    onClose: () -> Unit,
    notify: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var gallery by remember(target) { mutableStateOf(listOf(target.attachment)) }
    LaunchedEffect(target) {
        try {
            val images = records.imageGallery()
            if (images.any { it.id == target.attachment.id }) gallery = images
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notify("图片列表暂时无法加载，仍可查看当前图片") }
    }
    val pager = key(gallery) {
        rememberPagerState(initialPage = gallery.indexOfFirst { it.id == target.attachment.id }.coerceAtLeast(0)) { gallery.size }
    }
    var closingImage by remember { mutableStateOf<Attachment?>(null) }
    var markingImage by remember { mutableStateOf<Attachment?>(null) }
    val attachment = closingImage ?: gallery[pager.currentPage.coerceIn(gallery.indices)]
    val original = attachment.id == target.attachment.id
    SideEffect { target.hideOriginal = original }
    val backdrop = rememberLayerBackdrop()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var returnBounds by remember { mutableStateOf<Rect?>(null) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var window by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val status = WindowInsets.statusBars.getTop(density)
    val navigation = WindowInsets.navigationBars.getBottom(density)
    val viewport = with(density) {
        val side = 12.dp.toPx()
        val top = status + 60.dp.toPx()
        Rect(side, top, (window.width - side).coerceAtLeast(side + 1f),
            (window.height - navigation - 64.dp.toPx()).coerceAtLeast(top + 1f))
    }
    val request = remember(attachment.localFile, window, context) {
        if (window.width > 0 && window.height > 0 && attachment.localFile != null) {
            ImageRequest.Builder(context)
                .data(attachment.localFile)
                .size(window.width, window.height)
                .build()
        } else null
    }
    val fullPainter = rememberAsyncImagePainter(request)
    val imageState by fullPainter.state.collectAsState()
    val loaded = (imageState as? AsyncImagePainter.State.Success)?.painter
    val settled by remember { derivedStateOf { progress.value == 1f && !closing } }
    val thumbnail = target.thumbnail.takeIf { original }
    val painter = if (settled || closing) loaded ?: thumbnail else thumbnail ?: loaded
    val intrinsic = painter?.intrinsicSize
    val imageSize = intrinsic?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
        ?: Size(1f, 1f)
    val fit = fitPhoto(viewport, imageSize)

    fun close() {
        if (closing) return
        closingImage = attachment
        returnBounds = if (original) target.origin() else null
        closing = true
    }
    BackHandler(onBack = ::close)
    LaunchedEffect(attachment.id) { zoom = 1f; pan = Offset.Zero }
    LaunchedEffect(closing) {
        if (closing) {
            progress.animateTo(0f, PreviewExit)
            onClose()
        } else {
            progress.animateTo(1f, PreviewEnter)
        }
    }

    val transform = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 5f)
        val pivot = if (centroid.isSpecified) centroid - fit.center else Offset.Zero
        val nextPan = pivot + (pan - pivot) * (nextZoom / zoom) + panChange
        zoom = nextZoom
        val maxX = (fit.width * zoom - viewport.width).coerceAtLeast(0f) / 2f
        val maxY = (fit.height * zoom - viewport.height).coerceAtLeast(0f) / 2f
        pan = Offset(
            nextPan.x.coerceIn(-maxX, maxX),
            nextPan.y.coerceIn(-maxY, maxY),
        )
    }

    markingImage?.let { image ->
        androidx.compose.material3.MaterialTheme(colorScheme = com.yinu.designsystem.yinuColorScheme(true)) {
            ImageMarkupEditor(image, onClose = { markingImage = null }, notify = notify)
        }
        return
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { window = it }
            .onGloballyPositioned { rootOffset = it.positionInRoot() }
            .semantics { paneTitle = "图片预览" }
            .pointerInput(settled, painter != null) {
                detectTapGestures(
                    onTap = { close() },
                    onDoubleTap = if (settled && painter != null) {
                        {
                            zoom = if (zoom > 1f) 1f else 2.5f
                            pan = Offset.Zero
                        }
                    } else null,
                )
            }
            .transformable(transform, canPan = { zoom > 1f }, enabled = settled && painter != null),
    ) {
        if (settled) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = zoom <= 1f,
                key = { gallery[it].id },
                modifier = Modifier.fillMaxSize().testTag("image-gallery")
                    .then(if (isRenderEffectSupported()) Modifier.layerBackdrop(backdrop) else Modifier),
            ) { index ->
                GalleryPhoto(gallery[index], window, viewport,
                    if (index == pager.currentPage) zoom else 1f,
                    if (index == pager.currentPage) pan else Offset.Zero,
                    target.thumbnail.takeIf { gallery[index].id == target.attachment.id })
            }
        } else {
        Canvas(
            Modifier.fillMaxSize()
                .then(if (isRenderEffectSupported()) Modifier.layerBackdrop(backdrop) else Modifier)
                .semantics { contentDescription = attachment.fileName },
        ) {
            val fraction = progress.value
            drawRect(ViewerBackground.copy(alpha = fraction))
            if (painter != null && window != IntSize.Zero) {
                val origin = (if (closing) returnBounds else target.startBounds)?.translate(-rootOffset)
                val start = origin ?: fit.scaleAroundCenter(0.9f)
                val frame = Rect(
                    lerp(start.left, fit.left, fraction),
                    lerp(start.top, fit.top, fraction),
                    lerp(start.right, fit.right, fraction),
                    lerp(start.bottom, fit.bottom, fraction),
                ).scaleAroundCenter(lerp(1f, zoom, fraction)).translate(pan * fraction)
                val cover = maxOf(frame.width / imageSize.width, frame.height / imageSize.height)
                val drawn = imageSize * cover
                val path = Path().apply {
                    addRoundRect(RoundRect(frame, CornerRadius(lerp(12.dp.toPx(), 22.dp.toPx(), fraction))))
                }
                clipPath(path) {
                    translate(frame.center.x - drawn.width / 2f, frame.center.y - drawn.height / 2f) {
                        with(painter) { draw(drawn, alpha = if (origin == null) fraction else 1f) }
                    }
                }
            }
        }
        }

        val chrome = Modifier.graphicsLayer { alpha = ((progress.value - 0.38f) / 0.62f).coerceIn(0f, 1f) }
        Row(
            chrome.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LiquidTopBarButton(
                onClick = ::close,
                backdrop = backdrop,
                icon = YinuMenuIcons.back,
                contentDescription = "返回",
                iconOffset = DpOffset((-2).dp, 0.dp),
                iconTint = ViewerInk,
                containerColor = ViewerControl,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidTopBarButton(
                    onClick = { markingImage = attachment },
                    backdrop = backdrop,
                    icon = YinuMenuIcons.edit,
                    contentDescription = "标注图片",
                    iconTint = ViewerInk,
                    containerColor = ViewerControl,
                    enabled = settled && !pager.isScrollInProgress && attachment.localFile != null,
                )
                LiquidTopBarButton(
                    onClick = { sendAttachment(context, attachment, share = true, notify) },
                    backdrop = backdrop,
                    icon = YinuMenuIcons.share,
                    contentDescription = "分享图片",
                    iconTint = ViewerInk,
                    containerColor = ViewerControl,
                    enabled = settled,
                )
            }
        }

        Row(
            chrome.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 20.dp, end = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(attachment.fileName, color = ViewerInk, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pager.currentPage + 1} / ${gallery.size} · ${Formatter.formatShortFileSize(context, attachment.fileSize)}",
                    color = ViewerInk.copy(alpha = 0.65f), fontSize = 12.sp)
            }
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    sendAttachment(context, attachment, share = false, notify)
                },
                enabled = settled,
            ) { Text("外部打开", color = ViewerInk) }
        }

    }
}

@Composable
private fun GalleryPhoto(attachment: Attachment, window: IntSize, viewport: Rect, zoom: Float, pan: Offset, thumbnail: Painter?) {
    val context = LocalContext.current
    val request = remember(attachment.localFile, window) {
        ImageRequest.Builder(context).data(attachment.localFile)
            .size(window.width.coerceAtLeast(1), window.height.coerceAtLeast(1)).build()
    }
    val full = rememberAsyncImagePainter(request)
    val state by full.state.collectAsState()
    val painter = (state as? AsyncImagePainter.State.Success)?.painter ?: thumbnail
    Box(Modifier.fillMaxSize().background(ViewerBackground)) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = attachment.fileName }) {
            if (painter != null) {
                val image = painter.intrinsicSize.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 } ?: Size(1f, 1f)
                val frame = fitPhoto(viewport, image).scaleAroundCenter(zoom).translate(pan)
                val cover = maxOf(frame.width / image.width, frame.height / image.height)
                val drawn = image * cover
                clipPath(Path().apply { addRoundRect(RoundRect(frame, CornerRadius(22.dp.toPx()))) }) {
                    translate(frame.center.x - drawn.width / 2f, frame.center.y - drawn.height / 2f) {
                        with(painter) { draw(drawn) }
                    }
                }
            }
        }
        if (painter == null) {
            if (state is AsyncImagePainter.State.Error) Text("无法预览此图片", Modifier.align(Alignment.Center), color = ViewerInk)
            else CircularProgressIndicator(Modifier.align(Alignment.Center), color = ViewerInk)
        }
    }
}

private fun fitPhoto(viewport: Rect, image: Size): Rect {
    val scale = minOf(viewport.width / image.width, viewport.height / image.height)
    val size = image * scale
    return Rect(viewport.center - Offset(size.width / 2f, size.height / 2f), size)
}

private fun Rect.scaleAroundCenter(scale: Float): Rect {
    val scaled = size * scale
    return Rect(center - Offset(scaled.width / 2f, scaled.height / 2f), scaled)
}
