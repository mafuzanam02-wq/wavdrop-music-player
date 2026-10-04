# Wavdrop Music Player - Project Context

Concise handoff/state document. For technical depth see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); for
decisions and backlog see [ENGINEERING_BACKLOG_AND_DECISIONS.md](ENGINEERING_BACKLOG_AND_DECISIONS.md).

**Implementation baseline:** post-CF-2F5. Update this paragraph when the project state changes materially.

## What is WavDrop?

An offline-first, local-first Android music player (no account, no ads, no cloud, no `INTERNET`
permission) with a distinctive listening-statistics layer (Statistics, Monthly Reports, Wrapped,
Insights), verified backup/restore that preserves a user's listening history, and BlackPlayer EX /
Wavdrop Desktop import. Naming history: `Lyra` -> `EchoVault` -> **Wavdrop** (final; do not rename).

## Build and database baseline

| Item | Value |
|---|---|
| Package | `com.launchpoint.wavdrop` |
| Version | `0.1.0-beta9`, versionCode 9 |
| minSdk / compileSdk / targetSdk | 26 / 36 / 36 |
| Room | `wavdrop.db`, schema **13** (migrations 1 -> 13, schemas in `app/schemas`) |
| Playback stack | Media3 `MediaLibraryService` + ExoPlayer (versions in `gradle/libs.versions.toml`) |
| Launcher icons | six aliases; default **Obsidian Black** (see [docs/BRANDING.md](docs/BRANDING.md)) |

## Authoritative architecture (summary)

- Single module, MVVM + Repository, Compose, Hilt, Room, DataStore, Media3, WorkManager, Coil.
- `PlaybackService` owns the real ExoPlayer and `MediaLibrarySession`; the **primary player/session is
  the only playback authority**. `PlayerController` (app side) owns the logical queue.
- Analytics: time-scoped views are event-backed only; aggregate counters feed all-time views.
- Backup: export is format v2 with mandatory integrity; v1 and Desktop backups import; unmatched history
  is preserved in pending (quarantine) tables; restore is merge-only.

## Major systems complete

- Library scan, browse, search, 11 smart collections, playlists (duplicate-safe, drag reorder).
- Playback: queue management, shuffle/repeat, sleep timer, cold-start resume and hydration, system media
  browse/resume, Bluetooth/LE Audio/wired reconnect authority, paused-session retention after task
  removal, bad-media recovery with a user message, Previous-button behaviour setting, widget, Equalizer,
  synchronized LRC lyrics.
- Statistics, Monthly Reports, Wrapped (monthly/yearly/all-time), Insights.
- Backup v2 (verified export, integrity, pending/quarantine preservation, eventId-aware dedup,
  extension-root preservation), automatic backup via WorkManager, Desktop and BlackPlayer import.
- Device-local TrackIdentity foundation (not exported, no rematching).
- Occurrence-authority hardening of the playback queue (OH-1).
- Crossfade engineering foundations CF-1 to CF-2H3G, CF-2F2, CF-2F3, CF-2F4 and CF-2F5 (below).

## Crossfade - current state

Completed, internal-only foundations:

| Slice | What it is |
|---|---|
| CF-1 | pure planning/rules |
| CF-2A | lifecycle coordinator |
| CF-2B1 | runtime snapshot + occurrence binding |
| CF-2B2 | silent secondary preparation foundation |
| CF-2B3 | silent preparation orchestration |
| CF-2C1 | fade-window decision, plan-bound `Due(key, effectiveDurationMs, startAtPositionMs, latenessMs)` |
| CF-2C2 | occurrence-safe once-only secondary start primitive |
| CF-2C3 | lateness-aware `BeginFade` timing contract |
| CF-2C4 | plan-bound `Due` -> `BeginFade` runtime bridge (event-building only) |
| CF-2C5 | `BeginFade` reduction execution + exact prepared-secondary start (`executeBeginFade`) |
| CF-2C6 | occurrence-owned primary gain application / restoration (`CrossfadePrimaryGainController`) |
| CF-2C7A | occurrence-owned secondary dynamic gain primitive (`CrossfadeSecondaryPlayer.setGain`) |
| CF-2C7B | dedicated runtime `FadeTick` execution using exact coordinator gain pairs (`executeFadeTick`) |
| CF-2C7C | active-audible `evaluatePreparation` policy (Fading/HandoffPending are never re-planned) |
| CF-2C7D | main-thread monotonic timing-driver foundation (`CrossfadeTimingDriver`; unwired at that boundary) |
| CF-2D1 | occurrence-owned secondary handoff snapshot primitive (`CrossfadeSecondaryPlayer.handoffSnapshot`) |
| CF-2D2 | exact-generation / exact-occurrence primary reconciliation primitive (`PlayerController.reconcileCrossfadePrimary`) |
| CF-2D3 | exact-key runtime handoff execution (`CrossfadePreparationRuntime.executeHandoff`, internal; called only by the internal timing driver since CF-2D4) |
| CF-2D4 | timing-driver handoff execution + lifecycle continuation (`CrossfadeTimingDriver`; unwired at the CF-2D4 boundary, composed dormantly by CF-2E1) |
| CF-2E1 | dormant production composition: runtime + reconciler adapter + timing driver constructed behind the hard gate, driver never started at that boundary (CF-2E2 later adds the explicit start policy) |
| CF-2E2 | persisted normalized crossfade duration + explicit driver start/stop policy (internal; gate still false, no UI) |
| CF-2F1 | primary playback-error crossfade recovery (`PlaybackError` cancellation bridge from the primary listener) |
| CF-2F2 | primary terminal playback-state crossfade recovery (`PrimaryPlaybackTerminated` cancel on primary `STATE_IDLE`/`STATE_ENDED`) |
| CF-2F3 | secondary terminal playback-state recovery (unexpected secondary `STATE_IDLE`/`STATE_ENDED` fails the exact attempt through the existing `SecondaryError` path) |
| CF-2F4 | authoritative MediaController-disconnection crossfade recovery (`ControllerDisconnected` cancel, notified before the controller reference is cleared) |
| CF-2F5 | primary audio-focus / route interruption crossfade recovery (Media3 `onPlayWhenReadyChanged` + `onPlaybackSuppressionReasonChanged`, existing `Pause` cancel) |
| CF-2G1 | manual (explicit) pause crossfade cancellation (`Pause` cancel before the primary pause is forwarded) |
| CF-2G2 | explicit same-track seek crossfade cancellation (`Seek` cancel from the app seek and from external-controller scrubs) |
| CF-2G3 | explicit next/previous crossfade cancellation (`ManualNavigation` cancel from app skipToNext/skipToPrevious and from external-controller next/previous, including previous restart-current) |
| CF-2H1 | explicit repeat-mode change crossfade cancellation (`RepeatChanged` cancel from app `cycleRepeatMode` and external-controller repeat changes) |
| CF-2H2 | logical shuffle-change crossfade cancellation (`ShuffleChanged` cancel from `PlayerController.toggleShuffle`, before shuffle planning and the generation bump) |
| CF-2H3A | Play Next family queue-mutation crossfade cancellation (`QueueMutation` cancel from `playNext`, `playAllNext`, `moveToPlayNext`, before the generation bump) |
| CF-2H3B | Add to Queue family queue-mutation crossfade cancellation (`QueueMutation` cancel from `addToQueue`, `addAllToQueue`, before planning and the generation bump) |
| CF-2H3C | arbitrary future queue reorder crossfade cancellation (`QueueMutation` cancel from `moveQueueItemUp`, `moveQueueItemDown`, `moveQueueItemTo`, before validation and the generation bump) |
| CF-2H3D | queue removal and bulk-clear crossfade cancellation (`QueueMutation` cancel from `removeFromQueue`, `clearEarlierQueue`, `clearUpNext`, before validation and the generation bump) |
| CF-2H3E | library deletion crossfade cancellation (`QueueMutation` cancel from `handleSongDeleted`, before the current occurrence is resolved and the deletion is routed) |
| CF-2H3F | explicit whole-queue replacement crossfade cancellation (`QueueMutation` cancel from `playSong`, `playSearchResultPreservingQueue`, `playExternalUri`, both `playFromQueue` overloads and `playFromQueueShuffled`, before validation and any logical mutation) |
| CF-2H3G | playback-resumption adoption crossfade cancellation (`QueueMutation` cancel in `PlaybackService.onPlaybackResumption` only for Ready + `isForPlayback`, immediately before `adoptPlaybackResumption`) |

**Live/audible production rollout is NOT enabled.** Specifically:

- `PlaybackService.CROSSFADE_SECONDARY_RUNTIME_ENABLED` is `false`; the runtime is never constructed in
  production.
- Inside the internal runtime the secondary can now start (`executeBeginFade` -> `Fading` + `StartSecondary`),
  but the production gate is `false`, so this is unreachable in the shipped app.
- The initial BeginFade now also applies the coordinator's outgoing gain to the primary (after the secondary
  started), and cancellation/failure/close restore the primary to `1f`. Primary gain is owned per
  transition key through a narrow `PrimaryGainBackend` seam; the runtime holds no Player/ExoPlayer/MediaSession.
- CF-2C7A added the secondary dynamic-gain primitive (exact-key `setGain` on a started secondary). CF-2C7B added `CrossfadePreparationRuntime.executeFadeTick(key, now)`: for an explicitly supplied tick it revalidates live ownership, reduces the coordinator `FadeTick`, and applies the coordinator gain pair (secondary incoming first, then primary outgoing); a failed write or clock regression cancels (restore primary, abandon secondary). The terminal tick applies the final pair and leaves `HandoffPending`; `RequestHandoff` is recognised but NOT executed. At the CF-2C7B boundary nothing called `executeFadeTick` in production (no ticker/timing driver existed yet; CF-2C7D/CF-2D4 later add the driver), generic `ApplyGains` and `RequestHandoff` stay refused, and handoff was not implemented yet.
- Internal exact-key handoff/promotion is implemented through CF-2D3/CF-2D4, but production execution remains dormant because the hard gate is false and the timing driver is only ever started by the CF-2E2 persisted-duration policy when a gated graph exists, which never happens in shipping.
- A persisted, normalized crossfade duration exists internally (CF-2E2, default 0 ms / OFF) but there is no user-facing Settings preference or UI.
- No physical crossfade validation has occurred. Crossfade is not shipped and must not appear in
  user-facing copy.

CF-2C7C: while `Fading`/`HandoffPending`, `evaluatePreparation` bypasses CF-1 re-planning; it revalidates live occurrence ownership (loss cancels and returns, no same-call re-arm), cancels on explicit crossfade OFF, and otherwise retains the exact state (enabled-duration and current-duration changes apply to the next transition). Armed/Ready planning is unchanged. At the CF-2C7C boundary no ticker existed; CF-2C7D subsequently added the internal timing driver.

CF-2C7D: `CrossfadeTimingDriver` (scheduling only) drives the runtime through injected `CrossfadeTimingScheduler` and `CrossfadeMonotonicClock` seams: a 250 ms pre-fade cadence evaluates preparation and observes the primary position (Due begins the fade with the monotonic now), a 50 ms fade cadence runs active evaluation then `executeFadeTick`, at most one callback is pending, stale callbacks are generation-guarded, (as of CF-2D4 the terminal tick runs the runtime handoff in the same pulse instead of stopping). At the CF-2C7D boundary nothing constructed it (CF-2E1 later composes it dormantly; `PlaybackService` still never starts it); no persisted duration setting existed yet (CF-2E2 later added one), handoff was not yet implemented (added by CF-2D1..CF-2D4), and the gate stays `false`.

CF-2D1: an exact-key, Started-only `CrossfadeSecondaryPlayer.handoffSnapshot(key)` reports the secondary's validated physical position and duration (`SecondaryHandoffSnapshot`). It is observational (no volume, playback, seek or ownership change); a null result or backend exception keeps ownership. At the CF-2D1 boundary the runtime did not execute handoff, the primary was not moved, and the timing driver still stopped at `HandoffPending`; CF-2D2..CF-2D4 subsequently added reconciliation and handoff execution, while the gate remains `false`.

CF-2D2: `PlayerController.reconcileCrossfadePrimary(key, snapshot)` seeks the authoritative primary to `key.toPlaybackIndex` at the secondary's physical position (then installs that exact occurrence, position and duration locally without depending on immediate MediaController propagation) only when the controller is connected, the queue generation matches, the physical queue is clean, the snapshot is valid, and both the logical and physical source index equal `fromPlaybackIndex` and the automatic next still resolves to `toPlaybackIndex`; otherwise it rejects with no seek, no queue rebuild, no song-id fallback and no deferred request. Queue generation is not bumped, playback intent and primary gain are untouched, and the secondary keeps running. At the CF-2D2 boundary nothing called it yet: the runtime did not execute handoff and the timing driver still stopped at `HandoffPending`, and the gate stays `false`.

CF-2D3: `CrossfadePreparationRuntime.executeHandoff(key)` can now explicitly execute an exact `HandoffPending(key)` inside the internal runtime: it revalidates live ownership (loss cancels with its exact reason), reads the secondary physical snapshot, hands it unchanged to an injected `CrossfadePrimaryReconciler` (production adapter: `PlayerController.reconcileCrossfadePrimary`; not yet wired at the CF-2D3 boundary, connected dormantly by CF-2E1), then restores primary gain to 1f, abandons the secondary and closes through the coordinator `HandoffSucceeded` (Idle). Snapshot/reconciliation/restore/abandon failures close through `HandoffFailed` (restore + abandon, Idle); a primary seek is never rolled back, and primary restore ownership is retained if restoration stays uncertain. Generic `RequestHandoff` remains refused. As of CF-2D4 the internal timing driver calls `executeHandoff`. At the CF-2D3 / CF-2D4 boundary the production adapter and service composition were not yet wired; CF-2E1 subsequently adds the dormant production composition while the gate remains false and the driver remains unstarted. Media3 callbacks remain the stats-transition owner; the runtime has no stats or persistence.

CF-2D4: `CrossfadeTimingDriver` now executes the runtime handoff in the same pulse as the terminal `FadeTick` (and first thing in a pulse that starts in `HandoffPending`, before any provider read). `Succeeded` resumes the 250 ms pre-fade polling for the new authoritative occurrence; ownership-loss `Cancelled` and re-entrant `Inactive` follow the resulting runtime state's ordinary cadence; a genuine `Failed` halts the automatic run (no retry, no re-arm loop) and leaves the driver restartable. At most one handoff attempt per pulse; the driver does no handoff mechanics itself. At the CF-2D4 boundary the driver was not yet constructed in production and the production reconciler adapter was not yet wired; CF-2E1 adds that dormant composition while the gate remains false.

CF-2E1 (production composition foundation, NOT enablement): `PlaybackService` can own the runtime, the `PlayerController.reconcileCrossfadePrimary` adapter (`CrossfadePrimaryReconciler`) and the `CrossfadeTimingDriver` (main-looper scheduler, elapsed-realtime clock, primary physical duration/position providers) via `createCrossfadeProductionGraph`. Three safety barriers remain: (1) `CROSSFADE_SECONDARY_RUNTIME_ENABLED` is `false`; (2) the graph is constructed only behind that gate; (3) at the CF-2E1 boundary the driver was never started even inside the gated path (CF-2E2 later added the explicit start policy). At the CF-2E1 boundary the configured-duration provider was a dormant 0 ms (Crossfade OFF under the CF-1 rules), explicitly not a default or recommended duration; CF-2E2 replaced it with the persisted duration. Teardown closes the driver, then the runtime, then releases the primary. The reconciler is therefore connected but unreachable during normal execution.

CF-2E2 (configuration + lifecycle policy, NOT rollout): `AppSettingsRepository.crossfadeDurationMs` persists one canonical unit (milliseconds, key `crossfade_duration_ms`), default 0 ms / OFF, normalized through `CrossfadeRules.normalizeDurationMs` on both read and write. `PlaybackService` caches the value (main thread) and the driver reads that synchronous cached provider. The explicit activation policy starts the driver only for an initial enabled value or an OFF -> enabled transition; enabled -> enabled changes only update the cache (a live fade keeps its plan; a halted driver is not restarted); enabled -> OFF cancels the runtime with `ConfigurationDisabled` (restore primary, abandon secondary, Idle) BEFORE stopping the driver. A persisted value is not rollout permission: the hard gate stays `false`, so no graph, driver or audible crossfade exists in shipping. No UI, no physical validation, and the preference is deliberately not part of backups yet.

CF-2F1 (first slice of failure/cancel recovery): when the authoritative primary ExoPlayer reports a playback error, `PlaybackService` synchronously cancels any owned crossfade with `PlaybackError` (Armed/Ready abandon the secondary; Fading/HandoffPending restore the primary to 1f before abandoning the secondary). The timing driver is neither stopped nor restarted, `PlayerController` bad-media recovery stays authoritative and unchanged, secondary errors keep their separate `SecondaryError` path, and the hard gate stays `false` (a no-op in shipping since no runtime exists).

CF-2G1 (first slice of manual transport interaction): every explicit pause that reaches the session player (`PreviousBehaviorPlayer.pause()`: app UI, notification, lock screen, media keys, widget, system controllers) now runs `noteExternalTransport()`, then synchronously cancels any owned crossfade with `Pause` (Armed/Ready abandon the secondary; Fading/HandoffPending restore the primary to 1f before abandoning the secondary), and only then forwards the pause to the primary. The timing driver keeps running; while paused no transition is eligible and a later resume lets a fresh pulse arm from the live occurrence. Seek, next and previous are deliberately not handled yet; the hard gate stays `false` (no runtime exists in shipping, so the callback is a no-op).

CF-2G2 (second slice of manual transport interaction): an explicit same-track position seek cancels any owned crossfade with `Seek` BEFORE the seek is applied, from two sources. App user seek: `PlayerController.seekTo(positionMs)` notifies a lifecycle-scoped explicit-seek listener (registered by `PlaybackService`, cleared in `onDestroy`) before clamping, deferring or forwarding. External system scrub: `PreviousBehaviorPlayer.seekTo(positionMs)` does the same only when the current request comes from an external user controller (`ExternalTransportPolicy`). Seeks by the app-marked controller are never cancelled at the session layer, so internal seeks (notably CF-2D2 handoff reconciliation, bad-media recovery, hydration, queue sync) stay valid. Previous-button restart is routed to the underlying player so next/previous remains a separate slice. Fading/HandoffPending restore the primary to 1f before abandoning the secondary; the driver keeps running and a later pulse plans afresh from the live position. The hard gate stays `false`.

CF-2G3 (third and final slice of manual transport interaction): an explicit NEXT or PREVIOUS command cancels any owned crossfade with `ManualNavigation` BEFORE navigation is applied or deferred, from two sources. App command: `PlayerController.skipToNext()` / `skipToPrevious()` notify a lifecycle-scoped explicit-navigation listener (registered by `PlaybackService`, cleared in `onDestroy`) first, before the bad-media episode reset, controller lookup, pending-navigation capture or `navigate(...)`; a deferred pending navigation drained on reconnect does not notify again, and a navigation that later turns out to be a no-op has still cancelled. External system command: `PreviousBehaviorPlayer.seekToNext()`, `seekToNextMediaItem()`, `seekToPrevious()` and `seekToPreviousMediaItem()` call the callback only when the request comes from an external user controller; `seekToPrevious()` cancels once at the command boundary and delegates internally via `super.*` so one external PREVIOUS yields one cancel. PREVIOUS restart-current is `ManualNavigation` (never `Seek`) and still uses `super.seekTo(0L)`. App-marked controller requests stay inert at the session layer. External NEXT/PREVIOUS still do not call `noteExternalTransport()` (resume-authority policy unchanged). Driver keeps running; no queue mutation, generation, stats or settings change. The hard gate stays `false`.

CF-2H1 (first slice of queue/shuffle/repeat mutation while fading): an explicit repeat-mode change cancels any owned crossfade with `RepeatChanged` BEFORE the new mode is applied (every explicit command, regardless of whether the planned target changes). App command: `PlayerController.cycleRepeatMode()` notifies a lifecycle-scoped explicit-repeat-change listener first (registered by `PlaybackService`, cleared in `onDestroy`); the service custom `CYCLE_REPEAT` command calls it, so one user command yields one notification. External controller (system UI, Android Auto, AVRCP): `PreviousBehaviorPlayer.setRepeatMode` notifies once, external user controllers only; app-marked requests stay inert. The runtime `crossfadeOwnershipLossReason` repeat check is kept as the defensive fallback. No queueGeneration bump, no shuffle or general queue mutation handling, driver keeps running; the hard gate stays `false`.

CF-2H2 (second slice of queue/shuffle/repeat mutation while fading): an explicit LOGICAL shuffle toggle cancels any owned crossfade with `ShuffleChanged` BEFORE shuffle planning, the `playerQueueNeedsSync` handling and `bumpQueueGeneration()`. `PlayerController.toggleShuffle()` notifies a lifecycle-scoped explicit-shuffle-change listener first (registered by `PlaybackService`, cleared in `onDestroy`), even if the toggle then no-ops (unresolved current index, null plan). The service custom `TOGGLE_SHUFFLE` command calls `toggleShuffle()`, so one command yields one notification and the service adds no second hook. Native Media3 shuffle (`onShuffleModeEnabledChanged`, reasserted off) is not logical shuffle and never cancels. `playFromQueueShuffled` sets `shuffleEnabled` and starts a fresh queue (its own queue-replacement path) and is not this slice. The existing generation bump is unchanged and `crossfadeOwnershipLossReason` keeps `QueueMutation` as the defensive fallback. Driver keeps running; the hard gate stays `false`.

CF-2H3A (first part of remaining queue mutation while fading): the Play Next family (`PlayerController.playNext(song)`, `playAllNext(songs)`, `moveToPlayNext(playbackIndex)`) cancels any owned crossfade with the existing `QueueMutation` reason BEFORE the queue-generation bump and any queue/Media3 mutation, via a lifecycle-scoped explicit-play-next-mutation listener (registered by `PlaybackService`, cleared in `onDestroy`). The notification is the first statement of each public command, so empty-queue `playSong`, unresolved-current append, `NoOp`, `StartQueue`, insert and invalid/already-next `moveToPlayNext` all cancel from user intent; internal helpers (`insertAllAfterCurrent`, `appendAllPreservingQueue`, `playFromQueue`) never notify, so one command yields one cancel. Add to Queue, arbitrary reorder and deletion are not covered. Existing generation bumps, `playerQueueNeedsSync` and Media3 sync are unchanged; the runtime generation check stays as the defensive fallback. Driver keeps running; the hard gate stays `false`.

CF-2H3B (second part of remaining queue mutation while fading): the tail-append commands `PlayerController.addToQueue(song)` and `addAllToQueue(songs)` cancel any owned crossfade with the existing `QueueMutation` reason BEFORE planning and the queue-generation bump, via a separate lifecycle-scoped explicit-add-to-queue-mutation listener (registered by `PlaybackService`, cleared in `onDestroy`). The notification is the first statement of each public command, so NoOp (empty batch), StartNewQueue and AppendPreservingQueue all cancel from user intent; even a non-last current occurrence cancels because the append bumps the generation, and a last-item Repeat ALL wrap target is replaced by the appended occurrence on later planning. The shared `appendAllPreservingQueue` (also used by the Play Next fallbacks, which own the Play Next seam) never notifies, so one command yields one cancel. Arbitrary reorder and deletion are not covered. Generation bumps, `playerQueueNeedsSync` and Media3 sync are unchanged; the runtime generation check stays as the defensive fallback. Driver keeps running; the hard gate stays `false`.

CF-2H3C (third part of remaining queue mutation while fading): the arbitrary future reorder commands `PlayerController.moveQueueItemUp(playbackIndex)`, `moveQueueItemDown(playbackIndex)` and `moveQueueItemTo(from, to)` cancel any owned crossfade with the existing `QueueMutation` reason BEFORE validation and the queue-generation bump, via a separate lifecycle-scoped explicit-queue-reorder listener (registered by `PlaybackService`, cleared in `onDestroy`). The notification is the first statement of each public command, so invalid and no-op requests still cancel from user intent, and a valid reorder that leaves the immediate next unchanged still cancels because the generation changes. The private `swapPlaybackItems` never notifies, and `moveToPlayNext` stays on the Play Next seam only (no reorder notification), so one command yields one cancel. Deletion and other paths are not covered. Generation bumps, `playerQueueNeedsSync` and Media3 `moveMediaItem` are unchanged; the runtime generation check stays as the defensive fallback. Driver keeps running; the hard gate stays `false`.

CF-2H3D (fourth part of remaining queue mutation while fading): the queue-only removal commands `PlayerController.removeFromQueue(playbackIndex)`, `clearEarlierQueue()` and `clearUpNext()` (all preserve the current occurrence) cancel any owned crossfade with the existing `QueueMutation` reason BEFORE validation, planning and the queue-generation bump, via a separate lifecycle-scoped explicit-queue-removal listener (registered by `PlaybackService`, cleared in `onDestroy`). The notification is the first statement of each public command (`clearEarlierQueue`/`clearUpNext` became block bodies, return values unchanged), so rejected requests (current item, invalid index, planner NoOp) still cancel from user intent. The shared `applyBulkClear` never notifies, so one command yields one cancel. Library deletion (`handleSongDeleted` and the current/non-current deletion paths) is NOT covered and does not use this seam. Generation bumps, `playerQueueNeedsSync` and Media3 removal are unchanged; the runtime generation check stays as the defensive fallback. Driver keeps running; the hard gate stays `false`.

CF-2H3E (fifth part of remaining queue mutation while fading): library song deletion cancels any owned crossfade with the existing `QueueMutation` reason from the single public deletion boundary `PlayerController.handleSongDeleted(songId)`, via a separate lifecycle-scoped explicit-library-deletion listener (registered by `PlaybackService`, cleared in `onDestroy`; the CF-2H3D queue-removal seam is not reused). The notification is the first statement, before `currentPlaybackIndex()` and `routeSongDeletion`, so the Unresolved, NonCurrent and Current routes all cancel once; for a current-song deletion Fading/HandoffPending restore the primary and abandon the secondary BEFORE the existing continuation or clear/stop path runs. The private deletion helpers (`applyNonCurrentSongDeletion`, `handleCurrentSongDeleted`, `applyCurrentDeletionContinuation`) never notify. Deletion planners, generation bumps, `playerQueueNeedsSync`, Media3 mutation, stats ownership and session saves are unchanged; the runtime generation check stays as the defensive fallback. Inspection found queue-replacement paths that also bump the generation and are not owned yet (see the backlog), so the parent item stays open. Driver keeps running; the hard gate stays `false`.

CF-2H3F (sixth part of remaining queue mutation while fading): explicit user playback starts that replace the active queue cancel any owned crossfade with the existing `QueueMutation` reason BEFORE validation and any logical mutation, via a separate lifecycle-scoped explicit-queue-replacement listener (registered by `PlaybackService`, cleared in `onDestroy`). Public commands that notify first: `playSong`, `playSearchResultPreservingQueue`, `playExternalUri`, `playFromQueue(queue, startSong)`, `playFromQueue(queue, startIndex)` and `playFromQueueShuffled` (notified before `shuffleEnabled` is set). The internals never notify: `playFromQueueInternal`, `playPreservedSearchPlan`, the pending-request drain (which now calls the private non-notifying `playExternalUriWithoutNotification`) and the new private `startQueueWithoutNotification`, which the Play Next empty-queue and StartQueue branches and the Add to Queue StartNewQueue branches now call, so those fallbacks keep only their own CF-2H3A/CF-2H3B seam and one command still yields one cancel. Session resumption (`adoptPlaybackResumption`) and hydration/restore are NOT covered and stay open. Generation bumps, automatic-resume supersession, stats ownership and session saves are unchanged; the runtime generation check stays as the defensive fallback. Driver keeps running; the hard gate stays `false`.

CF-2H3G (final part of queue/shuffle/repeat mutation while fading): a service-owned playback resumption cancels any owned crossfade with the existing `QueueMutation` reason directly from `PlaybackService.onPlaybackResumption`, immediately BEFORE `playerController.adoptPlaybackResumption(plan)` and the Media3 repeat/shuffle update, and ONLY for a Ready mapping with `isForPlayback == true` (`shouldCancelCrossfadeForPlaybackResumption`). This is deliberately later than the explicit-user slices: a mere query (`isForPlayback == false`, which only exposes media items), an `Unavailable` result or a mapping failure leave the old playback state authoritative and never cancel. `adoptPlaybackResumption` stays crossfade-agnostic (no listener registry) and never cancels. Hydration (`ensurePlayerHydratedFromSession`, `restoreSessionIfNeeded`, explicit-PLAY hydration) and automatic Bluetooth/wired resume are NOT changed; hydration was inspected and found unable to mutate the queue during a live crossfade (see the backlog). The runtime generation check stays as the defensive fallback. Driver keeps running; the hard gate stays `false`.

CF-2F2 (second slice of failure/cancel recovery): when the authoritative primary ExoPlayer reports a terminal playback state in `PlaybackService`'s primary listener (`onPlaybackStateChanged`), `isPrimaryTerminalPlaybackState` (`STATE_IDLE` or `STATE_ENDED`) gates a synchronous `recoverCrossfadeFromPrimaryTerminalState(runtime)` that runs the new `CrossfadeCancelReason.PrimaryPlaybackTerminated` cancellation BEFORE the asynchronous widget work (no coroutine). `STATE_BUFFERING` and `STATE_READY` never cancel. Fading/HandoffPending restore the primary to 1f before abandoning the secondary; Armed/Ready abandon the secondary; a repeated terminal callback (ended then idle, error then idle, pause then idle) finds the runtime Idle and does nothing, so the initiating CF-2F1 `PlaybackError` or CF-2G1 `Pause` reason keeps ownership. The widget IDLE behaviour, `onPlayerError`, explicit pause, the snapshot `!isPlaying -> Pause` fallback, secondary-error handling, TD-018 natural AUTO transitions, stats and persistence are unchanged. Driver keeps running; the hard gate stays `false`.

CF-2F3 (third slice of failure/cancel recovery): the real secondary backend's attempt listener previously reacted only to `STATE_READY` and `onPlayerError`, so a secondary could reach `STATE_IDLE`/`STATE_ENDED` silently. `ExoSecondaryPlayerBackend` now routes each state change through `dispatchSecondaryPlaybackState`: READY -> `onReady(attempt)`, `isSecondaryTerminalPlaybackState` (IDLE or ENDED) -> the existing `onError(attempt)` (same callback as `onPlayerError`), BUFFERING -> nothing. The existing chain then applies unchanged (`handleError` exact-attempt check -> `failTerminally` -> `onSecondaryFailed(key)` -> `SecondaryFailed` -> `SecondaryError`), so no new cancel reason, callback interface, runtime API or service wiring was added. Owner-driven reset/release detach the listener first, so they never report a false failure; stale, duplicate, superseded, abandoned and released callbacks are ignored by the attempt token. Because the secondary is reset inside `failTerminally` before the runtime is notified, the audible-state order for this failure is secondary reset then primary restore (identical to the existing secondary `onError` path). The primary CF-2F1/CF-2F2 handling, the snapshot fallback and the driver are unchanged; the hard gate stays `false`.

CF-2F4 (fourth slice of failure/cancel recovery): when the CURRENT authoritative `MediaController` disconnects, `PlayerController`'s `controllerLifecycleListener.onDisconnected` notifies a new lifecycle-scoped `ControllerDisconnectedListenerRegistry` (set via `setControllerDisconnectedListener`, registered by `PlaybackService`, cleared in `onDestroy`) after the existing `ControllerAttemptOwnership.shouldApplyDisconnect` identity guard passes and BEFORE `mediaController`, the progress player and the connection state are cleared, so an audible Fading/HandoffPending cleanup can still restore the primary gain. The service callback runs `recoverCrossfadeFromControllerDisconnected(runtime)`, i.e. the existing `CrossfadeCancelReason.ControllerDisconnected` (no new reason). A stale or superseded controller's disconnect fails the identity guard and never notifies; a repeated disconnect of the same controller finds the reference already cleared. Reconnection stays demand-driven (nothing is carried over), a failed connection attempt is unchanged, and service teardown stays with `closeCrossfadeGraph`. The snapshot `controllerConnected` check remains the defensive fallback; CF-2F1/2F2/2F3, audio-focus/noisy handling and TD-018 are unchanged. Driver keeps running; the hard gate stays `false`.

CF-2F5 (fifth and final slice of failure/cancel recovery): media3 1.11.1. The primary `Player.Listener` in `PlaybackService` now overrides `onPlayWhenReadyChanged(playWhenReady, reason)` and `onPlaybackSuppressionReasonChanged(reason)` and classifies them with pure helpers (`classifyPrimaryPlayWhenReadyInterruption`, `classifyPrimarySuppressionInterruption` -> `PrimaryPlaybackInterruption.{None,AudioFocus,AudioRoute}`). Classified: a pause (`playWhenReady == false`) with `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS` (audio focus) or `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY` (route; produced by Media3's `setHandleAudioBecomingNoisy`), and suppression `PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS` (audio focus) or `PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE` / `_UNSUITABLE_AUDIO_OUTPUT` (route). Not classified: `USER_REQUEST` (CF-2G1 owns explicit pause), `REMOTE`, `END_OF_MEDIA_ITEM`, `SUPPRESSED_TOO_LONG`, any resume, suppression `NONE` and `SCRUBBING`, and buffering/ready states. A classified signal runs `recoverCrossfadeFromPrimaryInterruption(runtime)`, i.e. the existing `CrossfadeCancelReason.Pause` (no new reason), synchronously inside the callback. Generic `onIsPlayingChanged(false)` is deliberately not a trigger and is unchanged; `AudioDeviceCallback` route removal is not a trigger (resume authority unchanged); with `pauseOnAudioDisconnect` off Media3 does not interrupt, so nothing cancels. Nothing is resurrected when focus/route returns; the snapshot `!isPlaying -> Pause` fallback remains. Driver keeps running; the hard gate stays `false`.

Failure / cancel recovery during audible overlap is complete: primary error (CF-2F1), primary terminal state (CF-2F2), secondary error and terminal state (CF-2F3), controller disconnect (CF-2F4), audio-focus/route interruption (CF-2F5), explicit pause/seek/navigation (CF-2G), queue/repeat/shuffle mutations (CF-2H), playback resumption (CF-2H3G) and service teardown (`closeCrossfadeGraph`).

The next boundaries are separate: dual-player EQ/audio-session validation, user-facing Settings UI, production enablement, and physical Bluetooth/background validation.

## In progress

Crossfade runtime integration is in progress through individually closed, reviewed slices; no
implementation slice is left partially applied on `master`.

## Invariants (do not break)

- `song.id` is song identity and **`song.id != queue occurrence identity`**.
  `libraryQueue` = source positions; `playbackOrder` = indices into `libraryQueue`;
  `playbackQueue = playbackOrder.map { libraryQueue[it] }`. The current occurrence is
  positional/generation-bound (`queueGeneration`); Media3's index is authoritative only while physical
  and logical queues are aligned.
- Song-id fallback is allowed only when the id identifies exactly one occurrence; ambiguous duplicate
  ids **fail closed** (never first-match). No persistent occurrence UUIDs.
- Crossfade transitions are identified by `CrossfadeTransitionKey(queueGeneration, fromPlaybackIndex,
  toPlaybackIndex)`, never `song.id`.
- Wrong match is worse than unresolved: no metadata-as-identity, no automatic rematch/merge of pending
  history; events are immutable facts; no backfilled `eventId`; aggregate imports never fabricate events.
- Backups never contain or modify audio files; backups are verified before success is reported.
- `effectiveListeningTimeMs` is display-only.
- Release builds must never silently use the debug key (see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)).

## Do not reopen casually

- Identity model (MediaStore id / URI / Room id are not identity) and the quarantine snapshot-scoping
  and eventId rules - see the guardrail entries in [TECHNICAL_DEBT_REGISTER.md](TECHNICAL_DEBT_REGISTER.md).
- The rejected product list in [PLANNED.md](PLANNED.md) (streaming, accounts, AI, silent deletion).
- Crossfade slice ordering: runtime integration must be built as small review-gated slices on top of
  the existing coordinator/secondary-player contracts rather than a rewrite.
- The natural-transition (AUTO) callback ownership debt (TD-018) should be handled as its own slice,
  not mixed into crossfade work unless it becomes a demonstrated blocker.

## Where things are documented

See the documentation map in [README.md](README.md) and the maintenance rules in
[docs/DOCUMENTATION_POLICY.md](docs/DOCUMENTATION_POLICY.md).
