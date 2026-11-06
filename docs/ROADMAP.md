# Ava delivery roadmap

## Phase 1 — BYOK chat foundation
- [x] Native Kotlin / Jetpack Compose application shell
- [x] Model ID and OpenAI-compatible endpoint configuration
- [x] Streaming chat via server-sent events
- [x] Local conversation persistence
- [x] API key encryption with Android Keystore AES-GCM
- [ ] Provider-native adapters for OpenAI, Anthropic and Gemini
- [ ] Connection test, cancellation, retry and error recovery
- [ ] Automated unit and UI tests

## Phase 2 — Production chat experience
- [ ] Multiple conversations with create, rename, delete and search
- [ ] Markdown, code highlighting, copy and regenerate
- [ ] Attachments, export/import and robust long-context handling
- [ ] Responsive layouts, accessibility and performance checks

## Phase 3 — Agent foundation
- [ ] Provider-neutral normalized stream and tool-call models
- [ ] Tool registry with typed input/output contracts
- [ ] User-visible per-tool permissions and confirmation
- [ ] Bounded tool loop, cancellation and run recovery

## Phase 4 — Advanced capabilities
- [ ] Local memory and retrieval
- [ ] Optional on-device model support
- [ ] Background tasks only where Android lifecycle and permissions allow
- [ ] Backup, migration and diagnostics

The checkboxes describe implementation status; a checked item is not a claim of release readiness until CI and device-level verification are green.
