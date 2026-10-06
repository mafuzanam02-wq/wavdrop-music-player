# Wavdrop Backup Preservation Contract

Scope: Wavdrop Android backup, restore, migration, and preservation semantics (and the semantics Desktop
interoperability must respect).

This document defines **preservation semantics**: what must never be lost, what a wrong match costs, and
which guarantees the system makes. The **interchange format** is defined in
[WAVDROP_DATA_FORMAT_SPEC.md](WAVDROP_DATA_FORMAT_SPEC.md); import behaviour in
[WAVDROP_IMPORT_RULES.md](WAVDROP_IMPORT_RULES.md); legacy v1 in
[WAVDROP_BACKUP_SCHEMA_V1.md](WAVDROP_BACKUP_SCHEMA_V1.md). Section numbers are stable and are referenced
from the technical-debt register.

## Implementation status legend

Each major area is labelled against the implementation reconciled after CF-2C3:

- **Implemented** - behaviour exists in code and is covered by tests.
- **Partially implemented** - a foundation exists; the contract's full behaviour does not.
- **Deferred** - not implemented; recorded as future work (see
  [../ENGINEERING_BACKLOG_AND_DECISIONS.md](../ENGINEERING_BACKLOG_AND_DECISIONS.md) section 6).

Overall: the contract started as a proposed architecture baseline. The preservation foundation (pending
quarantine, backup format v2, integrity, eventId, device-local TrackIdentity, extension-root
preservation) and explicit Recovery Restore with a mandatory verified safety snapshot are implemented and physically
validated. Portable identity, rematching, GHOST/ARCHIVED lifecycle, and
snapshot retention/receipts are **not** implemented.

---

Wavdrop's backup system preserves a user's music life, not merely the songs currently visible in the
MediaStore scan. Restore must never silently discard history because a song is temporarily absent.

## 1. Product Contract

### 1.1 What a Wavdrop backup protects - *Partially implemented*

| Item | Status |
|---|---|
| Listening history (play counts, skips, listening time, events) | Implemented |
| Recently Played state (`lastListenedAt`, v2) | Implemented |
| Favourites | Implemented |
| Playlists and playlist order | Implemented for matched songs; unmatched entries are preserved in `pending_playlist_entries` with position |
| Lyrics overrides | Implemented (matched); unmatched preserved as pending |
| Import baselines | Implemented (matched); unmatched preserved as pending |
| Wavdrop preferences | Implemented for the supported `preferences.android` keys |
| Equalizer intent / custom EQ settings | **Deferred** (not in backups today) |
| Historical identities for music not currently available | Partially implemented (pending/quarantine rows; no exported TrackIdentity) |
| Future music-memory systems (Timeline, Eras, Rediscovery) | Deferred |

### 1.2 What it does not protect - *Implemented (principle)*

A Wavdrop backup does **not** contain the underlying audio files.

```text
Wavdrop Backup  = music library state and listening life
Music File Backup = user-owned audio files stored separately
```

User-facing wording must never imply that Wavdrop restores deleted MP3, FLAC, M4A, or other audio files.
Backup, import, and export never modify audio files.

## 2. Core Guarantees

| # | Guarantee | Status |
|---|---|---|
| 1 | No silent historical loss because music is temporarily absent | Implemented for restore (unmatched history -> pending tables); pending rows are archive artefacts and are not rematched |
| 2 | No silent partial restore | Implemented (warnings, restore diagnostics, partial-restore warning for unknown optional capabilities) |
| 3 | No future-format partial import | Implemented (newer versions and unknown required capabilities are rejected before apply) |
| 4 | No destructive restore without explicit user intent | Implemented: Merge is the default; Recovery must be explicitly selected, requires the stronger destructive confirmation, and cannot run until a verified pre-Recovery safety snapshot exists |
| 5 | No backup reported successful until read back and verified | Implemented (export read-back parse + integrity check before success) |
| 6 | No automatic deletion of historical music data merely because files disappeared | Implemented (no automatic purge exists; deleting a track keeps its stats and events) |
| 7 | No weak match attaches history to the wrong song | Implemented (ambiguous matches stay unresolved; no metadata-as-identity) |
| 8 | A backup can be inspected, explained, and verified by the user | Implemented (Backup Verification screen, restore preview with matched/skipped counts, integrity status) |

**Principle:** a wrong match is worse than an unresolved song. Audio files are not contained in
backups. History is never silently lost. Events are historical facts. Nothing is purged automatically.

## 3. Definitions

### 3.1 Current Library Track
A song currently discovered through MediaStore and available for playback. Device-local; may disappear or
receive a new MediaStore id; not a durable identity.

### 3.2 Track Identity - *Partially implemented*
A Wavdrop-owned historical identity for a piece of music, durable across rescans and temporary absence.
Today: a **device-local** `track_identities` row (`identityUuid`, soft `currentSongId`, `createdAt`,
`lastResolvedAt`) minted by the media scan, cleared (not deleted) when its song disappears. It is not
exported and is not used for rematching.

### 3.3 Source Reference
A low-trust device-specific hint (MediaStore song/album id, content URI, folder path, legacy Room id).
Source references assist matching but must never become the sole long-term identity.

### 3.4 Historical Tombstone - *Partially implemented*
A retained identity whose source audio is unavailable (a cleared `currentSongId` today). A tombstone is
not corruption, an error, or a deleted memory.

## 4. Track Availability State Model - *Deferred (as a state machine)*

```text
LIVE      matched to a playable local song
PENDING   restored from backup, no local song currently matched
GHOST     previously LIVE, its local song later disappeared
ARCHIVED  unavailable for 12+ months; retained but hidden from ordinary surfaces
PURGED    explicitly removed by the user
```

Implemented foundations: LIVE (identity with a current song), PENDING (the pending/quarantine tables), and
the GHOST *condition* (identity with null `currentSongId`). There is no persisted availability-state
field, no automatic transition, no 12-month ARCHIVED rule, and no PURGED flow.

Rules that remain binding: no automatic transition to PURGED; ARCHIVED is a visibility/storage state, not a
data-loss state; a returning song may transition ARCHIVED -> LIVE.

## 5. Durable Track Identity Model - *Partially implemented*

Contract target: `identityUuid` (durable primary identity), `portableTrackKey` (conservative
cross-device matching aid), metadata, source references, availability state, resolution metadata.

Implemented: `identityUuid`, `currentSongId`, `createdAt`, `lastResolvedAt` (device-local).
Deliberately **not** implemented: `portableTrackKey` (evidence, never identity - no proven use yet),
availability-state field, exported identity.

Identity rule: MediaStore id is not identity; content URI is not identity; Room song id is not identity;
Wavdrop UUID is identity. A portable key, when it exists, is a matching aid, not proof of equality; a
collision or ambiguity stays unresolved.

## 6. Unmatched Data Retention Policy

### 6.1 Default policy - *Implemented*
Track identity and metadata, aggregate statistics, listening events, lyrics overrides, playlist
positions: retain indefinitely. Dead source references: low-priority metadata.

Pending rows are **snapshot-scoped**: the origin key includes the backup fingerprint, so two exports of the
same library create separate pending rows. This is intentional (no cross-snapshot identity claims) and
tracked as a guardrail (TD-013).

### 6.2 After 12 months unmatched - *Deferred*
PENDING / GHOST -> ARCHIVED with Storage Management and Lifetime History surfaces. Not implemented.

### 6.3 Permanent deletion - *Deferred*
Must be user-initiated through a deliberate review flow that discloses exactly what is removed (history,
statistics, playlist placeholders, lyrics overrides, future rematch capability). No vague "clear stale
data" action.

## 7. Historical Data Behavior

- **7.1 Statistics** - *Partially implemented.* Stats of unmatched tracks are preserved in pending tables
  (archive-only; they do not contribute to Smart Collections, queues, or current-library analytics).
  Lifetime/Timeline/Era surfaces that would consume them are deferred.
- **7.2 Listening events** - *Implemented.* Events are immutable historical facts, valid even if the audio
  file disappears. New events receive a stable `eventId` at creation; legacy events are never backfilled.
- **7.3 Playlists** - *Partially implemented.* Unmatched playlist entries are preserved with their position in
  `pending_playlist_entries` and are not silently dropped; placeholder display and reconnect-in-original-
  position are deferred. Restore never reorders existing entries or collapses positions.
- **7.4 Lyrics overrides** - *Partially implemented.* Overrides are keyed by local song id / content URI and
  preserved as pending when unmatched; attaching them to a durable identity is deferred.

## 8. Restore Modes

Contract: make the user's intent explicit (Recovery vs Merge).

### 8.1 Recovery Restore - *Implemented and physically validated*
Physical validation (2026-10-06): the destructive/authoritative behavior and the safety-snapshot requirement
passed on a real device, and the destructive wording and confirmation were clear in use. The limitations
listed below are unchanged by that validation.

An explicit, user-selected mode for Wavdrop (Android) backups. The selected backup becomes authoritative for
WavDrop-owned recoverable state, mapped conservatively onto the songs currently in the device library. It is
never inferred (not from a clean install, an empty database, wording, backup age or source installation); every
import starts at Merge and Recovery is never remembered. Desktop backups stay Merge-only.

**Eligibility.** VERIFIED v2 only. A v1 backup (even with a v1 checksum) stays Merge-only; an invalid, tampered or
unparseable file is blocked. Eligibility is independent of Merge's "nothing mergeable" result (Recovery may
replace newer local state with an older backup). The clean-install flow is not redefined: the mode choice is
not offered there because there is nothing to replace.

**Mandatory safety snapshot.** Recovery never runs before a VERIFIED pre-restore snapshot exists. The snapshot is
an ordinary current-format (v2) backup produced by the existing exporter, written to the app-private
`files/recovery-safety/pre-recovery-latest.json` (no SAF folder, external storage, network or cloud), read back,
required to equal what was written, and accepted by `BackupSaveValidator` and the v2 parser (integrity
fingerprint, manifest, plausibility). The write goes through a temp file and an atomic replace, so a failed
attempt never destroys the previous verified snapshot. Any failure at any stage (build, write, read-back,
validate, replace) blocks Recovery with no "continue anyway"; Merge stays available. Only the latest snapshot is
kept (no retention, receipts or snapshot UI - still deferred). It is kept after success and after failure.
Because only one is kept, a second Recovery replaces the first snapshot with the post-first-Recovery state.

**Sequencing (single authority: `RecoveryRestoreOrchestrator`).** Re-parse and re-validate the selected text ->
verified snapshot -> (stop unless VERIFIED) -> plan (all matching decided before any clear) -> one Room
transaction -> supported preferences -> detailed outcome (Success / InputBackupInvalid / SafetySnapshotFailed /
RecoveryApplyFailed / PartialRecovery / RestoreInProgress).

**Locks (outermost first, never nested the other way).** `RestoreOperationLock` (non-queuing `tryLock`; one
restore at a time across Merge, Desktop import and Recovery) -> `BackupExecutionSerializer` for the snapshot,
released, then again for the apply+preferences window so Back up now / WorkManager auto-backup can never export a
half-applied Recovery. The two serializer holds are separate (the Mutex is not re-entrant). Known window: an
event recorded between the snapshot and the transaction is not in the snapshot.

**Data semantics (matched songs take the backup value; nothing is MAX-merged).**

| Data | Recovery behavior |
|---|---|
| Stats | Replaced with the backup row (lower values included, exact v2 `lastListenedAt`); local rows for songs the backup does not represent are removed |
| Favourites | Backup is authoritative; a local favourite the backup does not mark is cleared (Recovery only) |
| Events | Exported-source events (`wavdrop_playback`, `manual_restore`, `wavdrop_desktop_playback`) are replaced by the backup's set (matched ones restored, deduped inside the backup; none fabricated from counters). Events from non-exported sources are never deleted |
| Playlists | WavDrop playlists replaced by the backup's (names, backup order, created/updated times, duplicate entries of the same backup song kept); local-only playlists removed; device/MediaStore playlists untouched |
| Lyrics / baselines | Replaced by the backup's matched rows |
| Unmatched data | Preserved in the pending/quarantine tables with the same origin-key idempotence as Merge. Existing pending rows are NOT deleted (they are not exported, so the snapshot could not restore them) |
| desktopOverlay | Stored verbatim from the backup; removed if the backup carries none. Overlay stats/events are not re-applied (the root is already authoritative) |
| Preferences | The supported preference set, with unspecified (default) values resolved to defaults so Recovery is authoritative over a changed device. Scan mode and SAF folder grants are device state and are not restored. If auto-backup is restored without a local folder, the existing "Choose backup folder" prompt appears |

Never touched: the songs table / MediaStore / audio files, device-local TrackIdentity, queue/session, EQ.

**Atomicity boundary.** All Room changes are one `withTransaction`; a failure leaves the database exactly as it was.
DataStore cannot join that transaction. Preferences are applied afterwards; if that fails the result is
`PartialRecovery` (never reported as success), the snapshot is retained, and no compensating rollback is
attempted (not implemented).

### 8.2 Merge Restore - *Implemented*

| Data | Behavior today |
|---|---|
| Events with stable ids | Union, deduplicated by `eventId`; legacy events dedupe by `songId + occurredAt + eventType + listenedMs` |
| Aggregate stats | `MAX(local, backup)`, idempotent, never lowers known history |
| Favourites | Union (`true` wins) |
| Lyrics | Latest `updatedAt` wins (backup override applied only when absent locally or newer) |
| Playlists | Add missing playlists/entries; local extras preserved; existing order untouched |
| Preferences | Applied from `preferences.android` where supported |
| Unmatched tracks | Persisted as pending (quarantine) rows |

### 8.3 Legacy merge rule - *Implemented*
For backups without stable event ids or complete event history, merge uses conservative aggregate
reconciliation and does not claim a perfect combination. `MAX(local, backup)` is a fallback, not
mathematically equivalent to combining independent histories (TD-016).

## 9. Event and Statistics Authority

### 9.1 Long-term model - *Deferred*
`immutable events + imported baselines = derived aggregate statistics`. Today aggregates remain the fast path
and are not recomputed from events (event-led reconciliation is future work).

### 9.2 Event requirements - *Partially implemented*
New events carry `eventId` (generated at creation, never during export), `occurredAt`, `eventType`,
`listenedMs`, `durationMs`, and `source`. Events do **not** carry a `trackIdentityUuid`.

### 9.3 Imported baselines - *Implemented*
BlackPlayer-style imports retain provenance (`sourceType`, `sourceKey`, last imported counts, `importedAt`)
so repeated imports and restores never double-count.

### 9.4 Aggregate stats
Aggregates are useful for fast UI; they are not the sole durable truth once complete event coverage exists.

## 10. Backup Format Contract

Normative field-level definition: [WAVDROP_DATA_FORMAT_SPEC.md](WAVDROP_DATA_FORMAT_SPEC.md). Semantic
summary - *Implemented* unless noted:

- **Version policy:** v1 backups import indefinitely through the legacy adapter; v2 backups are rejected
  by older apps; future major versions are rejected before apply; future minor versions accept known
  fields; unknown *optional* capabilities import supported data with a partial-restore warning; unknown
  *required* capabilities are rejected. (The current parser knows no capabilities of either kind.)
- **Required vs optional capabilities:** `requiredCapabilities` / `optionalCapabilities` are mandatory
  arrays in v2.
- **Extension preservation:** the non-integrity extension root `desktopOverlay` is stored verbatim in
  `pending_backup_extensions` and re-exported, so newer portable data survives Android round trips. A
  general "preserve any unknown root" mechanism, a `trackIdentities` section, and an `extensions` object
  are **Deferred**.

## 11. JSON Type Rules - *Implemented (v2)*

Opaque identifiers are strings; timestamps, durations, and counters are non-negative JSON integers within
bounded ranges; duplicate JSON keys are rejected (no last-write-wins); nesting depth is bounded.

## 12. Integrity and Trust Levels - *Implemented*

Every v2 backup requires an integrity object (fingerprint of the parsed model, tagged so it cannot
collide with v1) and manifest counts; a v2 file without integrity is invalid. Trust levels
(`BackupIntegrityStatus`): `VERIFIED`, `UNVERIFIED_LEGACY` (valid v1 without checksum - never shown as
fully verified), `INVALID`. The checksum protects against accidental corruption, not hostile tampering;
encryption/signing is a separate future feature.

## 13. Export Reliability Contract

- **Implemented:** consistent snapshot; export-model validation; deterministic serialization; temporary
  write, read-back parse, and integrity verification before success is reported; a failed write never
  replaces the previous verified file; backup operations are serialized.
- **Partially implemented:** "Last verified backup" status exists; persisted receipts with counts and
  fingerprint do not.
- **Deferred:** append-N-verified-snapshots retention (default 7). Current file modes are `DATED` and
  `REPLACE_PREVIOUS`.

## 14. Automatic Backup Contract - *Implemented*

A unique 24-hour periodic WorkManager check (storage-not-low constraint) runs
`AutoBackupRepository.runIfDue()`, which owns the interval due check and the folder write. This is
**best-effort** scheduling: Android, battery, storage, and folder-access conditions may delay or skip
runs. User-facing wording must stay qualified (for example "Automatic Backup Check", "Last automatic
check") and must not promise exact daily/scheduled backups.

## 15. Rematching Contract - *Deferred*

Rematching is not implemented. When it is, it must follow the hierarchy: exact Wavdrop identity
reference; exact known source reference; strong normalized metadata + duration; conservative metadata
with tolerance; otherwise leave unresolved. **Ambiguous match: do not attach history automatically;
remain PENDING/ARCHIVED; surface for optional user review.** A wrong match is worse than an unresolved
song. Pending rows from different snapshots must not be auto-merged until durable identity and stable
event lineage exist.

## 16. UI Contract

- **Restore preview / result** - *Partially implemented.* Shows matched / unmatched / ambiguous tracks,
  stats, favourites, lyrics, playlists, events restored and skipped (duplicate, unmatched, invalid),
  preferences, integrity status, and pending-preserved counts. For eligible (verified v2 Android) backups it
  offers an explicit Merge / Recovery mode selector: Merge is the default, Recovery shows an explicit
  destructive warning, a concise impact summary and a stronger confirmation, and the result reports which mode
  ran. V1 and Desktop backups offer Merge only. Wording was physically validated.
- **Archive management (Historical Music / Storage Management)** - *Deferred.*

## 17. Implementation Sequencing

| Phase | Item | Status |
|---|---|---|
| P1 | Truthful automatic-backup wording | Implemented |
| P1 | Integrity-status distinction | Implemented |
| P1 | Duplicate-key rejection | Implemented |
| P1 | Recovery Restore vs Merge Restore | Implemented and physically validated |
| P1 | Mandatory pre-restore safety snapshot | Implemented (latest only; retention/receipts deferred) |
| P1 | Clear future-version rejection messaging | Implemented |
| P2 | Pending historical-data quarantine | Implemented |
| P2 | Retain unmatched stats/events/lyrics/baselines/playlist entries | Implemented |
| P2 | Post-scan rematching | Deferred |
| P2 | `lastListenedAt` backup support | Implemented |
| P2 | Snapshot retention and receipts | Deferred |
| P2 | Playlist order preservation | Implemented for Merge and Recovery |
| P3 | TrackIdentity migration | Device-local foundation implemented; portable identity deferred |
| P3 | Stable event ids | Implemented (new events only) |
| P3 | Event-led analytics reconciliation | Deferred |
| P3 | WorkManager best-effort scheduling | Implemented |
| P3 | Streaming import/export | Deferred |
| P3 | Optional encrypted backups | Deferred |

## 18. Release Gate

Backup must not be described as a "preservation-grade" system until all of the following are true.
Current status in brackets:

- Unmatched history is retained rather than discarded. [met]
- Restore mode is explicit. [met - Merge default, Recovery deliberate; physically validated]
- Backup integrity status is visible. [met]
- Backup success requires read-back verification. [met]
- Previous verified snapshots survive new-write failure. [met]
- Recently Played data survives restoration. [met]
- Playlist order survives recovery restoration. [met]
- Future-version backups fail safely. [met]
- Auto-backup wording accurately describes actual behavior. [met]

All currently defined preservation release-gate conditions are met. This is an internal engineering fact, not
a marketing claim: the project is not required to use the term "preservation-grade", and it must not appear in
public copy unless a deliberate product/marketing decision chooses it. Snapshot retention/receipts remain
deferred and are not a gate condition.
