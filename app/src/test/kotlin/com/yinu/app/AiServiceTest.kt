package com.yinu.app

import com.yinu.app.ai.AiService
import com.yinu.app.ai.AiServiceError
import com.yinu.app.ai.aiEndpoint
import com.yinu.app.ai.aiHttpMessage
import com.yinu.app.ai.aiReply
import com.yinu.app.ai.SpeechMultipart
import com.yinu.app.ai.speechEndpoint
import com.yinu.app.ai.speechReply
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiServiceTest {
    @Test
    fun resolvesBaseAndFullEndpointWithoutAcceptingUnsafeAddresses() {
        assertEquals("https://example.com/v1/chat/completions", aiEndpoint("https://example.com/v1/").toString())
        assertEquals("https://example.com/v1/chat/completions", aiEndpoint("https://example.com/v1/chat/completions").toString())
        assertEquals("https://api.openai.com/v1/chat/completions", aiEndpoint("https://api.openai.com").toString())
        listOf("http://example.com", "https://name@example.com", "https://example.com?key=value", "https://example.com/v1/responses").forEach { url ->
            assertThrows(AiServiceError::class.java) { aiEndpoint(url) }
        }
    }

    @Test
    fun onlyUsesVisibleAssistantText() {
        assertEquals("待办", aiReply("""{"choices":[{"message":{"content":" 待办 "}}]}"""))
        assertThrows(AiServiceError::class.java) {
            aiReply("""{"choices":[{"message":{"reasoning_content":"private","content":null}}]}""")
        }
        assertThrows(AiServiceError::class.java) { aiReply("<html>") }
    }

    @Test
    fun readsGatewayContentBlocksAndDiagnosesMachineReadableErrors() {
        assertEquals("待办", aiReply("""{"choices":[{"message":{"content":[{"type":"text","text":"待办"}]}}]}"""))
        assertEquals("服务找不到这个模型，请复制供应商提供的模型 ID。（HTTP 404）",
            aiHttpMessage(404, JSONObject("""{"code":"model_not_found","message":"hidden provider data"}""")))
    }

    @Test
    fun startsDisabledAndRequiresCredentials() {
        val service = AiService(RuntimeEnvironment.getApplication())
        assertFalse(service.options.enabled)
        assertThrows(AiServiceError::class.java) {
            runBlocking { service.save(true, service.options.baseUrl, service.options.model, "") }
        }
        assertThrows(AiServiceError::class.java) {
            runBlocking { service.organize(listOf("本地记录")) }
        }
        assertFalse(service.options.enabled)
    }

    @Test
    fun enableAndDisableArePersistedWithoutResavingConfiguration() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ai_service", 0)
        prefs.edit().putString("key", "stored-ciphertext").commit()
        try {
            val service = AiService(context)
            service.setEnabled(true)
            assertTrue(AiService(context).options.enabled)
            service.setEnabled(false)
            assertFalse(AiService(context).options.enabled)
            assertTrue(AiService(context).options.hasKey)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun voiceUploadKeepsOriginalAudioAndUsesTheConfiguredTranscriptionRoute() = runBlocking {
        assertEquals("/openai/v1/audio/transcriptions",
            speechEndpoint("https://api.groq.com/openai/v1/audio/transcriptions").path)
        assertThrows(AiServiceError::class.java) { speechEndpoint("https://api.example.com/v1/chat/completions") }
        assertEquals("测试语音", speechReply("""{"text":" 测试语音 "}"""))
        val file = File.createTempFile("sample-", ".m4a", RuntimeEnvironment.getApplication().cacheDir)
        try {
            val audio = byteArrayOf(0, 3, -1, 1)
            file.writeBytes(audio)
            val upload = SpeechMultipart("test-boundary", "speech-model")
            val buffer = ByteArrayOutputStream()
            upload.write(file, buffer)
            val body = buffer.toByteArray()
            val parts = String(body, Charsets.ISO_8859_1)
            assertEquals(upload.overhead + audio.size, body.size)
            assertTrue(parts.contains("name=\"model\"\r\n\r\nspeech-model"))
            val offset = parts.indexOf("audio/mp4\r\n\r\n") + "audio/mp4\r\n\r\n".length
            assertTrue(audio.contentEquals(body.copyOfRange(offset, offset + audio.size)))
            assertTrue(parts.endsWith("\r\n--test-boundary--\r\n"))
        } finally {
            file.delete()
        }
    }
}
