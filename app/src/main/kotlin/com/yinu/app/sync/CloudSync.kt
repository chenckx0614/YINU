package com.yinu.app.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.yinu.app.ai.decrypt
import com.yinu.app.ai.encrypt
import com.yinu.data.RecordSyncStore
import com.yinu.database.YinuDatabase
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val Batch = 20
private const val SyncJobId = 0x59494E

internal data class CloudStatus(
    val project: String = "",
    val publicKey: String = "",
    val email: String = "",
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    val lastSync: Long = 0,
    val message: String? = null,
)

@Singleton
internal class CloudSync @Inject constructor(@ApplicationContext private val context: Context, database: YinuDatabase) {
    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
    private val records = RecordSyncStore(database)
    private val gate = Mutex()
    private val mutableStatus = MutableStateFlow(CloudStatus(
        project = prefs.getString("project", null).orEmpty(),
        publicKey = prefs.getString("key", null).orEmpty(),
        email = prefs.getString("email", null).orEmpty(),
        signedIn = prefs.contains("session"),
        lastSync = prefs.getLong("lastSync", 0),
    ))
    val status = mutableStatus.asStateFlow()

    suspend fun signIn(config: CloudConfig, email: String, password: String, register: Boolean): String =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val address = email.trim()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() || address.length > 254 ||
                    password.isEmpty() || password.length > 1024
                ) throw CloudSetupError("请输入有效的邮箱和密码。")
                val existingProject = prefs.getString("project", null)
                val existingEmail = prefs.getString("email", null)
                if (prefs.contains("account") &&
                    (config.project != existingProject || !address.equals(existingEmail, ignoreCase = true))
                ) throw CloudSetupError("本机记录已绑定原账号，请使用原项目和邮箱登录。")
                val session = try {
                    SupabaseSyncApi(config).login(address, password, register)
                } catch (failure: CloudHttpError) {
                    throw CloudSetupError(authFailureMessage(failure, register))
                } catch (_: SocketTimeoutException) {
                    throw CloudSetupError("连接 Supabase 超时，请换个网络再试。")
                } catch (_: UnknownHostException) {
                    throw CloudSetupError("手机无法解析项目域名，请检查项目地址和网络。")
                } catch (_: SSLException) {
                    throw CloudSetupError("手机无法建立 HTTPS 连接，请检查系统时间和网络。")
                } catch (_: IOException) {
                    throw CloudSetupError("手机未能连接 Supabase，请检查网络后重试。")
                } ?: return@withLock "请在邮箱确认注册，然后回到这里登录。"
                val bound = prefs.getString("account", null)
                if (bound != null && session.userId != bound) throw CloudSetupError("此账号与本机绑定的账号不同。")
                val edit = prefs.edit().putString("project", config.project).putString("key", config.key)
                    .putString("email", session.email).putString("account", session.userId)
                persistSession(edit, session)
                mutableStatus.value = CloudStatus(config.project, config.key, session.email, signedIn = true,
                    lastSync = prefs.getLong("lastSync", 0))
                schedule()
                "已登录，准备同步。"
            }
        }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        gate.withLock {
            check(prefs.edit().remove("session").commit())
            context.getSystemService(JobScheduler::class.java)?.cancel(SyncJobId)
            mutableStatus.value = mutableStatus.value.copy(signedIn = false, syncing = false, message = "已退出同步，本机记录仍可使用。")
        }
    }

    fun schedule() {
        if (!status.value.signedIn) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(SyncJobId) != null) return
        val job = JobInfo.Builder(SyncJobId, ComponentName(context, CloudSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        if (!status.value.signedIn || !gate.tryLock()) return@withContext true
        try {
            val current = mutableStatus.value
            if (!current.signedIn) return@withContext true
            mutableStatus.value = current.copy(syncing = true, message = "正在同步…")
            val api = SupabaseSyncApi(CloudConfig(current.project, current.publicKey))
            var session = readSession() ?: return@withContext needsLogin()
            if (session.expiresAt - System.currentTimeMillis() < 60_000) {
                val userId = session.userId
                session = try {
                    api.refresh(session)
                } catch (failure: CloudHttpError) {
                    if (failure.status in setOf(400, 401, 403)) return@withContext needsLogin()
                    throw failure
                }
                if (session.userId != userId) return@withContext needsLogin()
                persistSession(prefs.edit(), session)
            }
            var workRemaining = false
            for (batch in 0 until 40) {
                val pending = records.pending(Batch)
                if (pending.isEmpty()) break
                // Pack by actual request size as well as record count.
                val send = pending.takeWhileWithinBudget()
                api.push(session, send)
                records.acknowledge(send)
                if (batch == 39) workRemaining = true
            }
            var cursor = prefs.getLong("cursor", 0)
            var pageSize = Batch
            for (batch in 0 until 40) {
                val changed = try {
                    api.pull(session, cursor, pageSize)
                } catch (_: CloudResponseTooLarge) {
                    if (pageSize == 1) throw CloudSetupError("云端有单条记录超出读取上限。")
                    pageSize = (pageSize / 2).coerceAtLeast(1)
                    workRemaining = true
                    continue
                }
                if (changed.isEmpty()) break
                records.apply(changed.map { it.record })
                cursor = changed.last().revision
                check(prefs.edit().putLong("cursor", cursor).commit())
                if (batch == 39) workRemaining = true
            }
            val now = System.currentTimeMillis()
            check(prefs.edit().putLong("lastSync", now).commit())
            mutableStatus.value = mutableStatus.value.copy(lastSync = now,
                message = if (workRemaining) "已合并一批记录，下次会继续。" else "同步完成")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudHttpError) {
            if (failure.status == 401) {
                // Refresh on the next attempt if the server expired the access token early.
                readSession()?.let { persistSession(prefs.edit(), it.copy(expiresAt = 0)) }
            }
            mutableStatus.value = mutableStatus.value.copy(message = when (failure.status) {
                404 -> "找不到同步接口，请在 Supabase 初始化同步表。"
                401, 403 -> "云端暂时拒绝同步，请检查账号权限。"
                else -> "云端同步失败（HTTP ${failure.status}），稍后重试。"
            })
            false
        } catch (failure: CloudSetupError) {
            mutableStatus.value = mutableStatus.value.copy(message = failure.message)
            false
        } catch (_: Exception) {
            mutableStatus.value = mutableStatus.value.copy(message = "同步暂未完成，请检查网络后重试。")
            false
        } finally {
            mutableStatus.value = mutableStatus.value.copy(syncing = false)
            gate.unlock()
        }
    }

    private fun List<com.yinu.data.SyncChange>.takeWhileWithinBudget(): List<com.yinu.data.SyncChange> {
        var bytes = 0
        val batch = takeWhile {
            bytes += it.toJson().toString().toByteArray(Charsets.UTF_8).size + 1
            bytes <= 3_500_000
        }
        if (batch.isEmpty()) throw CloudSetupError("有一条记录超过云端同步大小上限。")
        return batch
    }

    private fun readSession(): CloudSession? = runCatching {
        val data = JSONObject(decrypt(checkNotNull(prefs.getString("session", null))))
        CloudSession(data.getString("id"), data.getString("email"), data.getString("access"),
            data.getString("refresh"), data.getLong("expires"))
    }.getOrNull()

    private fun persistSession(edit: android.content.SharedPreferences.Editor, session: CloudSession) {
        val json = JSONObject().put("id", session.userId).put("email", session.email)
            .put("access", session.access).put("refresh", session.refresh).put("expires", session.expiresAt)
        check(edit.putString("session", encrypt(json.toString())).commit())
    }

    private fun needsLogin(): Boolean {
        prefs.edit().remove("session").commit()
        context.getSystemService(JobScheduler::class.java)?.cancel(SyncJobId)
        mutableStatus.value = mutableStatus.value.copy(signedIn = false, message = "登录已失效，请重新登录。")
        return true
    }
}

internal fun authFailureMessage(failure: CloudHttpError, register: Boolean): String = when (failure.errorCode) {
    "email_not_confirmed" -> "邮箱还没验证，请点邮件里的确认链接后再登录。"
    "invalid_credentials" -> "同步邮箱或密码不对。首次使用请先点「注册账号」。"
    "user_already_exists", "email_exists" -> "这个邮箱已注册，请验证邮箱后登录。"
    "weak_password" -> "密码不符合项目要求，请换一个更长的密码。"
    "signup_disabled", "email_provider_disabled" -> "项目关闭了邮箱注册，请检查 Supabase 的身份验证设置。"
    "email_address_not_authorized" -> "默认邮件服务只给项目团队成员发信，请用创建项目的邮箱注册。"
    else -> when (failure.status) {
        400, 422 -> if (register) "注册失败，请检查邮箱和密码。"
            else "登录失败，请核对同步邮箱和密码，首次使用需先注册。"
        401, 403 -> "项目拒绝了请求，请确认公钥与项目地址匹配。"
        429 -> "请求太频繁，请稍后重试。"
        else -> "登录服务暂不可用（HTTP ${failure.status}）。"
    }
}

@AndroidEntryPoint
class CloudSyncJob : JobService() {
    @Inject internal lateinit var cloud: CloudSync
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch { jobFinished(params, !cloud.sync()) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
