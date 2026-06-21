# Wavdrop Release Notes

Historical record of completed and shipped work. Newest changes first.

---

## 0.1.0-beta7.5

### Added
- Added All-Time Wrapped with aggregate-backed lifetime listening stats.
- Added All-Time Wrapped cards for lifetime totals, top songs, top artists, top albums, skips, recent plays, and listening summary.
- Added backup restore visibility for playlists with unmatched songs.
- Added precise warning when restoring older backups may overwrite newer local listening activity.

### Improved
- Hardened backup and migration resilience.
- Preserved additional Wrapped and milestone settings during backup/restore.
- Improved selected-folder restore behavior by prompting users to reselect folders after migration.
- Moved Wrapped, Monthly Reports, Home Wrapped Preview, and Insights analytics computation off the main thread.

### Fixed
- Fixed selected-folder backup restore silently producing empty libraries on new devices.
- Fixed missing backup coverage for Wrapped appearance and milestone settings.
- Fixed backup notification-controls default comparison.
- Fixed legacy backup compatibility for newly added preference fields.

### Notes
- All-Time Wrapped uses aggregate stats and intentionally avoids event-heavy cards for this MVP.
- Event-derived lifetime insights such as streaks, heatmaps, rediscovery, and comeback moments are deferred.

---

## Beta 7 — Navigation, Discoverability & Polish

### Navigation & Discoverability

- **Smart Collections now appear in Global Search.** Searching from any screen now surfaces Smart Collections (Forgotten Gems, Recently Played, Never Played) alongside songs, albums, artists, folders, and playlists.
- **Insights now has a Search entry point.** A search icon in the Insights top app bar opens Global Search directly, matching the behaviour on Home, Songs, and Library.
- **Settings now accessible from Insights.** A Settings icon in the Insights top app bar provides access to Settings without returning to Home first.
- **Empty Now Playing startup guard.** If the startup destination is set to Now Playing but no session has been saved, the app now falls back to Home instead of opening an empty, dead-end Now Playing screen.

### Audits & Consistency

- **Home screen audited.** Dashboard layout, MiniPlayer placement, empty states, and section wording reviewed and polished.
- **Library screen audited.** Hub card subtitles reviewed; Songs card intentionally retained as part of the complete content directory.
- **Wrapped screen audited.** Appearance and navigation polish reviewed.
- **"Insights" label normalised.** The Settings entry for the Insights hub was previously labelled "Reports & Insights"; it now consistently reads "Insights" across all surfaces.

### Polish & Wording

- **Monthly Reports empty state wording.** Internal "event-backed" and "aggregate" terminology removed from all user-facing Monthly Reports messages, replaced with plain language.
- **Metadata separator standardised.** Album Details and Folders list views now use the interpunct (`·`) separator between song count and duration, matching the Albums list.
- **Overflow menu contentDescriptions standardised.** All overflow/more-options icons across Album Details, Folder Details, and Now Playing now use the consistent label "More options".
- **Home empty-state button wording clarified.** The "Choose folder in Settings" button is now labelled "Library Settings" where it navigates directly to Library & Scanning, and "Settings" where it navigates to the main Settings hub.
- **Backup import empty state clarified.** "Choose Import Wavdrop Data from Settings" replaced with plain-language "Go to Settings to choose a Wavdrop backup file."
- **Backup section header capitalisation fixed.** "Automatic backup check" corrected to "Automatic Backup Check" for consistency with other section headers on the same screen.
- **Monthly Reports skip section.** "No skips recorded for this month. Skips during this month will appear here." tightened to remove repetition.

---

## Beta 3 — Backup Reliability & Queue Control

### Backup & Restore

- **Backup Verification screen.** A new screen in Settings → Backup lets you check whether your backup is intact before you need it. It confirms the file is readable, shows a summary of what's saved (songs, stats, playlists, listening history, custom lyrics), and flags any warnings.
- **Backup integrity protection.** Every backup now includes a checksum of its payload. If the file is damaged or written incompletely, Wavdrop detects it at verification time and on restore — instead of silently restoring corrupted data.
- **Manifest validation.** The backup records the count of each section. On restore, Wavdrop cross-checks these counts before applying any changes, catching truncated or partially-written files.
- **Better restore diagnostics.** The restore preview now shows how many songs were matched, how many entries were skipped because no matching song was found, and separate counts for stats, playlists, lyrics, and listening history.
- **Listening history now survives reinstall and app upgrades.** Play/skip events are re-keyed to current song IDs on restore, so Monthly Reports, Most Played, and Wrapped rebuild correctly after reinstalling or clearing app data.
- **Monthly Reports and Wrapped recovery improved.** Events generated by manual restores are now included in the backup so time-scoped analytics survive across backup generations.
- **BlackPlayer import baseline preservation improved.** Import baselines are now correctly re-keyed on restore, preventing artificially inflated play counts if you re-import BlackPlayer stats after restoring.
- **Safer manual export.** When you export a backup file manually, Wavdrop reads the saved file back and confirms it parses correctly before reporting success. A failed write is surfaced as an error rather than silently producing an empty file.
- **Atomic auto-backup writes.** Auto-backups now write to a temporary file first, validate it, then write the final file — keeping the previous backup untouched until the new one is confirmed complete.
- **Older backups continue to work.** Backups made before this update restore normally. New integrity fields are added only to backups created going forward.
- **More settings included in backups.** Artwork corner style, thumbnail display, Now Playing background, queue count, time display mode, notification controls, WhatsApp voice note filter, and Bluetooth/wired resume behaviour are now saved and restored.

### Playback & Queue

- **Album queue actions.** Album Details now has Play, Shuffle, Play next, and Add to queue buttons for the entire album. These appear at the top of the track list.
- **Artist queue actions.** Artist Details has the same four actions for the artist's full song list.
- **Folder queue actions.** Folder Details has the same four actions for all songs in that folder.
- **Play next and Add to queue for individual songs.** The overflow menu on every song row (Songs, Home, Search, Album, Artist, Folder, Playlist) already exposes Play next and Add to queue. Play next inserts the song immediately after the current track without disrupting the rest of the queue. Add to queue appends it at the end.

### Appearance

- **Obsidian Black is now the default launcher icon.** New installs start with the Obsidian Black icon. The icon can still be changed in Settings → Appearance.
- **Appearance preferences restore more completely.** Theme, accent colour, artwork style, display layout, and startup screen are all included in backups and restore on a new device or fresh install.

### Library & Scanning

- **Selected folder mode is now safe if folder access changes.** If you use selected-folder scanning and the chosen folder becomes temporarily unreachable (permission changed, SD card moved, storage path shifted), Wavdrop now preserves your existing library instead of clearing it. Settings → Library & Scanning shows a notice so you know to review your folder selection.
- **Rescans are more reliable on large music libraries.** The internal scan-to-database update no longer uses a query pattern that could fail silently for libraries above roughly 1,000 tracks. Large collections are now handled in smaller batches throughout.
- **Playlists are cleaned up automatically during a rescan.** If a song is removed from your device and Wavdrop detects it during a rescan, that song is now also removed from any playlists it belonged to. Previously it could linger as an unplayable entry.
- **Deleting a track cleans up more thoroughly.** When you delete a track from the device via Track Details, Wavdrop now also removes the associated BlackPlayer import record for that track. Play counts and listening history are intentionally kept so your stats and reports remain accurate.

### Stability & Polish

- **Bluetooth and wired resume reliability improvements.** Cold-start resume after device reconnect is more reliable; duplicate reconnect events are debounced.
- **Backup folder recovery after restore.** When a backup includes a non-default auto-backup interval but no folder is configured on the current device, the restore result now flags this so you know to set a backup folder.
- **Safer file validation for imports.** Wavdrop backup files and BlackPlayer import files with unrecognised content are rejected earlier with clearer error messages rather than failing partway through a restore.

---

## Beta 3.1 — Widget, Wrapped Visual Refresh & Polish

### Home Screen Widget

- **New home screen widget.** Add Wavdrop to your home screen to see the currently playing track at a glance. Shows album artwork, song title, and artist name.
- **Playback controls on your home screen.** Previous, Play/Pause, and Next buttons work directly from the widget without opening the app.
- **Widget stays in sync with playback.** The widget updates immediately when you skip a track, pause, or resume — matching what you see in the notification and the app.
- **Album artwork in the widget.** When artwork is available, it appears as both the track thumbnail and as a blurred atmospheric background behind the controls.
- **Adapts to different widget sizes.** The widget adjusts its layout when you resize it on your home screen, using the available space to show more or less detail.

### Wrapped Visual Refresh

- **Artwork-backed highlight cards.** Wrapped slides now use your most-played album artwork as dynamic backgrounds, making each year's summary feel personal.
- **Decorative insight cards.** Listening milestones and pattern highlights appear as styled cards throughout your Wrapped summary.
- **Better visual hierarchy.** Improved spacing, typography, and contrast across all Wrapped slides for a cleaner, more polished presentation.

### Wrapped Personalization

- **Artwork backgrounds on or off.** A new setting in Settings → Statistics → Wrapped lets you enable or disable artwork-backed backgrounds across all Wrapped slides.
- **Background intensity control.** Adjust how prominently artwork colours show through the background with a simple intensity slider.
- **Fallback themes.** Choose a fallback visual style for Wrapped slides when no artwork is available.

### Added
- Delete from device on Track Details for Android 11+. A "Delete from device" button appears
  on Track Details for library tracks on Android 11+ (API 30+). Tapping it shows a Wavdrop
  confirmation dialog, then the Android system consent dialog. On approval: the file is removed
  from the device, the track is removed from all playlists, any custom lyrics override is
  deleted, and Track Details navigates back. Stats and listening history are retained. Not shown
  for externally opened audio files. If the deleted song was playing and more songs follow in the
  queue, playback advances to the next song automatically (respecting shuffle order and Repeat
  All); if no next song is available, playback stops cleanly.
- Native Android share action for local audio tracks. Share appears in every song-row overflow menu
  (Songs, Home, Album, Artist, Folder, Smart Collection, Playlist Details, Queue Sheet), in the Now
  Playing overflow, and in Track Details. The system share sheet is opened via `ACTION_SEND` with
  `FLAG_GRANT_READ_URI_PERMISSION`. A "Could not share this track" snackbar is shown if the intent
  cannot start.
- Library & Scanning setting: **Include WhatsApp voice notes** (default OFF). When off, files inside
  WhatsApp Voice Notes and WhatsApp Business Voice Notes folders are excluded from the library,
  keeping them out of Songs, playlists, search, reports, and Wrapped. WhatsApp Audio and
  non-voice-note audio files received through WhatsApp are unaffected.

### Changed
- Polished Privacy Policy, Terms of Use, Disclaimer, Open Source Licenses, and backup wording for Play Store readiness.
- Refreshed all launcher icon variants with a consistent wave-and-play mark.
- Polished playlist drag-to-reorder feedback and affordance: each row now shows a visible drag
  handle — touch and drag the handle to reorder; long-pressing the row body still opens Track
  Details. The dragged song is shown as a floating preview while the source row stays in place as
  a placeholder; peer rows dim while a drag is active. Row interactions are suppressed during drag
  to prevent accidental playback.
- Polished Queue Sheet Up Next drag-to-reorder with handle-based dragging. Up Next rows now show a
  drag handle; touch and drag the handle vertically to reorder. Horizontal swipe-to-remove is
  unaffected. Previously Played and Playing Now rows are not reorderable. Current song and playback
  position are preserved during reorder; the queue commits a single move on drop.
- Fixed swipe/remove red background bleeding through during Queue Sheet vertical reorder drag.
  The entire row (including its swipe background) now moves together; horizontal swipe-to-remove
  is disabled while a reorder drag is active and re-enables immediately after drop.
- Stabilized edge auto-scroll in Queue Sheet and Playlist Details drag-to-reorder with a floating
  preview model: dragging near the top or bottom edge scrolls automatically while the real list
  rows stay stable, preventing recycled rows from desynchronizing the active drag.
- Queue Sheet and Playlist Details drag reorder now commits correctly when list virtualization
  interrupts the gesture mid-scroll. Previously, a long drag that caused the source row to leave
  the LazyColumn composition window would silently discard the move; the item now lands at the last
  tracked ghost position, matching the same guards used for a normal drop.

### Fixed
- Prevented duplicate tracks from being added to the same playlist. Duplicate adds are blocked at
  the repository layer — no duplicate row is ever written. Multi-select add (Add Songs screen) skips
  tracks already in the playlist. Backup/restore was already duplicate-safe and is unchanged.
- Improved duplicate-add feedback across all add-to-playlist surfaces. After every add action,
  song-row overflow menus on all screens (Songs, Home, Album, Artist, Folder, Smart Collection,
  Playlist Details, Now Playing, Track Details) now show "Added to playlist" or "Already in
  playlist". After a multi-select add, Playlist Details shows a contextual snackbar: "Added X
  songs", "Added X songs • Y already in playlist", or "All selected songs are already in this
  playlist".
- Playlists that already contain the selected song are now shown as greyed out and non-selectable
  in the Add to Playlist dialog, labelled "Already added". Songs already in the target playlist
  are shown as greyed and disabled in the Add Songs to Playlist screen, labelled "Already in
  playlist".

---

## Soft Launch Beta 2

### Added
- Home search now uses `SongRowWithOverflow` for a consistent song row experience.
- Play Next action in Home search results.
- Add To Queue action in Home search results.
- Add To Playlist action in Home search results.
- View Folder action in Home search results.

### Changed
- Unified Home and Songs search result experience — both surfaces now share the same song row component and action set.
