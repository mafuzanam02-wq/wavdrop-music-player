package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupSaveValidator
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.MemoryFolder
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.MemoryHandle
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.mutateManifest
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.rezip
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Success is reported only after the bytes on disk were re-read through the production reader and matched. */
class WdbkBackupSaverTest {

    private val backup: WavdropBackup = WdbkTestSupport.fullBackup(eventCount = 40)
    private val saver = WdbkBackupSaver(writer = WdbkWriter(eventsPerChunk = 10))

    private fun WdbkBackupSaver.saveAndVerify(t: BackupFileHandle, b: WavdropBackup): WdbkWriteReceipt =
        runBlocking { saveAndVerify(t, WdbkExportSnapshot.fromBackup(b)) }

    private fun WdbkBackupSaver.saveToFolderAndVerify(f: BackupFolder, name: String, b: WavdropBackup): WdbkWriteReceipt =
        runBlocking { saveToFolderAndVerify(f, name, WdbkExportSnapshot.fromBackup(b)) }

    private fun assertFailsWith(message: String? = null, block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (e: IOException) {
            if (message != null) assertEquals(message, e.message)
        }
    }

    // ── Manual export ─────────────────────────────────────────────────────────

    @Test fun `manual export writes then re-reads and verifies`() {
        val target = MemoryHandle()
        val receipt = saver.saveAndVerify(target, backup)
        assertNotNull(target.content)
        assertEquals(receipt.containerBytes, target.content!!.size.toLong())
        assertEquals(receipt.backupId, backup.backupId)
        assertNull(WdbkTestSupport.read(target.content!!).error)
    }

    @Test fun `falls back from wt to w only when the provider rejects wt`() {
        val target = object : BackupFileHandle {
            val inner = MemoryHandle()
            val tried = mutableListOf<String>()
            override fun openOutput(mode: String) = tried.add(mode).let { if (mode == "wt") null else inner.openOutput(mode) }
            override fun openInput() = inner.openInput()
            override fun delete() = inner.delete()
        }
        saver.saveAndVerify(target, backup)
        assertEquals(listOf("wt", "w"), target.tried)
    }

    @Test fun `no success when the output stream cannot be opened`() {
        val target = MemoryHandle(failOpenOutput = true)
        assertFailsWith("Could not save the backup file. Try a different location.") { saver.saveAndVerify(target, backup) }
        assertNull(target.content)
    }

    @Test fun `no success when the write fails part-way`() {
        val target = MemoryHandle(failWriteAfterBytes = 300)
        assertFailsWith { saver.saveAndVerify(target, backup) }
    }

    @Test fun `no success when the file cannot be read back`() {
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(MemoryHandle(failOpenInput = true), backup) }
    }

    @Test fun `no success when the saved file is corrupt on read-back`() {
        val flipped = MemoryHandle(corruptOnRead = { it.copyOf().also { b -> b[b.size / 2] = (b[b.size / 2].toInt() xor 0x33).toByte() } })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(flipped, backup) }
        val truncated = MemoryHandle(corruptOnRead = { it.copyOf(it.size - 30) })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(truncated, backup) }
    }

    @Test fun `no success on a manifest digest mismatch`() {
        val target = MemoryHandle(corruptOnRead = { bytes ->
            rezip(bytes) { entries ->
                WdbkTestSupport.mutateEntryDescriptor(entries, "sections/songs.json") { it.put("sha256", "1".repeat(64)) }
            }
        })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(target, backup) }
    }

    @Test fun `no success on a semantic fingerprint mismatch`() {
        val target = MemoryHandle(corruptOnRead = { bytes ->
            rezip(bytes) { entries -> mutateManifest(entries) { it.getJSONObject("integrity").put("fingerprint", "a".repeat(64)) } }
        })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(target, backup) }
    }

    @Test fun `a perfectly valid container of a DIFFERENT backup is not accepted as the saved one`() {
        val other = WdbkTestSupport.write(WdbkTestSupport.fullBackup(eventCount = 3).copy(backupId = "someone-else"), 10).first
        val target = MemoryHandle(corruptOnRead = { other })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveAndVerify(target, backup) }
    }

    // ── Folder (automatic) backup ─────────────────────────────────────────────

    private val fileName = "wavdrop-backup.wdbk"

    @Test fun `folder backup verifies temp then promotes the exact verified bytes then verifies final`() {
        val folder = MemoryFolder()
        val receipt = saver.saveToFolderAndVerify(folder, fileName, backup)
        val finalBytes = folder.files.getValue(fileName).content!!
        assertEquals(receipt.containerBytes, finalBytes.size.toLong())
        assertNull(WdbkTestSupport.read(finalBytes).error)
        // The temp file existed under a non-backup name and was removed.
        assertTrue(folder.files.getValue("$fileName.tmp").deleted)
        assertEquals(setOf(fileName, "$fileName.tmp"), folder.files.keys)
    }

    @Test fun `final bytes are the temp bytes - never regenerated`() {
        val folder = MemoryFolder()
        var tempCopy: ByteArray? = null
        folder.handleFactory = { name ->
            if (name.endsWith(".tmp")) MemoryHandle(corruptOnRead = { b -> b.also { tempCopy = it.copyOf() } }) else MemoryHandle()
        }
        saver.saveToFolderAndVerify(folder, fileName, backup)
        assertArrayEquals(tempCopy, folder.files.getValue(fileName).content)
    }

    @Test fun `existing final file is reused so no duplicate is created`() {
        val folder = MemoryFolder()
        val existing = MemoryHandle(content = "OLD".toByteArray())
        folder.files[fileName] = existing
        saver.saveToFolderAndVerify(folder, fileName, backup)
        assertTrue(folder.files.getValue(fileName) === existing)
        assertEquals(2, folder.files.size)
        assertNull(WdbkTestSupport.read(existing.content!!).error)
    }

    @Test fun `a stale temp from a crashed run is removed first`() {
        val folder = MemoryFolder()
        val stale = MemoryHandle(content = "garbage".toByteArray())
        folder.files["$fileName.tmp"] = stale
        saver.saveToFolderAndVerify(folder, fileName, backup)
        assertTrue(stale.deleted)
    }

    @Test fun `a corrupt temp leaves the previous final backup untouched`() {
        val folder = MemoryFolder()
        val previous = "PREVIOUS-GOOD-BACKUP".toByteArray()
        val final = MemoryHandle(content = previous)
        folder.files[fileName] = final
        folder.handleFactory = { name -> if (name.endsWith(".tmp")) MemoryHandle(corruptOnRead = { it.copyOf(it.size / 2) }) else MemoryHandle() }
        assertFailsWith { saver.saveToFolderAndVerify(folder, fileName, backup) }
        assertArrayEquals(previous, final.content)
        assertTrue(final.modes.isEmpty()) // final was never even opened for writing
        assertTrue(folder.files.getValue("$fileName.tmp").deleted)
    }

    @Test fun `a temp write failure leaves the previous final backup untouched`() {
        val folder = MemoryFolder()
        val final = MemoryHandle(content = "PREVIOUS".toByteArray())
        folder.files[fileName] = final
        folder.handleFactory = { name -> if (name.endsWith(".tmp")) MemoryHandle(failWriteAfterBytes = 100) else MemoryHandle() }
        assertFailsWith { saver.saveToFolderAndVerify(folder, fileName, backup) }
        assertArrayEquals("PREVIOUS".toByteArray(), final.content)
        assertTrue(final.modes.isEmpty())
    }

    @Test fun `temp creation failure fails without touching the final backup`() {
        val folder = MemoryFolder()
        val final = MemoryHandle(content = "PREVIOUS".toByteArray())
        folder.files[fileName] = final
        folder.failCreate = { it.endsWith(".tmp") }
        assertFailsWith { saver.saveToFolderAndVerify(folder, fileName, backup) }
        assertArrayEquals("PREVIOUS".toByteArray(), final.content)
    }

    @Test fun `final file creation failure is reported and the temp is cleaned up`() {
        val folder = MemoryFolder()
        folder.failCreate = { !it.endsWith(".tmp") }
        assertFailsWith { saver.saveToFolderAndVerify(folder, fileName, backup) }
        assertTrue(folder.files.getValue("$fileName.tmp").deleted)
    }

    @Test fun `final write failure is not reported as success`() {
        val folder = MemoryFolder()
        folder.files[fileName] = MemoryHandle(content = "PREVIOUS".toByteArray(), failWriteAfterBytes = 200)
        assertFailsWith { saver.saveToFolderAndVerify(folder, fileName, backup) }
        assertTrue(folder.files.getValue("$fileName.tmp").deleted)
    }

    @Test fun `final output that cannot be opened is not reported as success`() {
        val folder = MemoryFolder()
        folder.files[fileName] = MemoryHandle(content = "PREVIOUS".toByteArray(), failOpenOutput = true)
        assertFailsWith { saver.saveToFolderAndVerify(folder, fileName, backup) }
    }

    @Test fun `a corrupt final file is not reported as success`() {
        val folder = MemoryFolder()
        folder.files[fileName] = MemoryHandle(corruptOnRead = { it.copyOf(it.size - 40) })
        assertFailsWith(BackupSaveValidator.VALIDATION_FAILED_MESSAGE) { saver.saveToFolderAndVerify(folder, fileName, backup) }
        assertTrue(folder.files.getValue("$fileName.tmp").deleted)
    }

    @Test fun `temp file names never look like a backup to discovery`() {
        val folder = MemoryFolder()
        saver.saveToFolderAndVerify(folder, "wavdrop-backup-2026-10-07.wdbk", backup)
        val tempName = folder.files.keys.single { it.endsWith(".tmp") }
        assertEquals("wavdrop-backup-2026-10-07.wdbk.tmp", tempName)
        assertFalse(com.launchpoint.wavdrop.data.backup.BackupVerificationRepository.isBackupFileName(tempName))
        assertTrue(com.launchpoint.wavdrop.data.backup.BackupVerificationRepository.isBackupFileName("wavdrop-backup-2026-10-07.wdbk"))
    }

    @Test fun `receipt manifest matches what a real container contains`() {
        val receipt = saver.saveAndVerify(MemoryHandle(), backup)
        val json = JSONObject(String(receipt.manifest.toJsonBytes(), Charsets.UTF_8))
        assertEquals(receipt.fingerprint, json.getJSONObject("integrity").getString("fingerprint"))
    }
}
