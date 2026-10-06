package com.launchpoint.wavdrop.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRestoreOrchestratorTest {

    private val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val serializer = BackupExecutionSerializer()
    private val lock = RestoreOperationLock()

    private val snapshotInfo = VerifiedSafetySnapshot(
        path = "/x/pre-recovery-latest.json", createdAtMs = 1L, sizeBytes = 10L, backupId = "id", integrityFingerprint = "fp",
        verification = BackupIntegrityStatus.VERIFIED, songCount = 1, statsCount = 1, eventCount = 0, playlistCount = 0,
    )

    private inner class FakeSnapshots(var result: SafetySnapshotResult = SafetySnapshotResult.Verified(snapshotInfo)) : SafetySnapshotProvider {
        override suspend fun createVerifiedSnapshot(): SafetySnapshotResult { calls += "snapshot"; return result }
    }

    private inner class FakeDb(
        val fail: Boolean = false,
        val gate: CompletableDeferred<Unit>? = null,
    ) : RecoveryDatabaseApplier {
        override suspend fun applyRecovery(backup: WavdropBackup): WavdropBackupImportApplyResult {
            calls += "db:start"
            gate?.await()
            if (fail) throw IllegalStateException("tx failed")
            calls += "db:done"
            return WavdropBackupImportApplyResult(
                matchedTracks = 1, unmatchedTracks = 0, statsUpdated = 1,
                restoreMode = BackupRestoreMode.RECOVERY, recovery = RecoveryRestoreSummary(matchedTracks = 1),
            )
        }
    }

    private inner class FakePrefs(val result: RecoveryPreferenceResult) : RecoveryPreferenceApplier {
        override suspend fun applyRecoveryPreferences(preferences: BackupPreferences?): RecoveryPreferenceResult {
            calls += "prefs"; return result
        }
    }

    private fun orchestrator(
        snapshots: SafetySnapshotProvider = FakeSnapshots(),
        db: RecoveryDatabaseApplier = FakeDb(),
        prefs: RecoveryPreferenceApplier = FakePrefs(RecoveryPreferenceResult.Applied(false, false)),
    ) = RecoveryRestoreOrchestrator(snapshots, db, prefs, lock, serializer)

    private fun restore(o: RecoveryRestoreOrchestrator, json: String = RecoveryTestFixtures.v2Json()) = runBlocking { o.restore(json) }

    @Test fun `the verified snapshot is created strictly before any database or preference change`() {
        val outcome = restore(orchestrator())
        assertTrue(outcome is RecoveryRestoreOutcome.Success)
        assertEquals(listOf("snapshot", "db:start", "db:done", "prefs"), calls)
        val ok = outcome as RecoveryRestoreOutcome.Success
        assertEquals(snapshotInfo, ok.snapshot)
        assertEquals(BackupRestoreMode.RECOVERY, ok.result.restoreMode)
        assertEquals(snapshotInfo, ok.result.recovery!!.safetySnapshot)
        assertTrue(ok.result.recovery!!.preferencesRestored)
    }

    @Test fun `every snapshot failure stage blocks recovery before the database is touched`() {
        for (stage in SafetySnapshotFailureStage.values()) {
            calls.clear()
            val o = orchestrator(snapshots = FakeSnapshots(SafetySnapshotResult.Failed(stage, "boom")))
            val outcome = restore(o)
            assertEquals(RecoveryRestoreOutcome.SafetySnapshotFailed(stage, "boom"), outcome)
            assertEquals("no destructive call after $stage", listOf("snapshot"), calls)
        }
    }

    @Test fun `a snapshot that is not marked VERIFIED is treated as failed`() {
        val unverified = snapshotInfo.copy(verification = BackupIntegrityStatus.UNVERIFIED_LEGACY)
        val outcome = restore(orchestrator(snapshots = FakeSnapshots(SafetySnapshotResult.Verified(unverified))))
        assertTrue(outcome is RecoveryRestoreOutcome.SafetySnapshotFailed)
        assertFalse("db:start" in calls)
    }

    @Test fun `v1 backups are blocked from recovery before a snapshot is even attempted`() {
        val outcome = restore(orchestrator(), RecoveryTestFixtures.v1Json())
        assertEquals(RecoveryEligibility.Reason.LEGACY_V1, (outcome as RecoveryRestoreOutcome.InputBackupInvalid).reason)
        assertTrue(calls.isEmpty())
    }

    @Test fun `an invalid or tampered v2 backup is blocked`() {
        for (json in listOf(RecoveryTestFixtures.tampered(RecoveryTestFixtures.v2Json()), "{not json", "")) {
            calls.clear()
            val outcome = restore(orchestrator(), json)
            assertTrue(outcome is RecoveryRestoreOutcome.InputBackupInvalid)
            assertTrue("nothing runs for invalid input", calls.isEmpty())
        }
    }

    @Test fun `a verified v2 backup is allowed`() {
        assertTrue(restore(orchestrator()) is RecoveryRestoreOutcome.Success)
    }

    @Test fun `a database failure reports RecoveryApplyFailed, keeps the snapshot and skips preferences`() {
        val outcome = restore(orchestrator(db = FakeDb(fail = true)))
        val failed = outcome as RecoveryRestoreOutcome.RecoveryApplyFailed
        assertEquals(snapshotInfo, failed.snapshot)
        assertFalse("preferences must not be applied after a failed database recovery", "prefs" in calls)
    }

    @Test fun `a preference failure after the database commit is partial, never a success`() {
        val outcome = restore(orchestrator(prefs = FakePrefs(RecoveryPreferenceResult.Failed("datastore io"))))
        val partial = outcome as RecoveryRestoreOutcome.PartialRecovery
        assertEquals(snapshotInfo, partial.snapshot)
        assertEquals("datastore io", partial.detail)
        assertFalse(partial.result.recovery!!.preferencesRestored)
        assertFalse(partial.result.preferencesRestored)
    }

    @Test fun `a backup without preferences is a success that leaves settings alone`() {
        val outcome = restore(orchestrator(prefs = FakePrefs(RecoveryPreferenceResult.NotIncluded)))
        val ok = outcome as RecoveryRestoreOutcome.Success
        assertFalse(ok.result.preferencesRestored)
    }

    @Test fun `concurrent recovery requests never run twice and never overwrite the first snapshot`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val o = orchestrator(db = FakeDb(gate = gate))
        val first = async(Dispatchers.Default) { o.restore(RecoveryTestFixtures.v2Json()) }
        while ("db:start" !in calls) delay(5)
        val second = o.restore(RecoveryTestFixtures.v2Json())
        assertEquals(RecoveryRestoreOutcome.RestoreInProgress, second)
        assertEquals("a second request must not even snapshot", 1, calls.count { it == "snapshot" })
        gate.complete(Unit)
        assertTrue(first.await() is RecoveryRestoreOutcome.Success)
        assertEquals(1, calls.count { it == "db:start" })
    }

    @Test fun `another restore holding the lock also blocks recovery`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val merge = async(Dispatchers.Default) { lock.tryRun { gate.await(); "merged" } }
        delay(50)
        assertEquals(RecoveryRestoreOutcome.RestoreInProgress, orchestrator().restore(RecoveryTestFixtures.v2Json()))
        assertTrue(calls.isEmpty())
        gate.complete(Unit)
        assertEquals("merged", merge.await())
    }

    @Test fun `a periodic backup cannot run while recovery is applying and sees a settled state afterwards`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val o = orchestrator(db = FakeDb(gate = gate))
        val recovery = async(Dispatchers.Default) { o.restore(RecoveryTestFixtures.v2Json()) }
        while ("db:start" !in calls) delay(5)
        var backupRan = false
        val backup = async(Dispatchers.Default) { serializer.withSerializedBackup { backupRan = true; calls += "backup" } }
        delay(150)
        assertFalse("auto backup must wait for the apply window", backupRan)
        gate.complete(Unit)
        recovery.await(); backup.await()
        assertTrue(calls.indexOf("backup") > calls.indexOf("prefs"))
    }

    @Test fun `lock order is restore lock then serializer, never nested the other way`() = runBlocking<Unit> {
        // If the orchestrator held the serializer while waiting for the restore lock this would deadlock; it must not.
        val gate = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) { lock.tryRun { gate.await() } }
        delay(50)
        val backup = async(Dispatchers.Default) { serializer.withSerializedBackup { "free" } }
        assertEquals("free", backup.await())
        gate.complete(Unit)
        holder.await()
    }
}
