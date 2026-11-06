package com.antonycollika.ava.data

import org.json.JSONArray
import org.json.JSONObject

enum class ProviderType(val label: String, val defaultModel: String, val defaultBaseUrl: String) {
    OPENAI("OpenAI", "gpt-4o-mini", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic", "claude-sonnet-4-5", "https://api.anthropic.com/v1"),
    GEMINI("Google Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta"),
    OPENROUTER("OpenRouter", "openai/gpt-4o-mini", "https://openrouter.ai/api/v1"),
    DEEPSEEK("DeepSeek", "deepseek-chat", "https://api.deepseek.com/v1"),
    GROQ("Groq", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1"),
    MISTRAL("Mistral", "mistral-small-latest", "https://api.mistral.ai/v1"),
    OPENAI_COMPATIBLE("Custom OpenAI-compatible", "", "https://");

    companion object {
        fun fromName(value: String?): ProviderType =
            entries.firstOrNull { it.name == value } ?: OPENAI
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val edited: Boolean = false,
    val attachments: List<MessageAttachment> = emptyList()
)

data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long
)

object ChatJson {
    fun encode(messages: List<ChatMessage>): String = JSONArray().apply {
        messages.forEach { message ->
            put(JSONObject().put("id", message.id).put("role", message.role)
                .put("content", message.content).put("createdAt", message.createdAt)
                 .put("edited", message.edited)
                .put("attachments", JSONArray().apply {
                    message.attachments.forEach { attachment ->
                        put(JSONObject().put("id", attachment.id)
                            .put("mimeType", attachment.mimeType)
                            .put("fileName", attachment.fileName))
                    }
                }))
        }
    }.toString()

    fun decode(raw: String?): List<ChatMessage> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(ChatMessage(
                    id = item.optString("id", index.toString()),
                    role = item.optString("role", "user"),
                    content = item.optString("content"),
                    createdAt = item.optLong("createdAt", 0L),
                    edited = item.optBoolean("edited", false),
                    attachments = item.optJSONArray("attachments")?.let { array ->
                        buildList {
                            for (attachmentIndex in 0 until array.length()) {
                                val attachment = array.optJSONObject(attachmentIndex) ?: continue
                                val id = attachment.optString("id")
                                val mime = attachment.optString("mimeType")
                                if (id.isNotBlank() && mime in AttachmentStore.SUPPORTED_TYPES) {
                                    add(MessageAttachment(id, mime, attachment.optString("fileName", "image")))
                                }
                            }
                        }
                    }.orEmpty()
                ))
            }
        }
    }.getOrDefault(emptyList())
}
