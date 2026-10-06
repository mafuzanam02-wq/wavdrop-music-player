package com.launchpoint.wavdrop.data.backup

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoverySafetySnapshotCreatorTest {

    private lateinit var dir: File
    private val serializer = BackupExecutionSerializer()

    @Before fun setUp() { dir = File(System.getProperty("java.io.tmpdir"), "wd-snap-${System.nanoTime()}") }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun creator(
        io: SafetySnapshotIo = SafetySnapshotIo.Default,
        build: suspend () -> String = { RecoveryTestFixtures.v2Json() },
    ) = RecoverySafetySnapshotCreator(dir, serializer, build, io)

    private fun create(c: RecoverySafetySnapshotCreator) = runBlocking { c.createVerifiedSnapshot() }

    private fun failedStage(r: SafetySnapshotResult) = (r as SafetySnapshotResult.Failed).stage

    @Test fun `a valid snapshot is written to the app-private directory and verifies as v2`() {
        val c = creator()
        val r = create(c) as SafetySnapshotResult.Verified
        assertEquals(c.latestFile.absolutePath, r.snapshot.path)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.snapshot.verification)
        assertEquals("pre-recovery-latest.json", c.latestFile.name)
        assertTrue(r.snapshot.sizeBytes > 0 && r.snapshot.integrityFingerprint != null && r.snapshot.backupId != null)
        assertFalse("temp file is gone after the atomic replace", File(dir, RecoverySafetySnapshotCreator.TEMP_NAME).exists())
        // importable through the normal parser, as a normal v2 backup
        val parsed = WavdropBackupParser.parse(c.latestFile.readText())
        assertEquals(BackupIntegrityStatus.VERIFIED, parsed.integrityStatus)
        assertEquals(BackupFormatVersion.V2, parsed.backup!!.sourceVersion)
        assertTrue(BackupSaveValidator.isSavedBackupValid(c.latestFile.readText()))
    }

    @Test fun `the exporter failing blocks the snapshot and writes nothing`() {
        val c = creator(build = { throw IllegalStateException("db unavailable") })
        assertEquals(SafetySnapshotFailureStage.BUILD, failedStage(create(c)))
        assertFalse(c.latestFile.exists())
    }

    @Test fun `a write failure blocks the snapshot`() {
        val io = object : SafetySnapshotIo by SafetySnapshotIo.Default {
            override fun write(file: File, text: String) = throw IOException("disk full")
        }
        val c = creator(io)
        assertEquals(SafetySnapshotFailureStage.WRITE, failedStage(create(c)))
        assertFalse(c.latestFile.exists())
    }

    @Test fun `a missing read-back blocks the snapshot`() {
        val io = object : SafetySnapshotIo by SafetySnapshotIo.Default {
            override fun read(file: File): String? = null
        }
        assertEquals(SafetySnapshotFailureStage.READ_BACK, failedStage(create(creator(io))))
    }

    @Test fun `a read-back that differs from what was written blocks the snapshot`() {
        val io = object : SafetySnapshotIo by SafetySnapshotIo.Default {
            override fun read(file: File): String? = SafetySnapshotIo.Default.read(file)?.dropLast(5)
        }
        assertEquals(SafetySnapshotFailureStage.READ_BACK, failedStage(create(creator(io))))
        assertFalse(File(dir, RecoverySafetySnapshotCreator.LATEST_NAME).exists())
    }

    @Test fun `a v2 fingerprint mismatch blocks the snapshot`() {
        // The exporter output is damaged consistently (written == read back), so only integrity validation can catch it.
        val c = creator(build = { RecoveryTestFixtures.tampered(RecoveryTestFixtures.v2Json()) })
        assertEquals(SafetySnapshotFailureStage.VALIDATE, failedStage(create(c)))
        assertFalse(c.latestFile.exists())
    }

    @Test fun `a snapshot that is not a verified v2 backup is rejected`() {
        val c = creator(build = { RecoveryTestFixtures.v1Json() })
        assertEquals(SafetySnapshotFailureStage.VALIDATE, failedStage(create(c)))
    }

    @Test fun `a failed attempt never destroys the previous verified snapshot`() {
        val good = creator()
        create(good)
        val before = good.latestFile.readText()
        val bad = creator(build = { RecoveryTestFixtures.tampered(RecoveryTestFixtures.v2Json()) })
        assertTrue(create(bad) is SafetySnapshotResult.Failed)
        assertEquals(before, good.latestFile.readText())
        assertNotNull(WavdropBackupParser.parse(good.latestFile.readText()).backup)
    }

    @Test fun `a replace failure blocks the snapshot and keeps the previous one`() {
        val good = creator()
        create(good)
        val before = good.latestFile.readText()
        val io = object : SafetySnapshotIo by SafetySnapshotIo.Default {
            override fun replace(from: File, to: File) = throw IOException("cannot replace")
        }
        assertEquals(SafetySnapshotFailureStage.REPLACE, failedStage(create(creator(io, build = { RecoveryTestFixtures.v2Json(RecoveryTestFixtures.v2Backup(backupId = "00000000-0000-0000-0000-0000000000c3")) }))))
        assertEquals(before, good.latestFile.readText())
    }

    @Test fun `snapshot creation runs inside the shared backup serializer and never overlaps Back up now`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backupNow = async(Dispatchers.Default) {
            serializer.withSerializedBackup { entered.complete(Unit); release.await() }
        }
        entered.await()
        var built = false
        val snapshot = async(Dispatchers.Default) {
            creator(build = { built = true; RecoveryTestFixtures.v2Json() }).createVerifiedSnapshot()
        }
        delay(150)
        assertFalse("snapshot must wait for the running backup", built)
        release.complete(Unit)
        backupNow.await()
        assertTrue(snapshot.await() is SafetySnapshotResult.Verified)
        assertTrue(built)
    }

    @Test fun `two snapshot attempts are serialized`() = runBlocking {
        var active = 0
        var maxActive = 0
        val c = creator(build = {
            active++; maxActive = maxOf(maxActive, active); delay(60); active--
            RecoveryTestFixtures.v2Json()
        })
        val a = async(Dispatchers.Default) { c.createVerifiedSnapshot() }
        val b = async(Dispatchers.Default) { c.createVerifiedSnapshot() }
        assertTrue(a.await() is SafetySnapshotResult.Verified)
        assertTrue(b.await() is SafetySnapshotResult.Verified)
        assertEquals(1, maxActive)
    }
}
