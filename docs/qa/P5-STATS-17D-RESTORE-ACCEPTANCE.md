# P5-STATS-17D - Orphan history: restore acceptance record

**Verdict in one line:** JVM/Room acceptance of the 17B/17C quarantine pipeline is **PASS**. Real Android device acceptance is
**UNPROVEN** (not run, see section 4). No production code was changed in this slice.

## 1. Verified baseline

| Item | Value |
|---|---|
| GitHub `refs/heads/master` (`git ls-remote`, read-only) | `06f712a80b8505459b50f8f74c5018a0a820676a` |
| Local `HEAD` / `origin/master` | `06f712a80b8505459b50f8f74c5018a0a820676a` / same; 0 ahead, 0 behind |
| Remote (fetch and push) | `https://github.com/mafuzanam02-wq/wavdrop-music-player.git` |
| Branch | `master` |
| Index, worktree, untracked files at start | all empty |
| Repository instruction files | none at the root (no CLAUDE.md / AGENTS.md); `docs/BACKUP_PRESERVATION_CONTRACT.md`, `docs/WAVDROP_IMPORT_RULES.md`, `QA_CHECKLIST.md` read |
| App version | `0.1.0-beta10` (versionCode 10) |
| JVM host | Windows 11, Intel Core i5-1334U, 15.7 GB, OpenJDK 21.0.13, Gradle 8.13 |

No fetch, pull, reset, clean, stage, commit, push or deploy was performed.

## 2. End-to-end restore flow that was exercised

1. Original library (songs 1-7) with stats, events, playlist, lyrics and an import baseline is built **by a real Merge restore**.
2. The **real exporter** writes backup A (legacy JSON and `.wdbk`); the production parser / `WdbkReader` verifies it
   (`BackupIntegrityStatus.VERIFIED`).
3. Songs 5, 6 and 7 have their descriptors removed from A (a descriptor-less orphan situation); 6's events disagree on tags, 7's
   state nothing. The result is re-serialized and verified again.
4. A destination database holds only songs 1-3. **Merge** and **Recovery** each restore A (`WavdropBackupImportRepository.applyImport`,
   `RecoveryRestoreRepository.applyRecovery`) inside their Room transaction. `QuarantinePlanner` quarantines songs 4 (descriptor,
   unavailable) and 5, 6, 7 (descriptor-less).
5. The database file is **closed and reopened**; state is compared byte-for-byte via entity dumps.
6. The same backup is restored again (repeat import).
7. A new backup B is exported from the reopened database; it carries the quarantine through the 17B pending-history section.
8. A fresh installation restores B, restores B again, exports, and a third installation restores that: no growth.

## 3. Test matrix

Environment for every JVM row: Windows 11 / OpenJDK 21 / Gradle 8.13, Robolectric (SDK 35) with real SQLite, **not a device**.
Test class `OrphanRestoreAcceptanceRoomTest` (new, 7 tests) is run by
`./gradlew.bat :app:testDebugUnitTest --tests "*OrphanRestoreAcceptanceRoomTest"`; other rows by the class named.

| # | Scenario | Evidence | Result |
|---|---|---|---|
| 1 | Original library with history on multiple songs | `acceptance` x4 (JSON/WDBK x Merge/Recovery), seeded by real restore | PASS (JVM) |
| 2 | Backup creation and integrity verification | `acceptance` x4: A, the stripped A, and B all parse `VERIFIED` | PASS (JVM) |
| 3 | Restored environment missing some songs | destination holds songs 1-3 only; live stats exist only for 1-3, none for 4-7 | PASS (JVM) |
| 4 | Merge preserves unmatched history in quarantine | `acceptance - JSON/WDBK - Merge`: tracks 4,5,6,7; events, lyrics, baseline, playlist entries | PASS (JVM) |
| 5 | Recovery preserves per the contract | `acceptance - JSON/WDBK - Recovery`: same quarantine; existing pending rows kept (see 12) | PASS (JVM) |
| 6 | Persistence across close/reopen of the database file | file-backed DB reopened; full dump equal before/after | PASS (JVM, file DB) |
| 7 | Export of a new backup containing pending history | B verified and its pending section equals the stored quarantine | PASS (JVM) |
| 8 | Re-import without duplicate preservation | repeat import of A, of B, and a third generation: pending set unchanged | PASS (JVM) |
| 9 | Distinct missing-song ids keep distinct metadata | 4 = "Song 4" (descriptor), 5 = "Song 5" (hint), 6, 7 separate | PASS (JVM) |
| 10 | Conflicting / blank metadata unresolved | 6: title/artist/album `""` (URI kept as unanimous); 7: all `""` / null; unknown duration stays 0 | PASS (JVM) |
| 11 | Playlist-entry provenance intact | entries for 4 and 5 carry the playlist id/created/updated stated by the backup | PASS (JVM) |
| 12a | Interrupted Recovery at each of the 11 stages | `a Recovery interrupted at any stage...`: live data and an existing pending row are byte-identical after every failure | PASS (JVM) |
| 12b | Conflicting quarantine fails safely | `OrphanHistoryRoomTest` (17C) conflict test, both modes, full rollback | PASS (JVM) |
| 12c | Damaged / tampered input | `damaged or tampered...`: a changed JSON value and a flipped `.wdbk` byte never read back as VERIFIED, so no restore starts | PASS (JVM) |
| E1 | Events with stable ids and legacy events without | seed has one of each per song: live restore keeps 3 with ids and 3 without; **pending events never carry an id** (pending section contains no event id text) | PASS (JVM) |
| E2 | Reused numeric ids never match a live song | `OrphanHistoryRoomTest` collision test (live song 90 untouched); acceptance: no live rows for 4-7 | PASS (JVM) |
| E3 | Recovery safety-snapshot requirement | `RecoverySafetySnapshotCreatorTest` (11) and `RecoveryRestoreOrchestratorTest` (13) | PASS (JVM) |
| E4 | Existing 17B A-to-B-to-C preservation | `PendingHistoryPreservationRoomTest` (18), `OrphanHistoryRoomTest` chains | PASS (JVM) |
| E5 | 17C scaling (algorithmic) | `OrphanMetadataScalingTest` (5): reads bounded per record, 4x input <= 5x work | PASS (JVM) |
| D1 | Install, restore, restart, export, repeat import on an Android device/emulator | not run | **UNPROVEN** |
| D2 | Restore duration / peak memory on a device | not measured | **UNPROVEN** |
| D3 | App restart after restore on a device | not run | **UNPROVEN** |

Totals: full JVM suite `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` -> **4,805 tests, 368 suites, 0 failures,
0 errors, 0 skipped; BUILD SUCCESSFUL (12 min 1 s)**. Relevant suites: Orphan* (14 + 5 + 7), PendingHistory* (24 + 18),
Quarantine* (13 + 5), RecoveryRestore* (13 + 16 + 25), RecoverySafetySnapshotCreatorTest (11); all green.

## 4. Real Android environment

| Check | Finding |
|---|---|
| `adb devices -l` | one **physical** device: Samsung `SM_A155F` (serial `RZ8X204TP9A`), Android API 37 |
| Emulator / AVD | none installed (no `emulator.exe`, no system images, no AVDs) |
| Installed Wavdrop packages on that device | `com.launchpoint.wavdrop` (release) **and** `com.launchpoint.wavdrop.debug` |
| Existing instrumented tests | `app/src/androidTest` has DAO/migration/preference tests only; none for backup restore |

**Not run, deliberately.** The only target is a physical phone that holds a real installation of the app, and there is no
disposable emulator. `connectedDebugAndroidTest` would reinstall `com.launchpoint.wavdrop.debug` and uninstall it afterwards,
which erases that app's data; installing any APK, creating test data or changing app state on the phone is outside what this
slice was authorized to do without an explicit go-ahead. Nothing was installed, uninstalled, pushed, cleared or run on the
device (only read-only `adb devices`, `pm list packages` and `getprop` calls).

**Status: D1-D3 are UNPROVEN.** The JVM results above use real SQLite and the production code paths, but they are not physical
acceptance. To close D1-D3, either start a disposable emulator, or explicitly authorize a test installation on the phone
(see section 8).

## 5. Performance

Algorithmic evidence (17C1): on a 2,181-record input the previous planner made 5,465,221 element reads (about 2,500 per record);
the current one stays under 20 per record, and 4x the input costs at most 5x the work (`OrphanMetadataScalingTest`).

JVM wall-clock samples (**Robolectric, in-memory SQLite, desktop CPU - NOT device numbers**; printed by
`JVM timing of a small and a large orphan restore...`; not asserted; Merge of a backup that is all descriptor-less orphans):

| Run | Orphan IDs | Events | Restore time | JVM heap before / after |
|---|---|---|---|---|
| 1 (cold JIT) | 50 | 500 | 336 ms | 75 / 84 MB |
| 1 | 2,000 | 20,000 | 1,002 ms | 79 / 101 MB |
| 2 (within the full suite) | 50 | 500 | 22 ms | 80 / 83 MB |
| 2 | 2,000 | 20,000 | 555 ms | 84 / 97 MB |

Run-to-run noise is large (JIT warm-up, shared JVM). Heap figures are a crude `totalMemory - freeMemory` and include garbage.
Parsing, planning and transaction costs were not separated (no existing instrumentation, and none was added). Small acceptance
flows (4 songs, 7 stats, 14 events) restored in 2-32 ms. **No device timing or memory was measured; no claim is made about
device performance or about any millisecond improvement.**

## 6. Failure-safety evidence

- **Rejected input does not partially restore:** tampered JSON and a flipped `.wdbk` byte never verify, so no apply is invoked.
  Apply itself never runs on a backup whose integrity fails.
- **Transaction failure leaves no partial Room change:** failure injected at each of Recovery's 11 stages, and at the
  quarantine / pending-history stages, restores to the exact prior state (live rows and a pre-existing pending row).
  Merge's conflict path rolls back live and pending writes (17B/17C tests).
- **Repeat import does not inflate:** A twice, B twice, and a third generation: identical pending set; Merge reports a no-op.
- **No match by reused numeric id:** orphan id equal to an unrelated live song id touches nothing; destination songs 1-3 match
  only through their own descriptors.
- **Existing history not overwritten by unrelated orphan data:** a stored track/stats/event that differs from an incoming one
  under the same key fails the restore instead of being replaced.
- **Existing pending rows not removed:** a pre-existing pending row survives Recovery and every injected failure.
- **Export/import round trips retain the quarantine:** three generations, both formats, both modes.

## 7. Known limitations (carried from 17B/17C)

1. Orphan events whose own title/artist/album uniquely match a live song are restored live by the existing tag matching **and**
   also quarantined as archive (never counted as live stats).
2. Quarantine dedupes events on `(occurredAt, type, listenedMs)`; two orphan events that differ only in `durationMs` or `eventId`
   collapse into one. Fixing this needs a schema change.
3. Pending events have no `eventId` column, so original event ids are not retained for quarantined events.
4. Descriptor-less history is archive-only and cannot be matched to a song later (no rematching).
5. Pending rows are snapshot-scoped: the same logical missing song appears once per snapshot that quarantined it.
6. Conditional invariants not covered: behaviour under real low-storage / process-death conditions, very large (millions of
   events) backups, and SAF-provided file streams, none of which JVM tests reproduce.

## 8. Recommendation

- **Correctness (JVM/Room): acceptable.** No defect was found; nothing in this slice changes production code.
- **For the next distribution:** acceptable as a **beta** build on the strength of the JVM evidence and the earlier physical
  Recovery validation noted in `QA_CHECKLIST.md`, **but full Android restore acceptance is not declared**. D1-D3 remain
  UNPROVEN until run on a disposable emulator or an authorized test installation.
- **Suggested device pass (when authorized):** build `assembleDebug` at the exact commit, install on a disposable emulator
  (or, only with explicit approval, alongside the existing app), restore a synthetic `.wdbk` with descriptor-less orphans in
  Merge then Recovery, force-stop and relaunch, export, and re-import; record API level, APK identity, sizes, duration and
  `dumpsys meminfo`. The QA_CHECKLIST Recovery items (H, L) cover the matching manual steps.
- **No follow-up repair is justified** by anything observed here.

## 9. Files in this slice

- `app/src/test/kotlin/com/launchpoint/wavdrop/data/backup/OrphanRestoreAcceptanceRoomTest.kt` (new test only)
- `docs/qa/P5-STATS-17D-RESTORE-ACCEPTANCE.md` (this record)
