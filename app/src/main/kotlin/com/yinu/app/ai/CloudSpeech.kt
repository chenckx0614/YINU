package com.yinu.app.ai

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject

internal const val SpeechDefaultUrl = "https://api.groq.com/openai/v1/audio/transcriptions"
internal const val SpeechDefaultModel = "whisper-large-v3-turbo"

internal data class SpeechOptions(val url: String, val model: String, val hasKey: Boolean)

internal fun speechEndpoint(url: String): URI {
    val address = httpsServiceAddress(url)
    if (!address.path.endsWith("/audio/transcriptions")) {
        throw AiServiceError("请填写以 /audio/transcriptions 结尾的完整 HTTPS 地址。")
    }
    return address
}

internal suspend fun cloudTranscribe(endpoint: URI, model: String, key: String, file: File): String {
    val length = file.length()
    if (length == 0L) throw AiServiceError("录音文件为空。")
    if (length > 24_000_000L) throw AiServiceError("录音超过 24 MB，请录一段更短的语音。")
    val boundary = "yinu-" + UUID.randomUUID()
    val body = SpeechMultipart(boundary, model)
    val connection = endpoint.toURL().openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = 12_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(length + body.overhead)
        connection.outputStream.use { body.write(file, it) }
        val status = connection.responseCode
        if (status !in 200..299) {
            val error = try {
                connection.errorStream?.bufferedReader()?.use { JSONObject(it.readBounded(8192)).optJSONObject("error") }
            } catch (_: JSONException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: AiServiceError) {
                null
            }
            throw AiServiceError(if (status == 404 && error?.optString("code") != "model_not_found") {
                "找不到语音转写接口，请核对服务地址和模型 ID。（HTTP 404）"
            } else aiHttpMessage(status, error))
        }
        return connection.inputStream.bufferedReader().use { speechReply(it.readBounded(96_000)) }
    } catch (_: SocketTimeoutException) {
        throw AiServiceError("联网转写超时，可以重试或发送原语音。")
    } catch (_: IOException) {
        throw AiServiceError("连不上语音服务，请检查网络和服务地址。")
    } finally {
        connection.disconnect()
    }
}

internal class SpeechMultipart(boundary: String, model: String) {
    private val start = (
        "--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n" +
        "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\n" +
        "Content-Type: audio/mp4\r\n\r\n"
    ).toByteArray()
    private val end = "\r\n--$boundary--\r\n".toByteArray()
    val overhead get() = start.size + end.size

    suspend fun write(file: File, output: OutputStream) {
        currentCoroutineContext().ensureActive()
        output.write(start)
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        output.write(end)
    }
}

internal fun speechReply(response: String): String {
    val root = try {
        JSONObject(response)
    } catch (_: JSONException) {
        throw AiServiceError("语音服务返回了无法识别的格式，请检查接口。")
    }
    root.optJSONObject("error")?.let { throw AiServiceError(aiHttpMessage(200, it)) }
    return (root.opt("text") as? String)?.trim()
        ?: throw AiServiceError("服务未返回转写正文，请检查接口与模型。")
}
