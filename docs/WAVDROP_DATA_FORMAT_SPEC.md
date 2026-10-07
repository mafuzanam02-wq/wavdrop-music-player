# Wavdrop Data Format Specification

Canonical shared data interchange contract for Android, Desktop, and future platforms. This is the single
authoritative format document; there is no other copy.

Related: preservation semantics in [BACKUP_PRESERVATION_CONTRACT.md](BACKUP_PRESERVATION_CONTRACT.md);
import behaviour in [WAVDROP_IMPORT_RULES.md](WAVDROP_IMPORT_RULES.md); legacy v1 details in
[WAVDROP_BACKUP_SCHEMA_V1.md](WAVDROP_BACKUP_SCHEMA_V1.md).

Status: **Active.** Describes the currently implemented Android contract. Sections are
labelled *Current* (implemented), *Legacy* (accepted for compatibility), or *Deferred* (not implemented).

## Purpose

Imports and exports are offline-first, local-first, human-readable, versioned, and account-free. No
Wavdrop platform may require network access to import or export user data. Backup, import, and export
never modify audio files, and backups do not contain audio files.

Data ownership: user data belongs to the user. Platforms may export, import, and merge it, and must not lock
it behind accounts, cloud services, or subscriptions, or encrypt it unnecessarily. Supported platforms
today: Wavdrop Android and Wavdrop Desktop (lab); further platforms must follow this contract.

## 1. Format versions

| Version | Status | Notes |
|---|---|---|
| Android backup **v2** (`version: 2`) | **Current** - the only LOGICAL format Android exports | mandatory integrity; opaque ids as strings; packaged as a WDBK container (see section 1a) |
| **WDBK container v1** (`.wdbk`) | **Current** - how Android packages new exports | ZIP/DEFLATE + compact JSON sections; wraps the v2 logical model unchanged |
| Android backup v2 as a bare `.json` | **Legacy** - import only, supported indefinitely | what Android exported before WDBK-1 |
| Android backup **v1** (`version: 1`) | **Legacy** - import only, supported indefinitely | see [WAVDROP_BACKUP_SCHEMA_V1.md](WAVDROP_BACKUP_SCHEMA_V1.md) |
| Desktop lab backups (`schemaVersion: 1`) | **Current** (import) | legacy-desktop and shared-desktop shapes, section 7 |
| Newer major versions | Rejected before parsing/apply | "created by a newer version of Wavdrop" |

## 1a. WDBK container v1 (physical packaging)

Current. A `.wdbk` file is a standard ZIP (DEFLATE) holding the **v2 logical backup** split into compact UTF-8 JSON
sections. It changes packaging, not meaning: a decoded WDBK is the same model, with the same
`WavdropBackupIntegrityV2` fingerprint, that the v2 JSON of the same state decodes to. It contains no audio files. The
container version (`containerMajor 1, containerMinor 0`) is independent of the logical version (`2`); this is not
"Backup V3".

| Entry | Content | Presence |
|---|---|---|
| `sections/songs.json`, `track-stats.json`, `import-baselines.json`, `lyrics-overrides.json`, `playlists.json` | the v2 arrays, element shapes identical to v2 JSON (ids as strings, `lastListenedAt`, `eventId`, ...) | always (`[]` when empty) |
| `sections/preferences.json` | `{"android":{...}}`, as in v2 JSON | present IFF the backup has preferences |
| `history/listen-events-NNNNNN.json` | one bounded JSON array of v2 listen-event objects per chunk (6-digit zero-padded index from 0, contiguous, 1..2 000 events written, up to 10 000 accepted) | one per chunk; none for empty history |
| `extensions/desktop-overlay.json` | the preserved raw `desktopOverlay` root | optional; only when present |
| `manifest.json` | see below | exactly one; written last |

`manifest.json` fields: `format: "wavdrop_wdbk"`, `containerMajor`, `containerMinor`, `logicalFormat: "wavdrop_backup"`,
`logicalVersion: 2`, `backupId`, `sourceInstallationId`, `exportedAt` (epoch ms), `producer`
(`platform`, `appVersionCode`, `appVersionName`), `requiredCapabilities`, `optionalCapabilities`, `counts` (the v2
manifest counts), `integrity` (`v: 2`, `fingerprint` = the v2 semantic fingerprint) and `entries[]`: per payload entry
`path`, `section`, `sectionVersion`, `required`, `byteLength`, `sha256` (of the exact uncompressed bytes) and, for
history chunks, `chunkIndex` and `eventCount`. The manifest does not hash itself.

Rules: a reader verifies every entry's length and SHA-256, requires the physical entry set to equal the declared set,
checks required sections, contiguous chunk indexes and per-chunk/overall counts, recomputes the semantic fingerprint on
the reconstructed model, and applies the same strict v2 parsing and WD-05 plausibility rules as v2 JSON. Any failure
rejects the whole container; no partial model is ever produced. `containerMajor` greater than supported is rejected as a
newer version. Detection is by content (ZIP signature), never by extension or MIME type. Untrusted-input bounds: see
`docs/ARCHITECTURE.md` section 6 (`WdbkLimits`).

Version policy (Android parser):

- `version` must be an integer; `1` -> legacy adapter, `2` -> v2 parser, greater than 2 -> rejected as a
  newer version, anything else -> unsupported.
- v2 requires `formatMajor == 2` and a non-negative `formatMinor`.
- `requiredCapabilities` and `optionalCapabilities` are mandatory arrays in v2. An unknown *required*
  capability rejects the backup before apply. Unknown *optional* capabilities import the supported data
  with a partial-restore warning. The current Android parser knows no capabilities of either kind, so a
  backup declaring any required capability is rejected.

## 2. Android backup identity (v2) - *Current*

```json
{
  "format": "wavdrop_backup",
  "version": 2,
  "formatMajor": 2,
  "formatMinor": 0,
  "backupId": "uuid",
  "sourceInstallationId": "uuid",
  "exportedAt": 1782230400000,
  "producer": { "platform": "android", "appVersionCode": 9, "appVersionName": "0.1.0-beta9" },
  "requiredCapabilities": [],
  "optionalCapabilities": [],
  "manifest": { "songCount": 0, "trackStatsCount": 0, "listenEventCount": 0,
                "importBaselineCount": 0, "lyricsOverrideCount": 0, "playlistCount": 0,
                "preferenceCount": 0 },
  "integrity": { "v": 2, "fingerprint": "..." },
  "songs": [], "trackStats": [], "importBaselines": [], "lyricsOverrides": [],
  "playlists": [], "listenEvents": [],
  "preferences": { "android": {} },
  "desktopOverlay": { }
}
```

- `backupId`, `sourceInstallationId`, `exportedAt` (epoch-ms integer), and `integrity` are required.
- `producer.appVersionCode` / `appVersionName` are informational and not part of the fingerprint.
- `desktopOverlay` is an optional preserved **extension root** (section 9); other fields above are
  required except `preferences`.
- Android v2 backups do **not** carry the v1 `app` / `packageName` identity fields; identity is
  `format` + `version` + `formatMajor`.
- `trackIdentities` and an `extensions` object are **not** part of the current format (*Deferred*).

### Type rules (v2)

- Opaque identifiers (song id, album id, playlist id, stat/baseline/lyrics/event `songId`) are JSON
  **strings**: digits only, no sign, decimal, exponent, or leading zero (except `"0"`).
- Timestamps, durations, and counters are non-negative JSON integers (no exponent notation) within
  bounded, plausible ranges. Imported statistic magnitudes are validated after integrity checks.
- `trackStats[].lastListenedAt` is a required non-negative integer.
- Duplicate JSON object keys are rejected (no last-write-wins); nesting depth is bounded.
- Display metadata (title, artist, album) is preserved as stored; never rewritten for display.

### Integrity and trust - *Current*

`integrity.fingerprint` is computed over the **parsed model**, not raw JSON text (immune to re-encoding),
and is tagged so a v2 fingerprint never collides with a v1 payload checksum. `manifest` counts are
cross-checked against content. Trust levels: `VERIFIED`, `UNVERIFIED_LEGACY` (v1 without checksum),
`INVALID`. The checksum guards against accidental corruption only; it is not tamper-proof.

## 3. Song identity and display metadata

Song ids are platform-local and not portable. Android ids are `Long` MediaStore-derived values (strings in
the v2 JSON); Desktop ids are Desktop-generated strings. Cross-platform imports match songs by metadata and
supporting evidence, never by foreign ids. Desktop string ids are never written into Android tables.
Comparison-only normalization builds matching keys; visible metadata is never mutated.

Android's own import resolves backup songs to local songs with a multi-tier matcher (URI -> path + title ->
tags + duration -> tags-only). **Ambiguous matches are never guessed** (a wrong match is worse than an
unresolved song).

**TrackIdentity (Android device-local identity) is not exported** and plays no role in matching. Portable
identity is *Deferred*.

## 4. Stats source and effective listening time

Aggregate stats come from `trackStats`, not `songs`. Listening history is separate; aggregate imports never
fabricate events.

```text
estimatedListeningTimeMs = playCount x durationMs   (if both > 0, overflow-guarded) else 0
effectiveListeningTimeMs = max(totalListeningTimeMs, estimatedListeningTimeMs)
```

`effectiveListeningTimeMs` is derived locally for display, sorting, reports, and aggregate summaries. It is
never stored, exported, imported, or used to overwrite `totalListeningTimeMs`. Backups carry only raw
`totalListeningTimeMs`. Android and Desktop use the same rule.

## 5. Merge rules (matched songs)

Imports are idempotent and baseline-safe.

| Field | Rule |
|---|---|
| `playCount` | `MAX(existing, imported)` |
| `totalListeningTimeMs` | `MAX(existing, imported)` |
| `lastPlayedAt` / `lastListenedAt` | latest timestamp |
| `favorite` / `isFavorite` | OR merge; `true` wins |
| lyrics override | latest `updatedAt` wins |

Aggregate counts are never added to existing counts.

## 6. Listening events - *Current*

Events represent real playback on a local platform, or verified portable Wavdrop events restored from
another platform. Aggregate stats never fabricate events.

Android event shape (v2): `songId` (string), `contentUri`, `title`, `artist`, `album`, `eventType`
(`PLAY`/`SKIP`), `occurredAt`, `listenedMs`, `durationMs`, `source`, and optional `eventId`.

- **`eventId`** is generated when an event is created (never during export). New events carry one; legacy
  events have none and are **never backfilled**. It is serialized when present and covered by the
  integrity fingerprint.
- **Idempotency / dedup:** when an incoming event has an `eventId`, it is deduplicated by that id against
  existing events and earlier events in the same batch; events without an `eventId` use the legacy
  identity `local songId + occurredAt + eventType + listenedMs`. Never dedupe by song id alone.
- **Invalid events** (`occurredAt <= 0`, `listenedMs <= 0`, `durationMs < 0`) are skipped without failing
  the restore; no synthetic replacement events are created.
- **Exportable sources:** `wavdrop_playback`, `manual_restore`, `wavdrop_desktop_playback`. Synthetic or
  unknown sources (for example `blackplayer_import`) are excluded from export.
- Repeat imports must not duplicate events, inflate `playCount` or raw `totalListeningTimeMs`, duplicate
  playlist songs, or destabilize `importBaselines` / `lyricsOverrides`.

## 7. Desktop interoperability - *Current (import)*

Desktop lab backups appear in two forms.

Legacy desktop-only: `{ "appName": "wavdrop-desktop-lab", "schemaVersion": 1, ... }`.

Shared desktop (Android identity fields plus desktop signals): `{ "app": "Wavdrop", "format":
"wavdrop_backup", "version": 1, "sourcePlatform": "desktop", "appName": "wavdrop-desktop-lab",
"schemaVersion": 1, ... }`.

- Import routing detects the source platform **before** applying rules: `sourcePlatform = "desktop"` or
  `appName = "wavdrop-desktop-lab"` means desktop origin, regardless of other identity fields.
- Android accepts Desktop `schemaVersion: 1`, rejects other explicit values (for example `99`), and
  tolerates an absent `schemaVersion` only for legacy compatibility.
- Desktop playlists use `songIds: string[]`, resolved through the backup's `songs` array by metadata to
  local Android ids. Desktop events (`source = "wavdrop_desktop_playback"`) are resolved the same way and
  stored with local Android song ids, preserving `occurredAt`, `eventType`, `listenedMs`, `durationMs`,
  and `source`; Android does not require `contentUri` for them.
- **Unmatched or ambiguous Desktop-origin songs, playlist entries, and events are skipped**, not
  quarantined (the quarantine/pending system applies to Android-origin backups). Playlists with no
  translated songs are skipped.
- Desktop-only preferences are ignored. Desktop song id generation remains Desktop-owned.

Validated portability QA (historical): a Desktop backup with 732 songs / 1523 events (14
`wavdrop_desktop_playback`) imported into Android re-exported as 732 songs / 1525 events with the same 14
Desktop events; a second import was a no-op for events, baselines, lyrics, playlists, and aggregates.

## 8. Settings - *Current*

Settings are platform-scoped: `{"preferences": {"android": {...}, "desktop": {...}}}`.

- Android exports only `preferences.android` and never writes Android settings at the root or directly
  under `preferences`. Android imports only `preferences.android`; `preferences.desktop` is ignored.
- Missing `preferences` / `preferences.android` is valid (settings unchanged). Unknown keys are ignored;
  invalid values are sanitized or ignored.
- Legacy flat settings directly under `preferences` are accepted as **import-only** backward
  compatibility.
- Only supported keys are exported (theme, accent, launcher icon, startup, library scan, resume/Bluetooth
  behaviour, Now Playing display, Wrapped appearance, backup interval/mode, and similar). Equalizer
  settings are not exported (*Deferred*).

## 9. Playlists and extension preservation

Playlist song references are translated to local song ids on import; foreign ids are never stored in
Android tables. Playlist import is conservative and non-destructive: existing entries are not deleted or
reordered; matched new songs are appended without duplicates; re-import is idempotent. This is playlist
portability, not two-way synchronization.

**Extension roots - *Current, narrow.*** The root object `desktopOverlay` is stored verbatim in
`pending_backup_extensions` on import and written back on the next Android export, so newer portable
Desktop data survives an Android round trip even though Android does not interpret every nested field.
General unknown-field preservation is *Deferred*.

**Pending (quarantine) data - *Current.*** For Android-origin backups, backup history that matches no
local song is retained in snapshot-scoped pending tables rather than discarded (semantics in the
preservation contract). Pending rows are not exported as a separate section and are not rematched.

## 10. Compatibility

- Importers reject unsupported schema versions clearly and ignore unknown forward-compatible fields only
  when doing so does not change the meaning of the import.
- All imports report matched, skipped, ambiguous, and changed records before and after applying.
- Validation failures fail safely before any database mutation; apply runs inside a transaction.

## 11. Deferred

- Portable song identity / `portableSongKey`, identity export, and rematching.
- A shared cross-platform validation library.
- General unknown-field / extension preservation beyond `desktopOverlay`.
- Partial audio hashing or acoustic fingerprinting.
- Desktop-side portable import of `importBaselines`, `lyricsOverrides`, and `preferences.android` beyond
  current safe behavior.
- Optional encrypted or signed backups.
