package com.antonycollika.ava.data

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ToolLoopRunner(private val providerClient: ProviderClient) {
    data class ToolCall(
        val index: Int,
        var id: String = UUID.randomUUID().toString(),
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    suspend fun run(
        settings: ProviderSettings,
        apiKey: String,
        initialMessages: List<ChatMessage>,
        registry: ToolRegistryStore,
        runtime: WorkspaceToolRuntime,
        allowedToolUids: Set<String>,
        confirm: suspend (ToolExecution) -> Boolean,
        onText: (String) -> Unit,
        onAssistantToolCalls: (ChatMessage) -> Unit,
        onToolResult: (ChatMessage) -> Unit,
        onToolRoundComplete: () -> Unit,
        onToolStatus: (String) -> Unit
    ): String {
        val transcript = initialMessages.toMutableList()
        val finalText = StringBuilder()
        repeat(MAX_TOOL_ROUNDS) { round ->
            val tools = registry.modelTools(allowedToolUids, settings.provider)
            val calls = linkedMapOf<Int, ToolCall>()
            val responseText = StringBuilder()
            providerClient.streamEvents(settings, apiKey, transcript, tools, registry).collect { event ->
                when (event) {
                    is ProviderEvent.Text -> {
                        responseText.append(event.value)
                        finalText.append(event.value)
                        onText(event.value)
                    }
                    is ProviderEvent.ToolCallDelta -> {
                        val call = calls.getOrPut(event.index) { ToolCall(event.index) }
                        event.id?.let { call.id = it }
                        event.name?.let { call.name = it }
                        call.arguments.append(event.argumentsDelta)
                    }
                }
            }
            if (calls.isEmpty()) return finalText.toString()
            if (allowedToolUids.isEmpty()) {
                onToolStatus("File tools are blocked until a workspace folder is selected.")
                return finalText.toString()
            }
            val historyCalls = calls.values.sortedBy { it.index }.mapNotNull { call ->
                val control = registry.findByModelName(call.name) ?: return@mapNotNull null
                ChatToolCall(call.id, control.uid, call.name,
                    call.arguments.toString().ifBlank { "{}" })
            }
            val assistant = ChatMessage(UUID.randomUUID().toString(), "assistant",
                responseText.toString(), toolCalls = historyCalls)
            transcript += assistant
            onAssistantToolCalls(assistant)
            calls.values.sortedBy { it.index }.forEach { call ->
                val control = registry.findByModelName(call.name)
                if (control == null) {
                    val unknown = ChatMessage(UUID.randomUUID().toString(), "tool",
                        "Tool unavailable: the name is not registered or enabled.",
                        toolCallId = call.id)
                    transcript += unknown; onToolResult(unknown); return@forEach
                }
                val args = runCatching { JSONObject(call.arguments.toString().ifBlank { "{}" }) }
                    .getOrElse {
                        val invalid = ChatMessage(UUID.randomUUID().toString(), "tool",
                            "Invalid tool arguments: " + (it.message ?: "Malformed JSON."),
                            toolCallId = call.id, toolUid = control.uid)
                        transcript += invalid; onToolResult(invalid); return@forEach
                    }
                val execution = runCatching { runtime.prepare(call.name, args) }.getOrElse {
                    val failed = ChatMessage(UUID.randomUUID().toString(), "tool",
                        it.message ?: "Tool preparation failed.", toolCallId = call.id, toolUid = control.uid)
                    transcript += failed; onToolResult(failed); return@forEach
                }
                onToolStatus(execution.confirmationSummary)
                val approved = if (execution.requiresConfirmation) confirm(execution) else true
                val result = if (!approved) ToolResult(control.uid, false, "User declined this operation.")
                    else withContext(Dispatchers.IO) { runtime.execute(execution, confirmed = approved) }
                val toolMessage = ChatMessage(UUID.randomUUID().toString(), "tool",
                    (if (result.success) "Completed: " else "Failed: ") + result.output,
                    toolCallId = call.id, toolUid = control.uid)
                transcript += toolMessage
                onToolResult(toolMessage)
            }
            onToolRoundComplete()
            if (round == MAX_TOOL_ROUNDS - 1) {
                onToolStatus("Stopped after the maximum of " + MAX_TOOL_ROUNDS + " consecutive tool rounds.")
            }
        }
        return finalText.toString()
    }

    companion object { const val MAX_TOOL_ROUNDS = 8 }
}
