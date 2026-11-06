package com.antonycollika.ava.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ProviderException(message: String) : Exception(message)

class ProviderClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun stream(
        settings: ProviderSettings,
        apiKey: String,
        messages: List<ChatMessage>
    ): Flow<String> = callbackFlow {
        val request = buildRequest(settings, apiKey, messages)
        val call = http.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(500)
                        throw ProviderException("HTTP ${response.code}: ${detail.ifBlank { response.message }}")
                    }
                    val source = response.body?.source() ?: throw ProviderException("The provider returned an empty response.")
                    when (settings.provider) {
                        ProviderType.ANTHROPIC -> readAnthropic(source) { trySend(it) }
                        ProviderType.GEMINI -> readGemini(source) { trySend(it) }
                        else -> readOpenAi(source) { trySend(it) }
                    }
                }
                close()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                close(error)
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    private fun buildRequest(settings: ProviderSettings, key: String, messages: List<ChatMessage>): Request {
        require(key.isNotBlank()) { "Enter your API key in Settings." }
        require(settings.model.isNotBlank()) { "Enter a model name in Settings." }
        val base = settings.baseUrl.trimEnd('/')
        require(base.startsWith("https://")) { "For security, Ava requires an HTTPS provider URL." }
        val contextMessages = ConversationOperations.fitContext(messages, settings.model, settings.systemPrompt)
        val transcript = contextMessages.map { it.role to it.content }
        val body: String
        val builder = Request.Builder().header("Accept", "text/event-stream")

        when (settings.provider) {
            ProviderType.ANTHROPIC -> {
                val payloadMessages = JSONArray()
                transcript.forEach { (role, content) ->
                    payloadMessages.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user")
                        .put("content", content))
                }
                body = JSONObject().put("model", settings.model).put("max_tokens", 4096)
                    .put("stream", true).put("messages", payloadMessages)
                    .apply { if (settings.systemPrompt.isNotBlank()) put("system", settings.systemPrompt) }
                    .toString()
                builder.url(endpoint(base, "messages"))
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
            }
            ProviderType.GEMINI -> {
                val contents = JSONArray()
                transcript.forEach { (role, content) ->
                    contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", content))))
                }
                val payload = JSONObject().put("contents", contents)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 4096))
                if (settings.systemPrompt.isNotBlank()) {
                    payload.put("systemInstruction", JSONObject().put("parts",
                        JSONArray().put(JSONObject().put("text", settings.systemPrompt))))
                }
                body = payload.toString()
                val modelPath = settings.model.removePrefix("models/")
                val url = endpoint(base, "models/${modelPath}:streamGenerateContent?alt=sse&key=${URLEncoder.encode(key, "UTF-8")}")
                builder.url(url)
            }
            else -> {
                val payloadMessages = JSONArray()
                if (settings.systemPrompt.isNotBlank()) {
                    payloadMessages.put(JSONObject().put("role", "system").put("content", settings.systemPrompt))
                }
                transcript.forEach { (role, content) ->
                    payloadMessages.put(JSONObject().put("role", role).put("content", content))
                }
                body = JSONObject().put("model", settings.model).put("stream", true)
                    .put("messages", payloadMessages).toString()
                builder.url(endpoint(base, "chat/completions")).header("Authorization", "Bearer $key")
            }
        }
        return builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    private fun endpoint(base: String, path: String): String {
        val normalized = base.trimEnd('/')
        if (normalized.endsWith("/$path")) return normalized
        return if (normalized.endsWith("/v1") || normalized.endsWith("/v1beta")) {
            "$normalized/$path"
        } else {
            "$normalized/v1/$path"
        }
    }

    private suspend fun readOpenAi(source: BufferedSource, emit: (String) -> Unit) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val chunk = runCatching {
                JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta")?.optString("content")
            }.getOrNull()
            if (!chunk.isNullOrEmpty() && chunk != "null") emit(chunk)
        }
    }

    private suspend fun readAnthropic(source: BufferedSource, emit: (String) -> Unit) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            if (event.optString("type") == "error") {
                throw ProviderException(event.optJSONObject("error")?.optString("message") ?: "Anthropic stream error.")
            }
            if (event.optString("type") == "content_block_delta") {
                val text = event.optJSONObject("delta")?.optString("text")
                if (!text.isNullOrEmpty() && text != "null") emit(text)
            }
        }
    }

    private suspend fun readGemini(source: BufferedSource, emit: (String) -> Unit) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val candidate = runCatching {
                JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
            }.getOrNull() ?: continue
            for (index in 0 until candidate.length()) {
                val text = candidate.optJSONObject(index)?.optString("text")
                if (!text.isNullOrEmpty() && text != "null") emit(text)
            }
        }
    }
}
