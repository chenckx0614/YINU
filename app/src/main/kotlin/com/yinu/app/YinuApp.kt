package com.yinu.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.capsule.ContinuousRoundedRectangle
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.yinu.app.records.AttachmentPreview
import com.yinu.app.ai.AiService
import com.yinu.app.ai.LocalAiService
import com.yinu.app.records.AttachmentPreviewTarget
import com.yinu.app.records.ImagePreview
import com.yinu.app.records.LocalHiddenPreviewAttachment
import com.yinu.app.records.LocalVoiceCapture
import com.yinu.app.records.LocalVoicePlayback
import com.yinu.app.records.VoicePlayback
import com.yinu.app.records.VoiceCaptureOverlay
import com.yinu.app.records.rememberVoiceCapture
import com.yinu.app.records.LocalRecordMenus
import com.yinu.app.records.IncomingShareDialog
import com.yinu.app.records.LocalRecordDeleteDialog
import com.yinu.app.records.LocalRecordSelection
import com.yinu.app.records.RecordMenuHost
import com.yinu.app.records.RecordDeleteDialogHost
import com.yinu.app.records.RecordDeleteDialogOverlay
import com.yinu.app.records.RecordMenuOverlay
import com.yinu.app.records.RecordPage
import com.yinu.app.records.RecordLocationRequest
import com.yinu.app.records.RecordSelectionBar
import com.yinu.app.records.RecordSelectionBarHeight
import com.yinu.app.records.RecordSelectionHost
import com.yinu.app.records.blurredRecordPage
import com.yinu.app.records.RecordsViewModel
import com.yinu.app.search.SearchResultsPage
import com.yinu.app.search.SearchDatePicker
import com.yinu.app.search.SearchPage
import com.yinu.designsystem.YinuDock
import com.yinu.designsystem.YinuTopBar
import com.yinu.designsystem.YinuMenuIcons
import com.yinu.designsystem.yinuDockContentPadding
import com.yinu.designsystem.yinuTopContentPadding
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.haooz.chedule.ui.basic.LocalShortcutMenuLabels
import com.yinu.app.records.LocalMessageFlightHost
import com.yinu.app.records.MessageFlightHost
import com.yinu.app.records.MessageFlightOverlay
import com.yinu.app.records.LocalParticleDust
import com.yinu.app.records.rememberParticleDust

// Adapted from NexioSchedule on 2026-11-05.
// Copyright (C) 2023-2026 HyperCeiler Contributions
// SPDX-License-Identifier: AGPL-3.0-only
internal val PagePushEnter = tween<Float>(600, easing = Easing { fraction ->
    val t = fraction.coerceIn(0f, 1f)
    val k = 0.48f
    val shaped = if (t <= k) -t * t * t / (k * k) + 2f * t * t / k else t
    1f - (1f - shaped).pow(4.5f)
})
internal val PagePushExit = tween<Float>(320, easing = CubicBezierEasing(0.36f, 0.18f, 0.3f, 0.85f))
private val NoticeCorner = 22.dp
private val NoticeShape = ContinuousRoundedRectangle(NoticeCorner)

@Composable
internal fun YinuApp(
    records: RecordsViewModel,
    appearance: AppearanceOptions,
    onThemeChange: (AppearanceMode) -> Unit,
    onBlurChange: (Boolean) -> Unit,
    onMonetChange: (Boolean) -> Unit,
    onMenuLabelsChange: (Boolean) -> Unit,
    darkTheme: Boolean,
    onSystemBarsDark: (Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var noticeVisible by remember { mutableStateOf(false) }
    val noticeBackdrop = rememberLayerBackdrop()
    val deleteDialog = remember { RecordDeleteDialogHost() }
    val shareQueue by records.pendingShares.collectAsStateWithLifecycle()
    val shareBackdrop = rememberLayerBackdrop()
    var preview by remember { mutableStateOf<AttachmentPreviewTarget?>(null) }
    val imagePreview = preview?.takeIf { it.attachment.isImage }
    val darkSystemBars = darkTheme || imagePreview != null
    LaunchedEffect(darkSystemBars) { onSystemBarsDark(darkSystemBars) }
    LaunchedEffect(deleteDialog.visible) {
        if (deleteDialog.visible) snackbar.newestSnackbarData()?.dismiss()
    }
    LaunchedEffect(records) {
        records.messages.collect { notice ->
            val undo = notice.undo
            val result = try {
                noticeVisible = true
                snackbar.showSnackbar(
                    message = notice.message,
                    actionLabel = if (undo == null) null else "撤销",
                    withDismissAction = undo != null,
                    duration = SnackbarDuration.Short,
                )
            } finally {
                noticeVisible = false
            }
            if (result == SnackbarResult.ActionPerformed && undo != null) records.restoreTrash(undo)
        }
    }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsLocation by rememberSaveable { mutableStateOf<String?>(null) }
    val externalLocation by records.externalLocation.collectAsStateWithLifecycle()
    val quickAction by records.quickAction.collectAsStateWithLifecycle()
    LaunchedEffect(externalLocation) {
        externalLocation?.let { preview = null; settingsLocation = it; records.consumeLocation() }
    }
    val progress = remember { Animatable(if (settingsOpen) 1f else 0f) }
    val animationScope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    suspend fun closeSettings() {
        progress.animateTo(0f, PagePushExit)
        settingsOpen = false
    }

    LaunchedEffect(settingsLocation) {
        if (settingsLocation != null && settingsOpen) closeSettings()
    }
    LaunchedEffect(quickAction) {
        if (quickAction != null) {
            preview = null
            if (settingsOpen) closeSettings()
        }
    }

    BackHandler(enabled = settingsOpen) {
        animationScope.launch { closeSettings() }
    }

    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val aiService = remember(context) { AiService(context) }
    val noticeGlass = noticeVisible && deleteDialog.prompt == null && isRenderEffectSupported()
    CompositionLocalProvider(LocalShortcutMenuLabels provides appearance.menuLabels, LocalAiService provides aiService) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Box(
            Modifier.fillMaxSize()
                .then(if (shareQueue.isNotEmpty() && isRenderEffectSupported()) Modifier.layerBackdrop(shareBackdrop) else Modifier)
                .then(if (noticeGlass) Modifier.layerBackdrop(noticeBackdrop) else Modifier),
        ) {
        val hiddenAttachment = preview
            ?.takeIf { it.hideOriginal && it.startBounds != null && (!it.attachment.isImage || it.thumbnail != null) }
            ?.attachment?.id
        CompositionLocalProvider(LocalHiddenPreviewAttachment provides hiddenAttachment) {
            Home(
                records = records,
                deleteDialog = deleteDialog,
                onPreview = {
                    focus.clearFocus()
                    preview = it
                },
                homeActive = !settingsOpen && preview == null && shareQueue.isEmpty(),
                requestedLocation = settingsLocation,
                onRequestedLocationHandled = { settingsLocation = null },
                onExit = onExit,
                settingsProgress = { progress.value },
                onSettings = {
                    if (!settingsOpen) {
                        focus.clearFocus()
                        settingsOpen = true
                        animationScope.launch {
                            progress.animateTo(1f, PagePushEnter)
                        }
                    }
                },
                modifier = Modifier.pagePushSource { progress.value }
                    .then(if (!settingsOpen && preview == null) Modifier
                        else Modifier.clearAndSetSemantics { hideFromAccessibility() }),
            )
        }
        if (settingsOpen) {
            PagePushScrim { progress.value }
            YinuSettings(
                records = records,
                appearance = appearance,
                darkTheme = darkTheme,
                onThemeChange = onThemeChange,
                onBlurChange = onBlurChange,
                onMonetChange = onMonetChange,
                onMenuLabelsChange = onMenuLabelsChange,
                onLocateRecord = { id ->
                    if (settingsLocation == null) settingsLocation = id
                },
                onBack = { animationScope.launch { closeSettings() } },
                modifier = Modifier.pagePushDestination { progress.value },
            )
        }
        imagePreview?.let { ImagePreview(it, records, onClose = { preview = null }, notify = records::notify) }
        preview?.takeUnless { it.attachment.isImage }?.let {
            AttachmentPreview(it, onClose = { preview = null }, notify = records::notify)
        }
        }
        shareQueue.firstOrNull()?.let { share ->
            androidx.compose.runtime.key(share.requestId) { IncomingShareDialog(share, records, shareBackdrop) }
        }
        if (deleteDialog.prompt == null) {
            SnackbarHost(
                state = snackbar,
                modifier = Modifier.testTag("app-notices").align(Alignment.TopCenter).statusBarsPadding()
                    .padding(start = 54.dp, end = 54.dp, top = 6.dp).widthIn(max = 380.dp),
            ) { data ->
                RecordNotice(
                    message = data.visuals.message,
                    action = data.visuals.actionLabel,
                    dismissible = data.visuals.withDismissAction,
                    glass = noticeGlass,
                    backdrop = noticeBackdrop,
                    onUndo = { animationScope.launch { data.performAction() } },
                    onDismiss = { animationScope.launch { data.dismiss() } },
                )
            }
        }
    }
}

}

@Composable
private fun RecordNotice(
    message: String,
    action: String?,
    dismissible: Boolean,
    glass: Boolean,
    backdrop: com.kyant.backdrop.Backdrop,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val material = if (glass) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { NoticeShape },
        effects = {
            vibrancy()
            blur(16.dp.toPx())
        },
        highlight = null,
        shadow = null,
    ).edgeLight(shape = NoticeShape, edgeLight = rememberDefaultEdgeLight(degradedBaseColor = colors.surface))
    else Modifier
    Row(
        material.clip(NoticeShape).background(colors.surface.copy(alpha = if (glass) 0.82f else 1f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 44.dp).padding(start = 13.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f, fill = false), color = colors.onSurface, style = MaterialTheme.typography.bodySmall)
        if (action != null) {
            Box(Modifier.heightIn(min = 36.dp).clip(NoticeShape).background(colors.primary.copy(alpha = 0.10f))
                .clickable(role = Role.Button, onClick = onUndo).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                Text(action, color = colors.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (dismissible) {
            Box(Modifier.size(34.dp).clip(NoticeShape).clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center) {
                Icon(YinuMenuIcons.close, "关闭提示", Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

private fun Modifier.pageTranslation(fraction: () -> Float) = layout { measurable, constraints ->
    val page = measurable.measure(constraints)
    layout(page.width, page.height) {
        page.place((page.width * fraction()).roundToInt(), 0)
    }
}

internal fun Modifier.pagePushSource(progress: () -> Float) = pageTranslation { -0.24f * progress() }

internal fun Modifier.pagePushDestination(progress: () -> Float) = pageTranslation { 1f - progress() }

@Composable
internal fun PagePushScrim(progress: () -> Float) {
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        },
    ) {
        drawRect(Color.Black.copy(alpha = 0.42f * progress()))
    }
}

@Composable
private fun Home(
    records: RecordsViewModel,
    deleteDialog: RecordDeleteDialogHost,
    onPreview: (AttachmentPreviewTarget) -> Unit,
    homeActive: Boolean,
    requestedLocation: String?,
    onRequestedLocationHandled: () -> Unit,
    onExit: () -> Unit,
    settingsProgress: () -> Float,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = rememberLayerBackdrop()
    val menus = remember { RecordMenuHost() }
    val selection = remember { RecordSelectionHost() }
    val dialogBackdrop = rememberLayerBackdrop()
    val searchDestinationBackdrop = rememberLayerBackdrop()
    val selectedSearchDestination by records.searchDestination.collectAsStateWithLifecycle()
    var shownSearchDestination by remember { mutableStateOf(selectedSearchDestination) }
    val searchDestinationProgress = remember { Animatable(if (selectedSearchDestination == null) 0f else 1f) }
    val searchDestinationOpen = selectedSearchDestination != null || shownSearchDestination != null
    var datePickerDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var datePickerVisible by rememberSaveable { mutableStateOf(false) }
    val datePickerMounted = datePickerDay != null
    val pageBlur = blurredRecordPage(menus)
    var timelineJumpVersion by rememberSaveable { mutableIntStateOf(0) }
    val pager = key(timelineJumpVersion) { rememberPagerState { 2 } }
    val quote by records.quote.collectAsStateWithLifecycle()
    val links by records.links.collectAsStateWithLifecycle()
    val quickAction by records.quickAction.collectAsStateWithLifecycle()
    var recordLocation by remember { mutableStateOf<RecordLocationRequest?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var focusedPage by remember { mutableStateOf<Int?>(null) }
    val topPadding = yinuTopContentPadding()
    val dockHeight = yinuDockContentPadding()
    val dock = rememberDockMotion(focusedPage != null, dockHeight) { focus.clearFocus() }
    val selectionBottom = if (selection.active) maxOf(
        RecordSelectionBarHeight + maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 2.dp, 22.dp),
        WindowInsets.ime.asPaddingValues().calculateBottomPadding() + RecordSelectionBarHeight + 2.dp,
    ) else 0.dp
    val pageBottomInsets = if (selection.active) WindowInsets(bottom = selectionBottom) else dock.bottomInsets
    val dockInteractive = homeActive && !selection.active && !searchDestinationOpen && menus.target == null &&
        deleteDialog.prompt == null && !datePickerMounted &&
        !dock.keyboardActive && focusedPage == null

    fun reportFocus(page: Int, hasFocus: Boolean) {
        if (hasFocus) focusedPage = page
        else if (focusedPage == page) focusedPage = null
    }
    fun locateRecord(messageId: String) {
        focus.clearFocus(force = true)
        records.closeSearchDestination()
        recordLocation = RecordLocationRequest(messageId)
        // A deep link from search starts on the timeline and discards the old
        // pager gesture and focus requests that could restore the search page.
        if (pager.currentPage != 0 || pager.isScrollInProgress) timelineJumpVersion++
    }

    LaunchedEffect(requestedLocation, homeActive) {
        if (requestedLocation != null && homeActive) {
            locateRecord(requestedLocation)
            onRequestedLocationHandled()
        }
    }

    LaunchedEffect(pager.settledPage) {
        if (focusedPage != null && focusedPage != pager.settledPage) focus.clearFocus()
    }
    LaunchedEffect(quote) {
        if (quote != null && pager.currentPage != 0) {
            records.closeSearchDestination()
            pager.animateScrollToPage(0)
        }
    }
    LaunchedEffect(links, quickAction) {
        if (links.isNotEmpty() || quickAction != null) {
            records.closeSearchDestination()
            if (pager.currentPage != 0) timelineJumpVersion++
        }
    }
    LaunchedEffect(selectedSearchDestination) {
        val destination = selectedSearchDestination
        if (destination != null) {
            shownSearchDestination = destination
            searchDestinationProgress.animateTo(1f, PagePushEnter)
        } else if (shownSearchDestination != null) {
            searchDestinationProgress.animateTo(0f, PagePushExit)
            shownSearchDestination = null
        }
    }
    val searchRoot = pager.currentPage == 1
    BackHandler(enabled = homeActive && !selection.active && !searchDestinationOpen && menus.target == null &&
        deleteDialog.prompt == null && !datePickerMounted &&
        (searchRoot || (!dock.keyboardActive && focusedPage != null))) {
        if (searchRoot && !dock.keyboardActive) onExit()
        else focus.clearFocus(force = true)
    }
    BackHandler(
        enabled = homeActive && !selection.active && searchDestinationOpen && menus.target == null &&
            deleteDialog.prompt == null && !datePickerMounted,
    ) { records.closeSearchDestination() }
    BackHandler(enabled = homeActive && selection.active && deleteDialog.prompt == null, onBack = selection::cancel)

    val dockFraction = {
        val pageProgress = maxOf(settingsProgress(), searchDestinationProgress.value)
        (dock.reveal.value * (1f - pageProgress)).coerceIn(0f, 1f)
    }
    val playback = remember(records) { VoicePlayback(records::notify) }
    val voiceCapture = rememberVoiceCapture(records, playback)
    androidx.compose.runtime.DisposableEffect(playback) { onDispose(playback::stop) }
    LaunchedEffect(homeActive) {
        if (!homeActive) { voiceCapture.cancelRecording(); playback.stop() }
    }
    val flights = remember { MessageFlightHost() }
    val dust = rememberParticleDust(topPadding)
    CompositionLocalProvider(
        LocalMessageFlightHost provides flights,
        LocalParticleDust provides dust,
        LocalVoiceCapture provides voiceCapture,
        LocalVoicePlayback provides playback,
    ) {
    Box(modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxSize()
                // The dialog samples the page and its chrome. The overlay itself is outside this layer.
                .then(
                    if ((deleteDialog.prompt != null || datePickerMounted || voiceCapture.reviewFile != null) && isRenderEffectSupported()) {
                        Modifier.layerBackdrop(dialogBackdrop)
                    }
                    else Modifier,
                )
                .then(
                    if (datePickerMounted || deleteDialog.prompt != null) Modifier.clearAndSetSemantics { hideFromAccessibility() }
                    else Modifier,
                )
                .then(pageBlur),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .pagePushSource { searchDestinationProgress.value }
                    .then(if (searchDestinationOpen) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier),
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .then(if (isRenderEffectSupported()) Modifier.layerBackdrop(backdrop) else Modifier)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    CompositionLocalProvider(
                        LocalRecordMenus provides menus,
                        LocalRecordDeleteDialog provides deleteDialog,
                        LocalRecordSelection provides selection,
                    ) {
                        key(pager) {
                            HorizontalPager(
                                state = pager,
                                userScrollEnabled = !selection.active && !voiceCapture.active,
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                when (page) {
                                    0 -> RecordPage(
                                        records = records,
                                        topPadding = topPadding,
                                        bottomInsets = pageBottomInsets,
                                        active = homeActive && pager.currentPage == 0 && pager.settledPage == 0,
                                        onPreview = onPreview,
                                        onLocateRecord = ::locateRecord,
                                        recordLocation = recordLocation,
                                        onLocationHandled = { if (recordLocation === it) recordLocation = null },
                                    ) { reportFocus(page, it) }
                                    1 -> SearchPage(
                                        records = records,
                                        topPadding = topPadding,
                                        bottomInsets = pageBottomInsets,
                                        onPreview = onPreview,
                                        onLocateQuote = ::locateRecord,
                                        onOpenCategory = records::openSearchCategory,
                                        onOpenFavorites = records::openFavorites,
                                        onPickDate = { selectedDay ->
                                            if (datePickerDay == null) {
                                                datePickerDay = selectedDay ?: LocalDate.now().toEpochDay()
                                                datePickerVisible = true
                                            }
                                        },
                                    ) { reportFocus(page, it) }
                                }
                            }
                        }
                    }
                }
                if (pager.currentPage == 0) YinuTopBar("一隅", backdrop)
                else YinuTopBar("搜索", backdrop, blurHeight = 0.dp)
                YinuDock(
                    selectedTab = pager.currentPage,
                    onTabSelected = { index ->
                        if (dockInteractive) {
                            focus.clearFocus()
                            scope.launch { pager.animateScrollToPage(index) }
                        }
                    },
                    onSettings = { if (dockInteractive) onSettings() },
                    backdrop = backdrop,
                    recordIcon = RecordIcon,
                    searchIcon = SearchIcon,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .offset { IntOffset(0, ((1f - dockFraction()) * dockHeight.toPx()).roundToInt()) }
                        .graphicsLayer {
                            val fraction = dockFraction()
                            alpha = if (selection.active) 0f else fraction
                            scaleX = 0.94f + 0.06f * fraction
                            scaleY = scaleX
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .then(if (dockInteractive) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }),
                )
            }
            if (searchDestinationOpen) PagePushScrim { searchDestinationProgress.value }
            shownSearchDestination?.let { destination ->
                CompositionLocalProvider(
                    LocalRecordMenus provides menus,
                    LocalRecordDeleteDialog provides deleteDialog,
                    LocalRecordSelection provides selection,
                ) {
                    SearchResultsPage(
                        category = destination.category,
                        records = records,
                        backdrop = searchDestinationBackdrop,
                        onBack = { if (selection.active) selection.cancel() else records.closeSearchDestination() },
                        onPreview = onPreview,
                        onLocateQuote = ::locateRecord,
                        selectionBottomPadding = selectionBottom,
                        modifier = Modifier.pagePushDestination { searchDestinationProgress.value },
                    )
                }
            }
            if (selection.active) {
                RecordSelectionBar(
                    selection = selection,
                    backdrop = if (shownSearchDestination == null) backdrop else searchDestinationBackdrop,
                    onDelete = { selection.confirmDelete(deleteDialog, records) },
                    onMerge = { menus.showMerge(selection.selected.values.map { it.id }, records, selection::cancel) },
                    onExport = { menus.showExport(selection.selected.values.toList()) },
                    onOrganize = {
                        val sources = selection.selected.values.filter { !it.content.isNullOrBlank() }.sortedBy { it.createdAt }
                        when {
                            sources.isEmpty() -> records.notify("请选择含正文的记录")
                            sources.size > 200 -> records.notify("每次最多整理 200 条记录")
                            else -> menus.showOrganize(sources, records, selection::cancel)
                        }
                    },
                    onLink = {
                        if (records.setLinks(selection.selected.values.toList().sortedBy { it.createdAt })) selection.cancel()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 14.dp, end = 14.dp, bottom = selectionBottom - RecordSelectionBarHeight),
                )
            }
        }
        RecordMenuOverlay(menus, if (shownSearchDestination == null) backdrop else searchDestinationBackdrop)
        RecordDeleteDialogOverlay(deleteDialog, dialogBackdrop)
        datePickerDay?.let { initialDay ->
            SearchDatePicker(
                initialDay = initialDay,
                show = datePickerVisible,
                backdrop = dialogBackdrop,
                onDismiss = { datePickerVisible = false },
                onDismissFinished = { if (!datePickerVisible) datePickerDay = null },
                onSelect = {
                    if (datePickerVisible) {
                        datePickerVisible = false
                        records.selectSearchDate(it)
                    }
                },
            )
        }
        VoiceCaptureOverlay(voiceCapture, playback, dialogBackdrop, flights.activeVoiceFlight)
        MessageFlightOverlay(flights, topPadding)
    }
}

}

private val RecordIcon = ImageVector.Builder("Record", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
        moveTo(7f, 4f)
        lineTo(17f, 4f)
        quadTo(21f, 4f, 21f, 8f)
        lineTo(21f, 14f)
        quadTo(21f, 18f, 17f, 18f)
        lineTo(10f, 18f)
        lineTo(5.5f, 21f)
        lineTo(5.5f, 17.8f)
        quadTo(3f, 17f, 3f, 14f)
        lineTo(3f, 8f)
        quadTo(3f, 4f, 7f, 4f)
        close()
    }
}.build()

private val SearchIcon = ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
        moveTo(18f, 10.5f)
        arcTo(7.5f, 7.5f, 0f, true, true, 3f, 10.5f)
        arcTo(7.5f, 7.5f, 0f, true, true, 18f, 10.5f)
        moveTo(16f, 16f)
        lineTo(21f, 21f)
    }
}.build()
