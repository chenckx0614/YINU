package com.yinu.app.sync

import android.util.Base64
import com.yinu.data.SyncChange
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal data class CloudConfig(val project: String, val key: String)
internal data class CloudSession(val userId: String, val email: String, val access: String, val refresh: String, val expiresAt: Long)
internal data class CloudChange(val revision: Long, val record: SyncChange)
internal class CloudHttpError(val status: Int, val errorCode: String? = null) : IOException()
internal class CloudSetupError(message: String) : Exception(message)
internal class CloudResponseTooLarge : IOException()

internal fun cloudConfig(project: String, key: String): CloudConfig {
    val uri = runCatching { URI(project.trim().trimEnd('/')) }.getOrNull()
    if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawPath !in setOf("", "/rest/v1") ||
        uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null || uri.port == 0 || uri.port > 65535
    ) throw CloudSetupError("请填写 Supabase 的项目地址或 Data API 地址。")
    val publicKey = key.trim()
    val validKey = publicKey.matches(Regex("sb_publishable_[a-zA-Z0-9_-]{10,300}")) ||
        (publicKey.length <= 2048 && publicKey.matches(Regex("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+")) &&
            runCatching {
                val claims = Base64.decode(publicKey.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                JSONObject(claims.toString(Charsets.UTF_8)).optString("role") == "anon"
            }.getOrDefault(false))
    if (!validKey) throw CloudSetupError("请填写项目的 Publishable 或 anon 公钥。")
    return CloudConfig("https://${uri.rawAuthority}", publicKey)
}

internal class SupabaseSyncApi(private val config: CloudConfig) {
    suspend fun login(email: String, password: String, register: Boolean): CloudSession? {
        val path = if (register) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
        val data = JSONObject(request(path, body = JSONObject().put("email", email).put("password", password)))
        if (register && !data.has("access_token")) return null
        return parseSession(data, email)
    }

    suspend fun refresh(session: CloudSession): CloudSession {
        val data = JSONObject(request("/auth/v1/token?grant_type=refresh_token",
            body = JSONObject().put("refresh_token", session.refresh)))
        return parseSession(data, session.email)
    }

    suspend fun push(session: CloudSession, rows: List<SyncChange>) {
        request("/rest/v1/rpc/yinu_sync_push", session,
            JSONObject().put("changes", JSONArray(rows.map(SyncChange::toJson))))
    }

    suspend fun pull(session: CloudSession, after: Long, limit: Int): List<CloudChange> {
        val result = JSONArray(request(
            "/rest/v1/yinu_sync_records?select=revision,record_id,updated_at_ms,digest,payload" +
                "&revision=gt.$after&order=revision.asc&limit=$limit", session,
        ))
        return List(result.length()) { index ->
            val row = result.getJSONObject(index)
            CloudChange(row.getLong("revision"), SyncChange(row.getString("record_id"),
                row.getLong("updated_at_ms"), row.getString("digest"), row.getJSONObject("payload")))
        }
    }

    private suspend fun request(path: String, session: CloudSession? = null, body: JSONObject? = null): String {
        currentCoroutineContext().ensureActive()
        val connection = URI(config.project + path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("apikey", config.key)
            connection.setRequestProperty("Accept", "application/json")
            if (session != null) connection.setRequestProperty("Authorization", "Bearer ${session.access}")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 4_000_000) throw CloudSetupError("待同步记录太大，云端没有接收。")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorCode = if (path.startsWith("/auth/v1/") && code in 400..499) {
                    runCatching {
                        connection.errorStream?.use { stream ->
                            val bytes = ByteArray(4096)
                            var size = 0
                            while (size < bytes.size) {
                                val count = stream.read(bytes, size, bytes.size - size)
                                if (count <= 0) break
                                size += count
                            }
                            val response = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
                            response.optString("error_code").ifBlank { response.optString("code") }
                        }
                    }.getOrNull()
                } else null
                throw CloudHttpError(code, errorCode)
            }
            return connection.inputStream.bufferedReader().use { input ->
                val response = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (response.length + count > 4_000_000) throw CloudResponseTooLarge()
                    response.append(buffer, 0, count)
                }
                response.toString()
            }
        } finally {
            connection.disconnect()
        }
    }
}

private fun parseSession(data: JSONObject, email: String): CloudSession {
    val user = data.getJSONObject("user")
    return CloudSession(user.getString("id"), user.optString("email", email),
        data.getString("access_token"), data.getString("refresh_token"),
        System.currentTimeMillis() + data.getLong("expires_in") * 1000)
}
