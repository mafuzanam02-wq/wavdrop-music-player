# P5-F — Device validation: Full vs Bounded physical queue

Manual, owner-executed A/B of the **Full** debug build against the **Bounded-validation** debug build. No application code
needs to change. **Nothing here has been run yet**: every Pass/Fail field and every measurement is blank until the owner fills it in.
Production bounded mode stays OFF (`PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED = false`); this validation does not authorise
enabling it.

## 0. Builds and identity

| Variant | Gradle command | Behaviour |
|---|---|---|
| **A — Full** | `.\gradlew.bat :app:assembleDebug` | Committed gate (off). Every queue is Full. |
| **B — Bounded validation** | `.\gradlew.bat :app:assembleDebug -PwavdropValidationBounded=true` | Same baseline; bounded mode compiled ON in the **debug** BuildConfig only. Queues of ≤ 300 songs stay Full, ≥ 301 are Bounded. |

* The property only feeds the *debug* `BuildConfig.WAVDROP_VALIDATION_BOUNDED`; the release BuildConfig is hard-coded `false`, and
  combining the property with any release task fails the build. Release builds cannot become bounded.
* Mode is a compile-time constant of the installed APK: it cannot change in the middle of playback and there is no setting.
* **Both APKs use the same package id `com.launchpoint.wavdrop.debug` and the same debug signing key.** They share app data and
  **only one can be installed at a time**; installing the other over it (`adb install -r`, same version code) swaps the variant and keeps
  the saved session (that is what test H3/H4 rely on). They do **not** coexist side by side, and neither touches the release app
  (`com.launchpoint.wavdrop`).
* Confirm which variant is running from the first log line after the service starts:
  `WavdropValidation: build_identity variant=full|bounded committedGate=false validationOverride=… fullModeMax=300 crossfadeRuntime=true sdk=NN`.
* Record the APK file name + SHA-256 (from the build report) in every result sheet.

**Installing is the owner's action** (this task did not touch any device). Use `adb install -r <apk>` to swap variants. Never
`adb uninstall`, `pm clear`, or clear app data during these tests: it destroys the saved session that tests G/H depend on and the
library state. If you do need a clean slate, that is a separate, deliberate step, not part of any test here.

## 1. Environment sheet (fill once per device)

| Field | Value |
|---|---|
| Device model / Android version / API level | |
| Build A APK name + SHA-256 | |
| Build B APK name + SHA-256 | |
| Real library size (songs) | |
| Headset / Bluetooth / widget available? | |

Compatibility facts to keep in mind (from the current manifest): minSdk 26, targetSdk 36, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
service type, `READ_MEDIA_AUDIO` on API 33+ (`READ_EXTERNAL_STORAGE` ≤ 32), **no `POST_NOTIFICATIONS` is declared**, so on Android
13+ check separately whether the playback notification appears at all (record it; it is not a bounded-mode defect). Android 12+
background-start and foreground-service limits can affect cold PLAY from a widget or Bluetooth; Android versions differ, so record
results per device rather than assuming parity. Android Auto is **not** a supported integration and is not a pass criterion.
A very large queue crossing Binder is the reason the bounded window exists; note any `TransactionTooLargeException` (Full mode).

## 2. Library-size matrix

Run the applicable tests at each size the device can truthfully supply. **Record the real count; do not pretend a size.** If the
phone has fewer tracks, run the smaller rows. Synthetic/seeded library entries (if you create any) must be kept apart from real
music and **cannot** validate audible playback.

| Size | Real/synthetic | Build A run? | Build B run? | Notes |
|---|---|---|---|---|
| 300 (threshold: must be **Full** in B) | | | | |
| 301 (threshold: must be **Bounded** in B) | | | | |
| 1,000 | | | | |
| 5,000 | | | | |
| 10,000 | | | | |
| 25,000 | | | | |
| 50,000 (if feasible) | | | | |

Threshold check (B only): play from a 300-song and a 301-song queue (e.g. a playlist/folder of exactly that size). Expect
`install_requested … mode=Full logicalSize=300` and `… mode=Bounded logicalSize=301`. A (Full) must be `Full` for both.

## 3. Log capture

Only the owner runs these. Read-only; the script never clears logcat or touches app data.

```powershell
# before the test action (streams until Ctrl+C; logs go to %USERPROFILE%\wavdrop-validation-logs\<timestamp>-<label>\)
.\scripts\Capture-WavdropValidation.ps1 -Label bounded-B3 -Mode Live
# or, after the fact (dump what the buffer still holds + meminfo)
.\scripts\Capture-WavdropValidation.ps1 -Label bounded-B3 -Mode Snapshot
# memory sample
.\scripts\Capture-WavdropValidation.ps1 -Label bounded-peak -Mode Meminfo
```

It reports a missing adb, no device, an unauthorised/offline device or several devices (pass `-Serial`) and exits without acting.

Tags it keeps: `WavdropValidation` (new), `WavdropStartTrace`, `WavdropQueuePerf`, `WavdropSessionPerf`, `WavdropSessionProgress`,
`WavdropCrossfade`, `WavdropNextSlot`, `WavdropResume`, `Wavdrop-Startup`, `WavStats-PC` (warnings). Everything else is silenced; in particular the shared `AndroidRuntime` tag is **not** captured, because it
carries every app's crash reports.
Logs contain counts/indices/durations only, no titles or paths. `-OutRoot` must be outside the repository (a new directory inside it is refused).

### WavDrop crash diagnostics (separate, only when you explicitly want them)

Not part of ordinary capture. Scope them to the WavDrop identity, never to a shared tag or the whole crash buffer:

```powershell
# while the app process is alive: only that process's lines (any priority)
adb shell pidof com.launchpoint.wavdrop.debug          # note the pid; empty = not running
adb logcat -d -v threadtime --pid=<pid>
# after a crash/kill: package-scoped exit records (reason, timestamp), no other app's data
adb shell dumpsys activity exit-info com.launchpoint.wavdrop.debug
```

The pid changes on every restart, so this is run by hand, per process, and its output is yours to review before sharing.

### How to read the log lines (what each means, and what it does NOT mean)

| Line | Meaning |
|---|---|
| `WavdropValidation install_requested op= mode= logicalSize=` | Controller asked for an install; mode decided. **Not** a physical result. |
| `WavdropValidation window_submitted op= kind=install\|adjust [removes= inserts=] logicalSize=` | A **physical playlist operation was actually issued** to the player (`kind=install`: `setMediaItems` for an initial window, re-anchor, recovery or a held navigation that needed one; `kind=adjust`: an add/remove batch for a refill/trim or a queue edit). Emitted once per operation, by the adapter, after the player calls returned. **Not** verified, and **never** emitted for a baseline observation, a logical-only edit, a rejected request, a held request or a seek inside the verified window. **Physical-operation count = number of `window_submitted` lines.** |
| `WavdropValidation baseline_observation op= logicalSize=` | A queue edit or refill asked the physical player for its exact current occurrence. **Nothing was changed yet**; the edit may still end as a logical-only rebase (no physical operation at all), a bounded edit (`window_submitted kind=adjust`), a re-anchor (`kind=install`) or a failure. Do **not** count these as physical operations. |
| `WavdropValidation window_verified op= elapsedMs= installedCount= physicalCount= logicalCurrent=` | Physical player was **observed** and the window published. `elapsedMs` is monotonic submit→verify for that one operation. `installedCount` / `physicalCount` = Media3 CURRENT item count (bounded: ≤ ~100; Full: not applicable, no window). |
| `WavdropValidation window_not_verified op= result=stale\|incomplete\|rejected\|failed` | Terminal non-success. `stale` = superseded by newer intent (expected, not an error). |
| `WavdropStartTrace id= … stage=…` | Per-request startup trace; `audio_position_advancing` = **first audible playback** evidence. `+Nms` is since `request_received`. |
| `WavdropQueuePerf` | Queue sync / repair timings (Full path). |
| `WavdropSessionPerf` | Session save/restore timings. |
| `WavdropCrossfade` / `WavdropNextSlot` | NEXT preparation, promotion, retire. |

`op=` values you will see include `play_from_queue`, `preserve_search`, `startup_restore`, `cold_resume`, `sync_player_queue:<reason>`, `maintain` (refill), `navigate` (re-anchor), `resumption`,
`reconnect`, `recover_unprovable`, and queue-mutation operation names. Timings are device timings only when taken from the phone;
**do not** compare them to Robolectric/JVM numbers.

Measurements that are not instrumented in logs are taken with tools: `adb shell dumpsys meminfo com.launchpoint.wavdrop.debug`
(peak: sample repeatedly, record PSS "TOTAL"), Android Studio Profiler, or Perfetto. Anything you do not measure stays **"not measured"**.
Use ≥ 3 repetitions where variance matters (startup, first-play, far jump, refill) and write down each value.

## 4. Test sheets

Conventions: **Full** = build A expectation, **Bounded** = build B expectation for a queue **> 300** songs (for ≤ 300, B must behave
exactly like Full). "Complete logical queue" = the Queue screen / a MediaController shows all N songs, in order, regardless of the
~100-item physical window. Fill **P/F**, **Notes / artifact** (log folder, screenshot, time) for every row.
Delete tests use a **disposable test file only**; never an important track.

### A — Installation and initial playback

| # | Setup / action | Full | Bounded | Capture | P/F | Notes |
|---|---|---|---|---|---|---|
| A1 | Fresh debug install (`adb install`, owner action); grant media permission; cold launch | Library loads; no crash | Same; `build_identity variant=bounded` | startup time, `build_identity` | | |
| A2 | Play first song of a large list | Plays; Now Playing correct | `install_requested mode=Bounded`, `window_verified`, `physicalCount` ≤ ~100, logical queue complete | `WavdropStartTrace` first-play ms, install ms | | |
| A3 | Play a middle song | Plays | Window centred on it; complete logical queue | same | | |
| A4 | Play the last song | Plays; Next = end/none (repeat off) | Window anchored at tail; Next correct | same | | |
| A5 | Pause, resume | Resumes same position | Same; no re-install needed | log | | |
| A6 | Stop / close app, reopen | Last session shown | Same; queue complete | restore ms (`WavdropSessionPerf`) | | |
| A7 | Cold PLAY from widget (if present), app not running | Starts playback | Starts playback; no silent failure | `WavdropStartTrace entry=…` | | |

### B — Navigation

| # | Action | Full | Bounded | Capture | P/F | Notes |
|---|---|---|---|---|---|---|
| B1 | Next, Previous | Correct neighbours | Same | responsiveness (subjective + log) | | |
| B2 | Previous after > threshold vs < threshold seconds | Restart vs previous song | Identical | | | |
| B3 | Rapid Next ×10 | Lands 10 ahead, no skip/replay | Same; refills `stale` allowed, final `window_verified` | log | | |
| B4 | Rapid Previous ×10 | Lands 10 back | Same | log | | |
| B5 | Jump far forward (tap song ≫100 ahead) | Plays that song | `op=navigate` re-anchor `window_verified`; correct song | elapsedMs (repeat ×3) | | |
| B6 | Jump far backward | Plays that song | Same | elapsedMs ×3 | | |
| B7 | Jump to last / first item | Correct | Correct | | | |
| B8 | Seek within a song | Position moves | Same | | | |
| B9 | Seek to another song at a given position (Now Playing / queue) | Plays at position | Same; no restart-from-0 | | | |
| B10 | Duplicate song occurrences: tap each occurrence | The tapped occurrence plays | Same occurrence (not the first) | | | |

### C — Window movement

| # | Action | Full | Bounded | Capture | P/F | Notes |
|---|---|---|---|---|---|---|
| C1 | Let ≥ 250 songs advance by Next across several window boundaries | n/a | `op=maintain` refills `window_verified`; no gap | refill elapsedMs list | | |
| C2 | Same by **natural endings** (use short tracks or seek near end) | Auto-advance | Same; no silence at boundary | listen | | |
| C3 | Repeat OFF at genuine queue end | Stops/ends | Same; not a window edge | | | |
| C4 | Repeat ONE across a window boundary | Song repeats | Same | | | |
| C5 | Repeat ALL: logical end → wrap to first | Wraps | Wraps to logical first | | | |
| C6 | Several very short songs consecutively | Plays in order | Same, no skip | | | |
| C7 | Navigate (Next/jump) while a refill is in flight (best effort) | Correct | Newer intent wins; no wrong song | log (`stale` then `verified`) | | |

Watch in all of C: unexpected silence, stutter, wrong/repeated next song, unintended restart, frozen or wrong Now Playing.

### D — Queue mutations

| # | Action | Expected (both) | Capture | P/F | Notes |
|---|---|---|---|---|---|
| D1 | Play Next | Inserted right after current; current unchanged | `op=` mutation `window_verified` (B) | | |
| D2 | Add to Queue | Appended at end | same | | |
| D3 | Large Play All Next | All inserted in order after current | | | |
| D4 | Remove an upcoming song | Gone; current keeps playing | | | |
| D5 | Remove an earlier song | Gone; current keeps playing, index shifts | | | |
| D6 | Reorder upcoming | New order | | | |
| D7 | Clear Earlier / Clear Up Next | As named; current preserved | | | |
| D8 | Toggle Shuffle (on/off) | Current keeps playing; order changes/restores | | | |
| D9 | Toggle Repeat modes | Mode applies | | | |
| D10 | Delete current library song **(disposable test file only)** | Playback continues to the expected next song; song gone from library | | | |

For every row verify: current-item preserved (no restart), logical queue order, and (B) `window_verified` follows.

### E — Crossfade (listen; JVM events are not enough)

| # | Action | Expected | Capture | P/F | Notes |
|---|---|---|---|---|---|
| E1 | Normal crossfade between two songs | Smooth overlap | `WavdropCrossfade` | | |
| E2 | Repeated promotions (≥ 10 songs) | Always smooth | | | |
| E3 | Crossfade close to a refill boundary (B) | Smooth; NEXT ready or clean fallback | `WavdropNextSlot` ready/unavailable | | |
| E4 | Crossfade after shuffle | Smooth, correct next | | | |
| E5 | Crossfade after a queue mutation | Smooth, correct next | | | |
| E6 | Crossfade across repeat-ALL wrap | Smooth | | | |
| E7 | Skip during overlap | One song audible, correct | | | |
| E8 | Pause during overlap | Both pause; resume correct | | | |
| E9 | Crossfade setting off → on (if supported) | Takes effect | | | |

Record any stutter, silence, double audio, clipped transition, unintended restart. NEXT preparation duration: from
`WavdropNextSlot`/`WavdropCrossfade` lines.

### F — System controls

| # | Action | Expected | P/F | Notes |
|---|---|---|---|---|
| F1 | Notification Next/Previous/Pause/Play | Correct; metadata & position correct | | |
| F2 | Lock screen controls | Same | | |
| F3 | Wired headset buttons (if available) | Same | | |
| F4 | Bluetooth controls (if available) | Same | | |
| F5 | Widget actions | Same | | |
| F6 | External MediaController test client (if you have one) | Sees the **complete logical queue** timeline in B, not a 100-item slice; Next/Prev correct | | |

### G — Restoration (no data clearing)

Keep **force-stop**, **process kill** and **clear data/uninstall** separate. Do **not** clear app data in any of these.

| # | Action | Expected | Capture | P/F | Notes |
|---|---|---|---|---|---|
| G1 | Pause mid-track, close, reopen | Same song & position | restore ms | | |
| G2 | Restore a shuffled queue | Same order | | | |
| G3 | Restore at logical queue end | Same state | | | |
| G4 | Restore with repeat ALL | Mode kept | | | |
| G5 | Restore with duplicate songs | Same occurrence | | | |
| G6 | Restore after a queue mutation | Mutated queue | | | |
| G7 | Force-stop (`adb shell am force-stop com.launchpoint.wavdrop.debug`), restart | Restores last saved session | | | |
| G8 | Process death while in background (Developer options "Don't keep activities" is *not* this; use `adb shell am kill com.launchpoint.wavdrop.debug`) | Restores | | | |

### H — Recovery and rollback

| # | Action | Expected | P/F | Notes |
|---|---|---|---|---|
| H1 | Disconnect/reconnect controller (background app, swipe away, reopen) | `op=reconnect` re-anchors (B); correct song | | |
| H2 | Physical verification failure | Only via an existing safe test hook, **if one exists**; otherwise mark "not testable on device". Recovery stays bounded (no infinite retry in log) | | |
| H3 | Play a > 300 queue in **B**, then `adb install -r` **A** over it (owner action), reopen | Same logical session restores in Full; Full installs the entire queue (expect slower on huge libraries) | | |
| H4 | Reverse: A session then install B over it | Restores as Bounded | | |
| H5 | Confirm rollback needed no migration/deletion | No data cleared anywhere | | |

## 5. Performance sheet (per build, per library size)

| Measurement | Source | Build A | Build B |
|---|---|---|---|
| Cold start → library ready | stopwatch / Perfetto | | |
| First-play latency (tap → audible) | `WavdropStartTrace` `audio_position_advancing +ms` (×3) | | |
| Time to first physical install | `WavdropStartTrace playlist_install_*`; B: `window_verified elapsedMs` | | |
| Media3 physical item count | B: `physicalCount`/`installedCount`; A: queue size | | |
| Peak memory | `dumpsys meminfo` samples | | |
| Next/Previous responsiveness | subjective + log | | |
| Far queue jump | `op=navigate elapsedMs` ×3 | | |
| Window refill duration | `op=maintain elapsedMs` | | |
| Crossfade preparation duration | `WavdropNextSlot` | | |
| Promotion behaviour | `WavdropCrossfade` | | |
| Session restoration duration | `WavdropSessionPerf` | | |
| Controller timeline sync | F6 observation | | |

Anything not captured: write **not measured**.

## 6. Sign-off (owner)

Overall A result: ______  Overall B result: ______  Blocking defects: ______  Production activation: **separate decision**.

### Counting rules (physical operations vs. observations)
* `baseline_observations` ≥ `window_submitted kind=adjust` for edits: each edit has one baseline; only edits that actually needed a Media3 add/remove (or a re-anchor) add a `window_submitted`.
* A logical-only edit shows `baseline_observation` followed directly by `window_verified` and **zero** `window_submitted` lines.
* If a baseline never completes (`window_not_verified result=incomplete`), the adapter may issue one bounded recovery install; that install is reported as its own `window_submitted kind=install`.
* Full mode (A) produces only `install_requested mode=Full`; any `window_submitted` or `baseline_observation` in a Full run is a defect.
