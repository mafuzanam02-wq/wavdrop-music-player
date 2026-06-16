# Wavdrop Backup Schema V1

Version: 1.0

Status: Active

## Android Backup Identity

Android Wavdrop backups must identify themselves with:

```json
{
  "app": "Wavdrop",
  "format": "wavdrop_backup",
  "version": 1,
  "packageName": "com.launchpoint.wavdrop"
}
```

These values are part of the shared data contract and should remain stable for V1 Android backups.

## Desktop Backup Identity

Desktop lab backups appear in two forms.

Legacy desktop-only format:

```json
{
  "schemaVersion": 1,
  "appName": "wavdrop-desktop-lab"
}
```

Shared desktop format (overlays Android identity with desktop signals):

```json
{
  "app": "Wavdrop",
  "format": "wavdrop_backup",
  "version": 1,
  "sourcePlatform": "desktop",
  "appName": "wavdrop-desktop-lab",
  "schemaVersion": 1
}
```

`sourcePlatform: "desktop"` or `appName: "wavdrop-desktop-lab"` is sufficient to identify desktop origin. Android must not treat desktop song IDs as Android song IDs regardless of which identity form is used.

Android accepts Desktop backups with `schemaVersion: 1`. Android rejects Desktop backups
with unsupported explicit `schemaVersion` values, such as `99`, and tolerates absent
`schemaVersion` only for legacy compatibility. This validation hardening did not introduce
a backup schema version bump and did not require a database schema change.

## Android Backup Sections

Android V1 backups may contain:

- backup identity metadata
- exported timestamp/version metadata
- song references
- `trackStats`
- playlists
- listen events
- supported settings under `preferences.android`
- integrity/checksum metadata

Runtime schema details can evolve only when import/export remains compatible or the backup version is intentionally changed.

## Song Records

Song IDs are platform-local. They may be used to connect records inside a single backup, but they are not portable across platforms.

Android song IDs are `Long` values from MediaStore. Desktop song IDs are strings (for example, SHA-1 hashes or UUIDs). Import code must never write desktop string song IDs into Android database tables.

Display metadata must be preserved exactly as stored locally. Do not normalize title, artist, or album strings for display.

## Track Stats

Aggregate stats must be exported from `trackStats`, not from `songs`.

Backups store raw `totalListeningTimeMs` only. They do not store derived `effectiveListeningTimeMs`. Android and Desktop derive effective listening time locally as the larger of stored `totalListeningTimeMs` and estimated `playCount × durationMs` for user-facing display, sorting, reports, and aggregate summaries. Export/import must continue to read, write, and merge the stored `totalListeningTimeMs` field only; the derived value must not be exported, imported, persisted, used to overwrite `totalListeningTimeMs`, or treated as measured playback time.

Stats fields use baseline-safe merge rules on import:

| Field | Import rule |
|---|---|
| `playCount` | `MAX(existing, imported)` |
| `totalListeningTimeMs` | `MAX(existing, imported)` |
| `lastPlayedAt` | latest non-null/latest timestamp |
| `favorite` | OR merge; `true` wins |

Imports must be idempotent. Re-importing the same backup should not increase counts a second time.

## Listening Events

Aggregate backup stats must not fabricate listening events.

Android Monthly Reports, Wrapped, and other event-backed reports should continue to rely on real `TrackListenEventEntity` rows and verified portable Wavdrop playback events.

Imported listen events with impossible numeric values are skipped safely:

- `occurredAt <= 0`
- `listenedMs <= 0`
- `durationMs < 0`

Invalid listen events are not imported, do not crash restore, and do not poison the whole
backup where safe skipping is possible. No synthetic replacement events are created. Valid
listen events still restore normally, and repeat import idempotency remains preserved.

Exportable listen-event sources are:

- `wavdrop_playback`
- `manual_restore`
- `wavdrop_desktop_playback`

Unsupported or synthetic sources remain excluded, including `blackplayer_import` and unknown future sources unless explicitly supported later.

No listen events are synthesized from aggregate stats. Desktop aggregate stats may update aggregate `trackStats`, but they must not create fake event rows.

Restored/imported listen events use this identity concept for idempotency:

```text
local Android songId + occurredAt + eventType + listenedMs
```

Do not dedupe only by song ID; multiple plays of the same song are valid.

## Settings

Only explicitly supported settings should be exported/imported.

Settings are platform-scoped:

```json
{
  "preferences": {
    "android": {
      "startupDestination": "SONGS",
      "searchTapBehavior": "PRESERVE_QUEUE"
    },
    "desktop": {
      "...": "Desktop-only settings"
    }
  }
}
```

Android exports Android settings only under `preferences.android`. Android exports must not include `preferences.desktop`, must not write Android settings directly under `preferences`, and must not write Android settings at the backup root.

Android import reads only `preferences.android`. `preferences.desktop` is ignored on Android. Missing `preferences` and missing `preferences.android` are valid and leave Android settings unchanged. Unknown Android preference keys are ignored. Invalid Android preference values are sanitized or ignored.

Legacy Android backups that stored flat Android settings directly under `preferences` may be parsed for backward compatibility as import-only legacy data. New Android exports must use the platform-scoped shape.

Platform-specific settings must not be blindly imported across platforms. Unknown settings should be ignored safely.

## Playlists (Android Backup)

Android backup playlists reference songs by backup-local `Long` song IDs.

During restore, playlist song references are resolved to current Android song IDs through the multi-tier matcher (`BackupSongLinkResolver`). Empty translated playlists are skipped.

## Desktop Backup Playlists

Desktop backups carry playlists in the following shape:

```json
{
  "id": "9826cd84-02e3-4ae6-ae99-3cb7b12ed2ae",
  "name": "My Playlist",
  "songIds": [
    "ace76dacba47849f27d6b2515e600190ad52f51f",
    "509cbf5fb4b324b3723c230200b240481b04a0ae"
  ],
  "createdAt": "2026-06-13T05:36:17.634Z",
  "updatedAt": "2026-06-13T07:53:14.921Z"
}
```

Key schema points:

- `id` — desktop-local UUID string. Not used as an Android identifier.
- `name` — playlist display name. Used to match or create a local Android playlist.
- `songIds` — ordered array of desktop-local string song IDs. Order reflects the playlist's intended song sequence.
- `createdAt` / `updatedAt` — ISO 8601 strings. Informational; not used to overwrite Android timestamps.

Android import resolves each `songId` entry through the following path:

```
playlist songIds[i]
  → backup.songs[id == songIds[i]]   (look up desktop song metadata)
  → title + artist + album           (metadata fields)
  → Android library song match       (metadata normalization)
  → Android local song ID            (Long)
  → playlist_songs row
```

Unmatched or ambiguous entries are skipped. Playlists with no successfully translated songs are skipped entirely.

## Desktop-Origin Listen Events

Desktop backups may carry verified Desktop playback events in top-level `listenEvents`:

```json
{
  "songId": "desktop-string-song-id",
  "title": "Song title",
  "artist": "Artist",
  "album": "Album",
  "durationMs": 123456,
  "occurredAt": 1780000000000,
  "listenedMs": 60000,
  "eventType": "PLAY",
  "source": "wavdrop_desktop_playback"
}
```

Desktop IDs are platform-local and must not be trusted as Android IDs. Android resolves Desktop listen events to local Android songs through the Desktop backup song mapping and safe metadata fallback. Android does not require Android `contentUri` for Desktop-origin listen events.

Matched Desktop-origin events are stored in `track_listen_events` using local Android song IDs while preserving:

- `occurredAt`
- `eventType`
- `listenedMs`
- `durationMs`
- `source = "wavdrop_desktop_playback"`

Unmatched Desktop-origin listen events are skipped. Desktop-origin events should count in event-backed reports if they have valid `occurredAt` and `listenedMs`.

Desktop-exported backups may also contain portable Android-compatible sections such as `songs`, `playlists`, `listenEvents`, `importBaselines`, `lyricsOverrides`, and platform-scoped `preferences.android` / `preferences.desktop`. Android should accept `sourcePlatform: "desktop"` with supported `schemaVersion: 1`, consume Android-compatible fields, ignore Desktop-only preferences safely, and never modify audio files during backup/import/export.

Import preview/result wording should make clear that Desktop backups may include stats,
favorites, playlists, and listening history. It should also make clear that backup import
does not modify audio files. Backups contain metadata, history, settings, and playlist data
only; they do not contain music files.

## Playlist Import — Conservative Merge Rules

Playlist import is conservative and non-destructive:

- A playlist that already exists by name receives new matched songs appended; it is not replaced.
- Songs already present in the target playlist are not duplicated.
- Existing playlist entries are not deleted or reordered.
- Playlist order is preserved for newly added entries where the full translated set is appended after existing entries.
- Re-importing the same backup is idempotent.

This is playlist portability, not two-way playlist synchronization.

Repeat import must not duplicate Desktop-origin events, inflate `playCount`, inflate raw `totalListeningTimeMs`, duplicate playlist songs, or destabilize `importBaselines` and `lyricsOverrides`.

## Integrity And Compatibility

Import code should validate identity, version, and integrity metadata before applying changes.

Unsupported versions should fail safely. Unknown fields should be ignored only when doing so does not change the meaning of the import.

Validated Android/Desktop portability QA: Desktop exported 732 songs and 1523 listen events, including 14 `wavdrop_desktop_playback` events. Android export after first import had 732 songs and 1525 listen events, including the same 14 Desktop-origin events. Android export after second import remained 732 songs and 1525 listen events with the same 14 Desktop-origin events. `importBaselines` stayed 723, `lyricsOverrides` stayed 28, playlists stayed 3, aggregate play counts/listening time did not inflate, and no backup or database schema change was needed.

## Deferred Beyond Beta 3.1

The following are P2/P3 items, not Beta 3.1 scope:

- Desktop portable import of `importBaselines`, `lyricsOverrides`, and `preferences.android` beyond the current safe Android-side behavior.
- Portable song identity layer or optional `portableSongKey`.
- Partial audio hash or acoustic fingerprinting.
- Backup schema v2.
- Shared cross-platform validation library.
- Unknown future-field preservation architecture.
