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

    @Test fun deleteRemovesUserAndAssistantPair() {
        val source = listOf(message("user", "u1"), message("assistant", "a1"), message("user", "u2"))
        assertEquals(listOf(source.last()), ConversationOperations.deleteTurn(source, 1))
    }

    @Test fun legacyMessagesDecodeAsNotEdited() {
        val decoded = ChatJson.decode("""[{"id":"1","role":"user","content":"hello"}]""")
        assertFalse(decoded.single().edited)
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
