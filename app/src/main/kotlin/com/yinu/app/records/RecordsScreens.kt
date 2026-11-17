package com.yinu.app.records

import top.yukonga.miuix.kmp.utils.overScrollVertical

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yinu.data.RecordQuote
import com.yinu.data.SearchCategory
import com.yinu.designsystem.YinuBottomBlur
import com.yinu.designsystem.YinuMenuIcons
import com.haooz.chedule.ui.basic.LiquidTopBarButton
import com.haooz.chedule.ui.basic.NexioSearchCard
import com.haooz.chedule.ui.basic.NexioSearchLabel
import com.haooz.chedule.ui.basic.NexioSearchStatus
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

internal class RecordLocationRequest(val messageId: String)

@Composable
internal fun RecordPage(
    records: RecordsViewModel,
    topPadding: Dp,
    bottomInsets: WindowInsets,
    active: Boolean,
    onPreview: (AttachmentPreviewTarget) -> Unit,
    onLocateRecord: (String) -> Unit,
    recordLocation: RecordLocationRequest?,
    onLocationHandled: (RecordLocationRequest) -> Unit,
    onInputFocus: (Boolean) -> Unit,
) {
    val timeline by records.timeline.collectAsStateWithLifecycle()
    val selecting = LocalRecordSelection.current.active
    val pinned = records.pinned.collectAsStateWithLifecycle().value
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val threshold = with(density) { 96.dp.roundToPx() }
    val latestTimeline by rememberUpdatedState(timeline)
    val latestLocation by rememberUpdatedState(recordLocation)
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val state = latestTimeline as? RecordsState.Loaded ?: return@snapshotFlow null
            if (latestLocation != null || !state.hasOlder) return@snapshotFlow null
            val visible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            state.window.takeIf { visible >= state.records.size - 6 }
        }.collect { window -> window?.let(records::loadOlder) }
    }
    val showLatest by remember(listState, threshold) {
        derivedStateOf {
            listState.canScrollBackward &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= threshold)
        }
    }
    var composerHeight by remember { mutableStateOf(60.dp) }
    val bottomPadding = bottomInsets.asPaddingValues().calculateBottomPadding() +
        (if (selecting) 0.dp else composerHeight)
    val keyboardBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        // The list extends under the controls. The blur samples only this layer.
        Box(
            Modifier.fillMaxSize()
                .then(if (isRenderEffectSupported()) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MaterialTheme.colorScheme.background),
        ) {
            RecordList(
                state = timeline,
                emptyText = "还没有记录",
                topPadding = if (pinned == null) topPadding else topPadding + PinnedRecordHeight + 18.dp,
                bottomPadding = bottomPadding,
                newestAtBottom = true,
                viewModel = records,
                onPreview = onPreview,
                onLocateQuote = onLocateRecord,
                recordLocation = recordLocation.takeIf { active },
                onLocationHandled = onLocationHandled,
                listState = listState,
                modifier = Modifier.fillMaxSize(),
                animateSends = active,
            )
        }
        if (pinned != null) {
            PinnedRecordBanner(
                record = pinned,
                backdrop = backdrop,
                onLocate = {
                    if (active && !selecting) {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onLocateRecord(pinned.id)
                    }
                },
                onUnpin = {
                    if (active && !selecting) {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        records.setPinned(pinned.id, false)
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = topPadding + 12.dp),
            )
        }
        YinuBottomBlur(
            backdrop = backdrop,
            bottomPadding = bottomPadding,
            keyboardBottom = keyboardBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        AnimatedVisibility(
            visible = active && !selecting && showLatest && recordLocation == null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding + 10.dp),
            enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.8f),
            exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.8f),
        ) {
            LiquidTopBarButton(
                onClick = {
                    focus.clearFocus()
                    scope.launch {
                        listState.animateScrollToItem(0)
                        records.returnToLatest()
                    }
                },
                backdrop = backdrop,
                icon = YinuMenuIcons.down,
                contentDescription = "回到最新记录",
                buttonHeight = 38.dp,
                iconSize = 20.dp,
            )
        }
        if (!selecting) {
            RecordComposer(
                records = records,
                backdrop = backdrop,
                active = active,
                onInputFocus = onInputFocus,
                onLocateQuote = onLocateRecord,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottomInsets)
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

@Composable
internal fun RecordList(
    state: RecordsState,
    emptyText: String,
    topPadding: Dp,
    newestAtBottom: Boolean,
    viewModel: RecordsViewModel,
    onPreview: (AttachmentPreviewTarget) -> Unit,
    onLocateQuote: (String) -> Unit,
    modifier: Modifier = Modifier,
    category: SearchCategory? = null,
    bottomPadding: Dp = 0.dp,
    recordLocation: RecordLocationRequest? = null,
    onLocationHandled: (RecordLocationRequest) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    animateSends: Boolean = false,
) {
    val rows = (state as? RecordsState.Loaded)?.records.orEmpty()
    val dissolving = viewModel.dissolving.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    val latestOnLocationHandled by rememberUpdatedState(onLocationHandled)
    var highlighted by remember { mutableStateOf<RecordLocationRequest?>(null) }
    val highlight = remember { Animatable(0f) }
    val highlightColor = MaterialTheme.colorScheme.primary
    val newestId = if (newestAtBottom) rows.lastOrNull()?.id else rows.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && recordLocation == null) listState.scrollToItem(0)
    }
    LaunchedEffect(recordLocation) {
        val request = recordLocation ?: return@LaunchedEffect
        try {
            val needed = viewModel.includeInTimeline(request.messageId)
            if (needed == null) {
                viewModel.notify("原消息已删除或找不到")
                return@LaunchedEffect
            }
            val timeline = snapshotFlow { latestState }.first {
                it is RecordsState.Failed || it is RecordsState.Loaded && it.window >= needed
            }
            if (timeline is RecordsState.Loaded) {
                val index = timeline.records.indexOfLast { it.id == request.messageId }
                if (index >= 0) {
                    listState.scrollToRecord(timeline.records.lastIndex - index)
                    highlight.snapTo(1f)
                    highlighted = request
                } else {
                    viewModel.notify("原消息已删除或找不到")
                }
            } else {
                viewModel.notify("暂时无法定位原消息")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            viewModel.notify("暂时无法定位原消息")
        } finally {
            latestOnLocationHandled(request)
        }
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(650)
            highlight.animateTo(0f, tween(700))
            highlighted = null
        }
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth().overScrollVertical().padding(top = topPadding, bottom = bottomPadding), contentAlignment = Alignment.Center) {
            if (!newestAtBottom) when (state) {
                RecordsState.Loading -> NexioSearchStatus("正在搜索…")
                RecordsState.Failed -> NexioSearchStatus("读取失败，点此重试", onRetry = viewModel::retry)
                RecordsState.Idle -> NexioSearchStatus("输入关键词查看记录")
                is RecordsState.Loaded -> NexioSearchStatus(emptyText)
            } else when (state) {
                RecordsState.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                RecordsState.Failed -> TextButton(onClick = viewModel::retry) { Text("读取失败，点此重试") }
                RecordsState.Idle -> Text("输入关键词查看记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                is RecordsState.Loaded -> Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val displayedRows = remember(rows, newestAtBottom) { if (newestAtBottom) rows.asReversed() else rows }
    LazyColumn(
        modifier = modifier.fillMaxWidth().sweepSelection(LocalRecordSelection.current, displayedRows, listState).overScrollVertical(),
        overscrollEffect = null,
        state = listState,
        reverseLayout = newestAtBottom,
        contentPadding = PaddingValues(
            start = if (newestAtBottom) 14.dp else 18.dp,
            end = if (newestAtBottom) 14.dp else 18.dp,
            top = topPadding + 10.dp,
            bottom = bottomPadding + 10.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp, if (newestAtBottom) Alignment.Bottom else Alignment.Top),
    ) {
        itemsIndexed(displayedRows, key = { _, record -> record.id }) { index, record ->
            val isDissolving by remember(dissolving, record.id) {
                derivedStateOf { record.id in dissolving.value }
            }
            if (!newestAtBottom) {
                NexioSearchCard(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = spring(dampingRatio = 0.9f, stiffness = 420f)).fillMaxWidth().then(recordDissolve(record.id, isDissolving))) {
                    NexioSearchLabel(timeLabel(record.createdAt), Modifier.padding(bottom = 8.dp))
                    RecordBubble(
                        record, viewModel, onPreview, onLocateQuote, category, inSearch = true,
                    )
                }
                return@itemsIndexed
            }
            val earlier = displayedRows.getOrNull(index + 1)
            val zone = ZoneId.systemDefault()
            val showTime = remember(record.createdAt, earlier?.createdAt, zone) {
                earlier == null || record.createdAt - earlier.createdAt >= 5 * 60_000L ||
                    localDate(earlier.createdAt) != localDate(record.createdAt)
            }
            if (!animateSends) LaunchedEffect(record.id) { viewModel.consumeBubbleAnimation(record.id) }
            Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                placementSpec = spring(dampingRatio = 0.9f, stiffness = 420f)).fillMaxWidth()) {
                if (showTime) {
                    Text(
                        timeLabel(record.createdAt),
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val located by remember(record.id) { derivedStateOf { highlighted?.messageId == record.id } }
                Box(
                    Modifier.then(if (located) Modifier.fillMaxWidth()
                        .drawBehind {
                            drawRoundRect(
                                highlightColor.copy(alpha = highlight.value * 0.20f),
                                cornerRadius = CornerRadius(12.dp.toPx()),
                            )
                        }
                        .semantics { stateDescription = "已定位" }
                    else Modifier).then(recordDissolve(record.id, isDissolving)),
                ) {
                    RecordBubble(
                        record, viewModel, onPreview, onLocateQuote, category,
                        animateSend = animateSends,
                    )
                }
            }
        }
    }
}

private val ComposerShape = ContinuousCapsule()
private val ComposerGlass: BackdropEffectScope.() -> Unit = {
    vibrancy()
    blur(14.dp.toPx())
}

private suspend fun LazyListState.scrollToRecord(index: Int) {
    val layout = snapshotFlow { layoutInfo }.first { it.totalItemsCount > 0 }
    val contentHeight = (layout.viewportSize.height - layout.beforeContentPadding - layout.afterContentPadding)
        .coerceAtLeast(0)
    val targetOffset = -contentHeight / 3
    val visible = layout.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible != null) {
        animateScrollBy((visible.offset + targetOffset).toFloat(), tween(380, easing = FastOutSlowInEasing))
    } else {
        animateScrollToItem(index, scrollOffset = targetOffset)
    }
}

@Composable
private fun RecordComposer(
    records: RecordsViewModel,
    backdrop: Backdrop,
    active: Boolean,
    onInputFocus: (Boolean) -> Unit,
    onLocateQuote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text by records.draft.collectAsStateWithLifecycle()
    val quote by records.quote.collectAsStateWithLifecycle()
    val links by records.links.collectAsStateWithLifecycle()
    val quickAction by records.quickAction.collectAsStateWithLifecycle()
    val sending by records.sending.collectAsStateWithLifecycle()
    val importing by records.importing.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    val voice = checkNotNull(LocalVoiceCapture.current)
    val context = LocalContext.current
    fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var microphoneAllowed by remember { mutableStateOf(hasMicrophonePermission()) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        microphoneAllowed = allowed
        if (!allowed) records.notify("需要麦克风权限才能录制语音")
    }
    fun requestMicrophone() {
        microphoneAllowed = hasMicrophonePermission()
        if (!microphoneAllowed) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picking = false
        records.importFiles(uris)
    }
    val menus = LocalRecordMenus.current
    var pickerAnchor by remember { mutableStateOf(Rect.Zero) }
    val colors = MaterialTheme.colorScheme
    val glass = isRenderEffectSupported()
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(quote, active) {
        if (quote != null && active && !voiceMode && focusRequester.requestFocus()) keyboard?.show()
    }
    LaunchedEffect(links, active) {
        if (links.isNotEmpty() && active && !voiceMode && focusRequester.requestFocus()) keyboard?.show()
    }
    LaunchedEffect(quickAction, active) {
        if (!active || quickAction == null) return@LaunchedEffect
        val action = quickAction
        records.consumeQuickAction()
        if (action == "image") {
            focus.clearFocus()
            if (!picking) {
                picking = true
                try { picker.launch(arrayOf("image/*")) }
                catch (_: Exception) { picking = false; records.notify("无法打开图片选择器") }
            }
        } else {
            if (voiceMode) {
                voiceMode = false
                androidx.compose.runtime.withFrameNanos { }
            }
            if (focusRequester.requestFocus()) keyboard?.show()
        }
    }

    fun select(mimeType: String) {
        if (picking) return
        focus.clearFocus()
        try {
            picking = true
            picker.launch(arrayOf(mimeType))
        } catch (_: Exception) {
            picking = false
            records.notify("无法打开系统文件选择器")
        }
    }

    Column(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp)) {
        if (links.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("关联 ${links.size} 条记录", Modifier.weight(1f), color = colors.primary)
                TextButton(onClick = { focus.clearFocus(force = true); menus.showRelationsDraft(records, onLocateQuote) }) { Text("查看") }
                TextButton(onClick = { records.setLinks(emptyList()) }) { Text("清除") }
            }
        }
        quote?.let {
            QuoteSnippet(
                quote = it,
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 6.dp),
                onDismiss = { records.setQuote(null) },
                onLocate = { onLocateQuote(it.messageId) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.size(42.dp).onGloballyPositioned {
                pickerAnchor = it.boundsInRoot()
                records.attachmentOrigin = MessageOrigin(pickerAnchor.left, pickerAnchor.top, pickerAnchor.width, pickerAnchor.height)
            }) {
                LiquidTopBarButton(
                    onClick = { menus.open(RecordMenu.Add(pickerAnchor, ::select)) },
                    backdrop = backdrop,
                    icon = YinuMenuIcons.add,
                    contentDescription = "添加附件",
                    iconSize = 24.dp,
                    enabled = !importing && !sending && !voice.active,
                    modifier = Modifier
                        .alpha(if (menus.target is RecordMenu.Add) 0f else 1f),
                )
                if (importing) CircularProgressIndicator(Modifier.align(Alignment.Center).size(23.dp), strokeWidth = 2.dp)
            }
            LiquidTopBarButton(
                onClick = {
                    voiceMode = !voiceMode
                    if (voiceMode) {
                        focus.clearFocus(force = true)
                        keyboard?.hide()
                        requestMicrophone()
                    }
                },
                backdrop = backdrop,
                icon = if (voiceMode) YinuMenuIcons.keyboard else YinuMenuIcons.voice,
                contentDescription = if (voiceMode) "切换文字输入" else "切换语音输入",
                enabled = active && !voice.active && !importing && !sending,
            )
            if (voiceMode) {
                Box(
                    Modifier.weight(1f).height(46.dp)
                        .onGloballyPositioned { voice.composerBounds = it.boundsInRoot() }
                        .clip(ComposerShape)
                        .background(if (voice.recording) colors.primaryContainer else colors.surface)
                        .semantics { contentDescription = "按住说话" }
                        .holdToRecord(voice, active && !importing && !sending, microphoneAllowed, ::requestMicrophone),
                    contentAlignment = Alignment.Center,
                ) {
                    if (voice.recording) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 53.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.dp).background(colors.error, CircleShape))
                            Text(formatVoiceTime(voice.elapsedMillis), color = colors.onPrimaryContainer,
                                style = MaterialTheme.typography.labelMedium)
                            val transcribe = voice.release == VoiceRelease.Transcribe
                            Text(
                                if (transcribe) "松手转文字" else "← 取消 · 右上转文字",
                                color = if (transcribe) colors.primary else colors.onPrimaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            if (voice.sending) "正在发送…" else "按住说话",
                            color = colors.onSurface,
                        )
                    }
                }
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = records::updateDraft,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { onInputFocus(it.isFocused) }
                        .heightIn(min = 46.dp)
                        .then(
                            if (glass) Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { ComposerShape },
                                effects = ComposerGlass,
                                highlight = null,
                                shadow = null,
                            ) else Modifier,
                        )
                        .edgeLight(shape = ComposerShape, edgeLight = rememberDefaultEdgeLight())
                        .background(colors.surface.copy(alpha = if (glass) 0.62f else 1f), ComposerShape),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank() && !sending && !importing) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            records.send()
                        }
                    }),
                    decorationBox = { field ->
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) Text("写点什么…", color = colors.onSurfaceVariant)
                            Box(Modifier.onGloballyPositioned {
                                val bounds = it.boundsInRoot()
                                records.composerOrigin = MessageOrigin(bounds.left, bounds.top, bounds.width, bounds.height)
                            }) { field() }
                        }
                    },
                )
                val canSend = text.isNotBlank() && !sending && !importing
                LiquidTopBarButton(
                    onClick = records::send,
                    backdrop = backdrop,
                    icon = YinuMenuIcons.send,
                    contentDescription = "发送",
                    iconSize = 22.dp,
                    containerColor = if (canSend) colors.primaryContainer.copy(alpha = 0.9f) else colors.surface.copy(alpha = 0.76f),
                    iconTint = if (canSend) colors.onPrimaryContainer else colors.onSurfaceVariant.copy(alpha = 0.5f),
                    enabled = canSend,
                )
            }
        }
    }
}

@Composable
internal fun QuoteSnippet(
    quote: RecordQuote,
    onLocate: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    interactive: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val menus = LocalRecordMenus.current
    val anchor = remember { RecordAnchor() }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.background(colors.surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .onGloballyPositioned { anchor.coordinates = it }
            .then(
                if (interactive) Modifier.combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                    onLongClick = { menus.open(RecordMenu.Quote(anchor.bounds() ?: Rect.Zero, onLocate)) },
                    onLongClickLabel = "引用操作",
                ) else Modifier,
            )
            .padding(start = 9.dp, end = if (onDismiss == null) 9.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(20.dp).background(colors.primary.copy(alpha = 0.65f), RoundedCornerShape(1.dp)))
        Text(
            "引用 · ${quote.summary}",
            Modifier.weight(1f).padding(vertical = 8.dp),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (onDismiss != null) {
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onDismiss()
            }, modifier = Modifier.size(40.dp)) {
                Icon(YinuMenuIcons.close, contentDescription = "取消引用", modifier = Modifier.size(17.dp))
            }
        }
    }
}

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.CHINA)

private fun localDate(timestamp: Long): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

private fun timeLabel(timestamp: Long): String {
    val local = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (local.toLocalDate()) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> local.format(dateFormatter)
    }
    return "$day ${local.format(clockFormatter)}"
}

