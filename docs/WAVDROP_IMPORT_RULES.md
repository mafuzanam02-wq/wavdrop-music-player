# Wavdrop Import Rules

Status: **Active.** Describes import semantics as currently implemented. Format fields live in
`WAVDROP_DATA_FORMAT_SPEC.md`; preservation semantics live in `BACKUP_PRESERVATION_CONTRACT.md`.

## Required Reading

Read these files before changing backup, import, export, or migration behavior:

- `docs/WAVDROP_DATA_FORMAT_SPEC.md` (canonical format)
- `docs/BACKUP_PRESERVATION_CONTRACT.md` (preservation semantics)
- `docs/WAVDROP_BACKUP_SCHEMA_V1.md` (legacy V1 compatibility)
- `docs/WAVDROP_IMPORT_RULES.md` (this file)

## Routing

Import routes by origin and version before any rule is applied:

1. **Desktop origin** (`appName = "wavdrop-desktop-lab"` or `sourcePlatform = "desktop"`): desktop import
   path, regardless of other identity fields.
2. **Android v2** (`format = "wavdrop_backup"`, `version = 2`): v2 parser with mandatory integrity.
3. **Android v1** (`version = 1`, with the V1 `app` field): legacy adapter; `UNVERIFIED_LEGACY` unless a
   payload checksum is present and valid.
4. `version` greater than 2, an unknown required capability, a duplicate JSON key, an integrity or manifest
   mismatch, or implausible statistic magnitudes: **rejected before any database mutation**.

Preview runs first and reports matched, unmatched, ambiguous, and changed records; apply then runs in a
transaction and re-checks for a no-op inside the transaction. Import never modifies audio files.

## Identity Rules

Android v1 backup identity (legacy; v2 backups carry `format`, `version = 2`, `formatMajor` instead of
`app` / `packageName`):

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

If multiple local songs match, treat it as ambiguous and do not guess. A wrong match is worse than an
unresolved song.

What happens to an unresolved song depends on origin:

- **Android-origin (v1/v2):** history for tracks that match no local song (stats, events, lyrics
  overrides, import baselines, playlist entries) is **preserved in the snapshot-scoped pending
  (quarantine) tables**, not discarded. Pending rows are archive data: they do not affect current
  stats, are not rematched to live songs, and are not auto-merged across snapshots. Re-importing the
  same backup does not duplicate pending rows.
- **Desktop-origin (standalone Desktop backup):** unmatched or ambiguous songs, playlist entries, and
  events are **skipped with a warning** (no quarantine).

Android-side matching for Android-origin backups uses the multi-tier `BackupSongLinkResolver`. The
device-local TrackIdentity is not used for matching, is not exported, and no rematching exists.

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

## BlackPlayer `.bpstat` field semantics (BP-1)

A BlackPlayer EX `.bpstat` row has exactly 8 semicolon-separated fields:

```
playCount;periodPlayCount;title;artist;album;filePath;dateAddedMs;lastPlayedMs
```

- **Field 1 (`playCount`)** is the main play count. It is the only counter WavDrop imports (MAX-merged into `playCount`).
- **Field 2 (`periodPlayCount`)** is a PERIOD play count (a secondary play aggregate). It is **NOT a skip count**. It is parsed and shown
  in the preview for inspection only. It is never imported into `skipCount`, never added to `playCount` (the counters are not proven
  additive), never turned into listening time, and never creates events.
- Both numeric fields are play counters and use the play-count plausibility bound; neither uses the skip bound.
- **A `.bpstat` import never changes `skipCount`.** The merge passes 0 skips, so `MAX(local, 0)` leaves the local value untouched:
  never raised, replaced or decremented. Re-importing the same file changes nothing.
- The import baseline records `lastImportedPlayCount = playCount` and `lastImportedSkipCount = 0` (the file has no skip evidence).
  Idempotency comes from MAX semantics, not from the baseline.
- Aggregate import never fabricates listen events (D-17).
- **Historical limitation:** versions before this correction imported field 2 into `skipCount`. Those values are NOT decremented: their
  provenance (native WavDrop skips, older imports, backups/restores) cannot be reconstructed, and subtracting would risk destroying
  legitimate local skip history. The correction is prospective only.

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

Restored/imported listen-event idempotency (eventId-aware):

- If the incoming event has a non-null `eventId`, it is deduplicated against existing local non-null
  `eventId`s and against earlier events in the same batch. `eventId` is carried through to the stored row.
- If the incoming event has no `eventId` (legacy), the legacy identity concept applies:

```text
local Android songId + occurredAt + eventType + listenedMs
```

- Legacy events are never assigned a fabricated `eventId` on import.

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

## Lyrics, Baselines, and Extension Roots

- **Lyrics overrides:** a matched override is applied only when no local override exists or the backup's
  `updatedAt` is newer. Unmatched overrides are quarantined (Android-origin).
- **Import baselines:** restored per matched song so re-importing BlackPlayer stats after a restore never
  inflates counts; unmatched baselines are quarantined. BlackPlayer `.bpstat` import itself is
  delta-based and idempotent through baselines and never writes events.
- **Extension roots:** the `desktopOverlay` root of a verified backup is stored verbatim in
  `pending_backup_extensions` and re-exported by Android; its stats/events/favourites are also applied
  through the Desktop overlay planner where they match local songs. Other unknown roots are ignored
  (general extension preservation is deferred).

## Unsupported / Deferred

- Required capabilities of any kind (rejected), portable song identity or `portableSongKey`, TrackIdentity
  export, rematching of pending history, and encrypted or
  signed backups.
- Desktop portable import of `importBaselines`, `lyricsOverrides`, and `preferences.android` beyond the
  current safe Android-side behavior.
- A shared cross-platform validation library and general unknown-field preservation.
