# Wavdrop Music Player — Play Store Listing Draft

Working draft for the Google Play store listing. All claims must remain
accurate to the current shipped feature set. Do not add features that are
not yet implemented. Update this file whenever shipped features change the
value proposition.

---

## Short Description

**Google Play limit: 80 characters.**

```
Play your local music, offline. No account, no ads, no cloud.
```

Character count: 61 ✅

Alternate options:
```
Offline music player. No account, no ads, no tracking.
```
(54 chars)

```
Local music player with stats, playlists, and Wrapped. No cloud.
```
(64 chars)

---

## Full Description

**Google Play limit: 4000 characters.**

---

Wavdrop is a local music player for Android. It plays audio files stored
on your device — no account required, no subscription, no cloud upload,
no ads.

**Your library stays on your device.**
Wavdrop reads audio from your device storage using Android's standard media
access. Your listening statistics, playlists, lyrics, and preferences are
stored locally and never transmitted anywhere. Wavdrop has no internet
access.

**Browse your music your way.**
Navigate by songs, albums, artists, and folders. Create and manage playlists
with full drag-to-reorder support. Explore eight smart collections —
Favorites, Most Played, Recently Played, Never Played, Recently Added, Most
Skipped, Long Tracks, and Short Tracks — built automatically from your
listening history.

**Detailed listening insights.**
Track play counts, skip counts, listening time, and favorites per song.
Open the Statistics Dashboard for an all-time overview, Monthly Reports for
calendar-month breakdowns, Wrapped for yearly highlights, and Listening
Reports for top songs, artists, and albums across your full history.

**Rich playback features.**
· Full queue management — Play Next, Add to Queue, reorder, and remove
· Shuffle and repeat (Off, Repeat One, Repeat All)
· Sleep timer — 15, 30, 45, 60 minutes, or end of current song
· Bluetooth and wired headphone auto-resume controls
· Swipe album art to skip, double-tap for lyrics, long-press for details
· Media notification controls and lock-screen playback

**Lyrics support.**
Display embedded lyrics (ID3 USLT/SYLT), same-folder .lrc sidecar files,
and .txt sidecar files. Add or edit your own plain-text lyrics for any track
inside the app.

**Migration and backup.**
· Import listening stats from BlackPlayer EX .bpstat files
· Export a full local backup covering stats, playlists, lyrics, preferences,
  and listening history as a local JSON file
· Restore a Wavdrop backup on the same or a new device
· Share audio tracks with any app via Android's native share sheet

**Privacy by design.**
Wavdrop does not collect data, does not use analytics or advertising SDKs,
and makes no network requests. Your audio, statistics, and playlists stay on
your device unless you choose to export or share them. No tracking. No ads.

**Customisation.**
Choose from six launcher icons. Select from multiple accent colours. Enable
compact mode for denser song lists. Set your startup screen. Control which
folders are scanned and which are excluded.

Requires Android 8.0 (API 26) or higher. Audio format support depends
partly on your device's media codecs. MP3, AAC/M4A, FLAC, OGG Vorbis,
Opus, and WAV are reliably supported on most devices.

---

**Character count: approximately 2100** ✅ (well within 4000 limit)

---

## Key Features List

For display in promotional copy or feature bullets on the listing:

- Offline playback — works without internet access
- Browse by songs, albums, artists, folders
- Playlists with drag-to-reorder
- Smart collections (Favorites, Most Played, Never Played, and more)
- Statistics Dashboard and Listening Reports
- Monthly Reports — activity grouped by calendar month
- Wrapped — yearly listening highlights
- Per-song play counts, skip counts, and listening time
- Lyrics: embedded, sidecar .lrc/.txt, and custom editable
- Queue management: Play Next, Add to Queue, reorder
- Sleep timer
- Bluetooth and wired headphone auto-resume
- Swipe album art to navigate tracks
- Share tracks via Android native share sheet
- Delete from device (Android 11+, requires system confirmation)
- BlackPlayer EX .bpstat import
- Full backup export and restore (JSON)
- Six launcher icon variants (user-selectable)
- Accent colour and compact mode
- No account, no ads, no cloud, no tracking

---

## Privacy / Local-First Paragraph

For use in promotional descriptions, landing pages, or social copy:

> Wavdrop is offline-first by design. It does not require an account, does
> not upload your music or data to any server, and does not sell your
> information. Your library, playlists, listening history, and preferences
> stay on your device. The only time data leaves your device is when you
> choose to export a backup or share a track — and even then, Wavdrop is
> not the destination.

---

## Suggested Tags / Keywords

Google Play allows keyword optimisation through the description text rather
than a separate keywords field. Include these terms naturally in the
description:

**Primary:**
- offline music player
- local music player
- music player no ads
- mp3 player
- flac player
- music player without account

**Secondary:**
- audio player
- playlist manager
- listening stats
- music wrapped
- music statistics
- blackplayer import
- local audio player
- android music player

**Long-tail / niche:**
- music player no cloud
- music player no internet
- offline flac player
- music player listening history
- local music with stats

---

## Screenshot Checklist

Capture on a clean real device (not emulator) with a realistic library.
Suggested order for Play Store listing:

| # | Screen | What to show |
|---|---|---|
| 1 | Now Playing | Full artwork, track name, controls; shows the core playback experience |
| 2 | Home dashboard | Continue Listening, section cards; shows the dashboard value |
| 3 | All Songs list | Populated list with artwork; shows library scan working |
| 4 | Statistics Dashboard or Listening Report | Play counts, top songs; shows analytics value |
| 5 | Playlists or Smart Collections | User-created playlists or smart collection list |
| 6 | Monthly Reports or Wrapped | Calendar/year view; shows the unique reports angle |
| 7 | Albums or Artists browse | Browse capability; shows library depth |
| 8 | Settings → About or Legal | Shows the transparent privacy-first branding |

**Phone screenshot spec:** 1080 × 1920 px minimum (portrait), JPEG or PNG.
**Tablet screenshot spec (optional):** 1600 × 2560 px (portrait).

---

## What Not to Claim

Do not include any of the following in the listing unless the feature is
fully shipped:

- Cloud sync or backup to Wavdrop servers
- Online accounts or user profiles
- Streaming music
- AI recommendations or playlist generation
- Lyrics fetching from the internet
- Last.fm or scrobbling integration
- Android Auto support
- Lock-screen or home-screen widgets
- Tag / ID3 metadata editing
- Equalizer

---

## Listing Maintenance Notes

- Update `versionName` in the description after each release if it appears
- Update the feature list when new features ship (check `RELEASE_NOTES.md`)
- Re-verify privacy claims after any networking or analytics library is added
- Re-verify the Data Safety form after any permission or data-flow change
- Update the Privacy Policy URL reference once the web page is live
