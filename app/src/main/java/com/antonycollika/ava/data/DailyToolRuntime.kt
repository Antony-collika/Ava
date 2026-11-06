package com.antonycollika.ava.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.antonycollika.ava.SecureKeyStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

class DailyToolRuntime(context: Context, private val secrets: SecureKeyStore) {
    private val prefs = context.getSharedPreferences("ava_daily_tools", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).build()
    private val appContext = context.applicationContext

    fun execute(uid: String, args: JSONObject, confirmed: Boolean): String = when (uid) {
        "ava.memory.add" -> addNote(args.getString("title"), args.getString("content"))
        "ava.memory.list" -> notes().joinToString("\n") { it.optString("title") + ": " + it.optString("content") }
        "ava.memory.search" -> notes().filter { (it.optString("title") + " " + it.optString("content")).contains(args.getString("query"), true) }
            .joinToString("\n") { it.optString("title") + ": " + it.optString("content") }
        "ava.todo.add" -> addTodo(args.getString("title"))
        "ava.todo.list" -> todos().joinToString("\n") {
            (if (it.optBoolean("done")) "[x] " else "[ ] ") + it.optString("id") + " " + it.optString("title")
        }
        "ava.todo.complete" -> completeTodo(args.getString("id"))
        "ava.todo.delete" -> { require(confirmed) { "Task deletion requires confirmation." }; deleteTodo(args.getString("id")) }
        "ava.calc.evaluate" -> Calculator(args.getString("expression")).evaluate().toString()
        "ava.time.now" -> ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
        "ava.date.diff" -> ChronoUnit.DAYS.between(LocalDate.parse(args.getString("start")), LocalDate.parse(args.getString("end"))).toString()
        "ava.web.read" -> readWeb(args.getString("url"))
        "ava.web.search" -> searchWeb(args.getString("query"))
        "ava.clipboard.write" -> {
            val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                manager.setPrimaryClip(ClipData.newPlainText("Ava", args.getString("text")))
            }
            "Clipboard write requested."
        }
        "ava.alarm.set" -> {
            val hour = args.getInt("hour"); val minute = args.getInt("minute")
            require(hour in 0..23 && minute in 0..59)
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("message"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent); "Opened the system alarm confirmation screen."
        }
        "ava.calendar.create" -> {
            val start = args.getLong("start_epoch_millis"); val end = args.getLong("end_epoch_millis")
            require(end > start)
            val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, args.getString("title"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.Events.DESCRIPTION, args.optString("description"))
                .putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent); "Opened the system calendar event editor."
        }
        else -> error("Unknown daily tool UID.")
    }

    private fun notes(): List<JSONObject> = decode("notes")
    private fun todos(): List<JSONObject> = decode("todos")
    private fun decode(key: String): List<JSONObject> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())
    private fun save(key: String, values: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }
    private fun addNote(title: String, content: String): String {
        require(title.isNotBlank() && content.isNotBlank())
        val values = notes() + JSONObject().put("id", UUID.randomUUID().toString())
            .put("title", title.take(120)).put("content", content.take(20_000)).put("updatedAt", System.currentTimeMillis())
        save("notes", values); return "Saved note: " + title
    }
    private fun addTodo(title: String): String {
        require(title.isNotBlank())
        val item = JSONObject().put("id", UUID.randomUUID().toString().take(8))
            .put("title", title.take(500)).put("done", false)
        save("todos", todos() + item); return "Added task " + item.getString("id") + ": " + title
    }
    private fun completeTodo(id: String): String {
        val values = todos().map { if (it.optString("id") == id) it.put("done", true) else it }
        require(values.any { it.optString("id") == id }) { "Task not found." }
        save("todos", values); return "Completed task " + id
    }
    private fun deleteTodo(id: String): String {
        val values = todos(); require(values.any { it.optString("id") == id }) { "Task not found." }
        save("todos", values.filterNot { it.optString("id") == id }); return "Deleted task " + id
    }

    private fun readWeb(rawUrl: String): String {
        val uri = android.net.Uri.parse(rawUrl)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && host.isNotBlank() && host != "localhost" && !host.endsWith(".local") &&
            !host.startsWith("127.") && !host.startsWith("10.") && !host.startsWith("192.168.") &&
            !Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)) { "Only public HTTPS webpages are supported." }
        val request = Request.Builder().url(rawUrl).header("User-Agent", "AvaAndroid/1.0").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Web request failed: HTTP " + response.code)
            val body = response.body ?: error("Webpage returned an empty response.")
            require(body.contentLength() <= MAX_WEB_BYTES || body.contentLength() == -1L) { "Webpage is too large." }
            val html = body.string().take(MAX_WEB_BYTES.toInt())
            return html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace(Regex("[ \\t\\x0B\\f]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
                .trim().take(12_000)
        }
    }

    private fun searchWeb(query: String): String {
        val key = secrets.read("WEB_SEARCH") ?: error("Add a Brave Search API key in Tool settings before using web search.")
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&count=5"
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("X-Subscription-Token", key).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Web search failed: HTTP " + response.code)
            val root = JSONObject(response.body?.string().orEmpty())
            val results = root.optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
            return (0 until results.length()).joinToString("\n\n") { index ->
                val item = results.getJSONObject(index)
                item.optString("title") + "\n" + item.optString("url") + "\n" + item.optString("description")
            }
        }
    }

    companion object { private const val MAX_WEB_BYTES = 2 * 1024 * 1024 }
}

/** Small arithmetic parser; it never evaluates arbitrary Kotlin or JavaScript. */
private class Calculator(private val input: String) {
    private var position = 0
    fun evaluate(): Double {
        val value = expression(); skip()
        require(position == input.length) { "Unexpected character in expression." }
        require(value.isFinite()) { "Result is not finite." }
        return value
    }
    private fun expression(): Double {
        var value = term()
        while (true) { skip(); value = when (peek()) {
            '+' -> { position++; value + term() }
            '-' -> { position++; value - term() }
            else -> return value
        } }
    }
    private fun term(): Double {
        var value = power()
        while (true) { skip(); value = when (peek()) {
            '*' -> { position++; value * power() }
            '/' -> { position++; value / power() }
            '%' -> { position++; value % power() }
            else -> return value
        } }
    }
    private fun power(): Double {
        var value = unary(); skip()
        if (peek() == '^') { position++; value = value.pow(power()) }
        return value
    }
    private fun unary(): Double {
        skip()
        if (peek() == '+') { position++; return unary() }
        if (peek() == '-') { position++; return -unary() }
        if (peek() == '(') { position++; val value = expression(); skip(); require(peek() == ')'); position++; return value }
        val start = position
        while (position < input.length && (input[position].isDigit() || input[position] == '.')) position++
        require(position > start) { "Expected a number." }
        return input.substring(start, position).toDouble()
    }
    private fun skip() { while (position < input.length && input[position].isWhitespace()) position++ }
    private fun peek(): Char? = input.getOrNull(position)
    private fun Double.pow(other: Double): Double = Math.pow(this, other)
}
