package com.yinu.app.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haooz.chedule.ui.basic.NexioSearchFilter
import com.yinu.app.SettingsGroup
import com.yinu.app.SettingsNote
import com.yinu.app.SettingsPage
import com.yinu.app.records.CaptureField
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalCloudSync = staticCompositionLocalOf<CloudSync> { error("Missing cloud sync service") }

@Composable
internal fun CloudSyncSettings(onBack: () -> Unit, modifier: Modifier) {
    val sync = LocalCloudSync.current
    val status by sync.status.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var project by rememberSaveable { mutableStateOf(status.project) }
    var key by rememberSaveable { mutableStateOf(status.publicKey) }
    var email by rememberSaveable { mutableStateOf(status.email) }
    var password by remember { mutableStateOf("") }
    var authProgress by remember { mutableStateOf<String?>(null) }
    val busy = authProgress != null
    var detail by remember { mutableStateOf<String?>(null) }

    fun authenticate(register: Boolean) {
        if (busy) return
        focus.clearFocus()
        val config = try {
            cloudConfig(project, key)
        } catch (failure: CloudSetupError) {
            detail = failure.message
            return
        }
        authProgress = if (register) "正在注册…" else "正在登录…"
        detail = null
        scope.launch {
            try {
                detail = sync.signIn(config, email, password, register)
                password = ""
                if (sync.status.value.signedIn) sync.sync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CloudSetupError) {
                detail = error.message
            } catch (_: Exception) {
                detail = "无法连接项目或保存会话。请检查网络和项目配置。"
            } finally {
                authProgress = null
            }
        }
    }

    SettingsPage("跨设备同步", onBack, modifier.imePadding()) { minimumBodyHeight ->
        item {
            Column(Modifier.fillMaxWidth().heightIn(min = minimumBodyHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsGroup("Supabase 账号") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.signedIn) {
                            SettingsNote(status.email)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NexioSearchFilter("立即同步", true, enabled = !status.syncing && !busy) {
                                    detail = null
                                    scope.launch { sync.sync() }
                                }
                                NexioSearchFilter("退出登录", false, enabled = !status.syncing && !busy) {
                                    detail = null
                                    scope.launch { sync.signOut() }
                                }
                            }
                        } else {
                            CaptureField(project, { project = it.take(512) }, "项目地址或 Data API 地址", !busy)
                            CaptureField(key, { key = it.take(2048) }, "Publishable 或 anon 公钥", !busy)
                            CaptureField(email, { email = it.take(254) }, "邮箱", !busy)
                            CaptureField(password, { password = it.take(1024) }, "密码", !busy, secret = true)
                            (authProgress ?: detail ?: status.message)?.let { SettingsNote(it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NexioSearchFilter("登录并同步", true, enabled = !busy) { authenticate(register = false) }
                                NexioSearchFilter("注册账号", false, enabled = !busy) { authenticate(register = true) }
                            }
                        }
                    }
                }
                if (status.signedIn) (status.message ?: detail)?.let { SettingsNote(it) }
                if (status.lastSync > 0L) {
                    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSync))
                    SettingsNote("上次同步 $time")
                }
                SettingsNote("登录后会将本机记录同步到该项目。两台设备使用同一项目、同一邮箱。同步涵盖正文、收藏、删除、引用和附件名称；文件本体仍通过 ZIP 备份迁移。")
            }
        }
    }
}
