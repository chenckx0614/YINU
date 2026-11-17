package com.yinu.app.search

import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.foundation.lazy.rememberLazyListState
import com.yinu.designsystem.rememberTopBarMaterialVisible

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haooz.chedule.ui.basic.NexioSearchCard
import com.haooz.chedule.ui.basic.NexioSearchField
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.haooz.chedule.ui.basic.NexioSearchLabel
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.app.records.RecordList
import com.yinu.app.records.AttachmentPreviewTarget
import com.yinu.app.records.LocalRecordSelection
import com.yinu.app.records.RecordsViewModel
import com.yinu.app.records.RecordsState
import com.yinu.data.SearchCategory
import com.yinu.designsystem.YinuBottomBlur
import com.yinu.designsystem.YinuMenuIcons
import com.yinu.designsystem.YinuTopBar
import com.yinu.designsystem.yinuTopContentPadding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.layout.ProgressiveBlurTopBar

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchPage(
    records: RecordsViewModel,
    topPadding: Dp,
    bottomInsets: WindowInsets,
    onPreview: (AttachmentPreviewTarget) -> Unit,
    onLocateQuote: (String) -> Unit,
    onOpenCategory: (SearchCategory) -> Unit,
    onOpenFavorites: () -> Unit,
    onPickDate: (Long?) -> Unit,
    onInputFocus: (Boolean) -> Unit,
) {
    val query by records.query.collectAsStateWithLifecycle()
    val searchDay by records.searchDay.collectAsStateWithLifecycle()
    val date = searchDay?.let(LocalDate::ofEpochDay)
    val results by records.results.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val selecting = LocalRecordSelection.current.active
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(98.dp) }
    val resultsTop = topPadding + controlsHeight
    val bottomPadding = bottomInsets.asPaddingValues().calculateBottomPadding()
    val keyboardBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    var pickingCategory by rememberSaveable { mutableStateOf(false) }
    val overview = query.isBlank() && date == null
    val showGrid = overview || pickingCategory
    val hasScrollingContent = showGrid || (results as? RecordsState.Loaded)?.records?.isNotEmpty() == true

    Box(Modifier.fillMaxSize()) {
        // Keep the scrolling content in its own capture layer so the glass controls can sample it.
        Crossfade(
            targetState = showGrid,
            modifier = Modifier.fillMaxSize()
                .then(if (isRenderEffectSupported() && hasScrollingContent) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MaterialTheme.colorScheme.background),
            animationSpec = tween(durationMillis = 170),
            label = "Search categories and results",
        ) { grid ->
            if (grid) {
                CategoryGrid(resultsTop, bottomPadding) { type ->
                    focus.clearFocus()
                    onOpenCategory(type)
                }
            } else {
                RecordList(
                    state = results,
                    emptyText = when {
                        query.isNotBlank() -> "没有匹配的记录"
                        date != null -> "这一天还没有记录"
                        else -> "没有匹配的记录"
                    },
                    topPadding = resultsTop + 6.dp,
                    bottomPadding = bottomPadding,
                    newestAtBottom = false,
                    viewModel = records,
                    onPreview = onPreview,
                    onLocateQuote = onLocateQuote,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (hasScrollingContent) {
            YinuBottomBlur(
                backdrop = backdrop,
                bottomPadding = bottomPadding,
                keyboardBottom = keyboardBottom,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            ProgressiveBlurTopBar(
                backdrop = backdrop,
                height = resultsTop + 24.dp,
            ) {}
        }
        Column(
            Modifier.fillMaxWidth()
                // Block taps on results behind the filter controls while leaving pager swipes available.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .then(if (selecting) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)
                .padding(top = topPadding)
                .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } },
        ) {
            NexioSearchField(
                value = query,
                onValueChange = {
                    pickingCategory = false
                    records.updateQuery(it)
                },
                onSearch = { focus.clearFocus() },
                onCancel = {
                    focus.clearFocus()
                    pickingCategory = false
                    records.updateQuery("")
                },
                onFocusChange = onInputFocus,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NexioSearchFilter(date?.format(SearchDateLabel) ?: "按日期", date != null, YinuMenuIcons.calendar) {
                    focus.clearFocus()
                    pickingCategory = false
                    onPickDate(searchDay)
                }
                if (date != null) {
                    NexioSearchFilter("清除日期", false, YinuMenuIcons.close) { records.selectSearchDate(null) }
                }
                NexioSearchFilter("只看收藏", false, YinuMenuIcons.favorite) {
                    focus.clearFocus()
                    onOpenFavorites()
                }
                if (!overview) {
                    NexioSearchFilter(if (pickingCategory) "收起" else "分类", pickingCategory) {
                        focus.clearFocus()
                        pickingCategory = !pickingCategory
                    }
                }
            }
        }
        if (selecting) {
            Box(Modifier.fillMaxWidth().height(resultsTop).pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            })
        }
    }
}

@Composable
internal fun SearchResultsPage(
    category: SearchCategory?,
    records: RecordsViewModel,
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
    onPreview: (AttachmentPreviewTarget) -> Unit,
    onLocateQuote: (String) -> Unit,
    selectionBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val materialVisible = rememberTopBarMaterialVisible(listState)
    val groups by records.favoriteGroups.collectAsStateWithLifecycle(initialValue = emptyList())
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val state by remember(records, category, group) { records.observeSearch(category, onlyFavorites = category == null, favoriteGroup = group) }
        .collectAsStateWithLifecycle(initialValue = RecordsState.Loading)
    val hasResults = (state as? RecordsState.Loaded)?.records?.isNotEmpty() == true
    val query by records.query.collectAsStateWithLifecycle()
    val day by records.searchDay.collectAsStateWithLifecycle()
    val filterSummary = listOfNotNull(
        query.trim().takeIf(String::isNotEmpty),
        day?.let(LocalDate::ofEpochDay)?.format(SearchDateLabel),
    ).joinToString(" · ")
    val label = if (category == null) "收藏" else tiles.first { it.category == category }.label
    val emptyText = when {
        group != null -> "这个分组没有匹配的收藏"
        category == null && filterSummary.isEmpty() -> "还没有收藏"
        category == null -> "没有匹配的收藏"
        filterSummary.isEmpty() -> "这个分类还没有记录"
        else -> "这个分类没有匹配的记录"
    }
    val topPadding = yinuTopContentPadding()
    val bottomPadding = maxOf(
        selectionBottomPadding,
        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding(),
    )
    val keyboardBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val hasGroups = category == null && (groups.isNotEmpty() || group != null)
    val hasHeader = filterSummary.isNotEmpty() || hasGroups
    var summaryHeight by remember(filterSummary, hasGroups) { mutableStateOf(if (hasHeader) 40.dp else 0.dp) }
    Box(modifier.fillMaxSize()) {
        RecordList(
            state = state,
            emptyText = emptyText,
            topPadding = topPadding + summaryHeight + (if (hasHeader) 6.dp else 0.dp),
            bottomPadding = bottomPadding,
            newestAtBottom = false,
            viewModel = records,
            onPreview = onPreview,
            onLocateQuote = onLocateQuote,
            modifier = Modifier.fillMaxSize()
                .then(if (isRenderEffectSupported() && hasResults) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MaterialTheme.colorScheme.background),
            category = category,
            listState = listState,
        )
        if (hasResults && bottomPadding > 0.dp) {
            YinuBottomBlur(
                backdrop = backdrop,
                bottomPadding = bottomPadding,
                keyboardBottom = keyboardBottom,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        YinuTopBar(label, backdrop, onBack, blurHeight = if (hasResults) topPadding + summaryHeight + 24.dp else 0.dp,
            materialVisible = materialVisible)
        if (hasHeader) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = topPadding)
                    .onSizeChanged { summaryHeight = with(density) { it.height.toDp() } }
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filterSummary.isNotEmpty()) NexioSearchLabel(filterSummary, maxLines = 2)
                if (hasGroups) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NexioSearchFilter("全部收藏", group == null) { group = null }
                        (groups + listOfNotNull(group)).distinct().forEach { name ->
                            NexioSearchFilter(name, group == name) { group = name }
                        }
                    }
                }
            }
        }
    }
}

private val SearchDateLabel = DateTimeFormatter.ofPattern("yyyy/MM/dd")

private data class Tile(val category: SearchCategory, val label: String)

private val tiles = listOf(
    Tile(SearchCategory.DOCUMENT, "文档"),
    Tile(SearchCategory.IMAGE, "图片"),
    Tile(SearchCategory.VIDEO, "视频"),
    Tile(SearchCategory.AUDIO, "音乐"),
    Tile(SearchCategory.ARCHIVE, "压缩包"),
    Tile(SearchCategory.INSTALLER, "安装包"),
)
private val CategoryIconShape = ContinuousRoundedRectangle(11.dp)

@Composable
private fun CategoryGrid(topPadding: Dp, bottomPadding: Dp, onSelect: (SearchCategory) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().overScrollVertical().verticalScroll(rememberScrollState(), overscrollEffect = null)
            .padding(start = 18.dp, end = 18.dp, top = topPadding + 12.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NexioSearchLabel("按类型浏览", Modifier.padding(start = 8.dp, bottom = 2.dp))
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    NexioSearchCard(
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(tile.category) },
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = 38.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(35.dp).background(colors.onSurface.copy(alpha = 0.06f), CategoryIconShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(Modifier.size(21.dp)) {
                                    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                                        drawCategory(tile.category, colors.onSurface)
                                    }
                                }
                            }
                            Text(tile.label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawCategory(category: SearchCategory, ink: Color) {
    val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x: Float, y: Float, endX: Float, endY: Float) =
        drawLine(ink, Offset(x, y), Offset(endX, endY), stroke.width, stroke.cap)

    when (category) {
        SearchCategory.DOCUMENT -> {
            drawRoundRect(ink, Offset(5f, 3f), Size(14f, 18f), CornerRadius(2.5f), style = stroke)
            for (y in listOf(8f, 12f, 16f)) line(8.5f, y, if (y == 16f) 13f else 15.5f, y)
        }
        SearchCategory.IMAGE -> {
            drawRoundRect(ink, Offset(3f, 4f), Size(18f, 16f), CornerRadius(3f), style = stroke)
            drawCircle(ink, 1.4f, Offset(8f, 9f))
            line(4f, 17f, 10f, 12f)
            line(10f, 12f, 13f, 15f)
            line(13f, 15f, 17f, 10f)
            line(17f, 10f, 20f, 13f)
        }
        SearchCategory.VIDEO -> {
            drawRoundRect(ink, Offset(2.5f, 6f), Size(12.5f, 12f), CornerRadius(3f), style = stroke)
            drawPath(Path().apply {
                moveTo(15f, 10f)
                lineTo(21f, 7f)
                lineTo(21f, 17f)
                lineTo(15f, 14f)
            }, ink, style = stroke)
        }
        SearchCategory.AUDIO -> {
            drawOval(ink, Offset(4.5f, 14f), Size(8f, 6f))
            line(11.7f, 16.5f, 11.7f, 5f)
            line(11.7f, 5f, 19f, 3.5f)
            line(19f, 3.5f, 19f, 7f)
            line(19f, 7f, 11.7f, 8.5f)
        }
        SearchCategory.ARCHIVE -> {
            drawRoundRect(ink, Offset(5f, 3f), Size(14f, 18f), CornerRadius(2.5f), style = stroke)
            line(12f, 3.5f, 12f, 12f)
            line(12f, 6f, 15f, 6f)
            line(9f, 9f, 12f, 9f)
            drawRoundRect(ink, Offset(10f, 12f), Size(4f, 5f), CornerRadius(1f), style = stroke)
        }
        SearchCategory.INSTALLER -> {
            line(7.7f, 7.5f, 5.8f, 4.6f)
            line(16.3f, 7.5f, 18.2f, 4.6f)
            drawPath(Path().apply {
                moveTo(4f, 16f)
                cubicTo(4f, 5f, 20f, 5f, 20f, 16f)
                close()
            }, ink, style = stroke)
            drawCircle(ink, 1f, Offset(8.6f, 12f))
            drawCircle(ink, 1f, Offset(15.4f, 12f))
            line(5f, 19.5f, 19f, 19.5f)
        }
    }
}
