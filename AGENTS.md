# AGENTS.md

Guidance for coding agents working in this repository.

## Development process (mandatory)

Every development request (new feature, change, or bug fix) must follow [`docs/development-process.md`](docs/development-process.md). In short:

1. Write the request up as a requirements document in `requirements/`, named `YYYYMMDD-NN-short-name.md`, before touching code.
2. Get the user's explicit confirmation before starting development. Every revision of the document needs a new confirmation.
3. After development, test every acceptance criterion and deliver a test report with screenshot evidence for each test case.

Questions, code explanations, and read-only investigation do not need this process.

## Project

f2ft is a face-to-face voice translation Android app for short travel conversations between a Mandarin speaker and an English speaker sharing one phone. Speech is translated in real time by the Google Gemini Live API over a full-duplex WebSocket. Product requirements, the interaction prototype and the demo video are in `requirements/` (`产品需求.md` is the source of truth for behavior).

## Layout
- `requirements/` docs and reference materials (product spec, interaction prototype, demo), dated requirement documents, and their test reports.
- `docs/development-process.md` mandatory development workflow for agents.
- `android/` Gradle project, single module `:app`, package `com.fy.facetranslate`.
  - `live/GeminiLiveClient.kt` Live API protocol: setup message, audio/text input, server message parsing.
  - `session/ConversationViewModel.kt` turn-taking state machine; all state changes run on the main thread.
  - `session/Models.kt` `UiState`, `Phase`, `Record`.
  - `audio/` 16 kHz PCM mic capture and 24 kHz PCM streaming playback.
  - `data/Language.kt` per-language codes and UI strings; `data/SettingsStore.kt` settings and Keystore-encrypted API key.
  - `ui/` Jetpack Compose screens (home, session, settings).
- `.devtools/make_icons.py` regenerates launcher icons from `requirements/icon.png` (needs Pillow). Other files in `.devtools/` are local and git-ignored.

## Build and run

Use JDK 17 (the machine default may be newer and is not supported by the build):

```bash
cd android
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew :app:assembleDebug
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew :app:installDebug
```

- `android/local.properties` holds `sdk.dir` and is not committed.
- Gradle and dependency downloads need network access.
- There are no unit tests yet. Verify changes by building and, when a device is connected (`adb devices`), installing and exercising the affected flow; capture screenshots as evidence for the test report (see `docs/development-process.md`). If `adb devices` is empty, restarting the adb server (`adb kill-server && adb start-server`) has fixed it before. State plainly when something could not be verified on a device.

## Gemini Live API notes

- Endpoint: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=...`
- Audio in: raw 16-bit PCM, 16 kHz, mono, `audio/pcm;rate=16000`, about 100 ms chunks. Audio out: 24 kHz PCM.
- Default model `gemini-3.8-live`. One session per direction (ZH→EN, EN→ZH); the interpreter behavior comes from `systemInstruction`.
- `gemini-3.5-live-translate-preview` uses a different schema and the published docs do not match runtime:
  - `translationConfig` must be inside `generationConfig` (top level is rejected with 1007).
  - `inputAudioTranscription` / `outputAudioTranscription` must stay at `setup` top level (inside `generationConfig` is rejected).
  - Target codes come from its supported list (`zh-Hans`, `en`), stored as `Language.translationCode`.
  - It does not support system instructions or text input.
- The server validates the setup schema before the API key, so schema changes can be checked with a dummy key: a valid schema returns `1007 API key not valid`.
- Model IDs and field names change often; check current docs before changing them.

## Conventions

- Kotlin, Compose, no DI framework, no extra libraries beyond those in `app/build.gradle.kts` unless needed. Match existing comment density and naming.
- UI strings for each side live in `Language` and are shown in that side's own language; Chinese-owner screens (home, settings) use Chinese.
- Touch targets at least 48 dp with text labels.
- Late results from a cancelled turn must never update a newer turn: the ViewModel checks the client against the active turn and reconnects a session that was interrupted mid-turn. Preserve this when touching turn logic.

## Security and privacy

- Never put API keys in code, resources, logs, or commits. The key is entered on the settings page and stored encrypted via Android Keystore; backups exclude shared prefs.
- Conversation records and audio stay in memory only and are cleared when the session ends. Do not add on-device persistence of recordings or transcripts.
- The GitHub repo (https://github.com/wangwei1237/f2ft) is public. Scan staged files for secrets before committing, never force-push `main`, and ask before pushing.
