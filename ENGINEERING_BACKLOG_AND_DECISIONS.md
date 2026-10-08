# ENGINEERING BACKLOG & DECISIONS

> **Wavdrop Music Player** · package `com.launchpoint.wavdrop`
> Durable decisions and engineering backlog. Last reconciled after CF-2M8 (CF-2L handoff retired).
> Current state: [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md); current
> architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## 1. Purpose

This document is a permanent, living engineering reference for Wavdrop. It consolidates — in one
place — the project's architectural decisions, deferred work, audit findings, completed milestones,
and future ideas, along with the reasoning behind them.

It exists so that:

- The reasoning behind major decisions survives long after the conversations that produced them.
- Deferred and future work is recorded deliberately, not rediscovered accidentally.
- Completed work is distinguishable from planned work, and backlog is distinguishable from vision.
- A new engineer (or the same engineer years later) can understand *why* Wavdrop is built the way
  it is, not just *what* it does.

It is **not** a place to invent new work. Every entry here is consolidated from existing project
documentation, release history, completed audits, or the code. Sources include `PROJECT_CONTEXT.md`,
`docs/BACKUP_PRESERVATION_CONTRACT.md`, `RELEASE_NOTES.md`, `PLANNED.md`, git history, and the Wave B /
Wave C audit records. This is the single backlog source: there is no separate feature-backlog file.

When work ships, it should move from the backlog/deferred sections into **§5 Completed Major
Milestones** and the relevant release notes. When a decision changes, its entry in **§4** should be
updated with the new status and reasoning — history should be amended, never erased.

---

## 2. Current Project Status

| Field | Value |
|---|---|
| Product | Wavdrop Music Player |
| Package | `com.launchpoint.wavdrop` |
| Version | 0.1.0-beta10 (versionCode 10), Beta 10.0 release checkpoint; post-Beta 10 engineering on `master` (Wave C closed: WC-01 through WC-11 resolved) |
| Phase | Soft-launch stabilization; crossfade rolled out (CF-2N2) |
| Platform | Android (min SDK 26 / compile + target SDK 36) |
| Database | Room (`wavdrop.db`), schema v13 |

### Current priorities

1. **Playback correctness and occurrence safety** — duplicate-song queues, reconnect/resume authority,
   and session hydration must be provably safe before audible features build on them.
2. **Crossfade runtime integration** — complete: promotion architecture accepted (CF-2M7), rollout signed off (CF-2N1) and enabled (CF-2N2); built from small review-gated slices on the CF-1..CF-2H3G
   foundations (see §5 and §11). User-facing in Settings → Playback; unavailable while the Equalizer is on.
3. **Preservation integrity** — listening history, statistics, playlists, and favourites must
   survive reinstall, migration, and recovery without silent loss or false attribution.
4. **Performance readiness for large libraries** - Wave C is closed (WC-01 through WC-11 resolved, §7); remaining work is the roadmap items below.

### Explicitly NOT being worked on right now

These are deliberately out of scope for the current phase (see §6 and §9 for detail):

- Portable / cross-device TrackIdentity, identity export, and cross-device reconciliation.
- Ghost and Archive lifecycle automation (12-month archival, automatic rematching).
- Streaming, cloud sync, user accounts, social/shared listening, and AI recommendations
  (permanently rejected — see §4 and `PLANNED.md` *Rejected*).
- Wrapped sharing/export.
- ID3 tag editing and online lyric fetching.
- Android Auto.
- Backup encryption / signing.
- A general user-configurable folder block/allow list (only the WhatsApp-specific toggle and the five
  preset exclusions exist).

---

## 3. Core Engineering Principles

These are recurring principles already embedded in the codebase, the preservation contract, and the
release history. They are descriptive of how Wavdrop is actually built.

- **Preservation over reconstruction.** A backup protects a user's *music life* (history, stats,
  playlists, favourites, lyrics overrides), not merely the songs currently visible in MediaStore.
  Unmatched history is retained, never silently discarded.

- **Trust over convenience.** "A wrong match is worse than an unresolved song." Ambiguous matches
  are left unresolved (PENDING) rather than attaching a user's history to the wrong track.

- **Validate before writing.** Backups are read back and verified before being reported successful;
  restores validate integrity and run an authoritative no-op recheck *inside* the transaction
  before any write; a failed write must never destroy the previous verified snapshot.

- **Prefer uncertainty over false certainty.** Legacy data without stable event IDs is reconciled
  conservatively and the limitation is disclosed; `MAX(local, backup)` is used as a fallback but is
  never presented as a mathematically exact merge of two histories.

- **A failed operation is not an empty result.** A scan that *fails* (permission revoked,
  MediaStore error) is treated differently from a scan that genuinely finds zero songs; existing
  data is preserved on ambiguous failure (preserve-on-empty-scan, Wave B WB-02).

- **Durable identity is Wavdrop-owned, not device-derived.** MediaStore ID, content URI, and Room
  song ID are low-trust *source references*, never identity. Only a Wavdrop-generated UUID is
  identity.

- **Events are immutable historical facts.** A listen event remains valid even if its audio file
  later disappears. Aggregate imports (e.g. BlackPlayer) never fabricate per-event history.

- **Non-destructive by default.** Restore is additive unless the user explicitly chooses a
  destructive mode; playlist import appends and never deletes/reorders; deleting a track keeps its
  stats and history.

- **Honest, plain-language UX.** Auto-backup wording must describe actual behaviour (open-app
  triggered, not background scheduled); internal terms ("event-backed", "aggregate") are kept out
  of user-facing copy; backups must never imply they restore deleted audio files.

- **Testability without heavyweight frameworks.** JUnit4 with hand-written fakes; no Mockito / MockK.
  Robolectric is a test dependency but is used sparingly, only where real Android framework classes are
  required. Decision logic is extracted into pure objects (planners, rules, builders, reducers) so it can
  be unit-tested without an instrumented environment.

- **Fail closed on uncertainty (playback).** Queue-occurrence identity is positional and
  generation-bound; ambiguous duplicate-song fallback never guesses (never first-match); stale
  callbacks, deferred actions, and automatic-resume requests discard themselves rather than act on a
  queue that changed underneath them.

---

## 4. Major Engineering Decisions

A catalogue of significant, durable decisions. Status reflects the current phase.

| # | Decision | Reason | Status |
|---|---|---|---|
| D-01 | **MediaStore ID is not identity** | MediaStore IDs change on rescan/reinstall and are device-local; using them as identity loses history across reinstalls | Active |
| D-02 | **Content URI is not identity** | URIs are device- and provider-specific and change; durable identity must outlive them | Active |
| D-03 | **Room song ID is not identity** | Local primary keys are not portable and are reassigned on rescan | Active |
| D-04 | **Wavdrop UUID is identity** | A Wavdrop-generated UUID is the only durable, portable primary identity (`identityUuid`) | Active (device-local foundation shipped in P2-B1) |
| D-05 | **No metadata as identity** | Metadata (title/artist/album/duration) is a *matching aid* only; collisions/ambiguity must remain unresolved, never resolved to a guess | Active |
| D-06 | **No eventId backfill for legacy events** | Fabricating IDs for historical events would invent false certainty; legacy null-eventId rows are preserved as-is and excluded from eventId integrity unless present | Active (P2-B1) |
| D-07 | **Stable eventId generated at event creation** | Per the contract, `eventId` must be generated when an event is created, never during export; enables safe cross-generation dedup | Active (P2-B1) |
| D-08 | **Pending (quarantine) is separate from TrackIdentity** | Unmatched restored history is preserved as snapshot-scoped pending rows; these are archive artefacts and must not be auto-merged or rematched to live songs until durable identity + stable event lineage exist | Active (P2-A / P2-B0) |
| D-09 | **Scan owns TrackIdentity** | The media scan is the single writer of identities and of `currentSongId`; it mints one identity per new live song and clears references for vanished songs, never deleting identities and never inferring identity from metadata/URI/path | Active (P2-B1) |
| D-10 | **TrackIdentity is not exported** | Identity is currently a device-local foundation; portable identity export is deferred to a later phase to avoid shipping a half-formed cross-device contract | Active (deferred export — see §6) |
| D-11 | **Ranking-before-cap for Smart Collections** | Collections rank the full eligible set, then cap the visible list, so `totalEligibleCount` is accurate ("N songs qualify") | Active |
| D-12 | **Preserve-on-empty-scan** | A zero-result scan with an existing library is treated as a transient failure (permission/storage/indexing), preserving songs rather than wiping them | Active |
| D-13 | **Backup integrity fingerprints the parsed model, not raw JSON** | Makes the integrity check immune to re-encoding; v2 fingerprints are tagged to never collide with v1 for the same payload | Active |
| D-14 | **Duplicate JSON keys are rejected** | No last-write-wins; a duplicate key is invalid backup input and fails parsing | Active (Backup v2) |
| D-15 | **Future-version backups fail safely** | Newer major formats and unknown *required* capabilities are rejected before apply; unknown *optional* capabilities import what is supported with a partial-restore warning | Active (Backup v2) |
| D-16 | **Opaque identifiers serialized as strings (v2)** | Avoids precision/typing ambiguity; validated as digits-only, no sign/decimal/exponent/leading-zero | Active (Backup v2) |
| D-17 | **Aggregate imports never fabricate events** | BlackPlayer (and similar) aggregate imports update counters only; they never write `track_listen_events` rows, keeping time-scoped analytics honest | Active |
| D-18 | **`effectiveListeningTimeMs` is display-only** | Derived value for display/sorting/all-time reports; never overwrites stored `totalListeningTimeMs`, never a backup/DB field, never treated as measured time | Active |
| D-19 | **Time-period analytics use events only** | Monthly/yearly/Wrapped must not infer activity from aggregate `TrackStatsEntity`; aggregate is used only via explicit all-time fallback APIs | Active |
| D-20 | **Restore mode is explicit (Recovery vs Merge)** | The user's intent (authoritative replacement vs additive merge) must be explicit; Recovery requires a verified pre-restore safety snapshot or is blocked | Implemented and physically validated: Merge Restore and explicit Recovery Restore; Recovery runs only after a verified pre-restore safety snapshot is created, otherwise it is blocked. Snapshot retention/receipts remain deferred (see §6) |
| D-21 | **Stats merge is monotonic (`MAX`) and idempotent** | Merge restore never lowers known history and re-importing the same backup is a no-op | Active |
| D-22 | **Platform-scoped preferences** | New exports write Android settings under `preferences.android`; legacy flat `preferences` is import-only backward compatibility | Active |
| D-23 | **Playback errors recover by bypassing the failing item** | On a Media3 `PlaybackException`, advance to the next valid queue item (or stop cleanly), without fabricating stats or mutating identity; one transient `PlaybackUserMessage` per episode | Active (Wave B WB-01; user message shipped post-beta9) |
| D-24 | **Song identity is not queue-occurrence identity** | The same song can occupy several queue positions; the current occurrence is positional and bound to `queueGeneration`; ambiguous song-id fallback fails closed; no persistent occurrence UUIDs | Active (OH-1 hardening) |
| D-25 | **The Media3 queue is the hydration authority** | Whether the player is "hydrated" is inferred from the physical Media3 queue (never from a stale logical queue or a boolean claim); hydration never decides autoplay; external playback is never overwritten | Active (implemented) |
| D-26 | **Crossfade is enabled through one gate; the promotion architecture is the sole implementation** | Crossfade runs only through the CF-2M promotion architecture (prepared NEXT started once, promoted at fade start, retiring A faded out and recycled) behind `CrossfadeRolloutPolicy.RUNTIME_ENABLED`, now `true` (CF-2N2) after the CF-2N1 physical rollout sign-off. Physically validated by CF-2M7; the CF-2L secondary-player natural-handoff design (the earlier D-26 wording) is RETIRED and was deleted by CF-2M8. Transitions are keyed by `CrossfadeTransitionKey`; the EQ restriction stands | Active |
| D-27 | **Explicit transport beats automatic playback** | Automatic Bluetooth/wired resume requests carry authority tokens; an explicit user play/pause or route loss voids them | Active (implemented) |
| D-28 | **UI task lifetime is separate from playback lifetime (app-kill semantics)** | Four different events, never conflated. **Recents swipe**: the process/service normally survives; `PlaybackService.onTaskRemoved` keeps the session whenever the session-facing player (the `SessionFacade`, i.e. the logical CURRENT) has a real queue/item, playing or paused, so notification/headset/widget PLAY still reaches WavDrop; an empty session takes Media3's default stop (not immortal). A prepared NEXT or a RETIRING player never counts, and the swipe never cancels a NEXT preparation or settles an overlap. **Service destruction**: `onDestroy` clears controller callbacks, unregisters the audio-device callback, cancels the scope, closes the promotion runtime and preparation driver, then releases the engine once (order pinned by test). **Process death**: no cleanup callback; later explicit PLAY/open hydrates once from the persisted session (physical Media3 queue stays the hydration authority, positional occurrence preserved); the persisted session never carries crossfade/overlap/slot state, so a recovered session rebuilds CURRENT only and prepares a fresh crossfade later. **Force-stop**: respected; no alarm/WorkManager/sticky resurrection exists or may be added. After a service recreation crossfade is blocked until the persisted EQ state is known (`crossfadeEqualizerBlocks`). Position persistence is already incremental (transitions, pause, mutations, 10 s/5 s-delta checkpoints), so worst-case process death loses a few seconds of position, never the occurrence | Active |
| D-29 | **BlackPlayer `.bpstat` field 2 is a period play count, never skips** | A `.bpstat` row is `playCount;periodPlayCount;title;artist;album;filePath;dateAddedMs;lastPlayedMs`. Only field 1 is imported (MAX-merged into `playCount`); field 2 is shown for inspection and never imported, added to the play count, converted to listening time or turned into events (D-17 intact). A `.bpstat` import never changes `skipCount` (merge passes 0 skips; the baseline's skip value is 0). Prospective only: skip values mis-imported by earlier versions are NOT decremented because their provenance cannot be reconstructed. No schema, backup or entity change | Active |
| D-30 | **WDBK backup container: ZIP/DEFLATE + compact UTF-8 JSON, streamed/chunked history** | Decided in [ADR-001](docs/architecture/adr/ADR-001-wdbk-backup-container.md): a packaging/scalability redesign only, with restore semantics frozen; V1/V2 JSON import retained; SHA-256 integrity and read-back verification retained. Protobuf, Zstd, encryption, incremental backups and portable identity are deferred | WDBK-1 implemented (container v1 + paged export + unified content-sniffing reader + verification/folder integration); **physical QA pending** (see QA_CHECKLIST). The Recovery safety snapshot intentionally stays legacy v2 JSON in WDBK-1 (not debt). WDBK-2 (normalized songs / portable model) remains out of scope |
| D-31 | **Sleep Timer: duration plus an independent "Finish current track" modifier; one exact-occurrence terminal boundary** | A duration timer (15/30/45/60/custom) optionally finishes the song that is playing WHEN THE COUNTDOWN EXPIRES (the modifier binds at expiry, not when configured, and is not a new duration). Finish OFF pauses immediately at expiry (unchanged). Finish ON arms the exact current queue occurrence (queueGeneration + playbackIndex, never a song id alone) as the terminal occurrence, ends the countdown (no second countdown; the UI says "Finishing current track"), leaves playback running and lets that occurrence end naturally. Standalone "End of current song" is kept and uses the same primitive; it simply arms immediately. The boundary outranks Repeat One, Repeat All (including last-to-first wrap) and crossfade auto-continuation without changing the saved repeat/shuffle preference: it is enforced physically by Media3 pause-at-end-of-media-items on the physical player(s) and, through the existing eligibility/cancellation seams, ends any owned NEXT preparation or overlap and blocks new ones. Explicit Pause, Next/Previous, selecting another song or replacing the queue clear an armed boundary (an expired timer never resurrects); a seek inside the same occurrence and repeat/shuffle changes do not. One authoritative `SleepTimerState` (phase IDLE/COUNTDOWN/FINISHING_CURRENT_TRACK); decisions live in the pure `SleepTimerPolicy`, `PlayerController` only executes them. The timer stays in-memory, session-lifetime only: never persisted, scheduled (no alarms/WorkManager), backed up or resurrected after process death | Active (implemented); **physical QA PASSED and CLOSED** (owner, real device; QA_CHECKLIST ST-1). Out of scope and unchanged: fade-out at sleep, volume ramping, finish playlist/album, queue-length or wall-clock bedtime timers, new Repeat modes |
| D-32 | **Library scan: five PRESET folder exclusions, default OFF, in the single scan-eligibility rule** | Library & Scanning gains an "Exclude folders" section with Telegram, Signal, Messenger, Downloads and Recordings switches. They are presets, not a general block-list, and they do not replace the WhatsApp rule (an "include" toggle, default excluded, unchanged and not migrated). All five default OFF so an upgrade removes nothing (a user may keep music in Downloads); the library only changes after the user enables one AND rescans, through the normal scan synchronization (vanished songs; stats, listen events and identity history are untouched, nothing is deleted or restored specially; turning one OFF and rescanning brings the songs back through the ordinary MediaStore scan). Model: typed `LibraryScanExclusion` set in `LibraryScanSettings.excludedPresetFolders`, persisted as one DataStore string set of stable enum names (unknown/future names ignored), mutated through `LibraryScanSettingsRepository.setPresetExclusion` (atomic read-modify-write). One path policy, `LibraryScanFolderClassifier` (pure), classifies the song's FOLDER PATH only by whole path segment after removing a shared-storage prefix: Downloads = root `Download`/`Downloads` (+descendants), Recordings = root `Recordings`/`Recorder`, Telegram = root `Telegram` or the app roots of `org.telegram.messenger`(`.web`)/`org.thunderdog.challegram` under `Android/media|data`, Signal = `org.thoughtcrime.securesms` app roots, Messenger = `com.facebook.orca`/`com.facebook.mlite` app roots; look-alike folders (`Music/My Downloads`, `Music/Live Recordings`, `Music/Telegram Tribute`, `Music/Signal Fire`, `Music/The Messengers`) and nested same-named folders are ordinary music, and a folder name alone is never classified. Precedence in `LibraryScanSettingsRules.isSongAllowedByScanSettings`: duration, then the WhatsApp rule, then the preset exclusion, then the scan mode: an explicit exclusion beats a selected folder. The scanner keeps one Kotlin filter (no path rule in the MediaStore query). The scanner returns a typed `MediaStoreScanResult(songs, eligibleBeforePresetExclusionsCount)`: the count is the songs that passed the EXISTING rules (IS_MUSIC query, minimum duration, WhatsApp rule, scan mode) immediately BEFORE the preset exclusions ran (`LibraryScanSettingsRules.evaluateScanSettings`, two stages: `isSongAllowedBeforePresetExclusions` then `isExcludedByPreset`). `SongSyncPolicy.emptyScanDisposition` is pure: a zero-song scan is DEFINITIVE (`APPLY_DEFINITIVE_EMPTY`: stale live rows removed, identities reconciled against the empty set, `Success(0)`, shown as ordinary completion) only when that count is > 0 AND an exclusion is enabled, i.e. the explicit exclusion alone emptied the library (all-Downloads library with Exclude Downloads on; a selected folder equal to an excluded folder). Every other zero (count 0, MediaStore failure, selected folder matching nothing, WhatsApp-only library, no exclusions) keeps the D-12 / WB-02 preserve-on-empty behaviour. Stats, listen events, pending data and TrackIdentity rows are never deleted by this (identity bindings clear as for any vanished song). Exclusions are device-local and NOT part of backups (like scan mode and selected folders; WDBK/legacy schema untouched) | Active (implemented); **physical QA pending** (QA_CHECKLIST SE-1). Out of scope: custom folder lists, wildcard/regex rules, per-song or metadata filtering, OEM folder databases, background rescans, history cleanup |
| D-33 | **LS-1: lock-screen / system media progress investigation: app layer HEALTHY, no app defect reproduced, no runtime timer added** | Physical report (second phone): the lock-screen progress bar SOMETIMES does not advance while audio plays. LS-1 proved, on REAL Media3 (two real ExoPlayers -> `PlayerEngine` -> `SessionFacade` -> `PreviousBehaviorPlayer` -> a real `MediaLibrarySession` -> a real `MediaController`, deterministic clock, no PlayerController ticker, no Activity), that the session-facing position tracks the physical CURRENT player in every app-layer scenario: ordinary play, pause (frozen), resume, external seek (forward and back), previous-restart, native gapless transition with crossfade off, one real promotion, FOUR consecutive promotions (A->B->C->D, both slots reused), transient focus loss (frozen, no faked advancement) and regain, an active sleep countdown, and the finish-current-track boundary (advances until the track really ends, then stops). The controller always receives a real duration (> 0, never fabricated; the production `MediaItem` carries no duration metadata, so it comes only from the player timeline). The façade does not freeze position: Media3 1.11.1 `ForwardingSimpleBasePlayer` uses live position suppliers and `getState` only overlays the logical play state, the alias playlist and the one-shot promotion discontinuity; the promotion pin is consumed by exactly one state evaluation (executable regression). A conservative event-only model of a system surface (it learns only from session-player events and extrapolates) stays on the physical position through every scenario. Mutation checks confirmed the tests CAN fail: a constant position supplier in `getState` fails 8 of 10 progress tests; leaving the promotion pin in place fails both promotion tests. Harness fact worth keeping: Media3's controller deliberately returns its cached position after the controller itself calls play/pause until the session publishes a NEWER position event; with a frozen test clock the two are the same millisecond, so tests tick the clock a few ms between a controller command and its handling (a real IPC hop does). Decision: NO periodic invalidation, NO notification/metadata/layout refresh, NO second position authority, NO UI-ticker dependency (Media3 extrapolates between events and WavDrop never disables its periodic session position updates). Added only DEBUG diagnostics: tag `WavdropSessionProgress`, one numbers-and-flags line per meaningful state change (rate limited to 250 ms) plus a 10 s heartbeat while playing (elapsed realtime, physical CURRENT position, session position, playback state, playWhenReady, isPlaying, item index, promotion active; no titles, names or paths), read-only, created only in DEBUG builds. NOT proven here: Robolectric does not expose the platform `PlaybackState` that real SystemUI reads, and no real codecs, OEM SystemUI or Bluetooth stack ran, so the intermittent freeze may still be an OEM/system-surface behaviour or a device-specific condition | Investigation complete at the app layer; **physical reproduction pending** (QA_CHECKLIST LS-1). No production behaviour changed. If physical and session positions advance while the surface freezes, the problem is downstream of WavDrop's session-facing Player; further physical evidence is required to locate the responsible MediaSession/framework/SystemUI layer (the diagnostic reads the in-process session player, not the platform controller state). If physical advances but session is stale, it is a WavDrop/session-layer divergence: capture the lines and reopen LS-1. If both are stale, investigate the actual playback/player state. No root cause is claimed. Out of scope: custom lock-screen UI, custom notification progress, Android Auto |

---

## 5. Completed Major Milestones

Summaries of major systems already shipped. Detailed user-facing notes live in `RELEASE_NOTES.md`.

- **Playback & Queue.** `PlaybackService` (`MediaLibraryService` + ExoPlayer/Media3), `@Singleton`
  `PlayerController` with queue management, shuffle, repeat (OFF/ONE/ALL), seek, and a position ticker.
  `QueueNavigator` for next/previous/restart. Per-album/artist/folder queue actions (Play, Shuffle,
  Play next, Add to queue), per-song Play next / Add to queue, queue reorder/move, and bulk cleanup.

- **Occurrence-safe playback (post-beta9).** The logical queue (`libraryQueue` / `playbackOrder` /
  `queueGeneration`) is occurrence-aware: shuffle, session restore, playlist playback, deferred seeks,
  queue jumps, batch play-next, deletion routing, and UI actions preserve the exact queue occurrence
  for duplicate songs; ambiguous fallback fails closed (D-24). Natural gapless transitions and
  shuffle-queue synchronization were hardened (`PlaybackTransitionRules`, `ShuffleQueueSyncPlanner`).

- **Resume, hydration & system integration (post-beta9).** Media3 cold playback resumption, a
  `MediaLibraryService` browse/recent tree for system media surfaces, Media3-queue hydration authority
  (D-25), explicit-PLAY hydration through the session player, paused-session retention after task
  removal, Bluetooth/LE Audio/wired reconnect authority (D-27), and a Media3 1.11.x upgrade.
  The earlier "Playback Session Hydration" design is implemented; see §13.

- **Synchronized lyrics (post-beta9).** Timed `.lrc` sidecars render as synchronized lyrics on Now
  Playing; read precedence is unchanged.

- **Automatic backup via WorkManager (post-beta9).** A unique periodic (24 h) WorkManager check calls
  `AutoBackupRepository.runIfDue()`; wording remains truthful about best-effort scheduling.

- **Crossfade foundations CF-1 .. CF-2H3G (post-beta9, internal, gated off).** (Entries naming a secondary player, HandoffPending, handoff or reconciliation describe the RETIRED CF-2L implementation, deleted by CF-2M8; kept as history.) Engineering foundation
  only - not user-facing, not enabled. Completed:
  - **CF-1** pure planning/rules (`CrossfadeTransitionRules`, equal-power gain curve).
  - **CF-2A** pure lifecycle coordinator (`reduceCrossfade`).
  - **CF-2B1** runtime snapshot + occurrence binding.
  - **CF-2B2** silent secondary-player preparation foundation (attempt-token + key ownership).
  - **CF-2B3** silent preparation orchestration (plan/target validation, ownership-loss cancellation).
  - **CF-2C1** fade-window decision: `observePrimaryPosition` -> `Due(key, effectiveDurationMs, startAtPositionMs, latenessMs)` (plan-bound).
  - **CF-2C2** occurrence-safe once-only secondary start primitive with lateness-derived initial gain.
  - **CF-2C3** lateness-aware `BeginFade(key, now, initialElapsedMs)` / `Fading` / `StartSecondary`
    contract with overflow-safe completion and clock-regression cancellation.
  - **CF-2C4** plan-bound `Due` -> `BeginFade` runtime bridge (`beginFadeEvent`): maps `latenessMs` to
    `initialElapsedMs`, rejects stale/replaced-plan Dues, revalidates live ownership; event-building only,
    state stays Ready, nothing audible.
  - **CF-2C5** `executeBeginFade`: reduces the validated `BeginFade`, enters `Fading` before the effect and
    starts the exact prepared secondary once at the coordinator gain. Start failure / re-entrant error fails
    closed.
  - **CF-2C6** occurrence-owned primary gain (`CrossfadePrimaryGainController`, `PrimaryGainBackend`): the initial
    outgoing gain is applied after the secondary starts; cancel/failure/close restore the primary to `1f`;
    failed apply/restore keep key ownership; `PlaybackService` closes the runtime before releasing the primary
    player. Gate remains `false`.
  - **CF-2C7A** occurrence-owned secondary dynamic gain primitive (`SecondaryPlayerBackend.setGain`,
    `CrossfadeSecondaryPlayer.setGain(key, gain)`): exact key, Started phase only, shared gain validation; failed
    writes keep ownership. Not called by the runtime; generic `ApplyGains` still refused, no `FadeTick`
    execution, no ticker. Gate remains `false`.
  - **CF-2C7B** dedicated runtime `FadeTick` execution (`CrossfadePreparationRuntime.executeFadeTick`): fresh
    ownership revalidation per explicit tick, exact coordinator gain pair applied (secondary incoming first, then
    primary outgoing), failures and clock regression fail closed (restore + abandon), terminal tick applies the
    final pair and rests in `HandoffPending` (`RequestHandoff` recognised, not executed). Generic `ApplyGains` and
    `RequestHandoff` still refused; no ticker/timing driver; no production caller; gate remains `false`.
  - **CF-2C7C** active-audible `evaluatePreparation` policy: Fading/HandoffPending bypass CF-1 re-planning; live
    ownership is revalidated (loss cancels and returns, no same-call re-arm), explicit OFF cancels, otherwise the
    exact state is retained (duration changes deferred to the next transition). Armed/Ready unchanged. No ticker;
    gate remains `false`.
  - **CF-2C7D** main-thread monotonic timing-driver foundation (`CrossfadeTimingDriver` with scheduler and clock
    seams): 250 ms pre-fade cadence, 50 ms fade cadence, active evaluation before every FadeTick, one pending
    callback, generation-guarded stale callbacks, stopped at `HandoffPending` until CF-2D4. Scheduling only; deliberately not
    wired into `PlaybackService`, no persisted duration setting, no handoff. Gate remains `false`.
  - **CF-2D1** occurrence-owned secondary handoff snapshot (`CrossfadeSecondaryPlayer.handoffSnapshot(key)`,
    `SecondaryHandoffSnapshot`): exact key, Started only, validated physical position + duration, read-only, failure
    retains ownership. No runtime handoff, no primary movement, driver then stopped at `HandoffPending` (superseded by CF-2D4). Gate
    remains `false`.
  - **CF-2D2** exact-generation / exact-occurrence primary reconciliation primitive
    (`PlayerController.reconcileCrossfadePrimary`, pure `planCrossfadePrimaryReconciliation`): rejects dirty queue,
    disconnected controller, generation/logical/physical source mismatch, drifted automatic target and invalid
    snapshots with no seek; otherwise seeks the primary to the target at the secondary position. No queue-generation
    change, no song-id fallback, no secondary or gain change, no runtime handoff closure. Gate remains `false`.
  - **CF-2D3** exact-key runtime handoff execution (`CrossfadePreparationRuntime.executeHandoff`, injected
    `CrossfadePrimaryReconciler`): ownership revalidation, secondary snapshot, primary reconciliation, primary gain
    restored before secondary abandonment, coordinator `HandoffSucceeded`; failures close via `HandoffFailed`; no
    rollback seek; generic `RequestHandoff` still refused; caller added by CF-2D4 (internal driver only; `PlaybackService` unwired);
    Media3 callbacks remain the stats owner. Gate remains `false`.
  - **CF-2D4** timing-driver handoff execution + continuation: the terminal `FadeTick` (or a pulse entering in
    `HandoffPending`) runs `runtime.executeHandoff` in the same pulse; success resumes 250 ms pre-fade polling,
    ownership-loss cancellation follows ordinary state cadence, a genuine failure halts the run (restartable, no
    retry loop). Driver not constructed in production at the time (CF-2E1 later adds gated, dormant construction), gate `false`.
  - **CF-2E1** dormant production construction/lifecycle wiring (production composition foundation, not enablement):
    `PlaybackService` can own the runtime, the `PlayerController` reconciler adapter and the timing driver behind the
    gate; at the CF-2E1 boundary the driver was never started and the configured duration was a dormant 0 ms / OFF provider (CF-2E2 subsequently replaced it with the persisted normalized provider and added the start/stop policy), teardown closes driver then
    runtime then primary. Gate `false`; at the CF-2E1 boundary there was no persisted setting. There is still no user-facing UI, EQ forwarding or audio-focus change.
  - **CF-2E2** persisted configuration + explicit driver start/stop policy (internal infrastructure, not enablement):
    normalized millisecond `crossfade_duration_ms` (default OFF) cached by `PlaybackService`; driver starts on initial
    enabled / OFF->enabled only, enabled->enabled updates the cache, enabled->OFF cancels the runtime with
    `ConfigurationDisabled` before stopping the driver. Hard gate `false`; no UI; not in backups yet.
  - **CF-2F1** primary playback-error cancellation bridge (internal failure-recovery foundation): the primary
    ExoPlayer listener in `PlaybackService` synchronously calls `runtime.cancel(PlaybackError)`; Fading/HandoffPending
    restore the primary before abandoning the secondary; driver not stopped/restarted; `PlayerController` bad-media
    recovery unchanged. Gate `false`.
  - **CF-2G1** explicit pause crossfade cancellation (internal manual-pause recovery foundation): `PreviousBehaviorPlayer.pause()`
    runs `noteExternalTransport()`, then `runtime.cancel(Pause)` (restore primary before abandoning secondary), then forwards
    the pause; driver keeps running; seek/next/previous not handled. Gate `false`.
  - **CF-2G2** explicit same-track seek cancellation (internal explicit-seek recovery foundation): app UI seek via a
    lifecycle-scoped `PlayerController` listener and external scrubs via `PreviousBehaviorPlayer` (external controllers
    only) run `runtime.cancel(Seek)` before the seek; app-marked controller seeks, including CF-2D2 handoff
    reconciliation, are never cancelled; driver keeps running. Gate `false`.
  - **CF-2G3** explicit next/previous cancellation (internal navigation recovery foundation): app `skipToNext`/`skipToPrevious`
    notify a lifecycle-scoped `PlayerController` listener and external controllers (only) are intercepted in
    `PreviousBehaviorPlayer`; both run `runtime.cancel(ManualNavigation)` before navigation is applied or deferred (also for
    previous restart-current, never `Seek`; one cancel per external PREVIOUS; drained pending navigation does not re-cancel;
    app-marked requests inert at the session layer). Driver keeps running; resume-authority policy unchanged. Gate `false`.
  - **CF-2H1** explicit repeat-mode change cancellation (internal repeat recovery foundation): app `cycleRepeatMode` notifies a
    lifecycle-scoped `PlayerController` listener and external controllers (only) are intercepted in
    `PreviousBehaviorPlayer.setRepeatMode`; both run `runtime.cancel(RepeatChanged)` before the new mode is applied (the
    custom `CYCLE_REPEAT` command reuses `cycleRepeatMode`; app-marked requests inert at the session layer). The runtime
    ownership-loss repeat check remains as fallback. Driver keeps running; no queue-generation change. Gate `false`.
  - **CF-2H2** logical shuffle-change cancellation (internal shuffle recovery foundation): `PlayerController.toggleShuffle()`
    (UI and the custom notification command) notifies a lifecycle-scoped listener first, running
    `runtime.cancel(ShuffleChanged)` before shuffle planning and the existing queue-generation bump, even if the toggle then
    no-ops. Native Media3 shuffle (reasserted off) never cancels; `playFromQueueShuffled` is a separate fresh-queue path.
    Generation validation (`QueueMutation`) remains the fallback. Driver keeps running. Gate `false`.
  - **CF-2H3A** Play Next family queue-mutation cancellation (internal queue recovery foundation): `playNext`, `playAllNext` and
    `moveToPlayNext` notify a lifecycle-scoped listener first (one notification per command; internal helpers never notify),
    running `runtime.cancel(QueueMutation)` before the generation bump and queue/Media3 mutation. Add to Queue, arbitrary
    reorder and deletion remain open. Generation validation stays as the fallback. Driver keeps running. Gate `false`.
  - **CF-2H3B** Add to Queue family queue-mutation cancellation (internal queue recovery foundation): `addToQueue` and
    `addAllToQueue` notify a separate lifecycle-scoped listener first (one notification per command; the shared
    `appendAllPreservingQueue` never notifies, so Play Next fallbacks never trigger it), running `runtime.cancel(QueueMutation)`
    before planning and the generation bump. Arbitrary reorder and deletion remain open. Generation validation stays as the
    fallback. Driver keeps running. Gate `false`.
  - **CF-2H3C** arbitrary future queue reorder cancellation (internal queue recovery foundation): `moveQueueItemUp`,
    `moveQueueItemDown` and `moveQueueItemTo` notify a separate lifecycle-scoped listener first (one notification per command;
    the private `swapPlaybackItems` never notifies and `moveToPlayNext` stays on the Play Next seam), running
    `runtime.cancel(QueueMutation)` before validation and the generation bump, so invalid/no-op requests also cancel.
    Deletion remains open. Generation validation stays as the fallback. Driver keeps running. Gate `false`.
  - **CF-2H3D** queue removal and bulk-clear cancellation (internal queue recovery foundation): `removeFromQueue`,
    `clearEarlierQueue` and `clearUpNext` notify a separate lifecycle-scoped listener first (one notification per command;
    `applyBulkClear` never notifies; library deletion is not covered), running `runtime.cancel(QueueMutation)` before
    validation and the generation bump, so rejected/no-op requests also cancel. Library deletion remains open. Generation
    validation stays as the fallback. Driver keeps running. Gate `false`.
  - **CF-2H3E** library deletion cancellation (internal queue recovery foundation): `handleSongDeleted` notifies a separate
    lifecycle-scoped listener first (before current-occurrence resolution and routing; Unresolved, NonCurrent and Current
    routes all cancel once; private deletion helpers never notify), running `runtime.cancel(QueueMutation)` before any
    planner, generation bump, Media3 mutation or transport change. Generation validation stays as the fallback. Driver keeps
    running. Gate `false`.
  - **CF-2H3F** explicit whole-queue replacement cancellation (internal queue recovery foundation): the public explicit
    playback starts (`playSong`, `playSearchResultPreservingQueue`, `playExternalUri`, both `playFromQueue` overloads,
    `playFromQueueShuffled`) notify a separate lifecycle-scoped listener first, running `runtime.cancel(QueueMutation)`
    before validation and any logical mutation. Internals (`playFromQueueInternal`, `playPreservedSearchPlan`, the pending
    drain, `startQueueWithoutNotification`) never notify and the CF-2H3A/B fallbacks use them, so there is no double cancel.
    Session resumption stays open. Generation validation stays as the fallback. Driver keeps running. Gate `false`.
  - **CF-2H3G** playback-resumption adoption cancellation (internal queue recovery foundation): `PlaybackService.onPlaybackResumption`
    cancels via `runtime.cancel(QueueMutation)` immediately before `adoptPlaybackResumption`, only for Ready + `isForPlayback`
    (queries, Unavailable and mapping failures never cancel; `adoptPlaybackResumption` stays crossfade-agnostic). Hydration
    inspected and found unable to mutate a live-crossfade queue. Generation validation stays as the fallback. Driver keeps
    running. Gate `false`.
  - **CF-2F2** primary terminal playback-state recovery (internal failure/cancel foundation): the primary listener's
    `onPlaybackStateChanged` synchronously runs `runtime.cancel(PrimaryPlaybackTerminated)` for `STATE_IDLE`/`STATE_ENDED` only
    (BUFFERING/READY never cancel); error, pause and the snapshot fallback keep their own reasons; driver keeps running.
    Gate `false`.
  - **CF-2F3** secondary terminal playback-state recovery (internal failure/cancel foundation): the secondary backend's
    attempt listener maps IDLE/ENDED into the existing exact-attempt `onError` path (READY stays readiness, BUFFERING stays
    allowed), reaching `SecondaryError` through the unchanged owner and runtime chain. No new reason or API. Driver keeps
    running. Gate `false`.
  - **CF-2F4** authoritative controller-disconnection recovery (internal failure/cancel foundation): `PlayerController`
    notifies a lifecycle-scoped listener after the identity guard passes and before clearing the controller reference, running
    the existing `ControllerDisconnected` cancellation; stale disconnects never notify; reconnection stays demand-driven.
    The snapshot fallback remains. Driver keeps running. Gate `false`.
  - **CF-2F5** primary audio-focus / route interruption recovery (internal failure/cancel foundation): media3 1.11.1
    `onPlayWhenReadyChanged` (AUDIO_FOCUS_LOSS, AUDIO_BECOMING_NOISY pauses) and `onPlaybackSuppressionReasonChanged`
    (TRANSIENT_AUDIO_FOCUS_LOSS, UNSUITABLE_AUDIO_ROUTE, UNSUITABLE_AUDIO_OUTPUT) are classified by pure helpers and run the
    existing `Pause` cancellation synchronously. Generic `isPlaying == false`, USER_REQUEST, buffering, suppression NONE and
    `AudioDeviceCallback` are not triggers; no resurrection on regain. The snapshot fallback remains. Driver keeps running. Gate `false`.
  - **CF-2I1** secondary audio-session observability (internal foundation, first slice of item 6): read-only exact-key
    `audioSessionSnapshot` through backend, secondary owner and runtime (valid id > 0 only), a pure primary/secondary
    relationship helper (Unavailable/Shared/Distinct) and a once-per-preparation observer with a DEBUG log. No Equalizer is
    attached to the secondary, `AudioEnhancementController` and EQ capability authority are unchanged, an absent session never
    cancels, and the session id is not occurrence identity. JVM-validated only. Driver keeps running. Gate `false`.
  - **CF-2I2** Equalizer compatibility policy (internal; decision: EQ takes precedence over crossfade): the Equalizer lives on the
    primary session only, so while EQ is enabled crossfade is unavailable (`CrossfadeUnavailableReason.EqualizerEnabled`, precedence
    after NotPlaying and before RepeatOne) and native primary playback continues. `PlaybackService` caches `eqEnabled` and composes
    it into the snapshot; an OFF->ON change cancels an owned transition via the existing `PlanInvalidated` (primary restored, then
    secondary abandoned); ON->OFF does not resurrect. No setting is mutated, no secondary Equalizer, mirroring not implemented and
    revisitable after physical validation. Driver keeps running. Gate `false`.
  - **CF-2J1** rollout-gated Crossfade Settings UI foundation (internal; first slice of item 7): the persisted
    `crossfadeDurationMs` is wired through `SettingsViewModel` to a Transitions section in Playback Settings (Off, 2-12 s radio
    dialog) behind the single rollout authority `CrossfadeRolloutPolicy.RUNTIME_ENABLED` (`false` at that time; now `true`, see CF-2N2). A pure
    presentation policy hides the row when rollout is off, and when on disables it with "Unavailable while Equalizer is on" while EQ is
    enabled (saved duration preserved). UI persists only; it never drives the runtime. Not in backups. No audible crossfade.
  - **CF-2K1** production enablement safeguards + physical QA contract (internal; first slice of item 8): a pure
    `CrossfadeRolloutReadiness` + `canEnableCrossfadeProduction` (automated gate, physical core/background/Bluetooth/wired, EQ-compatibility
    policy; all required, fail closed) and a rewritten `QA_CHECKLIST.md` section 32 (staged physical procedure, device-evidence record,
    minimum device scope, sign-off table). Contract: `CrossfadeRolloutPolicy.RUNTIME_ENABLED` flips only after the automated gate has passed and every required
    physical condition is physically verified on the intended release build/device set; no env/build/remote override. Gate `false`; nothing visible or audible.
  - **CF-2L1** natural AUTO-transition ownership / deferred primary takeover (internal correction after the first physical validation
    showed a ~1-2 s stutter at the final handoff): the secondary stays audible until the authoritative Media3 AUTO transition lands the primary
    on the exact target, READY, repositioned (same-item seek) to a FRESH secondary position; then primary gain is restored and the secondary
    abandoned. State-driven, no delays; takeover requires position continuity within tolerance (retry exhaustion never permits a stale-position takeover; a Pause carries the fresh position to a primary materially behind or ahead so resume does not rewind or skip B); stale/non-AUTO/wrong-index/cancelled facts are inert; positional identity (Repeat All wrap, duplicates).
  - **CF-2L2** continuity-qualified soft natural ownership transfer (second physical run: B no longer rewound but the instant secondary-to-primary swap
    still produced a small audible stutter): after the CF-2L1 qualification the primary must also be within 80 ms of the fresh secondary position to
    begin; a short (150 ms) internal equal-power gain envelope (single curve, secondary written first, driver-evaluated on the monotonic clock, no delays)
    hands audible authority from the secondary to the primary; divergence beyond 200 ms or a non-READY primary aborts back to the secondary; the secondary is
    silenced (exactly 0) before it is abandoned and success follows only the completed transfer. Internal, no setting, gate false, physical retest pending.
  - **CF-2L3** natural-handoff lifecycle settlement + transport/reconciliation diagnostics (device run of CF-2L2: stutter mostly resolved, but handoffs often did
    not settle and playback became intermittently unstable; the DEBUG log shows an abort-reseek loop, recorded in PROJECT_CONTEXT): settlement snapshot + invariant
    (Idle is not proof), success verified settled before return, stale callbacks inert, bounded single-callback driver, DEBUG reconcile/transport/settlement lines.
    No tolerance tuning, no debounce/suppression; the loop itself was addressed by CF-2L4.
  - **CF-2L4** monotonic projected natural-handoff position clock (fixes the demonstrated abort-reseek storm: 556 aborts vs 5 completions, 354 cycles on one
    transition, raw positions frozen between sparse updates): bounded per-stream projection fed from one shared monotonic reading, entry/abort/completion on PROJECTED
    deltas, untrusted clocks never seek below a gross mismatch, primary clock invalidated on its own seek, expiry after 500 ms, clocks part of settlement. The 80/150/200/350 ms
    values are unchanged; no retry-count or timeout takeover. Physical retest pending.
    Awaiting physical retest. Gate `false`.
  Remaining work is in §11 (Crossfade runtime integration).

- **Resume / Session.** `PlaybackSessionRepository` + `PlaybackSessionRules` persist last-played
  context for resume-on-launch, with configurable remember-last-track / remember-position /
  restore-queue behaviour and a safe fallback to Home when no session exists.

- **Statistics Engine.** Aggregate counters (`TrackStatsEntity`) and event-backed history
  (`TrackListenEventEntity`, since DB v6). Builder layer: `StatsDashboardBuilder`,
  `ListeningReportBuilder`, `ArtistInsightsBuilder`, `MostPlayedBuilder`. Display-only
  `effectiveListeningTimeMs` rule.

- **Listening Analytics Architecture.** Shared pure `ListeningAnalyticsBuilder` over
  `ListeningPeriodRange` / `ListeningPeriodSummary`, with explicit empty-state reasons. Event-only
  for time-period analytics; aggregate only via explicit all-time fallback.

- **Insights.** Dedicated Insights hub with summary cards (plays, listening time, streaks) and
  entry points to Monthly Reports and Wrapped; analytics computed off the main thread.

- **Monthly Reports.** Event-backed monthly analytics (`MonthlyReportBuilder`) with month selector,
  top songs/artists/albums, listening days, busiest day, and honest empty states.

- **Wrapped.** Monthly, Yearly, and All-Time Wrapped via `WrappedBuilder`; Story Mode auto-play
  with progress, Reduce Motion support, artwork-backed slides, and position preservation when
  returning from detail screens. Sharing/export intentionally deferred.

- **Smart Collections.** Eleven read-only computed collections (Favorites, Most Played, Recently
  Played, Forgotten Gems, Never Played, Recently Added, Most Skipped, Long Tracks, Short Tracks,
  Always Finish, Usually Abandon). Ranking-before-cap with `totalEligibleCount`; surfaced in Global
  Search.

- **Library & Browse.** MediaStore scan (`MediaStoreScanner`, IS_MUSIC + minimum duration),
  Artists/Albums/Folders browse with grouping, cross-field `LibrarySearch`, alphabet fast-scroll
  index, selected-folder vs whole-device scan modes, and large-library batched scan/prune.

- **Playlists.** Room playlists with position-based ordering and cascade delete; conservative,
  non-destructive, idempotent playlist import; duplicate-add prevention and feedback;
  drag-to-reorder with floating-preview auto-scroll.

- **Lyrics.** Read precedence: user override → embedded ID3 → `.lrc` sidecar → `.txt` sidecar.
  Editable unsynced lyric overrides stored in `lyrics_overrides`. No tag writing or online fetch.

- **Equalizer (Beta 9).** Device-supported frequency controls, system equalizer integration,
  built-in presets, persistent settings, and dynamic capability detection.

- **BlackPlayer EX Import.** Parse → match (title+artist+album) → preview → apply in a single
  transaction; MAX-merges the main play count only (`.bpstat` field 2 is a period play count, never skips); idempotent; never writes events.

- **Backup & Restore (v1 → v2).** JSON export/import for stats, favourites, playlists, lyrics
  overrides, events, baselines, and preferences. Backup Verification screen, payload integrity
  checksum, manifest validation, read-back-verified export, atomic auto-backup writes, restore
  diagnostics, and older-backup-overwrite warnings. Android ↔ Desktop portability with validated QA.

- **Backup v2 Preservation (Beta 9).** Backup format v2 (mandatory integrity, capability fields,
  string-typed ids), preservation of unmatched history via the pending (quarantine) system, reduced
  duplicate listening history, and more careful import/recovery validation. Room schema 13 adds
  preserved extension roots (`pending_backup_extensions`, the `desktopOverlay` root).
  Implementation status per area: `docs/BACKUP_PRESERVATION_CONTRACT.md`.

- **P2-B0 — Preservation foundation.** Snapshot-scoped pending/quarantine retention of unmatched
  stats, events, lyrics, baselines, and playlist entries, kept isolated and readable as archive
  data only (no cross-snapshot merge).

- **P2-B1 — Identity foundation (Beta 9, internal).** Device-local TrackIdentity with Room v12
  migration, scan-owned identity lifecycle, stable eventId generation for new playback events,
  eventId backup serialization + integrity protection, and eventId-aware import dedup — while
  preserving legacy event behaviour and Backup v2 compatibility. Validated on Samsung S21 across
  upgrade/fresh install, rescan, song removal/re-add, playback, backup export/import, duplicate
  import, and tamper detection.

- **Failure-State Hardening (Wave B).** Media3 playback error recovery (WB-01) and MediaStore scan
  exception containment with preserve-on-failure semantics (WB-02). Both implemented and verified.

- **Home, Navigation & Widget.** Compact Home dashboard with customizable sections; bottom nav
  (Home, Songs, Library, Insights; Settings from the Home gear); home-screen widget with artwork and playback controls;
  startup-destination preference; native share action; per-icon launcher selection.

---

## 6. Launch-Deferred Architecture

Work intentionally postponed past the current launch phase. Sequencing references the preservation
contract (`docs/BACKUP_PRESERVATION_CONTRACT.md`, sequencing section: P1 trust/restore semantics ->
P2 preservation capability -> P3 full music-memory architecture).

| Item | Why deferred | Dependencies | Suggested phase |
|---|---|---|---|
| **Portable TrackIdentity** | Current identity is a device-local foundation (P2-B1); a portable contract must not ship half-formed | Stable event lineage, `sourceInstallationId`, portable key design | P3 |
| **Identity export in backups** | Avoid committing to a cross-device identity format before it is proven | Portable TrackIdentity | P3 |
| **Cross-device reconciliation / rematching** | Conservative matching hierarchy must be in place; a wrong match is worse than an unresolved song | Portable identity, post-scan rematch engine | P3 |
| **Ghost lifecycle** | LIVE→GHOST transition and reconnection require durable identity and rematch | Portable identity, rematching | P3 |
| **Archive lifecycle (12-month archival)** | ARCHIVED is a visibility/storage state; needs identity + retention tooling and Storage Management UI | Portable identity, pending retention | P3 |
| **Permanent deletion (deliberate purge flow)** | Must be user-initiated with full disclosure of what is removed; no automatic PURGED transition | Archive/Storage Management UI | P3 |
| **Event-led analytics reconciliation** | Aggregates remain the fast path until complete event coverage exists | Complete event coverage, stable eventIds | P3 |
| **Streaming import/export** | Memory-bounded large-history backups need a streaming codec | — | P3 |
| **Optional encrypted / signed backups** | Integrity checksum protects against accidental corruption only; encryption is a separate feature | Backup v2 stable | P3 |
| **Recovery Restore (authoritative)** | IMPLEMENTED and physically validated (post-Beta 10): explicit mode, VERIFIED v2 only, mandatory verified app-private safety snapshot, single Room transaction, honest Room/DataStore boundary (PartialRecovery, no compensating rollback). See docs/BACKUP_PRESERVATION_CONTRACT.md 8.1 | - | Done |
| **Snapshot retention and backup receipts** | Only DATED / REPLACE_PREVIOUS file modes exist and ONE latest pre-Recovery snapshot (a second Recovery replaces it); no retained-N snapshot policy or persisted receipts | Retention policy design | Post-launch |
| **Equalizer intent in backups** | EQ settings are not part of `preferences.android` today | Backup preference capability review | Future |
| **Desktop portable import of baselines / lyrics / `preferences.android`** | Beyond current safe Android-side behaviour | Shared cross-platform validation library | Future |
| **Portable song key / acoustic fingerprinting** | Conservative matching aid; risk of false attribution if rushed | Identity model | Future |

---

## 7. Performance Backlog

Populated from the Wave C large-library performance audit. Target library sizes: 1k / 5k / 10k / 25k+
tracks. Status was re-checked against code after CF-2C3.

| ID | Status |
|---|---|
| WC-01 | **Resolved** - Home search filters off-main (`Dispatchers.Default`) with a 200 ms debounce over a cached index |
| WC-02 | **Resolved** - no production screen or ViewModel subscribes to the full `track_listen_events` entity history any more. Home and Songs were decoupled earlier; Monthly Reports, Wrapped, Insights, Statistics, Most Played details and Diagnostics now use a COUNT, PLAY+SKIP / PLAY timestamps, or one selected period range (`listenEventsInRange`). `allListenEvents()` / `observeAll()` remain only as documented full-history entity primitives with no production caller. Insights still groups full-history PLAY timestamps by day/hour in memory until WC-09 |
| WC-03 | **Resolved** - `LibrarySearchIndex` precomputes normalized fields once per library change |
| WC-05 | **Resolved** - Home Wrapped preview is bounded to the latest activity year AND no longer builds a WrappedSummary or reads the year's event rows: one small aggregate (`observeHomeWrappedActivity`: one row per live active song with PLAY/SKIP counts + the all-event PLAY total, orphan PLAYs included) feeds `HomeWrappedPreviewBuilder`, which reproduces exactly what the card shows (matched-live-activity gate, total, top artist, top track). The full Wrapped screen is unchanged |
| WC-04 | **Resolved** - Home Recently/Most Played previews are ranked and limited in SQLite (`observeRecentlyListenedPreview` / `observeMostPlayedPreview`, `LIMIT 4`); Home no longer reads or sorts the full stats table. The queries `INNER JOIN songs` BEFORE the LIMIT, so orphan stats (history survives deletion) never consume a slot; ties break by `songId ASC` explicitly (the old Kotlin tie order was only incidental). Recently Played uses `lastListenedAt`, not `lastPlayedAt`. No schema change, no new index. `getMostPlayed()` / `getRecentlyPlayed()` / `allTrackStatsEntities()` are unchanged for other consumers |
| WC-06 | **Resolved** - the Smart Collections pipeline is dependency-aware. `SmartCollectionBuilder.dependencyOf` classifies every type (songs-only: Recently Added/Long/Short; stats: Favorites/Most Played/Recently Played/Never Played/Most Skipped; stats+time: Forgotten Gems; completion: Always Finish/Usually Abandon). `SmartCollectionsAssembler` re-evaluates only the affected families (completion change -> 2 types, day tick -> Forgotten Gems only, stats -> 6, library -> all 11) over live-only maps prepared once per emission; `observeSongResultForCollection(type)` subscribes only to the inputs its rules read. Membership, ranking, caps and order unchanged. Not changed: the completion-summary `GROUP BY` still re-executes when Room invalidates it (no persisted/incremental aggregate), a stats change still re-evaluates all 6 stats-dependent types, and each collector still builds its own pipeline (no shared application scope) |
| WC-07 | **Resolved (architecture)** - the shared `ArtworkImage` no longer uses `SubcomposeAsyncImage` or `BoxWithConstraints`. List/grid/header artwork is a non-subcomposing Coil `AsyncImage` over an always-present placeholder, keyed by request identity (`ArtworkRequestKey`: URI + target) so a recycled row never draws the previous cover; fixed-size call sites pass `artworkSize` and get a decode bounded to their pixel size (`ArtworkSizing`), and every request is built by `ArtworkRequestFactory`. Remaining direct `AsyncImage` users: two full-screen Wrapped backgrounds (not repeated rows). The Home widget keeps its own bounded off-main decode |
| WC-07 / Now Playing artwork | **Resolved - physical validation PASSED (owner, real device)** - the large Now Playing artwork now uses a request-keyed loader: retained cover ownership is request-scoped (`ArtworkSurface` reducer, late results from superseded requests are ignored), a definitive failure clears the previous cover, same URI+size (same-album tracks) is a no-op, the decode target is the measured surface (bucketed, capped at 1600px), software bitmaps, and one bounded retry so a single transient provider failure no longer sticks for the whole track. The owner has since validated the change on hardware; the Now Playing artwork reliability item is closed. The media notification does not use this path (see decision log) |
| WC-08 | **Resolved** - `sync()` no longer rewrites the whole songs table. `SongSyncPlanner` (pure) diffs the persisted rows against the scan by full `SongEntity` equality and `applySongSyncPlan` writes only new + changed rows and deletes only stale ids, in one Room transaction (upsert -> playlist remap -> stale delete -> TrackIdentity reconcile). An unchanged scan performs zero song upserts/deletes/identity writes (verified with SQLite `total_changes()`), identity reconciliation still runs every scan, only genuinely new ids are playlist-remap candidates, empty/failed-scan preservation is unchanged. Limitation: MediaStore is still fully scanned on every launch; this removes Room write churn (and the resulting library re-emissions), not the scan itself |
| WC-09 | **Resolved (post-Beta 10)** - Insights and Statistics no longer materialize the full PLAY timestamp history. A streamed cursor over the PLAY rows (`playTimestampCursor`, no ORDER BY) is reduced row-by-row by `InsightsPlayActivityAccumulator` into 7 weekday + 24 hour buckets and at most 366 current-year dates (`InsightsPlayActivity`), grouped through the supplied ZoneId in Kotlin (no SQLite strftime/localtime), so DST/half-hour offsets match the old implementation. The most-active day/hour tie rule is now explicit: highest count, then latest PLAY. Recomputes on any event-table change (COUNT invalidation, so restored historical events are seen) and Insights no longer subscribes to all analytics timestamps for its month-rollover trigger. No schema change or index (query plan is a table scan). Cost remains O(N) CPU over PLAY rows; memory is now bounded |
| WC-10 | **Resolved (post-Beta 10)** - `MusicTextNormalizer.normalizeTolerant` uses a reduced-pass implementation: suffix strip (regex, only when a closing bracket exists), one NFD, one builder pass (drops `Mn` (non-spacing) marks and the five apostrophe variants, maps `_` to a space), one in-place pass for the spaced-dash separator rule plus whitespace collapse, Unicode-aware `trim()`, one `lowercase(Locale.ROOT)`. The old per-stage `replace` chain (combining-mark regex, four apostrophe replaces, underscore replace, dash regex, final whitespace regex) is gone. Output is unchanged, proven against the legacy implementation kept in test code (exhaustive BMP, curated corpus, 60,000 fixed-seed random strings). NFD (not NFKD), Locale.ROOT lowercasing and suffix/dash semantics are unchanged; `normalizeStrict` keeps its output and shares a non-regex whitespace collapse. Per-call cost only: how often keys are computed stays with WC-03 (`LibrarySearchIndex`). No global normalization cache, no schema change. |
| WC-11 | **Resolved** - Home builds the `songsById` lookup once per song-library emission (`HomeLibraryProjection`, shared StateFlow) instead of on every dashboard input; playlist, stats-preview, Smart Collection and Wrapped emissions reuse it |

Descriptions below are the original audit observations; rows marked Resolved above are retained for
history.

### High

| ID | Description | Reason | Expected benefit | Suggested timing | Complexity |
|---|---|---|---|---|---|
| WC-01 | Home `uiState` filters the full library on the **main thread** per keystroke (no debounce, no `flowOn(Default)`) using the expensive `MusicTextNormalizer` | ~3×N NFD+regex normalizations per keystroke on the UI thread; ANR risk at 25k+ | Removes keystroke lag / dropped frames during Home search | Before broad soft-launch | Low–Medium |
| WC-02 | `allListenEvents()` (`observeAll`) loads the entire events table and re-emits on every insert; wired into the main Songs list, Wrapped preview, and Insights | Songs list re-derives on every play; memory/CPU scales with full history | Decouples Songs list from event churn; lower memory | Now (decouple) / Later (range queries) | Medium |
| WC-03 | No cached/precomputed normalized search index; raw fields re-normalized every filter pass | Compounds WC-01 and raises grouped-search cost | Search cost stops scaling per-keystroke with library size | Later | Medium |

### Medium

| ID | Description | Reason | Expected benefit | Suggested timing | Complexity |
|---|---|---|---|---|---|
| WC-04 | Home dashboard sorts the entire stats list to `take(4)` for Recently/Most Played, despite existing LIMIT DAO queries | Two full O(N log N) sorts per emission | Cheaper dashboard updates | Later | Low |
| WC-05 | `wrappedPreview` builds a full-year Wrapped on Home whenever events change | Full year computation for a preview card on each event | Lower background CPU; faster Home card | Later | Medium |
| WC-06 | `SmartCollectionBuilder.build` rebuilds all 11 collections (filter+sort full list ×11) on every stats/completion change; completion summary GROUP BY re-emits per event | Sustained background CPU/GC at large sizes | Reduced rebuild cost; smoother Home | Later | Medium |
| WC-07 | Artwork uses `SubcomposeAsyncImage` (+`BoxWithConstraints`) with no request size in large lists | Subcomposition per cell adds scroll-frame cost | Smoother large-list scrolling | Later | Low–Medium |
| WC-08 | `sync()` upserts the entire song table every launch regardless of change | Full-table write churn on each cold start | Faster startup; fewer disk writes | Later | Medium |
| WC-09 | Insights groups all PLAY events by day/hour in memory | Full-history in-memory grouping (off-main, bounded by subscription) | Faster Insights load on large histories | Later | Medium |

### Low

| ID | Description | Reason | Expected benefit | Suggested timing | Complexity |
|---|---|---|---|---|---|
| WC-10 | `normalizeTolerant` allocates multiple intermediates (NFD + several replaces + suffix loop) per call | Per-call cost matters at WC-01/03 volumes | Lower allocation/GC pressure | Later | Low |
| WC-11 | `dashboardState` rebuilds a full `songsById` map on every input emission | Avoidable 25k-entry map allocation | Cheaper dashboard recompute | Later | Low |

**Recommended measurement tests (from Wave C):** seed 1k/5k/10k/25k libraries and ~200k events;
measure cold-start-to-first-render and `sync()` duration (WC-08); main-thread frame times while
searching Home at 25k (WC-01); dashboard/Songs refresh after a single playback event (WC-02/04/05);
Smart Collections rebuild CPU per play (WC-06); large-list scroll dropped frames (WC-07); Insights
time-to-content (WC-09).

---

## 8. UX / Polish Backlog

Intentionally postponed UX improvements, drawn from `PLANNED.md`, release history, and the audits.

- **Recovery-UX honesty (Wave B follow-ups).**
  - ~~Surface a transient playback-error message~~ - done: `PlaybackUserMessage`
    (`BAD_TRACK_SKIPPED`, `QUEUE_EXHAUSTED`) is emitted by `PlayerController` and surfaced on Now Playing.
  - Permission-revoked-after-grant currently re-shows the first-run "Allow music access" screen
    rather than an "access was turned off in Settings" state.
  - Home/Library scan failure is currently log-only (Settings → Rescan shows a visible error); a
    Home banner for scan failure is an optional follow-up.
- **Additional Delete entry points** (after Track Details delete is stable): song-row overflow,
  Queue Sheet, Playlist Details inline rows, and bulk multi-select delete — pending
  accidental-deletion risk assessment in list contexts.
- **Drag-to-reorder robustness:** evaluate replacing the custom drag/auto-scroll/virtualization
  implementation with a stable library if real-device edge cases surface; the
  virtualization-interrupt commit path needs broader device validation.
- **Folder exclusion system:** extend beyond the WhatsApp voice-note toggle and the shipped preset
  exclusions (Telegram, Signal, Messenger, Downloads, Recordings; SE-1, D-32) to a general
  user-configurable block/allow list (still deferred).
- **Wording / consistency polish** (ongoing, per release history): plain-language empty states,
  standardized separators and content descriptions, normalized "Insights" labelling.
- **Export-before-reset:** Wavdrop has no in-app reset/clear-data feature. If one is ever added, it
  must prompt the user to export a backup first (and ideally run Back Up Now automatically) before any
  destructive action. Until then, Wavdrop has no in-app reset/clear-data path; OS-level uninstall or
  clear-data are the deliberate app-data destruction paths covered by the QA warning.

---

## 9. Future Product Vision

Forward-looking ideas that are **not scheduled** and are distinct from the actionable backlog above.
These describe a direction, not a commitment.

- **Full music-memory architecture (Contract P3).** Timeline, Eras, Rediscovery, comeback moments,
  and lifetime heatmaps/streaks built on complete event coverage and durable identity.
- **Cross-device music life.** A user's listening history, favourites, and playlists portable across
  devices and reinstalls via portable identity — without accounts or cloud-first playback.
- **Desktop interoperability.** Deeper Wavdrop Desktop ↔ Android portability beyond the current
  validated stats/playlist/event exchange, backed by a shared cross-platform validation library.
- **Richer Wrapped.** Event-derived lifetime insights (streaks, heatmaps, rediscovery) and Wrapped
  sharing/export.
- **Portable / custom EQ profiles.** Portable equalizer intent and shareable custom curves, building
  on the device EQ shipped in Beta 9.

> Vision items must be promoted to §6 (deferred architecture) or §11 (release backlog) with explicit
> dependencies before any implementation begins.

---

## 10. Completed Audit History

A historical record of major audits, each with what it accomplished. (Where an audit predates this
document and its detailed findings are not retained in the repository, that is noted explicitly
rather than reconstructed.)

| Audit | Focus | Outcome |
|---|---|---|
| **Queue audit** | Queue control and reorder behaviour | Informed the Beta 3 queue actions and drag-to-reorder stabilization; reorder commit and virtualization-interrupt fixes shipped (detailed findings not retained as a standalone doc). |
| **Empty-library audit** | Behaviour with no songs / failed scans | Informed selected-folder safety (preserve-on-empty), large-library batched scan, and honest empty states (detailed findings not retained as a standalone doc). |
| **Home / Library / Wrapped screen audits (Beta 7)** | Layout, empty states, wording, navigation | Polished dashboard, hub subtitles, Wrapped navigation, and normalized "Insights" labelling. |
| **Whole-app audit** | Cross-app consistency and readiness | Fed the Beta 7 polish/wording pass and Play Store readiness work (detailed findings not retained as a standalone doc). |
| **P2-B0** | Preservation foundation | Snapshot-scoped pending/quarantine retention of unmatched stats/events/lyrics/baselines/playlist entries, isolated as archive data. |
| **P2-B1** | Identity foundation | Device-local TrackIdentity, Room v12, scan-owned lifecycle, stable eventIds, eventId backup serialization + integrity + dedup; validated on-device. |
| **Wave A** | (Referenced as part of the audit sequence; detailed Wave A findings are not present in the inspected repository documentation and are intentionally not reconstructed here.) | Recorded for completeness; see future audit notes if/when archived. |
| **Wave B — Failure-State & Recovery** | "When reality goes wrong, does Wavdrop fail safely, recover predictably, and explain honestly?" | Found 2 HIGH launch risks (WB-01 playback error stall; WB-02 scan exception crash). Both fixed and verified this phase. Confirmed backup/restore/integrity/quarantine core is defensive. |
| **Wave C — Large-Library Performance** | "Will Wavdrop stay responsive with a large real-world library?" | No CRITICAL freeze path; 3 HIGH (WC-01/02/03), 6 MEDIUM, 2 LOW. Top risk: main-thread Home search filtering at 25k+. Findings populate §7. |

---

## 11. Future Release Backlog

Planning only — **not a release commitment.** Organized by rough horizon. Items move to
`RELEASE_NOTES.md` when shipped.

### Crossfade runtime integration (engineering; each item is its own slice)

Foundations CF-1..CF-2H3G are complete (§5). The following remain, and must **not** be combined into one
implementation item. (Historical: they were gated behind `CrossfadeRolloutPolicy.RUNTIME_ENABLED = false` until the final
items were validated; the gate is now true, see CF-2N2 below.)

1. Continuous fade progression / timing integration:
   a. CF-2C7A - secondary dynamic-gain primitive - complete (unused by the runtime; generic `ApplyGains` still refused).
   b. CF-2C7B - runtime `FadeTick` execution with paired primary/secondary gains (fresh ownership revalidation, failure
      ordering, terminal tick, transition to `HandoffPending`) - complete (handoff is executed by CF-2D3/CF-2D4).
   c. CF-2C7C - active-fade `evaluatePreparation` policy - complete.
   d. CF-2C7D - main-thread monotonic timing-driver foundation - complete (internal continuous-fade foundation is complete; production integration was NOT: at the CF-2C7D boundary the driver was deliberately unwired; CF-2E1 later added dormant gated production composition and CF-2E2 added configuration and start/stop policy; the hard gate is still false).
2. Occurrence-safe handoff / promotion / reconciliation of primary and secondary:
   a. CF-2D1 - secondary handoff snapshot primitive - complete.
   b. CF-2D2 - primary occurrence-reconciliation primitive - complete.
   c. CF-2D3 - runtime handoff execution / coordinator success-failure closure - complete (called by the internal driver since CF-2D4; production handoff is NOT complete).
   d. CF-2D4 - timing-driver handoff execution + continuation after successful handoff - complete.
   CF-2E1 - dormant production construction/lifecycle wiring - complete (gate false, driver not started).
   CF-2E2 - persisted crossfade configuration + explicit driver start/stop policy - complete (internal; a setting is not enablement, the hard gate remains the rollout boundary; no UI, not in backups).
   Next boundaries are separate and not auto-selected: dual-player EQ/audio-session validation, user-facing Settings UI, production enablement, physical Bluetooth/background validation.
3. Failure / cancel recovery during audible overlap:
   a. CF-2F1 - primary playback-error cancellation bridge - complete.
   b. CF-2F2 - primary terminal playback-state recovery (IDLE/ENDED) - complete.
   c. CF-2F3 - secondary terminal playback-state recovery (IDLE/ENDED) - complete.
   d. CF-2F4 - authoritative controller-disconnection recovery - complete.
   e. CF-2F5 - primary audio-focus / route interruption recovery - complete.
   Parent item (failure / cancel recovery during audible overlap) - complete. Service teardown is owned by `closeCrossfadeGraph`; the snapshot fallback remains defensive only.
4. Manual seek / pause / next / previous interaction while preparing or fading:
   a. CF-2G1 - explicit pause crossfade cancellation - complete.
   b. CF-2G2 - explicit same-track seek cancellation - complete.
   c. CF-2G3 - explicit next/previous navigation cancellation - complete.
   Parent item (manual seek / pause / next / previous interaction while preparing or fading) - complete. Queue/shuffle/repeat mutation is NOT covered (item 5).
5. Queue / shuffle / repeat mutation behaviour while fading:
   a. CF-2H1 - repeat-mode change cancellation - complete.
   b. CF-2H2 - logical shuffle-change cancellation - complete.
   c. CF-2H3A - Play Next family queue-mutation cancellation - complete.
   d. CF-2H3B - Add to Queue family queue-mutation cancellation - complete.
   e. CF-2H3C - arbitrary future queue reorder cancellation - complete.
   f. CF-2H3D - queue removal / bulk-clear cancellation - complete.
   g. CF-2H3E - library deletion cancellation - complete.
   h. CF-2H3F - explicit whole-queue replacement cancellation - complete.
   i. CF-2H3G - playback-resumption adoption cancellation - complete.
   Parent item (queue / shuffle / repeat mutation behaviour while fading) - complete. Evidence for closing it: every
   `bumpQueueGeneration()` path now has a cancellation owner (explicit commands via the CF-2H1..CF-2H3F listeners, resumption
   via CF-2H3G), and the one remaining generation bumper, hydration (`ensurePlayerHydratedFromSession`), only mutates when
   `HydrationAuthority` reports an empty physical Media3 queue (no items and no current item, not external), re-checked
   synchronously before the bump; a live crossfade requires a playing non-empty primary queue, so hydration is a no-op
   (`AlreadyHydrated` / `SkippedActiveQueue`) while one is owned. Established by code inspection plus the existing
   `HydrationAuthority` tests, not a device test; reopen if a hydration path is shown to run against a non-empty primary.
6. Dual-player Equalizer / audio-session validation:
   a. CF-2I1 - secondary audio-session observability foundation - complete.
   b. CF-2I2 - EQ compatibility policy: crossfade unavailable while EQ is enabled - complete.
   c. Physical dual-session EQ validation / possible future mirroring (revisit the policy only if a secondary session reliably owns a mirrored Equalizer with correct preset/capability behaviour, safe session churn and stable Bluetooth/wired behaviour) - open.
7. User-facing Settings UI / product exposure:
   a. CF-2J1 - rollout-gated Playback Settings UI foundation - complete (hidden while rollout is false).
   b. Final exposure with production enablement - pending item 8.
8. Production enablement:
   a. CF-2K1 - rollout safeguards + physical QA contract - complete.
   b. Physical validation - blocked: the first 6 s overlap works, but a ~1-2 s final handoff stutter was observed.
   c. CF-2L1 natural AUTO handoff correction - implemented; second physical run: rewind fixed, but a small audible takeover stutter remained.
   d. CF-2L2 continuity-qualified soft natural ownership transfer - implemented / awaiting physical retest.
   e. CF-2L3 lifecycle settlement + diagnostics - implemented.
   f. CF-2L4 projected natural-handoff position clock - implemented / awaiting physical retest.
   g. CF-2M1 promotable dual-player architecture feasibility (design only, proposed; gate unchanged): `docs/architecture/CROSSFADE-PROMOTION-FEASIBILITY.md`.
      CF-2M2 stable session facade parity spike (single physical player, gate-bound, verified by JVM/Robolectric tests; no device): see the same document, section 25.
      CF-2M3 two-slot PlayerEngine ownership foundation (two physical players, one focus/noisy/session-id owner, gate-bound, NEXT inert; JVM/Robolectric only, no device): see the same document, section 26.
      CF-2M4 NEXT-slot preparation + occurrence-safe queue graft (prepare B alone, graft the queue around it, invalidate through the shared cancellation point; gate-bound, never starts B; JVM/Robolectric incl. real ExoPlayer, no device): see the same document, section 27.
      CF-2M5 promote prepared NEXT + equal-power overlap (B started once, role swap + AUTO facade swap, tail-stripped retiring A recycled as NEXT, fade x duck composer, uid-alias fix; gate-bound, JVM/Robolectric incl. real ExoPlayer, no device): see the same document, section 28.
      CF-2M6 overlap interaction + error policy (reason-aware settle-to-B seam: A cut for pause/seek/navigation/repeat/shuffle/queue edits/focus loss/noisy/errors/OFF/EQ/disconnect/teardown, duck preserved; engine + facade command boundaries; gate-bound, JVM/Robolectric incl. real ExoPlayer, no device): see the same document, section 29.
      CF-2M7 PHYSICAL PASS (10 s / 12 s crossfades, no takeover stutter; promotion architecture ACCEPTED, EQ restriction and END_MARGIN unchanged); CF-2M8 retires and deletes the CF-2L natural-handoff implementation: see the same document, section 30.
      CF-2N1 rollout sign-off preparation: QA_CHECKLIST section 32 reconciled to the accepted promotion architecture (CF-2L retired); post-CF-2M8 validation APK for the remaining required physical sign-off (background/lock screen, Bluetooth, wired, EQ policy, interactions). The CF-2N1 physical rollout sign-off then PASSED (core, eligibility, duplicate occurrence, manual interactions, background/lock screen, Bluetooth, wired, EQ policy).
      CF-2N2 production enablement: `CrossfadeRolloutPolicy.RUNTIME_ENABLED` = `true` (the single gate; no second flag), KDoc/readiness wording and QA sign-off reconciled. Promotion architecture is the sole implementation; EQ ON -> crossfade unavailable, EQ OFF -> saved duration (default Off); mirrored EQ (CF-2M9) stays deferred. No release/version/publish performed.
      AK-1 app-kill / task-removal lifecycle hardening: pure `TaskRemovalPlaybackPolicy.decide(player)` over the session-facing player (NEXT/RETIRING never count), onTaskRemoved leaves crossfade untouched, teardown order and callback clearing pinned by tests, crossfade blocked until the persisted EQ state is known after recreation; see D-28.
      BP-1 `.bpstat` field-2 semantic correction: field 2 is a PERIOD play count (not skips); parser/model/UI/result corrected, `skipCount` is never touched by an import, no retroactive skip repair; see D-29 and WAVDROP_IMPORT_RULES.md.
      WC-02 resolved: Monthly Reports, Wrapped, Insights, Statistics, Most Played and Diagnostics read counts, PLAY/SKIP timestamps or one selected period range instead of the full event entity history (month/year membership stays in Kotlin ZoneId logic, not SQL); analytics meaning unchanged; WC-09 (full-history day/hour grouping) remains open.
      WC-04 resolved: Home previews rank, live-song-filter (INNER JOIN before LIMIT) and limit in SQLite with an explicit songId ASC tie-break; analytics unchanged; WC-11 remains open.
      WC-05 + WC-11 resolved: Home Wrapped card uses a per-live-song selected-year aggregate and a narrow preview builder (no full WrappedSummary; orphan PLAYs still count in the total, orphan-only history still shows the seed card); Home's song-id lookup is built once per library emission and shared; Wrapped screen, WC-02 and WC-04 contracts untouched.
      WC-06 resolved: Smart Collections recompute by dependency family (songs-only / stats / stats+day / completion) with type-scoped detail subscriptions; rules, ranking, caps (D-11), orphan filtering and canonical order unchanged; completion GROUP BY still re-runs on Room invalidation but no longer cascades into the other nine collections.
      WC-07 artwork: list artwork moved off SubcomposeAsyncImage/BoxWithConstraints with bounded request sizes; Now Playing artwork is request-keyed with stale-result protection, truthful failure (no stale cover), debug-only diagnostics (tag WavdropArtwork). Notification artwork is NOT the albumart URI: Wavdrop sets no artworkUri/artworkData on the Media3 item (only title/artist/album/extras), so the notification shows ExoPlayer's embedded-file artwork, a different mechanism than the MediaStore albumart URI Coil loads on every in-app surface; notification success therefore does not prove the URI decodes. Owner later validated the Now Playing artwork change on a real device (passed).
      WC-08 resolved: library sync writes only new/changed song rows and deletes only stale ids (pure SongSyncPlanner + applySongSyncPlan in the existing transaction); unchanged scans write nothing; playlist-remap boundary (new ids only), TrackIdentity reconciliation and preserve-on-empty/failed-scan behaviour unchanged; MediaStore is still fully scanned each launch; no schema change, no song-referencing foreign keys exist.
      Beta 10.0 release checkpoint: versionName 0.1.0-beta10 / versionCode 10; release notes and What's New rewritten around production crossfade, synchronized lyrics, playback reliability, faster libraries, backup/import fixes; in-app changelog updated; WC-09 and WC-10 explicitly remain open; no tag, publish or deploy performed.
      WC-09 resolved (post-Beta 10): Insights/Statistics read a bounded InsightsPlayActivity reduced from a streamed PLAY-timestamp cursor (Kotlin ZoneId grouping, explicit count-then-latest-play tie rule, <=366 retained dates) instead of the full PLAY timestamp list; month-rollover trigger uses the scalar event count; no schema/index change.
      WC-10 resolved (post-Beta 10): normalizeTolerant is a reduced-pass implementation (one NFD, one builder pass for marks/apostrophes/underscore, one in-place spaced-dash + whitespace pass, one Locale.ROOT lowercase) with output identical to the legacy chain (legacy kept as a test reference); no cache, no schema change, indexing frequency still owned by WC-03. Wave C is closed.
      Library access and scan recovery (post-Beta 10): AudioPermissionGate distinguishes first run, denied, blocked and a distinct Revoked state ("Music access was turned off", Open Settings, library data kept) through a pure AudioPermissionResolver; the one-way device-local fact "audio permission was granted before" is persisted in the existing DataStore (AudioPermissionHistoryRepository, never reset, NOT backed up, not inferred from songs). One shared scan presentation contract (LibraryScanUiState, LibrarySyncResult mapping, LibraryScanCoordinator) serves Home/Songs and Settings: Success = Complete, EmptyPreserved = Warning, Failed/exception = Error; a single scan operation owner per ViewModel prevents concurrent syncs, and Home isRefreshing is derived from it. Home gets a More options menu with Rescan library and a compact status/warning card with Try again over the preserved library; Songs pull-to-refresh and Settings rescan keep working. Transient scan state is not persisted; repository safety (WB-02) unchanged; no schema, backup or version change. Physical QA pending (permission revoke/regrant, rescan, induced scan failure).
      Large-queue interactive latency hardening (post-Beta 10; physical device QA pending): a user reported roughly 10 seconds of latency pressing Next on an older build with a very large queue. Aligned Next/Previous/jump were already a targeted seekTo; the remaining hazard was the dirty-queue path (playerQueueNeedsSync), which re-pushed the WHOLE playbackQueue (setMediaItems of thousands of items, one IPC burst) from transport, a dirty natural boundary, a warm reconnect and Play All Next. Now one authority, PhysicalQueueReconciler, repairs a divergent physical timeline incrementally: it never touches the current item (no restart, no play/pause/seek), removes surplus items before it for free, repairs a bounded priority window (previous, next 4, jump target, repeat-wrap target) synchronously for explicit transport, and the rest in bounded chunks (256 items per looper turn) outward from the current item. While dirty the controller index is trusted only at reconciler-verified logical indices (physical = logical + offset), so a natural boundary onto a verified index stays pure native gapless. Play All Next replaces only the changed span (cost follows the batch, not the queue); a span above 2,048 items, and a large shuffle reorder, are deferred to the chunked reconciler instead of one main-thread burst. Repair is bound to the queue generation and the controller instance, restarts on any mutation or navigation, never writes the session or stats, and crossfade stays fail-closed while dirty. A full re-push remains only as a labelled last resort (FullQueueSyncReason: transport target unprovable, natural boundary on an unverified index, reconnect with an untrusted current occurrence, current-song library deletion) plus fresh queue loads (O(N) by nature); each full re-push logs reason, queue size and current index. The 12,288-entry bounded MediaItem cache is unchanged. Proven by operation-shape tests at 1k/5k/12,288 (synchronous dirty Next cost identical across sizes, no whole-queue push from planners), not by wall-clock claims; JVM timings are not device evidence.
      Large-queue hardening validation pass (post-Beta 10): the reconciler's assumptions were proven on REAL Media3 (ExoPlayer behind a MediaSession, mutated and read through a MediaController, with the production PlayerController driven through its public transport API and a recording session player): `currentMediaItemIndex` on the controller reflects a prefix remove/insert/replace IMMEDIATELY (before any looper turn), the current item, position and play state are preserved, and the server player reports only a timeline change. The same run exposed a Media3 quirk the app had not guarded: after ANY controller-issued playlist mutation (a repair chunk, but also Play Next or a removal) the MediaController re-reports a STALE automatic position discontinuity and sometimes a media-item transition naming the unchanged current item, which PlayerController handed to StatsTracker (a same-song reselect fabricated a PLAY in the repair test; a stale index could select the wrong song). PlayerController now classifies such an echo (resolved occurrence equals the occurrence Now Playing already synced, unless Repeat ONE / Repeat ALL over a single item can legitimately loop onto itself) and ignores it for stats, sleep timer and session ownership; the genuine-advance song is resolved from the logical occurrence instead of the physical index. Dirty Next/Previous/jump, duplicate occurrences, Repeat OFF/ALL/ONE, reconnect + pending Next and the natural-boundary decision are covered on real Media3. Not covered (physical QA): real MediaStore files and codecs, Bluetooth/focus, the PlaybackService lifecycle, crossfade during repair, large real libraries, and a real natural end-of-track (a trackless JVM source cannot be played to its end).
   h. Final gate flip after passed validation - complete (CF-2N2).
9. Physical Bluetooth / background / wired / EQ-policy validation - complete (CF-2N1 sign-off).

### Playback hardening (engineering)

- **Natural AUTO-transition callback ownership** (TD-018): the crossfade-owned portion became a demonstrated blocker (physical handoff stutter) and is owned by CF-2L1 and CF-2L2; the broader callback-ownership cleanup stays a dedicated slice.

### Post-Beta 9 carry-over (still open after the Beta 10 checkpoint)

- Wave C is fully closed (WC-01 through WC-11 resolved; see §7).
- Wave B UX follow-ups: resolved (post-Beta 10, pending physical QA): permission-revoked recovery state and Home scan-failure visibility (see decision log).
- Outstanding real-device QA from `PLANNED.md`: delete-from-device flow, native share across share
  targets, Bluetooth/wired resume, launcher icon switching, end-to-end backup/restore regression.

### Post-Beta 10 stabilization (follow-ups after the Beta 10.0 release checkpoint; not release blockers)

- (none: Wave C closed)
- Additional Delete entry points (pending accidental-deletion risk review).
- The general user-configurable folder block/allow list evaluation (the Telegram/Signal/Messenger/
  Downloads/Recordings preset exclusions shipped in SE-1).

### Post-launch

- Public Privacy Policy page at the required Play Store URL; in-app policy reference line.
- Snapshot retention/receipts surfacing, a snapshot-management surface, and Storage Management entry points (Recovery Restore itself is implemented and physically validated).

### Future (P3 horizon)

- Portable TrackIdentity, identity export, cross-device reconciliation/rematching.
- Ghost/Archive lifecycle and deliberate permanent-purge flow.
- Event-led analytics reconciliation; streaming import/export; optional encrypted/signed backups.
- Wrapped sharing/export and event-derived lifetime insights.

---

## 12. Parking Lot

Ideas that have been discussed and deliberately left **unscheduled**. Recorded so they are not
re-proposed as new — and so the reasoning for not pursuing them is preserved.

**Deliberately postponed (from `PLANNED.md` *Deferred*):**

- Equalizer expansion beyond the Beta 9 device EQ (e.g. portable/custom curves).
- Scrobbling / Last.fm integration.
- Android Auto support.
- Additional home/lock-screen widget surfaces beyond the shipped widget.
- ID3 / metadata tag editing.
- Undo / recycle-bin for deleted files (not feasible — Android provides no recycle bin for shared
  media storage).

**Permanently rejected (philosophy guardrails, from `PLANNED.md` *Rejected*):**

- Silent deletion without confirmation; skipping the pre-confirmation dialog before
  `MediaStore.createDeleteRequest`; deleting externally-opened (non-library) audio.
- Wiping `track_stats` / `track_listen_events` when a track is deleted from device.
- Streaming features and cloud-first playback.
- User accounts, social, or shared-listening features.
- AI recommendation or playlist-generation systems.

**Open questions held for evidence (from `PLANNED.md` *Known Issues / Open Questions*):**

- Real-device validation breadth for share targets, Bluetooth/wired resume, launcher icon caching,
  and notification shuffle/repeat control visibility (OEM-dependent behaviour outside app control).

## 13. Playback Session Hydration (implemented)

**Status:** Implemented. Originally an approved design (Beta 9 stabilization); the primitive, the
Media3-queue authority, and the explicit-PLAY interception have since shipped. This section keeps the
durable principles and the remaining gaps.

**Problem it solved.** With a cold or suspended process, explicit PLAY from a notification, Bluetooth,
lock screen, or widget did nothing until the UI restored the session, because `player.play()` had an
empty ExoPlayer queue and restoration only ran from the Activity startup path.

**Durable principles (still binding):**

1. **Hydration is separate from autoplay.** Hydration rebuilds player state and never decides whether
   playback starts; the caller (explicit PLAY, reconnect policy, startup) decides.
2. **Hydration is idempotent and retryable.** Concurrent callers perform at most one attempt (mutex);
   a failed attempt never permanently consumes eligibility.
3. **Player state is authoritative.** "Hydrated" is derived from the physical Media3 queue
   (`HydrationAuthority`), not from a mutable flag or a stale logical queue. External playback is never
   overwritten, and the snapshot is re-checked immediately before it is applied so a manual queue
   always wins.

**Where it lives.** `PlayerController.ensurePlayerHydratedFromSession(...)` is the single primitive;
`restoreSessionIfNeeded` (Activity startup, via `PlaybackStartupCoordinator` / `StartupRestoreGate`) and
the cold-resume path call it. `PreviousBehaviorPlayer.play()` hydrates before forwarding an explicit PLAY,
and `PlaybackService.onPlaybackResumption` rebuilds the occurrence-safe queue for system resumption.

**Remaining follow-ups (not blockers):** the legacy wrappers `restoreSessionIfNeeded` /
`resumeSessionCold` are thin callers of the primitive and could be collapsed; physical validation of
cold-process PLAY from each external entry point remains part of `QA_CHECKLIST.md`.

---

*End of document. Update in place as work ships, decisions change, or audits complete - amend
history, never erase it.*
