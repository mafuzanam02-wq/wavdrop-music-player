\# Wavdrop Music Player — v0.9.0 Soft Launch



\## Release status



This is the first soft-launch snapshot for Wavdrop Music Player.



Wavdrop is an offline-first Android music player focused on local music libraries, reliable playback, accurate listening statistics, BlackPlayer migration, and user-owned backup data.



This release is intended for real-device testing and early feedback before wider Play Store preparation.



\---



\## Highlights



\### Playback reliability



\* Improved Bluetooth auto-resume reliability.

\* Improved wired headphone auto-resume reliability.

\* Added clearer resume behavior modes:



&#x20; \* Off

&#x20; \* Resume if interrupted

&#x20; \* Always resume

\* Improved cold-start resume behavior when Bluetooth or wired audio reconnects.

\* Added background playback reliability guidance for devices that restrict background activity.

\* Improved Now Playing layout responsiveness on small and larger phones.



\### Backup and restore



\* Improved Wavdrop JSON backup and restore reliability.

\* Added backup preference restore support.

\* Improved folder-based backup behavior to reduce duplicate backup files.

\* Added auto-backup support using a selected folder.

\* Improved backup overwrite behavior when using a fixed filename.

\* Added restore guidance when automatic backup settings are restored on a new device.



\### BlackPlayer migration



\* Improved BlackPlayer `.bpstat` import reliability.

\* Fixed repeated import behavior so importing the same stats source more than once does not inflate listening statistics.

\* Improved import wording to better explain stats reconciliation.



\### Listening statistics and reports



\* Improved preservation of play counts, skip counts, and listening time during import/restore.

\* Continued support for listening reports, Wrapped-style summaries, artist insights, and track details.



\### Search and library navigation



\* Improved search with grouped results for songs, artists, and albums.

\* Added long-press song actions across supported song lists.

\* Continued support for albums, artists, folders, playlists, smart collections, and alphabet fast scrolling.



\### Settings and personalization



\* Polished Settings organization and wording.

\* Added safer display personalization options.

\* Added Now Playing time display options:



&#x20; \* Elapsed / Duration

&#x20; \* Elapsed / Remaining

\* Added appearance-related display settings for artwork and song list presentation.

\* Improved settings row styling and visual consistency.



\### Release-readiness polish



\* Updated in-app changelog.

\* Improved legal/about/diagnostics wording.

\* Added Play Store readiness documentation.

\* Added soft-launch QA checklist.

\* Created GitHub release snapshot tag.



\---



\## Known limitations



\* Auto-resume after the app is swiped away from Recent Apps may still be blocked by Android or device manufacturer restrictions.

\* Battery optimization exemption can improve background playback reliability, but it cannot guarantee behavior on every device.

\* The home-screen widget code exists in the project but is dormant for this soft-launch release and is planned for a future update.

\* This is still a soft-launch build and should be tested on multiple real devices before wider release.



\---



\## Recommended testing focus



Testers should focus on:



\* Bluetooth disconnect/reconnect behavior.

\* Wired headphone unplug/replug behavior.

\* Playback after phone lock/unlock.

\* Backup Now and auto-backup behavior.

\* Restoring the same backup more than once.

\* Importing the same BlackPlayer `.bpstat` more than once.

\* Search and long-press song actions.

\* Now Playing layout on small and large screens.

\* Settings and display personalization.

\* General playback stability during daily use.



\---



\## Version



\* Version name: `0.1.0`

\* Version code: `1`

\* Git tag: `v0.9.0-soft-launch`



