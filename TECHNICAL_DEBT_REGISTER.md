# TECHNICAL DEBT REGISTER

> **Wavdrop Music Player** · package `com.launchpoint.wavdrop`
> Record of intentional engineering compromises. Last reviewed after CF-2C3 (post-beta9).

---

## Purpose

This register records **intentional engineering compromises** — places where the current
implementation is considered acceptable for now, but is known to have limitations that were
consciously accepted.

It is deliberately distinct from the feature backlog:

| Feature backlog (`ENGINEERING_BACKLOG_AND_DECISIONS.md`) | Technical debt (this document) |
|---|---|
| Work we *have not done yet* | Work we *did a particular way on purpose* |
| New capability, missing feature, future idea | An existing implementation with a known, accepted limitation |
| "We should add X" | "We chose X over Y, knowing the tradeoff" |
| Driven by product value | Driven by cost-vs-benefit of changing what already works |

**Technical debt, in this project, means:** *"We intentionally accepted this implementation because
changing it now would cost more than the benefit."*

This document exists so future development does **not accidentally "fix" something that was
deliberately designed this way.** Several entries here describe behaviour that looks like a bug or an
inefficiency but is, in fact, a guardrail (e.g. unresolved identity lineage is preferred over a
wrong rematch). Before "improving" any item below, read its *Reason This Exists* and *Why We
Accepted It* fields.

This register is **not** a bug list, an unfinished-feature list, or a roadmap. Bugs are fixed; debt
is *accepted and tracked.* Unfinished features live in the backlog; deferred architecture lives in
`ENGINEERING_BACKLOG_AND_DECISIONS.md` §6. In particular, **unfinished crossfade work is active feature
architecture, not technical debt**; its remaining items are tracked in the backlog (§11).

---

## Status Definitions

| Status | Meaning |
|---|---|
| **ACTIVE** | Deferred intentionally. The compromise is in force and accepted. |
| **MONITOR** | Accepted, but watch for conditions (scale, complaints, metrics) that would change the calculus. |
| **RESOLVED** | The debt has been removed; the limitation no longer exists. |
| **SUPERSEDED** | Replaced by a better implementation; kept for historical reasoning. |

---

## Entry Index

| ID | Category | Status | Priority |
|---|---|---|---|
| TD-001 | Playback recovery messaging | RESOLVED | Medium |
| TD-002 | Library sync retry | ACTIVE | Low |
| TD-003 | TrackIdentity lifecycle | ACTIVE | High (guardrail) |
| TD-004 | Smart Collections ranking | ACTIVE | Medium |
| TD-005 | Wrapped preview recompute | MONITOR | Low |
| TD-006 | Home search normalization | RESOLVED | High |
| TD-007 | Full-events flow coupling | ACTIVE (narrowed) | Medium |
| TD-008 | Search normalization caching | RESOLVED | Medium |
| TD-009 | Dashboard top-N sorting | MONITOR | Low |
| TD-010 | Artwork image loader | MONITOR | Low |
| TD-011 | Full-table scan upsert | MONITOR | Medium |
| TD-012 | Insights in-memory grouping | MONITOR | Low |
| TD-013 | Quarantine snapshot scoping | ACTIVE | High (guardrail) |
| TD-014 | Legacy eventId backfill | ACTIVE | High (guardrail) |
| TD-015 | Auto-backup scheduling model | SUPERSEDED | Low |
| TD-016 | Legacy merge reconciliation | ACTIVE | Medium |
| TD-017 | Permission-revoked recovery UX | MONITOR | Low |
| TD-018 | Natural AUTO-transition callback ownership | ACTIVE | Medium |

---

### TD-001

**ID:** TD-001
**Category:** Playback recovery messaging
**Current Status:** RESOLVED
**Priority:** Medium
**Origin:** Wave B audit (WB-01 fix)
**Resolution:** `PlayerController` now emits a typed `PlaybackUserMessage` (`BAD_TRACK_SKIPPED`,
`QUEUE_EXHAUSTED`; at most one per recovery episode) that Now Playing surfaces. The original limitation
is kept below for historical reasoning.
**Original Implementation:** On a Media3 `PlaybackException`, `PlayerController.onPlayerError`
bypassed the failing item (advance to next valid track, or stop cleanly), logging the error but
showing **no user-facing message.**
**Reason This Exists:** The playback layer has no existing transient-message / snackbar / event
channel between `PlayerController` and the UI.
**Risk:** A user whose track fails sees the song silently skipped without an explanation ("Couldn't
play this track. It may have been moved, deleted, or unsupported.").
**Why We Accepted It:** Recovery (not getting stuck) is the critical fix; inventing a broad UI event
architecture during stabilization would be larger in scope and risk than the fix itself.
**When To Revisit:** When a general transient-message/event system is introduced.
**Dependencies:** A playback-to-UI event channel (e.g. shared snackbar/event bus).
**Possible Future Solution:** Emit a typed playback-error event the UI surfaces as a snackbar.
**Notes:** The recovery logic itself is complete and verified; only the *messaging* is deferred.

---

### TD-002

**ID:** TD-002
**Category:** Library sync retry
**Current Status:** ACTIVE
**Priority:** Low
**Origin:** Wave B audit (WB-02 fix)
**Current Implementation:** `syncIfNeeded()` sets `hasSynced = true` before running and is **not
reset on failure**, so a contained scan failure does not auto-retry until the ViewModel is recreated.
Recovery is via pull-to-refresh or Settings → Rescan.
**Reason This Exists:** A self-resetting flag would re-trigger sync on every recomposition.
**Risk:** After a persistent failure (e.g. permission revoked), the library does not silently
re-sync on its own.
**Why We Accepted It:** Prevents a retry loop that would repeatedly hit the failing MediaStore query
on every recomposition; explicit recovery paths exist.
**When To Revisit:** If telemetry shows users stuck after a transient failure without discovering
pull-to-refresh.
**Dependencies:** A retry/backoff manager.
**Possible Future Solution:** A bounded retry manager with backoff that distinguishes transient from
persistent failures.
**Notes:** Pairs with the preserve-on-empty-scan guarantee — a failed scan never wipes the library.

---

### TD-003

**ID:** TD-003
**Category:** TrackIdentity lifecycle
**Current Status:** ACTIVE
**Priority:** High (guardrail — do not "fix" casually)
**Origin:** P2-B1 design; `docs/BACKUP_PRESERVATION_CONTRACT.md` §5/§15; Wave B audit (WB-03)
**Current Implementation:** A track removed and later re-added with a new MediaStore ID receives a
**fresh** `identityUuid`; the old identity simply has its `currentSongId` cleared. No reconnection
(rematching) is attempted.
**Reason This Exists:** Rematching is explicitly out of P2-B1 scope; the scan is the single identity
writer and never infers identity from metadata/URI/path.
**Risk:** A re-added song starts a new identity lineage rather than reconnecting to its prior history.
**Why We Accepted It:** Per the contract's safety rule, *"a wrong match is worse than an unresolved
song."* False-positive rematching would corrupt historical attribution; an unresolved/new lineage is
recoverable, a wrong attachment is not. No data is lost — the old identity persists as a cleared
reference (future tombstone).
**When To Revisit:** When the rematching engine and durable/portable identity land (Contract P3).
**Dependencies:** Portable TrackIdentity, stable event lineage (`sourceInstallationId`), conservative
matching hierarchy.
**Possible Future Solution:** Post-scan rematching that reconnects identities only on exact identity
or strong-confidence matches, leaving ambiguity unresolved.
**Notes:** This is a deliberate correctness guarantee, not an oversight.

---

### TD-004

**ID:** TD-004
**Category:** Smart Collections ranking
**Current Status:** ACTIVE
**Priority:** Medium
**Origin:** Decision D-11; Wave C audit (WC-06)
**Current Implementation:** `SmartCollectionBuilder` ranks the **full eligible set** for each of the
11 collection types, then caps the visible list. The rebuild runs on every relevant stats/completion
change (off the main thread).
**Reason This Exists:** Ranking before capping is what makes `totalEligibleCount` accurate ("N songs
qualify"), and rebuilding on input change keeps collections current.
**Risk:** Sustained background CPU/GC at large libraries (filter+sort of up to N songs × 11 types per
relevant event).
**Why We Accepted It:** Correctness — accurate eligible counts and always-current collections — is
preferred over micro-optimization; the work is already off-main (`flowOn(Default)`) and bounded by
`WhileSubscribed`.
**When To Revisit:** If profiling at 25k+ shows this as a measurable battery/scroll problem.
**Dependencies:** A partial top-k selection strategy that still yields an exact eligible count.
**Possible Future Solution:** Debounced/conflated rebuild trigger; compute `totalEligibleCount` via
`count()` and rank only the capped slice for capped types.
**Notes:** Ranking-before-cap is an intentional correctness choice, not an accident.

---

### TD-005

**ID:** TD-005
**Category:** Wrapped preview recompute
**Current Status:** MONITOR
**Priority:** Medium
**Origin:** Wave C audit (WC-05)
**Current Implementation:** `wrappedPreview` rebuilds the latest-year Wrapped on Home whenever listen
events change, off the main thread. Since post-beta9 its input is bounded to the latest year through a
ranged event query instead of the full history.
**Reason This Exists:** No incremental Wrapped cache exists; recomputing guarantees the preview is
always correct.
**Risk:** A full year computation runs for a preview card on every playback event; cost scales with
total history.
**Why We Accepted It:** Correctness before caching; the work is off-main and bounded by
`WhileSubscribed`, so the blast radius is limited.
**When To Revisit:** When large-history users (200k+ events) show measurable Home churn.
**Dependencies:** An incremental/cached Wrapped representation with a coarse invalidation trigger.
**Possible Future Solution:** Cache the latest-year Wrapped and recompute only on day change or
explicit refresh.
**Notes:** —

---

### TD-006

**ID:** TD-006
**Category:** Home search normalization
**Current Status:** RESOLVED
**Priority:** High
**Origin:** Wave C audit (WC-01)
**Resolution:** Home search now filters on `Dispatchers.Default` with a 200 ms debounce over a cached
`LibrarySearchIndex`. The original limitation is kept below for history.
**Original Implementation:** Home `uiState` filtered the full library on the **main thread** per
keystroke (no debounce, no `flowOn(Default)`), running the expensive `MusicTextNormalizer` over each
song's title/artist/album.
**Reason This Exists:** The Home `uiState` predates the later, correct pattern used by
`songSearchResults` (debounced + `Dispatchers.Default`) and was not migrated.
**Risk:** Keystroke lag / dropped frames, approaching ANR territory at 25k+ tracks.
**Why We Accepted It:** Acceptable for small/medium libraries; flagged as the top performance risk
and scheduled. This is **temporary**, not a permanent design choice.
**When To Revisit:** Before broad soft-launch widening (highest-priority Wave C item).
**Dependencies:** Pairs with TD-008 (cached normalized index).
**Possible Future Solution:** Move the filter to `Dispatchers.Default` with a short debounce,
mirroring `songSearchResults`; filter against a precomputed normalized index.
**Notes:** Tracked as WC-01. The grouped search pipeline (`songSearchResults`) already demonstrates
the correct approach.

---

### TD-007

**ID:** TD-007
**Category:** Full-events flow coupling
**Current Status:** ACTIVE (narrowed)
**Priority:** Medium
**Origin:** Wave C audit (WC-02)
**Current Implementation:** `allListenEvents()` (`observeAll`) still observes the **entire**
`track_listen_events` table and re-emits on every insert. Home and Songs no longer depend on it; the
remaining consumers are Monthly Reports, Insights, Statistics, Wrapped, Smart Collection details, and
Diagnostics, each only while subscribed.
**Reason This Exists:** A single full-history flow is simple and was sufficient at current data sizes.
**Risk:** Memory and CPU scale with full history; the Songs list churns on each playback event.
**Why We Accepted It:** Correct and simple for current libraries; the cost concentrates only at large
histories.
**When To Revisit:** When histories reach the hundreds-of-thousands of events range, or alongside
TD-006.
**Dependencies:** Range-scoped event queries (`observeInRange`) and decoupling the Songs list from
event emissions.
**Possible Future Solution:** Compute this-month counts via a scoped query only when that sort is
active; prefer range-scoped flows over `observeAll`.
**Notes:** Tracked as WC-02.

---

### TD-008

**ID:** TD-008
**Category:** Search normalization caching
**Current Status:** RESOLVED
**Priority:** Medium
**Origin:** Wave C audit (WC-03)
**Resolution:** `LibrarySearchIndex` precomputes normalized title/artist/album once per library change
and is shared by Home, global, and grouped search. The original limitation is kept for history.
**Original Implementation:** Every filter pass re-normalized raw song fields from scratch; there was no
precomputed normalized search index.
**Reason This Exists:** Normalizing on demand kept the model simple and avoided a derived index to
maintain.
**Risk:** Search cost scales linearly with library size on every keystroke; compounds TD-006.
**Why We Accepted It:** Acceptable at current sizes; the normalizer is correct, only repeated.
**When To Revisit:** Alongside TD-006.
**Dependencies:** A derived normalized index rebuilt only when the library changes.
**Possible Future Solution:** Precompute normalized title/artist/album once per song (at entity→model
mapping) and filter against cached values.
**Notes:** Tracked as WC-03.

---

### TD-009

**ID:** TD-009
**Category:** Dashboard top-N sorting
**Current Status:** MONITOR
**Priority:** Low
**Origin:** Wave C audit (WC-04)
**Current Implementation:** Home dashboard sorts the **entire** stats list (`sortedByDescending`) for
both Recently Played and Most Played just to `take(4)`, despite LIMIT-bounded DAO queries
(`getRecentlyPlayed()`, `getMostPlayed()`) existing.
**Reason This Exists:** Reusing the already-collected full stats list avoided extra query wiring.
**Risk:** Two full O(N log N) sorts per dashboard emission at large stats tables.
**Why We Accepted It:** Negligible at typical sizes; off the critical path.
**When To Revisit:** If dashboard updates show jank at 25k+.
**Dependencies:** Wiring the existing LIMIT DAO queries into Home.
**Possible Future Solution:** Use the existing bounded DAO queries or a partial top-k.
**Notes:** Tracked as WC-04.

---

### TD-010

**ID:** TD-010
**Category:** Artwork image loader
**Current Status:** MONITOR
**Priority:** Low
**Origin:** Wave C audit (WC-07)
**Current Implementation:** `ArtworkImage` uses Coil `SubcomposeAsyncImage` inside
`BoxWithConstraints`, with no explicit request size, for song-row artwork.
**Reason This Exists:** `SubcomposeAsyncImage` gives a clean success/placeholder branch and adapts to
the measured size.
**Risk:** Subcomposition per cell adds scroll-frame cost in large lists.
**Why We Accepted It:** Visual quality and simplicity; acceptable on tested devices.
**When To Revisit:** If large-list scrolling shows dropped frames, especially on low-end devices.
**Dependencies:** —
**Possible Future Solution:** Use `AsyncImage` with placeholder/error painters and an explicit decode
size; reserve `SubcomposeAsyncImage` for large surfaces (Now Playing).
**Notes:** Tracked as WC-07.

---

### TD-011

**ID:** TD-011
**Category:** Full-table scan upsert
**Current Status:** MONITOR
**Priority:** Medium
**Origin:** Wave C audit (WC-08)
**Current Implementation:** `SongRepository.sync()` calls `upsertAll` over the **entire** scanned set
on every sync (including the one-shot `syncIfNeeded` at each launch), regardless of whether anything
changed.
**Reason This Exists:** An unconditional upsert is simple and guarantees the table matches the scan.
**Risk:** Full-table write churn at each cold start for large libraries; extra startup work and disk
writes (on the IO thread, not main).
**Why We Accepted It:** Simplicity and correctness; the stale-delete path already diffs, and write
cost is off the main thread.
**When To Revisit:** If startup time or disk-write volume becomes a problem at 25k+.
**Dependencies:** A scanned-vs-existing diff to upsert only changed/new rows.
**Possible Future Solution:** Diff and short-circuit when the scanned set is unchanged.
**Notes:** Tracked as WC-08.

---

### TD-012

**ID:** TD-012
**Category:** Insights in-memory grouping
**Current Status:** MONITOR
**Priority:** Low
**Origin:** Wave C audit (WC-09)
**Current Implementation:** `InsightsViewModel` groups all PLAY events by day-of-week and by hour in
memory, off the main thread, recomputed on each event while Insights is subscribed.
**Reason This Exists:** In-memory grouping reuses the already-loaded events list.
**Risk:** Full-history in-memory grouping cost on large histories.
**Why We Accepted It:** Off-main and bounded by `WhileSubscribed` (only when Insights is open).
**When To Revisit:** If Insights time-to-content degrades on large histories.
**Dependencies:** SQL `GROUP BY` aggregation or a cached result.
**Possible Future Solution:** Push day/hour aggregation into SQL or cache the result.
**Notes:** Tracked as WC-09.

---

### TD-013

**ID:** TD-013
**Category:** Quarantine snapshot scoping
**Current Status:** ACTIVE
**Priority:** High (guardrail — do not "fix" casually)
**Origin:** `docs/BACKUP_PRESERVATION_CONTRACT.md` §6; P2-A/P2-B0; `QuarantinePlanner` design
**Current Implementation:** Pending (quarantine) rows are **snapshot-scoped**: the `originKey`
includes the backup fingerprint (which includes `exportedAt`), so two exports from the same
installation at different times create separate pending rows for the same logical unresolved track.
**Reason This Exists:** Until durable identity and stable event lineage exist, the system makes no
cross-snapshot identity claims; pending rows are archive artefacts, not asserted-distinct tracks.
**Risk:** Importing multiple snapshots of the same library can inflate "unavailable tracks" / history
counts in archive surfaces.
**Why We Accepted It:** Preserving unmatched history *without* risking false cross-snapshot merging is
the correct conservative behaviour for this phase. The apply path already dedups *same-backup*
re-imports.
**When To Revisit:** P2-B, once durable TrackIdentity + `sourceInstallationId` lineage exist.
**Dependencies:** Portable identity, stable event lineage.
**Possible Future Solution:** Cross-snapshot dedup keyed on durable identity; or dedup for display
only while preserving provenance.
**Notes:** The planner's own documentation explicitly forbids cross-snapshot auto-merge until those
foundations exist. This is a guardrail, not an inefficiency.

---

### TD-014

**ID:** TD-014
**Category:** Legacy eventId backfill
**Current Status:** ACTIVE
**Priority:** High (guardrail — do not "fix" casually)
**Origin:** Decision D-06/D-07; P2-B1; `docs/BACKUP_PRESERVATION_CONTRACT.md` §9.2
**Current Implementation:** Legacy listen events without an `eventId` are **not backfilled.** New
events get a stable `eventId` at creation; legacy null-eventId rows remain null and are excluded from
eventId integrity unless present.
**Reason This Exists:** Per the contract, `eventId` must be generated at event creation, never
fabricated later (e.g. during export or migration).
**Risk:** Legacy events cannot participate in eventId-based dedup; they rely on the
`songId + occurredAt + eventType + listenedMs` fingerprint instead.
**Why We Accepted It:** *Prefer uncertainty over false certainty* — synthesizing IDs for historical
events would invent identity that never existed and could corrupt cross-generation dedup. The
fingerprint path keeps legacy dedup safe; all-null-eventId backups fingerprint identically to the
pre-eventId baseline.
**When To Revisit:** Not planned — this is a permanent correctness rule, not a temporary shortcut.
**Dependencies:** None.
**Possible Future Solution:** None intended; backfilling would violate the contract.
**Notes:** Recorded here so it is never "fixed" by adding a backfill migration.

---

### TD-015

**ID:** TD-015
**Category:** Auto-backup scheduling model
**Current Status:** SUPERSEDED
**Priority:** Low
**Origin:** Backup preservation contract (automatic backup section)
**Superseded by:** durable WorkManager scheduling (commit "Add durable automatic backup scheduling"):
a unique 24-hour periodic check (`AutoBackupWorkScheduler` / `AutoBackupWorker`) calls
`AutoBackupRepository.runIfDue()`. Scheduling is still **best-effort** under Android/OEM constraints
(storage-not-low constraint; the folder permission may be revoked), so UI wording stays qualified
("Automatic Backup Check", "Last automatic check") instead of promising exact schedules.
**Original Implementation:** "Automatic" backup ran **when Wavdrop was opened** after the configured
interval had elapsed — it was not background-scheduled.
**Reason This Existed:** No `WorkManager`-based background scheduling had been implemented.
**Risk:** Users who rarely open the app back up less often than the nominal interval suggests.
**Why We Accepted It:** Honest wording over implied capability; real background scheduling is a
larger, separately-staged feature.
**When To Revisit:** When `WorkManager` best-effort scheduling is implemented (Contract P3); wording
must then be updated to the future-WorkManager phrasing.
**Dependencies:** `WorkManager` integration; battery/storage/folder-access constraints.
**Possible Future Solution:** Best-effort background backup with truthful "approximately every
[interval], subject to Android scheduling" wording.
**Notes:** This is a *wording-and-capability* compromise, deliberately surfaced in the contract.

---

### TD-016

**ID:** TD-016
**Category:** Legacy merge reconciliation
**Current Status:** ACTIVE
**Priority:** Medium
**Origin:** `docs/BACKUP_PRESERVATION_CONTRACT.md` §8.3
**Current Implementation:** For old backups without stable event IDs or complete event history, merge
uses conservative aggregate reconciliation; `MAX(local, backup)` is used as a fallback and the
limitation is disclosed rather than claiming a perfect combination.
**Reason This Exists:** Independent listening histories cannot be exactly combined without complete,
stable per-event data.
**Risk:** `MAX(local, backup)` is not mathematically equivalent to summing two independent histories;
some legacy merges under-count true combined activity.
**Why We Accepted It:** *Prefer uncertainty over false certainty* — an honest conservative estimate
with disclosure beats a fabricated exact total. New event-backed data is combined precisely (union by
eventId).
**When To Revisit:** Naturally superseded as complete event coverage with stable IDs accumulates
(event-led analytics reconciliation, Contract P3).
**Dependencies:** Complete event coverage, stable eventIds.
**Possible Future Solution:** Event-led reconciliation that recomputes aggregates from immutable
events where full coverage exists.
**Notes:** The merge contract explicitly states `MAX` is a fallback, not equivalence.

---

### TD-017

**ID:** TD-017
**Category:** Permission-revoked recovery UX
**Current Status:** MONITOR
**Priority:** Low
**Origin:** Wave B audit (WB-04)
**Current Implementation:** When audio permission is revoked after a previous grant, on resume the
gate resets to the first-run "Allow music access" state rather than a distinct "access was turned off
in Settings" state.
**Reason This Exists:** The gate's resume handling collapses the revoked case into the
not-yet-requested case.
**Risk:** A returning user sees new-user wording instead of guidance that access was revoked, which
can read as "where did my library go?".
**Why We Accepted It:** Functionally correct (it does re-request and the stale library is dropped);
only the *messaging* is suboptimal. Not a data or stability risk.
**When To Revisit:** Alongside the Wave B recovery-UX follow-ups.
**Dependencies:** A distinct revoked/blocked UI state with an Open Settings CTA.
**Possible Future Solution:** Route resume-after-revoke to a dedicated "Music access was turned off"
screen.
**Notes:** Borderline between UX backlog and accepted debt; recorded here because the current
behaviour is a conscious, accepted limitation rather than a planned feature.

---

### TD-018

**ID:** TD-018
**Category:** Natural AUTO-transition callback ownership
**Current Status:** ACTIVE
**Priority:** Medium
**Origin:** Occurrence-authority hardening (OH-1) review
**Current Implementation:** A natural Media3 AUTO transition can be observed by two callbacks in
`PlayerController`: `onPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION)` and
`onMediaItemTransition(MEDIA_ITEM_TRANSITION_REASON_AUTO)`. The discontinuity handler performs the
`StatsTracker` song transition explicitly (and `PlaybackCallbackOwnership` marks that reason
`notifiesStats = false, persistsSession = true`), while the media-item-transition handler also syncs Now
Playing with `notifiesStats = true` and persists the session. Ownership of the stats transition and of
session persistence for one natural transition is therefore split across two callbacks, relying on
ordering and on the stats tracker tolerating the overlap. Other reasons (REMOVE / PLAYLIST_CHANGED) were
made single-owner in OH-1.
**Reason This Exists:** OH-1 deliberately limited itself to reasons it could prove (REMOVE, deletion
routing, routing through the occurrence resolver) and preserved AUTO behaviour to avoid regressing
gapless playback, loop-boundary detection, and sleep-timer end-of-song handling in the same change.
**Risk:** A future change to either callback (notably crossfade, which will add a second player and new
transition timing) could double-count or drop a stats transition or session save for a natural
transition.
**Why We Accepted It:** No demonstrated user-visible defect; the existing behaviour is covered by
current tests and has shipped. Changing it opportunistically would couple an unrelated risk to other work.
**When To Revisit:** As a **dedicated slice**, ideally before crossfade handoff work depends on AUTO
transition semantics; or immediately if it becomes a demonstrated blocker. It should not be mixed into
crossfade slices otherwise.
**Dependencies:** A single-owner design for natural transitions (stats, persistence, sleep timer, loop
detection) with tests for gapless and repeat-one boundaries.
**Possible Future Solution:** Make `onMediaItemTransition` the sole owner of the song transition for
AUTO, and let the discontinuity callback only refresh state.
**Notes:** Distinct from crossfade feature work, which is tracked in the backlog, not here.

---

## Guiding Principles — When Should Technical Debt Be Paid?

Pay down an item in this register when one or more of the following is true:

- **When correctness improves.** If changing the implementation removes a real risk of wrong results,
  data loss, or misleading UI (e.g. the Wave B fixes), it is worth doing.
- **When measurable performance improves.** Pay performance debt (TD-007 and the MONITOR
  performance items) when profiling at realistic scale shows a *measured* regression — not on
  suspicion. Wave C provides the measurement tests to use.
- **When the maintenance burden exceeds the benefit.** If keeping the compromise costs more in
  ongoing complexity, bug surface, or developer time than replacing it would, replace it.
- **When a blocking dependency lands.** Several items (TD-003, TD-013, TD-016) are gated on durable
  identity, stable event lineage, or complete event coverage. They become payable only when those
  foundations exist — and not before.

Do **not** pay down debt simply because:

- The code "looks old" or unfamiliar.
- A newer API or pattern exists, absent a correctness, performance, or maintenance reason.
- An item *looks* like a bug — several entries here (TD-003, TD-013, TD-014) are deliberate
  guardrails. Re-read *Why We Accepted It* before changing them. "Fixing" these would reintroduce the
  exact risk they were designed to prevent.

When an item is paid down, update its **Current Status** to RESOLVED (limitation removed) or
SUPERSEDED (replaced by a better implementation), keep the entry for historical reasoning, and move
the user-facing result into `RELEASE_NOTES.md`.

---

*End of register. Amend status in place as debt is paid, gated dependencies land, or guardrails are
revisited — preserve the original reasoning, never erase it.*
