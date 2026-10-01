# Wavdrop Music Player

An offline-first, local-first Android music player built with Kotlin, Jetpack Compose, and Media3.
No account, no ads, no cloud, no analytics. The app declares no `INTERNET` permission.

Package `com.launchpoint.wavdrop` - version `0.1.0-beta9` (versionCode 9) - minSdk 26, compileSdk /
targetSdk 36 - Room schema 13.

## Current capabilities

- **Playback**: Media3 `MediaLibraryService` with background playback, notification and lock-screen
  controls, Bluetooth / LE Audio / wired auto-resume, sleep timer, queue management (Play next, Add to
  queue, reorder, bulk cleanup), shuffle and repeat, cold-start resume, system media browse/resume, and
  a home-screen widget. An Equalizer (device-supported bands, platform and built-in presets) is
  available in Settings.
- **Library**: MediaStore scan (whole device or selected folders), songs / albums / artists / folders,
  global search, alphabet index, 11 computed smart collections, playlists with drag-to-reorder.
- **Lyrics**: embedded, same-folder `.lrc` (synchronized) and `.txt` sidecars, and editable app-managed
  lyric overrides. Nothing is fetched online.
- **Statistics**: per-song counters, Statistics dashboard, Listening Reports, Monthly Reports, Wrapped
  (monthly / yearly / all-time), Insights hub.
- **Backup and migration**: verified JSON backup/restore (format v2, v1 import supported), automatic
  backup via a WorkManager check, preservation of unmatched history in a pending (quarantine) store,
  Wavdrop Desktop backup import, and BlackPlayer EX `.bpstat` import.
- **Personalisation**: theme, accent colour, six launcher icons (default Obsidian Black), home layout,
  startup screen.

Crossfade is an **engineering foundation only** (disabled by a hard gate, no user-facing setting). See
[PROJECT_CONTEXT.md](PROJECT_CONTEXT.md).

## Requirements

- Android 8.0+ (API 26) device or emulator
- JDK 17
- Android Studio (current stable) with the Android SDK for API 36

## Build and test

```bat
gradlew.bat assembleDebug
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

`assembleRelease` produces an unsigned APK unless release-signing credentials are supplied (see
[docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)). Instrumentation tests under `app/src/androidTest`
require a device or emulator (`gradlew.bat :app:connectedDebugAndroidTest`).

## Architecture in brief

Single-module MVVM + Repository. Compose UI and Hilt wire ViewModels to repositories; Room (schema 13)
holds library, stats, events, playlists, lyrics, identity, and preservation data; DataStore holds
settings. `PlaybackService` (a `MediaLibraryService`) owns the real ExoPlayer and `MediaLibrarySession`;
the app-side `PlayerController` is the authority for the **logical queue**, which is occurrence-safe:
`song.id` is song identity, not queue-occurrence identity, and the current occurrence is
positional and generation-bound (`libraryQueue` / `playbackOrder` / `queueGeneration`). Details:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Documentation map

| Document | Purpose |
|---|---|
| [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) | current project state and invariants |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | current technical architecture |
| [ENGINEERING_BACKLOG_AND_DECISIONS.md](ENGINEERING_BACKLOG_AND_DECISIONS.md) | durable decisions and engineering backlog |
| [TECHNICAL_DEBT_REGISTER.md](TECHNICAL_DEBT_REGISTER.md) | accepted technical debt |
| [PLANNED.md](PLANNED.md) | product / future work |
| [RELEASE_NOTES.md](RELEASE_NOTES.md), [WHATS_NEW.md](WHATS_NEW.md) | change history and current user-facing summary |
| [QA_CHECKLIST.md](QA_CHECKLIST.md) | manual / physical QA |
| [docs/WAVDROP_DATA_FORMAT_SPEC.md](docs/WAVDROP_DATA_FORMAT_SPEC.md), [docs/WAVDROP_IMPORT_RULES.md](docs/WAVDROP_IMPORT_RULES.md), [docs/BACKUP_PRESERVATION_CONTRACT.md](docs/BACKUP_PRESERVATION_CONTRACT.md), [docs/WAVDROP_BACKUP_SCHEMA_V1.md](docs/WAVDROP_BACKUP_SCHEMA_V1.md) | backup / import / preservation contracts |
| [docs/BRANDING.md](docs/BRANDING.md), [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) | branding, release signing |
| [PLAY_STORE_LISTING_DRAFT.md](PLAY_STORE_LISTING_DRAFT.md), [PLAY_STORE_READINESS_CHECKLIST.md](PLAY_STORE_READINESS_CHECKLIST.md) | store listing and readiness |
| [docs/DOCUMENTATION_POLICY.md](docs/DOCUMENTATION_POLICY.md) | how these documents are maintained |
