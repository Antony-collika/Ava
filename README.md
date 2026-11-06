# Ava

Ava is a native Android AI chat app built with Kotlin and Jetpack Compose.

## Product principles
- No Ava account and no application backend.
- Bring Your Own Key (BYOK): requests go directly from the device to the provider.
- Provider credentials are encrypted at rest with Android Keystore AES-GCM.
- Conversations are stored locally on the device.
- Designed as a fast, mobile-first chat client with a path to a permission-based AI Agent.

## Available in this build
- Native dark-mode chat UI with streaming responses and stop generation.
- Provider setup for OpenAI, Anthropic, Google Gemini and OpenAI-compatible APIs.
- User-configurable model ID, HTTPS base URL and optional system prompt.
- Separate encrypted API key storage for each provider.
- Test connection before saving provider settings.
- Local conversation history with new-chat, reopen and delete actions.
- Image attachments from the Android photo picker or camera; images are copied into app-private storage and sent as provider-native multimodal content.
- Markdown rendering for headings, lists, block quotes, fenced/inline code, bold, italic, strike-through and links, with in-chat search highlighting.
- In-chat search and cross-conversation search with message jump targets.
- Message editing, edit-and-regenerate, regenerate, linear branching, turn deletion with Undo, and model-aware context budgeting.
- SAF workspace folder access with relative-path sandboxing and file tools for listing, reading, writing, searching, copying, moving, renaming, deleting, ZIP, CSV, JSON and text conversion.
- Multi-round function calling for OpenAI-compatible, Anthropic and Gemini APIs; stable tool UIDs, editable model aliases/descriptions and per-tool approval modes.
- Local notes and TODOs, calculator, date/time utilities, webpage reading, Brave Search API integration, alarm/calendar handoff and clipboard write-only support.
- Gradle wrapper and GitHub Actions build/test workflow.

## Provider setup
1. Open Ava and choose a provider.
2. Enter the API key and model ID.
3. Keep the suggested base URL or enter the provider's HTTPS API base URL.
4. Use **Test connection**, then **Save and start chatting**.

For OpenAI-compatible services, enter the API base URL (normally ending in `/v1`) and the model ID accepted by that endpoint. Custom endpoints receive your API key, so only use endpoints you trust.

## Build
Open the project in Android Studio with JDK 17 and Android SDK 35, or run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The pull-request workflow builds the debug APK, runs unit tests and uploads the APK as a workflow artifact.

## Privacy and security
Ava has no chat proxy or application backend. Provider requests are made directly from the Android device. API keys are encrypted with AES-GCM using a non-exportable Android Keystore key. No app can guarantee protection on a rooted or compromised device. Conversations and settings remain in app-private local storage.

Workspace file tools remain unavailable until a folder is explicitly selected through Android's system folder picker. File paths passed to tools are relative to that folder; delete and other mutating operations require confirmation according to their configured policy. Configure a Brave Search API key in Tool controls to enable web search. Calendar events and alarms are handed off to the Android system UI for final confirmation.

Ava intentionally does not include a code execution environment, PDF/Word/Excel document tools, subagents or a remote backend.
