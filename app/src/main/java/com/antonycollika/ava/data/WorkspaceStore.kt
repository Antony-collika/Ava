package com.antonycollika.ava.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorkspaceEntry(val path: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long, val modifiedAt: Long)
enum class TextWriteMode { CREATE, OVERWRITE, APPEND, INSERT_LINES, DELETE_LINES }

/** SAF-backed workspace. Every path is validated and resolved from the persisted tree root. */
class WorkspaceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ava_workspace", Context.MODE_PRIVATE)
    fun selectedUri(): Uri? = prefs.getString("tree_uri", null)?.let(Uri::parse)
    fun hasAccess(): Boolean {
        val uri = selectedUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }
    fun selectWorkspace(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("tree_uri", uri.toString()).apply()
    }
    fun clearWorkspace() { prefs.edit().remove("tree_uri").apply() }
    fun root(): DocumentFile {
        val uri = selectedUri() ?: error("Choose a workspace folder first.")
        if (!hasAccess()) throw SecurityException("Workspace permission is unavailable. Select the folder again.")
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory }
            ?: error("The selected workspace folder is unavailable.")
    }
    private fun parts(path: String): List<String> {
        require(!path.startsWith("/") && !path.contains('\\') && !path.contains('\u0000')) { "Use a relative workspace path." }
        val result = path.split('/').filter { it.isNotBlank() && it != "." }
        require(result.none { it == ".." }) { "Paths cannot leave the workspace." }
        return result
    }
    private fun resolve(path: String): DocumentFile? {
        var node = root()
        parts(path).forEachIndexed { index, segment ->
            node = node.findFile(segment) ?: return null
            if (index < parts(path).lastIndex) require(node.isDirectory) { "A path segment is not a folder." }
        }
        return node
    }
    private fun parent(path: String): Pair<DocumentFile, String> {
        val p = parts(path); require(p.isNotEmpty())
        val folder = p.dropLast(1).joinToString("/")
        val target = if (folder.isBlank()) root() else resolve(folder) ?: error("Parent folder not found.")
        require(target.isDirectory)
        return target to p.last()
    }
    fun list(path: String = "", recursive: Boolean = false, max: Int = 500): List<WorkspaceEntry> {
        val folder = if (path.isBlank()) root() else resolve(path) ?: error("Folder not found.")
        require(folder.isDirectory)
        val result = mutableListOf<WorkspaceEntry>()
        fun walk(dir: DocumentFile, prefix: String) {
            dir.listFiles().sortedBy { it.name.orEmpty().lowercase() }.forEach { child ->
                if (result.size >= max) return
                val name = child.name ?: return@forEach
                val childPath = if (prefix.isBlank()) name else prefix + "/" + name
                result += WorkspaceEntry(childPath, name, child.isDirectory, child.length(), child.lastModified())
                if (recursive && child.isDirectory) walk(child, childPath)
            }
        }
        walk(folder, path.trim('/')); return result
    }
    fun tree(path: String = "", max: Int = 500): String = list(path, true, max).joinToString("\n") {
        val depth = it.path.removePrefix(path.trim('/').let { p -> if (p.isBlank()) "" else p + "/" }).count { c -> c == '/' }
        "  ".repeat(depth) + (if (it.isDirectory) "[DIR] " else "[FILE] ") + it.name
    }
    fun info(path: String): WorkspaceEntry {
        val f = resolve(path) ?: error("File not found.")
        return WorkspaceEntry(path, f.name.orEmpty(), f.isDirectory, f.length(), f.lastModified())
    }
    fun mkdir(path: String): String {
        val (p, name) = parent(path); require(p.findFile(name) == null)
        requireNotNull(p.createDirectory(name)); return "Created folder " + path
    }
    fun readText(path: String, startLine: Int? = null, endLine: Int? = null): String {
        val f = resolve(path) ?: error("File not found.")
        require(f.isFile && f.length() <= MAX_TEXT_BYTES) { "File is not readable text or exceeds 4 MB." }
        val value = context.contentResolver.openInputStream(f.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Unable to read file.")
        if (startLine == null && endLine == null) return value
        val lines = value.lines(); val start = ((startLine ?: 1) - 1).coerceAtLeast(0)
        val end = (endLine ?: lines.size).coerceAtMost(lines.size)
        require(start < end && start < lines.size)
        return (start until end).joinToString("\n") { "%5d | %s".format(it + 1, lines[it]) }
    }
    fun readMany(paths: List<String>): Map<String, String> = paths.take(MAX_BATCH).associateWith { readText(it) }
    fun writeText(path: String, text: String, mode: TextWriteMode = TextWriteMode.CREATE, line: Int? = null, endLine: Int? = null): String {
        val old = resolve(path)
        if (mode == TextWriteMode.CREATE) require(old == null) { "File already exists; choose overwrite." }
        else require(old?.isFile == true) { "File not found." }
        val (p, name) = parent(path)
        val f = old ?: requireNotNull(p.createFile(mime(name), name))
        val output = when (mode) {
            TextWriteMode.CREATE, TextWriteMode.OVERWRITE -> text
            TextWriteMode.APPEND -> readText(path) + text
            TextWriteMode.INSERT_LINES, TextWriteMode.DELETE_LINES -> {
                val lines = readText(path).lines().toMutableList()
                val start = (line ?: error("Line number required.")) - 1
                require(start in 0..lines.size)
                if (mode == TextWriteMode.INSERT_LINES) lines.addAll(start, text.lines())
                else {
                    val finish = (endLine ?: line ?: error("End line required.")) - 1
                    require(finish in start until lines.size)
                    repeat(finish - start + 1) { lines.removeAt(start) }
                }
                lines.joinToString("\n")
            }
        }
        context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(output.toByteArray(Charsets.UTF_8)) }
            ?: error("Unable to write file.")
        return "Updated " + path
    }
    fun replace(path: String, old: String, new: String): Int {
        require(old.isNotEmpty()); val value = readText(path); val count = value.windowed(old.length).count { it == old }
        require(count > 0) { "Exact text not found." }; writeText(path, value.replace(old, new), TextWriteMode.OVERWRITE); return count
    }
    fun searchNames(query: String, max: Int = 100): List<WorkspaceEntry> = list(recursive = true, max = 5000)
        .filter { it.name.contains(query, true) }.take(max)
    fun searchContents(query: String, max: Int = 100): List<Pair<String, Int>> {
        require(query.isNotBlank()); val hits = mutableListOf<Pair<String, Int>>()
        list(recursive = true, max = 5000).filter { !it.isDirectory && it.sizeBytes <= MAX_TEXT_BYTES }.forEach { entry ->
            if (hits.size < max) runCatching { readText(entry.path).lines() }.getOrNull()?.forEachIndexed { index, line ->
                if (line.contains(query, true) && hits.size < max) hits += entry.path to (index + 1)
            }
        }
        return hits
    }
    fun copy(source: String, destination: String): String {
        require(source.isNotBlank()) { "Cannot copy the workspace root." }
        val sourceParts = parts(source); val destinationParts = parts(destination)
        require(destinationParts.size <= sourceParts.size || destinationParts.take(sourceParts.size) != sourceParts) {
            "Cannot copy a folder into itself or one of its descendants."
        }
        val from = resolve(source) ?: error("Source not found.")
        val (p, name) = parent(destination); require(p.findFile(name) == null)
        copyNode(from, p, name); return "Copied " + source + " to " + destination
    }
    fun move(source: String, destination: String): String { copy(source, destination); deleteNode(source); return "Moved " + source + " to " + destination }
    fun rename(path: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", ".."))
        val f = resolve(path) ?: error("File not found.")
        require(f.parentFile?.findFile(name) == null); check(f.renameTo(name)); return "Renamed " + path
    }
    fun delete(path: String, confirmed: Boolean): String {
        require(confirmed) { "Deletion requires explicit confirmation." }; require(path.isNotBlank()) { "Cannot delete workspace root." }
        deleteNode(path); return "Deleted " + path
    }
    private fun deleteNode(path: String) { val f = resolve(path) ?: error("File not found."); check(f.delete()) }
    private fun copyNode(source: DocumentFile, target: DocumentFile, name: String) {
        if (source.isDirectory) {
            val folder = requireNotNull(target.createDirectory(name))
            source.listFiles().forEach { copyNode(it, folder, it.name ?: "item") }
        } else {
            val out = requireNotNull(target.createFile(source.type ?: mime(name), name))
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(out.uri, "wt")?.use { input.copyTo(it) } ?: error("Write failed.")
            } ?: error("Read failed.")
        }
    }
    fun zip(source: String, destination: String): String {
        require(source.isNotBlank()) { "Cannot archive the workspace root." }
        val sourceParts = parts(source); val destinationParts = parts(destination)
        require(destinationParts.size <= sourceParts.size || destinationParts.take(sourceParts.size) != sourceParts) {
            "Cannot create an archive inside the folder being archived."
        }
        val from = resolve(source) ?: error("Source not found."); val (p, name) = parent(destination)
        require(p.findFile(name) == null); val out = requireNotNull(p.createFile("application/zip", name))
        context.contentResolver.openOutputStream(out.uri, "wt")?.use { raw -> ZipOutputStream(raw).use { zip ->
            fun add(node: DocumentFile, entry: String) {
                if (node.isDirectory) node.listFiles().forEach { add(it, entry + (it.name ?: "item") + "/") }
                else { zip.putNextEntry(ZipEntry(entry)); context.contentResolver.openInputStream(node.uri)?.use { it.copyTo(zip) }; zip.closeEntry() }
            }
            add(from, from.name ?: "workspace")
        } } ?: error("Unable to create archive.")
        return "Created " + destination
    }
    fun unzip(archivePath: String, destination: String): String {
        val archive = resolve(archivePath) ?: error("Archive not found.")
        require(archive.isFile && archive.length() <= MAX_ARCHIVE_BYTES)
        val folder = resolve(destination) ?: error("Destination folder not found."); require(folder.isDirectory)
        context.contentResolver.openInputStream(archive.uri)?.use { raw -> ZipInputStream(raw).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val segments = parts(entry.name); require(segments.isNotEmpty() && !entry.name.startsWith("/"))
                var parent = folder
                segments.dropLast(1).forEach { part ->
                    parent = parent.findFile(part) ?: requireNotNull(parent.createDirectory(part))
                    require(parent.isDirectory)
                }
                val name = segments.last()
                if (entry.isDirectory) { if (parent.findFile(name) == null) requireNotNull(parent.createDirectory(name)) }
                else {
                    require(parent.findFile(name) == null) { "Archive entry already exists: " + entry.name }
                    val out = requireNotNull(parent.createFile(mime(name), name))
                    context.contentResolver.openOutputStream(out.uri, "wt")?.use { zip.copyTo(it) } ?: error("Extraction failed.")
                }
                zip.closeEntry()
            }
        } } ?: error("Unable to read archive.")
        return "Extracted " + archivePath
    }
    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "log", "yaml", "yml" -> "text/plain"
        "json" -> "application/json"; "csv" -> "text/csv"; "xml" -> "application/xml"
        "html", "htm" -> "text/html"; "zip" -> "application/zip"; else -> "application/octet-stream"
    }
    companion object { const val MAX_TEXT_BYTES = 4L * 1024 * 1024; const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024; const val MAX_BATCH = 20 }
}
