# P5 — Bounded Physical Queue: Architecture Design

| | |
|---|---|
| **Status** | Design only. No production, test, Gradle, signing or release change accompanies this document. |
| **Verified baseline** | `24ebf1d4d98bc045233f676d1cf2fa9c16f5a3cf` (`Fix crossfade timeline identity scaling`), `HEAD == origin/master`, divergence `0 0`, clean tree. |
| **Scope** | Retain the complete logical playback queue while installing only a bounded window into Media3. |
| **Not in scope** | Persistence redesign, library-scan changes, database changes, Search UI, UI list virtualization (see §15). |

**How to read this document.** Every statement is tagged:

- **[C] Confirmed** — read from the code at the baseline above (path and symbol given; line numbers are deliberately
  omitted because they drift). Anything marked [C] can be re-verified with a grep.
- **[P] Proposed** — future behaviour. Nothing tagged [P] exists.
- **[Q] Open** — needs evidence (measurement or an owner decision) before it can be decided.

Source paths are relative to `app/src/main/kotlin/com/launchpoint/wavdrop/` unless they start with `app/src/test/`.

---

## 1. Executive summary

**The problem.** [C] WavDrop keeps one invariant throughout `playback/`: *the physical Media3 playlist is the complete logical
playback queue in playback order*, so `MediaController.currentMediaItemIndex == logical playback index`. Everything that is
O(queue) in the large-library path follows from that single invariant:

1. every fresh start installs the whole queue (`setMeasuredMediaItems(songs = playbackQueue)`), once on a controller that is
   reached over the session IPC;
2. every crossfade transition makes a **second ExoPlayer mirror the whole queue** (`NextSlotPreparation` grafts N−1 items around
   the target, in chunks, then verifies N media ids) — and the driver arms that preparation as soon as a transition is
   eligible, i.e. for essentially every track;
3. the 250 ms crossfade poll copies the whole logical queue (`captureCrossfadeRuntimeSnapshot` → `playbackQueue.toList()`),
   twice per poll while a NEXT is Ready;
4. every external surface sees a Media3 timeline of N windows;
5. session restore, resumption and Play-All-Next/shuffle each have an O(N) physical step (some already chunked by
   `PhysicalQueueReconciler`, which bounds *main-thread bursts* but not total work).

**The proposal.** Move the invariant boundary. Keep the logical queue exactly as it is (it is already independent of Media3,
[C] `libraryQueue` / `playbackOrder` / `playbackQueue` / `queueGeneration` in `PlayerController`) and install into Media3 only a
**cyclic-contiguous window of the playback order**, described by three integers (`logicalGeneration`, `windowGeneration`,
`windowStart`) plus the window length. Small queues (≤ `FULL_MODE_MAX`, proposed 300) keep today's full-queue path byte for
byte, so the change only affects the large queues that motivated it.

**Why contiguous-in-playback-order.** [C] Shuffle is already a permutation of the playback order (`playbackOrder`), Media3
shuffle is permanently off, and duplicates are already positional. A window that is contiguous in that order therefore needs no
song-id lookup, no per-item stamps and no mapping table, and it composes with the existing positional machinery
(`OccurrenceMap`, `DeferredStart`, `CrossfadeTransitionKey`, `physicalIndexOffset`).

**Two non-negotiable constraints discovered during the investigation.**

- *Crossfade.* [C] `PlayerEngine.promoteReadyNext` requires CURRENT and NEXT to have the same item count and compares anchors
  by physical index equal to the logical key. The window must therefore be mirrored on both players in lock-step (§8).
- *MediaSession.* [C] There is no per-controller view in Media3: the app's own `MediaController`, the notification, the lock
  screen and any external controller all observe the same session player. A smaller physical playlist is therefore a smaller
  public queue for all of them. Keeping the public queue logically complete needs an explicit **logical façade timeline**
  (§9) — this is the single largest design item and a hard precondition for activation.

**Rollout.** An internal compile-time gate, default off, evaluated once per queue install; no user setting; rollback is a
one-line change that cannot corrupt state because only the logical queue is ever persisted (§12).

**Roadmap.** Six bounded slices, P5-A … P5-F (§13), starting with a pure, behaviour-neutral mapping layer and ending with
on-device validation and activation. A small independent pre-work item (stop copying the queue on every crossfade poll) is
identified in §3 and is safe to ship before any of this.

---

## 2. Verified existing architecture

### 2.1 Layers [C]

```
UI (Compose)                       reads PlayerController.nowPlayingState (full logical List<Song>)
   │
PlayerController (singleton)       logical queue authority; talks to Media3 only through a MediaController
   │  MediaController (app-marked, hint APP_CONTROLLER_HINT)
MediaLibrarySession  ◄── notification / lock screen / widgets / external controllers (same session)
   │
PreviousBehaviorPlayer             policy wrapper (external-transport detection, previous semantics, cold-PLAY authority)
   │
SessionFacade                      ForwardingSimpleBasePlayer; presents ONE stable player over the engine's CURRENT slot
   │       + OccurrenceIdentityPlayer / OccurrenceTimeline / AliasedTimeline   (P4: collision-free period uids)
PlayerEngine                       two physical ExoPlayers: CURRENT + NEXT (CrossfadeRolloutPolicy.RUNTIME_ENABLED = true)
```

- [C] `CrossfadeRolloutPolicy.RUNTIME_ENABLED = true`, so `PlaybackAssembly` builds `PlaybackTopology.TWO_SLOT_ENGINE`
  (`PlaybackAssembly.kt`); the façade is always present in the shipping build.
- [C] `PlaybackService.onCreate` builds `PreviousBehaviorPlayer(player = assembly.logicalPlayer, …)` and the
  `MediaLibrarySession` wraps it. `PlaybackService.WavdropSessionCallback` adds only two custom commands
  (`CMD_TOGGLE_SHUFFLE`, `CMD_CYCLE_REPEAT`), `onPlaybackResumption`, and the browse-tree callbacks. It does **not** override
  `onSetMediaItems` / `onAddMediaItems`.

### 2.2 The logical queue [C]

`PlayerController` (≈4,600 lines) owns three parallel structures, all private `var`s replaced wholesale on mutation:

| Field | Meaning |
|---|---|
| `libraryQueue: List<Song>` | source-order queue |
| `playbackOrder: List<Int>` | permutation of `libraryQueue` indices (identity, or shuffled) |
| `playbackQueue: List<Song>` | `playbackOrder.map { libraryQueue[it] }` — **the list ExoPlayer is loaded with** |
| `queueGeneration: Long` | bumped by `bumpQueueGeneration()` on every structural change; binds all deferred/async work |

- [C] `docs/ARCHITECTURE.md` §4.2: a song id is **not** an occurrence identity; the current occurrence is positional and
  generation-bound; ambiguous ids fail closed; **"Persistent per-occurrence UUIDs are intentionally not part of the
  architecture."** This design respects that: it introduces no persisted occurrence identity (see §5.4 for the one runtime-only
  option that needs an owner decision).
- [C] Occurrence-bound machinery is already positional and logical: `DeferredPlaybackCommands` (`DeferredStart`,
  `startPlaybackIndex`, `resolveDeferredStart`), `OccurrenceMap`, `PendingTransportCommands`, `SleepTerminalOccurrence
  (queueGeneration, playbackIndex, songId)`, `CrossfadeTransitionKey(queueGeneration, fromPlaybackIndex, toPlaybackIndex)`,
  `BadMediaRecoveryPlanner`. None of them needs to change its *logical* meaning.

### 2.3 A proto-mapping already exists [C]

The large-queue hardening (post-Beta 10, `ENGINEERING_BACKLOG_AND_DECISIONS.md`) added the vocabulary this design generalizes:

- `PlayerController.physicalIndexOffset` — "physical = logical + offset" while the queue is *dirty* (always ≤ 0).
- `PlayerController.verifiedLogicalIndices` and `isVerifiedPhysicalLanding(...)` (`PhysicalQueueReconciler.kt`) — the only
  logical indices at which the controller index may be trusted while dirty.
- `PhysicalQueueReconciler` — bounded `replaceMediaItems` mutations that never touch the current item; priority window
  `windowAhead = 4`, `chunkSize = 256`, `maxPriorityGap = 1024`; `QueueReconcileOutcome.{Completed, InProgress, Unsafe}`.
- `PlayerQueueTimeline` — the adapter between the reconciler and a Media3 `Player`.
- `FullQueueSyncReason` / `syncPlayerQueueAt` — the labelled last-resort full re-push.
- `MAX_SINGLE_SPAN_REPLACE_ITEMS = 2_048` — a span above this is deferred to the chunked reconciler instead of one burst.

Today the offset is a *repair-transient*: it converges to 0 when the reconciler completes. The proposal makes the offset a
**permanent, explicit, generation-stamped property of the window**, and replaces "converge to full equality" with "converge to
the target window".

### 2.4 Media-item representation [C]

- `PlaybackMediaItem.kt`: `Song.toPlaybackMediaItem()` sets `mediaId = song.id.toString()` and extras `wavdrop_album_id`. The
  media id is the **song** id, never an occurrence id. Browse ids (`WavdropMediaLibrary`) and `onPlaybackResumption` rely on this.
- `PlayerController.materializeMediaItems` caches `MediaItem` instances by `MediaItemCacheKey(id, uri, title, artist, album,
  duration, albumId)`, bound `MEDIA_ITEM_CACHE_MAX_SIZE = 12_288`. Because instances are shared between occurrences of the same
  song, **a per-occurrence stamp cannot ride the `MediaItem`**. Occurrence identity must stay positional.

### 2.5 Crossfade [C]

- `NextSlotPreparationDriver.evaluate()` runs every `CrossfadeCadence.PRE_FADE_POLL_INTERVAL_MS = 250` ms. It captures one
  `CrossfadeRuntimeSnapshot`, plans with `planCrossfadeTransition`, binds a `CrossfadeTransitionKey`, and calls
  `NextSlotPreparation.request(NextSlotRequest(key, queue = materialize(snapshot.playbackQueue), repeatMode))`.
- `NextSlotPreparation`: load **only B** on NEXT and prepare it; on READY, graft the rest of the queue around B in bounded
  chunks (`GRAFT_CHUNK_SIZE`), then verify per-index `mediaId` in slices; only `Ready` may be promoted.
- `PlayerEngine.promoteReadyNext(key)`: requires `out.currentMediaItemIndex == key.fromPlaybackIndex`,
  `inc.currentMediaItemIndex == key.toPlaybackIndex`, equal `mediaItemCount`, equal anchor media ids; then swaps roles and calls
  `SessionFacade.replaceDelegate(.., presentAsAutoTransition = true)`; `stripRetiringTail()` removes the retiring player's
  tail.
- `CrossfadePromotionRuntime.evaluate()` also calls `snapshotProvider()` on its own poll while a NEXT is Ready.

### 2.6 Persistence and restoration [C]

- `PlayerController.saveSessionAsync` builds `PlaybackSessionSnapshot(queueSongIds = libraryQueue.map { it.id },
  playbackOrder, currentSongId, currentIndex, positionMs, repeatMode, shuffleEnabled, updatedAtMs)` and writes it through
  `PlaybackSessionRepository.save` (DataStore; the queue and the order are stored as comma-joined strings). Latest-wins via
  `PlaybackSessionPersistenceGate`.
- `PlaybackSessionRules.mapSavedQueue` maps every saved id through `availableSongs.associateBy { it.id }` and returns `null`
  (restore fails entirely) if **any** saved id is missing from the library.
- `PlayerController.ensurePlayerHydratedFromSession` and `PlaybackResumptionMapper.map` both rebuild the whole
  `playbackQueue` and install all of it (`PlaybackResumptionPlan.mediaItems = playbackQueue.map(Song::toPlaybackMediaItem)`).
- `HydrationAuthority.mediaQueuePresent(mediaItemCount > 0 || hasCurrentMediaItem)` — "already hydrated" is decided from the
  **physical** player. This stays valid for a window.

### 2.7 UI [C]

The UI never reads the Media3 timeline. `NowPlayingState.queue: List<Song>` (the full `playbackQueue`, shared by reference),
`currentIndex`, `queueSource` are produced by `PlayerController.syncNowPlayingState` and consumed by `QueueSheet`,
`NowPlayingScreen`, `HomeScreen`, `MiniPlayer`, `PlaylistDetailsViewModel`. **UI behaviour is therefore independent of the
physical window.** Only *external* Media3 clients depend on the physical timeline.

---

### 2.8 Dependency map: where "physical index == logical index" is assumed

Classification: **1** must change before virtualization · **2** can use a compatibility adapter · **3** can remain unchanged ·
**4** requires a specific integration test before activation (a row may carry two).

#### 2.8.1 `playback/PlayerController.kt`

| Function / site | Assumption | Class | Required change / test |
|---|---|---|---|
| `installLibraryStart` ← `playFromQueueInternal` | installs `playbackQueue` entire, `startIndex = playbackStartIndex` | 1 | install the window around `playbackStartIndex`; test: item count ≤ W, current maps to start |
| `installPreservedSearchStart` ← `playPreservedSearchPlan` | same | 1 | same |
| `installExternalStart` ← `playExternalUriWithoutNotification` | one item | 3 | Full mode |
| `seekToPlaybackIndex` | `seekTo(playbackIndex + physicalIndexOffset)` or plain `seekTo(playbackIndex)` | 1 | `logicalToPhysical` or re-anchor; test: in/out-of-window, rapid repeats |
| `navigate`, `skipToNext`, `skipToPrevious` | logical via `QueueNavigator`; then `seekToPlaybackIndex` | 2 (3 for the planning) | no change beyond `seekToPlaybackIndex`; test: repeat-ALL wrap, Previous at index 0 |
| `jumpToQueueItem`, `jumpToSongById`, `executePendingQueueJump`, `deferQueueJump`, `deferNavigation` | logical indices | 3 + 4 | test: deferred jump to out-of-window target after reconnect |
| `currentPlaybackIndex()` / `resolveCurrentPlaybackIndex` | controller index trusted when aligned; `physicalIndexOffset` + `verifiedLogicalIndices` when dirty; O(N) `uniquePlaybackIndexForSongId` fallback | 1 | translate through `PhysicalWindow.physicalToLogical`; keep fail-closed; test: stale window generation, duplicate songs |
| `syncNowPlayingState`, `pendingPreserveSearchPlanForSync` | `controller.currentMediaItemIndex` as a logical index | 2 | adapter (strategy A) / true by construction (strategy B); test: Now Playing index across a shift |
| `playerListener.onMediaItemTransition` / `onPositionDiscontinuity`, `isStaleAutomaticEcho`, `handlePendingAutomaticTransition`, `naturalAdvanceLandedOnVerifiedIndex` | AUTO landing index compared with the logical queue | 1 | translate physical→logical, trigger window shift after an AUTO transition; ignore events from a stale `windowGeneration`; test: AUTO at window edge, shift during callback |
| `playNext`, `insertAllAfterCurrent`, `appendAllPreservingQueue`, `addToQueue`, `addAllToQueue`, `playAllNext` (`planBatchQueuePlayerSync`) | `addMediaItem(insertPlaybackIndex)`; `replaceMeasuredMediaItems(futureStart…)`; `MAX_SINGLE_SPAN_REPLACE_ITEMS` | 1 | window adapter edit (§7.2); test: batch larger than window |
| `removeFromQueue`, `applyBulkClear` (`clearEarlierQueue`, `clearUpNext`), `applyNonCurrentSongDeletion` | `removeMediaItem(playbackIndex)`, `removeMediaItems(mediaRemoveFrom, To)` | 1 | adapter; test: removal before/inside/after window |
| `handleCurrentSongDeleted`, `applyCurrentDeletionContinuation`, `applyCurrentDeletionWithoutController` | full `syncPlayerQueueAt` of the survivor queue | 1 | re-anchor; test: delete current at window edge |
| `moveQueueItemUp/Down/To`, `swapPlaybackItems`, `moveToPlayNext` | `moveMediaItem(from, to)` in playback indices | 1 | adapter; test: one end outside the window |
| `toggleShuffle`, `applyShuffleAroundCurrent`, `ShuffleQueueSyncPlanner.planShufflePhysicalSync` | whole physical reorder; large → deferred reconciler | 1 | bounded `replace` around current; test: shuffle toggle at 25k keeps current untouched |
| `cycleRepeatMode`, `playerListener.onRepeatModeChanged` | `controller.repeatMode = logical` | 1 | Bounded: physical OFF/ONE, façade reports logical; test: external repeat write |
| `kickQueueRepair`, `reconcilePhysicalQueue`, `repairForTransportTarget`, `markQueueDirty`, `applyPhysicalEdit` | repair target = full equality (`Completed` iff offset 0 and `itemCount == n`) | 1 | Full mode keeps them; Bounded gets a window-target repair with the same discipline (dirty on failure) |
| `syncPlayerQueueAt` (`FullQueueSyncReason`) | full re-push | 1 | **forbidden in Bounded mode**; guard test |
| `ensurePlayerHydratedFromSession`, `restoreSessionIfNeeded`, `adoptPlaybackResumption` | installs the full `playbackQueue` | 1 | window install; test: restore of 25k, duplicates, current at index n-1 |
| `HydrationAuthority.mediaQueuePresent` | `mediaItemCount > 0` | 3 | unchanged (window ≥ 1) |
| `captureCrossfadeRuntimeSnapshot` | `playbackQueue.toList()`, `playerQueueNeedsSync` | 1 | reference/accessor instead of a copy (P5-A0); expose window facts |
| `materializePlaybackMediaItemsForCrossfade`, `materializeMediaItems`, `mediaItemCache` | cache ≤ 12,288 | 2 | called with ≤ W items; cache bound may shrink [Q] |
| `saveSessionAsync`, `resolveSessionCurrentLibraryIndex` | logical | 3 | O(N) remains (§15) |
| `tickAndCheckLoopBoundary` | position only | 3 | none |
| `recoverFromCurrentPlaybackError` + `BadMediaRecoveryPlanner` | logical target → `seekToPlaybackIndex` | 3 + 4 | test: bad media at the window edge, repeat ALL |

#### 2.8.2 Other `playback/` files

| File / symbol | Assumption | Class | Notes |
|---|---|---|---|
| `QueueNavigator.kt` | pure logical arithmetic over `queueSize` | 3 | unchanged |
| `QueueMutation.kt` (`insertAllAfterCurrent`, `appendAll`, `clearEarlier`, `clearUpNext`, `planCurrentSongDeletion`, `planNonCurrentSongDeletion`, `shuffleToggleModel`, …) | pure logical planners; some return `mediaRemoveFrom/To` playback indices | 3 (+ 2 for the `media*` fields) | the `media*` fields become inputs to the window adapter |
| `PhysicalQueueReconciler.kt`, `PlayerQueueTimeline` | full-queue equality target | 1 (Bounded) / 3 (Full) | reuse primitives (`ensureLogical`, `repairRange`, offset math) for the window |
| `DeferredPlaybackCommands.kt`, `OccurrenceMap`, `resolveDeferredStart`, `PendingTransportCommands.kt` | logical positions + `queueGeneration` | 3 + 4 | install functions change, the commands do not |
| `SleepTimer.kt` (`SleepTerminalOccurrence`) | logical (generation, index) | 3 | `PlaybackAssembly.setPauseAtEndOfMediaItems` is a physical flag on the current item — test it across a shift |
| `NextSlotPreparation.kt` (`NextSlotRequest.queue`, graft, `verifySlice`) | NEXT holds the full queue; `mediaItemCount == queue.size`; `currentMediaItemIndex == key.to` | 1 | window-sized request; X2–X4 |
| `NextSlotPreparationDriver.kt` | `materialize(snapshot.playbackQueue)` | 1 | window materialization; X5 gate |
| `CrossfadeRuntimeSnapshot.kt`, `CrossfadeTransitionRules.kt` (`planCrossfadeTransition`) | needs `playbackQueue.size` and two durations | 2 | accessor instead of the list |
| `CrossfadeCancellation.kt` (`CrossfadeTransitionKey`) | logical (generation, from, to) | 3 | unchanged (X1) |
| `PlayerEngine.kt` (`promoteReadyNext`, `stripRetiringTail`) | physical index == `key.from/to`; equal item counts | 1 + 4 | X4; test: promotion with a shifted window |
| `CrossfadePromotionRuntime.kt` | `snapshotProvider()` each poll | 2 | benefits from P5-A0 |
| `SessionFacade.kt`, `OccurrenceTimelineIdentity.kt` | timeline = physical delegate's | 2 (A) / 1 (B) | `LogicalQueueTimeline` (§9.3) |
| `PreviousBehaviorPlayer.kt` | forwards next/previous to the player | 1 (B) | unchanged hooks, logical answers come from the façade |
| `PlaybackService.kt` (`onPlaybackResumption`, physical listeners, session callback) | full `mediaItems` for resumption | 1 | return the window (A) / logical start (B) |
| `PlaybackResumption.kt` (`PlaybackResumptionPlan.mediaItems`) | full map | 1 | window slice + start index |
| `PlaybackStartupTrace.kt` (install fingerprinting) | debug-only O(N) over the installed list | 2 | now ≤ W |
| `WavdropMediaLibrary.kt` | browse tree, ids = song ids | 3 | — |
| `WidgetPlaybackStateListener.kt`, `ui/widget/WidgetActionReceiver.kt` | current item / `mediaItemCount == 0` | 3 + 4 | cold-widget test in Bounded mode |
| `data/playback/PlaybackSessionRepository.kt`, `PlaybackSessionRules.kt` | logical persistence | 3 | O(N) remains |
| UI: `NowPlayingState`, `ui/screen/nowplaying/QueueSheet.kt`, `NowPlayingScreen.kt`, `HomeScreen.kt`, `MiniPlayer.kt` | logical list from `PlayerController` | 3 | — |

#### 2.8.3 Assumption index (the eight requested)

| Assumption | Where it lives | Class |
|---|---|---|
| `currentMediaItemIndex == logical playbackQueue index` | `seekToPlaybackIndex`, `navigate`, `syncNowPlayingState`, `currentPlaybackIndex`, `playerListener.*`, `PlayerEngine.promoteReadyNext`, `NextSlotPreparation.verifySlice` | 1 / 2 |
| Physical size == logical size | `PhysicalQueueReconciler.reconcile` (`Completed` ⇔ `itemCount == n`), `planBatchQueuePlayerSync` (`controllerMediaItemCount != old size`), `NextSlotPreparation` (`mediaItemCount == queue.size`), `PlayerEngine` (equal counts) | 1 |
| Logical index used directly as physical | `applyPhysicalEdit { addMediaItem/removeMediaItem/moveMediaItem(playbackIndex) }`, `seekTo(playbackIndex)` | 1 |
| Physical next/previous == logical | `PreviousBehaviorPlayer.seekToNext/Previous*` forwarding, Media3 AUTO transitions | 1 (needs the window floor/ring + façade) |
| Full-queue fallback install | `syncPlayerQueueAt`, all `install*Start`, hydration | 1 |
| Reconciliation across mismatched timelines | `PhysicalQueueReconciler`, `verifiedLogicalIndices`, `physicalIndexOffset` | 1 (Bounded) |
| Physical uid vs logical occurrence | `SessionFacade` alias, `OccurrenceIdentityPlayer` | 2 / 1 |
| Complete physical playlist for repeat/shuffle | native `REPEAT_MODE_ALL` wrap, `ShuffleQueueSyncPlanner` | 1 |
| Session restoration after process death | `ensurePlayerHydratedFromSession`, `onPlaybackResumption` | 1 |

---

## 3. Root causes of full-queue scaling costs

All costs below are [C] unless marked. Where a figure exists it comes from P4's opt-in JVM benchmark
(`CrossfadeTimelineIdentityBenchmarkTest`; real ExoPlayers, trackless source) and is **shape evidence only, not device latency**.

| # | Cost | Where | Complexity | Evidence / note |
|---|---|---|---|---|
| R1 | Fresh start installs the whole queue | `installLibraryStart`, `installPreservedSearchStart`, `installExternalStart` → `MediaController.setMeasuredMediaItems(playbackQueue)` | O(N) materialize + O(N) IPC + O(N) Media3 source creation | P4 JVM: `setMediaItems` of 25,000 trackless items ≈ 30–60 ms; production media-source construction is costlier (see `NextSlotPerformanceTest` production-factory variant). **No device baseline in the repo — must be captured (§11).** |
| R2 | NEXT mirrors the whole queue for each transition | `NextSlotPreparationDriver.evaluate` → `materialize(snapshot.playbackQueue)`; `NextSlotPreparation` graft N−1 + verify N ids | O(N) per track change | The dominant steady-state cost for a large queue: it recurs every track. |
| R3 | Recurring queue copy on the crossfade poll | `PlayerController.captureCrossfadeRuntimeSnapshot` → `playbackQueue.toList()`; called by the driver **and** `CrossfadePromotionRuntime.evaluate` every 250 ms | O(N) allocation ×≥1 per 250 ms | Independent of the window; the lists are never mutated in place (every mutation reassigns a fresh copy; the only `MutableList` operations act on `toMutableList()` copies), so the defensive copy is removable on its own (§13, P5-A0). |
| R4 | Full-timeline façade rebuild after promotion | (fixed in P4 for the hash pathology) | O(N) per state evaluation after promotion | P4 JVM: promotion at 25k ≈ 172 ms, state rebuild ≈ 150 ms — still O(N). |
| R5 | External controllers receive an N-window timeline | `SessionFacade` presents the physical timeline | O(N) IPC/state per timeline change | Whether Media3 1.11.1 slims or truncates very large timelines for controllers is **not verified** [Q]. |
| R6 | Restore/resumption install the whole queue | `ensurePlayerHydratedFromSession`, `PlaybackResumptionMapper.map`, `onPlaybackResumption` | O(N) | plus O(N + L) id mapping (`associateBy` over the library). |
| R7 | Logical mutations rebuild whole lists | e.g. `playNext`: `libraryQueue + song`, `playbackOrder.toMutableList()`, `playbackQueue = playbackOrder.mapNotNull {…}`; `removeFromQueue`: `toMutableList().removeAt`, `filterIndexed.map`, `mapNotNull` | O(N) per edit, allocation heavy | **Remains after this design** (§15). The window removes the physical O(N), not this one. |
| R8 | Persistence is O(N) per save | `saveSessionAsync`: `libraryQueue.map{id}` + CSV join in DataStore on every transition/pause/mutation | O(N) per save | **Remains after this design** (§15). |
| R9 | Tail scans that fall back to ids | `uniquePlaybackIndexForSongId`, `resolveSessionCurrentLibraryIndex` (`filter { id == … }.singleOrNull()`) | O(N), only on untrusted-index fallbacks | Rare path; must be kept fail-closed. |
| R10 | Heap | the whole queue's `MediaItem`s (cache ≤ 12,288) + a second player's copy during crossfade + each controller's mirrored timeline | O(N) × players × controllers | Needs measurement (§11) [Q]. |

What the window removes: R1, R2, R5, R6 (physical part), and R4's size term. What it does **not** remove: R3 (separately
fixable), R7, R8, R9.

---

## 4. Logical and physical queue contracts

### 4.1 Logical queue (unchanged) [C → carried forward]

The logical queue is the authoritative playback experience. It retains the complete ordered selection, distinct occurrences
(duplicates), the shuffle order, full history and future navigation, the current occurrence, repeat policy, queue edits, and is
the only thing persisted and restored. A 25,000-song queue **is** a 25,000-song queue to the user in every mode.

Contract L1–L6 [C, restated as invariants the window must not weaken]:

- **L1** A song id is not an occurrence. Occurrences are positions in `playbackQueue`, qualified by `queueGeneration`.
- **L2** `queueGeneration` is bumped before any structural change becomes observable; async work captured under an older
  generation is inert.
- **L3** Shuffle is a permutation in `playbackOrder`; Media3 shuffle stays off (`onShuffleModeEnabledChanged` re-asserts false).
- **L4** The current occurrence is resolved by `currentPlaybackIndex()`/`resolveCurrentPlaybackIndex`, which fails closed on
  ambiguity.
- **L5** Repeat is a logical property mirrored to Media3 (`onRepeatModeChanged` accepts external changes into the logical field).
- **L6** Stale deferred work (start, seek, navigation, jump) is discarded, never re-aimed by song id.

### 4.2 Physical window [P]

A **bounded sequence of consecutive playback-order positions**, possibly wrapping around the end of the queue, installed into
the active physical player (and mirrored on NEXT, §8).

```
PhysicalWindow (value, immutable; replaced, never edited in place)
  logicalGeneration : Long     // the queueGeneration this window was derived from
  windowGeneration  : Long     // bumped on every window change (re-anchor, shift, edit); binds in-flight work
  queueSize         : Int      // logical size at derivation time
  start             : Int      // logical playback index of physical index 0, in [0, queueSize)
  length            : Int      // loaded item count, 1 <= length <= min(W_MAX, queueSize)
  wraps             : Boolean  // true only when repeat == ALL and the window crosses the end (ring)

  physicalToLogical(p) = if (!wraps) start + p else (start + p) mod queueSize        for p in [0, length)
  logicalToPhysical(l) = null if not loaded, else the unique p with physicalToLogical(p) == l
```

Derived quantities used by policy (§6): `history = physicalCurrent`, `ahead = length - 1 - physicalCurrent`,
`atLogicalStart = (start == 0 && !wraps)`, `atLogicalEnd = (start + length == queueSize && !wraps)`.

### 4.3 Mode selection [P]

`QueueMode ∈ {Full, Bounded}` is a property of a *queue install* (a `queueGeneration` born by a fresh start, search-preserve,
restore or resumption), decided once:

```
mode = if (PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED && queueSize > FULL_MODE_MAX) Bounded else Full
```

- **Full** is today's behaviour exactly: `length == queueSize`, `start == 0`, `physical == logical`, native Media3 repeat
  ALL/ONE, `PhysicalQueueReconciler` as the repair authority. This is what 100 % of queues ≤ `FULL_MODE_MAX` keep using, which
  also keeps one-song and very small queues out of the new code.
- **Bounded** uses the window. Physical repeat is `OFF` (or `ONE`); the façade reports the *logical* repeat mode (§9).
- Requirement `FULL_MODE_MAX ≥ 2 × W_MAX` (proposed 300 vs W = 100) so that a ring window can never contain the same logical
  occurrence twice (invariant I3).

---

## 5. Exact occurrence and index mappings

### 5.1 Why a ring-contiguous window (decision) [P]

Options considered:

| Option | Mapping | Shuffled queue | Duplicates | Cost | Verdict |
|---|---|---|---|---|---|
| A. Contiguous logical window in **library** order | index arithmetic on `libraryQueue` | ✗ breaks: playback order ≠ library order | ok | low | rejected |
| B. Occurrence table (arbitrary subset) | `Map<physicalIndex, logicalIndex>` | ok | ok | O(W) table, sparse refill, hard repeat/wrap | rejected (complexity; no benefit) |
| C. **Contiguous window in playback order, cyclic** | two integers | ok (shuffle is just the order) | ok (positional) | O(1) | **chosen** |
| D. Hybrid (C + pinned extras, e.g. a "jump target") | C + small table | ok | ok | extra state | rejected for the initial slices; revisit only if a measured need appears |

Justification against the two hard cases:

- *Shuffled queues.* [C] `playbackOrder` is already the shuffled sequence and `playbackQueue` is its image; "next" is `index+1`
  in that image (`QueueNavigator.automaticNextIndex`). A window that is a slice of `playbackQueue` is shuffle-agnostic.
  Toggling shuffle changes the *content* of the slice, not its form (§7.2).
- *Duplicate songs.* Positions are distinct even when ids are equal; the mapping never consults a song id. A song id on a loaded
  physical item is a **verification check** (`physical item's mediaId == playbackQueue[physicalToLogical(p)].id`), exactly as
  `PhysicalQueueReconciler` and `isVerifiedPhysicalLanding` already use it — never identity.

### 5.2 Invariants [P]

- **I1** Every loaded physical item maps to exactly one logical occurrence (`physicalToLogical` is total on `[0, length)`).
- **I2** A logical occurrence maps to at most one loaded physical item per physical player (injective), *except* the
  explicitly defined crossfade overlap, where the retiring player holds the same window the new CURRENT also holds (§8).
- **I3** Injectivity needs `length ≤ queueSize` and, for a wrapping window, `length ≤ queueSize` with no overlap; guaranteed by
  `FULL_MODE_MAX ≥ 2·W_MAX`.
- **I4** A logical index is **never** passed to a Media3 method as a physical index. Every Media3 index argument is produced by
  `logicalToPhysical` (or by an explicit `windowRelative(...)` helper). Enforced structurally: in Bounded mode the only code that
  holds a `MediaController` for index-bearing calls is the window adapter (guard test, §13 P5-B).
- **I5** The current occurrence is the same logical entry before and after any window shift: shifts only add/remove items
  *other than* the current one, so Media3 reports neither a transition nor a discontinuity [C: this is the property the
  reconciler already relies on — "never touches the current item, no transition callbacks"].
- **I6** Every window mutation is bound to `(logicalGeneration, windowGeneration)`; work captured under older values is dropped.
  `bumpQueueGeneration()` (already cancels `queueRepairJob`) additionally cancels refill work.
- **I7** Retired identities are not reused: window identity is the pair `(logicalGeneration, windowGeneration)`; both are
  monotonic and never reset (as `DeferredPlaybackCommands.lastSelectionId` already is).
- **I8** A stale Media3 callback from a previous window cannot navigate the logical queue: callbacks translate their physical
  index through the *current* window and are ignored if `windowGeneration` moved between the command and the callback
  (cf. `isStaleAutomaticEcho`).

### 5.3 Failure behaviour when the mapping is unprovable [P]

Prefer rejection to guessing. In order:

1. **Reject the operation** and keep playing (a Next whose target cannot be localized: no seek).
2. **Re-anchor** the window around the *logical* current occurrence taken from `nowPlayingState`/`currentPlaybackIndex()`
   (one bounded install; the logical queue is intact so nothing is lost). Labelled `WindowReanchorReason.MappingUnprovable`.
3. If even the logical current is unprovable, **pause and re-install from logical state**, never from a song-id lookup.
4. The legacy full re-push (`syncPlayerQueueAt`) is **forbidden in Bounded mode** (guard test); it remains the Full-mode last resort.

### 5.4 Identity exposed to Media3 [P / Q]

Two different identities must not be confused:

- **Physical window identity** — what Media3 sees on each player. P4's `OccurrenceIdentityPlayer` already mints a stable
  `PeriodOccurrenceUid(occurrence, periodInOccurrence)` per physical window uid (`OccurrenceIdSource`, never reused across
  delegates). It stays valid for the physical players unchanged.
- **Logical timeline identity** — what *controllers* should see in the logical façade timeline (§9.3). Proposed:
  `LogicalWindowUid(logicalGeneration, logicalIndex)` with a 64-bit avalanche hash (reuse the `PeriodOccurrenceUid.hashCode`
  mix), so uids are stable across window shifts (generation unchanged) and never reused after a mutation (generation moves).
  **[Q]** If controllers need uids stable *across edits* (an unmoved item keeping its uid after an insert elsewhere), a
  runtime-only `occurrenceSerial: LongArray` parallel to `playbackQueue` would be required. That is a runtime, non-persisted
  structure (regenerated on restore), so it does not contradict "no persistent per-occurrence UUIDs", but it does add an O(N)
  parallel array to every logical mutation. Default recommendation: **do not add it**; ship generation-scoped uids and revisit
  only on evidence.
- **Hash-cancellation guard.** Whatever uid classes are used must keep `Pair(windowUid, periodUid)` non-cancelling (P4 root
  cause). Required regression test: the distinct-hash invariant over the façade's timeline at N = 25,000 in Bounded mode.

---

## 6. Window sizing and refill policy

All numbers here are **initial candidates [P]**, to be replaced by measurement (§11, Q1). They live in one object,
`PhysicalWindowPolicy`, so tuning never touches logic.

### 6.1 Candidate sizes

| W (loaded items) | history / ahead | Memory & prep cost | Continuity | Refill frequency | Comment |
|---|---|---|---|---|---|
| 25 | 5 / 19 | lowest | 19 quick Next presses exhaust it; short tracks (<30 s) refill constantly | very high | too tight for burst Next and for crossfade+refill coexistence |
| 50 | 10 / 39 | low | comfortable | high | viable fallback |
| **100** | **20 / 79** | moderate (≈ one install of 100 items) | ≈ 5 h of average tracks ahead; ≥ 49 rapid Nexts before the low watermark | low (once per ~50 tracks) | **initial candidate** |
| 200 | 40 / 159 | doubles install/IPC and the mirrored NEXT copy | marginal gain | very low | only if device data shows refill jank at 100 |

Chosen initial policy (`W = 100`):

| Parameter | Value | Meaning |
|---|---|---|
| `W_MAX` | 100 | maximum loaded items |
| `HISTORY_TARGET` / `HISTORY_MAX` | 20 / 40 | trim history down to target when it exceeds max |
| `HISTORY_MIN` | 10 | refill-behind (prepend) when history < min and `start > 0` |
| `AHEAD_TARGET` | 79 | refill-ahead stops here |
| `AHEAD_LOW` | 30 | **refill trigger** (low watermark) |
| `AHEAD_FLOOR` | 8 | below this, refill is synchronous-priority for the next transport command |
| `REFILL_BATCH` | 25 | items per looper turn (one bounded `addMediaItems`) |
| `FULL_MODE_MAX` | 300 | queues up to this size never enter Bounded mode |

Rationale for the 30/79 hysteresis: refill starts while ≥ 30 tracks remain (a very long buffer), batches of 25 take ≤ 2–3 turns,
and the floor of 8 guarantees `hasNextMediaItem` (and therefore the notification's Next button) stays true until the genuine end
of the logical queue.

### 6.2 Operations

- **Refill ahead** — `addMediaItems(length, materialize(logical[start+length … ]))` of ≤ `REFILL_BATCH`; extends the ring past
  `queueSize-1` back to `0` only when repeat = ALL.
- **Refill behind** — `addMediaItems(0, items)` of ≤ `REFILL_BATCH` when history < `HISTORY_MIN` and the window is not at the
  logical start; `start -= k`, `physicalCurrent += k` (Media3 shifts the current index; no transition fires, I5).
- **Trim** — `removeMediaItems(0, k)` when history > `HISTORY_MAX` (down to `HISTORY_TARGET`); `start += k`. Items before the
  current one only.
- **Shift** = refill-ahead and/or trim performed in the same turn. A shift never interrupts or restarts the current item
  because it neither removes nor replaces it, and never seeks (same property as `PhysicalQueueReconciler`).
- **Re-anchor** — `setMediaItems(window around target, startIndexInWindow, positionMs)` (+ `prepare`/`play`): used for jumps and
  mapping failures; replaces the playlist, so it is only used when the playing item is changing anyway (or on restore).
- **One in-flight job** per player at a time; a new window generation cancels the previous job (`yield()`-chunked like
  `kickQueueRepair`).

### 6.3 Edge cases

| Case | Behaviour |
|---|---|
| Logical start (`start == 0`) | history may be 0; Previous with repeat OFF = restart-current (existing `QueueNavigator.previousAction`). |
| Logical end, repeat OFF | window stops growing at `queueSize-1`; `ahead` reaches 0; playback ends naturally (`STATE_ENDED`), as today. |
| Repeat ALL near the end | refill-ahead continues at logical 0 (ring); physical repeat stays OFF; the wrap is a plain gapless transition inside the window. Requires `FULL_MODE_MAX ≥ 2·W_MAX`. |
| Repeat ONE | physical `REPEAT_MODE_ONE` is kept (the current item loops); window is static. |
| One-song / tiny queue | Full mode (never Bounded). |
| Very short tracks | refill is driven by *item count* (`AHEAD_LOW`), not time; if `ahead < AHEAD_FLOOR` the next transport command first performs a synchronous bounded refill. |
| Unknown-duration tracks | irrelevant to the window (count-based); crossfade already refuses `UnknownDuration`. |
| Fast repeated Next | each Next localizes inside the window (plain `seekTo(physical)`); refill is coalesced to the latest `windowGeneration`; at most one job. |
| Large direct jump / tap any queue item | target outside the window → re-anchor (the normal path, not the §5.3 failure path). |
| Missing/unplayable next track | unchanged: `BadMediaRecoveryPlanner` picks a **logical** target, `seekToPlaybackIndex` localizes it (re-anchor if outside). |
| Refill delayed or fails | Playback continues on the loaded items. On failure: mark `windowDirty`, retry once on the next transition, then fall back to re-anchor on the next explicit transport. No spinner, no blocking wait; the worst case is the Full-mode behaviour that already exists (bounded work on demand). |

---

## 7. Navigation and queue mutation behaviour

**Rule.** The logical queue is always updated first and completely (as today). Then, per edit, the window adapter chooses the
cheapest physical consequence:

- **L-only** — logical state only; the window mapping is adjusted (`start`, `length`), Media3 untouched.
- **B-op** — one bounded Media3 operation inside the window.
- **Rebuild** — window re-anchor (one bounded install).

An edit outside the window **never** forces a rebuild by itself. Current-song protection and crossfade cancellation are
unchanged: every `explicit*Listener` notification stays at the same call site, before planning, in the same order.

### 7.1 Navigation

| Command | Logical step (unchanged) | Physical consequence |
|---|---|---|
| Next | `QueueNavigator.nextIndex` over the full logical size | target = current+1 is always loaded (ahead ≥ 1 except at the genuine end; floor 8) → `seekTo(logicalToPhysical(t))`. If `windowDirty`/`ahead < floor`: synchronous bounded refill first, else re-anchor. Repeat-ALL wrap = ring, still in-window. |
| Previous | `QueueNavigator.previousAction` | restart-current: `seekTo(0L)`. Move-to current−1: in-window (history ≥ `HISTORY_MIN` unless at logical start). Repeat-ALL from index 0 → logical `n-1` is outside the window → **re-anchor** (a large jump). |
| Seek (position) | none | `seekTo(positionMs)` on the current item; window untouched. |
| Tap any queue item / `jumpToQueueItem` / `jumpToSongById` / `executePendingQueueJump` | resolves a **logical** index (`resolveQueueOccurrenceIndex`, `resolvePendingQueueJump`: unchanged) | in-window → `seekTo(physical)`; else re-anchor around the target. |
| Media-button / notification Next & Previous | arrive as `PreviousBehaviorPlayer.seekToNext/…Previous…` | see §9: they must be answered with **logical** semantics. |

### 7.2 Mutations (current = `c`, all indices logical playback indices)

| Edit | Logical | Window effect | Class |
|---|---|---|---|
| Play Next (`playNext`, 1 song) | insert at `c+1` | always adjacent to current → inside | B-op: `addMediaItem(physicalCurrent+1)`; `length+1`; if `length > W_MAX` trim the far-ahead tail |
| Play All Next (`insertAllAfterCurrent`, k songs) | insert k at `c+1` | physical suffix is no longer contiguous with the logical future | B-op (bounded): one `replaceMediaItems(physicalCurrent+1, end, first A_TARGET items of the new future)`; cost follows min(k, A_TARGET), not N. Replaces today's `MarkDirty` for spans > 2,048 |
| Add to queue / batch append (`addToQueue`, `addAllToQueue`, `appendAllPreservingQueue`) | append at end | window includes the logical end? | L-only if the window's end is not the logical end (the common case); B-op `addMediaItems` if it is (or when repeat ALL ring would reach the new tail) |
| Remove upcoming (`removeFromQueue`, p > c) | remove p | p inside window | B-op `removeMediaItem(physical)`, `length-1`; p beyond window end → L-only |
| Remove earlier (p < c) | remove p; `c-1` | inside → B-op; before window start → L-only with `start -= 1` (physical untouched) | mixed |
| Move to Play Next (`moveToPlayNext`) | remove p, insert at `c+1` | = remove + insert (each classed above) | composed |
| Reorder upcoming (`moveQueueItemTo/Up/Down`, `swapPlaybackItems`) | permute | both ends inside → `moveMediaItem`; one end outside → remove+insert; both outside → L-only | per case |
| Clear earlier (`clearEarlierQueue`) | drop `[0,c)` | drop all history | B-op `removeMediaItems(0, physicalCurrent)`; the current item becomes logical 0, so `start = 0` |
| Clear upcoming (`clearUpNext`) | drop `(c, n)` | drop all ahead | B-op `removeMediaItems(physicalCurrent+1, length)`; `length = physicalCurrent+1` |
| Delete current song (`handleCurrentSongDeleted`) | `planCurrentSongDeletion` | the survivor becomes current | Rebuild (re-anchor at survivor, bounded) — replaces `applyCurrentDeletionContinuation`'s full `syncPlayerQueueAt`; without controller: `applyCurrentDeletionWithoutController` is unchanged (deferred start) |
| Delete other occurrences (`applyNonCurrentSongDeletion`, all copies of an id) | `planNonCurrentSongDeletion` (O(N) scan, unchanged) | removed positions inside window → descending `removeMediaItem`; those before `start` shift `start`; those after the window are L-only | mixed |
| Shuffle toggle (`toggleShuffle`) | `shuffleToggleModel` (O(N), unchanged) → new `playbackOrder`/`playbackQueue`, current kept | the whole window *content* changes (prefix and suffix) | B-op ×2 bounded (≤ W): `replaceMediaItems` around the untouched current item — exactly `applyShuffleAroundCurrent`, but bounded; **removes the `ShuffleReorderDeferred` large-queue path** |
| Repeat off/one/all | field change | ALL ⇒ ring refill enabled; OFF/ONE ⇒ ring disabled (trim any wrapped tail) | B-op (bounded) |
| Search result *preserving* the queue (`playSearchResultPreservingQueue`, `playPreservedSearchPlan`) | new queue built by `SearchPlaybackPlanner` (O(N), unchanged), new generation | fresh install | Rebuild: window around the tapped song |
| External URI (`playExternalUri`) | one-song queue | — | Full mode (single item) |
| Reconnect / deferred commands | `DeferredPlaybackCommands.take()` (unchanged, logical) | `installLibraryStart` etc. install a **window** around `startPlaybackIndex` | Rebuild |

All "B-op" rows go through the existing `applyPhysicalEdit` discipline (dirty on failure, repair obligation survives
disconnection), with the repair target being "the window", not "the whole queue".

---

## 8. Crossfade integration

### 8.1 What must hold [P]

- **X1** Crossfade keys stay **logical**: `CrossfadeTransitionKey(logicalGeneration, fromLogical, toLogical)`. Every positional
  rule (`ownsCrossfadeSource`, `crossfadeOwnershipLossReason`, `bindCrossfadeTransition`) keeps its meaning.
- **X2** When NEXT is `Ready` for key K, **NEXT and CURRENT hold identical windows** (`start`, `length`, same logical content),
  differing only in current index (NEXT sits on B).
- **X3** Any window mutation (refill, trim, edit) is applied to CURRENT and NEXT in the **same main-thread turn**, CURRENT first;
  if applying to NEXT fails, NEXT's preparation is invalidated (`CrossfadeCancelReason.QueueMutation`) and CURRENT is
  unaffected. NEXT is never mutated while it is `PreparingTarget`/`Grafting` — the mutation is queued behind it or the
  preparation is restarted.
- **X4** Promotion verification becomes: equal `windowGeneration`/`start`/`length`, plus `out.currentMediaItemIndex ==
  windowIndexOf(from)` and `inc.currentMediaItemIndex == windowIndexOf(to)`, plus the existing anchor-id checks. The O(N)
  per-index verification shrinks to O(W).
- **X5** The window must contain `to` plus a safety margin before preparation is requested; if a refill is pending the planner
  returns a new transient `CrossfadeUnavailableReason` (e.g. `WindowRefillPending`) and the next 250 ms poll retries. It never
  waits, and it degrades to gapless Media3 playback.

### 8.2 Scenarios

| Scenario | Behaviour |
|---|---|
| Gapless, crossfade disabled | No NEXT activity. The window machinery alone runs; native Media3 gapless inside the window. |
| Crossfade enabled | `NextSlotRequest.queue` is the **window** (≤ W items), not the queue: B loaded alone, then at most one or two graft chunks (`GRAFT_CHUNK_SIZE` ≥ W is plausible; keep chunking for safety) → Ready. Removes R2. |
| Near the window end | X5 gates preparation; refill completes first (AHEAD_LOW = 30 ≫ the few items a transition needs), so this is the exception, not the rule. |
| Near repeat-ALL wrap | `to` is the next position in the ring; B is `logical 0` loaded as the ring continuation; window on both players identical (ring). |
| Promotion while a refill is pending | Not allowed to interleave: either the refill was applied to both (X3) before `Ready`, or preparation was invalidated. `promoteReadyNext` re-checks X4 at the promotion instant and rejects on mismatch (`TimelineMismatch`, fail closed). |
| Queue edit during preparation | Existing seam: `explicitPlayNextMutationListeners` / `…QueueRemoval…` / `…QueueReorder…` cancel first; a subsequent window edit then runs against an idle NEXT. |
| Explicit skip during overlap | Unchanged (`PromotionInterruption.Navigation`, `commandBoundary` in `SessionFacade`). A skip to an out-of-window target triggers a re-anchor via `handleSetMediaItems` → `PromotionInterruption.QueueMutated`, which already cuts the retiring player first. |
| Cancellation / rollback | Unchanged (`recycleIncomingAfterFailure`, `settleAfterSwapFailure`): both players hold the same window, so either survivor is a valid CURRENT. |
| Repeated promotions | After a promotion the retiring player is stripped (`stripRetiringTail`) and recycled; it is re-installed as the next NEXT *window* (bounded). The façade alias on `replaceDelegate` remains valid because the windows mirror (`establishAliases` compares `MediaItem`s positionally — O(W) now). |

### 8.3 Interaction with P4 (`OccurrenceTimelineIdentity`) [C → P]

- [C] The façade aliases a promoted delegate's uids to the already-presented ones only when it mirrors the previous queue
  (same size, equal `MediaItem` at every index). In Bounded mode "queue" = window; X2 guarantees the mirror.
- [C] `PeriodOccurrenceUid` hashing (64-bit avalanche) prevents the nested `Pair` XOR cancellation. Any new uid class introduced
  for the logical timeline (§9.3) must follow it, and the structural test (distinct hash codes at N = 25,000) must cover it.
- [P] `OccurrenceIdSource` keeps minting ids that are never reused across delegates — the property needed so a re-anchored window
  never collides with ids still held by a controller.

### 8.4 Independent pre-work (not part of the window) [C → P]

R3 is fixable alone: the lists behind `playbackQueue` are replaced, never mutated in place (every mutation assigns a fresh
copy; the only `MutableList` operations act on `toMutableList()` copies), so `CrossfadeRuntimeSnapshot.playbackQueue` can carry the reference instead of `toList()`. This removes a recurring
O(N) allocation on both polls. Scheduled as **P5-A0** (§13).

---

## 9. MediaSession and UI integration

### 9.1 Confirmed facts [C]

1. UI is decoupled (§2.7): Queue sheet, Now Playing, mini player and home read `NowPlayingState` — complete in both modes.
2. The **same** `MediaLibrarySession`/player serves PlayerController's own `MediaController`, the media notification, the lock
   screen, widgets and any external controller. Media3 offers no per-controller playlist view.
3. `PreviousBehaviorPlayer` forwards `seekToNext`/`seekToNextMediaItem`/`seekToPrevious*` to the façade/physical player after
   crossfade and resume-authority hooks (`onExplicitNavigation`, `onExternalTransport`). Today these are physically correct only
   because physical == logical.
4. **[Q — Media3 contract, not repo code]** Notification/lock-screen Next/Previous availability is expected to come from the session player's available commands, derived from its
   timeline and repeat mode (`hasNextMediaItem` etc.).
5. `PlaybackService.onPlaybackResumption` returns `PlaybackResumptionPlan.toMedia3Resumption()` whose `mediaItems` is the **whole**
   queue; `adoptPlaybackResumption` mirrors it into the logical state.
6. Widgets read only `currentMediaItem` (`WidgetPlaybackStateListener`) and `controller.mediaItemCount == 0` for cold detection
   (`WidgetActionReceiver`) — unaffected by window size.
7. The manifest declares `MediaLibraryService` and the platform `MediaBrowserService` action but **no Android Auto
   (`automotive_app_desc`) declaration** — Android Auto is not a supported surface today. Browse nodes (`WavdropMediaLibrary`)
   page the library independently of the playback queue — unaffected.

### 9.2 Behaviour by surface, for a physical window (strategy A) vs a logical façade (strategy B)

| Surface | A: window visible as the timeline | B: logical façade timeline |
|---|---|---|
| In-app UI (queue sheet, position "x / N") | ✓ unchanged (reads `NowPlayingState`) | ✓ unchanged |
| Now Playing position / progress | ✓ (current item only) | ✓ |
| Notification Next/Previous *availability* | ✓ in practice (ahead ≥ floor 8 until the genuine end; history ≥ 10 until the start) | ✓ exact |
| Notification Next/Previous *action* | ✓ for in-window targets; ✗ wrong at ring wrap / out-of-window | ✓ façade translates logical → physical |
| Lock screen controls | same as notification | ✓ |
| Widgets | ✓ | ✓ |
| Media-button next/previous (headset) | same as notification | ✓ |
| External controller queue length / index (`mediaItemCount`, `currentMediaItemIndex`, `timeline`) | ✗ **reports W and a window-relative index** | ✓ reports N and the logical index |
| External controller `seekTo(index)` / `setMediaItems` / `addMediaItems` | ✗ window-relative / acts on the window | ✓ façade interprets logically |
| Repeat reported to controllers | ✗ physical OFF vs logical ALL | ✓ façade overrides (like `LogicalPlayWhenReadyOwner`) |
| Android Auto | not a supported surface | n/a |
| Session timeline events consistency | ✓ | ✓ (must emit window-shift as *no* logical event) |

**Conclusion [P]:** strategy A keeps every *in-app* and *transport* behaviour correct and is the right vehicle for P5-B…D, but it
**violates the "public queue stays logically complete" contract for external controllers**. Therefore activation requires
strategy B (P5-E). Strategy A must never ship as the default.

### 9.3 Logical façade design (strategy B) [P]

`SessionFacade` is already "the stable session-facing Player" and a `ForwardingSimpleBasePlayer` that can accept **any**
`Timeline` through `State.Builder.setPlaylist(timeline, tracks, metadata)` (the P4 `AliasedTimeline` change already uses it).
Proposal:

- `LogicalQueueTimeline : Timeline` — a lazy, zero-per-item-allocation view over a read-only `LogicalQueueView`
  (`size`, `songAt(i)`, `logicalGeneration`). `getWindow(i)` builds one `Window` on demand from `songAt(i)` (the same
  `toPlaybackMediaItem()` shape), uid = `LogicalWindowUid(generation, i)`; `getPeriod`/`getUidOfPeriod`/`getIndexOfPeriod` use
  the P4 hash-safe scheme. It is the P4 `OccurrenceTimeline` idea, with the physical player replaced by the logical queue.
- `SessionFacade.getState()` overrides `currentMediaItemIndex`/`playlist`/`repeatMode`/available commands with logical values
  when the engine reports Bounded mode (same pattern as the existing `LogicalPlayWhenReadyOwner`).
- `SessionFacade.handleSeek/handleSetMediaItems/handleAddMediaItems/handleRemoveMediaItems/handleMoveMediaItems` already exist
  (they call `commandBoundary`); in Bounded mode they delegate to a `LogicalQueueCommandPort` instead of forwarding to the
  physical player. `PreviousBehaviorPlayer`'s next/previous hooks stay where they are.
- **PlayerController ↔ port.** In Bounded mode PlayerController stops issuing index-bearing playlist commands through its
  `MediaController`. It calls an in-process `PhysicalWindowPort` (registered by `PlaybackService` the same way
  `setExplicitSeekListener`/`setControllerDisconnectedListener` are today) for window installs and edits, and keeps the
  `MediaController` for transport and state (`play/pause/seekTo(position)/isPlaying/currentPosition`). Because the façade then
  reports the *logical* index, the many `controller.currentMediaItemIndex == logical` sites in `PlayerController` become true
  **by construction** and need no per-site translation (§2.8, class 2).
- **[Q]** How Media3 1.11.1 transmits an N-window timeline to controllers (size limits/slimming) is not verified; a 25,000-window
  timeline may already be truncated or expensive today. Measure `controller.mediaItemCount`/`currentTimeline.windowCount` at
  25k before relying on either strategy. If Media3 already slims timelines, strategy B's laziness is even cheaper.

### 9.4 Behaviour that cannot be preserved without strategy B [P]

External controllers' notion of queue length, index, repeat mode, and the semantics of their `seekTo(index)`, `setMediaItems`,
`addMediaItems`, `removeMediaItem`. There is no way to keep these correct through the current architecture with a window-only
physical playlist. They are the activation blocker called out in §12.

---

## 10. Persistence and restoration

### 10.1 What is saved [P, unchanged contract]

Only the **logical** session (`PlaybackSessionSnapshot`): full queue ids, `playbackOrder`, current song id + library index,
position, repeat, shuffle, `updatedAtMs` — and (existing) `queueSource` is not persisted. The physical window is **never**
persisted and is never a second source of truth; it is derived on demand from
`(playbackQueue, currentPlaybackIndex, repeatMode, PhysicalWindowPolicy)`. Play/pause intent is already not persisted as a
state (`hydration never decides whether playback starts`, `docs/ARCHITECTURE.md` §4.3) and that is unchanged.

### 10.2 Restoration [P]

1. `restoreSessionIfNeeded` / `ensurePlayerHydratedFromSession` / `PlaybackResumptionMapper.map` rebuild the logical queue
   exactly as today (the same occurrence-safe rules: `resolveStartLibraryIndex`, `restorePlaybackOrder`).
2. Instead of `setMediaItems(playbackQueue)`, they install **the window around the restored current occurrence**, with the saved
   position, `prepare()` and no `play()`.
3. `onPlaybackResumption` returns the window slice and its start index (strategy A) or the logical start (strategy B).
   `adoptPlaybackResumption` keeps mirroring the logical queue.
4. Crash/kill mid-shift is harmless: the persisted snapshot is logical and revision-gated (`PlaybackSessionPersistenceGate`,
   latest wins), so the next restore rebuilds a fresh window. No window state exists to corrupt.

### 10.3 Known limits that remain [C]

- Save and restore remain **O(N)** (CSV in DataStore; `mapSavedQueue` builds `associateBy` over the whole library). Not addressed here.
- **A single missing library song makes the whole restore return `null`** (`mapSavedQueue` returns `null` if any id is absent).
  This pre-existing behaviour is orthogonal to the window but becomes more visible with very large queues; recorded in §14 (Q8).
- Interrupted persistence: unchanged; at worst the previous complete snapshot is restored.

---

## 11. Performance validation

### 11.1 Method

- **JVM/Robolectric** (shape and operation counts only, never device latency): extend the existing opt-in harness
  (`CrossfadeTimelineIdentityBenchmarkTest`, enabled with `WAVDROP_BENCH=1`) and `NextSlotPerformanceTest`'s structure. Primary
  gates are **deterministic structural invariants** (item counts, call shapes, materialized-item counts, distinct hash codes),
  not wall-clock thresholds. [C] `NextSlotPerformanceTest` is known to be load-sensitive; no new test may copy its ratio
  assertions.
- **Android device** (the only valid latency/heap evidence): debug build tracers already exist — `WavdropStartTrace`
  (`PlaybackStartupTrace`, `docs/diagnostics/P0-PLAYBACK-STARTUP-TRACE.md`), `WavdropQueuePerf` (install timings), plus
  `WavdropSessionPerf`; heap via `adb shell dumpsys meminfo` and Android Studio allocation tracking; trace with Perfetto.
  Distinguish cold/warm controller and release vs debug builds.
- **A/B on equivalent workloads:** the debug mode override (§12.1) runs the same scripted workload (tap, Next ×k, jump, shuffle,
  restore) in Full and Bounded on the same device and library.
- Sizes: 1,000 / 5,000 / 10,000 / 25,000, and 50,000 where the device allows it. [C] 50,000 exceeded the JVM test heap in P4's
  harness with two real players, so 50k is **device-only**.

### 11.2 Metrics (measured separately)

| Metric | Source | Full (baseline) | Bounded target |
|---|---|---|---|
| Tap-to-PLAY latency | `WavdropStartTrace` `request_received → play_issued/first audio` | capture | ≤ Full, ~flat in N |
| Time to first playlist installation | `playlist_install_started → done` | capture | flat in N |
| Media3 item count (CURRENT, NEXT, controller) | `mediaItemCount`, structural test | = N | ≤ `W_MAX + REFILL_BATCH` |
| Media-item materialization | `materialize_done` (`hits/misses`) | O(N) | ≤ W per install, ≤ REFILL_BATCH per refill |
| Heap / allocation pressure | meminfo, allocation profiler, GC logs | capture | lower; no recurring O(N) allocation |
| Next / Previous latency | trace + instrumentation | capture | flat |
| Queue jump latency | trace | capture | one bounded install |
| Window refill latency + main-thread burst | new `WavdropWindowPerf` debug tag | n/a | per-turn burst ≤ a few ms |
| Crossfade prepare + promote | `NextSlot` timing, `CrossfadePromotionRuntime` | O(N) graft | O(W) |
| Session restore time | `WavdropSessionPerf` | capture | install O(W); id mapping still O(N+L) |
| Logical mutation cost | op timing | O(N) | O(N) (unchanged, §15) |
| Poll cost | allocation per 250 ms | O(N) copy | O(1) (P5-A0) |

**There is no device baseline in the repository for any of these.** Capturing the Full-mode baseline on the owner's device is
the first deliverable of P5-A0/P5-F and is a prerequisite for any claim of improvement.

### 11.3 Deterministic correctness tests (outline)

- Pure: `PhysicalWindow` mapping round-trips (including ring, duplicates, window of length 1, `start` at 0 and `n-1`),
  `PhysicalWindowPlanner` (refill/trim decisions as pure functions of window + policy).
- Real Media3 (reuse `RealMedia3QueueRig`, `RecordingPlayer`, `TestMediaSource`, the `AuthorityRig` pattern): window install,
  AUTO transition + refill, jump re-anchor, every row of §7.2, shuffle toggle around current, promotion with a shifted window,
  hydration, cold widget.
- Guards: no >`W_MAX+REFILL_BATCH` install in Bounded mode; no `syncPlayerQueueAt` in Bounded mode; no index passed to Media3
  that did not come from `logicalToPhysical`.

---

## 12. Feature gate and rollback

### 12.1 Gate [P]

```kotlin
internal object PhysicalQueuePolicy {                       // sibling of CrossfadeRolloutPolicy
    const val BOUNDED_WINDOW_ENABLED = false                // build-time; default OFF
    const val FULL_MODE_MAX = 300
    fun modeFor(queueSize: Int): QueueMode =
        if (BOUNDED_WINDOW_ENABLED && queueSize > FULL_MODE_MAX) QueueMode.Bounded else QueueMode.Full
}
```

- **Where evaluated:** exactly once per queue install, at the four places a queue is born — `playFromQueueInternal`,
  `playPreservedSearchPlan`, `ensurePlayerHydratedFromSession`, `adoptPlaybackResumption`. The result is stored with the logical
  queue (`queueMode`) and is read-only for the life of that `queueGeneration` family.
- **When a mode may be selected:** only at install. A mutation never flips an active queue Full ⇄ Bounded mid-playback.
  *Growth past `FULL_MODE_MAX` in Full mode* is allowed to keep today's behaviour (the existing chunked reconciler) **[Q2]**; the
  alternative — an in-place trim conversion (bounded `removeMediaItems` of far-ahead items, never touching current) — is
  specified in P5-D as optional.
- **Debug/test override:** a `BuildConfig.DEBUG`-only property to force a mode so both implementations run under identical
  workloads (§11 perf plan). Release builds read only the constant.
- **No user-facing setting.** No new UI.
- In Full mode the new code is **never executed** except the trivial `modeFor` call, which keeps the gate-off build byte-for-byte
  equivalent in behaviour.

### 12.2 Rollback [P]

1. **Build-level:** set `BOUNDED_WINDOW_ENABLED = false` and ship. A process started by the new build restores the **logical**
   session (the only thing persisted) and installs a Full queue; there is no window state to migrate.
2. **Runtime unprovable mapping (Bounded):** re-anchor (§5.3); the legacy full re-push stays forbidden in Bounded mode, so the
   fallback is never an O(N) install.
3. **No dual persistence:** there is nothing mode-specific on disk, so rollback cannot corrupt a persisted session.

### 12.3 Preconditions to activate on a real device

All must be green and recorded:

- **G1** Mapping invariants I1–I8 covered by pure tests and by real-Media3 tests (shuffle, duplicates, repeat ALL ring).
- **G2** No `setMediaItems`/`replaceMediaItems`/`addMediaItems` call with more than `W_MAX + REFILL_BATCH` items anywhere in
  Bounded mode (operation-shape guard using `RecordingPlayer`).
- **G3** Strategy B (logical façade) landed: external controllers report N items, logical index, logical repeat; the P4
  distinct-hash invariant holds at N = 25,000.
- **G4** Crossfade: X1–X5 under promotion, repeated promotion, cancellation and edit-during-preparation, with two real ExoPlayers.
- **G5** Restoration: process death → window rebuild → correct occurrence/position for duplicates and `current == n-1`.
- **G6** Full mode bit-identical in behaviour to the baseline (existing suites green with the gate on and queues ≤ 300).
- **G7** Device measurements (§11) show Bounded ≤ Full on every item and no regression in gapless/crossfade audio.
- **G8** Rollback rehearsed: flip the gate off over a persisted Bounded session and confirm a clean Full restore.

---

## 13. Implementation roadmap

Each slice is independently reviewable, ends green on the full debug suite plus debug/release compilation, and leaves the gate
OFF until P5-F.

### P5-A0 — Stop copying the queue on every crossfade poll (independent, optional first)

- **Files:** `playback/CrossfadeRuntimeSnapshot.kt`, `playback/PlayerController.kt` (`captureCrossfadeRuntimeSnapshot`).
- **Invariant:** `playbackQueue` is replaced, never mutated in place (verified); the snapshot may hold the reference.
- **Tests:** snapshot identity test; structural test that two captures share the list; existing crossfade suites.
- **Dependencies:** none. **Rollback:** revert. **Done when:** no `toList()` on the poll path; suites green. Also captures the
  Full-mode device baseline (§11) — it is the one slice that benefits users immediately.

### P5-A — Logical/physical mapping foundations (no behaviour change)

- **Files (new):** `playback/PhysicalWindow.kt` (value + `physicalToLogical`/`logicalToPhysical`), `playback/PhysicalWindowPolicy.kt`
  (constants + pure planner: refill/trim/re-anchor decisions), `playback/PhysicalQueuePolicy.kt` (gate, `QueueMode`); tests only
  otherwise. **No production call site changes.**
- **Invariants:** I1–I3, I7; policy decisions are pure.
- **Tests:** exhaustive pure tests (ring, duplicates, tiny/huge n, `start` boundaries, policy hysteresis); property-style
  round-trip tests.
- **Dependencies:** none. **Rollback:** delete files. **Done when:** pure suite green; nothing references the gate.

### P5-B — Controlled physical-window installation (behind the inactive gate)

- **Files:** `playback/PlayerController.kt` (`installLibraryStart`, `installPreservedSearchStart`, hydration paths choose
  Full/Bounded and install a window), new `playback/PhysicalWindowAdapter.kt` (owns `logicalToPhysical`, the only holder of index-
  bearing Media3 calls in Bounded mode), `playback/PlaybackResumption.kt`.
- **Invariants:** I1–I6, I8; Full mode untouched.
- **Tests:** real-Media3 install shape (count ≤ W, current maps), guard tests (no full push, no unmapped index), restore of 25k with
  duplicates, deferred start drain → window.
- **Dependencies:** P5-A. **Rollback:** gate stays off; revert. **Done when:** with the debug override on, a 25k queue starts and
  plays its first track with ≤ W items, and the suite with the override off is unchanged.

### P5-C — Window movement and navigation

- **Files:** `PhysicalWindowAdapter.kt` (refill/trim/re-anchor jobs, generation-bound), `PlayerController.kt`
  (`seekToPlaybackIndex`, `navigate`, `currentPlaybackIndex`, listener callbacks, `handlePendingAutomaticTransition`).
- **Invariants:** I4–I8; refill never touches the current item; one in-flight job; stale work cancelled by
  `bumpQueueGeneration`/`windowGeneration`.
- **Tests:** AUTO at window edge, burst Next, large jump, Previous at index 0 with repeat ALL (ring + re-anchor), repeat OFF end,
  short-track burst vs refill, refill failure/delay, stale callback after a shift.
- **Dependencies:** P5-B. **Rollback:** gate off. **Done when:** Next/Previous/jump/AUTO all pass the real-Media3 matrix at 25k
  with call-shape guards.

### P5-D — Queue-mutation integration

- **Files:** `PlayerController.kt` mutation sites (§7.2), `ShuffleQueueSyncPlanner.kt`, `PlaybackAssembly`/`PlayerEngine` untouched.
- **Invariants:** L-only/B-op/Rebuild classification; current-song protection; crossfade cancellation hooks unchanged in order.
- **Tests:** one real-Media3 test per row of §7.2 (before/inside/after the window), batch larger than W, shuffle toggle at 25k,
  delete-current at the edge, `OccurrenceMap`/`DeferredStart` rebasing with a shifted window. Optional: in-place Full→Bounded trim
  conversion on growth (Q2).
- **Dependencies:** P5-C. **Rollback:** gate off. **Done when:** the whole Slice 1–3 queue-correctness matrix passes in Bounded mode.

### P5-E — Crossfade, MediaSession façade and restoration integration

- **Files:** `NextSlotPreparation.kt`, `NextSlotPreparationDriver.kt`, `PlayerEngine.kt`, `CrossfadeRuntimeSnapshot.kt`,
  `CrossfadeTransitionRules.kt` (new unavailable reason), `SessionFacade.kt`, new `playback/LogicalQueueTimeline.kt`,
  `PreviousBehaviorPlayer.kt`, `PlaybackService.kt` (port registration, `onPlaybackResumption`).
- **Invariants:** X1–X5; G3; P4's distinct-hash invariant at 25k on the logical timeline.
- **Tests:** two-player lock-step mutation, promotion with a shifted window, repeated promotions, cancellation/rollback; façade
  reports N/logical index/logical repeat; external `seekTo(index)`/`add`/`remove` semantics; notification/headset Next-Previous;
  cold widget; `onPlaybackResumption`.
- **Dependencies:** P5-D. **Rollback:** gate off. **Done when:** G3, G4, G5 are green.

### P5-F — Device validation and activation

- **Work:** capture Full baseline, run the A/B scripted workloads (§11) at 1k–50k, rehearse rollback (G8), then flip
  `BOUNDED_WINDOW_ENABLED` in a dedicated, reviewable commit.
- **Dependencies:** P5-E. **Rollback:** flip the constant back. **Done when:** G1–G8 recorded in the QA checklist and the
  engineering backlog, and the owner signs off on the device results.

---

## 14. Open questions requiring evidence [Q]

| # | Question | How to resolve |
|---|---|---|
| Q1 | Right `W`, history/ahead split, `AHEAD_LOW`, `REFILL_BATCH` | device A/B at 50/100/200 with short-track and burst-Next scripts; heap and refill burst per turn |
| Q2 | Should a Full-mode queue that grows past `FULL_MODE_MAX` (e.g. `addAllToQueue` of 25k onto a small queue) be converted to Bounded in place (a bounded trim), or keep the existing chunked path? | prototype the trim in P5-D; weigh against "no mid-playback architecture switch" |
| Q3 | Generation-scoped logical uids vs a runtime `occurrenceSerial` array | only if a controller needs uid stability across edits |
| Q4 | Per-item heap/CPU cost in ExoPlayer (holders, `MediaSource` objects, prepared periods only for current+next) | heap profile at 100 vs 25,000 loaded items |
| Q5 | How Media3 1.11.1 transmits a very large timeline to controllers (limits, slimming) and what a 25k session does today | measure `mediaItemCount`/`timeline.windowCount` and binder payloads on a device |
| Q6 | Does `MediaItem` preload/prepare behaviour of the *next* item stay gapless when the next item is the last loaded one at a window edge? | real-Media3 + device gapless test at the edge |
| Q7 | Cache bound `MEDIA_ITEM_CACHE_MAX_SIZE = 12_288` — still needed when installs are ≤ 100 items? | cache hit-rate under the A/B workload |
| Q8 | Single missing library song fails the entire restore (`mapSavedQueue → null`) | owner decision; separate slice if desired (not the window's concern) |
| Q9 | `setPauseAtEndOfMediaItems` (sleep timer boundary) across a window shift | real-Media3 test |
| Q10 | Interaction of `FULL_MODE_MAX = 300` with real-world playlists (how common is 300–2,000?) | library-size telemetry is unavailable; ask the owner / sample libraries |

---

## 15. Explicitly deferred

- Persistence redesign (binary/delta snapshots, avoiding the O(N) save on every transition) — R8.
- Logical mutation cost (R7): replacing `List<Song>` rebuilds with persistent/paged structures.
- Library scan, database, search ranking and Search UI.
- Virtualizing the queue *UI* (the sheet already indexes a shared list; measure before deciding).
- Per-occurrence persisted identity (ARCHITECTURE: intentionally absent).
- Removing the single-missing-song restore failure (Q8).
- Any new crossfade feature or setting; any user-facing queue-mode setting.

---

### Appendix A — Existing tests that already guard adjacent behaviour (to be reused, not rewritten)

`app/src/test/kotlin/com/launchpoint/wavdrop/playback/`: `PhysicalQueueReconcilerTest`, `RealMedia3ReconcilerIntegrationTest`,
`RealMedia3QueueRig`, `LargeQueueHardeningGuardTest`, `PlayerControllerDeferredStartRealMedia3Test`,
`PlayerControllerOfflineQueueRealMedia3Test`, `PlayerControllerRealMedia3DirtyQueueTest`, `PlayerControllerBulkQueueTest`,
`QueueMutationTest`, `QueueNavigatorTest`, `OccurrenceMapTest`, `HandleSongDeletedQueueLogicTest`, `DeferredPlaybackCommandsTest`,
`NextSlotPreparationTest`, `NextSlotGraftTest`, `NextSlotChunkedGraftTest`, `NextSlotInvalidationTest`, `PlayerEngine*Test`,
`SessionFacade*Test`, `CrossfadeTimelineIdentityTest`, `CrossfadePromotion*Test`, `SleepTimer*Test`, `HydrationAuthorityTest`,
`PlaybackResumptionMapperTest`, `AuthorityRig`, `ColdPlayRealMedia3Test`.

### Appendix B — Glossary

- **Logical queue / occurrence** — `playbackQueue` and a position in it, qualified by `queueGeneration`.
- **Physical window** — the consecutive playback-order slice installed in a Media3 player.
- **Ring window** — a window that continues from the last logical index back to 0 (repeat ALL, Bounded mode only).
- **Re-anchor** — replace the physical playlist with a window around a (new) logical position.
- **Shift** — refill and/or trim without touching the current item.
- **Strategy A / B** — window visible to controllers / logical façade timeline over a private window.
