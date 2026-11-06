# Ava

Ava is a native Android AI chat client built with Kotlin and Jetpack Compose.

## Product principles
- No application backend or mandatory account.
- Bring Your Own Key (BYOK); requests are sent directly from the device to the configured AI endpoint.
- Local conversation history and settings.
- Streaming-first chat UX.
- Architecture will expand toward an AI Agent with explicit user approval for tool actions.

## Current implementation
- Android app shell and modern dark Compose chat interface.
- OpenAI-compatible chat completions endpoint with SSE streaming.
- Configurable model ID and HTTPS API base URL.
- API keys encrypted using an AES-GCM key held by Android Keystore.
- Chat transcript persisted in app-private preferences.

Native Anthropic and Gemini protocols, multi-conversation management, richer message rendering and the Agent runtime are planned, not yet implemented.

## Build
Open this repository in Android Studio with JDK 17 and Android SDK 35. The GitHub Actions workflow runs `gradle :app:assembleDebug` on pushes and pull requests.

## Security notes
Credentials are encrypted at rest with Android Keystore AES-GCM. No client-side application can guarantee secret safety on a rooted or otherwise compromised device. Requests go directly to the endpoint configured by the user. Review custom endpoint URLs before sending credentials.

See [docs/ROADMAP.md](docs/ROADMAP.md) for implementation status and planned phases.
