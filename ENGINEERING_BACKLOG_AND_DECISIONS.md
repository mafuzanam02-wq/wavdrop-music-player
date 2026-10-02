# ENGINEERING BACKLOG & DECISIONS

> **Wavdrop Music Player** · package `com.launchpoint.wavdrop`
> Durable decisions and engineering backlog. Last reconciled after CF-2C7A (post-beta9).
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
| Version | 0.1.0-beta9 (versionCode 9); post-beta9 engineering work is on `master` unreleased |
| Phase | Soft-launch stabilization; crossfade runtime foundations in progress |
| Platform | Android (min SDK 26 / compile + target SDK 36) |
| Database | Room (`wavdrop.db`), schema v13 |

### Current priorities

1. **Playback correctness and occurrence safety** — duplicate-song queues, reconnect/resume authority,
   and session hydration must be provably safe before audible features build on them.
2. **Crossfade runtime integration** — small review-gated slices on top of the completed CF-1..CF-2C7A
   foundations (see §5 and §11). Not user-facing and not enabled.
3. **Preservation integrity** — listening history, statistics, playlists, and favourites must
   survive reinstall, migration, and recovery without silent loss or false attribution.
4. **Performance readiness for large libraries** — remaining Wave C items (§7).

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
- A general user-configurable folder block/allow list (only the WhatsApp-specific toggle and a
  planned exclusion list exist).
- Enabling crossfade for users (the runtime is gated off; see §5 and §11).

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
| D-20 | **Restore mode is explicit (Recovery vs Merge)** | The user's intent (authoritative replacement vs additive merge) must be explicit; Recovery requires a verified pre-restore safety snapshot or is blocked | Merge restore implemented; explicit Recovery mode and pre-restore safety snapshot **not** implemented (see §6) |
| D-21 | **Stats merge is monotonic (`MAX`) and idempotent** | Merge restore never lowers known history and re-importing the same backup is a no-op | Active |
| D-22 | **Platform-scoped preferences** | New exports write Android settings under `preferences.android`; legacy flat `preferences` is import-only backward compatibility | Active |
| D-23 | **Playback errors recover by bypassing the failing item** | On a Media3 `PlaybackException`, advance to the next valid queue item (or stop cleanly), without fabricating stats or mutating identity; one transient `PlaybackUserMessage` per episode | Active (Wave B WB-01; user message shipped post-beta9) |
| D-24 | **Song identity is not queue-occurrence identity** | The same song can occupy several queue positions; the current occurrence is positional and bound to `queueGeneration`; ambiguous song-id fallback fails closed; no persistent occurrence UUIDs | Active (OH-1 hardening) |
| D-25 | **The Media3 queue is the hydration authority** | Whether the player is "hydrated" is inferred from the physical Media3 queue (never from a stale logical queue or a boolean claim); hydration never decides autoplay; external playback is never overwritten | Active (implemented) |
| D-26 | **Crossfade is gated and primary-authoritative** | The primary player/MediaSession stays the sole system-visible authority; the secondary player has no session and no audio focus; transitions are keyed by `CrossfadeTransitionKey`; `CROSSFADE_SECONDARY_RUNTIME_ENABLED` stays `false` until a validated integration exists | Active |
| D-27 | **Explicit transport beats automatic playback** | Automatic Bluetooth/wired resume requests carry authority tokens; an explicit user play/pause or route loss voids them | Active (implemented) |

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

- **Crossfade foundations CF-1 .. CF-2C7A (post-beta9, internal, gated off).** Engineering foundation
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
  transaction; delta-based, idempotent via import baselines; never writes events.

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
| **Recovery Restore (authoritative) full path** | Not implemented; restore is merge-only. Requires a mandatory verified pre-restore safety snapshot before destructive replacement | Safety-snapshot system | P1/P2 (staged) |
| **Snapshot retention and backup receipts** | Only DATED / REPLACE_PREVIOUS file modes exist; no retained-N snapshot policy or persisted receipts | Retention policy design | Post-launch |
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
| WC-02 | **Partially addressed** - Home and Songs no longer subscribe to the full events table; Monthly Reports, Insights, Statistics, Wrapped, Smart Collection details, and Diagnostics still use `allListenEvents()` |
| WC-03 | **Resolved** - `LibrarySearchIndex` precomputes normalized fields once per library change |
| WC-05 | **Partially addressed** - Home Wrapped preview is bounded to the latest year via a ranged query; still recomputed on event changes |
| WC-04, WC-06..WC-11 | Open (unchanged) |

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
- **Folder exclusion system:** extend beyond the WhatsApp voice-note toggle and the planned
  exclusion list (Telegram, Signal, Messenger, Downloads, Recordings) to a general user-configurable
  block/allow list.
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

Foundations CF-1..CF-2C7A are complete (§5). The following remain, and must **not** be combined into one
implementation item. All are gated behind `CROSSFADE_SECONDARY_RUNTIME_ENABLED = false` until the final
items are validated.

1. Continuous fade progression / timing integration:
   a. CF-2C7A - secondary dynamic-gain primitive - complete (unused by the runtime; generic `ApplyGains` still refused).
   b. Runtime `FadeTick` execution with paired primary/secondary gains (fresh ownership revalidation, failure
      ordering, terminal tick, transition to `HandoffPending`).
   c. Active-fade `evaluatePreparation` policy (today it fails closed and may re-arm while `Fading`).
   d. Main-thread monotonic timing driver.
2. Occurrence-safe handoff / promotion / reconciliation of primary and secondary.
3. Failure / cancel recovery during audible overlap.
4. Manual seek / pause / next / previous interaction while preparing or fading.
5. Queue / shuffle / repeat mutation behaviour while fading.
6. Dual-player Equalizer / audio-session validation.
7. Settings + persisted crossfade preference.
8. Production enablement (flipping the gate, with rollout safeguards).
9. Physical Bluetooth / background / EQ validation.

### Playback hardening (engineering)

- **Natural AUTO-transition callback ownership** (TD-018): own it in a dedicated slice before or
  independently of crossfade unless it becomes a demonstrated blocker.

### Beta 9.x (stabilization)

- Remaining Wave C items (§7): **WC-02** (finish decoupling analytics screens from the full-events flow)
  and the open MEDIUM/LOW items.
- Wave B UX follow-ups: permission-revoked recovery state; optional Home scan-failure banner.
- Outstanding real-device QA from `PLANNED.md`: delete-from-device flow, native share across share
  targets, Bluetooth/wired resume, launcher icon switching, end-to-end backup/restore regression.

### Beta 10

- Remaining Wave C MEDIUM performance items (WC-04…WC-09).
- Additional Delete entry points (pending accidental-deletion risk review).
- Broader folder exclusion list (Telegram/Signal/Messenger/Downloads/Recordings) and the general
  block/allow list evaluation.

### Post-launch

- Public Privacy Policy page at the required Play Store URL; in-app policy reference line.
- Recovery Restore (authoritative) with mandatory verified pre-restore safety snapshot.
- Snapshot retention/receipts surfacing and Storage Management entry points.

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
