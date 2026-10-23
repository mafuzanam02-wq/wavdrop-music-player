# P0 playback startup trace (debug builds only)

Evidence tooling for the large-library report: "selecting a song is slow / looks like network buffering" and
"Nothing queued next". It **observes only**; it changes no queue, ordering, Media3, crossfade, player construction or
persistence behavior.

* Code: `playback/PlaybackStartupTrace.kt`, with marks in `PlayerController`, `PlaybackService`, `PlaybackAssembly`
  (`defaultPhysicalPlayerFactory`) and tap hints in `HomeViewModel` / `GlobalSearchViewModel`.
* Active only when `BuildConfig.DEBUG` is true. In release builds every entry point returns immediately; call sites pass
  constants, ints and the player/error objects themselves, and all strings are built inside the tracer after that check.
  The only unconditional release cost is the tracer object's one-time static initialisation.
* Log tag: **`WavdropStartTrace`**. The pre-existing queue-install line is **`WavdropQueuePerf`** (kept as is).
* Privacy: lines contain only the request id, a fixed entry/stage vocabulary, queue/item counts, player serial numbers, the
  Media3 error **code name** and milliseconds. No titles, artists, paths, URIs or song ids. The request's start-item id is
  held in memory to compare against callbacks and is never logged.

## Capturing

Install a **debug** build (`.\gradlew.bat installDebug`; the app id is `com.launchpoint.wavdrop.debug`), then:

```bash
adb logcat -c
adb logcat -s WavdropStartTrace:D WavdropQueuePerf:D
```

To keep a file for review:

```bash
adb logcat -v time -s WavdropStartTrace:D WavdropQueuePerf:D > wavdrop-start-trace.txt
```

## Line format

```
id=<n> entry=<entry> cold=<true|false> queue=<size> stage=<stage> +<ms>ms [detail]
```

`id` correlates one request. `+ms` is milliseconds since `request_received` (monotonic `System.nanoTime`).
`cold=true` means the `MediaController` was **not connected when the request arrived** (the request was parked).
`tapToRequestMs` on `request_received` is the time from the UI tap hint to the controller request (it includes e.g. the
asynchronous search-tap-behavior settings read).

### Entry points

`songs_list`, `home_list`, `home_recent`, `search_replace_queue`, `search_preserve_queue`, `global_search_replace_queue`,
`global_search_preserve_queue` (from the tap hints); `play_from_queue` (any other caller: albums, artists, folders,
playlists, Smart Collections, shuffle, ...); `search_preserve` (controller default when no hint); `startup_restore`.

### Stages (in typical order)

| Stage | Meaning |
|---|---|
| `request_received` | PlayerController accepted the request (after the O(N) start-song resolution for the by-song overload). |
| `logical_queue_built` | `libraryQueue` / `playbackOrder` / `playbackQueue` computed. |
| `ui_state_published` | `NowPlayingState` updated with the new queue and song. |
| `controller_unavailable_parked` / `controller_connected_pending_drain` | Cold path: request parked, then drained after the controller connected. |
| `materialize_done` | `MediaItem` materialization for an install (`items`, cache `hits`/`misses`, `op`). |
| `playlist_install_started` | The install window opens, immediately BEFORE `setMediaItems`. The **first** install of a request fixes its item count and content fingerprint; later installs say `reinstall=true` and change nothing. |
| `playlist_install_done` | `setMediaItems` returned (`mutationMs`). |
| `prepare_issued` | `prepare()` called. Advances the request to PREPARED only if it installed a playlist (otherwise `attr=no_install_for_request`). |
| `play_issued` | `play()` called. |
| `playlist_visible_on_player` | A physical player's **own** listener reported a `PLAYLIST_CHANGED` timeline that arrived inside or after the install window and whose window count AND ordered media-id fingerprint equal what this request installed; that player (`owner=<serial>`) is now the request's owner. May be logged before or after `playlist_install_done`. |
| `playlist_change_unmatched` | A playlist change after the window opened whose count or content did not match (or from an unregistered player). Binds nothing. |
| `playlist_change_ignored` | A playlist change that arrived before the install window opened (`reason=before_install_window`): stale, never binds. |
| `owner_ambiguous` | A second, different player also reported a matching playlist (e.g. the crossfade NEXT slot receiving a graft of the same queue). Nothing is attributed from then on. |
| `ctl_state_*` / `svc_state_*` | Media3 playback state as reported to the controller (`ctl`) and by the service-side current player (`svc`). Context only. |
| `ctl_is_playing_true` / `svc_is_playing_true` | `onIsPlayingChanged(true)`. A state callback, context only. |
| `audio_position_advancing` | ExoPlayer `AnalyticsListener.onAudioPositionAdvancing` from the **owner** player for the **request's start item** while PREPARED. The only completion evidence (`complete basis=audio_position_advancing`). |
| `audio_position_advancing_unattributed` | The same callback that failed an attribution check; `reason=` is `before_prepare`, `no_owner`, `owner_ambiguous`, `other_player`, `media_mismatch` or `media_unknown`. Never completes a trace. |
| `audio_underrun`, `audio_sink_error` | Audio output problems; `attr=owner` or `attr=unattributed` by player. Context only. |
| `ctl_player_error` / `svc_player_error`, `bad_media_recovery_advance`, `aborted` | Errors and recovery. `aborted reason=` also covers `invalid_start_index`, `absent_or_ambiguous_start`, `bad_media_exhausted`, `bad_media_unclassified`. |
| `superseded` / `expired` | A newer request replaced the trace / a callback arrived more than 30 s after the request. |
| `complete` | `basis=audio_position_advancing`, or for `startup_restore` only `basis=ctl_ready_after_prepare` (state-only). |

State callbacks that arrive before the request's own `prepare()` carry `attr=pre_prepare`: they may belong to the previous
playback.

## Physical-player attribution

The service owns one physical ExoPlayer, or two with crossfade (CURRENT and NEXT, which swap roles at promotion). Each
physical player created by `defaultPhysicalPlayerFactory` gets **its own** analytics listener bound to that player's serial
(debug builds), so a callback is always attributed to the player that produced it; nothing is looked up afterwards.

Ownership of a request is established from the owning player's **own** `PLAYLIST_CHANGED` callback, never inferred from
list length alone. Before calling `setMediaItems` the controller opens an install window and records the request's playlist:
its item count and an order-sensitive fingerprint of its media ids (every id up to 512 windows, otherwise 512 evenly
strided ids plus the last). A player's callback binds only if it arrives inside or after that window **and** its count and
fingerprint equal the request's. The binding does not depend on whether the callback lands before or after
`playlist_install_done`. Measured (Robolectric, real ExoPlayer, `orderingProbe_...` test): the player's analytics
`timeline_changed` callback is delivered **inside** `setMediaItems`, i.e. before the call returns and before
`playlist_install_done`. Whether the Media3 controller-to-session hop adds a post (and so a later delivery) is not
observable without a running service, so both orders are accepted and only the install window, count and content decide.

Completion additionally requires the request to be PREPARED (its own `prepare()`), the owner to be unambiguous, the audio
callback to come from the owner, and its media item to be the request's start item.

Consequences and limits:

* A playlist callback from before the window opens (an earlier request, or an unrelated mutation) is `playlist_change_ignored`
  and never binds a newer request. A different playlist of the **same length**, or the same items reordered, is
  `playlist_change_unmatched` and never binds.
* An audio event from the crossfade secondary player, from an earlier request, or before the request's own install/prepare
  is reported as `*_unattributed` and **never** counts as the request having started.
* If a second, different player reports a matching playlist before the trace ends (for example the crossfade NEXT slot
  receiving a graft of the same queue), ownership becomes `owner_ambiguous` and nothing more is attributed. The trace then
  ends without a `complete`: an honest "could not attribute", not a failure of playback.
* If the owner cannot be bound at all, the trace never reaches `complete`; you will see `playlist_change_unmatched` and
  `reason=no_owner`.
* For queues longer than 512 items the fingerprint is **sampled**. Two different playlists that agree on the count and on
  every sampled id (for example the same large queue with an unsampled item swapped) cannot be told apart by content. If
  both are reported by different players, `owner_ambiguous` applies; if only one player reports, it is the same physical
  player that holds the request's queue and the attribution is still to the right player.
* The owner is bound once; it is not re-evaluated across a crossfade promotion. A trace normally finishes long before one.
* Re-tapping the **same song** in the same list produces an identical playlist and cannot be told apart from the earlier
  tap by content. The install window, the PREPARED requirement and the start-item check keep a stale event from the earlier
  tap from being accepted before the new request has installed; a residual ambiguity remains if the earlier tap's playout
  start were still pending after the replacement playlist was installed (not observed to be possible, not proven).
* Controller-side callbacks (`ctl_*`) carry no player or request identity in Media3's controller API. After `prepare()` they
  are timeline context, not proof that they belong to this request.

## Reproducing

Use a library of several thousand songs (the report concerns very large libraries). Capture with the commands above.

* **Cold**: force-stop the app (`adb shell am force-stop com.launchpoint.wavdrop.debug`), launch it, and tap a song as
  early as possible. A request is cold only if it reaches `PlayerController` **before** the `MediaController` has
  connected, so this is a race: launch-time session restore normally connects the controller within a moment. Whether
  you hit it is visible in the log: `cold=true` plus `controller_unavailable_parked` / `controller_connected_pending_drain`.
  If every tap logs `cold=false`, the cold path is not what a user normally experiences on that device (a finding in
  itself). A killed service (`adb shell am kill com.launchpoint.wavdrop.debug` while the app is backgrounded, then
  relaunch) is the other way to get a disconnected controller. The `startup_restore` trace shows the launch-time
  install cost of the restored queue.
* **Warm**: after one song is playing (controller connected), tap another. `cold=false`.
* **Songs list**: Songs tab, tap any song. Expect `entry=songs_list`, `queue=<whole sorted library>`.
* **Search (default "Preserve queue")**: search, tap a result. Expect `entry=search_preserve_queue`, `queue=<existing queue + 1>`.
  Repeat once with nothing queued (fresh launch / queue finished) to see a 1-song queue, and again with a large queue
  already playing to see the whole preserved queue re-installed.
* **Search ("Replace queue")**: Settings, search tap behavior, then search and tap. Expect `entry=search_replace_queue`
  (Songs tab search) or `entry=global_search_replace_queue` (global search), `queue=<whole library>`.

Repeat each at least five times and compare the first (cold JIT/class loading) with the rest.

## What the trace can show

* The ordering and duration of the synchronous controller-side work: queue build, materialization, `setMediaItems`.
* How long a request waited for the controller (cold start) and how long the tap took to reach the controller.
* Whether a "buffering-like" delay corresponds to Media3 reporting `BUFFERING` (`*_state_BUFFERING`) or is absent from the
  player and therefore UI-only.
* The relative order of controller-side and service-side callbacks. **This is not a measurement of cross-process delivery
  latency**: the two sides report different callbacks at different times, and nothing here pairs one service event with
  its controller counterpart, so a gap between them must not be read as IPC cost.
* Which entry point produced a 1-song queue (`queue=1`), and rejected taps (`aborted reason=...`).
* When an audio-position event can be tied to this request's owner player and start item (`audio_position_advancing`), and
  when it could not (`*_unattributed` with the reason).

## What it cannot prove

* **Audible output.** `*_is_playing_true` and `*_state_READY` are state callbacks. `audio_position_advancing` is the closest
  ExoPlayer signal (the audio sink's playout position started moving). It does not prove sound reached the speaker: volume,
  route, a silent intro or hardware latency are invisible to it, and it is only as reliable as the attribution above.
* Main-thread jank or frame drops (use Perfetto / `adb shell dumpsys gfxinfo` for that); the trace shows the blocking
  duration of the instrumented calls only.
* Time spent before the tap handler runs, or in Compose recomposition after `ui_state_published`.
* Storage, decoder or network-share latency inside Media3 beyond what appears as `BUFFERING` duration.
* Anything about release-build timing: R8, no debug logging, different timings. The numbers are for locating where
  time goes, not for absolute performance claims.
* Device behavior from JVM/Robolectric runs: those test the tracer's logic and attribution rules only.
