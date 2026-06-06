# Wavdrop Release Notes

Historical record of completed and shipped work. Newest changes first.

---

## Unreleased

### Added
- Library & Scanning setting: **Include WhatsApp voice notes** (default OFF). When off, files inside
  WhatsApp Voice Notes and WhatsApp Business Voice Notes folders are excluded from the library,
  keeping them out of Songs, playlists, search, reports, and Wrapped. WhatsApp Audio and
  non-voice-note audio files received through WhatsApp are unaffected.

### Changed
- Polished playlist drag-to-reorder feedback and affordance: each row now shows a visible drag
  handle — touch and drag the handle to reorder; long-pressing the row body still opens Track
  Details. The dragged song is shown as a floating preview while the source row stays in place as
  a placeholder; peer rows dim while a drag is active. Row interactions are suppressed during drag
  to prevent accidental playback.
- Polished Now Playing Queue Sheet drag-to-reorder with handle-based dragging. Up Next rows now
  show a drag handle; touch and drag the handle vertically to reorder. Horizontal swipe-to-remove
  is unaffected. Previously Played and Playing Now rows are not reorderable. Current song and
  playback position are preserved during reorder; the queue commits a single move on drop.
- Fixed swipe/remove red background bleeding through during Queue Sheet vertical reorder drag.
  The entire row (including its swipe background) now moves together; horizontal swipe-to-remove
  is disabled while a reorder drag is active and re-enables immediately after drop.
- Stabilized edge auto-scroll in Queue Sheet and Playlist Details drag-to-reorder with a floating
  preview model: dragging near the top or bottom edge scrolls automatically while the real list
  rows stay stable, preventing recycled rows from desynchronizing the active drag.
- Drag reorder in Queue Sheet and Playlist Details now commits correctly when list virtualization
  interrupts the gesture mid-scroll. Previously, a long drag that caused the source row to leave
  the LazyColumn composition window would silently discard the move; the item now lands at the last
  tracked ghost position, matching the same guards used for a normal drop.

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
