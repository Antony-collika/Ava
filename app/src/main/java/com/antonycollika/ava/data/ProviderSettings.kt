package com.antonycollika.ava.data

import android.content.Context

data class ProviderSettings(
    val provider: ProviderType,
    val model: String,
    val baseUrl: String,
    val systemPrompt: String
)

class ProviderSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ava_provider_settings", Context.MODE_PRIVATE)

    fun load(): ProviderSettings {
        val provider = ProviderType.fromName(prefs.getString("provider", ProviderType.OPENAI.name))
        return ProviderSettings(
            provider = provider,
            model = prefs.getString("model", null)?.takeIf(String::isNotBlank) ?: provider.defaultModel,
            baseUrl = prefs.getString("base_url", null)?.takeIf(String::isNotBlank) ?: provider.defaultBaseUrl,
            systemPrompt = prefs.getString("system_prompt", "") ?: ""
        )
    }

    fun save(settings: ProviderSettings) {
        prefs.edit().putString("provider", settings.provider.name)
            .putString("model", settings.model.trim())
            .putString("base_url", settings.baseUrl.trim().trimEnd('/'))
            .putString("system_prompt", settings.systemPrompt)
            .apply()
    }
}

class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("ava_conversations", Context.MODE_PRIVATE)

    fun loadAll(): List<Conversation> = runCatching {
        val ids = prefs.getStringSet("ids", emptySet()).orEmpty()
        ids.mapNotNull { id ->
            val messages = ChatJson.decode(prefs.getString("conversation_$id", null))
            if (messages.isEmpty()) null else Conversation(
                id = id,
                title = prefs.getString("title_$id", messages.first().content.take(42)) ?: "New chat",
                messages = messages,
                updatedAt = prefs.getLong("updated_$id", 0L)
            )
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun save(conversation: Conversation) {
        val ids = prefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet().apply { add(conversation.id) }
        prefs.edit().putStringSet("ids", ids)
            .putString("conversation_${conversation.id}", ChatJson.encode(conversation.messages))
            .putString("title_${conversation.id}", conversation.title)
            .putLong("updated_${conversation.id}", conversation.updatedAt)
            .apply()
    }

    fun delete(id: String) {
        val ids = prefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet().apply { remove(id) }
        prefs.edit().putStringSet("ids", ids).remove("conversation_$id")
            .remove("title_$id").remove("updated_$id").apply()
    }
}
