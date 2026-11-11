package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.PendingHistoryTestData.history
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkLayout
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkLimits
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.bytesOf
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.mutateEntryDescriptor
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.mutateManifest
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.read
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.replaceEntryConsistently
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.rezip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.unzip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.write
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-STATS-17B - the portable pending-history FORMAT, in both packagings, through the production writer / reader / exporter / parser.
 * Pure JVM (no database): what crosses a backup file, how it is validated, and how it degrades for readers that cannot use it.
 */
class PendingHistoryFormatTest {

    private fun full(h: PendingHistoryBackup?) = WdbkTestSupport.fullBackup(eventCount = 4).copy(pendingHistory = h)
    private val sorted = { h: PendingHistoryBackup -> h.copy(events = h.events.sortedBy { it.originFingerprint }) }

    private fun wdbk(h: PendingHistoryBackup? = history(), chunk: Int = 2) = write(full(h), eventsPerChunk = chunk).first

    private fun assertRejected(r: WavdropBackupImportResult, message: String? = null) {
        assertNull("no partial backup may reach restore", r.backup)
        assertNotNull(r.error)
        assertEquals(BackupIntegrityStatus.INVALID, r.integrityStatus)
        if (message != null) assertEquals(message, r.error)
    }

    private val pendingPaths = { entries: Collection<String> -> entries.filter { it.startsWith("extensions/pending-history") } }

    // ── .wdbk: shape and round trip ───────────────────────────────────────────

    @Test fun `wdbk - pending history round-trips through the production writer and reader across several chunks`() {
        val h = PendingHistoryTestData.manyEvents(5)
        val bytes = wdbk(h, chunk = 2)
        val entries = unzip(bytes)
        assertEquals(
            listOf("extensions/pending-history-events-000000.json", "extensions/pending-history-events-000001.json",
                "extensions/pending-history-events-000002.json", "extensions/pending-history.json"),
            pendingPaths(entries.keys),
        )
        val r = read(bytes)
        assertNull(r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        assertEquals(sorted(h), r.backup!!.pendingHistory)
        assertTrue("a supported section produces no warning", r.warnings.isEmpty())
    }

    @Test fun `wdbk - every pending entry is optional and the manifest declares no new capability`() {
        val entries = unzip(wdbk())
        val manifest = JSONObject(String(entries.getValue(WdbkLayout.MANIFEST), Charsets.UTF_8))
        val descriptors = manifest.getJSONArray("entries")
        var seen = 0
        for (i in 0 until descriptors.length()) {
            val d = descriptors.getJSONObject(i)
            if (!d.getString("path").startsWith("extensions/pending-history")) continue
            seen++
            assertFalse("optional, so an older reader may skip it", d.getBoolean("required"))
            assertEquals(1, d.getInt("sectionVersion"))
        }
        assertTrue(seen >= 2)
        assertEquals(0, manifest.getJSONArray("requiredCapabilities").length())
        assertEquals(0, manifest.getJSONArray("optionalCapabilities").length())
    }

    @Test fun `wdbk - the primary semantic fingerprint and live sections are identical with and without pending history`() {
        val without = write(full(null), eventsPerChunk = 2)
        val with = write(full(history()), eventsPerChunk = 2)
        assertEquals("pending history is outside the primary fingerprint", without.second.fingerprint, with.second.fingerprint)
        val a = read(without.first).backup!!
        val b = read(with.first).backup!!
        assertEquals(a.songs, b.songs); assertEquals(a.trackStats, b.trackStats); assertEquals(a.listenEvents, b.listenEvents)
        assertEquals(a.lyricsOverrides, b.lyricsOverrides); assertEquals(a.importBaselines, b.importBaselines); assertEquals(a.playlists, b.playlists)
        assertEquals(a.desktopOverlay?.rawJson, b.desktopOverlay?.rawJson)
        assertNotNull("the Desktop overlay is still preserved verbatim", b.desktopOverlay)
    }

    @Test fun `wdbk - no pending section when nothing is pending, and such a backup is unchanged and still restores`() {
        for (h in listOf(null, PendingHistoryBackup.EMPTY)) {
            val bytes = wdbk(h)
            assertTrue(pendingPaths(unzip(bytes).keys).isEmpty())
            val r = read(bytes)
            assertNull(r.error)
            assertNull(r.backup!!.pendingHistory)
            assertTrue(r.warnings.isEmpty())
        }
        assertTrue("byte-for-byte the same container as a backup that never knew the feature",
            wdbk(null).contentEquals(wdbk(PendingHistoryBackup.EMPTY)))
    }

    @Test fun `wdbk - pending entries carry no eventId and no source, nothing is invented`() {
        val entries = unzip(wdbk())
        val text = entries.filterKeys { it.startsWith("extensions/pending-history") }.values.joinToString { String(it, Charsets.UTF_8) }
        assertFalse(text.contains("eventId")); assertFalse(text.contains("\"source\""))
        val stats = JSONObject(String(entries.getValue(WdbkLayout.PENDING_HISTORY), Charsets.UTF_8)).getJSONArray("stats")
        val v1Sourced = (0 until stats.length()).map { stats.getJSONObject(it) }.single { it.getInt("playCount") == 40 }
        assertFalse("a null lastListenedAt is omitted, never written as 0", v1Sourced.has("lastListenedAt"))
        val v2Sourced = (0 until stats.length()).map { stats.getJSONObject(it) }.single { it.getInt("playCount") == 9 }
        assertEquals(1_600_000_050_000L, v2Sourced.getLong("lastListenedAt"))
    }

    // ── .wdbk: integrity and malformed content ────────────────────────────────

    @Test fun `wdbk - a tampered pending entry (hash mismatch) is rejected`() {
        val bytes = rezip(wdbk()) { e ->
            val i = e.indexOfFirst { it.first == "extensions/pending-history-events-000000.json" }
            e[i] = e[i].first to String(e[i].second, Charsets.UTF_8).replace("60000", "60001").toByteArray(Charsets.UTF_8)
        }
        assertRejected(read(bytes), WavdropBackupParser.INTEGRITY_ERROR)
    }

    @Test fun `wdbk - a removed or extra pending entry is rejected`() {
        assertRejected(read(rezip(wdbk()) { e -> e.removeAll { it.first == "extensions/pending-history-events-000001.json" } }))
        assertRejected(read(rezip(wdbk()) { e -> e.removeAll { it.first == WdbkLayout.PENDING_HISTORY } }))
    }

    private fun main(e: MutableList<Pair<String, ByteArray>>) = JSONObject(String(e.bytesOf(WdbkLayout.PENDING_HISTORY), Charsets.UTF_8))
    private fun reseal(e: MutableList<Pair<String, ByteArray>>, path: String, json: Any) =
        replaceEntryConsistently(e, path, json.toString().toByteArray(Charsets.UTF_8))

    private fun malformedMain(label: String, change: (JSONObject) -> Unit) {
        val bytes = rezip(wdbk()) { e -> val m = main(e); change(m); reseal(e, WdbkLayout.PENDING_HISTORY, m) }
        assertRejected(read(bytes))
        assertNull("$label must not leave a partial backup", read(bytes).backup)
    }

    private fun malformedChunk(change: (JSONArray) -> Unit) {
        val path = "extensions/pending-history-events-000000.json"
        val bytes = rezip(wdbk()) { e ->
            val a = JSONArray(String(e.bytesOf(path), Charsets.UTF_8)); change(a); reseal(e, path, a)
        }
        assertRejected(read(bytes))
    }

    @Test fun `wdbk - structurally valid JSON that breaks the section contract is rejected, each case on its own`() {
        malformedMain("duplicate origin key") { m -> val t = m.getJSONArray("tracks"); t.put(JSONObject(t.getJSONObject(0).toString())) }
        malformedMain("originKey not matching its fingerprint and song id") { m -> m.getJSONArray("tracks").getJSONObject(0).put("originKey", "x|1") }
        malformedMain("unknown status") { m -> m.getJSONArray("tracks").getJSONObject(0).put("status", "MERGED") }
        malformedMain("stats for an unknown track") { m -> m.getJSONArray("stats").getJSONObject(0).put("track", "nope|9") }
        malformedMain("two stats rows for one track") { m -> val s = m.getJSONArray("stats"); s.put(JSONObject(s.getJSONObject(0).toString())) }
        malformedMain("negative count") { m -> m.getJSONArray("stats").getJSONObject(0).put("playCount", -1) }
        malformedMain("decimal where an integer is required") { m -> m.getJSONArray("stats").getJSONObject(0).put("playCount", 4.5) }
        malformedMain("negative timestamp") { m -> m.getJSONArray("tracks").getJSONObject(0).put("restoredAt", -5) }
        malformedMain("count out of int range") { m -> m.getJSONArray("stats").getJSONObject(0).put("playCount", 3_000_000_000L) }
        malformedMain("duplicate lyrics fingerprint") { m -> val l = m.getJSONArray("lyricsOverrides"); l.put(JSONObject(l.getJSONObject(0).toString())) }
        malformedMain("duplicate playlist-entry fingerprint") { m -> val p = m.getJSONArray("playlistEntries"); p.getJSONObject(1).put("originFingerprint", p.getJSONObject(0).getString("originFingerprint")) }
        malformedMain("playlist entry for an unknown track") { m -> m.getJSONArray("playlistEntries").getJSONObject(0).put("track", "nope|9") }
        malformedMain("missing array") { m -> m.remove("importBaselines") }
        malformedMain("blank key") { m -> m.getJSONArray("importBaselines").getJSONObject(0).put("originFingerprint", " ") }
        malformedMain("oversized key") { m -> m.getJSONArray("importBaselines").getJSONObject(0).put("originFingerprint", "k".repeat(PendingHistoryLimits.MAX_KEY_CHARS + 1)) }
        malformedMain("declared event count does not match the chunks") { m -> m.put("eventCount", 99) }
        malformedMain("declared chunk count does not match the entries") { m -> m.put("eventChunkCount", 7) }
        malformedMain("body version disagrees with the descriptor") { m -> m.put("schemaVersion", 2) }
        malformedMain("non-integer schema version") { m -> m.put("schemaVersion", "1") }
    }

    @Test fun `wdbk - malformed event chunks are rejected`() {
        malformedChunk { a -> a.getJSONObject(0).put("track", "nope|9") }                 // child without a parent
        malformedChunk { a -> a.put(JSONObject(a.getJSONObject(0).toString())) }          // duplicate fingerprint (and count mismatch)
        malformedChunk { a -> a.getJSONObject(0).put("listenedMs", -1) }
        malformedChunk { a -> a.getJSONObject(0).remove("eventType") }
        malformedChunk { a -> a.getJSONObject(0).put("occurredAt", "1000") }
    }

    @Test fun `wdbk - an event chunk bigger than the reader limit is rejected before it is mapped`() {
        val bytes = wdbk(PendingHistoryTestData.manyEvents(3), chunk = 3)
        assertNull("control: accepted at the default limit", read(bytes).error)
        assertRejected(read(bytes, WdbkLimits(maxEventsPerChunk = 2)))
    }

    // ── .wdbk: how readers that cannot use the section behave ─────────────────

    @Test fun `wdbk - a newer optional version is skipped with one warning, and the rest of the backup restores`() {
        val bytes = rezip(wdbk()) { e ->
            for (p in pendingPaths(e.map { it.first })) mutateEntryDescriptor(e, p) { it.put("sectionVersion", 2) }
            // a version-2 body need not look like version 1 at all
            replaceEntryConsistently(e, WdbkLayout.PENDING_HISTORY, """{"schemaVersion":2,"somethingNew":[1,2,3]}""".toByteArray())
        }
        val r = read(bytes)
        assertNull(r.error)
        assertNull("an unknown version is never mistaken for supported data", r.backup!!.pendingHistory)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.single().contains("quarantined history"))
        assertEquals(full(null).songs, r.backup!!.songs)
        assertEquals(full(null).trackStats, r.backup!!.trackStats)
    }

    @Test fun `wdbk - the same newer version declared REQUIRED is rejected`() {
        val bytes = rezip(wdbk()) { e ->
            mutateEntryDescriptor(e, WdbkLayout.PENDING_HISTORY) { it.put("sectionVersion", 2); it.put("required", true) }
        }
        assertRejected(read(bytes))
    }

    @Test fun `wdbk - an unknown required entry still fails closed, even next to a valid pending section`() {
        val extra = "{}".toByteArray()
        val bytes = rezip(wdbk()) { e ->
            e.add(0, "extensions/pending-history-v2.json" to extra)
            mutateManifest(e) { m ->
                m.getJSONArray("entries").put(
                    JSONObject().put("path", "extensions/pending-history-v2.json").put("section", "pendingHistoryV2")
                        .put("sectionVersion", 1).put("required", true)
                        .put("byteLength", extra.size.toLong()).put("sha256", WdbkWriter.sha256Hex(extra)),
                )
            }
        }
        assertRejected(read(bytes))
    }

    @Test fun `wdbk - the reader's generic unknown-optional-entry rule is exactly what an older reader applies to the new entries`() {
        // Same mechanics as an unknown future extension: hashed, declared optional, ignored with the standard warning.
        val extra = "{}".toByteArray()
        val bytes = rezip(write(full(null), eventsPerChunk = 2).first) { e ->
            e.add(0, "extensions/pending-history.x" to extra)
            mutateManifest(e) { m ->
                m.getJSONArray("entries").put(
                    JSONObject().put("path", "extensions/pending-history.x").put("section", "somethingElse").put("sectionVersion", 1)
                        .put("required", false).put("byteLength", extra.size.toLong()).put("sha256", WdbkWriter.sha256Hex(extra)),
                )
            }
        }
        val r = read(bytes)
        assertNull(r.error)
        assertEquals(listOf("This backup includes optional data this version of Wavdrop does not understand; it was ignored."), r.warnings)
    }

    @Test fun `wdbk - a desktop overlay and pending history coexist and both survive`() {
        val r = read(wdbk())
        assertEquals(
            "the overlay is preserved verbatim, including a field this app does not understand",
            JSONObject(WdbkTestSupport.OVERLAY_RAW).toString(), JSONObject(r.backup!!.desktopOverlay!!.rawJson).toString(),
        )
        assertNotNull(r.backup!!.pendingHistory)
    }

    // ── legacy JSON ───────────────────────────────────────────────────────────

    private fun json(h: PendingHistoryBackup?) = WavdropBackupExporterV2.toJson(full(h))

    @Test fun `json - pending history round-trips through the production exporter and parser`() {
        val h = history()
        val r = WavdropBackupParser.parse(json(h))
        assertNull(r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        assertEquals(sorted(h), sorted(r.backup!!.pendingHistory!!))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun `json - the root key is absent when nothing is pending and such a file parses as before`() {
        for (h in listOf(null, PendingHistoryBackup.EMPTY)) {
            val text = json(h)
            assertFalse(JSONObject(text).has("pendingHistory"))
            val r = WavdropBackupParser.parse(text)
            assertNull(r.error); assertNull(r.backup!!.pendingHistory)
        }
    }

    @Test fun `json - the primary v2 fingerprint ignores the extension and the file still verifies`() {
        val a = WavdropBackupIntegrityV2.fingerprint(full(null))
        val b = WavdropBackupIntegrityV2.fingerprint(full(history()))
        assertEquals(a, b)
        assertEquals(JSONObject(json(null)).getJSONObject("integrity").getString("fingerprint"),
            JSONObject(json(history())).getJSONObject("integrity").getString("fingerprint"))
    }

    @Test fun `json - the extension has its own digest, so damage inside it is detected even though the fingerprint cannot see it`() {
        val root = JSONObject(json(history()))
        root.getJSONObject("pendingHistory").getJSONArray("stats").getJSONObject(0).put("playCount", 41)
        val r = WavdropBackupParser.parse(root.toString(2))
        assertNull(r.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, r.error)
        // and the primary sections are still protected exactly as before
        val primary = JSONObject(json(history()))
        primary.getJSONArray("trackStats").getJSONObject(0).put("playCount", 999)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, WavdropBackupParser.parse(primary.toString(2)).error)
    }

    @Test fun `json - the digest detects damage, not forgery - content edited together with its digest is accepted`() {
        // Documented limitation: neither the digest nor the primary fingerprint is a signature.
        val edited = history().let { h -> h.copy(core = h.core.copy(tracks = h.core.tracks.map { it.copy(title = "Edited") })) }
        val r = WavdropBackupParser.parse(json(edited))
        assertNull(r.error)
        assertEquals("Edited", r.backup!!.pendingHistory!!.core.tracks.first().title)
    }

    @Test fun `json - a digest-consistent but structurally invalid section is still rejected`() {
        val h = history()
        val dupKey = h.copy(core = h.core.copy(tracks = h.core.tracks + h.core.tracks.first()))
        val orphan = h.copy(events = h.events + h.events.first().copy(trackOriginKey = "nope|9", originFingerprint = "zzz"))
        val badKey = h.copy(core = h.core.copy(tracks = h.core.tracks.map { it.copy(originKey = "forged") }))
        for ((label, bad) in listOf("duplicate track" to dupKey, "orphan child" to orphan, "forged origin key" to badKey)) {
            val r = WavdropBackupParser.parse(json(bad))
            assertNull("$label", r.backup)
            assertNotNull("$label", r.error)
        }
    }

    @Test fun `json - malformed section shapes are rejected and a newer version is skipped with a warning`() {
        fun parseWith(change: (JSONObject) -> Unit): WavdropBackupImportResult {
            val root = JSONObject(json(history())); change(root); return WavdropBackupParser.parse(root.toString(2))
        }
        assertNotNull(parseWith { it.put("pendingHistory", JSONObject.NULL) }.error)
        assertNotNull(parseWith { it.put("pendingHistory", "oops") }.error)
        assertNotNull(parseWith { it.put("pendingHistory", JSONArray()) }.error)
        assertNotNull(parseWith { it.getJSONObject("pendingHistory").put("schemaVersion", "1") }.error)
        assertNotNull(parseWith { it.getJSONObject("pendingHistory").remove("events") }.error)
        assertNotNull(parseWith { it.getJSONObject("pendingHistory").remove("integrity") }.error)

        val newer = parseWith { it.put("pendingHistory", JSONObject().put("schemaVersion", 2).put("future", true)) }
        assertNull(newer.error)
        assertNull("unknown version is not treated as supported", newer.backup!!.pendingHistory)
        assertEquals(1, newer.warnings.size)
        assertEquals(full(null).trackStats, newer.backup!!.trackStats)
    }

    @Test fun `json - pending events carry no eventId or source`() {
        val text = JSONObject(json(history())).getJSONObject("pendingHistory").getJSONArray("events").toString()
        assertFalse(text.contains("eventId")); assertFalse(text.contains("source"))
    }

    // ── digest ────────────────────────────────────────────────────────────────

    @Test fun `digest - independent of record order, sensitive to every field`() {
        val h = history()
        val shuffled = h.copy(
            core = h.core.copy(tracks = h.core.tracks.reversed(), playlistEntries = h.core.playlistEntries.reversed()),
            events = h.events.reversed(),
        )
        assertEquals(PendingHistoryCodec.digest(h), PendingHistoryCodec.digest(shuffled))
        val mutations = listOf(
            h.copy(events = h.events.map { it.copy(listenedMs = it.listenedMs + 1) }),
            // null (v1-sourced) and 0 (v2, no qualifying listen) are different facts and must hash differently
            h.copy(core = h.core.copy(stats = h.core.stats.map { if (it.lastListenedAt == null) it.copy(lastListenedAt = 0L) else it })),
            h.copy(core = h.core.copy(playlistEntries = h.core.playlistEntries.map { it.copy(sourcePlaylistId = "0") })),
            h.copy(core = h.core.copy(tracks = h.core.tracks.map { it.copy(restoredAt = it.restoredAt + 1) })),
        )
        for (m in mutations) assertFalse(PendingHistoryCodec.digest(h) == PendingHistoryCodec.digest(m))
    }
}
