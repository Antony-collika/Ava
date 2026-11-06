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
- Basic Markdown styling for headings, bold text and inline code.
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

The current chat build is text-first. Attachments, rich full-spec Markdown, model discovery, export/backup and the Agent tool runtime are not part of this initial usable milestone.
