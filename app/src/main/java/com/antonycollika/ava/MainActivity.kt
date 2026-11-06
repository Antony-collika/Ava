package com.antonycollika.ava

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("ava_preferences", MODE_PRIVATE)
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFBBA7FF),
                    background = Color(0xFF101014),
                    surface = Color(0xFF1B1B22),
                    onSurface = Color(0xFFF1EFF7)
                )
            ) {
                var showSettings by remember { mutableStateOf(false) }
                var provider by remember { mutableStateOf(prefs.getString("provider", "OpenAI") ?: "OpenAI") }
                var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini") }
                var baseUrl by remember { mutableStateOf(prefs.getString("base_url", "https://api.openai.com") ?: "https://api.openai.com") }
                var apiKey by remember { mutableStateOf("") }
                var draft by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                val messages = remember { mutableStateListOf<Pair<String, String>>() }
                val scope = rememberCoroutineScope()

                if (showSettings) {
                    SettingsScreen(
                        provider = provider,
                        model = model,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        hasSavedKey = prefs.contains("api_key"),
                        onProviderChange = { provider = it },
                        onModelChange = { model = it },
                        onBaseUrlChange = { baseUrl = it },
                        onApiKeyChange = { apiKey = it },
                        onBack = { showSettings = false },
                        onSave = {
                            prefs.edit().putString("provider", provider)
                                .putString("model", model)
                                .putString("base_url", baseUrl.trimEnd('/'))
                                .apply()
                            if (apiKey.isNotBlank()) {
                                prefs.edit().putString("api_key", apiKey).apply()
                                apiKey = ""
                            }
                            showSettings = false
                        }
                    )
                } else {
                    Scaffold(
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Ava")
                                        Text(model, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { showSettings = true }) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            Surface(tonalElevation = 3.dp) {
                                Row(
                                    Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    OutlinedTextField(
                                        value = draft,
                                        onValueChange = { draft = it },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("Message Ava…") },
                                        maxLines = 5,
                                        shape = RoundedCornerShape(24.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilledIconButton(
                                        onClick = {
                                            val prompt = draft.trim()
                                            val secret = prefs.getString("api_key", null)
                                            if (prompt.isNotEmpty() && !sending) {
                                                if (secret.isNullOrBlank()) {
                                                    errorMessage = "Add your API key in Settings before sending."
                                                } else {
                                                    draft = ""
                                                    errorMessage = null
                                                    messages.add("user" to prompt)
                                                    sending = true
                                                    scope.launch {
                                                        val answer = StringBuilder()
                                                        try {
                                                            val history = JSONArray()
                                                            messages.dropLast(1).takeLast(24).forEach { (role, content) ->
                                                                history.put(JSONObject().put("role", role).put("content", content))
                                                            }
                                                            history.put(JSONObject().put("role", "user").put("content", prompt))
                                                            val payload = JSONObject()
                                                                .put("model", model)
                                                                .put("stream", true)
                                                                .put("messages", history)
                                                                .toString()
                                                            val request = Request.Builder()
                                                                .url(baseUrl.trimEnd('/') + "/v1/chat/completions")
                                                                .header("Authorization", "Bearer $secret")
                                                                .header("Accept", "text/event-stream")
                                                                .post(payload.toRequestBody("application/json".toMediaType()))
                                                                .build()
                                                            withContext(Dispatchers.IO) {
                                                                client.newCall(request).execute().use { response ->
                                                                    if (!response.isSuccessful) {
                                                                        val detail = response.body?.string().orEmpty().take(300)
                                                                        throw IllegalStateException("Provider error ${response.code}: $detail")
                                                                    }
                                                                    val source = response.body?.source()
                                                                        ?: throw IllegalStateException("Empty response")
                                                                    while (!source.exhausted()) {
                                                                        val line = source.readUtf8Line() ?: break
                                                                        if (!line.startsWith("data:")) continue
                                                                        val data = line.removePrefix("data:").trim()
                                                                        if (data == "[DONE]") break
                                                                        val chunk = runCatching {
                                                                            JSONObject(data).optJSONArray("choices")
                                                                                ?.optJSONObject(0)?.optJSONObject("delta")
                                                                                ?.optString("content")
                                                                        }.getOrNull()
                                                                        if (!chunk.isNullOrEmpty() && chunk != "null") {
                                                                            withContext(Dispatchers.Main) {
                                                                                answer.append(chunk)
                                                                                if (messages.lastOrNull()?.first == "assistant") {
                                                                                    messages[messages.lastIndex] = "assistant" to answer.toString()
                                                                                } else {
                                                                                    messages.add("assistant" to answer.toString())
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } catch (failure: Throwable) {
                                                            errorMessage = failure.message ?: "Request failed"
                                                        } finally {
                                                            sending = false
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        enabled = draft.isNotBlank() && !sending,
                                        modifier = Modifier.size(52.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                                    }
                                }
                            }
                        }
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            errorMessage?.let {
                                Text(it, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                            if (messages.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            Modifier.size(64.dp).background(Color(0xFF30294A), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("A", style = MaterialTheme.typography.headlineLarge,
                                                color = Color(0xFFD2C5FF))
                                        }
                                        Spacer(Modifier.height(20.dp))
                                        Text("What can I help with?", style = MaterialTheme.typography.headlineSmall)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Your AI. Your keys. Your conversations.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(18.dp))
                                        OutlinedButton(onClick = { showSettings = true }) {
                                            Text("Configure provider")
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                    contentPadding = PaddingValues(vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(messages) { (role, content) ->
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = if (role == "user") Arrangement.End else Arrangement.Start
                                        ) {
                                            Surface(
                                                color = if (role == "user") Color(0xFF30294A) else Color(0xFF202027),
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.widthIn(max = 320.dp)
                                            ) {
                                                Text(content, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                                            }
                                        }
                                    }
                                    if (sending) item { Text("Ava is responding…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    provider: String,
    model: String,
    baseUrl: String,
    apiKey: String,
    hasSavedKey: Boolean,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provider settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Connect your AI", style = MaterialTheme.typography.headlineSmall)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = provider, onValueChange = {}, readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("OpenAI", "Anthropic", "Google Gemini", "OpenAI-compatible").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { onProviderChange(option); expanded = false }
                        )
                    }
                }
            }
            OutlinedTextField(model, onModelChange, label = { Text("Model ID") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(baseUrl, onBaseUrlChange, label = { Text("API base URL") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(apiKey, onApiKeyChange,
                label = { Text(if (hasSavedKey) "API key saved — enter to replace" else "API key") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.weight(1f))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth(),
                enabled = model.isNotBlank() && baseUrl.startsWith("https://")) {
                Text("Save settings")
            }
            Text(
                "Ava sends requests directly to the provider. API-key encryption and additional provider protocols are part of the next implementation pass.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
