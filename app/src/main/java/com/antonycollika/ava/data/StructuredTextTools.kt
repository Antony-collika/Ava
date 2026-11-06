package com.antonycollika.ava.data

import org.json.JSONArray
import org.json.JSONObject

object StructuredTextTools {
    private fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { row += cell.toString(); cell.setLength(0) }
                (c == '\n' || c == '\r') && !quoted -> {
                    row += cell.toString(); cell.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }
    private fun encodeCsv(rows: List<List<String>>): String = rows.joinToString("\n") { row ->
        row.joinToString(",") { value ->
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"" + value.replace("\"", "\"\"") + "\"" else value
        }
    }
    fun csvRead(store: WorkspaceStore, path: String): String {
        val rows = csv(store.readText(path))
        if (rows.isEmpty()) return "(empty CSV)"
        val width = rows.maxOf { it.size }
        val header = rows.first() + List(width - rows.first().size) { "" }
        val separator = List(width) { "---" }
        return (listOf(header, separator) + rows.drop(1).map { it + List(width - it.size) { "" } })
            .joinToString("\n") { "| " + it.joinToString(" | ") + " |" }
    }
    fun csvFilter(store: WorkspaceStore, path: String, column: String, value: String): String {
        val rows = csv(store.readText(path)); require(rows.isNotEmpty())
        val index = rows.first().indexOf(column); require(index >= 0) { "CSV column not found." }
        val filtered = listOf(rows.first()) + rows.drop(1).filter { it.getOrNull(index).orEmpty().contains(value, true) }
        return encodeCsv(filtered)
    }
    fun csvSort(store: WorkspaceStore, path: String, column: String, descending: Boolean): String {
        val rows = csv(store.readText(path)); require(rows.isNotEmpty())
        val index = rows.first().indexOf(column); require(index >= 0)
        val sorted = rows.drop(1).sortedWith(compareBy { it.getOrNull(index).orEmpty().toDoubleOrNull() ?: Double.NaN })
            .let { if (descending) it.reversed() else it }
        store.writeText(path, encodeCsv(listOf(rows.first()) + sorted), TextWriteMode.OVERWRITE)
        return "Sorted CSV by " + column
    }
    fun csvStats(store: WorkspaceStore, path: String, column: String): String {
        val rows = csv(store.readText(path)); require(rows.isNotEmpty())
        val index = rows.first().indexOf(column); require(index >= 0)
        val values = rows.drop(1).mapNotNull { it.getOrNull(index) }
        val numeric = values.mapNotNull { it.toDoubleOrNull() }
        return "rows=" + values.size + ", unique=" + values.distinct().size +
            if (numeric.isEmpty()) "" else ", sum=" + numeric.sum() + ", average=" + numeric.average() +
                ", min=" + numeric.minOrNull() + ", max=" + numeric.maxOrNull()
    }
    fun csvAddRow(store: WorkspaceStore, path: String, values: JSONArray): String {
        val rows = csv(store.readText(path)); require(rows.isNotEmpty())
        val row = (0 until values.length()).map { values.optString(it) }
        require(row.size == rows.first().size) { "Row must have the same number of cells as the header." }
        store.writeText(path, encodeCsv(rows + listOf(row)), TextWriteMode.OVERWRITE); return "Added CSV row."
    }
    fun csvEditCell(store: WorkspaceStore, path: String, row: Int, column: String, value: String): String {
        val rows = csv(store.readText(path)).map { it.toMutableList() }.toMutableList()
        val col = rows.firstOrNull()?.indexOf(column) ?: -1
        require(col >= 0 && row in 1 until rows.size) { "CSV row or column not found (row is 1-based, excluding header)." }
        while (rows[row].size <= col) rows[row] += ""
        rows[row][col] = value
        store.writeText(path, encodeCsv(rows), TextWriteMode.OVERWRITE); return "Updated CSV cell."
    }
    fun jsonFormat(store: WorkspaceStore, path: String): String {
        val value = JSONObjectOrArray(store.readText(path))
        val formatted = when (value) { is JSONObject -> value.toString(2); is JSONArray -> value.toString(2); else -> value.toString() }
        store.writeText(path, formatted, TextWriteMode.OVERWRITE); return "Formatted JSON file."
    }
    fun jsonValidate(store: WorkspaceStore, path: String): String {
        JSONObjectOrArray(store.readText(path)); return "JSON is valid."
    }
    fun jsonGet(store: WorkspaceStore, path: String, pointer: String): String {
        var value: Any = JSONObjectOrArray(store.readText(path))
        pointer.split('.').filter { it.isNotBlank() }.forEach { segment ->
            value = when (value) {
                is JSONObject -> value.opt(segment).takeUnless { it == null || it == JSONObject.NULL } ?: error("JSON path not found.")
                is JSONArray -> value.opt(segment.toInt()).takeUnless { it == null || it == JSONObject.NULL } ?: error("JSON array index not found.")
                else -> error("JSON path does not point to a container.")
            }
        }
        return when (value) { is JSONObject -> value.toString(2); is JSONArray -> value.toString(2); else -> value.toString() }
    }
    fun jsonSet(store: WorkspaceStore, path: String, pointer: String, rawValue: String): String {
        val root = JSONObjectOrArray(store.readText(path)); val keys = pointer.split('.').filter { it.isNotBlank() }
        require(keys.isNotEmpty() && root is JSONObject) { "JSON update path must start at an object property." }
        var current = root
        keys.dropLast(1).forEach { key ->
            val next = current.optJSONObject(key) ?: JSONObject().also { current.put(key, it) }
            current = next
        }
        val value: Any = runCatching { JSONObject(rawValue) }.getOrElse {
            runCatching { JSONArray(rawValue) }.getOrElse {
                if (rawValue == "null") JSONObject.NULL else rawValue
            }
        }
        current.put(keys.last(), value)
        store.writeText(path, root.toString(2), TextWriteMode.OVERWRITE); return "Updated JSON path " + pointer
    }
    fun csvToJson(store: WorkspaceStore, path: String, destination: String): String {
        val rows = csv(store.readText(path)); require(rows.isNotEmpty())
        val headers = rows.first()
        val output = JSONArray()
        rows.drop(1).forEach { row ->
            output.put(JSONObject().apply { headers.forEachIndexed { index, key -> put(key, row.getOrNull(index).orEmpty()) } })
        }
        store.writeText(destination, output.toString(2), TextWriteMode.CREATE); return "Converted CSV to JSON."
    }
    fun jsonToCsv(store: WorkspaceStore, path: String, destination: String): String {
        val array = JSONArray(store.readText(path))
        val headers = linkedSetOf<String>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.keys()?.forEach { headers += it }
        require(headers.isNotEmpty())
        val rows = mutableListOf(headers.toList())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            rows += headers.map { item.opt(it)?.toString().orEmpty() }
        }
        store.writeText(destination, encodeCsv(rows), TextWriteMode.CREATE); return "Converted JSON to CSV."
    }
    fun markdownToHtml(store: WorkspaceStore, path: String, destination: String): String {
        val html = buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\"></head><body>\n")
            store.readText(path).lines().forEach { line ->
                val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
                if (heading != null) {
                    val level = heading.groupValues[1].length
                    append("<h").append(level).append(">").append(escape(heading.groupValues[2])).append("</h").append(level).append(">\n")
                } else if (line.isBlank()) append("\n")
                else append("<p>").append(escape(line)).append("</p>\n")
            }
            append("</body></html>")
        }
        store.writeText(destination, html, TextWriteMode.CREATE); return "Converted Markdown to HTML."
    }
    fun count(text: String): String = "characters=" + text.length + ", lines=" + text.lines().size +
        ", words=" + Regex("\\S+").findAll(text).count()
    fun compare(store: WorkspaceStore, first: String, second: String): String {
        val a = store.readText(first).lines(); val b = store.readText(second).lines()
        val max = maxOf(a.size, b.size)
        return (0 until max).mapNotNull { index ->
            val left = a.getOrNull(index); val right = b.getOrNull(index)
            if (left == right) null else "line " + (index + 1) + "\n- " + (left ?: "(missing)") + "\n+ " + (right ?: "(missing)")
        }.take(200).joinToString("\n")
    }
    private fun JSONObjectOrArray(raw: String): Any =
        runCatching { JSONObject(raw) }.getOrElse { JSONArray(raw) }
    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
