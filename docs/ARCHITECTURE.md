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
| CF-2C7A | `SecondaryPlayerBackend.setGain`, `CrossfadeSecondaryPlayer.setGain(key, gain)` | occurrence-owned secondary dynamic gain primitive: succeeds only for the exact active key in the Started phase with a valid gain (shared `isValidCrossfadeGain`); repeated updates allowed. A backend false/exception returns false without changing ownership, phase or notifying the listener. At the CF-2C7A boundary it was not yet called by the runtime; generic `ApplyGains`/`FadeTick` execution and a ticker were still absent. CF-2C7B..CF-2C7D subsequently added runtime FadeTick execution and the timing driver |
| CF-2C7B | `CrossfadePreparationRuntime.executeFadeTick(key, nowElapsedRealtimeMs)`, `FadeTickExecutionResult` | dedicated execution of one caller-supplied coordinator `FadeTick`: only while Fading for the exact key; live ownership is revalidated first (loss cancels, no gain applied); the reduced state is visible before any backend runs; the coordinator `ApplyGains` pair is applied verbatim, secondary incoming first then primary outgoing; a failed write cancels with `SecondaryError`/`PrimaryGainError` (restore primary, abandon secondary), clock regression cancels with `ClockRegression`; a state changed re-entrantly is never overwritten. The terminal tick applies the final pair and rests in `HandoffPending` (`RequestHandoff` recognised, not executed). Results: Inactive / Applied / HandoffPending / Cancelled(reason). Generic `applyReduction` still refuses `ApplyGains` and `RequestHandoff`; at the CF-2C7B boundary there was no timing driver or production caller (CF-2C7D later added the driver and CF-2E1 added gated production composition) |
| CF-2C7C | `CrossfadePreparationRuntime.evaluatePreparation` (audible branch) | pre-audible states (Armed/Ready) are plan-managed; audible states (Fading/HandoffPending) are lifecycle-managed. For an audible transition one fresh snapshot is taken, then: live ownership loss cancels with its exact reason and returns; explicit OFF (`CrossfadeRules.isEnabled` false) cancels with `ConfigurationDisabled` and returns; otherwise the exact state is retained with no planning, prepare, reset or gain write. Enabled-duration and current-duration changes are deferred to the next transition. `isRetainablePlan` stays Armed/Ready-only; coordinator and `executeFadeTick` unchanged; at the CF-2C7C boundary no ticker existed (CF-2C7D subsequently added the internal timing driver) |
| CF-2C7D | `CrossfadeTimingDriver`, `CrossfadeTimingScheduler`, `CrossfadeMonotonicClock`, `MainLooperCrossfadeTimingScheduler`, `ElapsedRealtimeCrossfadeClock` | scheduling-only driver; the runtime state is the sole lifecycle (the driver tracks started/stopped/closed plus a generation token). Pulse: Idle/Armed/Ready evaluate preparation, Ready observes the primary position and a Due calls `executeBeginFade(due, clock.nowMs())`; Fading runs `evaluatePreparation` first (ownership + explicit OFF) and, if the same key is still Fading, `executeFadeTick(key, clock.nowMs())`. Cadence: 250 ms pre-fade, 50 ms fading; one pending callback, never recursive; `HandoffPending` (before or after a tick) stops scheduling. Provider/clock exceptions are contained and the loop continues. The driver computes no progress or gains and never sees song ids. Production scheduler posts and removes only its own runnables on the main looper. At the CF-2C7D boundary it was not constructed in production (CF-2E1 later added gated dormant construction and CF-2E2 added the explicit activation policy). CF-2D4 update: the terminal tick (or a pulse entering in `HandoffPending`) calls `runtime.executeHandoff(key)` in the same pulse; Succeeded resumes the 250 ms poll, Cancelled/Inactive follow `nextDelayFor(runtime.state)`, a genuine Failed halts the run (`started = false`, restartable, no retry, runtime not closed); `nextDelayFor(HandoffPending)` stays null so a stranded pending state fails closed |
| CF-2D1 | `SecondaryPlayerBackend.handoffSnapshot`, `CrossfadeSecondaryPlayer.handoffSnapshot(key)`, `SecondaryHandoffSnapshot(positionMs, durationMs)` | occurrence-owned, read-only physical observation of the started secondary for a later handoff: non-null only when not released, the key is the active key, the phase is Started and the backend validates (`validatedSecondaryHandoffSnapshot`: one item, playWhenReady, READY or BUFFERING, duration > 0, 0 <= position <= duration; nothing clamped, IDLE/ENDED null). Repeated reads allowed, no monotonic enforcement. Null or a backend exception leaves ownership, phase and listener untouched. Uses the secondary physical `duration`, never the overlap or Song duration. No runtime handoff API, no primary movement, driver unchanged |
| CF-2D2 | `planCrossfadePrimaryReconciliation`, `executeCrossfadePrimaryReconciliation`, `PlayerController.reconcileCrossfadePrimary(key, snapshot)`, `CrossfadePrimaryReconciliationResult/Rejection` | pure positional planner (no song ids): rejects ControllerUnavailable, QueueGenerationChanged, PlayerQueueDirty (never resyncs the queue), InvalidSnapshot (not clamped), TargetOutOfBounds, CurrentOccurrenceMismatch (logical source), PhysicalIndexMismatch (Media3 index), TargetMismatch (from == to or automatic next drifted, e.g. repeat). Metadata duration is not compared. On a Seek plan exactly one `controller.seekTo(to, position)` is issued; an exception is `SeekFailed`. After a successful seek the exact target occurrence, secondary physical position and duration are installed into NowPlaying deterministically (`reconcileCrossfadeNowPlayingState`, no post-seek MediaController read; buffered position conservatively reset, stats deferred to CF-2D3) and the session is persisted with that exact index and position; queue structures, queue generation, playback intent and primary gain are untouched, and no callback suppression is added. No runtime handoff, secondary cleanup or driver change; at the CF-2D2 boundary it was not called in production (CF-2E1 later connected it through the gated production reconciler adapter) |
| CF-2D3 | `CrossfadePreparationRuntime.executeHandoff(key)`, `CrossfadeHandoffExecutionResult` (Inactive / Succeeded / Cancelled(reason) / Failed(`CrossfadeHandoffFailure`)), `CrossfadePrimaryReconciler` seam | exact-key handoff from `HandoffPending`: open runtime + same key else Inactive; fresh snapshot and `crossfadeOwnershipLossReason` first (loss cancels, nothing else runs); `secondary.handoffSnapshot(key)`; `primaryReconciler.reconcile(key, snapshot)` with the snapshot unchanged (default seam rejects ControllerUnavailable; exceptions contained); on physical success `primaryGain.restore(key)` BEFORE `secondary.abandon(key)` (no silence gap), then `HandoffSucceeded` (Idle, no cleanup commands, so exactly one restore and one reset). Any failure (SecondarySnapshotUnavailable, PrimaryReconciliationRejected(reason), PrimaryReconciliationException, PrimaryGainRestoreFailed, SecondaryAbandonFailed) drives the coordinator `HandoffFailed` (RestorePrimaryGain then AbandonSecondary, Idle); restore ownership is retained after a failed restore so cleanup/`close()` retry. State is re-read after every external effect and a re-entrant change wins (Inactive). No rollback seek, no stats or persistence, no queue mutation, generic `RequestHandoff` still refused, `executeFadeTick` does not call it; at the CF-2D3 boundary the timing driver did not yet execute handoff and production wiring was absent (CF-2D4 added driver handoff execution and CF-2E1 later added gated production composition) |
| CF-2E1 | `createCrossfadeProductionGraph`, `crossfadePrimaryReconciler`, `usableCrossfadePrimaryDuration`, `closeCrossfadeGraph` (`CrossfadeProductionGraph.kt`), wired in `PlaybackService.onCreate`/`onDestroy` | composition only, behind `CROSSFADE_SECONDARY_RUNTIME_ENABLED` (false). The graph builds one runtime (primary gain seam on the primary ExoPlayer, snapshot from `PlayerController.captureCrossfadeRuntimeSnapshot`, `CrossfadePrimaryReconciler` adapter delegating once and unchanged to `PlayerController.reconcileCrossfadePrimary`) and a `CrossfadeTimingDriver` over that exact runtime (`MainLooperCrossfadeTimingScheduler`, `ElapsedRealtimeCrossfadeClock`, configured duration (CF-2E1 originally supplied a dormant 0 ms provider, `DORMANT_CROSSFADE_CONFIGURED_DURATION_MS`, now removed; CF-2E2 replaced it with the service-owned persisted-duration provider), current duration = primary `duration` only when > 0 (C.TIME_UNSET and 0 are null), position = primary `currentPosition`). Three barriers: gate false; construction only behind the gate; at the CF-2E1 boundary the driver was NEVER started even inside it (no start from player/session/Bluetooth events); from CF-2E2 only the explicit persisted-duration activation policy starts it, and only when a gated graph exists. Teardown: `serviceScope.cancel()` then `closeCrossfadeGraph` (driver close, then runtime close which restores primary gain and releases the secondary) then enhancement release then primary release; fields nulled. At the CF-2E1 boundary there was no persisted crossfade setting; there is still no user-facing Settings UI, EQ forwarding, audio-focus change or session role for the secondary; the reconciler is connected but unreachable in normal execution |
| CF-2E2 | `AppSettingsRepository.crossfadeDurationMs` \/ `setCrossfadeDurationMs`, `decideCrossfadeDriverActivation`, `applyCrossfadeConfiguredDurationChange` (`CrossfadeDriverActivationPolicy.kt`), observer in `PlaybackService.onCreate` | persisted `longPreferencesKey("crossfade_duration_ms")` in milliseconds, default 0 (OFF), normalized by `CrossfadeRules.normalizeDurationMs` on read and write (never an invalid value in the Flow). `PlaybackService` collects it on `serviceScope` (`distinctUntilChanged`), caches the normalized value on the main thread and the driver provider reads that cache synchronously (no DataStore in the graph\/driver\/runtime). Policy: initial enabled or OFF -> enabled starts the driver (idempotent); enabled -> enabled only updates the cache (no restart, no cancel; a live Fading\/HandoffPending keeps its plan per CF-2C7C; a self-halted driver is not restarted by a repeated value); enabled -> OFF calls `runtime.cancel(ConfigurationDisabled)` (restore primary, abandon secondary, Idle) BEFORE `driver.stop()` (invalidates stale callbacks); null\/partial graph never starts and never throws. The policy is applied only when `CROSSFADE_SECONDARY_RUNTIME_ENABLED` (false), so shipping builds only update the cache. Service teardown still invalidates the driver first and does not use this path. The preference is not mirrored into backups (deliberately deferred) and has no UI |
| CF-2F1 | `recoverCrossfadeFromPrimaryPlaybackError(runtime)` (`CrossfadeProductionGraph.kt`), called from the primary `Player.Listener.onPlayerError` in `PlaybackService` | the authoritative primary ExoPlayer reported a real playback error: synchronously (no coroutine) and key-less `runtime.cancel(CrossfadeCancelReason.PlaybackError)`. Armed/Ready abandon the secondary; Fading/HandoffPending restore the primary to 1f BEFORE abandoning the secondary; Idle and a null runtime (gate false) are no-ops; repeated errors are idempotent. The timing driver is neither stopped nor restarted (activation stays with CF-2E2; a later pulse evaluates only the fresh positional occurrence). `PlayerController` bad-media recovery is unchanged and remains authoritative; secondary errors keep their separate `SecondaryError` path; no queue-generation, stats or song-id involvement |
| CF-2G1 | `recoverCrossfadeFromExplicitPause(runtime)` (`CrossfadeProductionGraph.kt`), injected as `onExplicitPause` into `PlaybackService.PreviousBehaviorPlayer` | explicit pause at the session player seam: `noteExternalTransport()` (unchanged), then key-less synchronous `runtime.cancel(CrossfadeCancelReason.Pause)`, then `super.pause()`. Cleanup runs before the pause is forwarded so Fading/HandoffPending can still restore the primary to 1f before the secondary is abandoned; Armed/Ready abandon the secondary; Idle and a null runtime (gate false) are no-ops; repeats are idempotent. The player stays crossfade-agnostic (callback only). The timing driver is neither stopped nor restarted (paused snapshots are ineligible, a later resume re-arms freshly). Not wired to `onIsPlayingChanged` (non-user causes); no seek/next/previous/queue-mutation handling, no stats, queue-generation or song-id involvement |
| CF-2G2 | `recoverCrossfadeFromExplicitSeek(runtime)` (`CrossfadeProductionGraph.kt`), `ExplicitSeekListenerRegistry`, `PlayerController.setExplicitSeekListener`, `onExplicitSeek` in `PlaybackService.PreviousBehaviorPlayer` | key-less synchronous `runtime.cancel(CrossfadeCancelReason.Seek)` BEFORE an explicit same-track seek. App user seek: `PlayerController.seekTo(positionMs)` notifies the listener first (before clamp, pending-seek capture or controller seek; a drained PendingSeek never cancels again); the service registers it in `onCreate` and clears it first thing in `onDestroy` (no process-global callback). External scrub: `PreviousBehaviorPlayer.seekTo(positionMs)` calls the callback only when `isExternalUserTransportRequest()` (refactored from `noteExternalTransport`; app-marked controller excluded). The app-marked controller is never cancelled at the session layer, so CF-2D2 handoff reconciliation (`seekTo(index, position)` via the app controller), bad-media recovery, hydration and queue sync remain valid. `seekToPrevious` restart now uses `super.seekTo(0L)` so previous stays outside this hook. Fading/HandoffPending restore the primary before abandoning the secondary; the driver keeps running; not wired to `onPositionDiscontinuity`/`onMediaItemTransition`; no ManualNavigation, next/previous, queue, stats, generation or song-id handling |
| CF-2G3 | `recoverCrossfadeFromExplicitNavigation(runtime)` (`CrossfadeProductionGraph.kt`), `ExplicitNavigationListenerRegistry`, `PlayerController.setExplicitNavigationListener`, `onExplicitNavigation` in `PlaybackService.PreviousBehaviorPlayer` | key-less synchronous `runtime.cancel(CrossfadeCancelReason.ManualNavigation)` BEFORE explicit next/previous. **APP NEXT/PREVIOUS:** `PlayerController.skipToNext/skipToPrevious` -> explicit-navigation listener -> cancel -> normal immediate or deferred navigation (listener runs before bad-media reset, controller lookup, pending capture; the reconnect drain never re-notifies; no-op navigation still cancels). **EXTERNAL NEXT/PREVIOUS:** MediaSession -> `PreviousBehaviorPlayer.seekToNext/seekToNextMediaItem/seekToPrevious/seekToPreviousMediaItem` -> `isExternalUserTransportRequest()` -> cancel -> existing semantics; `seekToPrevious` cancels once and delegates with `super.*` (no double cancel). **APP-MARKED INTERNAL MOVEMENT:** no session-layer cancellation. **PREVIOUS RESTART:** `ManualNavigation`, restart via `super.seekTo(0L)`, not `Seek`. Resume-authority (`noteExternalTransport`) is not extended to next/previous. Driver keeps running; not wired to AUTO transitions, bad-media recovery or queue-item jumps; no queue, stats, generation or song-id handling |
| CF-2H1 | `recoverCrossfadeFromRepeatChange(runtime)` + `REPEAT_CHANGE_CANCEL_REASON` (`CrossfadeProductionGraph.kt`), `ExplicitRepeatChangeListenerRegistry`, `PlayerController.setExplicitRepeatChangeListener`, `onExplicitRepeatChange` in `PlaybackService.PreviousBehaviorPlayer` | key-less synchronous `runtime.cancel(CrossfadeCancelReason.RepeatChanged)` BEFORE an explicit repeat-mode change. **APP:** `PlayerController.cycleRepeatMode` -> explicit-repeat-change listener -> cancel -> derive/assign repeat mode -> Media3 -> NowPlaying -> persist (the service custom `CYCLE_REPEAT` command delegates to it: one notification). **EXTERNAL:** MediaSession -> `PreviousBehaviorPlayer.setRepeatMode` -> `isExternalUserTransportRequest()` -> cancel -> forward. App-marked requests (incl. internal hydration/reconnect `controller.repeatMode` writes) inert at the session layer; `onRepeatModeChanged` is NOT a cancel point. `crossfadeOwnershipLossReason` keeps its `RepeatChanged` check as the defensive fallback. Driver keeps running; no queueGeneration, shuffle or general queue mutation handling |
| CF-2H2 | `recoverCrossfadeFromShuffleChange(runtime)` + `SHUFFLE_CHANGE_CANCEL_REASON` (`CrossfadeProductionGraph.kt`), `ExplicitShuffleChangeListenerRegistry`, `PlayerController.setExplicitShuffleChangeListener` | key-less synchronous `runtime.cancel(CrossfadeCancelReason.ShuffleChanged)` BEFORE an explicit logical shuffle toggle. **LOGICAL SHUFFLE:** UI / notification custom command (`CMD_TOGGLE_SHUFFLE`) -> `PlayerController.toggleShuffle()` -> explicit shuffle listener -> cancel(`ShuffleChanged`) -> existing shuffle planning -> `bumpQueueGeneration()` -> logical/physical reorder (cancel precedes the index resolution, so it also runs when the toggle no-ops). **NATIVE MEDIA3 SHUFFLE:** external controller shuffle=true -> `onShuffleModeEnabledChanged` -> reassert false -> logical shuffle unchanged -> no crossfade cancel. `playFromQueueShuffled` is a fresh-queue path, not covered here. Generation validation (`QueueMutation`) remains the defensive fallback; driver keeps running; no repeat, remaining queue mutation or song-id handling |
| CF-2H3A | `recoverCrossfadeFromPlayNextMutation(runtime)` + `PLAY_NEXT_MUTATION_CANCEL_REASON` (`CrossfadeProductionGraph.kt`), `ExplicitPlayNextMutationListenerRegistry`, `PlayerController.setExplicitPlayNextMutationListener` | key-less synchronous `runtime.cancel(CrossfadeCancelReason.QueueMutation)` BEFORE an explicit Play Next-family command. **PLAY NEXT:** `playNext(song)` -> notify (first statement) -> existing insertion / unresolved-current append / empty-queue `playSong`. **PLAY ALL NEXT:** `playAllNext(songs)` -> notify once -> existing planner; NoOp, StartQueue (`playFromQueue`) and InsertAfterCurrent (`insertAllAfterCurrent`, incl. its append fallback) never notify again. **MOVE TO PLAY NEXT:** `moveToPlayNext(index)` -> notify -> validation -> move. Add to Queue, arbitrary reorder, deletion and queue replacement are not covered. Generation validation (`QueueMutation`) remains the defensive fallback; driver keeps running; no song-id handling |
| CF-2H3B | `recoverCrossfadeFromAddToQueueMutation(runtime)` + `ADD_TO_QUEUE_MUTATION_CANCEL_REASON` (`CrossfadeProductionGraph.kt`), `ExplicitAddToQueueMutationListenerRegistry`, `PlayerController.setExplicitAddToQueueMutationListener` | key-less synchronous `runtime.cancel(CrossfadeCancelReason.QueueMutation)` BEFORE an explicit tail-append command. **ADD TO QUEUE / ADD ALL TO QUEUE:** `addToQueue(song)` / `addAllToQueue(songs)` -> notify once (first statement) -> `planQueueAdd` -> NoOp / StartNewQueue (`playSong`/`playFromQueue`) / AppendPreservingQueue (`appendAllPreservingQueue`). **SHARED `appendAllPreservingQueue`** never owns a notification: it is also reached from the Play Next unresolved-current fallbacks, which notify through the Play Next seam only (no Add-to-Queue notification). Arbitrary reorder, deletion and queue-item jumps are not covered. Generation validation (`QueueMutation`) remains the defensive fallback; driver keeps running; no song-id handling |

Design rules already in force:

- The **primary player and MediaSession remain authoritative**. The secondary player does not own a
  MediaSession and does not take audio focus (`handleAudioFocus = false`, not "becoming noisy").
- The secondary path has no Equalizer forwarding; a dual-player EQ/audio-session design is unresolved.
- State is set before commands execute; ownership is invalidated before listeners are invoked
  (reentrancy-safe). Stale callbacks are no-ops via attempt tokens and keys.
- The runtime is only constructed inside `if (CROSSFADE_SECONDARY_RUNTIME_ENABLED)` and closed in
  `onDestroy`.

Current state: inside the internal runtime the secondary can start, the primary gain can be lowered/restored, FadeTicks and an exact-key handoff execute, and a service-owned timing driver (CF-2C7D/2D4) can drive the whole sequence. `PlaybackService` can construct that graph behind the hard gate and starts/stops the driver from a persisted, normalized crossfade duration (CF-2E1/2E2), and a primary playback error synchronously cancels any owned crossfade (CF-2F1). The gate `CROSSFADE_SECONDARY_RUNTIME_ENABLED` stays `false`, so none of it is reachable in shipping. Not implemented (see the backlog for the itemised list): the remaining failure/cancel recovery during audible overlap, remaining queue mutation behaviour while fading, dual-player EQ validation, user-facing Settings UI (the persisted internal preference exists), production enablement, and physical device validation.

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
