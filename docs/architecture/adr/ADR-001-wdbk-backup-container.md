# ADR-001: WDBK Backup Container and Compression Architecture

- Status: Accepted - implementation pending
- Decision date: 2026-10-06
- Scope: Backup storage/container and serialization architecture.

This ADR does NOT change current runtime behavior by itself. It records a decision made before implementation
starts so the reasoning stays recoverable.

Related documents:

- [Backup preservation contract](../../BACKUP_PRESERVATION_CONTRACT.md)
- [Data format specification](../../WAVDROP_DATA_FORMAT_SPEC.md)
- [Backup schema V1 (legacy)](../../WAVDROP_BACKUP_SCHEMA_V1.md)
- [Import rules](../../WAVDROP_IMPORT_RULES.md)
- [Engineering backlog and decisions](../../../ENGINEERING_BACKLOG_AND_DECISIONS.md)

## 1. Context (current situation)

WavDrop has a mature, JSON-based backup system with established semantics:

- V1 legacy compatibility and V2 verified backups;
- SHA-256-backed integrity/fingerprint validation;
- read-back verification before a backup is reported successful;
- Merge Restore;
- Recovery Restore, with a mandatory verified pre-recovery safety snapshot;
- conservative song matching;
- unmatched-data quarantine/preservation;
- listening-event deduplication;
- Android/Desktop extension preservation;
- serialized backup execution and automatic backup scheduling.

The current backup implementation works. This redesign is NOT motivated by broken restore semantics. The
problem is the physical representation and its scalability:

- a monolithic JSON document becomes increasingly expensive as listening history grows;
- building and parsing one large document puts avoidable pressure on memory;
- naturally append-heavy history benefits from streaming/chunking;
- the backup should become a durable, portable container without giving up the readability and compatibility
  advantages of JSON.

BACKUP SEMANTICS ARE FROZEN BEFORE THIS STORAGE REDESIGN.

## 2. Decision

WavDrop's next-generation backup file uses the `.wdbk` extension as the WavDrop backup container.

- The physical container is ZIP using standard DEFLATE compression.
- The payload remains based on compact UTF-8 JSON.
- No custom binary compression format is adopted.
- Standard Android/JVM-supported archive primitives are used wherever practical, with no native codec
  dependency.

The container must support:

- a manifest;
- explicit container/version information;
- independently versionable logical sections where appropriate;
- integrity information;
- streamed/chunked large history sections;
- a structure deterministic enough for reliable verification and testing;
- Android and Desktop interoperability;
- safe preservation of data Android does not understand but is contractually required to round-trip.

Exact ZIP entry names/directory structure are NOT fixed here; the implementation slice defines them. This ADR
defines architectural requirements only.

### 2.1 Integrity

The container architecture must provide SHA-256-based integrity verification and must not weaken existing
integrity guarantees.

- A backup is not successful merely because a ZIP file was created. Success still requires: write, read back,
  validate the container, validate required sections, validate integrity - and only then is success reported.
- Existing V2 integrity semantics are preserved during migration until a container-level contract explicitly
  supersedes or incorporates them.
- ZIP CRC values are not the WavDrop integrity contract, and verification is not removed because ZIP has them.

### 2.2 Streaming and chunking

Large history must no longer require one giant in-memory JSON string as the long-term architecture. Streaming
and chunking of large, naturally append-heavy data (listening history) is part of the accepted WDBK
architecture, for both export and import. Exact chunk sizing is an implementation/benchmark decision and is
not decided here.

### 2.3 Legacy compatibility

Existing backups remain valid. After `.wdbk` export ships, WavDrop must still import V1 `.json` and V2
`.json` backups. Users are not required to convert old backups first. The importer must detect supported
legacy JSON versus WDBK safely. Legacy compatibility is indefinite unless a future explicit ADR changes that
policy. Old V1/V2 data is not reinterpreted under new semantics.

### 2.4 Semantics are unchanged

WDBK changes packaging/storage, not restore meaning. These contracts are preserved:

Merge Restore:

- monotonic/MAX aggregate semantics where currently defined;
- set-true-only favourite behavior;
- event union and deduplication;
- existing playlist merge behavior;
- existing lyrics merge behavior;
- quarantine/pending preservation.

Recovery Restore:

- verified safety snapshot before destructive Recovery;
- backup-authoritative recoverable state;
- conservative matching;
- unmatched history preserved rather than guessed;
- audio files untouched.

Also preserved: V1/V2 compatibility, no fabricated listening events, current Desktop round-trip guarantees,
backup execution serialization, and the current trust/integrity distinctions. Any change to these requires its
own explicit decision.

### 2.5 No audio files

A WDBK file contains WavDrop application state and music-history data, never the user's audio files.
MediaStore remains outside the backup payload. Nothing about the container may imply, in the UI or in copy,
that `.wdbk` contains or restores MP3, FLAC, M4A, WAV or other music files.

### 2.6 Local-first

Cloud backup/sync is not part of this decision: no accounts, Google Drive or Dropbox integration, cloud-first
storage or network sync, and no INTERNET permission. A `.wdbk` file must remain a fully useful local backup
artifact.

## 3. Reasoning

ZIP + DEFLATE was chosen because it provides:

- mature standard-library/platform support;
- broad tooling support;
- no custom decompressor and no native codec requirement;
- straightforward multi-entry packaging;
- useful compression for repetitive JSON;
- natural support for independently streamed entries;
- easy inspection during development;
- good Android/Desktop portability;
- lower migration and operational complexity than introducing a binary schema and a specialised codec at the
  same time.

The goal is a conservative architecture improvement, not the maximum theoretical compression ratio at any
cost.

Compact UTF-8 JSON stays the first WDBK payload representation because:

- WavDrop already has mature JSON domain contracts;
- the current parser/validation semantics are well tested;
- Android/Desktop interoperability already relies on these meanings;
- JSON is inspectable and diagnosable;
- moving container, compression, semantic schema and binary serialization in one migration would multiply
  risk.

Compression makes JSON's textual verbosity much less important on disk. A binary encoding can be evaluated
independently later.

## 4. Alternatives considered

A. Raw Room/SQLite database backup - rejected as the portable backup format. It couples backup to the Android
persistence implementation, the database schema is not an interchange contract, Desktop portability is poor,
migration is hazardous, and it risks copying device-specific/internal state.

B. MediaStore IDs or file paths as durable identity - rejected. They are source references, not portable
identity, and the container redesign must not promote them to identity.

C. Monolithic uncompressed JSON as the long-term format - superseded by the WDBK direction. Legacy imports
remain supported.

D. Custom compression format - rejected. WavDrop will not invent a compression algorithm.

E. CBOR as the immediate WDBK payload - not chosen. It does not offer enough immediate benefit to justify
changing serialization at the same time as the container migration.

F. ZIP password encryption - rejected as the encryption design. Traditional ZIP password encryption is not an
acceptable security architecture.

G. Timestamp-only event deduplication - rejected. The container change must not weaken stable event identity
or the current dedup rules.

## 5. Deferred

- Protobuf: not part of WDBK-1. The JSON domain model is mature, and changing container and payload encoding
  together would make semantic-equivalence failures harder to isolate. Revisit only if measurements show JSON
  parsing/size is still a material problem after ZIP/DEFLATE plus streaming/chunking. A switch needs its own
  ADR.
- Zstandard: not part of WDBK-1; ZIP/DEFLATE is the baseline (native/common platform support, fewer
  dependencies, simpler interoperability, lower compatibility risk). Reconsider only after real backup
  benchmarks show a meaningful benefit in size, compression/decompression time, memory and battery/CPU large
  enough to justify the codec/dependency complexity. A codec change needs its own ADR.
- Authenticated encrypted backups: deferred. Integrity is not confidentiality: SHA-256 integrity detects
  accidental corruption/tampering within the defined trust model and does not encrypt user history. Any later
  encrypted backup must use an authenticated-encryption design with its own threat model/ADR, never ordinary
  ZIP password encryption.
- Incremental/delta backups: deferred. First establish the stable WDBK container, stable section contracts,
  streaming/chunking, reliable full-snapshot restore, and identity/event lineage sufficient to reason about
  deltas. Full verified snapshots remain the baseline. Incrementals need a future ADR.
- Portable TrackIdentity: not part of WDBK-1. The device-local TrackIdentity must not be exported merely
  because a new container exists. Portable identity, portable song keys and cross-device rematching are a
  separate architecture problem, and a wrong match remains worse than unresolved data.
- WDBK-2 - Portable Model Refinement: a possible later phase that may refine the portable model (normalized
  song definitions, compact song references, better independently versioned sections, improved cross-platform
  round-trip structure). It requires its own bounded design/review and is not done merely to implement WDBK-1.

## 6. Migration direction (architecture level)

WDBK-1:

- add a WDBK container reader/writer;
- preserve all current backup semantics;
- retain V1/V2 JSON import (legacy import code is not removed when WDBK export ships);
- move new exports to WDBK only when equivalence tests and validation pass;
- stream/chunk large history rather than build one monolithic JSON payload;
- provide container verification;
- prove Android round-trip behavior.

### 6.1 Equivalence gate

Before WDBK becomes the default export format, semantic equivalence must be proven: for the same logical
WavDrop state, the legacy/current model and the WDBK model after decode must produce equivalent restore
meaning. At minimum test: stats, favourites, listening events, playlists and order, lyrics, import baselines,
preferences, pending/quarantine preservation, desktopOverlay, integrity/trust results, Merge behavior and
Recovery behavior. Packaging differences are allowed; semantic differences are not.

### 6.2 Benchmarks

Collect evidence at realistic history sizes (representative small and large libraries/history). Measure at
least resulting backup bytes, peak memory, export time and import/verification time. These results inform
chunk sizing, buffering, and whether a later Zstd/Protobuf investigation is justified. This ADR sets no
performance claims.

## 7. Consequences

Positive:

- smaller backups;
- lower long-term memory pressure;
- scalable history export/import;
- clean separation between container and semantic contracts;
- easier future section evolution;
- standard portable archive tooling;
- legacy compatibility retained.

Costs:

- two input families must coexist: legacy JSON and WDBK;
- container validation becomes another trust boundary;
- export/import code becomes more complex;
- Android and Desktop implementations must agree on container semantics;
- more equivalence tests are required.

## 8. When to revisit

This ADR may be superseded if evidence shows:

- DEFLATE has unacceptable CPU/time/size characteristics;
- JSON remains a dominant performance/memory problem even after streaming and compression;
- Desktop interoperability exposes structural constraints;
- authenticated encryption becomes a product requirement;
- stable portable identity enables safe incremental backups;
- platform/runtime constraints materially change.

Revisit through a NEW ADR. Do not silently rewrite this historical decision.

## 9. Status summary

Decision: ACCEPTED

Implementation: NOT STARTED

Current production format: existing V1/V2 JSON system

Next planned engineering slice: WDBK-1 - Durable Backup Container

No production behavior changed by ADR-001.
