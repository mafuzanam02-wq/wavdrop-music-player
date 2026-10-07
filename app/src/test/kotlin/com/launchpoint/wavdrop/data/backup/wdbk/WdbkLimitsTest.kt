package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupInputReader
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.read
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.rezip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.unzip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Decompression/size bounds: the limit itself passes, one byte (or entry) over fails; actual bytes are counted. */
class WdbkLimitsTest {

    private val backup = WdbkTestSupport.fullBackup(eventCount = 30)
    private val bytes: ByteArray = write(backup, eventsPerChunk = 10).first
    private val files = unzip(bytes)
    private val payload = files.filterKeys { it != WdbkLayout.MANIFEST }

    private fun ok(limits: WdbkLimits) = read(bytes, limits).also { assertNull(it.error, it.error) }.backup
    private fun rejected(limits: WdbkLimits) = assertNull(read(bytes, limits).backup)

    @Test fun `outer container limit - exact size passes and one byte over fails`() {
        assertNotNull(ok(WdbkLimits(maxContainerBytes = bytes.size.toLong())))
        val over = read(bytes, WdbkLimits(maxContainerBytes = bytes.size - 1L))
        assertNull(over.backup)
        assertEquals(BackupInputReader.TOO_LARGE_MESSAGE, over.error)
    }

    @Test fun `entry count limit - exact count passes and one fewer fails`() {
        val n = files.size // payload entries + manifest
        assertNotNull(ok(WdbkLimits(maxEntryCount = n)))
        rejected(WdbkLimits(maxEntryCount = n - 1))
    }

    @Test fun `per-entry decompressed limit - the largest entry exactly passes and one byte less fails`() {
        val largest = payload.values.maxOf { it.size }.toLong()
        assertNotNull(ok(WdbkLimits(maxEntryUncompressedBytes = largest)))
        rejected(WdbkLimits(maxEntryUncompressedBytes = largest - 1))
    }

    @Test fun `manifest has its own bound`() {
        val manifestSize = files.getValue(WdbkLayout.MANIFEST).size.toLong()
        assertNotNull(ok(WdbkLimits(maxManifestBytes = manifestSize)))
        rejected(WdbkLimits(maxManifestBytes = manifestSize - 1))
    }

    @Test fun `total decompressed limit - the exact sum passes and one byte less fails`() {
        val total = files.values.sumOf { it.size }.toLong()
        assertNotNull(ok(WdbkLimits(maxTotalUncompressedBytes = total)))
        rejected(WdbkLimits(maxTotalUncompressedBytes = total - 1))
    }

    @Test fun `events-per-chunk limit - exact passes and one over fails`() {
        assertNotNull(ok(WdbkLimits(maxEventsPerChunk = 10)))
        rejected(WdbkLimits(maxEventsPerChunk = 9))
    }

    @Test fun `declared ZIP sizes are never trusted - a bomb is stopped by counting real bytes`() {
        // 40 MiB of zeros compresses to ~40 KiB. Its manifest-declared size is irrelevant: the per-entry bound is
        // enforced on the bytes actually produced by the inflater.
        val bomb = ByteArray(40 * 1024 * 1024)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("sections/songs.json"))
            zip.write(bomb)
            zip.closeEntry()
        }
        val container = out.toByteArray()
        assertTrue("fixture must actually be a small, highly compressed file", container.size < 200 * 1024)
        val result = read(container, WdbkLimits(maxEntryUncompressedBytes = 1024 * 1024))
        assertNull(result.backup)
        assertEquals("This backup is larger than Wavdrop can safely open.", result.error)
    }

    @Test fun `many tiny entries cannot bypass the total bound`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            repeat(50) { i ->
                zip.putNextEntry(ZipEntry("history/listen-events-%06d.json".format(i)))
                zip.write(ByteArray(10_000))
                zip.closeEntry()
            }
        }
        val result = read(out.toByteArray(), WdbkLimits(maxTotalUncompressedBytes = 100_000))
        assertNull(result.backup)
    }

    @Test fun `a chunk that declares too many events is rejected before restore`() {
        val over = write(RecoveryTestFixtures.v2Backup(events = WdbkTestSupport.events(25)), eventsPerChunk = 25).first
        assertNull(read(over, WdbkLimits(maxEventsPerChunk = 24)).backup)
        assertNotNull(read(over, WdbkLimits(maxEventsPerChunk = 25)).backup)
    }

    @Test fun `default limits are the documented conservative values`() {
        val d = WdbkLimits.DEFAULT
        assertEquals(100L * 1024 * 1024, d.maxContainerBytes)
        assertEquals(BackupInputReader.MAX_BACKUP_INPUT_BYTES, d.maxContainerBytes)
        assertEquals(4096, d.maxEntryCount)
        assertEquals(64L * 1024 * 1024, d.maxEntryUncompressedBytes)
        assertEquals(256L * 1024 * 1024, d.maxTotalUncompressedBytes)
        assertEquals(4L * 1024 * 1024, d.maxManifestBytes)
        assertEquals(10_000, d.maxEventsPerChunk)
        assertEquals(2_000, WdbkLimits.EVENTS_PER_CHUNK)
        assertTrue(WdbkLimits.EVENTS_PER_CHUNK <= d.maxEventsPerChunk)
    }

    @Test fun `the writer refuses to emit an entry the reader could not open`() {
        // An over-bound section fails at export time, not as a surprise at restore time.
        val huge = "x".repeat(5_000)
        val big = RecoveryTestFixtures.v2Backup(
            lyrics = listOf(com.launchpoint.wavdrop.data.backup.BackupLyricsOverride(1L, "content://media/1", huge, 1L)),
        )
        try {
            kotlinx.coroutines.runBlocking { WdbkWriter(maxEntryBytes = 4_096).write(WdbkExportSnapshot.fromBackup(big), ByteArrayOutputStream()) }
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test fun `streamed input is bounded without reading it whole`() {
        // The reader consumes a plain InputStream sequentially (no seek, no full-file byte array on its side).
        var reads = 0
        val stream = object : java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return super.read(b, off, minOf(len, 1024))
            }
        }
        assertNull(WdbkReader().read(stream).error)
        assertTrue(reads > 1)
    }

    @Test fun `rezipped container with reversed order still respects bounds`() {
        val reversed = rezip(bytes) { it.reverse() }
        assertNotNull(read(reversed, WdbkLimits(maxEntryCount = files.size)).backup)
        assertNull(read(reversed, WdbkLimits(maxEntryCount = files.size - 1)).backup)
    }
}
