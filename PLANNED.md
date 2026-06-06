# Wavdrop Planned Work

Source of truth for upcoming work. Nothing in this file is implemented.
When work ships, move it to RELEASE_NOTES.md and remove it here.

---

## Approved

### Future Scan Exclusions

Telegram, Signal, Messenger, Downloads, and Recordings folders (not yet scoped or prioritised).

---

## Under Evaluation

- **Additional Delete entry points**: after Track Details Phase 1 is stable and validated,
  evaluate adding "Delete from device" to song-row overflow menus (Songs, Home, Album, Artist,
  Folder, Smart Collection screens). Requires assessment of accidental-deletion risk in
  list-view contexts.
- **Drag auto-scroll/reorder library**: evaluate replacing the current custom drag-to-reorder
  implementation with a stable third-party library if real-device edge cases surface after
  Beta 3 distribution. The current implementation is functional but the auto-scroll and
  virtualization-interrupt paths are non-trivial to maintain.
- **Broader folder exclusion system**: extending the per-folder scan exclusion beyond the current
  WhatsApp-specific toggle and the planned scan-exclusion folder list to a general block/allow
  list that users can configure freely.

---

## Deferred

- Delete from song-row overflow menus (Phase 2, after Phase 1 is stable).
- Delete from Queue Sheet.
- Delete from Playlist Details inline row actions.
- Bulk delete (multi-select delete from device).
- Undo / recycle-bin behavior for deleted files (not feasible: Android provides no recycle bin
  for shared media storage).
- Equalizer.
- Scrobbling / last.fm integration.
- Android Auto support.
- Home screen or lock screen widgets.
- Metadata / ID3 tag editing.

---

## Rejected

- Silent deletion (no confirmation before removing a file from device).
- Skipping the Wavdrop pre-confirmation dialog before `MediaStore.createDeleteRequest`.
- Deleting externally opened audio files (opened via `ACTION_VIEW`; not part of the Wavdrop library).
- Wiping `track_stats` or `track_listen_events` when the user deletes a track from device.
- Streaming features.
- Cloud-first music playback.
- User accounts / social / shared listening features.
- AI recommendation or playlist-generation systems.

---

## Known Issues / Open Questions

- **Queue/playlist drag reorder — virtualization-interrupt commit**: the commit fix lands the
  dragged item at the last tracked ghost position when the source row leaves the composition
  window, but this path needs broader real-device validation across screen sizes and Android
  versions before it can be considered fully stable.
- **Native Share action**: needs validation across WhatsApp, Gmail, Google Drive, Bluetooth,
  Nearby Share / Quick Share, and OEM-customized share sheets (Samsung OneUI, Xiaomi MIUI,
  etc.) to confirm the `audio/*` MIME type and `FLAG_GRANT_READ_URI_PERMISSION` combination
  behaves correctly across apps and Android versions.
- **Delete from device Phase 1**: implemented on Track Details for Android 11+. Needs real-device
  QA: delete non-playing track, delete currently playing track, cancel at each confirmation stage,
  verify playlist/lyrics cleanup, verify stats are retained.
- **Bluetooth / wired headphone resume**: auto-resume on device connect needs real-device
  validation across a broader range of headphone models, Bluetooth speakers, and car audio
  systems. Behavior depends on Android version and OEM audio-focus handling.
- **Launcher icon switching**: live icon switching via activity-alias works correctly on tested
  devices but launcher caching delays still vary. Some launchers (Nova, Action Launcher, etc.)
  may cache the old icon for minutes to hours after the switch.
- **Notification shuffle/repeat controls**: media notification action button visibility and
  behaviour is determined by Android's media session UI and the OEM notification shade — not
  directly controllable by the app. Exact appearance varies by device and Android version.
- **Backup/restore regression check**: the Backup & Restore flow should be fully retested before
  Beta 3 distribution. Several user-facing systems changed since the last backup validation:
  playlist duplicate prevention, duplicate-add feedback, greyed-out Add to Playlist dialog
  entries, and the share action. None of these affect the backup format, but end-to-end testing
  is warranted.
