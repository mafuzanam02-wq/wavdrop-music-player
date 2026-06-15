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

Imports merge stored `totalListeningTimeMs` only. Derived `effectiveListeningTimeMs` is calculated after import for display, sorting, reports, and aggregate summaries:

```text
if totalListeningTimeMs > 0:
    use totalListeningTimeMs
else if playCount > 0 and durationMs > 0:
    use playCount × durationMs
else:
    use 0
```

Actual stored listening time always wins. The estimate is only a fallback when stored listening time is zero or missing but play count and duration are available. Imports must not read, write, export, or persist an `effectiveListeningTimeMs` field, and must not use it to overwrite `totalListeningTimeMs`.

## Listening Events

Do not fabricate listening events from aggregate stats.

Imported desktop aggregate stats may update Android aggregate stats, but they must not create `TrackListenEventEntity` rows. Monthly Reports and Wrapped remain event-backed unless a future verified event import contract is added.

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
