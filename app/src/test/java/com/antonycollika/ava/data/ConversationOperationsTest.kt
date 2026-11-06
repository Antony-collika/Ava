package com.antonycollika.ava.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationOperationsTest {
    private fun message(role: String, text: String = role) =
        ChatMessage(id = text, role = role, content = text)

    @Test fun branchCopiesOnlyThroughSelectedMessage() {
        val source = listOf(message("user", "u1"), message("assistant", "a1"), message("user", "u2"))
        assertEquals(source.take(2), ConversationOperations.branchPrefix(source, 1))
        assertEquals(emptyList<ChatMessage>(), ConversationOperations.branchPrefix(source, 9))
    }

    @Test fun regenerationFindsOwningUserTurnAndDetectsSuffix() {
        val source = listOf(message("user", "u1"), message("assistant", "a1"),
            message("user", "u2"), message("assistant", "a2"))
        val plan = ConversationOperations.regenerationPlan(source, 1)!!
        assertEquals(0, plan.userMessageIndex)
        assertTrue(plan.hasFollowingMessages)
        assertFalse(ConversationOperations.regenerationPlan(source.take(2), 1)!!.hasFollowingMessages)
    }

    @Test fun deleteRemovesWholeTurnIncludingToolCallsAndResults() {
        val source = listOf(message("user", "u1"), message("assistant", "tool call"),
            ChatMessage("tool", "tool", "result"), message("assistant", "final"),
            message("user", "u2"))
        assertEquals(listOf(source.last()), ConversationOperations.deleteTurn(source, 2))
    }

    @Test fun regenerationDoesNotBranchForToolMessagesInSameTurn() {
        val source = listOf(message("user", "u1"), message("assistant", "tool call"),
            ChatMessage("tool", "tool", "result"), message("assistant", "final"))
        assertFalse(ConversationOperations.regenerationPlan(source, 3)!!.hasFollowingMessages)
    }

    @Test fun messageEditMetadataRoundTripsAndLegacyDefaultsRemainFalse() {
        val edited = message("user", "corrected").copy(edited = true)
        assertTrue(ChatJson.decode(ChatJson.encode(listOf(edited))).single().edited)
        val legacy = ChatJson.decode("""[{"id":"1","role":"user","content":"hello"}]""")
        assertFalse(legacy.single().edited)
    }

    @Test fun attachmentAndToolCallMetadataRoundTrip() {
        val original = listOf(
            ChatMessage("user-1", "user", "look at this",
                attachments = listOf(MessageAttachment("image-id", "image/jpeg", "photo.jpg"))),
            ChatMessage("assistant-1", "assistant", "opening file",
                toolCalls = listOf(ChatToolCall("call-1", "ava.fs.read", "read_file", """{"path":"notes.md"}"""))),
            ChatMessage("tool-1", "tool", "contents", toolCallId = "call-1", toolUid = "ava.fs.read")
        )
        assertEquals(original, ChatJson.decode(ChatJson.encode(original)))
    }

    @Test fun regenerationWorksWhenAssistantReplyIsMissing() {
        val messages = listOf(message("user", "failed request"))
        val plan = ConversationOperations.regenerationPlan(messages, 0)!!
        assertEquals(0, plan.userMessageIndex)
        assertFalse(plan.hasFollowingMessages)
    }

    @Test fun contextProjectionKeepsNewestUserTurn() {
        val old = message("user", "x".repeat(160_000))
        val latest = message("user", "latest")
        val result = ConversationOperations.fitContext(
            listOf(old, message("assistant", "old reply"), latest),
            model = "llama-small",
            responseReserve = 30_000
        )
        assertEquals(latest, result.last())
        assertTrue(result.none { it.id == old.id })
    }
}
