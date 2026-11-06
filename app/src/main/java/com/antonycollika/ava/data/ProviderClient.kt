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
import android.util.Base64
import java.util.concurrent.TimeUnit

class ProviderException(message: String) : Exception(message)

sealed interface ProviderEvent {
    data class Text(val value: String) : ProviderEvent
    data class ToolCallDelta(val index: Int, val id: String? = null, val name: String? = null, val argumentsDelta: String = "") : ProviderEvent
}

class ProviderClient(private val attachmentStore: AttachmentStore? = null) {
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

    fun streamEvents(
        settings: ProviderSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        tools: JSONArray,
        registry: ToolRegistryStore
    ): Flow<ProviderEvent> = callbackFlow {
        val request = buildRequest(settings, apiKey, messages, tools, registry)
        val call = http.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(500)
                        throw ProviderException("HTTP ${response.code}: ${detail.ifBlank { response.message }}")
                    }
                    val source = response.body?.source() ?: throw ProviderException("The provider returned an empty response.")
                    val emit: (ProviderEvent) -> Unit = { trySend(it) }
                    when (settings.provider) {
                        ProviderType.ANTHROPIC -> readAnthropicEvents(source, emit)
                        ProviderType.GEMINI -> readGeminiEvents(source, emit)
                        else -> readOpenAiEvents(source, emit)
                    }
                }
                close()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                close(error)
            }
        }
        awaitClose { call.cancel(); reader.cancel() }
    }

    private fun buildRequest(settings: ProviderSettings, key: String, messages: List<ChatMessage>, tools: JSONArray? = null, registry: ToolRegistryStore? = null): Request {
        require(key.isNotBlank()) { "Enter your API key in Settings." }
        require(settings.model.isNotBlank()) { "Enter a model name in Settings." }
        val base = settings.baseUrl.trimEnd('/')
        require(base.startsWith("https://")) { "For security, Ava requires an HTTPS provider URL." }
        val contextMessages = ConversationOperations.fitContext(messages, settings.model, settings.systemPrompt)
        val body: String
        val builder = Request.Builder().header("Accept", "text/event-stream")

        when (settings.provider) {
            ProviderType.ANTHROPIC -> {
                val payloadMessages = JSONArray()
                contextMessages.forEach { message ->
                    if (message.role == "assistant" && message.toolCalls.isNotEmpty()) {
                        val blocks = JSONArray()
                        if (message.content.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", message.content))
                        message.toolCalls.forEach { call -> blocks.put(JSONObject().put("type", "tool_use")
                            .put("id", call.id).put("name", registry?.control(call.uid)?.modelName ?: call.modelName)
                            .put("input", runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject()))) }
                        payloadMessages.put(JSONObject().put("role", "assistant").put("content", blocks)); return@forEach
                    }
                    if (message.role == "tool") {
                        payloadMessages.put(JSONObject().put("role", "user").put("content", JSONArray().put(
                            JSONObject().put("type", "tool_result").put("tool_use_id", message.toolCallId).put("content", message.content))))
                        return@forEach
                    }
                    val blocks = JSONArray()
                    if (message.content.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", message.content))
                    message.attachments.forEach { attachment ->
                        val bytes = requireNotNull(attachmentStore) { "Image attachments are unavailable." }.read(attachment)
                        blocks.put(JSONObject().put("type", "image").put("source", JSONObject()
                            .put("type", "base64").put("media_type", attachment.mimeType)
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                    }
                    payloadMessages.put(JSONObject().put("role", if (message.role == "assistant") "assistant" else "user")
                        .put("content", if (message.attachments.isEmpty()) message.content else blocks))
                }
                val anthropicTools = tools?.let { source -> JSONArray().apply {
                    for (index in 0 until source.length()) {
                        val function = source.getJSONObject(index).getJSONObject("function")
                        put(JSONObject().put("name", function.getString("name"))
                            .put("description", function.optString("description"))
                            .put("input_schema", function.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
                    }
                } }
                body = JSONObject().put("model", settings.model).put("max_tokens", 4096)
                    .put("stream", true).put("messages", payloadMessages)
                    .apply {
                        if (settings.systemPrompt.isNotBlank()) put("system", settings.systemPrompt)
                        if (anthropicTools != null && anthropicTools.length() > 0) put("tools", anthropicTools)
                    }.toString()
                builder.url(endpoint(base, "messages"))
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
            }
            ProviderType.GEMINI -> {
                val contents = JSONArray()
                contextMessages.forEach { message ->
                    if (message.role == "assistant" && message.toolCalls.isNotEmpty()) {
                        val parts = JSONArray()
                        if (message.content.isNotBlank()) parts.put(JSONObject().put("text", message.content))
                        message.toolCalls.forEach { call -> parts.put(JSONObject().put("functionCall", JSONObject()
                            .put("name", registry?.control(call.uid)?.modelName ?: call.modelName)
                            .put("args", runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())))) }
                        contents.put(JSONObject().put("role", "model").put("parts", parts)); return@forEach
                    }
                    if (message.role == "tool") {
                        val name = message.toolUid?.let { registry?.control(it)?.modelName } ?: message.toolUid ?: "tool"
                        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject()
                            .put("functionResponse", JSONObject().put("name", name)
                                .put("response", JSONObject().put("result", message.content))))))
                        return@forEach
                    }
                    val parts = JSONArray()
                    if (message.content.isNotBlank()) parts.put(JSONObject().put("text", message.content))
                    message.attachments.forEach { attachment ->
                        val bytes = requireNotNull(attachmentStore) { "Image attachments are unavailable." }.read(attachment)
                        parts.put(JSONObject().put("inlineData", JSONObject()
                            .put("mimeType", attachment.mimeType)
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                    }
                    contents.put(JSONObject().put("role", if (message.role == "assistant") "model" else "user")
                        .put("parts", parts))
                }
                val geminiTools = tools?.let { source -> JSONArray().apply {
                    val declarations = JSONArray()
                    for (index in 0 until source.length()) {
                        val function = source.getJSONObject(index).getJSONObject("function")
                        declarations.put(JSONObject().put("name", function.getString("name"))
                            .put("description", function.optString("description"))
                            .put("parameters", function.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
                    }
                    if (declarations.length() > 0) put(JSONObject().put("functionDeclarations", declarations))
                } }
                val payload = JSONObject().put("contents", contents)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 4096))
                    .apply { if (geminiTools != null && geminiTools.length() > 0) put("tools", geminiTools) }
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
                contextMessages.forEach { message ->
                    if (message.role == "assistant" && message.toolCalls.isNotEmpty()) {
                        val calls = JSONArray().apply { message.toolCalls.forEach { call -> put(JSONObject()
                            .put("id", call.id).put("type", "function").put("function", JSONObject()
                                .put("name", registry?.control(call.uid)?.modelName ?: call.modelName)
                                .put("arguments", call.arguments))) } }
                        payloadMessages.put(JSONObject().put("role", "assistant").put("content", message.content)
                            .put("tool_calls", calls)); return@forEach
                    }
                    if (message.role == "tool") {
                        payloadMessages.put(JSONObject().put("role", "tool").put("tool_call_id", message.toolCallId)
                            .put("content", message.content)); return@forEach
                    }
                    val content: Any = if (message.attachments.isEmpty()) message.content else JSONArray().apply {
                        if (message.content.isNotBlank()) put(JSONObject().put("type", "text").put("text", message.content))
                        message.attachments.forEach { attachment ->
                            val bytes = requireNotNull(attachmentStore) { "Image attachments are unavailable." }.read(attachment)
                            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put(
                                "url", "data:${attachment.mimeType};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
                            )))
                        }
                    }
                    payloadMessages.put(JSONObject().put("role", message.role).put("content", content))
                }
                body = JSONObject().put("model", settings.model).put("stream", true)
                    .put("messages", payloadMessages)
                    .apply { if (tools != null && tools.length() > 0) put("tools", tools).put("tool_choice", "auto") }
                    .toString()
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

    private suspend fun readOpenAiEvents(source: BufferedSource, emit: (ProviderEvent) -> Unit) {
        data class Partial(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
        val calls = linkedMapOf<Int, Partial>()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val delta = runCatching { JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") }.getOrNull() ?: continue
            delta.optString("content").takeIf { it.isNotBlank() && it != "null" }?.let { emit(ProviderEvent.Text(it)) }
            val tools = delta.optJSONArray("tool_calls") ?: continue
            for (index in 0 until tools.length()) {
                val item = tools.optJSONObject(index) ?: continue
                val callIndex = item.optInt("index", index)
                val partial = calls.getOrPut(callIndex) { Partial() }
                item.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { partial.id = it }
                val function = item.optJSONObject("function")
                function?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }?.let { partial.name = it }
                val fragment = function?.optString("arguments").orEmpty().takeIf { it != "null" }.orEmpty()
                if (fragment.isNotEmpty()) partial.args.append(fragment)
                emit(ProviderEvent.ToolCallDelta(callIndex, partial.id.takeIf { it.isNotBlank() }, partial.name.takeIf { it.isNotBlank() }, fragment))
            }
        }
    }

    private suspend fun readAnthropicEvents(source: BufferedSource, emit: (ProviderEvent) -> Unit) {
        var eventName = ""
        var blockIndex = 0
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("event:")) { eventName = line.removePrefix("event:").trim(); continue }
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (eventName.ifBlank { event.optString("type") }) {
                "content_block_start" -> {
                    blockIndex = event.optInt("index", blockIndex)
                    val block = event.optJSONObject("content_block")
                    if (block?.optString("type") == "tool_use") {
                        emit(ProviderEvent.ToolCallDelta(blockIndex, block.optString("id"), block.optString("name"),
                            block.optJSONObject("input")?.toString()?.takeIf { it != "{}" }.orEmpty()))
                    }
                }
                "content_block_delta" -> {
                    val delta = event.optJSONObject("delta") ?: continue
                    if (delta.optString("type") == "text_delta") delta.optString("text").takeIf { it.isNotEmpty() }?.let { emit(ProviderEvent.Text(it)) }
                    if (delta.optString("type") == "input_json_delta") emit(ProviderEvent.ToolCallDelta(event.optInt("index", blockIndex),
                        argumentsDelta = delta.optString("partial_json")))
                }
                "error" -> throw ProviderException(event.optJSONObject("error")?.optString("message") ?: "Anthropic stream error.")
            }
        }
    }

    private suspend fun readGeminiEvents(source: BufferedSource, emit: (ProviderEvent) -> Unit) {
        var index = 0
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            val parts = runCatching { JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") }.getOrNull() ?: continue
            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                part.optString("text").takeIf { it.isNotBlank() && it != "null" }?.let { emit(ProviderEvent.Text(it)) }
                val call = part.optJSONObject("functionCall") ?: continue
                emit(ProviderEvent.ToolCallDelta(index++, call.optString("id").ifBlank { null },
                    call.optString("name"), call.optJSONObject("args")?.toString().orEmpty()))
            }
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
