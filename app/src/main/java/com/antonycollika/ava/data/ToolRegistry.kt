package com.antonycollika.ava.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ToolApproval { AUTO, ASK, DISABLED }

data class ToolDefinition(
    val uid: String,
    val defaultName: String,
    val defaultDescription: String,
    val writeOperation: Boolean = false,
    val deleteOperation: Boolean = false,
    val parameters: JSONObject
)

data class ToolControl(
    val uid: String,
    val enabled: Boolean,
    val approval: ToolApproval,
    val modelName: String,
    val modelDescription: String
)

object AvaTools {
    private fun schema(vararg fields: Pair<String, String>): JSONObject {
        val properties = JSONObject()
        fields.forEach { (name, type) -> properties.put(name, JSONObject().put("type", type)) }
        return JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(fields.map { it.first }))
    }

    val definitions = listOf(
        ToolDefinition("ava.fs.list", "list_files", "List files and folders in a workspace directory.", parameters = schema("path" to "string")),
        ToolDefinition("ava.fs.tree", "directory_tree", "Show a recursive directory tree.", parameters = schema("path" to "string")),
        ToolDefinition("ava.fs.info", "file_info", "Get file or folder metadata.", parameters = schema("path" to "string")),
        ToolDefinition("ava.fs.read", "read_file", "Read a text file or a numbered line range.", parameters = schema("path" to "string")),
        ToolDefinition("ava.fs.read_many", "read_many_files", "Read multiple text files in one operation.", parameters = schema("paths" to "array")),
        ToolDefinition("ava.fs.search_name", "search_files", "Find workspace files by name.", parameters = schema("query" to "string")),
        ToolDefinition("ava.fs.search_text", "search_file_contents", "Search text across workspace files.", parameters = schema("query" to "string")),
        ToolDefinition("ava.fs.mkdir", "create_directory", "Create a directory.", true, parameters = schema("path" to "string")),
        ToolDefinition("ava.fs.write", "write_file", "Create, overwrite, append, or edit lines in a text file.", true, parameters = schema("path" to "string", "content" to "string", "mode" to "string")),
        ToolDefinition("ava.fs.replace", "replace_text", "Replace an exact text fragment in a file.", true, parameters = schema("path" to "string", "old_text" to "string", "new_text" to "string")),
        ToolDefinition("ava.fs.copy", "copy_path", "Copy a file or directory within the workspace.", true, parameters = schema("source" to "string", "destination" to "string")),
        ToolDefinition("ava.fs.move", "move_path", "Move a file or directory within the workspace.", true, parameters = schema("source" to "string", "destination" to "string")),
        ToolDefinition("ava.fs.rename", "rename_path", "Rename a file or directory.", true, parameters = schema("path" to "string", "new_name" to "string")),
        ToolDefinition("ava.fs.delete", "delete_path", "Delete a file or directory after explicit confirmation.", true, true, schema("path" to "string")),
        ToolDefinition("ava.fs.zip", "zip_path", "Create a ZIP archive from a workspace path.", true, parameters = schema("source" to "string", "destination" to "string")),
        ToolDefinition("ava.fs.unzip", "unzip_archive", "Extract a ZIP archive into a workspace folder.", true, parameters = schema("archive" to "string", "destination" to "string")),
        ToolDefinition("ava.memory.add", "save_note", "Save a durable local note for future conversations.", true, parameters = schema("title" to "string", "content" to "string")),
        ToolDefinition("ava.memory.list", "list_notes", "List saved local notes.", parameters = schema()),
        ToolDefinition("ava.memory.search", "search_notes", "Search saved local notes.", parameters = schema("query" to "string")),
        ToolDefinition("ava.todo.add", "add_todo", "Add an item to the local task list.", true, parameters = schema("title" to "string")),
        ToolDefinition("ava.todo.list", "list_todos", "List local tasks and their status.", parameters = schema()),
        ToolDefinition("ava.todo.complete", "complete_todo", "Mark a task as completed.", true, parameters = schema("id" to "string")),
        ToolDefinition("ava.todo.delete", "delete_todo", "Delete a task after explicit confirmation.", true, true, schema("id" to "string")),
        ToolDefinition("ava.calc.evaluate", "calculate", "Evaluate a mathematical expression.", parameters = schema("expression" to "string")),
        ToolDefinition("ava.time.now", "current_date_time", "Get current local date, time and time zone.", parameters = schema()),
        ToolDefinition("ava.date.diff", "date_difference", "Calculate the number of days between two ISO dates.", parameters = schema("start" to "string", "end" to "string")),
        ToolDefinition("ava.web.read", "read_webpage", "Read and extract readable text from an HTTPS webpage.", parameters = schema("url" to "string")),
        ToolDefinition("ava.web.search", "search_web", "Search the web using the configured Brave Search API key.", parameters = schema("query" to "string")),
        ToolDefinition("ava.clipboard.write", "copy_to_clipboard", "Write text to the device clipboard. Clipboard reading is not available.", true, parameters = schema("text" to "string"))
    )
}

class ToolRegistryStore(context: Context) {
    private val prefs = context.getSharedPreferences("ava_tool_registry", Context.MODE_PRIVATE)
    private val defaults = AvaTools.definitions.associateBy { it.uid }

    fun all(): List<ToolControl> = AvaTools.definitions.map { definition ->
        val raw = prefs.getString(definition.uid, null)
        if (raw == null) ToolControl(
            uid = definition.uid,
            enabled = true,
            approval = if (definition.writeOperation) ToolApproval.ASK else ToolApproval.AUTO,
            modelName = definition.defaultName,
            modelDescription = definition.defaultDescription
        ) else runCatching {
            val json = JSONObject(raw)
            ToolControl(definition.uid, json.optBoolean("enabled", true),
                runCatching { ToolApproval.valueOf(json.optString("approval")) }.getOrDefault(ToolApproval.ASK),
                json.optString("modelName", definition.defaultName),
                json.optString("modelDescription", definition.defaultDescription))
        }.getOrDefault(ToolControl(definition.uid, true, ToolApproval.ASK, definition.defaultName, definition.defaultDescription))
    }

    fun update(control: ToolControl): String? {
        val definition = defaults[control.uid] ?: return "Unknown tool UID."
        if (!NAME_PATTERN.matches(control.modelName)) return "Tool name must use letters, numbers, underscore or hyphen (1–64 characters)."
        if (all().any { it.uid != control.uid && it.modelName.equals(control.modelName, true) }) return "Another tool already uses this model name."
        if (control.modelDescription.isBlank()) return "Model description cannot be empty."
        prefs.edit().putString(control.uid, JSONObject().put("enabled", control.enabled)
            .put("approval", control.approval.name).put("modelName", control.modelName)
            .put("modelDescription", control.modelDescription).toString()).apply()
        return null
    }

    fun restore(uid: String) {
        val definition = defaults[uid] ?: return
        prefs.edit().remove(uid).apply()
    }

    fun findByModelName(name: String): ToolControl? = all().firstOrNull {
        it.enabled && it.approval != ToolApproval.DISABLED && it.modelName == name
    }

    fun modelTools(allowedUids: Set<String>? = null): JSONArray = JSONArray().apply {
        val controls = all().filter { it.enabled && it.approval != ToolApproval.DISABLED && (allowedUids == null || it.uid in allowedUids) }
        controls.forEach { control ->
            val definition = defaults[control.uid] ?: return@forEach
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", control.modelName).put("description", control.modelDescription)
                .put("parameters", definition.parameters)))
        }
    }

    fun control(uid: String): ToolControl? = all().firstOrNull { it.uid == uid }

    companion object { private val NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$") }
}

data class ToolExecution(
    val uid: String,
    val arguments: JSONObject,
    val requiresConfirmation: Boolean,
    val confirmationSummary: String
)

data class ToolResult(val uid: String, val success: Boolean, val output: String)

class WorkspaceToolRuntime(
    private val workspace: WorkspaceStore,
    private val registry: ToolRegistryStore
) {
    fun prepare(modelToolName: String, arguments: JSONObject): ToolExecution {
        val control = registry.findByModelName(modelToolName) ?: error("Tool is disabled or unknown.")
        val definition = AvaTools.definitions.first { it.uid == control.uid }
        val path = arguments.optString("path")
        val summary = when (control.uid) {
            "ava.memory.add" -> "Save note: " + arguments.optString("title")
            "ava.todo.add" -> "Add task: " + arguments.optString("title")
            "ava.todo.complete" -> "Complete task " + arguments.optString("id")
            "ava.todo.delete" -> "Delete task " + arguments.optString("id")
            "ava.clipboard.write" -> "Copy text to device clipboard"
            "ava.fs.delete" -> "Delete " + path
            "ava.fs.write" -> "Write file " + path + " (" + arguments.optString("mode", "CREATE") + ")"
            "ava.fs.replace" -> "Replace text in " + path
            "ava.fs.mkdir" -> "Create folder " + path
            "ava.fs.move" -> "Move " + arguments.optString("source") + " to " + arguments.optString("destination")
            "ava.fs.copy" -> "Copy " + arguments.optString("source") + " to " + arguments.optString("destination")
            "ava.fs.rename" -> "Rename " + path + " to " + arguments.optString("new_name")
            "ava.fs.zip" -> "Create ZIP " + arguments.optString("destination")
            "ava.fs.unzip" -> "Extract " + arguments.optString("archive") + " into " + arguments.optString("destination")
            else -> "Run " + control.modelName
        }
        val mustAsk = definition.deleteOperation || control.approval == ToolApproval.ASK
        return ToolExecution(control.uid, arguments, mustAsk, summary)
    }

    fun execute(execution: ToolExecution, confirmed: Boolean = false): ToolResult {
        val definition = AvaTools.definitions.firstOrNull { it.uid == execution.uid }
            ?: return ToolResult(execution.uid, false, "Unknown tool UID.")
        if (execution.requiresConfirmation && !confirmed) return ToolResult(execution.uid, false, "Waiting for user confirmation.")
        return runCatching {
            val a = execution.arguments
            val result = when (definition.uid) {
                "ava.fs.list" -> workspace.list(a.optString("path"), false).joinToString("\n") { if (it.isDirectory) "[DIR] " + it.path else "[FILE] " + it.path }
                "ava.fs.tree" -> workspace.tree(a.optString("path"))
                "ava.fs.info" -> workspace.info(a.getString("path")).toString()
                "ava.fs.read" -> workspace.readText(a.getString("path"), a.takeIf { it.has("start_line") }?.getInt("start_line"),
                    a.takeIf { it.has("end_line") }?.getInt("end_line"))
                "ava.fs.read_many" -> workspace.readMany(a.getJSONArray("paths").let { array -> (0 until array.length()).map { array.getString(it) } }).toString()
                "ava.fs.search_name" -> workspace.searchNames(a.getString("query")).joinToString("\n") { it.path }
                "ava.fs.search_text" -> workspace.searchContents(a.getString("query")).joinToString("\n") { it.first + ":" + it.second }
                "ava.fs.mkdir" -> workspace.mkdir(a.getString("path"))
                "ava.fs.write" -> workspace.writeText(a.getString("path"), a.optString("content"),
                    runCatching { TextWriteMode.valueOf(a.optString("mode", "CREATE").uppercase()) }.getOrDefault(TextWriteMode.CREATE),
                    a.takeIf { it.has("line") }?.getInt("line"), a.takeIf { it.has("end_line") }?.getInt("end_line"))
                "ava.fs.replace" -> "Replaced " + workspace.replace(a.getString("path"), a.getString("old_text"), a.getString("new_text")) + " occurrence(s)."
                "ava.fs.copy" -> workspace.copy(a.getString("source"), a.getString("destination"))
                "ava.fs.move" -> workspace.move(a.getString("source"), a.getString("destination"))
                "ava.fs.rename" -> workspace.rename(a.getString("path"), a.getString("new_name"))
                "ava.fs.delete" -> workspace.delete(a.getString("path"), confirmed)
                "ava.fs.zip" -> workspace.zip(a.getString("source"), a.getString("destination"))
                "ava.fs.unzip" -> workspace.unzip(a.getString("archive"), a.getString("destination"))
                else -> DailyToolRuntime.execute(execution.uid, a, confirmed)
            }
            ToolResult(execution.uid, true, result)
        }.getOrElse { ToolResult(execution.uid, false, it.message ?: "Tool failed.") }
    }
}
