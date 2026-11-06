package com.antonycollika.ava.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.antonycollika.ava.SecureKeyStore
import com.antonycollika.ava.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun AvaApp(
    settingsStore: ProviderSettingsStore,
    conversationStore: ConversationStore,
    keyStore: SecureKeyStore,
    providerClient: ProviderClient
) {
    var settings by remember { mutableStateOf(settingsStore.load()) }
    val conversations = remember { mutableStateListOf<Conversation>().apply { addAll(conversationStore.loadAll()) } }
    var activeId by remember { mutableStateOf(conversations.firstOrNull()?.id ?: UUID.randomUUID().toString()) }
    val messages = remember { mutableStateListOf<ChatMessage>().apply {
        addAll(conversations.firstOrNull()?.messages.orEmpty())
    } }
    var showSettings by remember { mutableStateOf(!keyStore.hasKey(settings.provider.name)) }
    var showHistory by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun persist() {
        if (messages.isEmpty()) return
        val firstUser = messages.firstOrNull { it.role == "user" }?.content.orEmpty()
        val title = conversations.firstOrNull { it.id == activeId }?.title
            ?.takeIf { it != "New chat" }
            ?: firstUser.take(42).ifBlank { "New chat" }
        val conversation = Conversation(activeId, title, messages.toList(), System.currentTimeMillis())
        conversationStore.save(conversation)
        val index = conversations.indexOfFirst { it.id == activeId }
        if (index >= 0) conversations[index] = conversation else conversations.add(0, conversation)
        conversations.sortByDescending { it.updatedAt }
    }

    fun startNewChat() {
        generationJob?.cancel()
        sending = false
        messages.clear()
        activeId = UUID.randomUUID().toString()
        errorMessage = null
        showHistory = false
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFC5B5FF),
        onPrimary = Color(0xFF241A42),
        background = Color(0xFF101014),
        surface = Color(0xFF1B1B22),
        surfaceVariant = Color(0xFF292832),
        onSurface = Color(0xFFF2F0F7),
        onSurfaceVariant = Color(0xFFB6B1C2)
    )) {
        if (showSettings) {
            ProviderSettingsScreen(
                initial = settings,
                hasSavedKey = { selected -> keyStore.hasKey(selected.name) },
                onBack = { showSettings = false },
                onRemoveKey = { selected -> keyStore.clear(selected.name) },
                onSave = { updated, key ->
                    settingsStore.save(updated)
                    if (key.isNotBlank()) keyStore.save(key.trim(), updated.provider.name)
                    settings = updated
                    showSettings = false
                    errorMessage = null
                }
            )
        } else {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Ava", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${settings.provider.label} · ${settings.model}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { showHistory = true }) {
                                Icon(Icons.Default.History, contentDescription = "Conversation history")
                            }
                        },
                        actions = {
                            IconButton(onClick = ::startNewChat) {
                                Icon(Icons.Default.Add, contentDescription = "New chat")
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                        Row(
                            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message Ava…") },
                                maxLines = 6,
                                shape = RoundedCornerShape(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = {
                                    if (sending) {
                                        generationJob?.cancel()
                                        sending = false
                                    } else {
                                        val prompt = draft.trim()
                                        val key = keyStore.read(settings.provider.name)
                                        if (prompt.isNotEmpty()) {
                                            if (key.isNullOrBlank()) {
                                                errorMessage = "Add your API key in Settings to start chatting."
                                                showSettings = true
                                            } else {
                                                draft = ""
                                                errorMessage = null
                                                val requestHistory = messages.toList() + ChatMessage(
                                                    id = UUID.randomUUID().toString(),
                                                    role = "user",
                                                    content = prompt
                                                )
                                                messages.add(requestHistory.last())
                                                messages.add(ChatMessage(UUID.randomUUID().toString(), "assistant", ""))
                                                sending = true
                                                persist()
                                                generationJob = scope.launch {
                                                    val answer = StringBuilder()
                                                    try {
                                                        providerClient.stream(settings, key, requestHistory).collect { chunk ->
                                                            answer.append(chunk)
                                                            if (messages.isNotEmpty()) {
                                                                messages[messages.lastIndex] = messages.last().copy(content = answer.toString())
                                                                persist()
                                                            }
                                                        }
                                                        if (answer.isEmpty()) errorMessage = "The provider returned no text. Check the selected model and provider settings."
                                                    } catch (failure: Throwable) {
                                                        if (failure !is kotlinx.coroutines.CancellationException) {
                                                            errorMessage = failure.message ?: "Unable to reach the provider."
                                                            if (messages.lastOrNull()?.role == "assistant" && messages.last().content.isBlank()) {
                                                                messages.removeAt(messages.lastIndex)
                                                            }
                                                        }
                                                    } finally {
                                                        sending = false
                                                        persist()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = sending || draft.isNotBlank(),
                                modifier = Modifier.size(52.dp)
                            ) {
                                Icon(
                                    if (sending) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                                    contentDescription = if (sending) "Stop response" else "Send message"
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    errorMessage?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                    if (messages.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(68.dp).background(Color(0xFF30294A), RoundedCornerShape(24.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Text("A", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFD2C5FF))
                                }
                                Spacer(Modifier.height(22.dp))
                                Text("What can I help with?", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(8.dp))
                                Text("Your AI. Your keys. Your conversations.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(20.dp))
                                OutlinedButton(onClick = { showSettings = true }) {
                                    Text(if (keyStore.hasKey(settings.provider.name)) "Provider settings" else "Add API key")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                MessageBubble(message)
                            }
                            if (sending && messages.lastOrNull()?.content.isNullOrEmpty()) {
                                item { Text("Ava is thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp)) }
                            }
                        }
                    }
                }
            }
        }

        if (showHistory) {
            ModalBottomSheet(onDismissRequest = { showHistory = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Your conversations", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = ::startNewChat) { Text("New chat") }
                    }
                    if (conversations.isEmpty()) {
                        Text("Your saved chats will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp))
                    } else {
                        conversations.forEach { conversation ->
                            Row(Modifier.fillMaxWidth().clickable {
                                generationJob?.cancel()
                                activeId = conversation.id
                                messages.clear()
                                messages.addAll(conversation.messages)
                                errorMessage = null
                                showHistory = false
                            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(conversation.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                                    Text("${conversation.messages.size} messages",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    conversationStore.delete(conversation.id)
                                    conversations.removeAll { it.id == conversation.id }
                                    if (activeId == conversation.id) startNewChat()
                                }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Delete conversation") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) Color(0xFF342C4D) else Color.Transparent,
            shape = RoundedCornerShape(if (isUser) 22.dp else 8.dp),
            modifier = Modifier.widthIn(max = 680.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.content.ifBlank { " " },
                    modifier = Modifier.padding(horizontal = if (isUser) 16.dp else 4.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettingsScreen(
    initial: ProviderSettings,
    hasSavedKey: (ProviderType) -> Boolean,
    onBack: () -> Unit,
    onRemoveKey: (ProviderType) -> Unit,
    onSave: (ProviderSettings, String) -> Unit
) {
    var provider by remember(initial) { mutableStateOf(initial.provider) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var systemPrompt by remember(initial) { mutableStateOf(initial.systemPrompt) }
    var apiKey by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var keyRemoved by remember(provider) { mutableStateOf(false) }
    val currentHasKey = hasSavedKey(provider) && !keyRemoved
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provider settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Connect your AI provider", style = MaterialTheme.typography.headlineSmall)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = provider.label, onValueChange = {}, readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ProviderType.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            provider = option
                            model = option.defaultModel
                            baseUrl = option.defaultBaseUrl
                            expanded = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("Model") }, placeholder = { Text("Enter model ID") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text(if (currentHasKey) "API key saved · enter to replace" else "API key") },
                visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = systemPrompt, onValueChange = { systemPrompt = it },
                label = { Text("System prompt (optional)") }, modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 4
            )
            if (currentHasKey) {
                TextButton(onClick = { onRemoveKey(provider); keyRemoved = true }) { Text("Remove saved API key") }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSave(ProviderSettings(provider, model.trim(), baseUrl.trim(), systemPrompt), apiKey) },
                enabled = model.isNotBlank() && baseUrl.trim().startsWith("https://") &&\n                    android.net.Uri.parse(baseUrl.trim()).host != null &&
                    (apiKey.isNotBlank() || currentHasKey),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save and start chatting") }
            Text(
                "Your API key is encrypted on this device. Ava sends requests directly to the provider; no Ava account or application server is used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
