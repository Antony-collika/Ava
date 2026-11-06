package com.antonycollika.ava.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.antonycollika.ava.SecureKeyStore
import com.antonycollika.ava.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun AvaApp(
    settingsStore: ProviderSettingsStore,
    conversationStore: ConversationStore,
    keyStore: SecureKeyStore,
    attachmentStore: AttachmentStore,
    workspaceStore: WorkspaceStore,
    toolRegistryStore: ToolRegistryStore,
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
    var showTools by remember { mutableStateOf(false) }
    var workspaceMessage by remember { mutableStateOf<String?>(null) }
    var pendingToolExecution by remember { mutableStateOf<ToolExecution?>(null) }
    var pendingToolDecision by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val dailyToolRuntime = remember(context, keyStore) { DailyToolRuntime(context, keyStore) }
    val toolRuntime = remember(workspaceStore, toolRegistryStore, dailyToolRuntime) {
        WorkspaceToolRuntime(workspaceStore, toolRegistryStore, dailyToolRuntime)
    }
    val workspacePicker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { workspaceStore.selectWorkspace(uri) }
            .onSuccess { workspaceMessage = "Workspace folder selected." }
            .onFailure { workspaceMessage = it.message ?: "Unable to access workspace." }
    }
    var draft by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<MessageAttachment>>(emptyList()) }
    val context = LocalContext.current
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        val imported = uris.mapNotNull { uri -> runCatching { attachmentStore.importUri(context.contentResolver, uri) }
            .onFailure { errorMessage = it.message ?: "Unable to attach this image." }.getOrNull() }
        pendingAttachments = (pendingAttachments + imported).take(4)
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) runCatching { attachmentStore.importBitmap(bitmap) }
            .onSuccess { pendingAttachments = (pendingAttachments + it).take(4) }
            .onFailure { errorMessage = it.message ?: "Unable to save camera image." }
    }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var activeRunId by remember { mutableStateOf<String?>(null) }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    var editThenRegenerate by remember { mutableStateOf(false) }
    var pendingRegenerationIndex by remember { mutableStateOf<Int?>(null) }
    var renameConversationId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var historySearch by remember { mutableStateOf("") }
    var inChatSearchVisible by remember { mutableStateOf(false) }
    var inChatSearch by remember { mutableStateOf("") }
    var searchMatchIndex by remember { mutableIntStateOf(0) }
    var pendingJumpMessageId by remember { mutableStateOf<String?>(null) }
    var darkTheme by remember { mutableStateOf(settingsStore.loadDarkTheme()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val messageSnapshot = messages.toList()
    val inChatMatches = if (inChatSearch.isBlank()) emptyList() else messageSnapshot.mapIndexedNotNull { index, message ->
        if (message.content.contains(inChatSearch, ignoreCase = true)) index else null
    }
    LaunchedEffect(messageSnapshot.lastOrNull()?.id, messageSnapshot.lastOrNull()?.content, inChatSearchVisible) {
        if (!inChatSearchVisible && messageSnapshot.isNotEmpty()) listState.animateScrollToItem(messageSnapshot.lastIndex)
    }
    LaunchedEffect(pendingJumpMessageId, messageSnapshot) {
        val target = pendingJumpMessageId ?: return@LaunchedEffect
        val index = messageSnapshot.indexOfFirst { it.id == target }
        if (index >= 0) { listState.animateScrollToItem(index); pendingJumpMessageId = null }
    }
    val scope = rememberCoroutineScope()

    fun persist() {
        if (messages.isEmpty()) {
            conversationStore.delete(activeId)
            conversations.removeAll { it.id == activeId }
            return
        }
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

    fun cancelGeneration(persistPartial: Boolean = true) {
        activeRunId = null
        generationJob?.cancel()
        pendingToolDecision?.cancel()
        pendingToolDecision = null
        pendingToolExecution = null
        sending = false
        if (messages.lastOrNull()?.let { it.role == "assistant" && it.content.isBlank() } == true) {
            messages.removeAt(messages.lastIndex)
        }
        if (persistPartial) persist()
    }

    fun startNewChat() {
        cancelGeneration()
        messages.clear()
        activeId = UUID.randomUUID().toString()
        errorMessage = null
        showHistory = false
    }

    fun startStreaming(requestHistory: List<ChatMessage>) {
        val key = keyStore.read(settings.provider.name)
        if (key.isNullOrBlank()) {
            if (messages.lastOrNull()?.let { it.role == "assistant" && it.content.isBlank() } == true) messages.removeAt(messages.lastIndex)
            errorMessage = "Add your API key in Settings to start chatting."
            showSettings = true
            sending = false
            return
        }
        editingMessageId = null
        editThenRegenerate = false
        pendingRegenerationIndex = null
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        sending = true
        persist()
        generationJob = scope.launch {
            val answer = StringBuilder()
            try {
                ToolLoopRunner(providerClient).run(
                    settings = settings,
                    apiKey = key,
                    initialMessages = requestHistory,
                    registry = toolRegistryStore,
                    runtime = toolRuntime,
                    allowedToolUids = AvaTools.definitions.filter { !it.uid.startsWith("ava.fs.") || workspaceStore.hasAccess() }.map { it.uid }.toSet(),
                    confirm = { execution ->
                        val decision = CompletableDeferred<Boolean>()
                        pendingToolExecution = execution
                        pendingToolDecision = decision
                        decision.await()
                    },
                    onText = { chunk ->
                        if (activeRunId == runId) {
                            answer.append(chunk)
                            val index = messages.indexOfLast { it.role == "assistant" }
                            if (index >= 0) messages[index] = messages[index].copy(content = messages[index].content + chunk)
                            persist()
                        }
                    },
                    onAssistantToolCalls = { assistant ->
                        if (activeRunId == runId) {
                            val index = messages.indexOfLast { it.role == "assistant" }
                            if (index >= 0 && messages[index].content.isBlank() && messages[index].toolCalls.isEmpty()) messages[index] = assistant
                            else messages.add(assistant)
                            persist()
                        }
                    },
                    onToolResult = { toolMessage ->
                        if (activeRunId == runId) { messages.add(toolMessage); persist() }
                    },
                    onToolRoundComplete = {
                        if (activeRunId == runId) {
                            messages.add(ChatMessage(UUID.randomUUID().toString(), "assistant", ""))
                            persist()
                        }
                    },
                    onToolStatus = { status -> workspaceMessage = status }
                )
                if (activeRunId == runId && answer.isEmpty() && messages.lastOrNull()?.toolCalls.isNullOrEmpty()) {
                    errorMessage = "The provider returned no text. Check the selected model and provider settings."
                }
                if (messages.lastOrNull()?.let { it.role == "assistant" && it.content.isBlank() && it.toolCalls.isEmpty() } == true) {
                    messages.removeAt(messages.lastIndex)
                }
            } catch (failure: Throwable) {
                if (activeRunId == runId && failure !is kotlinx.coroutines.CancellationException) {
                    errorMessage = failure.message ?: "Unable to reach the provider."
                    if (messages.lastOrNull()?.let { it.role == "assistant" && it.content.isBlank() } == true) messages.removeAt(messages.lastIndex)
                }
            } finally {
                if (activeRunId == runId) {
                    activeRunId = null
                    sending = false
                    persist()
                }
            }
        }
    }

    fun regenerateAt(userIndex: Int, createBranch: Boolean) {
        if (sending || userIndex !in messages.indices || messages[userIndex].role != "user") return
        if (keyStore.read(settings.provider.name).isNullOrBlank()) {
            errorMessage = "Add your API key in Settings to regenerate this response."
            showSettings = true
            return
        }
        val prefix = messages.take(userIndex + 1)
        if (createBranch) {
            persist()
            activeId = UUID.randomUUID().toString()
            messages.clear()
            messages.addAll(prefix)
        } else {
            while (messages.size > userIndex + 1) messages.removeAt(messages.lastIndex)
        }
        errorMessage = null
        messages.add(ChatMessage(UUID.randomUUID().toString(), "assistant", ""))
        startStreaming(prefix)
    }

    fun requestRegeneration(selectedIndex: Int) {
        if (sending) return
        val plan = ConversationOperations.regenerationPlan(messages, selectedIndex) ?: return
        if (plan.hasFollowingMessages) pendingRegenerationIndex = plan.userMessageIndex
        else regenerateAt(plan.userMessageIndex, createBranch = false)
    }

    fun branchAt(selectedIndex: Int) {
        if (sending || selectedIndex !in messages.indices) return
        val prefix = ConversationOperations.branchPrefix(messages, selectedIndex)
        if (prefix.isEmpty()) return
        persist()
        activeId = UUID.randomUUID().toString()
        messages.clear()
        messages.addAll(prefix)
        errorMessage = null
        showHistory = false
        persist()
    }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFFC5B5FF),
        onPrimary = Color(0xFF241A42),
        background = Color(0xFF101014),
        surface = Color(0xFF1B1B22),
        surfaceVariant = Color(0xFF292832),
        onSurface = Color(0xFFF2F0F7),
        onSurfaceVariant = Color(0xFFB6B1C2)
    ) else lightColorScheme()) {
        if (showSettings) {
            ProviderSettingsScreen(
                initial = settings,
                loadProviderSettings = { selected -> settingsStore.load(selected) },
                hasSavedKey = { selected -> keyStore.hasKey(selected.name) },
                onBack = { showSettings = false },
                onRemoveKey = { selected -> keyStore.clear(selected.name) },
                onSave = { updated, key ->
                    settingsStore.save(updated)
                    if (key.isNotBlank()) keyStore.save(key.trim(), updated.provider.name)
                    settings = updated
                    showSettings = false
                    errorMessage = null
                },
                onTestConnection = { candidate, enteredKey ->
                    val secret = enteredKey.takeIf(String::isNotBlank)
                        ?: keyStore.read(candidate.provider.name).orEmpty()
                    if (secret.isBlank()) throw ProviderException("Enter an API key before testing.")
                    val received = StringBuilder()
                    providerClient.stream(
                        candidate, secret,
                        listOf(ChatMessage(UUID.randomUUID().toString(), "user", "Reply with OK."))
                    ).collect { received.append(it) }
                    if (received.isBlank()) throw ProviderException("The provider connected but returned no text.")
                }
            )
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                Text(
                                    "~${ConversationOperations.estimateConversationTokens(messages, settings.systemPrompt)} tokens",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { showHistory = true }) {
                                Icon(Icons.Default.History, contentDescription = "Conversation history")
                            }
                        },
                        actions = {
                            IconButton(onClick = { darkTheme = !darkTheme; settingsStore.saveDarkTheme(darkTheme) }) {
                                Text(if (darkTheme) "☼" else "☾")
                            }
                            IconButton(onClick = { workspacePicker.launch(null) }) { Text("▣") }
                            IconButton(onClick = { showTools = true }) { Text("⚙") }
                            IconButton(onClick = { inChatSearchVisible = !inChatSearchVisible; inChatSearch = "" }) { Text("⌕") }
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
                        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
                            if (pendingAttachments.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pendingAttachments.forEach { attachment ->
                                        val bitmap = remember(attachment.id) {
                                            attachmentStore.file(attachment)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                                        }
                                        Box(Modifier.size(68.dp)) {
                                            bitmap?.let { Image(it, attachment.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                            IconButton(onClick = { pendingAttachments = pendingAttachments.filterNot { it.id == attachment.id } },
                                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) { Text("×") }
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Bottom) {
                                IconButton(onClick = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    enabled = !sending && pendingAttachments.size < 4) { Text("＋") }
                                IconButton(onClick = { cameraPicker.launch(null) },
                                    enabled = !sending && pendingAttachments.size < 4) { Text("◉") }
                                OutlinedTextField(value = draft, onValueChange = { draft = it },
                                    modifier = Modifier.weight(1f), placeholder = { Text("Message Ava…") },
                                    maxLines = 6, shape = RoundedCornerShape(24.dp))
                                Spacer(Modifier.width(8.dp))
                                FilledIconButton(onClick = {
                                    if (sending) cancelGeneration() else {
                                        val prompt = draft.trim()
                                        if (prompt.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                                            if (keyStore.read(settings.provider.name).isNullOrBlank()) {
                                                errorMessage = "Add your API key in Settings to start chatting."
                                                showSettings = true
                                                return@FilledIconButton
                                            }
                                            draft = ""
                                            errorMessage = null
                                            messages.add(ChatMessage(UUID.randomUUID().toString(), "user", prompt,
                                                attachments = pendingAttachments.toList()))
                                            pendingAttachments = emptyList()
                                            messages.add(ChatMessage(UUID.randomUUID().toString(), "assistant", ""))
                                            startStreaming(messages.dropLast(1).toList())
                                        }
                                    }
                                }, enabled = sending || draft.isNotBlank() || pendingAttachments.isNotEmpty(),
                                    modifier = Modifier.size(52.dp)) {
                                    Icon(if (sending) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                                        contentDescription = if (sending) "Stop response" else "Send message")
                                }
                            }
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (inChatSearchVisible) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = inChatSearch, onValueChange = { inChatSearch = it; searchMatchIndex = 0 },
                                modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Find in this chat") })
                            Text("${if (inChatMatches.isEmpty()) 0 else searchMatchIndex + 1}/${inChatMatches.size}", modifier = Modifier.padding(horizontal = 6.dp))
                            IconButton(onClick = { if (inChatMatches.isNotEmpty()) { searchMatchIndex = (searchMatchIndex - 1).mod(inChatMatches.size); scope.launch { listState.animateScrollToItem(inChatMatches[searchMatchIndex]) } } }) { Text("↑") }
                            IconButton(onClick = { if (inChatMatches.isNotEmpty()) { searchMatchIndex = (searchMatchIndex + 1).mod(inChatMatches.size); scope.launch { listState.animateScrollToItem(inChatMatches[searchMatchIndex]) } } }) { Text("↓") }
                        }
                    }
                    workspaceMessage?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, modifier = Modifier.weight(1f))
                                TextButton(onClick = { workspacePicker.launch(null) }) { Text("Choose folder") }
                            }
                        }
                    }
                    if (!workspaceStore.hasAccess()) {
                        TextButton(onClick = { workspacePicker.launch(null) }) { Text("Choose a workspace to enable file tools") }
                    }
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
                        val listState = rememberLazyListState()
                        LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
                            if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                val messageIndex = messages.indexOfFirst { it.id == message.id }
                                MessageBubble(
                                    message = message,
                                    attachmentStore = attachmentStore,
                                    highlightQuery = inChatSearch,
                                    enabled = !sending,
                                    editing = editingMessageId == message.id,
                                    editText = editingText,
                                    onEditTextChange = { editingText = it },
                                    onSaveEdit = {
                                        if (messageIndex in messages.indices) {
                                            messages[messageIndex] = messages[messageIndex].copy(content = editingText, edited = true)
                                            val shouldRegenerate = editThenRegenerate && message.role == "user"
                                            editingMessageId = null
                                            editThenRegenerate = false
                                            persist()
                                            if (shouldRegenerate) requestRegeneration(messageIndex)
                                        }
                                    },
                                    onCancelEdit = { editingMessageId = null; editThenRegenerate = false },
                                    onAction = { action ->
                                        when (action) {
                                            MessageAction.EDIT -> {
                                                editingMessageId = message.id; editingText = message.content; editThenRegenerate = false
                                            }
                                            MessageAction.EDIT_AND_REGENERATE -> {
                                                editingMessageId = message.id; editingText = message.content; editThenRegenerate = true
                                            }
                                            MessageAction.REGENERATE -> requestRegeneration(messageIndex)
                                            MessageAction.BRANCH -> branchAt(messageIndex)
                                            MessageAction.DELETE -> {
                                                val before = messages.toList()
                                                val deletedConversationId = activeId
                                                val deletedConversation = conversations.firstOrNull { it.id == deletedConversationId }
                                                val after = ConversationOperations.deleteTurn(before, messageIndex)
                                                if (after != before) {
                                                    messages.clear(); messages.addAll(after); persist()
                                                    scope.launch {
                                                        val result = snackbarHostState.showSnackbar(
                                                            "Message turn deleted", actionLabel = "Undo",
                                                            withDismissAction = true, duration = SnackbarDuration.Short
                                                        )
                                                        if (result == SnackbarResult.ActionPerformed) {
                                                            val currentMessages = conversations.firstOrNull { it.id == deletedConversationId }?.messages
                                                            val canUndo = currentMessages == after || (after.isEmpty() && currentMessages == null)
                                                            if (!canUndo) {
                                                                snackbarHostState.showSnackbar("Conversation changed; undo was skipped.")
                                                                return@launch
                                                            }
                                                            val restored = Conversation(
                                                                id = deletedConversationId,
                                                                title = deletedConversation?.title
                                                                    ?: before.firstOrNull { it.role == "user" }?.content?.take(42).orEmpty().ifBlank { "New chat" },
                                                                messages = before,
                                                                updatedAt = System.currentTimeMillis()
                                                            )
                                                            conversationStore.save(restored)
                                                            val restoredIndex = conversations.indexOfFirst { it.id == deletedConversationId }
                                                            if (restoredIndex >= 0) conversations[restoredIndex] = restored
                                                            else conversations.add(0, restored)
                                                            if (activeId == deletedConversationId) {
                                                                messages.clear()
                                                                messages.addAll(before)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
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

        if (pendingToolExecution != null) {
            val pending = pendingToolExecution!!
            AlertDialog(onDismissRequest = { pendingToolDecision?.complete(false); pendingToolDecision = null; pendingToolExecution = null },
                title = { Text("Allow this tool action?") }, text = { Text(pending.confirmationSummary) },
                confirmButton = { TextButton(onClick = { pendingToolDecision?.complete(true); pendingToolDecision = null; pendingToolExecution = null }) { Text("Allow") } },
                dismissButton = { TextButton(onClick = { pendingToolDecision?.complete(false); pendingToolDecision = null; pendingToolExecution = null }) { Text("Deny") } })
        }
        if (showTools) ToolSettingsSheet(toolRegistryStore, keyStore, settings.provider, onDismiss = { showTools = false })
        if (pendingRegenerationIndex != null) {
            AlertDialog(
                onDismissRequest = { pendingRegenerationIndex = null },
                title = { Text("Regenerate response?") },
                text = { Text("There are later messages in this conversation. Create a new branch?") },
                confirmButton = {
                    TextButton(onClick = {
                        val index = pendingRegenerationIndex ?: return@TextButton
                        pendingRegenerationIndex = null
                        regenerateAt(index, createBranch = true)
                    }) { Text("Create branch") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val index = pendingRegenerationIndex ?: return@TextButton
                        pendingRegenerationIndex = null
                        regenerateAt(index, createBranch = false)
                    }) { Text("Regenerate here") }
                }
            )
        }
        if (renameConversationId != null) {
            AlertDialog(
                onDismissRequest = { renameConversationId = null },
                title = { Text("Rename conversation") },
                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        val id = renameConversationId ?: return@TextButton
                        val target = conversations.firstOrNull { it.id == id }
                        if (target != null) {
                            val updated = target.copy(title = renameText.trim().ifBlank { "New chat" },
                                updatedAt = System.currentTimeMillis())
                            conversationStore.save(updated)
                            val index = conversations.indexOfFirst { it.id == id }
                            if (index >= 0) conversations[index] = updated
                        }
                        renameConversationId = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renameConversationId = null }) { Text("Cancel") } }
            )
        }
        if (showHistory) {
            ModalBottomSheet(onDismissRequest = { showHistory = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Your conversations", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = ::startNewChat) { Text("New chat") }
                    }
                    OutlinedTextField(
                        value = historySearch, onValueChange = { historySearch = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Search conversations and messages") }
                    )
                    if (conversations.isEmpty()) {
                        Text("Your saved chats will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp))
                    } else {
                        conversations.filter { conversation ->
                            historySearch.isBlank() || conversation.title.contains(historySearch, true) ||
                                conversation.messages.any { it.content.contains(historySearch, true) }
                        }.forEach { conversation ->
                            val matchingMessage = if (historySearch.isBlank()) null else conversation.messages.firstOrNull { it.content.contains(historySearch, true) }
                            Row(Modifier.fillMaxWidth().clickable {
                                cancelGeneration()
                                activeId = conversation.id
                                messages.clear()
                                messages.addAll(conversation.messages)
                                pendingJumpMessageId = matchingMessage?.id
                                inChatSearch = historySearch
                                inChatSearchVisible = historySearch.isNotBlank()
                                errorMessage = null
                                showHistory = false
                            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(conversation.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                                    Text(matchingMessage?.content?.let { excerptAround(it, historySearch) } ?: "${conversation.messages.size} messages",
                                        style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    renameConversationId = conversation.id
                                    renameText = conversation.title
                                }) { Text("✎") }
                                IconButton(onClick = {
                                    conversationStore.delete(conversation.id)
                                    conversations.removeAll { it.id == conversation.id }
                                    if (activeId == conversation.id) {
                                        cancelGeneration(persistPartial = false)
                                        messages.clear()
                                        activeId = UUID.randomUUID().toString()
                                        errorMessage = null
                                        showHistory = false
                                    }
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
private fun ToolSettingsSheet(store: ToolRegistryStore, keyStore: SecureKeyStore, provider: ProviderType, onDismiss: () -> Unit) {
    var webSearchKey by remember { mutableStateOf(keyStore.read("WEB_SEARCH").orEmpty()) }
    var keySaved by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(store.all()) }
    var editingUid by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingDescription by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Tool controls", style = MaterialTheme.typography.titleLarge)
            Text("Web search · Brave Search API", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = webSearchKey, onValueChange = { webSearchKey = it; keySaved = false },
                label = { Text("Brave Search API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (webSearchKey.isNotBlank()) { keyStore.save(webSearchKey, "WEB_SEARCH"); keySaved = true } },
                    enabled = webSearchKey.isNotBlank()) { Text("Save search key") }
                TextButton(onClick = { keyStore.clear("WEB_SEARCH"); webSearchKey = ""; keySaved = false }) { Text("Remove") }
                if (keySaved) Text("Saved", color = MaterialTheme.colorScheme.primary)
            }
            Text("UIDs and original descriptions are fixed. The model sees only enabled tools and their customized names/descriptions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            controls.forEach { control ->
                val definition = AvaTools.definitions.first { it.uid == control.uid }
                Divider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(control.modelName, style = MaterialTheme.typography.titleMedium)
                        Text("UID: " + definition.uid, style = MaterialTheme.typography.labelSmall)
                        Text(definition.defaultDescription, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = control.enabled, onCheckedChange = { enabled ->
                        val updated = control.copy(enabled = enabled)
                        store.update(updated, provider); controls = store.all()
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mode: " + control.approval.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val modes = ToolApproval.entries
                        val next = modes[(modes.indexOf(control.approval) + 1) % modes.size]
                        store.update(control.copy(approval = next)); controls = store.all()
                    }) { Text("Change") }
                    TextButton(onClick = {
                        editingUid = control.uid; editingName = control.modelName
                        editingDescription = control.modelDescription; validationError = null
                    }) { Text("Edit") }
                    TextButton(onClick = { store.restore(control.uid); controls = store.all() }) { Text("Reset") }
                }
                Text("Model description: " + control.modelDescription, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (editingUid != null) {
        AlertDialog(onDismissRequest = { editingUid = null },
            title = { Text("Customize tool for the model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editingName, onValueChange = { editingName = it },
                        label = { Text("Model tool name") }, singleLine = true)
                    OutlinedTextField(value = editingDescription, onValueChange = { editingDescription = it },
                        label = { Text("Model description") }, minLines = 3)
                    validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uid = editingUid ?: return@TextButton
                    val current = controls.first { it.uid == uid }
                    val updated = current.copy(modelName = editingName.trim(), modelDescription = editingDescription.trim())
                    validationError = store.update(updated, provider)
                    if (validationError == null) { controls = store.all(); editingUid = null }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingUid = null }) { Text("Cancel") } })
    }
}

private enum class MessageAction { EDIT, EDIT_AND_REGENERATE, REGENERATE, BRANCH, DELETE }

@Composable
private fun MessageBubble(
    message: ChatMessage,
    attachmentStore: AttachmentStore,
    highlightQuery: String,
    enabled: Boolean,
    editing: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAction: (MessageAction) -> Unit
) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember(message.id) { mutableStateOf(false) }
    var expandedEditor by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (message.edited) Text("edited", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.role == "tool") {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Tool result · " + (message.toolUid ?: "unknown"), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (editing) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = editText, onValueChange = onEditTextChange,
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { expandedEditor = true }) { Text("Expand") }
                        TextButton(onClick = onCancelEdit) { Text("Cancel") }
                        Button(onClick = onSaveEdit, enabled = editText.isNotBlank()) { Text("Save") }
                    }
                }
            }
        } else {
            Surface(
                color = if (isUser) Color(0xFF342C4D) else Color.Transparent,
                shape = RoundedCornerShape(if (isUser) 22.dp else 8.dp),
                modifier = Modifier.widthIn(max = 680.dp)
            ) {
                Column(Modifier.padding(horizontal = if (isUser) 16.dp else 4.dp, vertical = 12.dp)) {
                    if (message.toolCalls.isNotEmpty()) {
                    Text("Called " + message.toolCalls.joinToString { it.modelName },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                message.attachments.forEach { attachment ->
                        val bitmap = remember(attachment.id) {
                            attachmentStore.file(attachment)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                        }
                        bitmap?.let { Image(it, attachment.fileName,
                            Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit) }
                    }
                    SelectionContainer {
                        Text(text = remember(message.content) { renderBasicMarkdown(message.content.ifBlank { " " }, highlightQuery) },
                            style = MaterialTheme.typography.bodyLarge, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight)
                    }
                }
            }
            Box {
                TextButton(onClick = { menuExpanded = true }, enabled = enabled) { Text("⋯") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Copy") }, onClick = {
                        clipboard.setText(AnnotatedString(message.content)); menuExpanded = false
                    })
                    DropdownMenuItem(text = { Text("Edit message") }, onClick = {
                        menuExpanded = false; onAction(MessageAction.EDIT)
                    })
                    if (isUser) {
                        DropdownMenuItem(text = { Text("Edit & regenerate") }, onClick = {
                            menuExpanded = false; onAction(MessageAction.EDIT_AND_REGENERATE)
                        })
                        DropdownMenuItem(text = { Text("Regenerate") }, onClick = {
                            menuExpanded = false; onAction(MessageAction.REGENERATE)
                        })
                    } else {
                        DropdownMenuItem(text = { Text("Branch in new chat") }, onClick = {
                            menuExpanded = false; onAction(MessageAction.BRANCH)
                        })
                    }
                    DropdownMenuItem(text = { Text("Delete turn") }, onClick = {
                        menuExpanded = false; onAction(MessageAction.DELETE)
                    })
                }
            }
        }
    }
    if (expandedEditor) {
        AlertDialog(onDismissRequest = { expandedEditor = false }, title = { Text("Edit message") },
            text = { OutlinedTextField(value = editText, onValueChange = onEditTextChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp), minLines = 10) },
            confirmButton = { TextButton(onClick = { expandedEditor = false }) { Text("Done") } })
    }
}

private fun excerptAround(text: String, query: String): String {
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) return text.take(90)
    val start = (index - 35).coerceAtLeast(0)
    val end = (index + query.length + 55).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end) + if (end < text.length) "…" else ""
}

private fun renderBasicMarkdown(source: String, highlightQuery: String = "") = buildAnnotatedString {
    val tick = 96.toChar().toString()
    val inlinePattern = Regex("(\\*\\*.+?\\*\\*|__.+?__|~~.+?~~|\\*[^*]+\\*|_[^_]+_|" + tick + "[^" + tick + "]+" + tick + "|!?\\[[^]]+\\]\\([^ )]+\\))")
    var inCodeBlock = false
    val lines = source.lines()
    lines.forEachIndexed { lineIndex, original ->
        val line = original.trimEnd()
        if (line.trimStart().startsWith(tick.repeat(3))) {
            inCodeBlock = !inCodeBlock
        } else if (inCodeBlock) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33292932))) { append(line) }
        } else {
            val heading = Regex("^#{1,6}\\s+").find(line)
            val quote = line.trimStart().startsWith(">")
            val unordered = Regex("^\\s*[-*+]\\s+").find(line)
            val ordered = Regex("^\\s*\\d+[.)]\\s+").find(line)
            val content = when {
                heading != null -> line.removeRange(heading.range)
                quote -> line.trimStart().removePrefix(">").trimStart()
                unordered != null -> "• " + line.removeRange(unordered.range)
                ordered != null -> line.substring(ordered.range.first, ordered.range.last + 1).trim() + " " + line.removeRange(ordered.range)
                else -> line
            }
            val blockStyle = when {
                heading != null -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (24 - heading.value.trim().length).coerceAtLeast(14).sp)
                quote -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color.Gray)
                else -> SpanStyle()
            }
            withStyle(blockStyle) {
                var cursor = 0
                inlinePattern.findAll(content).forEach { match ->
                    appendHighlighted(content.substring(cursor, match.range.first), highlightQuery)
                    val token = match.value
                    when {
                        token.startsWith(tick) -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33292932))) {
                            append(token.removePrefix(tick).removeSuffix(tick))
                        }
                        token.startsWith("**") || token.startsWith("__") ->
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
                        token.startsWith("~~") ->
                            withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
                        token.startsWith("*") || token.startsWith("_") ->
                            withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(token.substring(1, token.length - 1)) }
                        token.startsWith("[") || token.startsWith("![") -> {
                            val label = token.substringAfter("[").substringBefore("]")
                            withStyle(SpanStyle(color = Color(0xFF8AB4F8), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) { append(label) }
                        }
                    }
                    cursor = match.range.last + 1
                }
                appendHighlighted(content.substring(cursor), highlightQuery)
            }
        }
        if (lineIndex < lines.lastIndex) append('\n')
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendHighlighted(text: String, query: String) {
    if (query.isBlank()) { append(text); return }
    var cursor = 0
    while (cursor < text.length) {
        val match = text.indexOf(query, cursor, ignoreCase = true)
        if (match < 0) { append(text.substring(cursor)); break }
        append(text.substring(cursor, match))
        withStyle(SpanStyle(background = Color(0xFFFFD54F), color = Color.Black, fontWeight = FontWeight.Bold)) {
            append(text.substring(match, match + query.length))
        }
        cursor = match + query.length
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettingsScreen(
    initial: ProviderSettings,
    loadProviderSettings: (ProviderType) -> ProviderSettings,
    hasSavedKey: (ProviderType) -> Boolean,
    onBack: () -> Unit,
    onRemoveKey: (ProviderType) -> Unit,
    onSave: (ProviderSettings, String) -> Unit,
    onTestConnection: suspend (ProviderSettings, String) -> Unit
) {
    var provider by remember(initial) { mutableStateOf(initial.provider) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var systemPrompt by remember(initial) { mutableStateOf(initial.systemPrompt) }
    var apiKey by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var keyRemoved by remember(provider) { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
                            val selected = loadProviderSettings(option)
                            provider = selected.provider
                            model = selected.model
                            baseUrl = selected.baseUrl
                            systemPrompt = selected.systemPrompt
                            apiKey = ""
                            keyRemoved = false
                            testMessage = null
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
            OutlinedButton(
                onClick = {
                    testing = true
                    testMessage = null
                    scope.launch {
                        val result = runCatching {
                            onTestConnection(ProviderSettings(provider, model.trim(), baseUrl.trim(), systemPrompt), apiKey)
                        }
                        testMessage = result.fold({ "Connection successful." }, { it.message ?: "Connection failed." })
                        testing = false
                    }
                },
                enabled = !testing && model.isNotBlank() && baseUrl.trim().startsWith("https://") &&
                    android.net.Uri.parse(baseUrl.trim()).host != null && (apiKey.isNotBlank() || currentHasKey),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (testing) "Testing connection…" else "Test connection") }
            testMessage?.let {
                Text(it, color = if (it == "Connection successful.") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { onSave(ProviderSettings(provider, model.trim(), baseUrl.trim(), systemPrompt), apiKey) },
                enabled = model.isNotBlank() && baseUrl.trim().startsWith("https://") &&
                    android.net.Uri.parse(baseUrl.trim()).host != null &&
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
