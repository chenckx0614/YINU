package com.yinu.app.ai

import androidx.compose.runtime.*
import com.haooz.chedule.ui.basic.NexioOverlayDialog
import com.haooz.chedule.ui.basic.NexioPickerActions
import com.kyant.backdrop.Backdrop
import com.yinu.app.records.CaptureField
import com.yinu.app.records.RecordMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun AiOrganizeDialog(menu: RecordMenu.Organize, show: Boolean, backdrop: Backdrop, dismiss: () -> Unit, finished: () -> Unit) {
    val service = LocalAiService.current
    val scope = rememberCoroutineScope()
    val texts = remember(menu) { menu.records.map { checkNotNull(it.content) } }
    val eligible = remember(texts) { texts.sumOf { it.length } <= AiInputLimit }
    val ready = service.options.enabled && service.options.hasKey
    val host = remember(service.options.baseUrl) { aiEndpoint(service.options.baseUrl).host }
    var running by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun close() {
        if (saving) return
        running?.cancel()
        dismiss()
    }

    NexioOverlayDialog(show, "AI 整理",
        status ?: when {
            !ready -> "请到「设置 → AI 服务」填写 API Key，再打开开关。"
            running != null -> "正在整理所选正文…"
            result != null -> "可以编辑结果，保存后会关联原记录。"
            !eligible -> "每次最多整理 12,000 字，请减少选中的记录。"
            else -> "将 ${texts.size} 条记录的正文发送至 $host。"
        }, backdrop, ::close, finished) {
        result?.let { CaptureField(it, { updated -> result = updated }, "整理结果", show && !saving) }
        val label = if (saving) "正在保存…" else if (result != null) "保存记录" else "发送并整理"
        NexioPickerActions(label, show && !saving,
            confirmEnabled = ready && eligible && running == null && result?.isBlank() != true,
            onCancel = ::close, onConfirm = {
                if (ready && running == null && !saving && eligible) {
                    status = null
                    val text = result
                    if (text == null) {
                        running = scope.launch {
                            try {
                                result = service.organize(texts)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: AiServiceError) {
                                status = error.message
                            } catch (_: Exception) {
                                status = "整理失败，请检查服务设置后重试。"
                            } finally {
                                running = null
                            }
                        }
                    } else if (text.isNotBlank()) {
                        saving = true
                        scope.launch {
                            try {
                                menu.model.saveAiSummary(text, menu.records)
                                menu.done()
                                dismiss()
                                menu.model.notify("已保存 AI 整理结果")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "无法保存整理结果，请重试。"
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            })
    }
}
