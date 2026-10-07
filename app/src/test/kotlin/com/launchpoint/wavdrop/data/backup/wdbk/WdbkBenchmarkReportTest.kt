package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupImportBaseline
import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.BackupLyricsOverride
import com.launchpoint.wavdrop.data.backup.BackupPlaylist
import com.launchpoint.wavdrop.data.backup.BackupSong
import com.launchpoint.wavdrop.data.backup.BackupTrackStats
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupExporterV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-001 §6.2 evidence generator. Measures legacy v2 JSON vs WDBK size/time on synthetic fixtures and sweeps the
 * history chunk size. It makes NO wall-clock or ratio assertions (results are environment-specific); it asserts only
 * that every fixture round-trips with an identical semantic fingerprint. The report is written to
 * `build/reports/wdbk-benchmark.txt`.
 *
 * Peak memory: the JVM exposes no reliable per-operation peak. Two proxies are reported instead: (1) the largest
 * single contiguous text payload an approach must hold (legacy: the whole document; WDBK: the largest entry), and
 * (2) a sampled used-heap high-water mark taken every 2 ms during the operation. The sampled value INCLUDES garbage not
 * yet collected, so it is an upper-bound-ish indicator, not a measurement of live memory.
 */
class WdbkBenchmarkReportTest {

    private data class Fixture(val name: String, val songs: Int, val events: Int, val playlists: Int)

    private val fixtures = listOf(
        Fixture("small", songs = 60, events = 300, playlists = 3),
        Fixture("medium", songs = 2_000, events = 20_000, playlists = 40),
        Fixture("large (event-heavy)", songs = 4_000, events = 100_000, playlists = 60),
    )

    private val titles = listOf("Midnight City", "Blue in Green", "Hurt", "Take Five", "Windowlicker", "Teardrop", "Karma Police")
    private val artists = listOf("M83", "Miles Davis", "Johnny Cash", "Dave Brubeck", "Aphex Twin", "Massive Attack", "Radiohead")

    private fun build(f: Fixture): WavdropBackup {
        val rnd = Random(42)
        val base = RecoveryTestFixtures.NOW
        val songs = List(f.songs) { i ->
            BackupSong(
                id = 1_000L + i, uri = "content://media/external/audio/media/${1_000 + i}",
                title = "${titles[i % titles.size]} $i", artist = artists[i % artists.size], album = "Album ${i / 12}",
                albumId = 500L + i / 12, duration = 120_000L + rnd.nextInt(300_000), dateAdded = base - rnd.nextInt(1_000_000_000),
                trackNumber = i % 14, year = 1960 + i % 60, folderPath = "Music/${artists[i % artists.size]}/", folderName = artists[i % artists.size],
            )
        }
        val stats = songs.map { s ->
            val plays = rnd.nextInt(200)
            BackupTrackStats(
                s.id, s.uri, plays, rnd.nextInt(20), base - rnd.nextInt(100_000_000), plays * 150_000L, rnd.nextInt(10) == 0,
                lastListenedAt = base - rnd.nextInt(100_000_000),
            )
        }
        val events = List(f.events) { i ->
            val s = songs[rnd.nextInt(songs.size)]
            val play = rnd.nextInt(10) != 0
            BackupListenEvent(
                songId = s.id, contentUri = s.uri, title = s.title, artist = s.artist, album = s.album,
                eventType = if (play) TrackListenEventEntity.TYPE_PLAY else TrackListenEventEntity.TYPE_SKIP,
                occurredAt = base - i * 61_000L - rnd.nextInt(30_000), listenedMs = if (play) 120_000L + rnd.nextInt(60_000) else 4_000L,
                durationMs = s.duration, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
                eventId = java.util.UUID(rnd.nextLong(), rnd.nextLong()).toString(),
            )
        }
        val playlists = List(f.playlists) { p ->
            BackupPlaylist(
                100L + p, "Playlist $p", base - 5_000_000L, base - 1_000_000L,
                List(minOf(60, songs.size)) { pos ->
                    val s = songs[(p * 7 + pos * 13) % songs.size]
                    com.launchpoint.wavdrop.data.backup.BackupPlaylistSong(s.id, s.uri, pos, s.title, s.artist, s.album)
                },
            )
        }
        return RecoveryTestFixtures.v2Backup(
            songs = songs, stats = stats, events = events, playlists = playlists,
            baselines = listOf(BackupImportBaseline(songs.first().id, "blackplayer", "k", 3, 1, base)),
            lyrics = listOf(BackupLyricsOverride(songs.first().id, songs.first().uri, "la la la\nlalala", base)),
            preferences = RecoveryTestFixtures.emptyPrefs().copy(themeMode = "DARK", compactMode = true),
        )
    }

    private class HeapSampler : AutoCloseable {
        private val peak = AtomicLong(0)
        @Volatile private var running = true
        private val thread = Thread {
            val rt = Runtime.getRuntime()
            while (running) {
                peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), ::maxOf)
                try { Thread.sleep(2) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true }

        fun start(): HeapSampler { System.gc(); peak.set(0); thread.start(); return this }
        override fun close() { running = false; thread.join() }
        val peakBytes: Long get() = peak.get()
    }

    private fun <T> measured(block: () -> T): Triple<T, Long, Long> {
        val sampler = HeapSampler().start()
        val t0 = System.nanoTime()
        val result = try { block() } finally { sampler.close() }
        return Triple(result, (System.nanoTime() - t0) / 1_000_000, sampler.peakBytes)
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    private fun mib(b: Long) = "%.1f".format(b / 1048576.0)
    private fun kib(b: Long) = "%.0f".format(b / 1024.0)

    @Test fun `generate size timing and chunk-size evidence`() {
        val report = StringBuilder()
        report.appendLine("WDBK-1 benchmark (synthetic fixtures; environment-specific, no assertions on time or ratio)")
        report.appendLine("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, max heap ${mib(Runtime.getRuntime().maxMemory())} MiB, cores ${Runtime.getRuntime().availableProcessors()}")
        report.appendLine("Chunk size used for the size/time table: ${WdbkLimits.EVENTS_PER_CHUNK} events")
        report.appendLine()
        report.appendLine("| fixture | songs | events | legacy JSON KiB | WDBK KiB | WDBK/JSON | legacy export ms | WDBK export ms | legacy parse ms | WDBK decode+verify ms | legacy largest payload KiB | WDBK largest entry KiB | legacy heap peak MiB* | WDBK write heap peak MiB* |")
        report.appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")

        var largeFixture: WavdropBackup? = null
        for (f in fixtures) {
            val backup = build(f)
            if (f.name.startsWith("large")) largeFixture = backup
            val fingerprint = WavdropBackupIntegrityV2.fingerprint(backup)

            val (json, legacyExportMs, legacyPeak) = measured { WavdropBackupExporterV2.toJson(backup) }
            val jsonBytes = json.toByteArray(Charsets.UTF_8)
            val (legacyParsed, legacyParseMs) = timed { WavdropBackupParser.parse(json, RecoveryTestFixtures.NOW + 1_000_000L) }
            assertNull(legacyParsed.error)
            assertEquals(fingerprint, WavdropBackupIntegrityV2.fingerprint(legacyParsed.backup!!))

            val out = ByteArrayOutputStream()
            val (receipt, wdbkExportMs, wdbkPeak) = measured { kotlinx.coroutines.runBlocking { WdbkWriter().write(WdbkExportSnapshot.fromBackup(backup), out) } }
            val container = out.toByteArray()
            val (decoded, decodeMs) = timed { WdbkReader().read(ByteArrayInputStream(container), RecoveryTestFixtures.NOW + 1_000_000L) }
            assertNull(decoded.error)
            assertEquals(fingerprint, WavdropBackupIntegrityV2.fingerprint(decoded.backup!!))
            assertEquals(backup.listenEvents.size, decoded.backup!!.listenEvents.size)

            val largestEntry = WdbkTestSupport.unzip(container).values.maxOf { it.size }.toLong()
            report.appendLine(
                "| ${f.name} | ${f.songs} | ${f.events} | ${kib(jsonBytes.size.toLong())} | ${kib(container.size.toLong())} | " +
                    "${"%.3f".format(container.size.toDouble() / jsonBytes.size)} | $legacyExportMs | $wdbkExportMs | $legacyParseMs | $decodeMs | " +
                    "${kib(jsonBytes.size.toLong())} | ${kib(largestEntry)} | ${mib(legacyPeak)} | ${mib(wdbkPeak)} |",
            )
            check(receipt.containerBytes == container.size.toLong())
        }
        report.appendLine()
        report.appendLine("* sampled used-heap high-water mark (includes uncollected garbage and the fixture itself); a coarse indicator, NOT a measurement of live memory. The 'largest payload' columns are the deterministic proxy.")

        // Chunk-size sweep on the event-heavy fixture.
        val large = largeFixture!!
        report.appendLine()
        report.appendLine("Chunk-size sweep on the event-heavy fixture (${large.listenEvents.size} events):")
        report.appendLine("| events/chunk | chunks | WDBK KiB | largest history entry KiB | write ms | decode ms |")
        report.appendLine("|---:|---:|---:|---:|---:|---:|")
        for (chunk in listOf(250, 500, 1_000, 2_000, 5_000, 10_000, 25_000)) {
            val out = ByteArrayOutputStream()
            val (_, writeMs) = timed { kotlinx.coroutines.runBlocking { WdbkWriter(chunk).write(WdbkExportSnapshot.fromBackup(large), out) } }
            val bytes = out.toByteArray()
            val (r, readMs) = timed { WdbkReader(WdbkLimits(maxEventsPerChunk = maxOf(chunk, 10_000))).read(ByteArrayInputStream(bytes), RecoveryTestFixtures.NOW + 1_000_000L) }
            assertNull(r.error)
            val files = WdbkTestSupport.unzip(bytes)
            val history = files.filterKeys { it.startsWith("history/") }
            report.appendLine("| $chunk | ${history.size} | ${kib(bytes.size.toLong())} | ${kib(history.values.maxOf { it.size }.toLong())} | $writeMs | $readMs |")
        }

        // True streamed export: events are GENERATED on demand in canonical order and never exist as a list, and the
        // archive is discarded as written, so this isolates what the export path itself keeps alive.
        report.appendLine()
        report.appendLine("True streamed export (events generated lazily; output discarded; no event list exists anywhere):")
        report.appendLine("| events | WDBK KiB | chunks | largest history chunk KiB (uncompressed) | export ms | sampled heap peak MiB* |")
        report.appendLine("|---:|---:|---:|---:|---:|---:|")
        val smallSections = build(Fixture("sections", songs = 4_000, events = 0, playlists = 60))
        for (n in listOf(100_000, 500_000)) {
            val snapshot = WdbkExportSnapshot(
                backupId = smallSections.backupId!!, sourceInstallationId = smallSections.sourceInstallationId!!,
                exportedAtMs = smallSections.exportedAtMs!!, appVersionCode = null, appVersionName = null,
                songs = smallSections.songs, trackStats = smallSections.trackStats, importBaselines = smallSections.importBaselines,
                lyricsOverrides = smallSections.lyricsOverrides, preferences = smallSections.preferences,
                playlists = smallSections.playlists, desktopOverlayRawJson = null,
                openEvents = {
                    var delivered = 0
                    WdbkEventSource { max ->
                        val count = minOf(max, n - delivered)
                        List(count) { k ->
                            val i = delivered + k
                            val s = smallSections.songs[i % smallSections.songs.size]
                            BackupListenEvent(
                                songId = s.id, contentUri = s.uri, title = s.title, artist = s.artist, album = s.album,
                                eventType = TrackListenEventEntity.TYPE_PLAY, occurredAt = RecoveryTestFixtures.NOW + i * 61_000L,
                                listenedMs = 150_000L, durationMs = s.duration,
                                source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = "stream-$i",
                            )
                        }.also { delivered += count }
                    }
                },
            )
            val sink = object : java.io.OutputStream() {
                var bytes = 0L
                override fun write(b: Int) { bytes++ }
                override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
            }
            val (receipt, ms, peak) = measured { kotlinx.coroutines.runBlocking { WdbkWriter().write(snapshot, sink) } }
            assertEquals(n, receipt.manifest.counts.listenEventCount)
            val chunks = receipt.manifest.entries.filter { it.section == "listenEvents" }
            report.appendLine("| $n | ${kib(sink.bytes)} | ${chunks.size} | ${kib(chunks.maxOf { it.byteLength })} | $ms | ${mib(peak)} |")
        }

        val file = File("build/reports/wdbk-benchmark.txt")
        file.parentFile.mkdirs()
        file.writeText(report.toString())
        println(report)
    }
}
