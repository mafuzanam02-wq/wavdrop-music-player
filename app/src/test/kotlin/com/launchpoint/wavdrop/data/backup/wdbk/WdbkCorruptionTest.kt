package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupInputReader
import com.launchpoint.wavdrop.data.backup.BackupIntegrityStatus
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportResult
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.bytesOf
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.mutateEntryDescriptor
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.mutateManifest
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.read
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.replaceEntryConsistently
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.rezip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.unzip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.write
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.zip
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Untrusted-input behaviour: every malformed/tampered/oversized container fails cleanly with NO backup. */
class WdbkCorruptionTest {

    private val backup: WavdropBackup = WdbkTestSupport.fullBackup(eventCount = 12)
    private val good: ByteArray = write(backup, eventsPerChunk = 5).first

    private fun assertRejected(result: WavdropBackupImportResult, message: String? = null) {
        assertNull("no partial backup may reach restore", result.backup)
        assertNotNull(result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
        if (message != null) assertEquals(message, result.error)
    }

    private fun assertRejected(bytes: ByteArray, message: String? = null) = assertRejected(read(bytes), message)

    private fun assertRejectedAfter(message: String? = null, mutate: (MutableList<Pair<String, ByteArray>>) -> Unit) =
        assertRejected(rezip(good, mutate), message)

    private fun replaceAll(bytes: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val f = from.toByteArray(); val t = to.toByteArray()
        val out = bytes.copyOf()
        var i = 0
        while (i <= out.size - f.size) {
            if (f.indices.all { out[i + it] == f[it] }) { t.copyInto(out, i); i += f.size } else i++
        }
        return out
    }

    @Test fun `control - the unmodified container is accepted`() {
        val ok = read(good)
        assertNull(ok.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, ok.integrityStatus)
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    @Test fun `empty input is rejected`() = assertRejected(ByteArray(0))

    @Test fun `not a zip is rejected`() {
        assertRejected("this is not a zip file at all".toByteArray())
        assertRejected(ByteArray(2_000) { (it * 31).toByte() })
    }

    @Test fun `plain legacy JSON is not a container`() = assertRejected(RecoveryTestFixtures.v2Json().toByteArray())

    @Test fun `zip without a manifest is rejected`() {
        assertRejectedAfter { entries -> entries.removeAll { it.first == "manifest.json" } }
    }

    @Test fun `zip with no entries at all is rejected`() = assertRejected(zip(emptyList()))

    @Test fun `duplicate manifest is rejected`() {
        val entries = unzip(good).map { it.key to it.value }.toMutableList()
        entries.add("manifest.jsox" to entries.bytesOf("manifest.json"))
        assertRejected(replaceAll(zip(entries), "manifest.jsox", "manifest.json"))
    }

    @Test fun `duplicate entry names are rejected`() {
        val entries = unzip(good).map { it.key to it.value }.toMutableList()
        entries.add("sections/songs.jsoX" to entries.bytesOf("sections/songs.json"))
        val dup = replaceAll(zip(entries), "sections/songs.jsoX", "sections/songs.json")
        assertRejected(dup)
    }

    @Test fun `malformed manifest JSON is rejected`() {
        assertRejectedAfter { entries ->
            val i = entries.indexOfFirst { it.first == "manifest.json" }
            entries[i] = "manifest.json" to "{ this is not json".toByteArray()
        }
        assertRejectedAfter { entries ->
            val i = entries.indexOfFirst { it.first == "manifest.json" }
            entries[i] = "manifest.json" to "[1,2,3]".toByteArray()
        }
    }

    @Test fun `wrong format identifier is rejected`() {
        assertRejectedAfter { mutateManifest(it) { m -> m.put("format", "something_else") } }
    }

    @Test fun `unsupported newer container major is rejected with the newer-version message`() {
        val newer = "This backup was created by a newer version of Wavdrop. Update Wavdrop and try again."
        assertRejectedAfter(newer) { mutateManifest(it) { m -> m.put("containerMajor", 2) } }
        // A future major may change the manifest shape entirely; the message must still be the clear one.
        assertRejectedAfter(newer) { entries ->
            val i = entries.indexOfFirst { it.first == "manifest.json" }
            entries[i] = "manifest.json" to """{"format":"wavdrop_wdbk","containerMajor":9,"somethingNew":{}}""".toByteArray()
        }
    }

    @Test fun `a newer logical version inside a container is rejected with the newer-version message`() {
        assertRejectedAfter(WavdropBackupParser.NEWER_VERSION_ERROR) { mutateManifest(it) { m -> m.put("logicalVersion", 3) } }
    }

    @Test fun `unknown required capability is rejected and unknown optional capability only warns`() {
        assertRejectedAfter { mutateManifest(it) { m -> m.put("requiredCapabilities", org.json.JSONArray(listOf("future-thing"))) } }
        val result = read(rezip(good) { mutateManifest(it) { m -> m.put("optionalCapabilities", org.json.JSONArray(listOf("nice-to-have"))) } })
        assertNull(result.error)
        assertTrue(result.warnings.single().contains("nice-to-have"))
    }

    @Test fun `each required section is mandatory`() {
        for (path in WdbkLayout.ALWAYS_PRESENT.values) {
            // Entry gone AND its descriptor gone: the container is self-consistent but lacks a required section.
            assertRejectedAfter { entries ->
                entries.removeAll { it.first == path }
                mutateManifest(entries) { m ->
                    val arr = m.getJSONArray("entries")
                    val keep = org.json.JSONArray()
                    for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("path") != path) keep.put(arr.get(i))
                    m.put("entries", keep)
                }
            }
            // Entry gone but still declared.
            assertRejectedAfter { entries -> entries.removeAll { it.first == path } }
        }
    }

    @Test fun `undeclared payload entry is rejected`() {
        assertRejectedAfter { it.add(0, "sections/extra.json" to "[]".toByteArray()) }
        assertRejectedAfter { it.add(0, "history/listen-events-000099.json" to "[]".toByteArray()) }
    }

    // ── Integrity layers ──────────────────────────────────────────────────────

    @Test fun `wrong entry SHA-256 is rejected as an integrity failure`() {
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            mutateEntryDescriptor(entries, "sections/songs.json") { it.put("sha256", "0".repeat(64)) }
        }
    }

    @Test fun `tampered entry content with the original manifest is an integrity failure not a parse error`() {
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            val i = entries.indexOfFirst { it.first == "sections/songs.json" }
            val text = String(entries[i].second, Charsets.UTF_8).replace("Song 1", "Song X")
            entries[i] = "sections/songs.json" to text.toByteArray()
        }
        // Even content that would not parse reports integrity (the hash is checked before a parse error is surfaced).
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            val i = entries.indexOfFirst { it.first == "sections/songs.json" }
            entries[i] = "sections/songs.json" to "{{{".toByteArray()
        }
    }

    @Test fun `wrong declared entry size is rejected`() {
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            mutateEntryDescriptor(entries, "sections/songs.json") { it.put("byteLength", it.getLong("byteLength") + 1) }
        }
    }

    @Test fun `wrong chunk event count is rejected`() {
        assertRejectedAfter { entries ->
            mutateEntryDescriptor(entries, "history/listen-events-000000.json") { it.put("eventCount", 4) }
        }
        assertRejectedAfter { entries ->
            mutateEntryDescriptor(entries, "history/listen-events-000000.json") { it.put("eventCount", 6) }
        }
    }

    @Test fun `wrong overall section count is rejected`() {
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            mutateManifest(entries) { m -> m.getJSONObject("counts").put("songCount", 99) }
        }
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            mutateManifest(entries) { m -> m.getJSONObject("counts").put("listenEventCount", 1) }
        }
    }

    @Test fun `wrong semantic fingerprint is rejected even when every entry hash is valid`() {
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            mutateManifest(entries) { m -> m.getJSONObject("integrity").put("fingerprint", "f".repeat(64)) }
        }
    }

    @Test fun `content changed together with its hash still fails the semantic fingerprint`() {
        // A tamperer who recomputes the entry hash and manifest length cannot also forge the fingerprint.
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { entries ->
            val songs = String(entries.bytesOf("sections/songs.json"), Charsets.UTF_8).replace("\"year\":2020", "\"year\":1999")
            replaceEntryConsistently(entries, "sections/songs.json", songs.toByteArray())
        }
    }

    @Test fun `identity fields are covered by the fingerprint`() {
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { mutateManifest(it) { m -> m.put("backupId", "tampered") } }
        assertRejectedAfter(WavdropBackupParser.INTEGRITY_ERROR) { mutateManifest(it) { m -> m.put("exportedAt", 1L) } }
    }

    @Test fun `the strict v2 trust rules still apply to WDBK sections`() {
        // Leading-zero opaque id: rejected by the shared section parser even though hash and manifest agree.
        assertRejectedAfter { entries ->
            val songs = String(entries.bytesOf("sections/songs.json"), Charsets.UTF_8).replaceFirst("\"id\":\"1\"", "\"id\":\"01\"")
            replaceEntryConsistently(entries, "sections/songs.json", songs.toByteArray())
        }
        // Duplicate JSON key rejected by the hardened reader.
        assertRejectedAfter { entries ->
            val songs = String(entries.bytesOf("sections/songs.json"), Charsets.UTF_8).replaceFirst("\"uri\":", "\"uri\":\"x\",\"uri\":")
            replaceEntryConsistently(entries, "sections/songs.json", songs.toByteArray())
        }
        // Decimal where a strict integer is required.
        assertRejectedAfter { entries ->
            val stats = String(entries.bytesOf("sections/track-stats.json"), Charsets.UTF_8).replaceFirst("\"playCount\":10", "\"playCount\":10.0")
            replaceEntryConsistently(entries, "sections/track-stats.json", stats.toByteArray())
        }
        // Over-deep nesting.
        assertRejectedAfter { entries ->
            replaceEntryConsistently(entries, "sections/songs.json", ("[".repeat(500) + "]".repeat(500)).toByteArray())
        }
    }

    @Test fun `implausible stats are rejected like in legacy backups`() {
        val absurd = RecoveryTestFixtures.v2Backup(stats = listOf(RecoveryTestFixtures.backupStats(1L, 2_000_000_000, 0, false)))
        assertRejected(write(absurd).first, WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR)
    }

    // ── Container damage ──────────────────────────────────────────────────────

    @Test fun `truncated zip is rejected at every cut point`() {
        for (cut in listOf(good.size - 1, good.size - 10, good.size - 22, good.size - 60, good.size / 2, 100, 31, 4)) {
            assertRejected(good.copyOf(cut))
        }
    }

    @Test fun `trailing garbage after the end record is rejected`() {
        assertRejected(good + ByteArray(40) { 7 })
    }

    @Test fun `corrupted compressed bytes are rejected`() {
        for (at in listOf(60, 90, good.size / 3, good.size / 2)) {
            val broken = good.copyOf()
            broken[at] = (broken[at].toInt() xor 0x5A).toByte()
            assertRejected(broken)
        }
    }

    @Test fun `hostile entry names are rejected`() {
        val payload = unzip(good).map { it.key to it.value }
        for (name in listOf(
            "../evil.json", "sections/../../evil.json", "/etc/passwd", "/sections/songs.json", "C:/x.json",
            "sections\\songs2.json", "sections//songs2.json", "sections/", "./sections/songs2.json", "a".repeat(300),
            "sections/so ngs.json", "sections/s\u00f6ngs.json", "",
        )) {
            assertRejected(zip(payload + (name to "[]".toByteArray())))
        }
    }

    @Test fun `a nested archive is never opened`() {
        val inner = zip(listOf("x.json" to "[]".toByteArray()))
        // As an undeclared entry it is rejected; as the (declared) content of a section it is just invalid JSON.
        assertRejectedAfter { it.add(0, "sections/nested.json" to inner) }
        assertRejectedAfter { entries -> replaceEntryConsistently(entries, "sections/songs.json", inner) }
    }

    @Test fun `failures never expose a partial model`() {
        val results = listOf(
            read(good.copyOf(good.size / 2)),
            read(rezip(good) { mutateManifest(it) { m -> m.put("containerMajor", 5) } }),
            read(rezip(good) { mutateEntryDescriptor(it, "sections/songs.json") { d -> d.put("sha256", "00") } }),
        )
        for (r in results) {
            assertNull(r.backup)
            assertFalse(r.integrityStatus == BackupIntegrityStatus.VERIFIED)
        }
    }

    // ── Minor-version tolerance contract (pinned) ─────────────────────────────

    @Test fun `a higher minor version is tolerated when extras are declared optional and hash-verified`() {
        val extra = """{"anything":true}""".toByteArray()
        val bytes = rezip(good) { entries ->
            entries.add(0, "extensions/future-thing.json" to extra)
            mutateManifest(entries) { m ->
                m.put("containerMinor", 3)
                m.put("someNewManifestField", JSONObject().put("x", 1))
                m.getJSONArray("entries").put(
                    JSONObject().put("path", "extensions/future-thing.json").put("section", "futureThing")
                        .put("sectionVersion", 1).put("required", false)
                        .put("byteLength", extra.size.toLong()).put("sha256", WdbkWriter.sha256Hex(extra)),
                )
            }
        }
        val result = read(bytes)
        assertNull(result.error)
        assertEquals(1, result.warnings.size)
        assertEquals(backup.songs, result.backup!!.songs)
    }

    @Test fun `a higher minor version with an unknown REQUIRED entry is rejected`() {
        val extra = "{}".toByteArray()
        assertRejected(
            rezip(good) { entries ->
                entries.add(0, "sections/future.json" to extra)
                mutateManifest(entries) { m ->
                    m.put("containerMinor", 1)
                    m.getJSONArray("entries").put(
                        JSONObject().put("path", "sections/future.json").put("section", "future")
                            .put("sectionVersion", 1).put("required", true)
                            .put("byteLength", extra.size.toLong()).put("sha256", WdbkWriter.sha256Hex(extra)),
                    )
                }
            },
        )
    }

    @Test fun `an optional entry with a hash mismatch is still rejected`() {
        val extra = "{}".toByteArray()
        assertRejected(
            rezip(good) { entries ->
                entries.add(0, "extensions/future-thing.json" to extra)
                mutateManifest(entries) { m ->
                    m.getJSONArray("entries").put(
                        JSONObject().put("path", "extensions/future-thing.json").put("section", "futureThing")
                            .put("sectionVersion", 1).put("required", false)
                            .put("byteLength", extra.size.toLong()).put("sha256", "0".repeat(64)),
                    )
                }
            },
        )
    }

    @Test fun `an unsupported section version is rejected when required and skipped when optional`() {
        assertRejectedAfter { entries -> mutateEntryDescriptor(entries, "sections/songs.json") { it.put("sectionVersion", 2) } }

        val result = read(rezip(good) { entries -> mutateEntryDescriptor(entries, "extensions/desktop-overlay.json") { it.put("sectionVersion", 2) } })
        assertNull(result.error)
        assertNull("an unreadable optional extension is skipped, not guessed", result.backup!!.desktopOverlay)
        assertEquals(1, result.warnings.size)
    }

    @Test fun `a required section may not be downgraded to optional`() {
        assertRejectedAfter { entries -> mutateEntryDescriptor(entries, "sections/songs.json") { it.put("required", false) } }
    }

    @Test fun `a section declared under the wrong logical section is rejected`() {
        assertRejectedAfter { entries -> mutateEntryDescriptor(entries, "sections/songs.json") { it.put("section", "playlists") } }
    }

    @Test fun `outer-limit message matches the shared too-large wording`() {
        val tiny = WdbkLimits(maxContainerBytes = (good.size - 1).toLong())
        assertRejected(read(good, tiny), BackupInputReader.TOO_LARGE_MESSAGE)
    }
}
