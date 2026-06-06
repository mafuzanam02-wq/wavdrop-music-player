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
  Details. The dragged row highlights with the `primaryContainer` surface and its handle tints
  primary; peer rows dim while a drag is active. Row interactions are suppressed during drag to
  prevent accidental playback.

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
