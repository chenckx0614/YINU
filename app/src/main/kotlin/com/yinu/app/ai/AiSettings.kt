package com.yinu.app.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.yinu.app.SettingsGroup
import com.yinu.app.SettingsNote
import com.yinu.app.SettingsPage
import com.yinu.app.records.CaptureField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun AiSettings(onBack: () -> Unit, modifier: Modifier) {
    val service = LocalAiService.current
    val options = service.options
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var url by remember { mutableStateOf(options.baseUrl) }
    var model by remember { mutableStateOf(options.model) }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val busy = saving || testing
    val requestAddress = remember(url) { runCatching { aiEndpoint(url).toString() }.getOrNull() }

    fun save(clearKey: Boolean = false, enable: Boolean? = null) {
        if (busy) return
        focus.clearFocus()
        saving = true
        status = null
        scope.launch {
            try {
                if (clearKey) service.save(false, options.baseUrl, options.model, "", clearKey = true)
                else if (enable == false || enable == true && options.hasKey &&
                    url == options.baseUrl && model == options.model && key.isBlank()
                ) service.setEnabled(enable == true)
                else service.save(enable ?: options.enabled, url, model, key)
                val saved = service.options
                if (enable != false) {
                    url = saved.baseUrl
                    model = saved.model
                    key = ""
                }
                status = when {
                    clearKey -> "已清除密钥并关闭 AI 服务。"
                    enable == false -> "已关闭 AI 整理。"
                    saved.enabled -> "已启用。长按一条文字消息，点 AI 图标即可整理。"
                    saved.hasKey -> "已保存 Key。打开开关即可使用整理功能。"
                    else -> "已保存。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AiServiceError) {
                status = error.message
            } catch (_: Exception) {
                status = "无法保存 AI 服务设置，请重试。"
            } finally {
                saving = false
            }
        }
    }

    fun testConnection() {
        if (busy) return
        focus.clearFocus()
        testing = true
        status = null
        scope.launch {
            try {
                service.test(url, model, key)
                status = "连接成功，模型已返回正文。开启 AI 服务即可使用整理功能。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AiServiceError) {
                status = error.message
            } catch (_: Exception) {
                status = "测试失败，请重新核对地址、模型和 API Key。"
            } finally {
                testing = false
            }
        }
    }

    SettingsPage("AI 服务", onBack, modifier.imePadding()) { minimumBodyHeight ->
        item {
            Column(Modifier.fillMaxWidth().heightIn(min = minimumBodyHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsGroup("模型服务") {
                    SwitchPreference(title = "启用 AI 服务", checked = options.enabled, enabled = !busy,
                        onCheckedChange = { save(enable = it) })
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CaptureField(url, { url = it.take(512); status = null }, "HTTPS 服务地址", !busy)
                        CaptureField(model, { model = it.take(160); status = null }, "模型名称", !busy)
                        CaptureField(key, { key = it.take(2048); status = null },
                            if (options.hasKey) "已保存 API Key，留空则保留" else "API Key",
                            !busy, secret = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NexioSearchFilter(if (saving) "正在保存…" else "保存设置", true, enabled = !busy) { save() }
                            NexioSearchFilter(if (testing) "正在测试…" else "测试连接", false, enabled = !busy) { testConnection() }
                        }
                        if (options.hasKey) {
                            NexioSearchFilter("清除密钥", false, enabled = !busy) { save(clearKey = true) }
                        }
                    }
                }
                status?.let { SettingsNote(it) }
                requestAddress?.let { SettingsNote("请求地址 \n$it") }
                SettingsNote("第三方服务请填写供应商提供的 Base URL（保留 /v1 等路径）和完整模型 ID。测试只发送一句固定样例，可能消耗少量 API 额度。")
                SpeechServiceSettings(service)
            }
        }
    }
}

@Composable
private fun SpeechServiceSettings(service: AiService) {
    val options = service.speechOptions
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var address by remember { mutableStateOf(options.url) }
    var model by remember { mutableStateOf(options.model) }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun save(clear: Boolean = false) {
        if (busy) return
        focus.clearFocus()
        busy = true
        scope.launch {
            try {
                if (clear) service.saveSpeech(options.url, options.model, "", clear = true)
                else service.saveSpeech(address, model, key, clear = false)
                address = service.speechOptions.url
                model = service.speechOptions.model
                key = ""
                status = if (clear) "已清除语音服务密钥。" else "语音服务已保存。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AiServiceError) {
                status = error.message
            } catch (_: Exception) {
                status = "无法保存语音服务设置，请重试。"
            } finally {
                busy = false
            }
        }
    }

    SettingsGroup("联网语音转写") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureField(address, { address = it.take(512); status = null }, "语音转写接口地址", !busy)
            CaptureField(model, { model = it.take(160); status = null }, "转写模型名称", !busy)
            CaptureField(key, { key = it.take(2048); status = null },
                if (options.hasKey) "已保存转写 API Key，留空则保留" else "语音转写 API Key",
                !busy, secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NexioSearchFilter("保存语音设置", true, enabled = !busy) { save() }
                if (options.hasKey) NexioSearchFilter("清除语音密钥", false, enabled = !busy) { save(clear = true) }
            }
        }
    }
    status?.let { SettingsNote(it) }
    SettingsNote("预设 Groq Whisper，也可填支持 M4A 的兼容转写接口。确认联网转写时会上传这一段录音，费用或额度由语音服务账号承担。")
}
