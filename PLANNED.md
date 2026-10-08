# Wavdrop Planned Work

Product-level view of upcoming work. Nothing in this file is implemented.
When work ships, move it to RELEASE_NOTES.md and remove it here. Detailed engineering slices and
architecture backlog live in [ENGINEERING_BACKLOG_AND_DECISIONS.md](ENGINEERING_BACKLOG_AND_DECISIONS.md);
do not duplicate them here.

---

## In Progress (not user-facing)

- **Crossfade** - architecture in progress; live user-facing feature not yet enabled. There is no
  crossfade setting and nothing audible changes yet.

---

## Approved

### Public Privacy Policy Page — IMPLEMENTED (WPP-1); deployment and live validation pending

Code is ready in both repositories: the in-app policy now carries `WavdropAbout.PRIVACY_POLICY_URL`, and the LaunchPoint Digital site has a prerendered `/wavdrop/privacy` route. NOT yet deployed, so the URL is not live-validated and must not be entered in the Play Console until QA_CHECKLIST "WPP-1" items A–I are verified over HTTPS.

Original requirement: publish the Wavdrop Privacy Policy at `https://launchpointdigital.co.za/wavdrop/privacy`. This URL is required for the Google Play store listing Privacy Policy field before public launch. The page should publish the same text as the in-app Privacy Policy dialog. Once the page is live, add a reference line to the bottom of the in-app Privacy Policy copy pointing to the URL.

---

## Under Evaluation

- **Support domain email**: ~~Migrated~~ — `WavdropAbout.CONTACT_EMAIL` and the privacy policy contact line in `SettingsAboutScreen.kt` now use `info@launchpointdigital.co.za`. Update the Play Store developer contact field to the same address before submission.
- **Additional Delete entry points**: after Track Details Phase 1 is stable and validated,
  evaluate adding "Delete from device" to song-row overflow menus (Songs, Home, Album, Artist,
  Folder, Smart Collection screens). Requires assessment of accidental-deletion risk in
  list-view contexts.
- **Drag auto-scroll/reorder library**: evaluate replacing the current custom drag-to-reorder
  implementation with a stable third-party library if real-device edge cases surface in
  testing. The current implementation is functional but the auto-scroll and
  virtualization-interrupt paths are non-trivial to maintain.
- **Broader folder exclusion system**: extending the per-folder scan exclusion beyond the WhatsApp-specific
  toggle and the shipped preset exclusions (Telegram, Signal, Messenger, Downloads, Recordings; all default
  OFF) to a general block/allow list that users can configure freely. Arbitrary exclusion is NOT supported yet.

---

## Deferred

- Delete from song-row overflow menus (Phase 2, after Phase 1 is stable).
- Delete from Queue Sheet.
- Delete from Playlist Details inline row actions.
- Bulk delete (multi-select delete from device).
- Undo / recycle-bin behavior for deleted files (not feasible: Android provides no recycle bin
  for shared media storage).
- Scrobbling / last.fm integration.
- Android Auto support.
- Lock-screen widgets / additional widget surfaces (a home-screen widget already ships).
- Equalizer expansion beyond the shipped device Equalizer (portable/custom curves; Output Profiles,
  Headphones, and Loudness Protection placeholders in Settings are "Coming later").
- Metadata / ID3 tag editing.
- In-app reset / clear-data (if ever added it must require an export-before-reset prompt).

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
- **Backup/restore regression check**: the Backup & Restore flow (manual export, verification,
  merge restore, automatic WorkManager backup, Desktop import, quarantine behaviour) should be
  retested end to end on a real device before the next distribution; see `QA_CHECKLIST.md`.
