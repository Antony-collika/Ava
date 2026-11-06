package com.antonycollika.ava

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.antonycollika.ava.data.AttachmentStore
import com.antonycollika.ava.data.WorkspaceStore
import com.antonycollika.ava.data.ToolRegistryStore
import com.antonycollika.ava.data.ConversationStore
import com.antonycollika.ava.data.ProviderClient
import com.antonycollika.ava.data.ProviderSettingsStore
import com.antonycollika.ava.ui.AvaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsStore = ProviderSettingsStore(applicationContext)
        val conversationStore = ConversationStore(applicationContext)
        val keyStore = SecureKeyStore(applicationContext)
        val attachmentStore = AttachmentStore(applicationContext)
        val workspaceStore = WorkspaceStore(applicationContext)
        val toolRegistryStore = ToolRegistryStore(applicationContext)

        setContent {
            AvaApp(
                settingsStore = settingsStore,
                conversationStore = conversationStore,
                keyStore = keyStore,
                attachmentStore = attachmentStore,
                workspaceStore = workspaceStore,
                toolRegistryStore = toolRegistryStore,
                providerClient = ProviderClient(attachmentStore)
            )
        }
    }
}
