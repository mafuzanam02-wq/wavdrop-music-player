# Wavdrop Music Player - Project Context

Concise handoff/state document. For technical depth see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); for
decisions and backlog see [ENGINEERING_BACKLOG_AND_DECISIONS.md](ENGINEERING_BACKLOG_AND_DECISIONS.md).

**Implementation baseline:** post-CF-2C7B. Update this paragraph when the project state changes materially.

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
- Crossfade engineering foundations CF-1 to CF-2C7B (below).

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

**Live runtime integration is NOT implemented.** Specifically:

- `PlaybackService.CROSSFADE_SECONDARY_RUNTIME_ENABLED` is `false`; the runtime is never constructed in
  production.
- Inside the internal runtime the secondary can now start (`executeBeginFade` -> `Fading` + `StartSecondary`),
  but the production gate is `false`, so this is unreachable in the shipped app.
- The initial BeginFade now also applies the coordinator's outgoing gain to the primary (after the secondary
  started), and cancellation/failure/close restore the primary to `1f`. Primary gain is owned per
  transition key through a narrow `PrimaryGainBackend` seam; the runtime holds no Player/ExoPlayer/MediaSession.
- CF-2C7A added the secondary dynamic-gain primitive (exact-key `setGain` on a started secondary). CF-2C7B added `CrossfadePreparationRuntime.executeFadeTick(key, now)`: for an explicitly supplied tick it revalidates live ownership, reduces the coordinator `FadeTick`, and applies the coordinator gain pair (secondary incoming first, then primary outgoing); a failed write or clock regression cancels (restore primary, abandon secondary). The terminal tick applies the final pair and leaves `HandoffPending`; `RequestHandoff` is recognised but NOT executed. Nothing calls `executeFadeTick` in production: no ticker/timing driver exists, generic `ApplyGains` and `RequestHandoff` stay refused, and handoff is not implemented.
- Handoff/promotion is not implemented.
- There is no Settings / persisted crossfade preference.
- No physical crossfade validation has occurred. Crossfade is not shipped and must not appear in
  user-facing copy.

The next engineering frontier is the active-fade `evaluatePreparation` policy, then the main-thread monotonic timing driver, then handoff.

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
