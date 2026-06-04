# Wavdrop Soft Launch QA Checklist

Use this checklist on a real Android phone with a realistic local music library. Record device model, Android version, Wavdrop version, and any screen recordings or logs for failures.

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
| Open Smart Collections. | All supported collection cards/rows appear. | |
| Open Favorites. | Favorite songs are listed or an intentional empty state appears. | |
| Open Most Played. | Aggregate/event rules display expected results. | |
| Open Recently Played. | Recent Wavdrop plays appear after listening activity. | |
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

## 16. BlackPlayer .bpstat Import

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Select a valid `.bpstat` export. | Preview opens and parses rows. | |
| Import rows that match Wavdrop songs. | Matched tracks and deltas are shown. | |
| Apply import once. | Aggregate play/skip stats update. | |
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

## 20. Theme / Accent / Launcher Icon

| Check | Expected result | Pass / Fail / Notes |
|---|---|---|
| Switch theme to Light. | App updates to light styling without unreadable text. | |
| Switch theme to Dark. | App updates to dark styling without unreadable text. | |
| Switch theme to System. | App follows device theme. | |
| Change accent color. | Primary controls and highlights use selected accent. | |
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
| Reports & Insights naming. | Settings category/screen title uses Reports & Insights where expected. | |
| Library statistics card. | Library shows compact song/album/artist/duration summary. | |
| Sleep Timer state. | Timer can be turned off and does not persist after process kill. | |

## Final Sign-Off

| Item | Pass / Fail / Notes |
|---|---|
| No crashes during smoke pass. | |
| No private data exposed in Diagnostics or reports beyond intended library metadata. | |
| Playback remains stable across foreground/background/headset scenarios. | |
| Backup/import tests completed with no unexpected data loss. | |
| Known issues documented with device details and reproduction steps. | |
