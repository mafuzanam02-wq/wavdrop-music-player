# Wavdrop Music Player - Project Context

Concise handoff/state document. For technical depth see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); for
decisions and backlog see [ENGINEERING_BACKLOG_AND_DECISIONS.md](ENGINEERING_BACKLOG_AND_DECISIONS.md).

**Implementation baseline:** post-CF-2E2. Update this paragraph when the project state changes materially.

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
- Crossfade engineering foundations CF-1 to CF-2E2 (below).

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

The next boundaries are separate: user-facing Settings UI, manual transport and queue-mutation behaviour during overlap, dual-player EQ\/audio-session validation, production enablement and physical Bluetooth\/background validation.

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
