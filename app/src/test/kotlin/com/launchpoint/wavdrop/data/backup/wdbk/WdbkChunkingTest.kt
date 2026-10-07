package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupIntegrityStatus
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.read
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.unzip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listening history is the large section: chunk boundaries, determinism, ordering independence, tampering. */
class WdbkChunkingTest {

    private val chunk = 5

    private fun backupWith(n: Int): WavdropBackup = RecoveryTestFixtures.v2Backup(events = WdbkTestSupport.events(n))

    private fun historyNames(bytes: ByteArray) = unzip(bytes).keys.filter { it.startsWith("history/") }

    private fun assertExactRoundTrip(n: Int, perChunk: Int = chunk) {
        val src = backupWith(n)
        val (bytes, receipt) = write(src, perChunk)
        val result = read(bytes)
        assertNull("n=$n: ${result.error}", result.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
        // Every event exactly once: same multiset, no loss, no duplication.
        assertEquals(n, result.backup!!.listenEvents.size)
        assertEquals(src.listenEvents.map { it.eventId.orEmpty() }.sorted(), result.backup!!.listenEvents.map { it.eventId.orEmpty() }.sorted())
        assertEquals(src.listenEvents.toSet().size, result.backup!!.listenEvents.toSet().size)
        assertEquals(WavdropBackupIntegrityV2.fingerprint(src), WavdropBackupIntegrityV2.fingerprint(result.backup!!))
        val expectedChunks = (n + perChunk - 1) / perChunk
        assertEquals(expectedChunks, historyNames(bytes).size)
        assertEquals(n, receipt.manifest.entries.filter { it.section == "listenEvents" }.sumOf { it.eventCount ?: 0 })
    }

    @Test fun `history sizes around the chunk boundary all round-trip exactly`() {
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 1, 7 * chunk + 3)) assertExactRoundTrip(n)
    }

    @Test fun `the production chunk size has the same boundary behaviour`() {
        val c = WdbkLimits.EVENTS_PER_CHUNK
        for (n in listOf(c - 1, c, c + 1)) assertExactRoundTrip(n, perChunk = c)
        assertExactRoundTrip(2 * c + 1, perChunk = c)
    }

    @Test fun `chunk names are deterministic zero-padded and contiguous`() {
        val names = historyNames(write(backupWith(23), chunk).first)
        assertEquals(
            listOf(
                "history/listen-events-000000.json", "history/listen-events-000001.json",
                "history/listen-events-000002.json", "history/listen-events-000003.json",
                "history/listen-events-000004.json",
            ),
            names,
        )
    }

    @Test fun `no history entries are written for an empty history`() {
        assertTrue(historyNames(write(backupWith(0), chunk).first).isEmpty())
    }

    @Test fun `each chunk is a bounded array and no entry holds the whole history`() {
        val (bytes, receipt) = write(backupWith(1_000), eventsPerChunk = 100)
        val chunks = receipt.manifest.entries.filter { it.section == "listenEvents" }
        assertEquals(10, chunks.size)
        assertTrue(chunks.all { it.eventCount == 100 })
        val files = unzip(bytes)
        val total = chunks.sumOf { files.getValue(it.path).size }
        assertTrue("a chunk must be a small fraction of the history", chunks.all { files.getValue(it.path).size <= total / 5 })
    }

    @Test fun `physical ZIP entry order does not matter`() {
        val src = backupWith(23)
        val bytes = write(src, chunk).first
        val shuffled = WdbkTestSupport.rezip(bytes) { it.reverse() } // manifest first, chunks descending
        val result = read(shuffled)
        assertNull(result.error)
        assertEquals(src.listenEvents.toSet(), result.backup!!.listenEvents.toSet())
        assertEquals(WavdropBackupIntegrityV2.fingerprint(src), WavdropBackupIntegrityV2.fingerprint(result.backup!!))
    }

    @Test fun `a missing middle chunk is rejected`() {
        val bytes = write(backupWith(23), chunk).first
        val tampered = WdbkTestSupport.rezip(bytes) { it.removeAll { e -> e.first == "history/listen-events-000002.json" } }
        assertNull(read(tampered).backup)
    }

    @Test fun `a missing middle chunk is rejected even when the manifest is rewritten to match`() {
        val bytes = write(backupWith(23), chunk).first
        val tampered = WdbkTestSupport.rezip(bytes) { entries ->
            entries.removeAll { it.first == "history/listen-events-000002.json" }
            WdbkTestSupport.mutateManifest(entries) { m ->
                val arr = m.getJSONArray("entries")
                val keep = org.json.JSONArray()
                for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("path") != "history/listen-events-000002.json") keep.put(arr.get(i))
                m.put("entries", keep)
            }
        }
        // Chunk indexes 0,1,3,4 are not contiguous, and counts/fingerprint no longer match either.
        assertNull(read(tampered).backup)
    }

    @Test fun `a duplicate chunk is rejected`() {
        val bytes = write(backupWith(23), chunk).first
        // Same content under a second name, declared as a second chunk index 1.
        val tampered = WdbkTestSupport.rezip(bytes) { entries ->
            val dup = entries.first { it.first == "history/listen-events-000001.json" }.second
            entries.add(0, "history/listen-events-000005.json" to dup)
            WdbkTestSupport.mutateManifest(entries) { m ->
                m.getJSONArray("entries").put(
                    org.json.JSONObject()
                        .put("path", "history/listen-events-000005.json").put("section", "listenEvents")
                        .put("sectionVersion", 1).put("required", true).put("byteLength", dup.size.toLong())
                        .put("sha256", WdbkWriter.sha256Hex(dup)).put("chunkIndex", 5).put("eventCount", 5),
                )
            }
        }
        assertNull("duplicated events must change counts/fingerprint", read(tampered).backup)
    }

    @Test fun `a chunk whose declared index does not match its name is rejected`() {
        val bytes = write(backupWith(12), chunk).first
        val tampered = WdbkTestSupport.rezip(bytes) { entries ->
            WdbkTestSupport.mutateEntryDescriptor(entries, "history/listen-events-000001.json") { it.put("chunkIndex", 7) }
        }
        assertNull(read(tampered).backup)
    }

    @Test fun `physical chunk order cannot change meaning - differently chunked containers decode to the same fingerprint`() {
        val src = backupWith(41)
        val a = read(write(src, 3).first).backup!!
        val b = read(write(src, 17).first).backup!!
        assertEquals(WavdropBackupIntegrityV2.fingerprint(a), WavdropBackupIntegrityV2.fingerprint(b))
        assertEquals(a.listenEvents, b.listenEvents)
    }

    @Test fun `large synthetic history spans many chunks and round-trips exactly`() {
        val n = 60_123
        val src = RecoveryTestFixtures.v2Backup(events = WdbkTestSupport.events(n))
        val (bytes, receipt) = write(src)
        val chunks = receipt.manifest.entries.filter { it.section == "listenEvents" }
        assertEquals((n + WdbkLimits.EVENTS_PER_CHUNK - 1) / WdbkLimits.EVENTS_PER_CHUNK, chunks.size)
        assertTrue(chunks.all { (it.eventCount ?: 0) <= WdbkLimits.EVENTS_PER_CHUNK })
        // The largest single uncompressed payload is one chunk — far below a monolithic history document.
        val files = unzip(bytes)
        val largest = files.values.maxOf { it.size }
        val legacyJsonBytes = com.launchpoint.wavdrop.data.backup.WavdropBackupExporterV2.toJson(src).toByteArray().size
        assertTrue("largest entry $largest vs legacy document $legacyJsonBytes", largest * 10 < legacyJsonBytes)
        val result = read(bytes)
        assertNotNull(result.backup)
        assertEquals(n, result.backup!!.listenEvents.size)
        assertEquals(WavdropBackupIntegrityV2.fingerprint(src), WavdropBackupIntegrityV2.fingerprint(result.backup!!))
        assertTrue("compressed container must be smaller than the legacy JSON", bytes.size < legacyJsonBytes)
    }
}
