package com.yinu.app.records

import android.graphics.Bitmap
import android.graphics.Canvas as BitmapCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.haooz.chedule.ui.basic.LiquidTopBarButton
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.yinu.data.Attachment
import com.yinu.designsystem.YinuMenuIcons
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MarkupTool(val label: String) {
    Pen("画笔"), Circle("圈选"), Arrow("箭头"), Cover("遮挡")
}

internal data class ImageMark(val tool: MarkupTool, val points: List<Offset>)

@Composable
internal fun ImageMarkupEditor(attachment: Attachment, onClose: () -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf(MarkupTool.Pen) }
    val marks = remember { mutableStateListOf<ImageMark>() }
    val draft = remember { mutableStateListOf<Offset>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var discardShowing by remember { mutableStateOf(false) }
    val ink = Color(0xFFF7F6F0)

    LaunchedEffect(attachment) {
        try {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(checkNotNull(attachment.localFile))
                    .size(3072, 3072).allowHardware(false).build(),
            ) as? SuccessResult ?: error("Image decode failed")
            bitmap = withContext(Dispatchers.Default) { result.image.toBitmap() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            error = "内存不足，无法编辑这张图片"
        } catch (_: Exception) {
            error = "无法读取这张图片"
        }
    }

    fun requestClose() {
        if (busy) return
        if (marks.isEmpty()) onClose()
        else { discardDialog = true; discardShowing = true }
    }
    BackHandler(onBack = ::requestClose)

    val stem = attachment.fileName.substringBeforeLast('.').take(70).ifBlank { "图片" }
    val outputName = "$stem-标注.png"
    fun export(write: suspend (Bitmap, List<ImageMark>) -> Unit) {
        val source = bitmap ?: return
        if (busy || marks.isEmpty()) return
        val snapshot = marks.toList()
        busy = true
        scope.launch {
            try {
                write(source, snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                notify("内存不足，无法生成标注图片")
            } catch (_: Exception) {
                notify("导出失败，请检查存储空间")
            } finally {
                busy = false
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) export { source, snapshot ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { stream ->
                    writeMarkedImage(source, snapshot, stream)
                }
            }
            notify("已另存标注图片")
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF1A1A18)).statusBarsPadding().navigationBarsPadding()
            .semantics { paneTitle = "图片标注" },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidTopBarButton(::requestClose, backdrop, YinuMenuIcons.back, "返回图片预览",
                iconTint = ink, containerColor = Color(0xE6333330), enabled = !busy)
            Text("图片标注", Modifier.padding(start = 12.dp).weight(1f), color = ink)
            NexioSearchFilter("撤销", false, enabled = marks.isNotEmpty() && !busy) { marks.removeAt(marks.lastIndex) }
        }
        val source = bitmap
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (source == null) {
                Text(error ?: "正在读取图片…", color = ink)
            } else {
                val bounds = remember(source, canvasSize) {
                    val scale = minOf(canvasSize.width.toFloat() / source.width, canvasSize.height.toFloat() / source.height)
                    val width = source.width * scale
                    val height = source.height * scale
                    Rect(Offset((canvasSize.width - width) / 2, (canvasSize.height - height) / 2), Size(width, height))
                }
                fun normalized(point: Offset) = Offset(
                    ((point.x - bounds.left) / bounds.width).coerceIn(0f, 1f),
                    ((point.y - bounds.top) / bounds.height).coerceIn(0f, 1f),
                )
                Canvas(
                    Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
                        .then(if (isRenderEffectSupported()) Modifier.layerBackdrop(backdrop) else Modifier)
                        .semantics { contentDescription = "标注画布" }
                        .pointerInput(tool, bounds, busy) {
                            if (!busy && !bounds.isEmpty) detectDragGestures(
                                onDragStart = { point ->
                                    draft.clear()
                                    if (bounds.contains(point)) draft += normalized(point)
                                },
                                onDragCancel = { draft.clear() },
                                onDragEnd = {
                                    if (draft.size > 1 && (tool == MarkupTool.Pen || draft.first() != draft.last())) {
                                        marks += ImageMark(tool, draft.toList())
                                    }
                                    draft.clear()
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (draft.isNotEmpty()) {
                                        val point = normalized(change.position)
                                        if (tool != MarkupTool.Pen && draft.size == 2) draft[1] = point
                                        else draft += point
                                    }
                                },
                            )
                        },
                ) {
                    if (!bounds.isEmpty) drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                        native.translate(bounds.left, bounds.top)
                        native.drawBitmap(source, null, RectF(0f, 0f, bounds.width, bounds.height), Paint(Paint.FILTER_BITMAP_FLAG))
                        drawImageMarks(native, marks, bounds.width, bounds.height)
                        if (draft.size > 1) drawImageMarks(native, listOf(ImageMark(tool, draft)), bounds.width, bounds.height)
                        native.restore()
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkupTool.entries.forEach { choice ->
                NexioSearchFilter(choice.label, choice == tool, enabled = !busy) { tool = choice }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NexioSearchFilter("另存副本", false, enabled = marks.isNotEmpty() && !busy) {
                try { save.launch(outputName) } catch (_: Exception) { notify("无法打开文件选择器") }
            }
            NexioSearchFilter("分享标注图", false, enabled = marks.isNotEmpty() && !busy) {
                export { image, snapshot ->
                    withContext(Dispatchers.IO) {
                        val directory = File(context.cacheDir, "long_exports/${UUID.randomUUID()}")
                        check(directory.mkdirs())
                        val file = File(directory, "marked.png")
                        try {
                            file.outputStream().use { writeMarkedImage(image, snapshot, it) }
                        } catch (error: Throwable) {
                            file.delete()
                            directory.delete()
                            throw error
                        }
                        file
                    }.let { file ->
                        sendAttachment(context, attachment.copy(
                            fileName = outputName, mimeType = "image/png", localFile = file, fileSize = file.length(),
                        ), share = true, notify = notify)
                    }
                }
            }
            if (busy) Text("导出中…", color = ink)
        }
    }
    if (discardDialog) {
        NexioOverlayDialog(
            show = discardShowing, title = "退出标注？", summary = "退出会清除当前标注草稿。",
            backdrop = backdrop, onDismissRequest = { discardShowing = false },
            onDismissFinished = { discardDialog = false },
        ) {
            NexioPickerActions("退出", discardShowing, onCancel = { discardShowing = false }, onConfirm = onClose)
        }
    }
}

internal fun writeMarkedImage(source: Bitmap, marks: List<ImageMark>, output: java.io.OutputStream) {
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = BitmapCanvas(result)
        canvas.drawBitmap(source, null, RectF(0f, 0f, result.width.toFloat(), result.height.toFloat()), null)
        drawImageMarks(canvas, marks, result.width.toFloat(), result.height.toFloat())
        check(result.compress(Bitmap.CompressFormat.PNG, 100, output))
    } finally {
        result.recycle()
    }
}

private fun drawImageMarks(canvas: BitmapCanvas, marks: List<ImageMark>, width: Float, height: Float) {
    val stroke = minOf(width, height) * 0.008f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 80, 67)
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    for (mark in marks) {
        if (mark.points.size < 2) continue
        val first = mark.points.first()
        val last = mark.points.last()
        val a = Offset(first.x * width, first.y * height)
        val b = Offset(last.x * width, last.y * height)
        val rect = RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        when (mark.tool) {
            MarkupTool.Pen -> {
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    mark.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
                }
                canvas.drawPath(path, paint)
            }
            MarkupTool.Circle -> canvas.drawOval(rect, paint)
            MarkupTool.Arrow -> {
                val length = (b - a).getDistance()
                if (length == 0f) continue
                val direction = (b - a) / length
                val head = minOf(stroke * 5f, length / 2f)
                val base = b - direction * head
                val wing = Offset(-direction.y, direction.x) * (head / 2f)
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                canvas.drawLine(b.x, b.y, base.x + wing.x, base.y + wing.y, paint)
                canvas.drawLine(b.x, b.y, base.x - wing.x, base.y - wing.y, paint)
            }
            MarkupTool.Cover -> {
                val cover = Paint().apply { color = android.graphics.Color.BLACK }
                canvas.drawRect(floor(rect.left), floor(rect.top), ceil(rect.right), ceil(rect.bottom), cover)
            }
        }
    }
}
