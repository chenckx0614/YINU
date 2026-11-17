package com.yinu.app.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.chedule.ui.basic.NexioNumberPicker
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.kyant.backdrop.Backdrop
import java.time.LocalDate

@Composable
internal fun SearchDatePicker(
    initialDay: Long,
    show: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    var draftDay by rememberSaveable(initialDay) { mutableLongStateOf(initialDay) }
    val date = LocalDate.ofEpochDay(draftDay)
    val maxDay = date.lengthOfMonth()
    val textStyle = TextStyle(fontSize = 24.sp)

    NexioOverlayDialog(
        show = show,
        title = "选择日期",
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        // Year, month and day wheels follow the layout in NexioSchedule's TodayScreen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NexioNumberPicker(
                value = date.year,
                onValueChange = { draftDay = LocalDate.ofEpochDay(draftDay).withYear(it).toEpochDay() },
                accessibilityLabel = "年份",
                modifier = Modifier.weight(1f),
                enabled = show,
                range = 1900..2100,
                label = { "${it}年" },
                visibleItemCount = 3,
                textStyle = textStyle,
                itemHeight = 60.dp,
            )
            NexioNumberPicker(
                value = date.monthValue,
                onValueChange = { draftDay = LocalDate.ofEpochDay(draftDay).withMonth(it).toEpochDay() },
                accessibilityLabel = "月份",
                modifier = Modifier.weight(1f),
                enabled = show,
                range = 1..12,
                label = { "${it}月" },
                visibleItemCount = 3,
                wrapAround = true,
                textStyle = textStyle,
                itemHeight = 60.dp,
            )
            // Changing to a shorter month clamps the draft and resets any old day-wheel fling.
            key(maxDay) {
                NexioNumberPicker(
                    value = date.dayOfMonth,
                    onValueChange = {
                        val latest = LocalDate.ofEpochDay(draftDay)
                        draftDay = latest.withDayOfMonth(it.coerceAtMost(latest.lengthOfMonth())).toEpochDay()
                    },
                    accessibilityLabel = "日期",
                    modifier = Modifier.weight(1f),
                    enabled = show,
                    range = 1..maxDay,
                    label = { "${it}日" },
                    visibleItemCount = 3,
                    wrapAround = true,
                    textStyle = textStyle,
                    itemHeight = 60.dp,
                )
            }
        }
        NexioPickerActions(
            confirmLabel = "查看记录",
            enabled = show,
            onCancel = onDismiss,
            onConfirm = { onSelect(LocalDate.ofEpochDay(draftDay)) },
        )
    }
}
