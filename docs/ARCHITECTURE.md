# Wavdrop Architecture

Canonical description of the **current** technical implementation of Wavdrop Android.
Code and current tests are the authority; this document is kept in step with them (see
[DOCUMENTATION_POLICY.md](DOCUMENTATION_POLICY.md)). Historical reasoning and decisions live in
[../ENGINEERING_BACKLOG_AND_DECISIONS.md](../ENGINEERING_BACKLOG_AND_DECISIONS.md); current project
state lives in [../PROJECT_CONTEXT.md](../PROJECT_CONTEXT.md).

---

## 1. Application structure

Single Gradle module (`:app`), package `com.launchpoint.wavdrop`, MVVM + Repository.

| Concern | Technology |
|---|---|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Dependency injection | Hilt (annotation processing via kapt; Room via KSP) |
| Persistence | Room (`wavdrop.db`) for library, stats, events, playlists, lyrics, preservation data |
| Settings | Preferences DataStore (`wavdrop_preferences` and per-feature repositories) |
| Playback | Media3 `ExoPlayer` + `MediaLibraryService` / `MediaLibrarySession` |
| Background work | WorkManager (periodic automatic-backup due check) with Hilt worker factory |
| Images | Coil (`SubcomposeAsyncImage` in `ArtworkImage`) |
| Build | AGP / Kotlin / Gradle wrapper versions are pinned in `gradle/libs.versions.toml` and `gradle/wrapper` |

`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`. Exact library versions are authoritative in
`gradle/libs.versions.toml`; do not copy them into prose documents.

Source layout (`app/src/main/kotlin/com/launchpoint/wavdrop/`):

```
WavdropApp.kt, MainActivity.kt
data/
  model/        domain models (Song, TrackStats, summaries, SmartCollectionType, ...)
  local/        WavdropDatabase, dao/, entity/
  repository/   SongRepository, StatsRepository (PlayEventWriter), PlaylistRepository,
                SmartCollectionRepository
  backup/       backup export/import/verification, auto-backup, quarantine, desktop interop
  legacy/       BlackPlayer .bpstat import (parse -> match -> apply)
  lyrics/       override / embedded / sidecar lyrics, LRC parser
  stats/        analytics builders (aggregate and event-backed)
  settings/     DataStore-backed settings repositories and enums
  playback/     persisted session snapshot + rules (resume)
  search/       LibrarySearchIndex and related
  smart/        SmartCollectionBuilder
  mediastore/   MediaStoreScanner
playback/       PlaybackService, PlayerController, queue/session/crossfade logic
di/             AppModule, PlaybackModule, LyricsModule, BackupWorkModule
ui/             theme, navigation, components, screens + ViewModels, widget
```

Boundaries:

- **ViewModels** expose `StateFlow`s built from repositories and pure builders; they never touch Room
  or Media3 directly.
- **Repositories** own persistence and are the only writers of their tables. The media scan
  (`SongRepository.sync`) is the single writer of `track_identities`.
- **Pure objects** (planners, rules, builders, reducers) hold decision logic so it is unit-testable
  without Android. Prefer adding logic there over inside services or composables.
- `PlayEventWriter` is the narrow interface the playback layer uses to record plays/skips
  (implemented by `StatsRepository`).

---

## 2. Database

Room database `WavdropDatabase`, `wavdrop.db`, **schema version 13**, `exportSchema = true`
(JSON schemas for 1..13 are committed in `app/schemas/`).

### Migrations (1 -> 13)

| Migration | Change |
|---|---|
| 1->2 | `track_stats` |
| 2->3 | `import_baselines` (idempotent external imports) |
| 3->4 | folder columns on `songs` |
| 4->5 | `playlists`, `playlist_songs` |
| 5->6 | `track_listen_events` (event history begins here) |
| 6->7 | `lyrics_overrides` |
| 7->8 | `lastListenedAt` on `track_stats` (Recently Played), seeded from `lastPlayedAt` |
| 8->9 | six pending (quarantine) tables for unmatched backup history |
| 9->10 | playlist provenance columns on `pending_playlist_entries` |
| 10->11 | `lastListenedAt` on `pending_track_stats` |
| 11->12 | `track_identities` + nullable `eventId` on `track_listen_events` (no backfill) |
| 12->13 | `pending_backup_extensions` (preserved extension roots) |

All migrations are additive. `WavdropDatabaseMigrationTest` (instrumentation) covers upgrades.
Migration tests and exported schemas must be updated with every schema bump.

### Entity groups

| Group | Entities | Notes |
|---|---|---|
| Library | `SongEntity` | MediaStore-synced; low-trust source reference |
| Stats / events | `TrackStatsEntity`, `TrackListenEventEntity` | aggregate counters and PLAY/SKIP event rows; `eventId` nullable (legacy rows) |
| Playlists | `PlaylistEntity`, `PlaylistSongEntity` | position-ordered; cascade delete |
| Lyrics | `LyricsOverrideEntity` | app-managed unsynced lyric overrides |
| Import baselines | `ImportBaselineEntity` | per-song, per-source baselines that make external imports idempotent |
| Identity | `TrackIdentityEntity` | device-local Wavdrop UUID; `currentSongId` is a soft reference (no FK/cascade) |
| Pending / quarantine | `PendingTrackEntity`, `PendingTrackStatsEntity`, `PendingListenEventEntity`, `PendingLyricsOverrideEntity`, `PendingImportBaselineEntity`, `PendingPlaylistEntryEntity` | snapshot-scoped archive rows for backup history that matched no local song |
| Pending extensions | `PendingBackupExtensionEntity` | raw JSON of preserved extension roots (currently the `desktopOverlay` root) |

### Identity rules

- MediaStore id, content URI, and Room song id are source references, never identity.
- `TrackIdentity` is a **device-local** foundation: minted by the scan for each live song; cleared (not
  deleted) when the song disappears; **not exported** in backups; no rematching exists yet.
- New `TrackListenEventEntity` rows receive a stable `eventId` at creation. Legacy rows keep
  `eventId = null` and are never backfilled.

---

## 3. Library and stats

- `MediaStoreScanner` (IS_MUSIC, minimum duration setting) feeds `SongRepository.sync`. A scan that
  fails or returns nothing for an existing library preserves existing songs (preserve-on-empty-scan);
  stale rows are pruned in chunks.
- `StatsTracker` records a play after a meaningful-play threshold of `min(30 s, 50 % of duration)`;
  switching away earlier records a skip. A lower 5 s listen threshold only updates `lastListenedAt`
  (Recently Played) and writes no event.
- Smart collections: 11 read-only computed types (`SmartCollectionType`), ranked over the full eligible
  set before capping so eligible counts are exact.
- Search: `LibrarySearchIndex` caches normalized fields; Home and global search filter off the main
  thread (`Dispatchers.Default`) with a 200 ms debounce.

### Analytics authority

| Source | Used for |
|---|---|
| `TrackStatsEntity` (aggregate) | all-time counters, favourites, most played all-time, listening reports |
| `TrackListenEventEntity` (events) | every time-scoped view: Monthly Reports, Wrapped, This Month, Insights |

Rules:

- Time-period analytics use events only. Aggregate stats are never used to fabricate monthly/yearly
  history; they are reachable only through explicit all-time fallback APIs.
- Aggregate imports (BlackPlayer, desktop aggregates) never write events.
- `effectiveListeningTimeMs = max(totalListeningTimeMs, playCount x durationMs)` is **display-only**:
  never stored, exported, or imported; backups carry only raw `totalListeningTimeMs`.
- Builders: `StatsDashboardBuilder`, `ListeningReportBuilder`, `ArtistInsightsBuilder`,
  `MostPlayedBuilder` (aggregate-oriented) and `ListeningAnalyticsBuilder`, `MonthlyReportBuilder`,
  `WrappedBuilder`, `InsightsSummaryBuilder` (event-backed).

### Lyrics

`LyricsRepository` precedence: user override (`lyrics_overrides`) -> embedded ID3 -> same-folder `.lrc`
sidecar -> same-folder `.txt` sidecar. `LrcParser` produces timed lines; Now Playing shows synchronized
lyrics for timed LRC content. Wavdrop never writes audio tags and never fetches lyrics over the network.

---

## 4. Playback architecture

### 4.1 Process and session ownership

```
UI -> PlayerController (app-side, @Singleton)
        -> MediaController -> MediaLibrarySession (in PlaybackService)
              -> PreviousBehaviorPlayer (ForwardingPlayer)
                    -> primary ExoPlayer
```

- `PlaybackService` is a `MediaLibraryService` (foreground type `mediaPlayback`). It owns the real
  `ExoPlayer`, the `MediaLibrarySession`, the Equalizer controller, widget state updates, audio-device
  callbacks, and the (gated) crossfade preparation runtime.
- **The primary player and its MediaSession are the only playback authority** seen by the system
  (notification, lock screen, Bluetooth, system media browse/resume clients, widget). Android Auto is
  not a supported/claimed target.
- `PreviousBehaviorPlayer` implements the Previous-button setting and intercepts `play()`: if the
  physical player is empty it hydrates the saved session first, then plays.
- The service exposes a browse tree (`WavdropMediaLibrary`: root -> recent / songs) so system
  surfaces can discover and resume playback. Resumption (`onPlaybackResumption`,
  `PlaybackResumptionMapper`) rebuilds the occurrence-safe queue from the saved session; browse item
  ids name only the song and never encode a queue occurrence.
- Task removal keeps a paused-but-resumable session alive (`TaskRemovalPlaybackPolicy`): a real Media3
  queue/item means resumable; a stale logical queue over an empty player does not.
- Widget controls route through a `MediaController` (`WidgetActionReceiver`, not exported).

### 4.2 `PlayerController` logical queue

`PlayerController` is the app-side authority for the **logical** queue. Media3 shuffle is permanently
off; shuffle is modeled in `playbackOrder`.

```
libraryQueue   : List<Song>      source positions (the un-shuffled queue)
playbackOrder  : List<Int>       indices into libraryQueue (the play order)
playbackQueue  = playbackOrder.map { libraryQueue[it] }   (what ExoPlayer holds)

currentPlaybackIndex
  -> playbackOrder[currentPlaybackIndex]  = currentLibraryIndex
```

Invariants:

- `song.id` is song identity. **A song id is not a queue-occurrence identity**: the same song can occupy
  several positions. The current occurrence is positional and generation-bound.
- `queueGeneration` identifies logical queue mutation generations and is bumped on every queue
  mutation. Deferred work (seeks during controller reconnect, queue jumps, restores) binds to an
  occurrence and must discard itself when the generation moved.
- Media3's current index is authoritative only while the physical and logical queues are aligned
  (`playerQueueNeedsSync == false`). When the physical queue is dirty the stale Media3 index must not be
  used as occurrence authority.
- `resolveCurrentPlaybackIndex` resolves the current occurrence conservatively. Falling back to a song
  id is allowed only when that id identifies **exactly one** occurrence; ambiguous duplicate ids **fail
  closed** (never first-match).
- Persistent per-occurrence UUIDs are intentionally **not** part of the architecture.
- Queue mutations are expressed as planners (`QueueMutation`, `ShuffleQueueSyncPlanner`, search/batch
  planners) and applied to ExoPlayer incrementally rather than by rebuilding the playlist.

### 4.3 Hydration, resumption, and reconnect

- **Hydration** (`ensurePlayerHydratedFromSession`, `HydrationAuthority`) rebuilds an empty player from
  the persisted session. The physical Media3 queue is the authority for "already hydrated"; external
  playback is never overwritten; the snapshot is re-checked immediately before applying. Hydration
  never decides whether playback starts. Activity startup (`PlaybackStartupCoordinator` /
  `StartupRestoreGate`), explicit PLAY, and Media3 resumption all share the primitive.
- **Automatic resume** on Bluetooth / LE Audio / wired reconnect is governed by
  `AutomaticResumeAuthority` (request tokens: explicit user transport beats a newer automatic request
  beats an older one; route loss voids the request), `BluetoothReconnectPolicy`,
  `AudioOutputReconnectClassifier`, and `HeadphoneResumeMode` settings. Request identity never replaces
  `queueGeneration`.
- **Bad media recovery** (`BadMediaRecoveryPlanner`): on a Media3 error the controller advances to the
  next valid item (or stops cleanly) and emits at most one `PlaybackUserMessage` per episode
  (`BAD_TRACK_SKIPPED`, `QUEUE_EXHAUSTED`) that Now Playing surfaces.
- **Callback ownership** (`PlaybackCallbackOwnership`): each Media3 callback declares whether it owns the
  stats transition and session persistence. See the open AUTO-transition item in
  [../TECHNICAL_DEBT_REGISTER.md](../TECHNICAL_DEBT_REGISTER.md) (TD-018).

### 4.4 Equalizer

`AudioEnhancementController` owns the platform `Equalizer` effect attached to the primary player's
audio session, driven entirely by `AudioEnhancementsRepository` (presets: flat, Wavdrop, platform,
custom bands). Every effect call is guarded; failures log and continue. The secondary crossfade path
has **no** EQ forwarding.

---

## 5. Crossfade subsystem (engineering foundation only)

Status: **foundations complete; live integration not implemented; feature disabled.**
`PlaybackService.CROSSFADE_SECONDARY_RUNTIME_ENABLED` is `false`. Nothing in the subsystem is reachable
in production, and there is no user-facing crossfade setting.

Identity of a transition is `CrossfadeTransitionKey(queueGeneration, fromPlaybackIndex,
toPlaybackIndex)` - never `song.id`. Anything uncertain fails closed.

| Slice | Component | Role |
|---|---|---|
| CF-1 | `CrossfadeTransitionRules` (`planCrossfadeTransition`, `CrossfadeGainCurve.equalPower`) | pure planning: whether/for how long a transition may crossfade |
| CF-2A | `CrossfadeCoordinator` (`reduceCrossfade`) | pure lifecycle reducer: Idle / Armed / Ready / Fading / HandoffPending; emits semantic commands |
| CF-2B1 | `CrossfadeRuntimeSnapshot` | runtime snapshot of the primary queue and occurrence binding |
| CF-2B2 | `CrossfadeSecondaryPlayer` + `SecondaryPlayerBackend` (`ExoSecondaryPlayerBackend`) | silent secondary preparation foundation with attempt-token + key ownership |
| CF-2B3 | `CrossfadePreparationRuntime` | silent preparation orchestration; plan/target validation; ownership-loss cancellation |
| CF-2C1 | `observePrimaryPosition` -> `FadeWindowObservation` (`Due(key, effectiveDurationMs, startAtPositionMs, latenessMs)`, plan-bound) | fade-window decision (lateness bounded by `MAX_FADE_START_LATENESS_MS`) |
| CF-2C2 | `CrossfadeSecondaryPlayer.start(...)` | occurrence-safe, once-only secondary start primitive with lateness-derived initial gain |
| CF-2C3 | `BeginFade(key, now, initialElapsedMs)`, `Fading(..., initialElapsedMs)`, `StartSecondary(key, initialIncomingGain)` | lateness-aware fade-begin timing contract; overflow-safe completion; clock regression cancels |
| CF-2C4 | `CrossfadePreparationRuntime.beginFadeEvent(due, nowElapsedRealtimeMs)` | plan-bound `Due` -> `BeginFade(key, now, initialElapsedMs = latenessMs)`; revalidates exact key+plan and live ownership; builds the event only (no reduction, state stays Ready, nothing started) |
| CF-2C5 | `CrossfadePreparationRuntime.executeBeginFade(due, now)` | runs the CF-2C4 bridge, reduces `BeginFade` (state -> `Fading` first), then executes exactly `StartSecondary` via `CrossfadeSecondaryPlayer.start`; start failure / re-entrant error fails closed; initial `ApplyGains` is consumed by CF-2C6 |
| CF-2C6 | `CrossfadePrimaryGainController` over `PrimaryGainBackend`; `PrimaryGainError` cancel reason | transition-key-owned primary gain: after the secondary started, `executeBeginFade` applies the coordinator's `gains.outgoing`; `RestorePrimaryGain` and `close()` restore `1f`. Ownership is claimed before the write and kept on failed apply/restore; other keys cannot change or restore it. Production backend is the local primary `ExoPlayer.volume` in `PlaybackService`; `onDestroy` closes the runtime (restoring gain) before releasing the primary player. Generic `ApplyGains` still refused |
| CF-2C7A | `SecondaryPlayerBackend.setGain`, `CrossfadeSecondaryPlayer.setGain(key, gain)` | occurrence-owned secondary dynamic gain primitive: succeeds only for the exact active key in the Started phase with a valid gain (shared `isValidCrossfadeGain`); repeated updates allowed. A backend false/exception returns false without changing ownership, phase or notifying the listener. Not called by the runtime; generic `ApplyGains` and `FadeTick` execution are still absent, no ticker exists |
| CF-2C7B | `CrossfadePreparationRuntime.executeFadeTick(key, nowElapsedRealtimeMs)`, `FadeTickExecutionResult` | dedicated execution of one caller-supplied coordinator `FadeTick`: only while Fading for the exact key; live ownership is revalidated first (loss cancels, no gain applied); the reduced state is visible before any backend runs; the coordinator `ApplyGains` pair is applied verbatim, secondary incoming first then primary outgoing; a failed write cancels with `SecondaryError`/`PrimaryGainError` (restore primary, abandon secondary), clock regression cancels with `ClockRegression`; a state changed re-entrantly is never overwritten. The terminal tick applies the final pair and rests in `HandoffPending` (`RequestHandoff` recognised, not executed). Results: Inactive / Applied / HandoffPending / Cancelled(reason). Generic `applyReduction` still refuses `ApplyGains` and `RequestHandoff`; no timing driver and no production caller |
| CF-2C7C | `CrossfadePreparationRuntime.evaluatePreparation` (audible branch) | pre-audible states (Armed/Ready) are plan-managed; audible states (Fading/HandoffPending) are lifecycle-managed. For an audible transition one fresh snapshot is taken, then: live ownership loss cancels with its exact reason and returns; explicit OFF (`CrossfadeRules.isEnabled` false) cancels with `ConfigurationDisabled` and returns; otherwise the exact state is retained with no planning, prepare, reset or gain write. Enabled-duration and current-duration changes are deferred to the next transition. `isRetainablePlan` stays Armed/Ready-only; coordinator and `executeFadeTick` unchanged; no ticker |
| CF-2C7D | `CrossfadeTimingDriver`, `CrossfadeTimingScheduler`, `CrossfadeMonotonicClock`, `MainLooperCrossfadeTimingScheduler`, `ElapsedRealtimeCrossfadeClock` | scheduling-only driver; the runtime state is the sole lifecycle (the driver tracks started/stopped/closed plus a generation token). Pulse: Idle/Armed/Ready evaluate preparation, Ready observes the primary position and a Due calls `executeBeginFade(due, clock.nowMs())`; Fading runs `evaluatePreparation` first (ownership + explicit OFF) and, if the same key is still Fading, `executeFadeTick(key, clock.nowMs())`. Cadence: 250 ms pre-fade, 50 ms fading; one pending callback, never recursive; `HandoffPending` (before or after a tick) stops scheduling. Provider/clock exceptions are contained and the loop continues. The driver computes no progress or gains and never sees song ids. Production scheduler posts and removes only its own runnables on the main looper. Not constructed anywhere in production |
| CF-2D1 | `SecondaryPlayerBackend.handoffSnapshot`, `CrossfadeSecondaryPlayer.handoffSnapshot(key)`, `SecondaryHandoffSnapshot(positionMs, durationMs)` | occurrence-owned, read-only physical observation of the started secondary for a later handoff: non-null only when not released, the key is the active key, the phase is Started and the backend validates (`validatedSecondaryHandoffSnapshot`: one item, playWhenReady, READY or BUFFERING, duration > 0, 0 <= position <= duration; nothing clamped, IDLE/ENDED null). Repeated reads allowed, no monotonic enforcement. Null or a backend exception leaves ownership, phase and listener untouched. Uses the secondary physical `duration`, never the overlap or Song duration. No runtime handoff API, no primary movement, driver unchanged |

Design rules already in force:

- The **primary player and MediaSession remain authoritative**. The secondary player does not own a
  MediaSession and does not take audio focus (`handleAudioFocus = false`, not "becoming noisy").
- The secondary path has no Equalizer forwarding; a dual-player EQ/audio-session design is unresolved.
- State is set before commands execute; ownership is invalidated before listeners are invoked
  (reentrancy-safe). Stale callbacks are no-ops via attempt tokens and keys.
- The runtime is only constructed inside `if (CROSSFADE_SECONDARY_RUNTIME_ENABLED)` and closed in
  `onDestroy`.

The secondary can start, the primary gain can be lowered/restored, and explicit FadeTicks can be executed inside the internal runtime (CF-2C5/2C6/2C7B) but the
gate stays `false` (CF-2C7C also made evaluation safe while Fading/HandoffPending). Not implemented (see the backlog for the itemised list):
production wiring of the CF-2C7D timing driver (it exists but nothing constructs or starts it), occurrence handoff/promotion,
failure recovery during overlap, interaction with seek/pause/skip/queue mutation while fading,
dual-player EQ validation, a Settings/persisted preference, production enablement, and physical device
validation.

---

## 6. Backup and preservation architecture

Authoritative documents: [WAVDROP_DATA_FORMAT_SPEC.md](WAVDROP_DATA_FORMAT_SPEC.md) (interchange
format), [BACKUP_PRESERVATION_CONTRACT.md](BACKUP_PRESERVATION_CONTRACT.md) (semantics),
[WAVDROP_IMPORT_RULES.md](WAVDROP_IMPORT_RULES.md) (import behaviour),
[WAVDROP_BACKUP_SCHEMA_V1.md](WAVDROP_BACKUP_SCHEMA_V1.md) (legacy v1).

- **Export** always writes format **v2** (`WavdropBackupExporterV2`): `formatMajor 2 / formatMinor 0`,
  `backupId`, `sourceInstallationId`, `exportedAt`, `producer`, capability arrays, `manifest`, mandatory
  `integrity` (fingerprint of the parsed model), string-typed opaque ids, `lastListenedAt`, optional
  `eventId`, platform-scoped `preferences.android`, and any preserved `desktopOverlay` extension root.
- **Parse** (`WavdropBackupParser`): accepts v1 (legacy adapter, `UNVERIFIED_LEGACY` unless a payload
  checksum exists) and v2 (integrity required); rejects duplicate JSON keys, bounded nesting depth,
  newer versions, unknown required capabilities (none are known today), integrity/manifest mismatches,
  and implausible stat magnitudes. Unknown optional capabilities are tolerated.
- **Import** (`WavdropBackupImportRepository`, transaction-bound): merge semantics only. Stats merge by
  `MAX` and are idempotent; events dedupe by `eventId` when present, else by the legacy fingerprint;
  unmatched history is routed through `QuarantinePlanner` into the pending tables; extension roots are
  stored in `pending_backup_extensions`. Matching uses `BackupSongLinkResolver` (URI -> path+title ->
  tags+duration -> tags-only); ambiguous matches stay unresolved.
- **Desktop interop**: `DesktopWavdropBackupParser` / planner / repository handle Desktop-origin backups
  (detected by `appName = "wavdrop-desktop-lab"` or `sourcePlatform = "desktop"`); Desktop string ids
  are never written into Android tables.
- **Reliability**: exports are read back and verified before success is reported
  (`BackupSaveValidator`); a failed write never replaces the previous verified file; backups are
  serialized by `BackupExecutionSerializer`.
- **Automatic backup**: `AutoBackupWorkScheduler` enqueues a unique 24-hour periodic WorkManager check
  (storage-not-low constraint); `AutoBackupWorker` calls `AutoBackupRepository.runIfDue()`, which owns the
  interval due check and the folder write. Scheduling is best-effort under Android's constraints.
  `WavdropApp` reconciles the schedule at startup and when the interval setting changes.
- **TrackIdentity is not exported**, there is no rematching engine, and restore has no separate
  "Recovery" mode or pre-restore safety snapshot yet. See the preservation contract for per-area status.

BlackPlayer EX import (`data/legacy`): parse -> match (title+artist+album) -> preview -> apply in one
transaction; delta-based and idempotent via `import_baselines`; never writes events.

---

## 7. Threading

- Compose, `PlayerController`, `PlaybackService`, and all Media3 player/session interaction are
  **main-thread confined** (the service scope is `Dispatchers.Main`). The crossfade runtime and
  secondary player are main-thread confined as well.
- Room access and file/SAF work run on `Dispatchers.IO` (e.g. `AutoBackupRepository.runIfDue`).
- CPU-heavy derivations (library search index, Home filtering, analytics builders, Insights grouping)
  run with `flowOn(Dispatchers.Default)`.
- ViewModel flows use `WhileSubscribed` so background derivation stops with the UI.

---

## 8. Testing

- Local JVM suite: `./gradlew.bat :app:testDebugUnitTest` (JUnit4). Decision logic is extracted into pure
  objects and tested with hand-written fakes; there is no Mockito or MockK. Robolectric is a
  `testImplementation` dependency and is used sparingly, only where real Android framework classes are
  needed (currently `PlaybackServiceConnectionResultTest`); `isReturnDefaultValues = true` is set for the
  JVM unit tests.
- Instrumentation tests (`app/src/androidTest`): Room migration (`WavdropDatabaseMigrationTest` using
  exported schemas), DAO retention, and clean-install preference restore. They need a device/emulator.
- Release gate: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease` must pass before closing a slice.
- Do not record test totals in architecture docs; run the complete JVM suite instead.
- Physical-device behaviour (Bluetooth, OEM launchers, background restrictions) is validated manually
  via [../QA_CHECKLIST.md](../QA_CHECKLIST.md).
