# Wavdrop Import Rules

Version: 1.0

Status: Active

## Required Reading

Read these files before changing backup, import, export, or migration behavior:

- `docs/WAVDROP_DATA_FORMAT_SPEC.md`
- `docs/WAVDROP_BACKUP_SCHEMA_V1.md`
- `docs/WAVDROP_IMPORT_RULES.md`

## Identity Rules

Android backup identity:

- `app = "Wavdrop"`
- `format = "wavdrop_backup"`
- `version = 1`
- `packageName = "com.launchpoint.wavdrop"`

Desktop backup identity — either of the following signals desktop origin:

- `appName = "wavdrop-desktop-lab"` (legacy and shared formats)
- `sourcePlatform = "desktop"` (shared format)

The shared desktop format combines Android identity fields with desktop signals:

```json
{
  "app": "Wavdrop",
  "format": "wavdrop_backup",
  "version": 1,
  "sourcePlatform": "desktop",
  "appName": "wavdrop-desktop-lab"
}
```

When `appName` or `sourcePlatform` indicates desktop origin, route to the desktop import path regardless of whether Android identity fields are present. Song IDs are platform-local and not portable. Android must not use desktop string IDs as Android song IDs.

Desktop-exported backups may contain portable Android-compatible sections such as `songs`, `playlists`, `listenEvents`, `importBaselines`, `lyricsOverrides`, and platform-scoped preferences. Android should accept supported `schemaVersion: 1`, consume Android-compatible fields, ignore Desktop-only preferences safely, and never modify audio files during backup/import/export.

Android accepts Desktop backups with `schemaVersion: 1`. Android rejects Desktop backups
with unsupported explicit `schemaVersion` values, such as `99`, and tolerates absent
`schemaVersion` only for legacy compatibility. This is parser validation only; it did not
introduce a backup schema version bump or database schema change.

## Matching Rules

Cross-platform imports must match songs by metadata, not foreign IDs.

Use comparison-only normalization for matching. Preserve original display metadata and do not mutate local title, artist, or album values.

Recommended matching evidence:

- normalized title
- normalized artist
- normalized album when available
- duration or folder evidence when available

If no local song matches an imported song, skip it with a warning. If multiple local songs match, treat it as ambiguous and skip it. Do not guess.

## Aggregate Stats Merge Rules

Stats imports use max/baseline-safe merge, not additive merge.

| Field | Rule |
|---|---|
| `playCount` | `MAX(existing, imported)` |
| `totalListeningTimeMs` | `MAX(existing, imported)` |
| `lastPlayedAt` | latest non-null/latest timestamp |
| `favorite` | OR merge; `true` wins |

Imports must be idempotent. Re-importing the same file should not inflate stats.

Imports merge stored `totalListeningTimeMs` only. Derived `effectiveListeningTimeMs` is calculated after import for user-facing display, sorting, reports, and aggregate summaries:

```text
estimatedListeningTimeMs =
    if playCount > 0 and durationMs > 0:
        playCount × durationMs, with overflow guard
    else:
        0

effectiveListeningTimeMs =
    max(totalListeningTimeMs, estimatedListeningTimeMs)
```

`effectiveListeningTimeMs` is the larger of stored actual/measured time and estimated time. Imports must not read, write, export, or persist an `effectiveListeningTimeMs` field, and must not use it to overwrite `totalListeningTimeMs`. No listen events are synthesized from aggregate stats.

## Listening Events

Do not fabricate listening events from aggregate stats.

Imported Desktop aggregate stats may update Android aggregate stats, but they must not create synthetic `TrackListenEventEntity` rows. Valid Desktop-origin playback events are different: they are verified event rows exported by Wavdrop Desktop and may be restored when they can be matched safely to local Android songs.

Desktop-origin listen event shape:

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

Desktop IDs are platform-local and must not be trusted as Android IDs. Android resolves Desktop listen events to local Android songs through the Desktop backup song mapping and safe metadata fallback. Android does not require Android `contentUri` for Desktop-origin listen events. Matched events are stored in `track_listen_events` using local Android song IDs while preserving `occurredAt`, `eventType`, `listenedMs`, `durationMs`, and `source = "wavdrop_desktop_playback"`. Unmatched Desktop-origin listen events are skipped.

Imported listen events with impossible numeric values are skipped safely:

- `occurredAt <= 0`
- `listenedMs <= 0`
- `durationMs < 0`

Invalid listen events are not imported, do not crash restore, and do not poison the whole
backup where safe skipping is possible. No synthetic replacement events are created. Valid
listen events still restore normally, and repeat import idempotency remains preserved.

Exportable listen-event sources are `wavdrop_playback`, `manual_restore`, and `wavdrop_desktop_playback`. Unsupported or synthetic sources remain excluded, including `blackplayer_import` and unknown future sources unless explicitly supported later.

Restored/imported listen-event idempotency uses this identity concept:

```text
local Android songId + occurredAt + eventType + listenedMs
```

Do not dedupe only by song ID; multiple plays of the same song are valid. Repeat import of the same Desktop backup must not duplicate events, inflate `playCount`, inflate raw `totalListeningTimeMs`, duplicate playlist songs, or destabilize `importBaselines` and `lyricsOverrides`.

Monthly Reports, Wrapped, and other event-backed reports should use real listen-event `listenedMs` where applicable. Desktop-origin events should count in event-backed reports if they have valid `occurredAt` and `listenedMs`.

## Settings

Platform-specific settings must not be blindly imported.

Settings are platform-scoped under `preferences.android` and `preferences.desktop`.

Android import rules:

- Import only `preferences.android`.
- Ignore `preferences.desktop` completely.
- Treat missing `preferences` as valid.
- Treat missing `preferences.android` as valid.
- Ignore unknown Android preference keys.
- Sanitize or ignore invalid Android preference values.
- Do not import Desktop UI preferences into Android.
- Legacy flat Android settings under `preferences` may be accepted as import-only backward compatibility.

Android export rules:

- Export Android settings under `preferences.android`.
- Do not export `preferences.desktop`.
- Do not write Android settings directly under `preferences`.
- Do not write Android settings at the backup root.

Import only settings that Android explicitly supports and that are safe for the source platform. Unknown or unsupported settings should be ignored safely.

## Playlists

Playlist song references must be translated into local song IDs. Do not import foreign song IDs into Android playlist tables.

### Android-Origin Playlists

Android backup playlist entries reference songs by backup-local `Long` song IDs. During restore, these are resolved to current Android song IDs through the multi-tier `BackupSongLinkResolver` (URI → path+title → tags+duration → tags-only). Empty translated playlists are skipped.

### Desktop-Origin Playlists

Desktop backup playlist entries use `songIds: string[]` where each value is a desktop-local string song ID referencing a song in the same backup's `songs` array.

Resolution path for each `songIds` entry:

```
playlist songIds[i]
  → backup.songs[id == songIds[i]]   (desktop song metadata)
  → title + artist + album           (matching fields)
  → Android library song             (metadata normalization)
  → Android local song ID (Long)
  → playlist_songs row
```

Ambiguous or unmatched entries are skipped. Playlists with no successfully translated songs are skipped entirely.

## Playlist Import Phase 1 — Conservative Merge

Android playlist import is conservative and non-destructive. The following behaviors define Phase 1:

**What import does:**

- Matches an existing local playlist by name (case-insensitive).
- Creates a new playlist if no local playlist with that name exists.
- Appends newly matched songs to the end of the playlist.
- Preserves the order of newly imported song entries as they appear in the backup.
- Skips song entries that are already present in the target playlist (no duplicates).
- Skips song entries that cannot be confidently matched (unmatched or ambiguous).
- Skips playlists that produce no matched songs after translation.
- Is idempotent: re-importing the same backup produces no additional changes.

**What import does not do:**

- Does not delete existing local playlist entries.
- Does not reorder entries already present in the target playlist.
- Does not guarantee exact replication of the source playlist state for playlists that already had local entries.
- Does not synchronize playlist deletions or renames across platforms.

This is playlist portability, not two-way playlist synchronization.

## Failure Behavior

Import preview/apply flows should make skipped records visible:

- matched songs
- skipped missing songs
- skipped ambiguous songs
- stats that will change
- favorites that will be applied
- playlists that will be imported or skipped
- playlist entries matched, skipped unmatched, and skipped as duplicates

Validation failures should fail safely before any database mutation. Apply operations should use transactions where practical.

Import preview should make clear that Desktop backups may include stats, favorites,
playlists, and listening history. Preview/result wording should also make clear that
backup import does not modify audio files. Backups contain metadata, history, settings,
and playlist data only; they do not contain music files.

## Validated Android/Desktop QA

Validated portability loop:

1. Desktop exported a backup containing Desktop-origin `wavdrop_desktop_playback` listen events.
2. Android imported the Desktop backup.
3. Android exported a backup.
4. Android imported the same Desktop backup again.
5. Android exported again.

Result: `wavdrop_desktop_playback` events survived Android import/export, the second import did not duplicate Desktop-origin events, total `listenEvents` stayed stable after repeat import, `importBaselines` stayed stable, `lyricsOverrides` stayed stable, playlists stayed stable, aggregate play counts/listening time did not inflate, and no backup or database schema change was needed.

Real QA counts: source Desktop backup had 732 songs and 1523 listen events, including 14 `wavdrop_desktop_playback` events. Android export after first import had 732 songs and 1525 listen events, including the same 14 Desktop-origin events. Android export after second import remained 732 songs and 1525 listen events with the same 14 Desktop-origin events. `importBaselines` stayed 723, `lyricsOverrides` stayed 28, playlists stayed 3.

## Deferred Beyond Beta 3.1

The following are P2/P3 items, not Beta 3.1 scope:

- Desktop portable import of `importBaselines`, `lyricsOverrides`, and `preferences.android` beyond the current safe Android-side behavior.
- Portable song identity layer or optional `portableSongKey`.
- Partial audio hash or acoustic fingerprinting.
- Backup schema v2.
- Shared cross-platform validation library.
- Unknown future-field preservation architecture.
