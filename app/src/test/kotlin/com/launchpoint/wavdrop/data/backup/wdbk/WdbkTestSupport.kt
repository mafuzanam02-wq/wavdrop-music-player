package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupImportBaseline
import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.BackupLyricsOverride
import com.launchpoint.wavdrop.data.backup.BackupPlaylist
import com.launchpoint.wavdrop.data.backup.BackupPreferences
import com.launchpoint.wavdrop.data.backup.BackupSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Shared builders and ZIP crafting helpers for the WDBK tests. Pure JVM, no mocks. */
internal object WdbkTestSupport {

    const val NOW = RecoveryTestFixtures.NOW

    fun events(count: Int, songId: Long = 1L, withEventIds: Boolean = true): List<BackupListenEvent> =
        List(count) { i ->
            RecoveryTestFixtures.backupEvent(
                songId = songId,
                eventId = if (withEventIds) "evt-%08d".format(i) else null,
                // Many rows deliberately share the same occurredAt — physical order must never matter.
                at = NOW - 10_000_000L + (i / 3) * 1_000L,
            )
        }

    /** A backup exercising every section, including awkward text, nulls, duplicates and the Desktop overlay. */
    fun fullBackup(eventCount: Int = 7, overlay: Boolean = true): WavdropBackup {
        val songs = listOf(
            RecoveryTestFixtures.backupSong(1L),
            RecoveryTestFixtures.backupSong(9_007_199_254_740_993L, title = "Big \"id\" \\ song é中🎵"),
            BackupSong(3L, "content://media/3", "No folder", "A", "B", 7L, 1L, 2L, 0, 0, null, null),
        )
        return RecoveryTestFixtures.v2Backup(
            songs = songs,
            stats = listOf(
                RecoveryTestFixtures.backupStats(1L, 10, 1, favorite = true),
                RecoveryTestFixtures.backupStats(3L, 0, 0, favorite = false, lastPlayedAt = 0L, lastListenedAt = 0L, listeningMs = 0L),
            ),
            baselines = listOf(BackupImportBaseline(1L, "blackplayer", "key/with\nnewline", 4, 2, NOW - 5_000L)),
            lyrics = listOf(BackupLyricsOverride(1L, "content://media/1", "line one\nline \"two\"\n\ttabbed ☃", NOW - 4_000L)),
            events = events(eventCount) + listOf(
                RecoveryTestFixtures.backupEvent(3L, eventId = null, at = NOW - 99_000L),
                RecoveryTestFixtures.backupEvent(9_007_199_254_740_993L, eventId = "evt-x", at = NOW - 98_000L),
            ),
            playlists = listOf(
                BackupPlaylist(
                    id = 5L, name = "Mix é", createdAt = 10L, updatedAt = 20L,
                    songs = listOf(
                        // Duplicate occurrences of the same song and explicit positions must round-trip untouched.
                        RecoveryTestFixtures.backupPlaylistSong(1L, 0),
                        RecoveryTestFixtures.backupPlaylistSong(3L, 1),
                        RecoveryTestFixtures.backupPlaylistSong(1L, 2),
                    ),
                ),
                BackupPlaylist(id = 6L, name = "Empty", createdAt = 11L, updatedAt = 21L, songs = emptyList()),
            ),
            preferences = BackupPreferences(
                startupDestination = "ALBUMS", mostPlayedPeriod = null, mostPlayedLimit = null,
                homeVisibleSections = listOf("A", "B"), scanMode = null, selectedFolderUris = listOf("content://tree/x"),
                minimumTrackDurationSeconds = 30, compactMode = true, wrappedVisualStyle = "BOLD", showQueueCount = false,
            ),
            overlayRawJson = if (overlay) OVERLAY_RAW else null,
        )
    }

    /** Preserved Desktop overlay including a field Android does not understand. */
    const val OVERLAY_RAW =
        """{"schemaVersion":1,"producer":{"platform":"desktop"},"unknownFutureField":{"keep":[1,2,3]},""" +
            """"trackStats":[{"desktopTrackId":"d1","title":"T","artist":"A","album":"B","durationMs":1000,"playCount":2,"skipCount":0,""" +
            """"totalListeningTimeMs":2000,"lastPlayedAt":5,"lastListenedAt":5,"favorite":true}],"listenEvents":[]}"""

    fun emptyBackup(): WavdropBackup = RecoveryTestFixtures.v2Backup(
        songs = emptyList(), stats = emptyList(), events = emptyList(), playlists = emptyList(),
        lyrics = emptyList(), baselines = emptyList(), preferences = null, overlayRawJson = null,
    )

    fun write(backup: WavdropBackup, eventsPerChunk: Int = WdbkLimits.EVENTS_PER_CHUNK): Pair<ByteArray, WdbkWriteReceipt> {
        val out = ByteArrayOutputStream()
        val receipt = runBlocking { WdbkWriter(eventsPerChunk).write(WdbkExportSnapshot.fromBackup(backup), out) }
        return out.toByteArray() to receipt
    }

    fun read(bytes: ByteArray, limits: WdbkLimits = WdbkLimits.DEFAULT): WavdropBackupImportResult =
        WdbkReader(limits).read(ByteArrayInputStream(bytes), NOW + 1_000_000L)

    /** Entries of a ZIP in physical order. */
    fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                result[e.name] = zip.readBytes()
            }
        }
        return result
    }

    /** Builds a ZIP from entries in the given order. Allows any entry name (including hostile ones). */
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun rezip(bytes: ByteArray, mutate: (MutableList<Pair<String, ByteArray>>) -> Unit): ByteArray {
        val list = unzip(bytes).map { it.key to it.value }.toMutableList()
        mutate(list)
        return zip(list)
    }

    fun mutateManifest(entries: MutableList<Pair<String, ByteArray>>, change: (JSONObject) -> Unit) {
        val i = entries.indexOfFirst { it.first == WdbkLayout.MANIFEST }
        val json = JSONObject(String(entries[i].second, Charsets.UTF_8))
        change(json)
        entries[i] = WdbkLayout.MANIFEST to json.toString().toByteArray(Charsets.UTF_8)
    }

    fun mutateEntryDescriptor(entries: MutableList<Pair<String, ByteArray>>, path: String, change: (JSONObject) -> Unit) =
        mutateManifest(entries) { m ->
            val arr: JSONArray = m.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).getString("path") == path) change(arr.getJSONObject(i))
            }
        }

    fun List<Pair<String, ByteArray>>.bytesOf(name: String): ByteArray = first { it.first == name }.second

    /** Re-seals a mutated entry: replaces its bytes and fixes the manifest length + SHA so ONLY the content differs. */
    fun replaceEntryConsistently(entries: MutableList<Pair<String, ByteArray>>, path: String, newBytes: ByteArray) {
        val i = entries.indexOfFirst { it.first == path }
        entries[i] = path to newBytes
        mutateEntryDescriptor(entries, path) {
            it.put("byteLength", newBytes.size.toLong())
            it.put("sha256", WdbkWriter.sha256Hex(newBytes))
        }
    }

    // ── In-memory SAF stand-ins ───────────────────────────────────────────────

    class MemoryHandle(
        var content: ByteArray? = null,
        var failOpenOutput: Boolean = false,
        var failWriteAfterBytes: Long? = null,
        var failOpenInput: Boolean = false,
        /** Applied to bytes at read time to simulate corruption after a successful write. */
        var corruptOnRead: ((ByteArray) -> ByteArray)? = null,
        var deleted: Boolean = false,
    ) : BackupFileHandle {
        val modes = mutableListOf<String>()

        override fun openOutput(mode: String): OutputStream? {
            modes += mode
            if (failOpenOutput) return null
            val buf = object : ByteArrayOutputStream() {
                override fun close() {
                    content = toByteArray()
                    super.close()
                }
            }
            val limit = failWriteAfterBytes
            return if (limit == null) buf else object : OutputStream() {
                private var written = 0L
                override fun write(b: Int) {
                    if (++written > limit) throw IOException("disk full")
                    buf.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written + len > limit) throw IOException("disk full")
                    written += len
                    buf.write(b, off, len)
                }

                override fun close() = buf.close()
            }
        }

        override fun openInput(): InputStream? {
            if (failOpenInput) return null
            val data = content ?: return null
            return ByteArrayInputStream(corruptOnRead?.invoke(data) ?: data)
        }

        override fun delete(): Boolean {
            deleted = true
            content = null
            return true
        }
    }

    class MemoryFolder : BackupFolder {
        val files = LinkedHashMap<String, MemoryHandle>()
        var failCreate: ((String) -> Boolean)? = null
        var handleFactory: ((String) -> MemoryHandle)? = null

        override fun findChild(name: String): BackupFileHandle? = files[name]?.takeIf { !it.deleted }

        override fun createFile(mimeType: String, name: String): BackupFileHandle? {
            if (failCreate?.invoke(name) == true) return null
            val h = handleFactory?.invoke(name) ?: MemoryHandle()
            files[name] = h
            return h
        }
    }
}
