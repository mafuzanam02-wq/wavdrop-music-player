# P5-F3 — Bounded playback release-readiness audit

Documentation-and-evidence audit only. **No application code, test code, rollout gate, build script, APK or device was changed or
touched to produce this document.** Production bounded mode stays OFF. Nothing here activates it or authorises activating it.

Evidence labels used throughout:

* **[CODE]** read in the repository at the baseline commit.
* **[JVM]** automated test that exists in the repository; passing is taken from the local result files noted in §2.3.
* **[DEVICE-REPORT]** informal, owner-relayed statement from the beta tester. Not a measurement, not a log, not a sign-off.
* **[DEVICE-LOG]** captured `WavdropValidation` / `WavdropStartTrace` / meminfo output from a phone. **None exists yet.**
* **[UNKNOWN]** not recorded anywhere in the repository or the conversation.

## 1. Baseline

| Item | Verified value |
|---|---|
| Branch | `master` |
| HEAD | `7ad11a3003abba12b50cacb48fe44edf2ef9f70d` (matches the expected baseline) |
| HEAD vs `origin/master` | `0 0` (after `git fetch`) |
| Working tree | clean at start |
| `PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED` | `const val … = false` ([PhysicalQueuePolicy.kt:22](../../app/src/main/kotlin/com/launchpoint/wavdrop/playback/PhysicalQueuePolicy.kt)) |
| Validation override | `VALIDATION_BOUNDED = BuildConfig.WAVDROP_VALIDATION_BOUNDED`; debug field from `-PwavdropValidationBounded`, release field hard-coded `"false"` ([build.gradle.kts](../../app/build.gradle.kts)) |
| Crossfade runtime | `CrossfadeRolloutPolicy.RUNTIME_ENABLED = true`; `CrossfadeRolloutPolicy.kt`, `CrossfadeTiming.kt`, `CrossfadeProductionReadiness.kt` have **no diff** between `49098e6` (P5 design commit) and HEAD |
| Release version | `versionCode = 10`, `versionName = "0.1.0-beta10"` (already the shipped beta10 in [RELEASE_NOTES.md](../../RELEASE_NOTES.md)) |

Recent commits in scope: `be023c5` P5-A, `27a24af` P5-B, `1068a50` P5-C, `cc4728b` P5-D, `7cb44e0` P5-E, `c822151` P5-F validation
builds, `ee9aae0` explicit-selection autoplay, `7ad11a3` playback presentation.

## 2. Scope and evidence base

### 2.1 In scope
G1–G8 of design §12.3; the two recent correction slices; the release/debug boundary; performance evidence; the remaining device
test plan; an activation classification.

### 2.2 Out of scope
Implementing or fixing anything, changing thresholds or tests, building APKs, installing on devices, running the full suite, Android
Auto, a 25,000-track synthetic library.

### 2.3 What test evidence actually exists
* The repository records **no** test counts or pass/fail results for P5 (the design-doc implementation records say "tested", with no
  numbers). Test results are not committed.
* Local, untracked, git-ignored Gradle output `app/build/test-results/testDebugUnitTest/` holds one run: **310 suites, 4,042 tests,
  0 failures, 0 errors, 0 skipped**, timestamps 2026-10-27 21:39–21:51 UTC. It contains `ExplicitSelectionAutoplayTest` (26),
  `PlaybackPresentationTest` (14), `PlaybackToggleConsistencyTest` (12), `ValidationVariantTest` (14), the Bounded* (32 across 3 suites),
  PhysicalWindow* (164 across 8 suites), PhysicalQueue* (24 across 3), PlayerControllerBounded* (54 across 3) and
  `LogicalQueueSessionTest` (8). The HEAD commit was authored later (2026-10-28 06:18 UTC), but this run already contains the HEAD
  commit's new test classes, so it was run on the tree that became HEAD. **That the tree was byte-identical to HEAD is not proven.**
  A re-run on the exact baseline would remove the doubt; this audit did not perform one.
* This is Robolectric/JVM evidence against real Media3 1.11.1 and real ExoPlayers with trackless or fake sources. It proves logic and
  call shape, **not** audible behaviour, OEM behaviour, binder limits, latency or memory.

## 3. Executive conclusion

**Classification: B — implementation appears ready, but essential device evidence is missing.**

* The automated evidence for the logical/physical mapping, window size limits, MediaSession façade, crossfade, restoration and
  mutations is broad and, in the one run available, fully green. The bounded design is consistent with its own gates *in code*.
* The device evidence is one informal report: six functional passes on a bounded **debug** APK by one beta tester, plus a prior
  report that the original large-library problem disappeared. That is valuable (the symptom that motivated P5 is reportedly gone)
  but it covers the *happy-path interactive surface*, not the gates that carry the risk: process-death restoration (G5), real audio at
  crossfade promotion with a shifted window (G4), external controllers on a huge timeline (G3), rollback rehearsal (G8), and any
  performance comparison (G7).
* **Remaining work is mostly testing**, plus a small amount of *non-playback* preparation that is not yet done: a release candidate that
  actually has the gate on (the activation rehearsal needs a one-line gate change **and** coordinated test changes, a separate
  authorised slice), a new `versionCode`, and an in-app/loggable way to prove which variant a tester is running. No playback defect
  is established by this audit. Two items are flagged for confirmation (the armed-Play lifetime in §5.1 and the timing test in §7.2).
* Positive feedback is not an activation criterion. G5, G7 and G8 are **Not Tested** on a device; G3 and G4 are **Partial**.

## 4. G1–G8 evidence matrix

Status vocabulary: **Pass** (the gate as worded is satisfied by evidence that can satisfy it), **Partial**, **Fail**, **Not Tested**.
"Blocks activation" means: must change before the production gate may be considered.

**Design §12.3 requires ALL of G1–G8 to be green and recorded before activation. No gate is waived by this audit.** A gate that is
not yet fully satisfied (anything other than Pass) is *open* and must be closed with the evidence named in its section; where the
table says "No" under Blocks, that only means no *further implementation* is expected, not that the gate may be skipped. Today G1 is
the only gate with a Pass.

| Gate | Requires (design §12.3) | Status | Blocks? |
|---|---|---|---|
| **G1** | Mapping invariants I1–I8 by pure tests **and** real-Media3 tests (shuffle, duplicates, ring) | **Pass** (automated; device corroboration indirect) | No |
| **G2** | No set/replace/add call larger than `W_MAX + REFILL_BATCH` in Bounded mode (operation-shape guard) | **Partial** (strong distributed guards; no single global recorder assertion found; no device log) | **Open** — no implementation change expected, but the gate is not satisfied until the §4 G2 evidence list is complete |
| **G3** | Logical façade: external controllers see N items, logical index, logical repeat; distinct-hash invariant at N = 25,000 | **Partial** (JVM pass; device/binder/OEM surfaces Not Tested) | **Yes** |
| **G4** | Crossfade X1–X5 under promotion, repeated promotion, cancellation, edit-during-preparation, two real ExoPlayers | **Partial** (JVM pass with two real ExoPlayers; no audio, no device) | **Yes** |
| **G5** | Process death → window rebuild → exact occurrence/position, duplicates, `current == n-1` | **Partial** (JVM pass; device Not Tested) | **Yes** |
| **G6** | Full mode behaviour identical to baseline (existing suites green, queues ≤ 300) | **Partial** (suites green in the one run; two documented intentional Full-mode changes since; no Full-mode device run recorded) | **Open** — requires the Full-mode regression evidence listed under G6; the intentional changes are permitted, an unexplained difference is not |
| **G7** | Device measurements: Bounded ≤ Full on every metric; no regression in gapless/crossfade audio | **Not Tested** (no baseline, no measurement) | **Yes** |
| **G8** | Rollback rehearsed: gate off over a persisted Bounded session → clean Full restore | **Not Tested** (H3/H4 unexecuted) | **Yes** |

### G1 — Logical/physical mapping correctness
* **Implementation [CODE]:** `PhysicalWindow.kt` (`physicalToLogical`/`logicalToPhysical`, ring `wraps`), `PhysicalWindowPolicy.kt`
  (`W_MAX=100`, `HISTORY_TARGET=20`, `HISTORY_MAX=40`, `HISTORY_MIN=10`, `AHEAD_TARGET=79`, `AHEAD_LOW=30`, `AHEAD_FLOOR=8`,
  `REFILL_BATCH=25`, `FULL_MODE_MAX=300`), `PhysicalWindowAdapter.kt` (occurrence-exact acknowledgement; ambiguous → fail closed,
  bounded re-anchor, never a song-id search).
* **Tests [JVM]:** `PhysicalWindowTest`, `PhysicalWindowPolicyTest`, `PhysicalWindowAdapterTest`, `PhysicalWindowMovementTest`,
  `PhysicalWindowCurrentOccurrenceTest`, `PhysicalWindowMutationPlannerTest`, `PhysicalWindowMutationLifecycleTest`,
  `PhysicalWindowPortTest`, `PlayerControllerBounded{Window,Mutation,Deletion}Test` (164 + 54 tests in the local run, 0 failures).
* **Device evidence:** [DEVICE-REPORT] the tester's skipping between distant songs and continuous playback produced no wrong-song
  report. Duplicate occurrences on the tester's library: [UNKNOWN].
* **Missing:** device confirmation that duplicates in a real >300 queue play/restore the right occurrence.

### G2 — Bounded operation size
* **Implementation [CODE]:** the adapter issues at most one install (≤ `W_MAX`) or one bounded batch (`REFILL_BATCH`/`HISTORY_TARGET`);
  an oversized install is refused (`PhysicalWindowAdapterTest.kt:254-259`); removals precede adds so the playlist never exceeds the
  larger of old/new window.
* **Tests [JVM]:** per-test maxima of physical item count (`PhysicalWindowMovementTest.kt:218`, `PlayerControllerBoundedWindowTest.kt:217`),
  the structural guards at 1k–50k in `BoundedIntegrationMatrixTest`/`BoundedCrossfadeTest`/`BoundedRestorationTest`
  ("CURRENT ≤ 100, one install, NEXT ≤ 100"), `PhysicalSubmissionTraceTest`.
* **Gap:** I did not find one recorder-based assertion applied to *every* path that no call exceeds `W_MAX + REFILL_BATCH`; the
  guarantee is the sum of many tests.
* **Device:** [DEVICE-LOG] none.
* **Additional evidence required to close G2 (all must be recorded; none exists today):**
  1. *Code/JVM:* either a single recorder-based guard (a `RecordingPlayer`/port wrapper asserting no `setMediaItems`,
     `replaceMediaItems` or `addMediaItems` call exceeds `W_MAX + REFILL_BATCH` = 125 items, and none of them is invoked with the
     full queue) run over install, refill, trim, re-anchor, every mutation row, restore and recovery paths, **or** a written
     enumeration, reviewed by the owner, mapping each physical-call site in `PhysicalWindowAdapter`/`PlayerController` to the
     existing test that bounds it. (A new test is a future slice, not part of this audit.)
  2. *Device:* `WavdropValidation window_submitted` lines (with `kind`, `removes=`, `inserts=`) from at least one session per scenario
     T1, T3, T6, T7, T10 on a queue > 300 songs, tabulated as max install size, max inserts, max removes, and count; plus
     `window_verified physicalCount=` maxima. Capture is read-only via `scripts/Capture-WavdropValidation.ps1`.
  3. *Device negative control:* a Full-mode run on the same library shows zero `window_submitted` / `baseline_observation` lines
     (P5-F counting rules), proving the lines come from the bounded path only.
  4. *Pass requires:* every captured `window_submitted` satisfies install ≤ 100 items, adjust inserts ≤ 25 (history prepend ≤ 20) and
     removes within the same bound, and no operation carries the full queue. Any exceedance is a Fail, not a note.

### G3 — Complete logical MediaSession presentation
* **Implementation [CODE]:** `LogicalQueueTimeline` (lazy; per-entry window/period; generation-scoped hashed uids, duplicates distinct),
  `SessionFacade.bindLogicalQueue`, `LogicalQueueCommands`; structural playlist edits from external controllers are
  **unavailable/rejected** in bounded mode by design.
* **Tests [JVM]:** `LogicalQueueSessionTest`, `ExternalIndexSeekTest`, `BoundedRestorationTest` (a real controller sees 25,000 items),
  `BoundedIntegrationMatrixTest` (queue of 25,000; subscriber sees one AUTO per promotion and no queue change).
* **Device:** none. The design itself lists as device-only: binder transaction size/latency at 25k–50k, notification, lock screen,
  AVRCP Next/Previous (design P5-E "Device-only / outstanding"). Each connecting controller pulls ~N windows once. Q5 (very large
  timeline transport) is open.
* **Blocks:** yes. A failure here is user-visible on every external surface.

### G4 — Crossfade CURRENT/NEXT correctness and promotion safety
* **Implementation [CODE]:** NEXT is *invalidated and rebuilt* on every window change (never patched in lock-step); the crossfade stack
  receives the verified window in physical coordinates; readiness requires an Active, unambiguous window with ≥ `AHEAD_FLOOR` items after
  the target; `RUNTIME_ENABLED=true` and unchanged.
* **Tests [JVM]:** `BoundedCrossfadeTest` and `BoundedIntegrationMatrixTest` with two real ExoPlayers (40 consecutive promotions across
  refills, repeat-ALL wrap, repeat-OFF end, delete-current, shuffle, jump with NEXT ready, failed adjustment, Full mode).
* **Device:** [DEVICE-REPORT] "continuous playback" passed; whether crossfade was enabled, its duration, and whether any window
  boundary was crossed are [UNKNOWN]. No listening test with crossfade across refills is recorded (design Q6 gapless-at-edge is open).
* **Blocks:** yes. Audible quality at promotion with a shifted window cannot be proved on a JVM.

### G5 — Process-death restoration
* **Implementation [CODE]:** only the logical snapshot is persisted; `PlaybackResumptionMapper.map(..., boundedModeFor)` installs one
  verified window at the saved position through the in-process port; `onPlaybackResumption` adopts only for `isForPlayback=true`.
* **Tests [JVM]:** `BoundedRestorationTest` — cold restore of 25,000 at position, every boundary occurrence (incl. last), duplicates and
  shuffled order, pause during restore, newer selection during restore, failed acknowledgement → one repair install; `PlaybackResumptionContractTest`.
* **Device:** none. Not covered by the six reported checks. P5-F sheet rows G1–G8 and A6 are unexecuted.
* **Known limit [CODE/design]:** a single library song missing from the saved queue makes the **whole** restore return `null`
  (design Q8; pre-existing, orthogonal, but more likely to be hit with a huge, frequently re-scanned library).
* **Blocks:** yes.

### G6 — Full-mode compatibility
* **Evidence:** the 4,042-test local run is green; Full is the default for every queue while the gate is off and for ≤ 300 songs when on.
  Tests labelled `both { bounded -> … }` run Full and Bounded.
* **Not literally "bit-identical":** two committed slices deliberately changed Full-mode behaviour since the gate was written:
  (1) a paused Next/Previous-to-another/queue-row selection now **plays** (`ee9aae0`), (2) the Play/Pause control now shows the playback
  *intent* while preparing (`7ad11a3`). These are user-visible in Full mode, are not bounded-specific, and are not listed in the
  user-facing release notes (beta10 notes predate them).
* **Device:** [UNKNOWN] whether the tester or owner ran a Full debug build of the same commit.
* **Interpretation for G6:** the two changes above are **explicitly permitted** as documented, intentional deviations from the original
  "bit-identical" wording. They are not regressions *if and only if* the behaviour is exactly what `ExplicitSelectionAutoplayTest` and
  `PlaybackPresentationTest`/`PlaybackToggleConsistencyTest` specify (paused selection of another occurrence plays; same-occurrence
  restart/seek stays paused; the control shows intent while preparing but never hides an error or suppression). Everything else in
  Full mode must remain compatible with the pre-P5 baseline.
* **Evidence required to close G6 (none of it is recorded today):**
  1. The complete unit suite green on the **exact** baseline commit (re-run; the existing result set is not proven identical, §2.3).
  2. The Full-mode (gate off, and ≤ 300-song with gate on) tests, including every `both { bounded -> … }` case, green.
  3. A Full-mode device regression pass on a Full debug build of the same commit: the six already-reported basic checks plus
     start, Next/Previous, queue edits, shuffle/repeat and restore (P5-F sheets A–D, G), executed as T3's regression control.
     Zero Full-mode `window_submitted`/`baseline_observation` lines.
  4. The two intentional changes listed in the release notes/disclosure (K10) so they are not mistaken for regressions.
* **Blocks:** G6 stays **open** until items 1–3 are recorded. The permission above is for the two named changes only; it does not
  waive the gate.

### G7 — Real-device performance and audio vs Full
* **Evidence:** none. Design §11.2: "There is no device baseline in the repository for any of these." This is still true.
  Instrumentation exists (`WavdropStartTrace`, `WavdropValidation`, `WavdropSessionPerf`, `scripts/Capture-WavdropValidation.ps1`),
  every field of P5-F §1/§2/§5 is blank.
* [DEVICE-REPORT] subjective: the large-library problem is gone; interactions "passed". Treat as subjective user experience only.
* **Blocks:** yes. Activation cannot claim "Bounded ≤ Full" without numbers.

### G8 — Rollback Bounded → Full over a preserved session
* **Design [CODE]:** nothing mode-specific is persisted; a Full-gate build restores the logical session and installs Full
  (design §12.2, §10). `boundedSession`/`installedQueueMode` are runtime-only.
* **Tests [JVM]:** per-mode restore tests; no test crosses two builds (impossible in JVM).
* **Device:** none. P5-F rows H3/H4/H5 are unexecuted. Not rehearsed with a *large* session, which is where Full-mode restore is slow
  and where the original problem lived.
* **Blocks:** yes.

## 5. Recent correction slices

### 5.1 Explicit song-selection autoplay (`ee9aae0`)

| Requirement | Mechanism [CODE] | Test evidence [JVM] (`ExplicitSelectionAutoplayTest`, 26 tests, Full + Bounded via `both{}`) |
|---|---|---|
| Paused Next selects and plays a different occurrence | `PlayerController.selectOccurrence` → `forcePlay=true` iff target ≠ current | `pausedNextSelectsTheNextOccurrenceAndPlaysIt`, `pausedNextWithShuffle…`, `pausedNextWrapsWithRepeatAll…` |
| Paused queue-row selection plays; duplicates | same entry | `pausedQueueRowSelectionOfAnotherOccurrencePlays`, `pausedSelectionOfADuplicateAtAnotherIndexPlaysThatOccurrence` |
| Previous restarting the same occurrence keeps Pause | `PreviousQueueAction.RestartCurrent` → `seekTo(0)`, returns false | `pausedPreviousThatRestartsTheCurrentSongStaysPaused`, `pausedSeekWithinTheCurrentSongStaysPaused`, `pausedSelectionOfTheCurrentOccurrenceIsARestartNotASelection` |
| True end with repeat OFF starts nothing | `nextIndex` null → false | `pausedNextAtTheTrueQueueEnd…`, `anExternalNextAtTheTrueQueueEndStartsNothing` |
| Deferred Pause authority | `DeferredPlaybackCommands.playPredatesSelection`; `capturePlayWhenReady` clears it (newer wins); drain skips an older Pause only when the selection actually started | `aDeferredExplicitNextWhilePausedKeepsItsPlayIntent…`, `aDeferredQueueRowSelection…`, `anOlderDeferredPauseCannotOverrideANewerSelection`, `aNewerDeferredPauseOverridesAnOlderSelectionsPlayIntent`, `aRejectedDeferredNavigationNeverPlaysThePreviousSong` |
| Bounded verification prevents stale playback | `armedPlayEpoch`/`selectionPlayEpoch`; Play fires only from the verified outcome or an applied seek if still current and same controller; `Held` also arms | `pausedBoundedInWindowSelectionPlaysWithoutReinstalling`, `pausedBoundedOutOfWindowSelectionPlaysOnlyAfterTheReanchoredWindowIsVerified`, `aNewerSelectionSupersedesAnOlderPendingOne`, `aPauseDuringAPendingReanchorWins` |
| External transport does not bypass selection semantics | `PreviousBehaviorPlayer.startSelectedIfExternal`, guarded by `selectionPlayArmed()` | `externalNextPreviousAndSeekToAnotherItemPlayFromPausedButSameItemSeekDoesNot`, `anExternalPauseDuringAPendingReanchorWins` |
| Refill/trim/mutation/reconnect never autoplay | not a selection → no `forcePlay` | `backgroundRefillAndTrimNeverStartAPausedSession`, `aQueueMutationThatDoesNotSelectNeverStartsAPausedSession`, `reconnectingAPausedControllerWithoutASelectionDoesNotAutoplay` |

Uncovered boundaries (to confirm, not established defects):
* **Armed-Play lifetime across controller replacement.** `startIfStillCurrent` requires `mediaController === controller`. If the
  controller is replaced while a Play is armed, `armedPlayEpoch` is only cleared by an outcome callback, a newer selection, a toggle
  or an external transport. While set, `isSelectionPlayArmed()` makes the service wrapper skip its own external start. Whether the
  adapter always delivers a terminal `Stale`/failure outcome (→ `withdrawArmedPlay`) on controller replacement was **not traced**.
  `BoundedIntegrationMatrixTest` has a "disconnect mid re-anchor" case but I did not confirm it asserts on the armed flag. Inspect or
  add a targeted test in a future slice.
* Device-level race timing of external Next from a real Bluetooth/lock-screen client.

### 5.2 Visual playback presentation (`7ad11a3`)

| Requirement | Mechanism [CODE] | Test evidence [JVM] |
|---|---|---|
| Presented state vs physical `isPlaying`; truthful `isPlaying` kept | `NowPlayingState.presentedAsPlaying` added beside `isPlaying`; `PlaybackPresentation.presentedAsPlaying` (pure) | `PlaybackPresentationTest.theDerivationKeepsEveryRealSituationDistinguishable` |
| Intent while buffering is not shown as Pause | `playWhenReady && state != ENDED` → playing | `pausedNextShowsNoFalsePauseWhilePreparing`, `normalBufferingDuringPlaybackIsNeverShownAsPause`, `rapidSuccessiveSelectionsPresentOneContinuousPlay` |
| Toggle consistent with what is shown | `toggleShouldPause(shownAsPlaying)`; deferred toggle derives from `presentedAsPlaying` | `PlaybackToggleConsistencyTest` (12): `aPlayIntentThatIsStillBufferingShowsPauseAndATapPauses`, `rapidPlayPauseWhileBufferingLandsOnTheLastTap` |
| Armed bounded Play shown as Pause only while verification is really outstanding; tap cancels | `armedIntent` requires `armedPlayEpoch` set **and** (`deliveringVerifiedOutcome` or adapter busy or held navigation) | `pausedBoundedOutOfWindowSelectionIsPresentedAsPlayingThroughVerification`, `aBoundedSelectionWithAnArmedPlayShowsPauseAndATapCancelsItForGood`, `anOlderArmedPlayNeverResumesAfterANewerPlayPauseCycle` |
| Manual Pause overrides pending Play | toggle retires the armed epoch first | `aNewerPauseDuringVerificationWinsAndIsShownAtOnce`, `aPauseImmediatelyAfterADistantSelectionWins` |
| Genuine suppression/error not hidden | `hasError → false`, `suppressed → false` even behind an armed intent | `theDerivationKeepsEveryRealSituationDistinguishable`, `aRejectedVerificationNeverLeavesTheUiClaimingPlayback` |
| Mini-player and Now Playing consistent | both render `presentedAsPlaying` | `theMiniPlayerAndNowPlayingRenderThePresentationFieldNotTheRawMedia3Flag` (a **source-text** check, not a rendered UI test) |
| Home resume card | `shouldShowResumeSessionCard` requires `!isPlaying && !presentedAsPlaying` | covered only indirectly; I found no dedicated Home test for this predicate |
| Notification/session truthful | session keeps raw state | `theSessionAndTheNotificationStayTruthful` |

Uncovered: no Compose UI/rendering test; the home-screen widget and any other `isPlaying` consumers were not inspected for consistency
with the new field; behaviour on real Media3 with a real decoder (preparation times) is device-only. Possible *inconsistency* between
the in-app control (intent) and the notification (truth) during a long preparation is by design but should be eyeballed on a device.

## 6. Release and debug boundaries (findings)

Verified [CODE]:
* **Gate:** `const val BOUNDED_WINDOW_ENABLED = false`. Mode selection (`PlayerController.kt:3474` and `:4763`) is
  `BOUNDED_WINDOW_ENABLED || VALIDATION_BOUNDED || boundedInstallEnabledForTesting`, and only when `logicalPresentationAvailable`.
* **Validation override:** `buildConfigField("boolean","WAVDROP_VALIDATION_BOUNDED", validationBounded.toString())` in `debug`; the literal
  `"false"` in `release`. A `gradle.taskGraph.whenReady` check throws if the property is `true` and any task whose name contains
  "Release" is scheduled. `ValidationVariantTest` asserts the release literal and `refusing to run` by reading the build script text.
* **Diagnostics:** `ValidationTrace.enabled = BuildConfig.DEBUG`; every entry point returns early otherwise. `proguard-rules.pro`
  strips `Log.v/d/i` in release with `-assumenosideeffects`; `Log.w/e` are kept. 47 `Log.d/v/i` calls are not individually
  `DEBUG`-gated and rely on that R8 rule (not reviewed for content; the rule is documented as defence in depth).
* **Identities:** debug `applicationId` = `com.launchpoint.wavdrop.debug`; release = `com.launchpoint.wavdrop`. They share
  `versionCode 10` / `versionName 0.1.0-beta10`. All debug variants (Full and Bounded-validation) share one package id and debug key and
  cannot coexist; `adb install -r` swaps them and keeps the session (P5-F §0).
* **Signing:** `keystore.properties` is git-ignored (`.gitignore:14`) and was not opened; none supplied → unsigned release; all four
  supplied → signed; partial → build fails; there is no fallback to the debug key. Release tasks require the keystore file only when
  `validateSigningRelease` is scheduled.
* **Rollback path:** build-level only (gate back to `false`, higher `versionCode`); nothing mode-specific is persisted.

Findings (none fixed here):

| # | Finding | Severity | Effect on activation |
|---|---|---|---|
| R1 | **No in-app or user-visible variant indicator.** `variantLabel` is only emitted in a debug logcat line. Debug Full and Bounded APKs have identical `versionName`/`versionCode`/package id. Which APK the tester ran is known only from provenance. | Medium (evidence quality) | Record APK file name + SHA-256 and the `build_identity` line for every device claim. |
| R2 | **All device evidence is from debug builds.** Release uses R8 (`isMinifyEnabled`, `isShrinkResources`), a different package id and no debug trace. A release build with the gate on has never existed, so R8/shrink behaviour of the bounded path is unexercised. | High | A release-type rehearsal build is required before any release candidate. |
| R3 | **The flip is not a one-line change.** Eight test files assert `assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)` (`PhysicalQueueCommandAuthorizationTest`, `PhysicalQueuePolicyTest`, `PhysicalWindowAdapterTest`, `PhysicalWindowPortTest`, `PlayerControllerBounded{Deletion,Mutation,Window}Test`, `ValidationVariantTest`). By design they fail on a flip; the activation slice must update them deliberately. | Info (tripwire working) | Plan the flip as its own reviewable commit (design P5-F). |
| R4 | **After the flip, debug and release are both Bounded; a "Full" comparison build no longer exists from the same commit.** The override only turns bounded *on*. | Medium | Archive the Full APKs (debug + release) of the pre-flip commit, with SHA-256, as rollback artefacts **before** flipping. |
| R5 | `boundedInstallEnabledForTesting` is an `internal var` compiled into release (default `false`, no non-test writer in `src/main`). Not reachable from another app. | Low | None; note only. |
| R6 | `ValidationVariantTest` guards the release literal by substring-matching `build.gradle.kts`; the Release-task guard matches on task name containing "Release". A restructuring of `buildTypes` could silently defeat the text check. | Low | Future hardening. |
| R7 | Release `versionCode 10` is already shipped. A bounded release needs `versionCode` ≥ 11 and a signed (not unsigned) artefact; rollback is likewise a new, higher `versionCode` with the gate `false` (Android refuses downgrades). | Info | Release prerequisite, not done. |
| R8 | The design's §12.1 sketch said "no BuildConfig"; the implemented override uses one. Documented in code and P5-F, consistent with "debug only"; the design text is stale. | Info | Doc drift. |

Conclusion: **as built, the production gate can be flipped without the validation override or `WavdropValidation` diagnostics reaching
a release build** — the release BuildConfig literal is `false`, the property guard blocks release tasks, and diagnostics are
`BuildConfig.DEBUG`-gated. This is [CODE] + [JVM] reasoning, **not** verified by inspecting a built release APK in this audit. No
defect in the protections was found. R2 and R4 are the gaps that matter.

## 7. Performance evidence

### 7.1 Four kinds of evidence, kept apart

| Kind | What exists | What it can support | What it cannot |
|---|---|---|---|
| Algorithmic complexity [CODE/design] | Bounded install ≤ `W_MAX`; refill ≤ `REFILL_BATCH`; NEXT ≤ 100; save/restore mapping still O(N+L); logical mutation O(N) (unchanged) | Bounded work is independent of N for install, move, crossfade graft | Real latency, heap, GC |
| JVM timings [JVM] | `NextSlotPerformanceTest` (CF-2M4) and structural counts at 1k–50k | Shape and super-linear regressions | Device latency (the test itself says so) |
| Real-device measurements [DEVICE-LOG] | **None.** Every §5 field of P5-F is blank; no Full baseline exists | — | Any "Bounded ≤ Full" claim |
| Subjective experience [DEVICE-REPORT] | Large-library problem "disappeared"; six functional passes | That the user-visible failure is plausibly resolved | Magnitude, regression elsewhere, memory |

### 7.2 `NextSlotPerformanceTest.graftScalesLinearlyWithTracklessSources`

What the test does [CODE]: a real `PlayerEngine` with two Robolectric ExoPlayers grafts a NEXT queue of 2…12,288 items, 3–7 runs per
size, and records `NextSlotGraftTiming`. It sits on the **Full-mode crossfade path** (a full-queue graft up to the 12,288 cache bound).
Bounded mode does not use that path for queues > 300 (NEXT mirrors CURRENT, ≤ 100 items). The gate-off production build *does* use it
for every queue, including 25,000.

Assertions in the `trackless` variant (`production = false`):
1. `assertEquals(expectedChunks, chunkCount)` — deterministic.
2. `max chunk × 3 < prepend+append sum` — **wall-clock relative**.
3. `median chunk < 10 ms` — **wall-clock absolute**.
4. total at 12,288 `< 5,000 ms` — **wall-clock absolute**.
5. The scaling-ratio assertion is skipped for trackless (`if (production)`); it is only printed. The source comment records observed
   trackless ratios of 27–100 as noise-dominated.
Plus in `measure`: `awaitCondition(60 s)` and `state is Ready`.

Recorded evidence in the local result file (the passing run): `trackless` ratio **67.1** (linear ≈ 12.3, quadratic ≈ 151) versus
`production-factory` **15.7**; that run is green. I could **not** examine a failing run: no failing output is in the repository or
the conversation, so the exact failing assertion is [UNKNOWN].

Plausible noise sources (reasoning from structure, not proven):
* Items 2–4 are wall-clock comparisons on a shared JVM that runs alongside ~300 other suites, in parallel forks and with GC pauses;
  the test's own comment notes GC "only ever adds time". A single slow chunk breaks item 2; a loaded machine breaks item 3 or 4.
* Trackless variant has near-zero per-item cost, so the inherent O(items × chunks) timeline-derivation term dominates and amplifies
  jitter (ratio 67 vs 15.7 in the same run).
* Design §11.1 independently states the test "is known to be load-sensitive".

What can and cannot be concluded:
* It is **not** evidence about the bounded path (different code path, different sizes), so a failure there does not by itself
  indicate a bounded regression. That is an argument from structure, not a proof; the shared code (`NextSlotPreparation`,
  `PlayerEngine`) is used by bounded for ≤ 100-item windows.
* It is **not** proven benign: the same code is the Full-mode crossfade graft for the gate-off build. A real super-linear regression
  would look like a failure here, and the only discriminator available is repetition. Isolated runs passing does not distinguish
  "noise under load" from "marginal timing that fails when the machine is busy".
* Recommended (not done): run the single class N times isolated and N times concurrently with the full suite; keep each run's
  `CF-2M4 perf …` stdout; a genuine regression would show the **production-factory** ratio (asserted < 60) or the absolute timings
  rise consistently, while noise shows a random assertion failing with ratios fluctuating around the 15 (production) / 27–100 (trackless)
  band. Whether to demote the wall-clock assertions is a separate decision; this slice does not change them.

## 8. Remaining device scenarios

Do not repeat the six reported checks (Pause→Next, Pause→Select, Pause while loading, rapid Play/Pause, distant skipping, continuous
playback) except as **regression controls inside** the rollback rehearsal. No Android Auto. No synthetic 25,000-track library: use
the largest *real* library each person has and record its count; bounded mode applies from 301 songs, so an owner library of a few
thousand tracks exercises the same code, but **only the affected tester's device can speak to the large-library scale and binder
behaviour**.

Ranked by risk. Executor: **T** = affected beta tester (no adb), **O** = owner (adb, capture script).

| Rank | ID | Test | Gate | Executor | Pass requires |
|---|---|---|---|---|---|
| 1 | T1 | **Process death with a large queue** — follow the T1 protocol below. A run counts only if the process is *proven* to have been terminated and restarted | G5 | O (adb); T only for the non-adb variants (swipe-away, reboot), which do **not** count as proven process death on their own | Same song, same occurrence, position within the agreed tolerance of the saved one; exactly one `window_submitted kind=install` after restart; no wrong-song audio; app data intact |
| 2 | T2 | **Exact duplicate occurrence.** Queue the same song at ≥3 positions (incl. across a window boundary and at the last index); play the 2nd/3rd occurrence; kill; restore; also shuffle on | G1/G5 | O (real library ≥301; can be a playlist with repeats) | The tapped/saved occurrence is the one restored; Next goes to the correct neighbour |
| 3 | T3 | **Rollback rehearsal.** Bounded debug with a >300 session → `adb install -r` Full debug of the same commit → reopen → verify session → reinstall Bounded → verify; run the six basic checks once on Full as regression control | G8, G6 | O; T for the full large-library case (needs the owner to install, or adb on tester's device) | Same logical session (song, position, order, repeat/shuffle); no data cleared; Full restore completes (record time; may be slow) |
| 4 | T4 | **Large external timeline.** On the tester's device with the large queue: notification, lock screen, Bluetooth/AVRCP Next/Previous/Pause/Play, optional external MediaController client; note `TransactionTooLargeException` | G3 | T (visual) + O (logs) | Correct title/artwork/position; Next/Previous move exactly one logical item; no crash, no ANR |
| 5 | T5 | **Screen-off background + lock screen.** ≥ 60 min with screen off, at least 3 window refills, then lock-screen controls | G3/G4 | T | No dropout, no silent stop, controls work; compare against Full if progress freezes (open LS-1 can confound) |
| 6 | T6 | **Crossfade across bounded-window movement.** Crossfade 6 s, ≥ 30 consecutive transitions through ≥ 3 refill boundaries; skip during an overlap; pause during an overlap | G4 | T (listen) + O (`WavdropCrossfade`/`WavdropNextSlot`) | No double audio, silence, restart, or wrong next song; NEXT ready or clean fallback |
| 7 | T7 | **Interrupted/delayed window operation.** Burst Next ×10 during a refill; incoming call or audio-focus loss mid re-anchor; background the app during a far jump | G2/G3 | T + O | Newer intent wins; `window_not_verified` only as `stale`; any non-stale failure followed by exactly one recovery and a verified window; no infinite retry |
| 8 | T8 | **Bluetooth/wired headset controls** (incl. cold PLAY after app killed) | G3/G5 | T or O | Same as T4; cold PLAY resumes the saved occurrence |
| 9 | T9 | **Extended soak.** ≥ 3 h continuous, normal listening | G4/G7 | T | No crash/ANR, no memory growth trend (O samples `dumpsys meminfo` every 30 min if possible) |
| 10 | T10 | **Full vs Bounded comparison.** Cold start, first-play (×3), far jump (×3), refill, peak PSS, session restore, on the same device and library | G7 | O (capture script) | Bounded ≤ Full within the agreed tolerance (§9) on every item |

### T1 protocol — process death must be proven, not assumed

Three different events are easy to confuse. Only the first is G5's "process death":

| Event | What dies | Typical trigger | Counts as T1? |
|---|---|---|---|
| **Process death** | The whole app process, including the playback service, with no `onDestroy` guarantee; the saved logical session is the only survivor | System low-memory kill, `kill` of the pid, `am kill` when allowed | **Yes**, once proven |
| **Activity destruction** | Only the UI activity (swipe from Recents, "Don't keep activities"); the service/process may keep running with its in-memory queue | Recents swipe, developer option | **No** — it does not test restoration (a separate scenario; record as such) |
| **Force-stop** | App stopped by the user/adb: services stopped, alarms and pending intents cleared, app in "stopped" state | `adb shell am force-stop`, Settings → Force stop | **No** for T1; it is the separate sheet row G7 (restart restores the last saved session) |

`adb shell am kill` only kills a **cached/background** process. It is documented as not killing a process that is in the foreground or
holds a foreground service, and **WavDrop's playback service is a media foreground service while playing or while its notification is
shown.** Do not assume the command worked; many runs will silently leave the process alive. Procedure:

1. Record the baseline: song + occurrence index/position, queue size, build (APK file name + SHA-256) and `build_identity` line.
   Do **not** clear data, uninstall or use `pm clear`. Let the session save (pause for a few seconds so a save occurs).
2. Record `adb shell pidof com.launchpoint.wavdrop.debug` → **PID-A** (empty output means the process is not alive).
3. Put the app in a state in which it may be killed: pause playback, dismiss/stop the foreground notification or let the service stop,
   leave the app (Home), and wait until `adb shell dumpsys activity processes` / `dumpsys activity services <pkg>` shows no running
   foreground service for the package. If it cannot be made killable, state so and use the next method.
4. Terminate with the **non-force-stop** methods in order of preference: `adb shell am kill <pkg>` (works only if cached);
   otherwise `adb shell run-as <pkg> kill <pid>` (debug build only) or `adb shell kill <pid>` where the shell may; otherwise ask the OS
   to reclaim memory (open several large apps) — only if a pid change is then proven. Do **not** substitute `am force-stop`.
5. **Prove termination:** `adb shell pidof <pkg>` is empty (or `kill` reported success and the pid is gone), and/or
   `adb shell dumpsys activity exit-info <pkg>` shows a new exit record with a reason that is not the user's force-stop
   (for example a signal/low-memory/other record) and a timestamp after step 3. Save the output.
6. Restart by user action (launcher icon, notification Play if the notification persists, headset/widget Play) and read
   `adb shell pidof <pkg>` → **PID-B**. **PID-B must differ from PID-A**, and the logcat must show a fresh `build_identity` line from the
   new process. If PID-B equals PID-A, or no new process start is evidenced, the run is **void** (not a failure).
7. Verify and record: song, occurrence index, position, queue size, order, shuffle/repeat, `install_requested … mode=Bounded` and exactly
   one `window_submitted kind=install` + `window_verified`; app data present (library and session intact) without any clear step.
8. Repeat for each case required by acceptance criterion 1 (duplicate occurrence, index N-1, shuffled order, repeat ALL). Cold PLAY via
   the notification/headset after the kill is a separate row (T8).
Notes for the tester: they cannot normally prove steps 2–6 without adb. A swipe-away or a phone reboot is useful extra evidence but is
recorded as *activity destruction* / *reboot*, never as process death.

Every run records: device model, Android/API level, real library count, APK file name + SHA-256, `build_identity` line, duration.
None of these fields is recorded for the already-reported six passes.

## 9. Activation decision

**B — implementation appears ready, but essential device evidence is missing.**

Why not A: the audit found no established defect in the bounded playback implementation; the two uncovered boundaries in §5.1 and R-items
in §6 are confirmations, not failures.
Why not C: a *controlled rehearsal* needs (a) G5 and G8 evidence on a real device, (b) a release-type candidate with the gate on (R2),
(c) a new `versionCode` and signed artefact (R7), none of which exist. Positive tester reports do not substitute for them.
Why not D: G7 has no baseline or measurement at all, and G3/G4/G5/G8 have no device evidence.

**To reach C**, the shortest path is: T1, T2, T3 (owner, small), T4–T6 on the tester's device, T10 baseline captured, then one
authorised slice to build a rehearsal candidate (gate flip + test updates + `versionCode`, no other change).
**To reach D**, add T7–T9, a clean re-run of the full suite on the exact baseline, and an isolated stability result for
`NextSlotPerformanceTest`.

### Proposed measurable acceptance criteria

**Every numerical threshold below is PROPOSED and has not been agreed with the owner.** None is derived from data: **no Full-mode or
Bounded-mode baseline measurement exists** (G7), so no value here is a measured result and none may be quoted as one. Until the owner
agrees (or changes) a number, the criterion is "pending agreement" and cannot be used to declare a gate passed. All device figures are
[DEVICE-LOG] evidence to be captured; anything not captured is "not measured" and keeps its gate open.

**A. Correctness criteria (counts; proposed)**

1. G5/T1/T2: all of ≥ 10 proven-process-death restores (T1 protocol; ≥ 3 with duplicates, ≥ 2 at index N-1) land on the saved occurrence.
   Position tolerance, in **milliseconds**: |restored position − saved position| ≤ 2,000 ms (proposed).
2. G8/T3: 3 of 3 round trips (Bounded→Full→Bounded) preserve song, occurrence, position (same ≤ 2,000 ms tolerance, proposed), order,
   repeat and shuffle; zero data cleared.
3. G2: across every captured session, 0 `window_submitted` with an install > 100 items or an adjust with inserts or removes > 25
   (history prepend ≤ 20); 0 `window_submitted` in a Full-mode run (see G2 evidence list).
4. G3/T4: 0 crashes, 0 `TransactionTooLargeException`, 0 wrong-neighbour Next/Previous in ≥ 20 external commands per surface
   (notification, lock screen, Bluetooth).
5. G4/T6: ≥ 30 audible transitions through ≥ 3 refills with 0 audible defects.
6. Soak/T9: 0 crashes and 0 ANRs over ≥ 3 h.
7. G6: full suite on the exact baseline: 0 failures; `NextSlotPerformanceTest` isolated ×10 passes, or the failure mode is explained
   and accepted by the owner.

**B. Time-based metrics — unit: milliseconds (ms)**

Same device, same real library, same commit, Full APK vs Bounded APK, ≥ 3 repetitions per cell, report each value and the median.
Full is measured first; Bounded is compared with the Full median of the same device.

| Metric | Source | Proposed tolerance (Bounded vs Full median) |
|---|---|---|
| First-play latency (tap → audible) | `WavdropStartTrace audio_position_advancing +ms` | Bounded ≤ Full + max(100 ms, 10 % of Full) |
| Far-jump latency | `op=navigate` `window_verified elapsedMs` (Bounded); start trace (Full) | same rule |
| Session restore duration | `WavdropSessionPerf` | same rule |
| Window refill duration | `op=maintain` `elapsedMs` (Bounded only) | no Full comparator; report max and median; acceptance value to be proposed by the owner after the first capture |
| Cold start → library ready | stopwatch / Perfetto | same rule |

**C. Memory metrics — unit: MiB, with percentage comparison**

| Metric | Source | Proposed tolerance |
|---|---|---|
| Peak PSS TOTAL after ≥ 10 min playback and after the same scripted workload | `adb shell dumpsys meminfo` samples, reported in MiB (convert from KiB) | Bounded ≤ Full + 5 MiB **or** ≤ 105 % of Full, whichever is larger (proposed) |
| PSS growth during soak (T9) | meminfo every 30 min, MiB | final-hour mean ≤ 110 % of first-hour mean (proposed); no monotonic upward trend across the last 3 samples |

Memory is never compared against a time tolerance, and times are never converted to a percentage of memory. A metric without a
captured Full value cannot be assessed and blocks G7.

### Stop conditions (any one halts the activation path)

* Any wrong-song, wrong-occurrence or lost-position event after a restore or rollback.
* Any crash, ANR, `TransactionTooLargeException`, or silent stop attributable to the bounded path.
* Any audible crossfade defect (double audio, silence, restart) that does not occur in Full on the same device.
* Any `window_not_verified` other than `stale`, not followed by exactly one verified recovery.
* A Full → Bounded → Full round trip that clears or alters the saved session.
* Any measured metric worse than Full beyond the tolerance agreed with the owner (§9 tolerances are proposals until agreed), or an unmeasured required metric.
* A built release artefact showing the validation override or `WavdropValidation` output.

## 10. Rollback prerequisites

Before any gate flip commit exists:
1. Archive **Full** debug and release APKs (and `mapping.txt`) of the pre-flip commit with file names and SHA-256 (R4).
2. Rehearse T3 on a device with a large saved session and record the result in P5-F rows H3–H5.
3. Decide `versionCode` ≥ 11 for the bounded build and reserve ≥ 12 for the rollback build; both signed with the upload key
   (never the debug key).
4. Write the rollback as a one-commit revert: `BOUNDED_WINDOW_ENABLED = false` plus the eight asserting tests restored.
5. Confirm that a rollback build installs over the bounded build and restores the logical session (same package id, higher version).
6. Keep the validation script and P5-F sheets available; do not delete app data in any rehearsal.

## 11. Known risks and unresolved issues

| ID | Item | Status |
|---|---|---|
| K1 | No device logs, device identification, library count or duration for the tester's six passes | [UNKNOWN]; ask |
| K2 | Armed-Play state after controller replacement (§5.1) | Unconfirmed; inspect/test |
| K3 | Binder/transaction behaviour for 25k–50k timelines (design Q5) | Open |
| K4 | Gapless/crossfade at a window edge on real audio (design Q6) and sleep-timer boundary across a window shift (Q9) | Open |
| K5 | Whole-queue restore fails if any saved song is missing from the library (Q8) | Open, pre-existing |
| K6 | `NextSlotPerformanceTest` timing assertions unresolved | §7.2 |
| K7 | Local test results are untracked and predate the HEAD commit by ~8 h | §2.3 |
| K8 | LS-1 intermittent lock-screen progress (physical reproduction pending) can confound T5 | Open, pre-existing |
| K9 | `POST_NOTIFICATIONS` not declared: on Android 13+ the notification may not appear, which affects T4/T5 | Known (P5-F §1) |
| K10 | Full-mode behaviour changed by `ee9aae0`/`7ad11a3`; not in user-facing notes | Disclosure needed |
| K11 | Home widget and other `isPlaying` consumers not checked against `presentedAsPlaying` | Not inspected |

## 12. Outstanding questions

1. Exact device model, Android version and API level of the affected tester.
2. The tester's real library size and the size of the queue they played (≥ 301?). Did any queue contain duplicates?
3. Which APK (file name, SHA-256, build date, commit) did the tester run? Was crossfade enabled (and at what length)?
4. Duration of the "continuous playback" test; did it cross window boundaries (≥ ~79 songs in one stretch)?
5. Did the tester's "original problem disappeared" report refer to the same device and library, and to Full mode at the time?
6. Can the owner reach a library of > 300 songs, with repeats, to run T1–T3? Is adb access to the tester's device possible?
7. Is the owner content with the proposed numeric tolerances in §9?
8. Does the owner accept authorising a rehearsal-candidate slice (gate flip, tests, `versionCode`) after T1–T3 pass?
9. Is a shipped-release disclosure of the two Full-mode behaviour changes (K10) wanted?

## 13. Evidence references

* Design: [P5-BOUNDED-PHYSICAL-QUEUE-DESIGN.md](../development/P5-BOUNDED-PHYSICAL-QUEUE-DESIGN.md) §11, §12, §12.3, §13 (P5-A…P5-F records), §14.
* Device sheet: [P5-F-DEVICE-VALIDATION.md](P5-F-DEVICE-VALIDATION.md); capture script `scripts/Capture-WavdropValidation.ps1`.
* Policy/build: `app/src/main/kotlin/com/launchpoint/wavdrop/playback/PhysicalQueuePolicy.kt`, `PhysicalWindowPolicy.kt`,
  `PhysicalWindowAdapter.kt`, `ValidationTrace.kt`, `PlayerController.kt` (mode selection at `:3474`, `:4763`),
  `app/build.gradle.kts`, `app/proguard-rules.pro`.
* Fix slices: `git show ee9aae0`, `git show 7ad11a3`; `PlaybackPresentation.kt`, `DeferredPlaybackCommands.kt`, `PreviousBehaviorPlayer.kt`.
* Tests: `app/src/test/kotlin/com/launchpoint/wavdrop/playback/` — `PhysicalWindow*Test`, `PlayerControllerBounded*Test`,
  `BoundedIntegrationMatrixTest`, `BoundedCrossfadeTest`, `BoundedRestorationTest`, `LogicalQueueSessionTest`, `ExternalIndexSeekTest`,
  `PhysicalQueue*Test`, `ValidationVariantTest`, `ExplicitSelectionAutoplayTest`, `PlaybackPresentationTest`,
  `PlaybackToggleConsistencyTest`, `NextSlotPerformanceTest`.
* Local result files (untracked): `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
