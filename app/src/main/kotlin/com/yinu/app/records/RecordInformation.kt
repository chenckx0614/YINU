package com.yinu.app.records

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class InformationKind(val label: String) {
    Phone("电话号码"), Address("地址"), Tracking("快递单号")
}

internal data class RecordInformation(val kind: InformationKind, val value: String)

private val TrackingLabel = Regex("(?:快递单号|快递号|运单号|物流单号|物流编号|单号|tracking(?: number)?)[：: \\t]*([A-Z0-9][A-Z0-9-]{7,29})(?![A-Z0-9-])", RegexOption.IGNORE_CASE)
private val CarrierNumber = Regex("(?<![A-Z0-9])(?:SF[0-9]{10,16}|YT[0-9]{10,16}|JD[0-9]{10,16})(?![A-Z0-9])", RegexOption.IGNORE_CASE)
private val PhoneNumber = Regex("(?<![A-Za-z0-9])(?:(?:\\+?86[ -]?)?1[3-9]\\d[ -]?\\d{4}[ -]?\\d{4}|(?:0\\d{2,3}|\\(0\\d{2,3}\\))[ -]?\\d{7,8})(?![A-Za-z0-9])")
private val AddressLabel = Regex("(?:收货地址|联系地址|详细地址|地址|地点)[：: \\t]+([^\\r\\n，,；;。]{3,100})")
private val StreetAddress = Regex("[\\p{IsHan}]{2,40}(?:大道|路|街|巷|弄|道)[0-9]{1,6}号(?:[0-9]+(?:栋|楼|室|单元))*")
private val InlineField = Regex("[ \\t]*(?:电话|手机|联系人|运单号|快递单号)[：:]")

internal fun extractRecordInformation(text: String): List<RecordInformation> {
    val candidates = mutableListOf<Pair<IntRange, RecordInformation>>()
    fun add(match: MatchResult, kind: InformationKind, group: Int) {
        val part = match.groups[group] ?: return
        val value = if (kind == InformationKind.Address) part.value.split(InlineField, limit = 2).first().trim() else part.value
        val valueRange = part.range.first until part.range.first + value.length
        if (value.isNotBlank() && candidates.none { (range, _) ->
                valueRange.first <= range.last && range.first <= valueRange.last
            }) {
            candidates += valueRange to RecordInformation(kind, value)
        }
    }
    TrackingLabel.findAll(text).forEach { add(it, InformationKind.Tracking, 1) }
    CarrierNumber.findAll(text).forEach { add(it, InformationKind.Tracking, 0) }
    PhoneNumber.findAll(text).forEach { add(it, InformationKind.Phone, 0) }
    AddressLabel.findAll(text).forEach { add(it, InformationKind.Address, 1) }
    StreetAddress.findAll(text).forEach { add(it, InformationKind.Address, 0) }
    return candidates.sortedBy { it.first.first }.map { it.second }.distinctBy { item ->
        item.kind to if (item.kind == InformationKind.Phone) item.value.filter(Char::isDigit) else item.value.uppercase()
    }
}

@Composable
internal fun RecordInformationDialog(
    text: String,
    show: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onFinished: () -> Unit,
    notify: (String) -> Unit,
) {
    val context = LocalContext.current
    val information by produceState<List<RecordInformation>?>(null, text) {
        value = withContext(Dispatchers.Default) { extractRecordInformation(text) }
    }
    NexioOverlayDialog(show, "提取信息", null, backdrop, onDismiss, onFinished) {
        val results = information
        if (results == null) {
            Text("正在提取信息…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (results.isEmpty()) {
            Text("没有识别到电话、地址或快递单号", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = dialogBodyMaxHeight(320.dp, extra = 48.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(results) { item ->
                    Text(item.kind.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.value, color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NexioSearchFilter("复制", false, enabled = show) { copyText(context, item.value, notify) }
                        val action = when (item.kind) {
                            InformationKind.Phone -> "拨号" to Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", item.value.filter { it.isDigit() || it == '+' }, null))
                            InformationKind.Address -> "地图" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(item.value)))
                            InformationKind.Tracking -> null
                        }
                        action?.let { (label, intent) ->
                            NexioSearchFilter(label, false, enabled = show) {
                                try { context.startActivity(intent) }
                                catch (_: android.content.ActivityNotFoundException) { notify("没有可用的${label}应用") }
                                catch (_: SecurityException) { notify("无法打开${label}应用") }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(top = 12.dp)) {
            NexioSearchFilter("关闭", false, enabled = show, onClick = onDismiss)
        }
    }
}
