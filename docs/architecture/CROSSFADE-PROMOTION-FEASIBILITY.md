# ADR: Crossfade promotion architecture (CF-2M1 feasibility)

- Status: **Proposed**; the session-façade question was **verified by CF-2M2** (section 25). CF-2M1 itself changed no production code, test or gate.
- Date: 2026-10-04
- Baseline: `1d320a1` ("Project crossfade handoff positions safely"), `CrossfadeRolloutPolicy.RUNTIME_ENABLED = false`.
- Scope: can WavDrop promote the already-playing incoming physical ExoPlayer to be the logical current player,
  instead of playing a second copy of B and transferring audibility back to the primary?
- Dependency facts below were verified against the **actual Media3 1.11.1 binaries** in this repository
  (`gradle/libs.versions.toml: media3 = "1.11.1"`) by disassembling `media3-common`, `media3-exoplayer` and
  `media3-session` (`javap`). Statements marked **(verified)** come from that. Statements marked **(to verify)** are
  design assumptions that must be proven by a named slice before they are relied on.

---

## 1. Problem statement

Crossfade must start B early, overlap it with the end of A, and leave exactly one authoritative player playing B.
Physical validation of CF-2L4 still shows a small audible stutter around the handoff and occasional brief
play/pause disturbance. CF-2L1..CF-2L4 each removed a real defect (B rewind, seek storm, false divergence aborts,
projection through stalls) and the remaining artifact looks intrinsic to the ownership model, not to a constant.

## 2. Physical evidence

- CF-2L1: first overlap fine; ~1-2 s final-handoff stutter; B rewound at the seam (fixed by the natural-AUTO model).
- CF-2L2 device log: raw `Player.currentPosition` is frozen between sparse updates; false divergence caused an
  abort -> reseek storm (556 aborts vs 5 completes; one key cycled 354 times over >100 s; force-stopping the debug app
  restored phone smoothness). CF-2L4 fixed the storm with a projected position clock.
- CF-2L4 physical: timing generally correct, no obvious B rewind, reduced instability, but a small audible
  handoff stutter and brief play/pause disturbance remain.

Every one of those defects exists only because **two independently decoded copies of B exist and must be
re-aligned and swapped in audibility**. No tolerance (80/150/200/350 ms, 500/100 ms) is changed or proposed here.

## 3. BlackPlayer conceptual lesson (clean-room, concept only)

Two physical players, each holds one track. The player that starts B early simply *becomes* the current player at
fade start (roles swap; no seek, no second B, no B-to-B reconciliation, no final audible handoff). The old player
is stopped/reset and reloaded with the following track. Fade ends slightly before A naturally completes and
completion callbacks from the retiring player are ignored by identity. One engine owns audio focus; both players
share one audio-session id; the session follows the logical player, not a physical one.

Used conceptually only (see section 21 of the task). Nothing is copied: not its MediaPlayer APIs, timers,
singleton, queue container or MediaSessionCompat mechanics; WavDrop keeps Media3, positional occurrence identity,
the equal-power curve and its lifecycle tests.

## 4. Current WavDrop ownership (CF-2L4)

```
MediaLibrarySession (one)  --binds-->  PreviousBehaviorPlayer : ForwardingPlayer(P1)      [P1 fixed, forever]
        ^                                        |
        | MediaController (PlayerController)     +-- P1 ExoPlayer: holds the WHOLE logical queue, handleAudioFocus=true,
        |                                              own audioSessionId, EQ attached to P1's session
   NowPlaying / Stats / persistence              |
   all driven by P1 events via the controller    +-- S  ExoPlayer (CrossfadeSecondaryPlayer): single item [B], silent,
                                                       handleAudioFocus=false, own audioSessionId, never a session player

A on P1 ----fade out (P1.volume)----------------------->  P1 naturally advances to ITS OWN B (silent)
B on S  ----fade in (S.volume)  -------------------------> S plays B early
then:  wait AUTO+READY on P1 -> same-item seek P1 to S position -> 150 ms S->P1 gain transfer -> S abandoned
                (position clock, 80/200/350 ms thresholds, projection, 16 ms ticks, reconcile seeks)
```

Two Bs are decoded for the whole overlap, and the audible B changes decoders at the end. That final B->B ownership
transfer is the stutter surface.

## 5. Proposed promotion architecture

```
MediaLibrarySession (one, never rebuilt)
        |
        v
SessionFacade : ForwardingSimpleBasePlayer      <-- ONE stable Player identity for the service lifetime
        |   getState() synthesized from the LOGICAL CURRENT physical player; commands routed to it
        |   setPlayer(x) swaps the delegate (listener moved, state re-derived, listeners get the diff)
        |
  PlayerEngine (logical roles, single owner of focus / audio session / gains)
        |
        +-- Slot P1 (ExoPlayer)  role = CURRENT   holds full queue   gain g1
        +-- Slot P2 (ExoPlayer)  role = NEXT      holds [B] (+ queue graft)  prepared, paused, silent

 Before fade : P1(A) current; P2(B) prepared, READY, paused, volume 0, queue grafted around B
 Fade start  : P2.play() at gain~0          -> façade delegate := P2   (PROMOTION, once)
               P1 becomes RETIRING: tail stripped, fades 1->0, events ignored by identity
 Overlap     : g2 rises, g1 falls (equal-power); authoritative player = P2 (B)
 Fade end    : P1.pause(), clear media, role := NEXT slot; P2 gain = 1
 After       : P1 re-prepared on demand for the next armed transition
 There is no second B, no B seek, no B->B handoff, no projected clock, no takeover tolerance.
```

## 6. MediaSession-facing player options (verified against Media3 1.11.1)

| | A. Custom `BasePlayer` façade over 2 ExoPlayers | B. Raw `SimpleBasePlayer` | **C. `ForwardingSimpleBasePlayer` + `setPlayer`** | D. `MediaSession.setPlayer(newPlayer)` on promotion | `ForwardingPlayer` (today) |
|---|---|---|---|---|---|
| Verified API | `BasePlayer` abstract, ~60 methods | `SimpleBasePlayer` with `State.Builder` (`setPlaylist(Timeline,Tracks,MediaMetadata)`, `setPositionDiscontinuity(int,long)`, `setAudioSessionId`, `PositionSupplier.getExtrapolating`) | `androidx.media3.common.ForwardingSimpleBasePlayer(Player)` exists in 1.11.1; `protected final setPlayer(Player)` + `getPlayer()`; overrides all `handle*` and `getState()` | `MediaSession.setPlayer(Player)` is `public final` | `private final Player player` - **no dynamic delegate possible** |
| Dynamic delegate | manual | manual | **Yes.** `setPlayer`: same-looper check (IllegalArgumentException otherwise), move listener, re-init forwarding state, `invalidateState()` | session-level only | **No** |
| Feasibility | high effort | medium | **high, least code** | high | infeasible for swap |
| Session continuity | stable if one façade | stable | **stable: one Player object, one session, one token** | session/token kept, but sends `ControllerCb.onPlayerChanged` (full controller resync) and restarts the legacy stub | n/a |
| Command routing | manual | `handle*` futures | **forwarded to current delegate by default; override to add WavDrop policy** | direct to new player | direct |
| Listener/event behaviour | manual ListenerSet + manual diffs | derived from state diffs (free, deterministic) | derived from state diffs; **a promotion is exactly one state diff**; reason pinned via `setPositionDiscontinuity(AUTO_TRANSITION, 0)` in an overridden `getState()` | controllers infer events from a resync diff (reason not authoritative) | native |
| Timeline | delegate's real timeline | synthesized or copied | **delegate's real timeline** (via `getCurrentTimeline`) | new player's | real |
| currentMediaItem/position | delegate | `PositionSupplier` | **live position suppliers** over delegate (`LivePositionSuppliers`) | new player's | real |
| Notification / AVRCP / Auto | same session -> unchanged | same | **same session, same player identity -> unchanged; AVRCP sees a normal gapless AUTO transition** | notification manager sees a "player changed" | unchanged |
| Complexity | high | medium | **low-medium (WavDrop-specific hooks only)** | low | n/a |
| Risk | listener fidelity bugs | placeholder-state semantics | `@UnstableApi` class (verified annotation), unproven in this codebase; **to verify**: event fidelity at swap, `controllerForCurrentRequest` inside `handle*`, `getMaxSeekToPreviousPosition` | resync visible to every controller; sleep-timer/AUTO logic sees PLAYLIST_CHANGED | n/a |

**Recommendation: C.** Subclass `ForwardingSimpleBasePlayer` as `SessionFacade`. Option D is kept only as a documented
fallback (it keeps the service and token but is an observable "player replaced" resync). Option A and B are what C is
built on; no reason to re-implement them.

Hard requirement 6 (session identity): **achievable.** One `MediaLibrarySession`, one Player identity, no
`setPlayer` on the session, no service restart. Android sees playback continue; a promotion is a state diff inside one
player (media item changed, position reset to B's, `isPlaying` unchanged). **Limitations:** (a) the diff must be
engineered so `isPlaying` never flickers (both physicals are playing at the swap, so it holds); (b) the platform
`PlaybackStateCompat` is refreshed by Media3's normal path; its position anchor legitimately jumps to B's start.

## 7. Queue and timeline architecture

Decision: **physical players keep native multi-item playlists; the façade exposes the delegate's real Timeline.**
A BlackPlayer-style "single-item physical + synthetic logical timeline" is rejected (section 7.2).

### 7.1 Answers

1. `playbackQueue` / `libraryQueue` / `playbackOrder` / `queueGeneration` stay entirely in `PlayerController` (unchanged
   source of truth). The current physical keeps the mirrored Media3 playlist exactly as today, so
   `currentMediaItemIndex == playback index` still holds on **both** physicals.
2. A synthetic Timeline is not needed. `SimpleBasePlayer.State.Builder.setPlaylist(Timeline, Tracks, MediaMetadata)`
   (verified) lets the façade reuse the delegate's real Timeline. (A synthetic one is only needed in the rejected model.)
3. `seekToNext/Previous`: BasePlayer funnels to `seekTo(index, pos)` -> `handleSeek(index,pos,seekCommand)` on the façade
   -> current physical. The existing "explicit navigation cancels crossfade" rule (CF-2G3) is kept; no manual crossfade.
4. `seekTo(index,pos)`: same path; cancels any overlap/arm first, then seeks the current physical.
5. Shuffle/repeat: logical shuffle remains `playbackOrder` (Media3 shuffle stays off); repeat is mirrored to Media3.
   The NEXT slot copies the repeat mode when prepared. Repeat/shuffle changes cancel crossfade (CF-2H1/2H2, unchanged).
6. Duplicate occurrences stay positional: the key is `(queueGeneration, fromPlaybackIndex, toPlaybackIndex)`. Because
   the NEXT slot is grafted so that B sits at index `to` (section 7.3), indices are identical on both physicals.
   Promotion preconditions assert `nextSlot.mediaItemCount == queueSize` and `mediaId` at `to` (diagnostic only; identity
   remains positional, never song-id).
7. Queue mutations: every mutation family already cancels crossfade *before* mutating (CF-2H3A..G). Under promotion
   that cancel resets/recycles the NEXT slot. Mutations are applied to the current physical only. The NEXT slot is never
   mutated in place; it is rebuilt on the next arming.
8. System controllers read the façade, i.e. the current physical's real Timeline and metadata.
9. Physicals are single-item only transiently (NEXT slot before graft). Permanent single-item physicals are rejected.

### 7.2 Why not single-item physical players

It would regress ExoPlayer's native gapless (there is no `setNextMediaPlayer` equivalent in Media3; WavDrop has
physical gapless validation and `WavdropGapless` diagnostics), force a synthetic timeline of up to
`MEDIA_ITEM_CACHE_MAX_SIZE = 12_288` items, require re-implementing all queue mutation, shuffle/repeat and bad-media
recovery in an engine, and touch every `PlayerController` queue path. Blast radius is far larger than the problem.

### 7.3 NEXT slot preparation: graft

NEXT slot = `setMediaItem([B])` + `prepare()` (as today's secondary). When READY (still silent), graft the neighbours
with `addMediaItems(0, before)` and `addMediaItems(after)`. Adding items around a non-playing, already-prepared item
does not disturb it. Cost is O(n) `MediaSource` creation on the main thread for the second player, comparable to the
existing queue install (`WavdropQueuePerf`). **Open item:** measure on a large queue; if it is too costly the
fallback is a persistent mirror built once at queue install (memory trade-off, section 18).

## 8. Promotion moment

Options: A fade START, B midpoint, C fade END. **Recommend A: promote at fade start** (matches BlackPlayer).

| Concern | START | MIDPOINT | END |
|---|---|---|---|
| MediaSession current item / Now Playing | flips once, when B is audible | flips in the middle; pause/seek target ambiguous for half the fade | B audible all overlap while session says A (wrong) |
| Stats / scrobble | one AUTO transition: A credited up to fade start, B counted once at its start | same, later | A over-credited, B under-credited |
| Position display | B's position from 0 (A tail audible under B) | jump mid-overlap | jump at the very end, then B->B problem returns |
| Pause / seek / next | act on B (clear) | undefined before midpoint | act on A while B is audible |
| Mechanism | one swap | one swap + extra state | the current seam problem |

Consequence: A's listened time ends at fade start (loses <= configured overlap, max 12 s). Acceptable; recorded as an
open question for the stats owner (section 14).

## 9. Completion semantics and what becomes obsolete

Final seam target: B already authoritative; incoming gain reaches 1; A gain reaches 0; A stops, clears media, becomes the
NEXT slot (re-prepared on demand). No B seek/restart, no second B, no projected clock, no takeover tolerance, no
secondary->primary soft transfer.

Likely obsolete after promotion lands (**list only, nothing is removed in CF-2M1**):

- `CrossfadeNaturalHandoff.kt`: `decideNaturalTakeover`, `naturalTransferProgress/Gains`, `NATURAL_TAKEOVER_MAX_LAG_MS` (350),
  `NATURAL_TRANSFER_ENTRY_TOLERANCE_MS` (80), `NATURAL_TRANSFER_ABORT_TOLERANCE_MS` (200),
  `NATURAL_TAKEOVER_TRANSFER_DURATION_MS` (150), `CrossfadeHandoffWait` (NaturalTransition, PrimaryReady, PrimarySeek,
  OwnershipTransfer, PositionConfidence), `PrimaryTakeoverFacts`, `isPrimaryPlaybackAdvancing`.
- `CrossfadePositionClock.kt` (`NaturalHandoffPositionClock`, `PositionSampleDecision`,
  `NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS`, `NATURAL_HANDOFF_RAW_AGREEMENT_MS`).
- `CrossfadePrimaryReconciliation.kt` and `PlayerController.reconcileCrossfadePrimary` /
  `reconcileCrossfadePrimaryAfterNaturalTransition` / `reconcileCrossfadeNowPlayingState`.
- In `CrossfadePreparationRuntime`: `executeNaturalHandoff`, `observePositions`, begin/continue/complete/abortTransfer,
  abort reasons, `RECONCILE_*`/`TRANSFER_*`/`POSITION_*` diagnostics, the `HandoffPending` lifecycle (replaced by an
  overlap/promoted state), `observeCrossfadeNaturalTransition`, `advanceCrossfadeHandoff`, the "handoff seek lead" state.
- `CrossfadeTimingDriver`: 50 ms handoff wait and 16 ms transfer tick (the 250 ms pre-fade poll and fade tick remain).
- `SecondaryHandoffSnapshot`, `validatedSecondaryHandoffSnapshot`, `CrossfadeSecondaryPlayer.handoffSnapshot`.
- `CrossfadeAudioSessionObserver` (CF-2I1) once the session id is shared.
- Tests: `CrossfadeNaturalHandoffTest`, `CrossfadePositionClockTest`, `CrossfadeAdvancementEligibilityTest`,
  `CrossfadeNaturalTransferEnvelopeTest`, `CrossfadePostAutoReconciliationPlannerTest`, and the handoff parts of
  `CrossfadeSecondaryStartTest` / `CrossfadeSettlementTest`.

**Kept and reused:** `CrossfadeGainCurve.equalPower`, `CrossfadeTransitionKey`, `planCrossfadeTransition` and eligibility rules,
`CrossfadeRuntimeSnapshot`, the whole cancellation family (CF-2F*/2G*/2H*), `reduceCrossfade` (states adapted),
`CrossfadeRolloutPolicy` / `CrossfadeProductionReadiness`, the timing-driver skeleton, the settlement concept
(`isCrossfadeFullySettled`; "Idle alone is not proof"), `CrossfadePrimaryGainController` (generalised to a per-slot,
key-owned gain controller), `CrossfadeSecondaryPlayer` (generalised to the NEXT-slot prepare/start owner).

## 10. Natural completion of A

- The overlap must finish **before** A's natural end. Target: `fadeStart = duration - D - END_MARGIN`. `END_MARGIN` is a
  new parameter to be chosen from measurement in CF-2M5 (BlackPlayer uses ~100 ms; start higher, e.g. 250 ms, and measure).
  It is not an existing constant and is not derived from the 80/150/200/350 family.
- Old A can simply be paused after its gain reaches 0. P1's natural AUTO into its own B is prevented because P1 is
  paused at fade end.
- **Architectural elimination of the race (preferred over a callback guard):** at promotion, strip P1's tail
  (`removeMediaItems(a + 1, end)`). If a fade overruns A's end for any reason, P1 reaches `STATE_ENDED` instead of an AUTO
  transition into a second B. P1 is retiring and discardable, and the strip does not touch the playing item.
- Old-player callbacks are ignored **structurally**: the façade registers its listener only on the logical-current
  physical (`setPlayer`), so `STATE_ENDED` / errors from the retiring player never reach logical consumers. They are only
  observed by the engine's identity-tagged physical observer and are classified as "retiring, ignore and recycle".
- Physicals are not made single-item permanently (section 7.2); the tail strip is the minimal equivalent.

## 11. Play / pause during overlap (architecture only)

Overlap states: **Armed/Ready** (NEXT prepared, no promotion) and **Overlap** (promoted, P1 retiring).

| Event | Armed / Ready (before promotion) | Overlap (after promotion; B is current) |
|---|---|---|
| Pause (user) | unchanged: CF-2G1 cancel, recycle NEXT, then pause current | one pause to B; retiring A cut immediately (pause+clear); B gain forced to 1 (inaudible while paused); overlap ends; B position preserved; no seek |
| Resume | normal play of current; re-arm later | normal play of B at full gain (overlap already ended) |
| NEXT / PREVIOUS | CF-2G3 cancel, then normal navigation | cancel overlap, cut A, then normal navigation from B (no manual crossfade) |
| Explicit seek | CF-2G2 cancel, then seek | cancel overlap, cut A, seek B, restore B gain 1 |
| Audio focus loss / noisy | existing CF-2F5 interruption cancel | engine pauses logical current B with the matching Media3 reason, cuts A, ends overlap |
| Duck (transient, can-duck) | n/a | engine multiplier applies on top of both fade gains (section 12) |

Properties: exactly one user command reaches exactly one player; no playback-state oscillation (A is cut, never
"paused then resumed"); no B seek; no position reconstruction.

## 12. Audio focus

Today P1 `handleAudioFocus=true`, secondary `false` (secondary never competes). With **two promotable players** the
current design cannot persist: if P2 were also a focus holder, `P2.play()` makes a second request from the same app and
the first holder receives a loss and pauses itself mid-fade; and if only P1 held focus, pausing/recycling P1 abandons
focus while P2 plays.

**Recommended ownership: one engine-owned focus.** Set `handleAudioFocus=false` on **both** physicals and own focus in
the engine using Media3's own `androidx.media3.common.audio.AudioFocusManager` (**verified public in media3-common
1.11.1**: `AudioFocusManager(Context, Looper, PlayerControl)`, `updateAudioFocus(playWhenReady, playbackState)`,
`getVolumeMultiplier()`, `setAudioAttributes`, `release`; `PlayerControl` = `setVolumeMultiplier`, `executePlayerCommand`).
Mapping: `executePlayerCommand` -> façade `setPlayWhenReady` with the matching `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS`
and `PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS` (so CF-2F5's `classifyPlayWhenReadyInterruption` keeps working);
`setVolumeMultiplier` -> multiplies both slot gains. `setHandleAudioBecomingNoisy` stays on the engine/logical layer only.
The class is `@UnstableApi` (verified). **(to verify)** that it is usable outside ExoPlayer and that suppression reasons surface
through the façade state identically to today.

## 13. Audio session / EQ

- `ExoPlayer.setAudioSessionId(int)` **exists** in 1.11.1 (verified: abstract on `ExoPlayer`), and
  `SimpleBasePlayer.State.Builder.setAudioSessionId` exists for the façade. Sharing one id across two ExoPlayers is
  technically supported.
- Assign **at construction, before any `prepare()`**, using one id from `AudioManager.generateAudioSessionId()`; changing
  it on a prepared player re-creates its AudioTrack (a glitch). **(to verify on device).**
- `AudioEnhancementController` then attaches the Equalizer once to the shared id and it applies to whichever slot is
  current, with no role-following needed. This is also the only plausible route to removing the CF-2I2 restriction
  ("crossfade unavailable while EQ enabled"), because today the secondary has its own session and no EQ.
- Risks: effect chains on a session with two live tracks (the effect applies to the mix; OEM effect libraries differ),
  effect orphaning while no track is alive, offload/tunneling modes, `onAudioSessionIdChanged` plumbing in
  `AudioEnhancementController.attach(player)` which currently reads one player's id.
- BlackPlayer's legacy MediaPlayer behaviour does not transfer automatically; this is a Media3-specific claim that
  needs device proof. **Decision: keep the EQ restriction through promotion slices.** Lifting it is its own slice (CF-2M9)
  after device validation.

## 14. Stats / Now Playing

Today all logical consumers hang off one place, which makes the façade a clean fit: `PlayerController` is a
`MediaController` on the session, so StatsTracker, NowPlayingState and session persistence follow **session player events**
already. `PlaybackService` also attaches a direct listener to P1 for widget state.

| Consumer | Today's trigger | After promotion |
|---|---|---|
| `NowPlayingState` | `onIsPlayingChanged`, `onMediaItemTransition`, `onPositionDiscontinuity`, `onPlaybackStateChanged` via controller | unchanged: façade emits the same events |
| `StatsTracker` song / play / pause | `onMediaItemTransition` (+ AUTO discontinuity), `onIsPlayingChanged` | one AUTO transition + one AUTO_TRANSITION discontinuity at promotion, exactly the existing native-gapless path; `isPlaying` stays true so no pause/play pair |
| Session persistence | `saveSessionAsync` on transition/discontinuity/pause | unchanged (one save at promotion) |
| Widget state | direct `player.addListener` in `PlaybackService` | **move to the façade listener** (logical) |
| Notification / session metadata | Media3 session on P1 | same session on the façade |
| Crossfade hooks (error, interruption, terminal state, READY, transition) | direct on P1 | per-physical observer, tagged by slot; only the **current** slot's errors/terminals count |

Must-not list is met by construction: B is counted once (single AUTO transition), never before promotion (P2 is not
observed by logical consumers while NEXT), no repeated "started" (no `isPlaying` edge), one session save, listened duration
not reset (no discontinuity other than the AUTO one), no fake pause/play (A is cut, not paused-through-the-façade).
**Open:** A's listened time stops at fade start; confirm product acceptance.

## 15. Listener ownership

- **Physical concerns** (engine-owned, per-slot, identity tagged): `onPlayerError`, readiness (`STATE_READY`/`BUFFERING`)
  for NEXT-slot prepare callbacks, `onAudioSessionIdChanged`, decoder/renderer events, retiring-player `STATE_ENDED`.
- **Logical concerns** (façade-only): current media item and `onMediaItemTransition`, `onPositionDiscontinuity`,
  `isPlaying`/`playWhenReady`/`playbackState`, repeat/shuffle, timeline and metadata, available commands.
- Rule: **only the façade is registered by the session, widgets and any logical consumer; only the engine registers on
  physicals.** `ForwardingSimpleBasePlayer` makes this structural: it listens to exactly one physical at a time.

## 16. Error handling (fail closed to one authoritative player)

| Failure | Behaviour |
|---|---|
| NEXT (P2) preparation fails before fade | existing `onSecondaryFailed`; this transition is not crossfaded; P1 continues and advances natively (gapless unchanged). A benefit of keeping multi-item physicals. |
| B errors during overlap (after promotion) | B is logical current: normal `PlayerController.recoverFromCurrentPlaybackError` runs; retiring A is cut; one player remains |
| Retiring A errors | not authoritative: ignore, cut and recycle; B continues; DEBUG log only |
| Preparing the following transition's NEXT fails | affects only that arming; no playback effect |
| A physical current dies / is released | engine rebuilds an ExoPlayer, reinstalls queue/index/position from `PlayerController` (same as hydration), `setPlayer(rebuilt)`; if impossible, façade reports the error and playback stops cleanly |

Existing bad-media principles (bounded, occurrence-safe, queue preserved, one message per episode, no fabricated stats)
are untouched because recovery runs on the façade's current player exactly as now.

## 17. Cold restore

`onPlaybackResumption` returns `MediaItemsWithStartPosition`; Media3 applies it through the session player (the façade ->
`handleSetMediaItems` -> current physical). Queue, current index, position, logical shuffle order and repeat mode are
restored by the existing mapper exactly as today. **Confirmed with one nuance:** the proposed "current slot = restored item,
next slot = automatic next occurrence" holds for the *logical* model, but the NEXT physical is **not** prepared at startup.
It is prepared lazily by the existing arming flow (`planCrossfadeTransition` computes the resolved next occurrence), which
avoids a second decoder and a second queue graft during cold start. The cancel on adopted resumption (CF-2H3G) stays.

## 18. Performance (vs CF-2L4)

Removed: reconciliation seeks, position projection and clock, 50 ms natural-takeover polling, 16 ms B->B transfer ticks,
duplicate B decode after promotion, abort/reseek loops. Added/kept: two prepared ExoPlayers (as CF-2L4 already has),
fade-gain ticks, one queue graft per arming, façade state derivation.

| | CF-2L4 | Promotion |
|---|---|---|
| CPU during overlap | two B decodes + polls + transfer | two decodes for the overlap only, A decode ends at fade end |
| CPU after overlap | secondary B kept until handoff completes | single decode immediately |
| Decoders | 2 for the overlap + transfer window | 2 for the overlap only |
| Memory | second player with one item | second player with one item **plus graft** (O(n) MediaSource); fallback persistent mirror trades memory |
| Battery | extra polling/seek churn | lower |
| Main thread | 16 ms/50 ms handoff ticks, seeks | 16 ms/50 ms fade ticks only; graft is the new burst (to measure) |

## 19. Migration plan (bounded, each stage buildable and testable; gate stays false)

| Slice | Content | Checkpoint |
|---|---|---|
| **CF-2M2** | Stable `SessionFacade : ForwardingSimpleBasePlayer` over a **single** physical. Behaviour parity only. Moves widget listener onto the façade. Port `PreviousBehaviorPlayer` hooks (`handleSeek`, `handleSetPlayWhenReady`, external-controller detection, max-seek-previous). **First task: parity spike with hand-written fakes + Robolectric looper** to settle the "to verify" items (event fidelity, `controllerForCurrentRequest` inside `handle*`). | JVM tests + device parity (notification, BT, lock screen, widget) |
| **CF-2M3** | `PlayerEngine` + slot roles (two ExoPlayers, engine-owned `AudioFocusManager`, shared audio-session id at construction, noisy-handling owner). No crossfade behaviour; façade `setPlayer` swap proven with fakes. | JVM tests, no behaviour change |
| **CF-2M4** | NEXT-slot lifecycle: prepare `[B]`, graft, invalidate on queue mutation (reuses CF-2H cancels), measure graft cost (`WavdropQueuePerf`). Generalise `CrossfadeSecondaryPlayer` and the gain controller. | tests + graft measurement |
| **CF-2M5** | Promotion at fade start, overlap gain execution, tail strip, fade-end margin, retire/recycle, façade event pinning (AUTO + AUTO_TRANSITION, `isPlaying` constant), stats/persistence parity tests. | JVM tests |
| **CF-2M6** | Overlap lifecycle policy: pause/seek/next/previous/focus/noisy/error/physical-death (section 11 & 16), settlement. | JVM tests |
| **CF-2M7** | Temporary physical validation build (same procedure as prior validation APKs). | device evidence |
| **CF-2M8** | After a physical pass: delete the CF-2L natural-handoff stack (section 9), reconcile docs. After a physical **fail**: delete the promotion code instead. | tests + docs |
| **CF-2M9** (optional) | Shared-session EQ validation; decide whether to lift the EQ restriction. | device evidence |

## 20. Build beside vs refactor in place

**Recommend A: build the promotion engine beside CF-2L, then delete the old handoff.** CF-2L4 is unshipped and gated; its
cancel/eligibility/planning layers are reused by the new design, but its handoff core cannot be incrementally morphed into
promotion (different ownership model, different states). Building beside keeps the CF-2L4 tests as a regression oracle
for the shared layers, and the single rollout gate (`RUNTIME_ENABLED`) stays the only switch: the new construction path is
selected behind the existing gate, not a new flag. To avoid carrying two permanent architectures, **CF-2M8 is mandatory
and tied to the physical result**: pass -> remove CF-2L handoff; fail -> remove the promotion code. The façade is constructed
only when the gate is true during CF-2M2..CF-2M8 (as the crossfade graph is today), so shipping behaviour is untouched
until rollout; at rollout the façade becomes the unconditional session player.

## 21. Lessons used / not used

Used conceptually: prepared second player, the early player *is* B, role promotion at start, recycle old for next, one
logical audio-focus owner, retiring-player completion ignored, session follows logical player. Not used: MediaPlayer APIs,
`CountDownTimer`, synchronized singleton, obfuscated code, queue container, `MediaSessionCompat` mechanics, method bodies,
linear fade. WavDrop keeps Media3, occurrence-safe identity, equal-power fade, lifecycle tests, modern Bluetooth handling,
cold resumption and settings architecture.

## 22. Risks

1. `ForwardingSimpleBasePlayer` is `@UnstableApi` (verified) and unproven in this codebase: event fidelity at swap is unproven. Mitigation: the
   CF-2M2 parity spike is the go/no-go for the whole direction; fallback option D.
2. Porting `PreviousBehaviorPlayer` semantics (external vs app controller detection depends on `controllerForCurrentRequest`
   being valid inside `handle*`).
3. Engine-owned focus re-creates suppression/ducking semantics that CF-2F5 depends on.
4. Queue graft cost on large queues (up to 12,288 items) on the main thread.
5. Shared audio session across two simultaneous AudioTracks is device-dependent (EQ/effects).
6. The façade changes the production session path (even with crossfade off), so a regression there affects every user
   once unconditional; hence parity-first (CF-2M2) and gate-only construction until then.
7. Stats: A's listened time ends at fade start.
8. Fade must end before A ends: late-armed or very short tracks reduce or skip the overlap (existing eligibility rules; any
   change is a later slice, not a constant tweak).

## 23. Open questions

1. ~~Does `ForwardingSimpleBasePlayer.setPlayer` + an overridden `getState()` produce exactly one `onMediaItemTransition(AUTO)` and one
   `AUTO_TRANSITION` discontinuity with `isPlaying` unchanged?~~ **Answered yes in CF-2M2 (section 25).**
2. Is `androidx.media3.common.audio.AudioFocusManager` (`@UnstableApi`, verified) safe to drive from an engine outside ExoPlayer?
3. Measured graft cost for a large queue; graft vs persistent mirror.
4. Value of `END_MARGIN` and the retiring-player tail-strip behaviour on real files.
5. Product decision on A's listened-time accounting at promotion.
6. Is the shared-session EQ safe on target devices (decides CF-2M9)?
7. Android Auto / system controller behaviour for an in-place state diff (verify in CF-2M7).

## 24. Verdict

**GO, conditionally.** The architecture is feasible on the pinned Media3: a stable session-facing player with a swappable
physical delegate exists in 1.11.1 (`ForwardingSimpleBasePlayer.setPlayer`), `MediaSession` identity can remain stable,
`setAudioSessionId` and a reusable `AudioFocusManager` exist, and physical multi-item players preserve native gapless and the
real Timeline, so the queue stays in `PlayerController`. It removes the stutter surface structurally instead of tuning it.

**Gate on the CF-2M2 parity spike.** If façade event fidelity or `PreviousBehaviorPlayer` parity cannot be achieved
without behavioural drift, fall back to option D (`MediaSession.setPlayer`) or stop and re-evaluate. No tolerance change
is proposed anywhere in this plan, and no physical claim is made until a device retest.

## 25. CF-2M2 verification results (single-player parity spike)

Status of the CF-2M1 "to verify" items, proven by tests (hand-written `ScriptedPlayer`, a real Media3 `SimpleBasePlayer`
driven by explicit state; Robolectric main looper; a real `MediaSession` and real in-process `MediaController`s). **No device
was used.** Real ExoPlayer live-position behaviour, notification, Bluetooth/AVRCP and Android Auto were not exercised and
remain CF-2M7 evidence.

**Implemented (production):** `SessionFacade : ForwardingSimpleBasePlayer` (one physical delegate, nothing synthesized,
eager state snapshot at construction, the single swap seam `replaceDelegate(newDelegate, presentAsAutoTransition)`, explicit
same-looper `require`); `PreviousBehaviorPlayer` extracted unchanged into its own file (its two `PlayerController` calls became
injected functions); `WidgetPlaybackStateListener` extracted verbatim as the logical consumer.

**Chain actually built:** `ExoPlayer -> SessionFacade -> PreviousBehaviorPlayer -> MediaLibrarySession`. This differs from the
sketch in section 5/6, deliberately: the WavDrop policy wrapper stays **outside** the façade, so its decisions
(`controllerForCurrentRequest`, previous semantics, explicit-transport hooks) run synchronously on the session request exactly
as before and are not duplicated or ported into `handle*`. The physical player is the façade's swappable delegate.

**Gate decision: B (gate-bound).** The façade is constructed only when `CrossfadeRolloutPolicy.RUNTIME_ENABLED` is true;
with the gate false (shipping) the chain is exactly the pre-CF-2M2 `ExoPlayer -> PreviousBehaviorPlayer`. No second flag.
Smallest rollback surface while parity is proven only off-device.

**Findings:**

| CF-2M1 open question | Result |
|---|---|
| Façade remains the same object | Yes. |
| `controllerForCurrentRequest` inside `handle*` | **Works.** A probe `ForwardingSimpleBasePlayer` reads the real `ControllerInfo` (with the app connection hint) inside `handleSetPlayWhenReady`/`handleSeek`/`handleSetRepeatMode`, distinguishing the app controller from an external one. |
| `PreviousBehaviorPlayer` parity | Identical policy callbacks and identical physical commands with and without the façade, for both an app and an external controller (pause, play, same-track seek, next, previous-restart, previous-item, repeat). `getMaxSeekToPreviousPosition` is still advertised to controllers. |
| Single-delegate event parity | Exact equality with the player observed directly for pause, play, seek, AUTO transition, buffering, repeat, error, timeline. No extra transitions, discontinuities, pause/play edges or PLAYLIST_CHANGED. |
| `isPlaying` flicker in playing->playing swap | None (no `isPlaying`, `playWhenReady` or `state` event, at the façade and at a real controller). |
| Default swap sequence (playing P1(A) -> playing P2(B), same timeline) | `discontinuity(0@170000->1@1000, INTERNAL)`, `transition(B, PLAYLIST_CHANGED)`, `metadata(B)`. One coherent diff; no timeline event. |
| Swap presented as AUTO (`presentAsAutoTransition = true`) | `discontinuity(0@170000->1@1000, AUTO_TRANSITION)`, `transition(B, AUTO)`, `metadata(B)`. Same single diff with only its reason pinned via an overridden `getState()`; **nothing is fabricated**; the pin is one-shot (a later change carries no leftover discontinuity). |
| Same sequence as seen by a real `MediaController` through the session | `discontinuity(1@5000->2@1000, AUTO_TRANSITION)`, `transition(C, AUTO)`, `metadata(C)`; controller stays connected, `isPlaying` never flickers. |
| Different-timeline swap (NEXT slot not yet grafted) | Exactly one timeline event and one transition, no `isPlaying` event. This is why the CF-2M4 graft must finish before promotion. |
| Command routing after swap | After the swap commands reach only the new delegate; the old delegate receives none (including `release`). |
| Listener ownership | Old delegate changes (pause, ENDED, repeat) produce no façade event; new delegate changes do. |
| Looper contract | Same-looper replacement succeeds; a different looper throws `IllegalArgumentException` before any state change (delegate and events untouched). |
| Session identity | Same `MediaSession`, same session player object and token across a swap; no `MediaSession.setPlayer`; no rebuild. |
| Widget listener | Through the façade it makes exactly the same sink calls as when attached to the physical player; after a swap it follows the new item with no play/pause edge and ignores the retiring player. |

**Implementation notes learned:** `SimpleBasePlayer` snapshots state lazily, so the façade snapshots at construction (otherwise
a physical change before the first read is folded into that read and no event is emitted). A bare constant-position jump is
reported by Media3 as an `INTERNAL` discontinuity; real players supply live positions, which the façade forwards.

**Verdict for CF-2M3: GO.** Every CF-2M2 go criterion was met in JVM/Robolectric tests; none of the no-go conditions occurred.
Residual limits (device, real ExoPlayer swap, system UI) stay as CF-2M7 evidence.
