# Wavdrop QA Checklist

Current comprehensive manual / physical QA checklist. Use it on a real Android phone with a realistic local
music library. Record device model, Android version, Wavdrop version (Settings -> About), and any screen
recordings or logs for failures. Automated JVM tests do not replace these checks; Bluetooth, OEM, and
background behaviour can only be validated on hardware.

> **Warning - back up Wavdrop first.** Uninstalling or clearing app data removes local stats, playlists, settings, and backup schedules unless restored from a backup. Wavdrop has no in-app reset. Before ANY test below that involves uninstalling, clearing app data, or a fresh install, run Settings -> Backup & Migration -> Back Up Now (or Save Backup File), open Backup Verification, and confirm the file is verified and located in a place that survives the test. Restore is **merge-only** and never restores audio files.

## High-Risk Areas

| Area | Why it matters | Pass / Fail / Notes |
|---|---|---|
| Playback continuity | Core experience; regressions are highly visible. | |
| Queue mutation | Recent gesture and reorder work can affect current track stability. | |
| Library scan permissions | First-launch success depends on Android media permission behavior. | |
| Backup/import | Data restore/import issues can affect user trust. | |
| Statistics/reports accuracy | Wavdrop separates event-backed history from imported aggregate stats. | |
| Background/Bluetooth behavior | Real-device audio routing often exposes issues not seen on emulator. | |
| Large libraries | Performance and loading states must hold up with thousands of songs. | |

## Tester Setup

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Record phone model, Android version, storage size, and Wavdrop version. | Test report includes enough device context to reproduce issues. | |
| Test with at least one small library and one larger library if available. | Empty, normal, and stress paths can be compared. | |
| Include songs with and without album art. | Artwork fallback and real artwork both appear correctly. | |
| Include MP3, AAC/M4A, FLAC, OGG/Opus, and WAV files if available. | Supported format coverage is represented. | |
| Include at least one `.lrc` or `.txt` sidecar lyric file if available. | Lyrics sidecar behavior can be checked. | |

## 1. Install / First Launch

> **Back up Wavdrop first.** A fresh install test on a device with existing Wavdrop data destroys that data. Uninstalling or clearing app data removes local stats, playlists, settings, and backup schedules unless restored from a backup.

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Install Wavdrop fresh. | App installs without package/name errors. | |
| Launch Wavdrop for the first time. | App opens without crash or blank screen. | |
| Deny audio/library permission. | App explains why library access is needed and remains usable. | |
| Grant audio/library permission. | App proceeds to scan or shows library content. | |
| Close and reopen the app. | Startup destination loads predictably. | |
| Check Settings -> About. | App name, version, package, legal rows, support, and website are visible. | |

## 2. Library Scan

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Run a whole-device scan from Settings -> Library. | Songs appear after scan completes. | |
| Confirm files shorter than the configured minimum are excluded. | Short clips below the threshold are not listed. | |
| Change scan mode to selected folders. | UI shows selected-folder mode clearly. | |
| Add a selected folder. | Only eligible audio from selected folders appears after scan. | |
| Remove a selected folder. | Removed folder content disappears after rescan. | |
| Rescan after adding one new audio file. | New song appears without duplicating existing songs. | |
| Rescan after deleting a file from storage. | Deleted song is pruned from the library. | |

## 3. Playback Basics

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Tap a song in Songs. | Selected song starts playing. | |
| Pause and resume from mini-player. | Playback pauses/resumes without changing track. | |
| Pause and resume from Now Playing. | Playback pauses/resumes and position remains stable. | |
| Seek within a song. | Playback resumes from the selected position. | |
| Tap Next. | Next track starts according to current queue order. | |
| Tap Previous near the beginning of a track. | Previous track starts when available. | |
| Tap Previous after several seconds. | Current track restarts when expected. | |
| Open Track Details from overflow/Now Playing. | Details match the current song without exposing broken metadata. | |

## 4. Queue Behavior

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open Queue Sheet during playback. | Previously Played, Playing Now, and Up Next sections display correctly. | |
| Use Play Next from a song overflow. | Song appears immediately after Playing Now. | |
| Use Add to Queue from a song overflow. | Song appears at the end of Up Next. | |
| Remove an Up Next song using the available action. | Correct song is removed and current playback does not stutter. | |
| Move an Up Next song up/down if controls are available. | Queue order changes and current track remains stable. | |
| Tap a Previously Played song. | Song starts playing or behaves according to current supported action. | |
| Try to remove Playing Now. | Current track is protected unless a safe supported action exists. | |

## 5. Shuffle / Repeat

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Turn shuffle on. | Up Next order changes while Playing Now remains stable. | |
| Turn shuffle off. | Playback returns to predictable library/queue order. | |
| Set repeat off and reach queue end. | Playback stops or ends predictably. | |
| Set repeat all and reach queue end. | Playback loops to the start of the queue. | |
| Set repeat one and let a track finish. | Same track repeats. | |
| Toggle shuffle while repeat one is active. | Current track remains stable and controls stay responsive. | |

## 6. Now Playing Gestures

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Single tap album art. | Playback toggles play/pause. | |
| Double tap album art. | Lyrics overlay opens or closes. | |
| Long press album art. | Track Details opens. | |
| Swipe album art left. | Next track plays. | |
| Swipe album art right. | Previous track action runs. | |
| Make a small accidental movement while tapping. | Track does not skip. | |
| Swipe vertically on album art. | Track does not skip. | |
| Open lyrics overlay and swipe/scroll lyrics. | Lyrics remain usable and album-art swipe navigation does not interfere. | |

## 7. Sleep Timer

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open Settings -> Playback -> Sleep Timer. | Options Off, 15, 30, 45, 60 minutes, and End of current song appear. | |
| Select 15 minutes. | Settings row shows 15 minutes. | |
| Select Off after setting a timer. | Settings row returns to Off and timer does not pause playback later. | |
| Select End of current song and let the song finish. | Playback pauses/stops after the current item completes. | |
| Select End of current song with repeat one active. | Playback pauses/stops at the repeat boundary. | |
| Kill the app process after setting a timer. | Timer does not need to survive; no crash or stale UI on relaunch. | |

## 8. Lyrics

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open lyrics for a song with embedded lyrics. | Lyrics display. | |
| Open lyrics for a song with same-folder `.lrc`. | Sidecar lyrics display if supported by device storage access. | |
| Play a song with a timed `.lrc` sidecar. | Lyrics are synchronized: the current line follows playback and scrolls; seeking re-syncs. | |
| Pause, seek, and resume with synchronized lyrics open. | Highlight stays correct; no stutter or drift. | |
| Open lyrics for a song with same-folder `.txt`. | Text lyrics display if supported by device storage access. | |
| Open lyrics for a song without lyrics. | Empty state explains no lyrics are available and how to add/edit them. | |
| Edit unsynced lyrics in Track Details. | Saved custom lyrics display afterward. | |
| Clear custom lyrics. | App falls back to embedded/sidecar lookup. | |
| Scroll long lyrics. | Text scrolls smoothly and controls remain usable. | |

## 9. Playlists

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Create a playlist with a valid name. | Playlist appears in Playlists. | |
| Try creating a blank playlist name. | App rejects the blank name gracefully. | |
| Try creating a duplicate playlist name. | App rejects the duplicate gracefully. | |
| Add songs to a playlist. | Songs appear in playlist details in correct order. | |
| Remove a song from a playlist. | Song disappears from that playlist only. | |
| Reorder playlist songs if available. | New order is retained. | |
| Rename a playlist. | New name appears and duplicate rules still apply. | |
| Delete a playlist. | Playlist is removed without deleting audio files. | |

## 10. Favorites

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Toggle favorite from song overflow. | Favorite status changes and snackbar feedback appears. | |
| Double tap a song row if supported. | Favorite status toggles without starting unintended playback. | |
| Open Favorites smart collection. | Favorited songs appear. | |
| Remove a favorite. | Song disappears from Favorites after refresh/update. | |
| Restart app. | Favorite status persists. | |

## 11. Search

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Search by song title. | Matching songs appear. | |
| Search by artist. | Matching songs appear. | |
| Search by album. | Matching songs appear. | |
| Search with different casing. | Results remain case-insensitive. | |
| Search for a missing term. | Empty state explains no matches and suggests changing the search. | |
| Tap a search result. | Song starts while preserving the broader library queue order. | |

## 12. Albums / Artists / Folders

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open Albums. | Albums list displays counts/artwork/fallbacks correctly. | |
| Open an album. | Album details show correct songs and playable rows. | |
| Open Artists. | Artists list displays local representative artwork or fallback. | |
| Open an artist. | Artist details show header, albums, songs, and insights correctly. | |
| Open Folders. | Folder list groups songs without exposing broken paths in normal UI. | |
| Open a folder. | Folder details show only songs from that folder. | |
| Play from album/artist/folder detail. | Playback starts and queue order matches the context. | |

## 13. Smart Collections

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open Smart Collections. | All 11 collection cards/rows appear (Favorites, Most Played, Recently Played, Forgotten Gems, Never Played, Recently Added, Most Skipped, Long Tracks, Short Tracks, Always Finish, Usually Abandon). | |
| Open Favorites. | Favorite songs are listed or an intentional empty state appears. | |
| Open Most Played. | Aggregate/event rules display expected results. | |
| Open Recently Played. | Recent Wavdrop plays appear after listening activity. | |
| Open Forgotten Gems, Always Finish, and Usually Abandon. | Each lists plausible songs or an intentional empty state; counts say how many songs qualify. | |
| Open Never Played. | Songs with no play history appear. | |
| Open Recently Added. | Recently added songs appear in plausible order. | |
| Open Most Skipped. | Skipped songs appear after skip activity. | |
| Open Long Tracks and Short Tracks. | Songs are grouped by duration as expected. | |

## 14. Statistics / Reports / Wrapped

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Play a song past the meaningful-play threshold. | Statistics eventually reflect one play. | |
| Skip a song. | Skip count updates where shown. | |
| Open Statistics Dashboard. | Overview and lists show data or polished empty state. | |
| Open Listening Reports. | All-time report uses aggregate stats correctly. | |
| Open Monthly Reports with event history. | Month shows event-backed counts/lists. | |
| Open Monthly Reports without event history. | Empty state explains no event history for that month. | |
| Open Wrapped with available year. | Event-backed yearly summary appears. | |
| Open Wrapped without event history. | Empty state explains Wrapped needs Wavdrop listening history. | |
| Import BlackPlayer stats, then check monthly reports. | Imported aggregate stats do not fake monthly event history. | |

## 15. Backup Export / Restore

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Export a Wavdrop backup JSON. | File is created through Android file picker. | |
| Export after creating playlists/favorites/stats. | Backup completes without crash. | |
| Import a valid Wavdrop backup on the same device. | Preview displays restorable data. | |
| Apply a valid restore. | Stats/playlists/preferences restore as supported. | |
| Import an invalid JSON file. | App shows a clear error and does not change data. | |
| Import an unsupported backup version if available. | App rejects it safely. | |
| Restore does not duplicate playlists unexpectedly. | Playlist results remain understandable. | |
| Open Settings -> Backup & Migration -> Backup Verification on a fresh backup. | File is reported verified with counts for songs, stats, playlists, events, lyrics. | |
| Restore the same backup a second time. | Result is a no-op or reports nothing new; stats, events, and playlists do not inflate. | |
| Restore an older backup over newer local activity. | An older-backup warning appears; local history is not lowered (merge keeps the higher values). | |
| Restore on a clean install (after uninstall/reinstall). | Preferences restore, you are prompted to re-select the music folder if needed, then history merges after the scan. | |
| Restore a backup that contains songs not on this device. | Result reports history preserved for later matching (pending/quarantine); no crash; nothing is shown as playable. | |
| Edit a backup file by hand and import it. | Import is rejected with an integrity error before any change. | |
| Import a Wavdrop Desktop backup (if available), twice. | Matched stats, playlists, and desktop events import; second import does not duplicate events. | |
| Export, then re-import an export that came from a restored device. | Restored history (events from `manual_restore`) survives a second backup generation. | |

## 16. BlackPlayer .bpstat Import

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Select a valid `.bpstat` export. | Preview opens and parses rows. | |
| Import rows that match Wavdrop songs. | Matched tracks and play totals are shown; the file's period plays are shown as "not imported". | |
| Apply import once. | Play counts update (MAX-merged); skip counts are NOT changed (`.bpstat` field 2 is a period play count). The result lists plays updated, no skips. | |
| Apply the same import again. | Delta-based import reports no new changes. | |
| Import a file with unmatched rows. | Unmatched rows are counted/skipped without crash. | |
| Import malformed file. | App shows clear error and does not change stats. | |
| Check event-backed reports after import. | Import does not create listen event history. | |

## 17. Bluetooth Behavior

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Connect Bluetooth headphones while playback is paused. | Auto-resume follows current settings. | |
| Connect Bluetooth headphones while playback is active. | Playback continues without duplicate starts. | |
| Disconnect Bluetooth headphones. | Playback pauses or continues according to audio route/settings behavior. | |
| Change Bluetooth auto-resume setting. | New behavior is reflected on next connection. | |
| Switch between phone speaker and Bluetooth output. | App remains responsive and Now Playing state stays correct. | |
| Connect Bluetooth while the app process is cold (swipe away, force-stop is a harsher variant). | Resume follows the setting; if a session exists it is rebuilt without overwriting a queue you started. | |
| Press Pause on the headset/car right as Bluetooth reconnects. | Explicit user pause/play wins over the automatic resume; no unexpected restart. | |
| Disconnect Bluetooth quickly after connecting (before auto-resume fires). | A lost route cancels the pending automatic resume; playback does not start on the speaker. | |
| Test a Bluetooth LE Audio device, if available. | Reconnect behaviour matches classic A2DP behaviour. | |

## 18. Wired Headphones

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Plug in wired headphones while playback is paused. | Auto-resume follows current settings. | |
| Plug in wired headphones while playback is active. | Playback continues without restarting. | |
| Unplug wired headphones. | Playback pauses if pause-on-disconnect is enabled. | |
| Rapidly plug/unplug once. | App does not crash or start multiple playback sessions. | |
| Change wired auto-resume setting. | New behavior is reflected on next connection. | |

## 19. Notifications / Background Playback

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Start playback and press Home. | Playback continues in background. | |
| Lock the phone during playback. | Playback continues and system controls remain usable. | |
| Pause from notification/media controls. | App state updates to paused. | |
| Resume from notification/media controls. | Playback resumes and UI reflects state when reopened. | |
| Use Next/Previous from system controls. | Queue navigation matches app behavior. | |
| Swipe app away from recents during playback. | Behavior is predictable and no crash occurs. | |
| Pause, then swipe the app away from recents; press Play from the notification / headset / lock screen. | The paused session survives and playback resumes at the saved position. | |
| With the process cold, press PLAY from notification, Bluetooth, lock screen, and widget (one at a time). | Playback starts from the restored session without opening the app UI. | |
| Open an Android system media surface / music-app integration that lists Wavdrop. | Wavdrop appears with a browsable Songs list and a Recent item; tapping resumes correctly. | |
| Start a queue in the app, then trigger an external PLAY. | The existing queue is not replaced by the saved session. | |

## 20. Theme / Accent / Launcher Icon

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Switch theme to Light. | App updates to light styling without unreadable text. | |
| Switch theme to Dark. | App updates to dark styling without unreadable text. | |
| Switch theme to System. | App follows device theme. | |
| Change accent color. | Primary controls and highlights use selected accent. | |
| Fresh install: check the launcher icon. | Default is Obsidian Black. | |
| Cycle through all six launcher icons (Obsidian Black, Midnight Violet, Clean Purple, Deep Teal, Ocean Blue, Sunset Orange). | Each applies; only one launcher entry exists at a time; launcher caching may delay the visible change. | |
| Change launcher icon. | Preference saves; launcher icon updates according to device launcher behavior. | |
| Restart app after theme/accent changes. | Preferences persist. | |

## 21. Diagnostics Screen

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open Settings -> About -> Diagnostics. | Diagnostics screen opens without crash. | |
| Verify app version/package/database fields. | Values match installed build. | |
| Verify song/album/artist/playlist/event counts. | Counts are plausible and contain no private names. | |
| Verify theme/accent/startup/scan settings. | Values reflect current settings. | |
| Confirm selected folders shows only a count. | Folder names and paths are not exposed. | |
| Confirm no edit/delete/reset buttons exist. | Screen is read-only. | |

## 22. Empty States

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Fresh install with no permission. | Empty state explains library access is needed. | |
| Empty library after permission. | Empty state explains how to add audio files/scan. | |
| Empty Playlists. | Empty state explains how to create playlists. | |
| Empty Smart Collection. | Empty state explains why collection is empty. | |
| Empty Search results. | Empty state explains no matches and suggests changing query. | |
| Empty Statistics/Reports/Wrapped. | Empty state explains listening history requirement. | |
| Empty Lyrics. | Empty state explains no lyrics and supported ways to add them. | |

## 23. Loading States

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open Home immediately after launch. | No broken blank screen during data load. | |
| Open Songs during/after scan. | Loading or content state appears intentionally. | |
| Open album/artist/folder details quickly. | Screen does not flash broken empty content. | |
| Open playlist details with many songs. | Loading/content transition is smooth. | |
| Open Statistics/Reports/Wrapped. | Loading state appears only when useful and not noisy. | |
| Open Now Playing before playback starts. | Empty/loading state is clear and stable. | |

## 24. Large-Library Stress Testing

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Scan a library with at least 1,000 songs if available. | Scan completes without crash. | |
| Scan a library with 5,000+ songs if available. | App remains usable after scan. | |
| Scroll Songs rapidly. | Rows remain smooth, artwork loads progressively, no wrong overflow target. | |
| Use A-Z index on a large library. | Jumping is fast and lands near expected section. | |
| Search large library. | Results update without freezing the app. | |
| Open Albums/Artists on large library. | Lists load and scroll acceptably. | |
| Start playback from a large library search result. | Correct song plays and queue remains correct. | |
| Open Queue Sheet with a large queue. | Sheet opens and scrolls without severe jank. | |

## 25. Regression Notes

| Scenario | Expected result | Pass / Fail / Notes |
|---|---|---|
| Queue remove after recent gesture fixes. | Red/remove state does not get stuck and audio does not stutter. | |
| Queue drag/reorder if enabled. | Auto-scroll/reorder behavior is acceptable or unavailable by design. | |
| Album-art swipe navigation. | Swipe left/right skips tracks without triggering tap actions. | |
| Song row artwork. | Shared rows show artwork/fallback consistently across screens. | |
| Artist artwork. | Artist list/details use local album-art-derived image or fallback. | |
| About/legal screens. | Privacy, Disclaimer, Open Source, Supported Formats, and Diagnostics all open. | |
| Insights naming. | Bottom-nav tab and Settings entry both read "Insights". | |
| Library statistics card. | Library shows compact song/album/artist/duration summary. | |
| Sleep Timer state. | Timer can be turned off and does not persist after process kill. | |

## 26. Duplicate Songs in the Queue

Build a queue where the same song appears more than once (for example Add to queue twice, or a playlist
that contains the song and an album queue).

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Play the second occurrence of a duplicated song from the Queue Sheet. | That exact occurrence plays; Previously Played / Up Next split is correct around it. | |
| Jump to a duplicated song from the queue, then press Next and Previous. | Navigation follows queue positions, not the first copy of the song. | |
| Turn shuffle on and off while a duplicated song is playing. | The same occurrence keeps playing; no jump to the other copy. | |
| Remove one occurrence of a duplicated song from Up Next. | Only that occurrence is removed. | |
| Reorder around a duplicated song. | Current occurrence and position are preserved. | |
| Start playback of a song from a playlist that contains it twice (each position). | The tapped position starts, not the first match. | |
| Kill the app mid-playback of the second copy, reopen, and resume. | The same occurrence is restored. | |
| Delete (Track Details, Android 11+) a song that appears twice in the queue. | Playback and queue stay consistent; no crash. | |

## 27. Queue Bulk Cleanup and Mutations

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Use the bulk clear actions in the Queue Sheet (for example clear upcoming). | The current song keeps playing and only the intended songs are removed; the queue updates immediately. | |
| Add many songs (Play next / Add to queue / album actions) in quick succession. | Order is correct; no stutter or restart of the current song. | |
| Move a song up/down and by drag during playback. | Order commits once on drop; current song and position unchanged. | |

## 28. Equalizer

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Open Settings -> Equalizer. | Screen shows device-supported bands and presets, or a clear unsupported state. | |
| Enable the Equalizer and pick a platform preset, a Wavdrop preset, then custom bands. | Sound changes audibly; settings persist after leaving the screen. | |
| Restart the app and the process. | Equalizer settings persist and apply to the next track. | |
| Switch output (speaker / Bluetooth / wired) with the Equalizer on. | No crash; the effect stays applied or re-attaches. | |
| Note: Equalizer settings are not part of backups today. | Restoring a backup does not change Equalizer settings. | |

## 29. Home-Screen Widget

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Add the Wavdrop widget to the home screen. | Shows artwork, title, artist. | |
| Tap Previous / Play-Pause / Next on the widget with the app open and closed. | Controls act on the current session; the widget updates immediately. | |
| Press the widget Play with a cold process. | Playback resumes from the saved session. | |
| Resize the widget. | Layout adapts without clipping. | |

## 30. Automatic Backup

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Choose a backup folder and set an interval. | Settings shows the folder and a "Last automatic check" status; wording says automatic checks, not guaranteed schedules. | |
| Leave the app closed past the interval (or advance the device clock for a quick test). | A verified backup appears in the folder after the periodic check runs (timing is best-effort). | |
| Revoke the folder permission or remove the folder. | Status shows "Folder unavailable"; no crash; no backup is written elsewhere. | |
| Set the interval to Off. | No further automatic backups are created. | |

## 31. Identity and Event Ids (observable via backup files)

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Play several songs, then export a backup and inspect the JSON. | New `listenEvents` carry an `eventId`; older events from before the identity foundation have none. | |
| Restore the same backup twice and recount events. | Event count does not grow on the second restore. | |
| Remove a song from storage, rescan, re-add it, rescan. | No crash; the song's history is not silently attached to a different song. | |
| Inspect an exported backup. | There is no track-identity section (identity is device-local and not exported). | |

## 32. Crossfade

**Production rollout is ENABLED** through `CrossfadeRolloutPolicy.RUNTIME_ENABLED = true` (CF-2N2, after the CF-2N1 physical sign-off recorded in 32.12). It is the single gate; crossfade is Off by default and unavailable while the Equalizer is on.

The Playback Settings Crossfade row is visible. With the saved duration Off (the default) behaviour is the unchanged gapless native transition: no overlap and no volume dips between tracks.

**Architecture status.** The CF-2L secondary-player natural-handoff implementation is **RETIRED** (deleted by CF-2M8). The **CF-2M promotion architecture is ACCEPTED**: the next track B is prepared on the engine's NEXT player, started once, promoted to CURRENT at fade start and left authoritative, while the old CURRENT (the RETIRING player) fades out and is recycled. There is no final ownership transfer. **CF-2M7 was the PHYSICAL CORE AUDIO PASS** (see 32.2) and **CF-2N1 the production-rollout physical sign-off** (see 32.12).

The procedure below is the validation and regression checklist for crossfade. The rollout gate was flipped only after the automated gate passed and every required physical condition was physically verified (see 32.12); re-run the relevant rows on any release candidate that changes playback code.

Model used below: **CURRENT** (audible, authoritative), **NEXT** (the prepared incoming track B, silent until promotion), **RETIRING** (the previous CURRENT while it fades out), then recycled. Expected UI behaviour: **Now Playing, the notification and the session move to B at fade start**, while A fades out and B fades in. This is intentional (ownership moves at promotion), not a defect.

### 32.1 Device evidence (record for every run)

Record in the notes column or a short run log: device model, Android version, WavDrop build/commit, audio output used (speaker / wired / Bluetooth device), crossfade duration, EQ state, repeat mode, result and a note/log reference. A manual record is sufficient; no database or telemetry is involved.

Minimum initial validation target: at least one real Android device covering speaker, wired output (if the device supports it; USB-C wired audio counts), Bluetooth output, foreground, and background/lock-screen. The Samsung S21 already used for Wavdrop validation is acceptable as the first device. One device does not validate Android universally; do not generalise beyond the devices tested. A requirement with no available hardware is **UNVALIDATED**, never Pass.

### 32.2 Basic overlap

**Recorded physical result (CF-2M7, gate-enabled debug validation build of the promotion architecture, before the CF-2M8 cleanup; user-reported):** ordinary A → B transitions and extended **10 s and 12 s** crossfades were clean; B starts once and remains authoritative; the old final takeover stutter/restart is **gone**; the transition sounded clean through the overlap. The UI/session moved to B at fade start while A faded out (intentional). `END_MARGIN_MS` stayed at 500 ms (not tuned). The Equalizer was not validated. This is a **PHYSICAL CORE AUDIO PASS** for the ownership architecture. It was not run on the post-CF-2M8 build and does not cover the rows below that were not reported; the active blocker is now **validation breadth** (background, Bluetooth, wired, EQ policy, interactions), not the ownership seam.

*Historical, RETIRED:* the CF-2L runs (CF-2K2A, CF-2L1, CF-2L2) of the secondary-player handoff design showed a 1-2 s then smaller takeover stutter at the final ownership transfer and intermittent play/pause problems after some handoffs. That seam was the cause the promotion architecture removes; CF-2L4 was never retested and no longer exists.

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| In a validation build, open Settings → Playback. | A Transitions section with a Crossfade row is visible. (Never visible in a build with the gate disabled.) | |
| Choose Off and let a track end. | Native transition, no overlap. | |
| Choose 2 seconds and let a track end. | Audible overlap of roughly 2 seconds; B fades in as A fades out; no restart/rewind of B. | |
| Choose 6 seconds and let a track end. | Audible overlap of roughly 6 seconds. | |
| Choose 12 seconds and let a track end. | Clean bounded overlap, no crash. | CF-2M7 (pre-CF-2M8 build): 10 s and 12 s clean. Re-confirm on the post-CF-2M8 validation build. |
| Change the duration while playback continues. | The next eligible transition follows the new duration; a transition already overlapping is not retimed. | |
| Watch Now Playing at the transition. | Title/art/progress move to B once at fade start; no return to A; no jump backwards. | CF-2M7: moves to B at fade start (intentional). |

Listening/behaviour validation only; stopwatch precision is not required.

### 32.3 Eligibility

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Repeat One, let a track end. | No crossfade (native repeat). | |
| Queue with a single entry. | No crossfade. | |
| Repeat Off, final item ends. | No crossfade; playback ends normally. | |
| Repeat All, final item ends. | The wrap transition to the first item may crossfade. | |
| A track so short that a safe overlap is below the minimum. | Native transition, no overlap. | |

### 32.4 Duplicate occurrences (occurrence safety)

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Queue the same song more than once at different positions and let the earlier one end. | The crossfade targets the next occurrence by queue position and does not jump to the first occurrence of that song. | |

### 32.5 Manual interaction during preparation or overlap

Perform each while NEXT is being prepared and again during an audible overlap (organise runs coherently; not all in one run). During an overlap B is already the current track: every action below cuts the retiring A first and then acts on B.

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Pause, then resume. | A is cut and B pauses; resume resumes B only; no stuck low volume, no doubled playback, no ghost retiring audio. | |
| Seek in the current track. | The retiring A is cut; the seek acts on B only; no restart of B beyond an ordinary seek. | |
| Next, then Previous. | The retiring A is cut; the normal next/previous policy runs from B (including the previous-restart threshold); the correct track plays. | |
| Toggle shuffle; change repeat mode. | The overlap settles safely; no wrong-track transition. | |
| Play Next; Add to Queue; reorder the queue; remove a queued item. | A is cut; the queue stays correct; no ghost audio. | |
| Delete a queued track from the library (where supported). | Same; no crash. | |

### 32.6 Audio focus

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| During preparation or overlap, cause an incoming call or another transient audio-focus interruption (any practical source). | The retiring A is cut; B is the only current track and is suppressed by the interruption, then resumes per the normal WavDrop/Media3 focus behaviour when focus returns; A never returns. | |
| During an overlap, cause a short "duck" (e.g. a navigation prompt). | The overlap continues; both tracks are quieter and recover; no restart. | |

### 32.7 Becoming noisy / route removal

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Unplug wired headphones during preparation and during overlap. | One pause; the retiring A is cut; B remains the current track; no second player keeps sounding; the existing WavDrop pause/resume policy remains authoritative. | |
| Disconnect Bluetooth during preparation and during overlap. | Same. | |

### 32.8 Bluetooth

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Start playback on Bluetooth and let tracks crossfade. | One audible stream; normal overlap. | |
| Background the app; lock the screen; let more transitions occur. | Same; no crash. | |
| Use next/previous from the headset. | Controls remain authoritative; correct queue occurrence. | |
| Disconnect during preparation and during overlap; then reconnect. | One audible stream, no duplicate playback, no stuck gain, no stale promotion after reconnect. | |

### 32.9 Wired headphones

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Play wired and let tracks crossfade. | Normal overlap. | |
| Unplug during preparation and during overlap, then reconnect. | Behaviour follows the existing WavDrop resume policy only (no invented automatic resume); no ghost audio. | |
| Manual next/previous; background and lock screen. | Same expectations as Bluetooth. | |

### 32.10 Background and lock screen

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Begin a track in the foreground, background the app before the crossfade, let it transition. | No crash, no duplicate playback. | |
| Lock the screen before the next transition and let it transition. | Same. | |
| Use notification transport during preparation and overlap. | System transport remains authoritative; B is the current state; no duplicate playback; correct queue occurrence. | |
| Use lock-screen transport during preparation and overlap. | Same. | |

### 32.11 Equalizer policy, failures and teardown

This validates the CURRENT policy only (crossfade is unavailable while the Equalizer is on). It does **not** validate an Equalizer mirrored across both players and does not lift the EQ restriction (CF-2M9 remains optional and separate).

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Crossfade = 6 seconds, EQ off; play through a transition. | Crossfade is eligible and overlaps. | |
| Enable the Equalizer. | The Crossfade row is disabled with "Unavailable while Equalizer is on"; the saved 6-second preference stays stored. | |
| Play through a transition with EQ on. | No crossfade; the EQ remains audible on ordinary playback. | |
| Enable the Equalizer DURING an overlap. | The retiring A is cut; B continues with the Equalizer; no re-promotion. | |
| Disable the Equalizer. | The row is enabled again and shows 6 seconds; a later eligible transition can crossfade. | |
| Where practical: unreadable next track; next track removed before the transition; NEXT preparation failure; current-player playback error. | The crossfade fails closed, one authoritative current track continues or the normal queue recovery runs, no ghost retiring audio, existing queue recovery behaviour is unchanged. No artificial destructive hooks are added for this. | |
| If reproducible: swipe the app away or stop the playback service during preparation and during an overlap. | No continuing retiring-player audio, no leaked audio, the next launch starts from a normal state. | |

### 32.12 Production enablement sign-off

Production enablement required every row below to be Pass on the intended release build and device set; the gate flip (CF-2N2) was a separate slice made after this table was complete. This mirrors `CrossfadeProductionReadiness`. No device model or Android version was supplied for the sign-off, so none is recorded here.

**CF-2N1 production-rollout physical sign-off: PASSED** (user-reported, on the CF-2N1 post-CF-2M8 validation build). EQ policy only: EQ on makes crossfade unavailable, EQ off makes the saved duration available; crossfade with the Equalizer (CF-2M9) is not part of the rollout and was not validated. **Automated rows below were established by the CF-2N2 run** (full JVM suite and release build).

| Item | Pass / Fail / Notes |
|---|---|
| Automated JVM suite green | **PASS** (CF-2N2: full JVM suite, 2628 tests, 0 failures, 0 skipped) |
| Release APK assembled | **PASS** (CF-2N2: `app:assembleRelease` succeeded) |
| Crossfade core overlap passed | **PASS** (CF-2N1; earlier CF-2M7 core audio pass: 10 s / 12 s clean, no takeover stutter) |
| Repeat eligibility passed | **PASS** (CF-2N1) |
| Duplicate occurrence passed | **PASS** (CF-2N1) |
| Manual interaction cancellation passed | **PASS** (CF-2N1) |
| Background / lock-screen passed | **PASS** (CF-2N1) |
| Bluetooth passed | **PASS** (CF-2N1) |
| Wired passed | **PASS** (CF-2N1; wired / supported wired route) |
| EQ compatibility passed | **PASS** (CF-2N1; policy only, see above) |
| No stuck gain (CURRENT / NEXT / RETIRING) | **PASS** (CF-2N1) |
| No ghost retiring audio | **PASS** (CF-2N1) |
| No wrong / stale promotion state | **PASS** (CF-2N1) |
| No crash | **PASS** (CF-2N1) |

## Now Playing Artwork Reliability (WC-07) — physical validation PENDING

Run on a real device; use the debug build and filter logcat by tag `WavdropArtwork` (request / failure lines for the large artwork).

| Check | Pass / Fail / Notes |
|---|---|
| Track with known artwork: Now Playing, Mini Player and Home row all show it. | |
| Track with no artwork: placeholder everywhere, and no previous cover left behind on Now Playing. | |
| Previously problematic track (Mini Player showed art but Now Playing did not): Now Playing now shows it; if not, capture the `WavdropArtwork` failure line. | |
| Two tracks from the same album: switching between them causes no placeholder flash. | |
| Rapid next/previous: the final track's artwork (never an intermediate one) is shown. | |
| Shuffle transitions: artwork always matches the current track. | |
| Crossfade transition: artwork follows the new track without sticking on the old one. | |
| Open and close Now Playing repeatedly: artwork present every time. | |
| Toggle the lyrics overlay: artwork returns correctly when it is hidden. | |
| Rotate / resize (if applicable): artwork re-renders at the new size without disappearing. | |
| Background the app and reopen: artwork still present. | |
| Compare Mini Player, Home and the media notification for the same track (notification uses the file's embedded art, a different source). | |
| Now Playing background mode other than Artwork: no artwork is expected (not a bug). | |

## Final Sign-Off

| Item | Pass / Fail / Notes |
|---|---|
| No crashes during smoke pass. | |
| No private data exposed in Diagnostics or reports beyond intended library metadata. | |
| Playback remains stable across foreground/background/headset scenarios. | |
| Backup/import tests completed with no unexpected data loss. | |
| Known issues documented with device details and reproduction steps. | |
