# Ava roadmap

## Milestone 1 — Usable BYOK chat
- [x] Native Kotlin / Jetpack Compose application
- [x] Provider setup for OpenAI, Anthropic, Gemini and OpenAI-compatible endpoints
- [x] Per-provider API key encryption with Android Keystore AES-GCM
- [x] Model ID, base URL and system prompt configuration
- [x] Provider connection test
- [x] Streaming responses and stop generation
- [x] Local conversation history, new chat, reopen and delete
- [x] Basic Markdown text styling
- [x] Gradle wrapper, CI build and unit-test workflow
- [ ] Successful CI build and installable APK artifact verification
- [ ] Device-level smoke test on a physical Android device

## Milestone 2 — Production chat experience
- [ ] Rich Markdown (lists, tables, fenced code, syntax highlighting)
- [ ] Copy message, regenerate response, edit and resend
- [ ] Model discovery where provider APIs support it
- [ ] Attachments, image input and document handling
- [ ] Export/import and backup/restore
- [ ] Better accessibility, tablet layouts and automated UI tests

## Milestone 3 — Agent foundation
- [ ] Provider-neutral normalized stream and tool-call models
- [ ] Typed local tool registry
- [ ] Per-tool permissions and user confirmation UI
- [ ] Bounded tool loop, cancellation and run recovery

## Milestone 4 — Advanced capabilities
- [ ] Local memory and retrieval
- [ ] Optional on-device model support
- [ ] Background tasks within Android lifecycle and permission constraints

Checked items describe implemented code, not a release guarantee. CI and device verification are tracked separately.
