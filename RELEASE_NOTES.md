# Wavdrop Release Notes

Historical record of completed and shipped work. Newest changes first.

---

## Unreleased

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
