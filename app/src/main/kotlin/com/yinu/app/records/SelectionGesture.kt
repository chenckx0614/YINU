package com.yinu.app.records

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.yinu.data.Record
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Ordinary swipes scroll; holding a row starts one consistent selection stroke. */
internal fun Modifier.sweepSelection(selection: RecordSelectionHost, rows: List<Record>, list: LazyListState): Modifier =
    if (!selection.active || selection.deleting) this else pointerInput(selection, rows, list, selection.active, selection.deleting) {
        coroutineScope {
            var dragging = false
            var point = Offset.Zero
            var previous: Int? = null
            var select = true
            val visited = hashSetOf<String>()
            fun applyAtPoint() {
                val layout = list.layoutInfo
                val y = (if (layout.reverseLayout) size.height - point.y else point.y) - layout.beforeContentPadding
                val info = list.layoutInfo.visibleItemsInfo.firstOrNull {
                    y >= it.offset && y < it.offset + it.size
                } ?: return
                val index = info.index
                val record = rows.getOrNull(index) ?: return
                if (previous == null) select = record.id !in selection.selected
                val from = previous ?: index
                for (i in minOf(from, index)..maxOf(from, index)) {
                    val row = rows.getOrNull(i) ?: continue
                    if (visited.add(row.id)) selection.setSelected(row, select)
                }
                previous = index
            }
            var scrolling: kotlinx.coroutines.Job? = null
            fun startScrolling() { scrolling = launch {
                while (dragging) {
                    withFrameNanos { }
                    if (!dragging) continue
                    val edge = 56 * density
                    val top = list.layoutInfo.beforeContentPadding.toFloat().coerceAtMost(size.height / 3f)
                    val bottom = size.height - list.layoutInfo.afterContentPadding.toFloat().coerceAtMost(size.height / 3f)
                    val velocity = when {
                        point.y < top + edge -> -((top + edge - point.y) / edge).coerceIn(0f, 1f)
                        point.y > bottom - edge -> ((point.y - bottom + edge) / edge).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (abs(velocity) > 0f) {
                        list.scrollBy(velocity * 14 * density * if (list.layoutInfo.reverseLayout) -1 else 1)
                        applyAtPoint()
                    }
                }
            }
            }
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    point = held.position; previous = null; visited.clear()
                    dragging = true; applyAtPoint(); startScrolling()
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            point = change.position
                            if (change.pressed) applyAtPoint()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally { dragging = false; scrolling?.cancel() }
                }
            } finally { scrolling?.cancel() }
        }
    }



