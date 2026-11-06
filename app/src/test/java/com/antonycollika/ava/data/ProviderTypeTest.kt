package com.antonycollika.ava.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProviderTypeTest {
    @Test
    fun unknownProviderFallsBackToOpenAi() {
        assertEquals(ProviderType.OPENAI, ProviderType.fromName("not-a-provider"))
        assertEquals(ProviderType.OPENAI, ProviderType.fromName(null))
    }

    @Test
    fun builtInProvidersHaveUsableDefaults() {
        listOf(ProviderType.OPENAI, ProviderType.ANTHROPIC, ProviderType.GEMINI).forEach { provider ->
            assertNotNull(provider.defaultModel.takeIf(String::isNotBlank))
            assertEquals(true, provider.defaultBaseUrl.startsWith("https://"))
        }
    }
}
