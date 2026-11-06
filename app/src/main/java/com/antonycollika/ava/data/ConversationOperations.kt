package com.antonycollika.ava.data

/**
 * Pure conversation operations. Message identity is scoped to its conversation, so a branch
 * can retain the original message IDs without mutating the source conversation.
 */
object ConversationOperations {
    data class RegenerationPlan(val userMessageIndex: Int, val hasFollowingMessages: Boolean)

    fun regenerationPlan(messages: List<ChatMessage>, selectedIndex: Int): RegenerationPlan? {
        if (selectedIndex !in messages.indices) return null
        val userIndex = if (messages[selectedIndex].role == "user") {
            selectedIndex
        } else {
            (selectedIndex downTo 0).firstOrNull { messages[it].role == "user" } ?: return null
        }
        return RegenerationPlan(
            userMessageIndex = userIndex,
            hasFollowingMessages = userIndex < messages.lastIndex - 1 ||
                (userIndex < messages.lastIndex && messages[userIndex + 1].role != "assistant")
        )
    }

    fun branchPrefix(messages: List<ChatMessage>, selectedIndex: Int): List<ChatMessage> {
        if (messages.isEmpty() || selectedIndex !in messages.indices) return emptyList()
        return messages.take(selectedIndex + 1)
    }

    fun deleteTurn(messages: List<ChatMessage>, selectedIndex: Int): List<ChatMessage> {
        if (selectedIndex !in messages.indices) return messages
        val selected = messages[selectedIndex]
        val userIndex = if (selected.role == "user") selectedIndex
            else (selectedIndex downTo 0).firstOrNull { messages[it].role == "user" } ?: selectedIndex
        val endIndex = if (messages[userIndex].role == "user" &&
            userIndex + 1 < messages.size && messages[userIndex + 1].role == "assistant"
        ) userIndex + 1 else userIndex
        return messages.filterIndexed { index, _ -> index !in userIndex..endIndex }
    }

    fun estimatedTokens(text: String): Int {
        if (text.isBlank()) return 0
        val utf8Bytes = text.toByteArray(Charsets.UTF_8).size
        return maxOf((text.length + 3) / 4, (utf8Bytes + 2) / 3)
    }

    fun modelContextLimit(model: String): Int {
        val id = model.lowercase()
        return when {
            "gemini" in id -> 1_000_000
            "claude" in id -> 200_000
            "gpt-4.1" in id -> 1_000_000
            "gpt-4o" in id -> 128_000
            "deepseek" in id -> 64_000
            "mistral" in id -> 128_000
            "llama" in id -> 32_000
            else -> 32_000
        }
    }

    fun estimateConversationTokens(
        messages: List<ChatMessage>,
        systemPrompt: String = ""
    ): Int = estimatedTokens(systemPrompt) + messages.sumOf { estimatedTokens(it.content) }

    /**
     * Keeps the newest complete suffix that fits the model budget. The system prompt and
     * a response reserve are counted before history is admitted. The latest user turn is
     * retained even if it alone exceeds the estimate, avoiding a silent empty request.
     */
    fun fitContext(
        messages: List<ChatMessage>,
        model: String,
        systemPrompt: String = "",
        responseReserve: Int = 4096
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val available = (modelContextLimit(model) - responseReserve - estimatedTokens(systemPrompt)).coerceAtLeast(1)
        var start = messages.size - 1
        var used = estimatedTokens(messages.last().content)
        while (start > 0) {
            val previousCost = estimatedTokens(messages[start - 1].content)
            if (used + previousCost > available) break
            used += previousCost
            start--
        }
        while (start < messages.lastIndex && messages[start].role != "user") start++
        return messages.drop(start)
    }
}
