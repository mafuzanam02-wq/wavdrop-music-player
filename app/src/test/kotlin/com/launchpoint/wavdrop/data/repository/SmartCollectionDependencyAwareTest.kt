package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder.Dependency
import com.launchpoint.wavdrop.data.smart.SmartCollectionsAssembler
import java.io.File
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-06: the shared Smart Collections pipeline is dependency-aware. Rule semantics are untouched (rankedEligibleSongs is the same
 * code, covered by SmartCollectionBuilderTest / SmartCollectionCompletionTest); these tests prove (a) the dependency matrix is true
 * of the rules, (b) incremental assembly equals the monolithic build for every random input sequence, (c) which types are evaluated
 * per upstream change, and (d) type-scoped detail flows only touch the inputs they need.
 */
class SmartCollectionDependencyAwareTest {

    private val day = 24L * 60 * 60 * 1_000L
    private val now0 = 1_800_000_000_000L
    private val allTypes = SmartCollectionType.values().toList()

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun song(id: Long, duration: Long = 200_000L, title: String = "T${id % 7}", added: Long = id) = Song(
        id = id, title = title, artist = "A", album = "B", albumId = 0L, duration = duration,
        uri = "content://media/$id", dateAdded = added, trackNumber = 0, year = 2020,
    )

    private fun stat(id: Long, plays: Int = 0, skips: Int = 0, lastPlayed: Long = 0, lastListened: Long = 0, fav: Boolean = false) =
        TrackStatsEntity(songId = id, contentUri = "u$id", playCount = plays, skipCount = skips, lastPlayedAt = lastPlayed, lastListenedAt = lastListened, isFavorite = fav)

    private fun comp(id: Long, plays: Int, skips: Int, valid: Int, avg: Float) = SongCompletionSummary(id, plays, skips, valid, avg)

    private class Data(val songs: List<Song>, val stats: List<TrackStatsEntity>, val completions: List<SongCompletionSummary>, val now: Long)

    private fun randomSongs(rnd: Random, n: Int) = (1L..n).map {
        song(it, duration = listOf(89_999L, 90_000L, 90_001L, 419_999L, 420_000L, 200_000L)[rnd.nextInt(6)], title = "T${rnd.nextInt(5)}", added = if (rnd.nextInt(4) == 0) 0L else rnd.nextInt(20).toLong())
    }

    private fun randomStats(rnd: Random, n: Int, now: Long) = (1L..n + 5).filter { rnd.nextInt(4) != 0 }.map { id -> // ids > n are orphans
        val boundary = now - 60 * day
        stat(
            id, plays = rnd.nextInt(9), skips = rnd.nextInt(4),
            lastPlayed = listOf(0L, boundary - 1, boundary, boundary + 1, now - day)[rnd.nextInt(5)],
            lastListened = if (rnd.nextBoolean()) 0L else now - rnd.nextInt(100) * day, fav = rnd.nextInt(4) == 0,
        )
    }

    private fun randomCompletions(rnd: Random, n: Int) = (1L..n + 5).filter { rnd.nextInt(3) != 0 }.map { id ->
        comp(id, rnd.nextInt(7), rnd.nextInt(7), rnd.nextInt(7), listOf(0f, 0.39f, 0.4f, 0.84f, 0.85f, 1f)[rnd.nextInt(6)])
    }

    private fun randomData(rnd: Random, n: Int, now: Long = now0) = Data(randomSongs(rnd, n), randomStats(rnd, n, now), randomCompletions(rnd, n), now)

    // ── (a) the dependency matrix is true of the actual rules ────────────────────────────────────────────────────────────

    @Test fun everyTypeIgnoresTheInputsItsDependencyFamilyDoesNotListAndDependenciesCoverAllTypes() {
        assertEquals(allTypes.toSet(), Dependency.values().flatMap { SmartCollectionBuilder.typesWith(it) }.toSet())
        val rnd = Random(1)
        repeat(120) {
            val a = randomData(rnd, 30 + rnd.nextInt(40))
            val b = randomData(rnd, a.songs.size).let { Data(a.songs, it.stats, it.completions, a.now + 70 * day) } // other stats/completions/now
            for (type in allTypes) {
                fun result(stats: List<TrackStatsEntity>, comps: List<SongCompletionSummary>, now: Long) = SmartCollectionBuilder.songsResultFor(type, a.songs, stats, comps, now)
                val base = result(a.stats, a.completions, a.now)
                when (SmartCollectionBuilder.dependencyOf(type)) {
                    Dependency.SONGS_ONLY -> assertEquals("$type", base, result(b.stats, b.completions, b.now))
                    Dependency.STATS -> { assertEquals("$type", base, result(a.stats, b.completions, b.now)) }
                    Dependency.STATS_AND_TIME -> assertEquals("$type", base, result(a.stats, b.completions, a.now))
                    Dependency.COMPLETION -> assertEquals("$type", base, result(b.stats, a.completions, b.now))
                }
            }
        }
    }

    // ── (b) incremental assembly == monolithic build ────────────────────────────────────────────────────────────────────

    @Test fun incrementalAssemblyEqualsTheMonolithicBuildAfterEveryRandomEvent() {
        val rnd = Random(2026)
        repeat(60) { run ->
            val size = listOf(20, 60, 160)[run % 3] // 160 overflows the NEVER_PLAYED / ALWAYS_FINISH / USUALLY_ABANDON caps
            var d = randomData(rnd, size)
            val assembler = SmartCollectionsAssembler()
            assertNull(assembler.onSongs(d.songs)); assertNull(assembler.onStats(d.stats)); assertNull(assembler.onCompletions(d.completions))
            var out = assembler.onDay(d.now)!!
            assertEquals("initial#$run", SmartCollectionBuilder.build(d.songs, d.stats, d.completions, d.now), out)
            repeat(25) { step ->
                when (rnd.nextInt(4)) {
                    0 -> { val s = randomSongs(rnd, if (rnd.nextBoolean()) size else size - rnd.nextInt(size / 2)); d = Data(s, d.stats, d.completions, d.now); out = assembler.onSongs(s)!! }
                    1 -> { val s = randomStats(rnd, size, d.now); d = Data(d.songs, s, d.completions, d.now); out = assembler.onStats(s)!! }
                    2 -> { val c = randomCompletions(rnd, size); d = Data(d.songs, d.stats, c, d.now); out = assembler.onCompletions(c)!! }
                    else -> { val n = d.now + (rnd.nextInt(5) - 1) * 20 * day; d = Data(d.songs, d.stats, d.completions, n); out = assembler.onDay(n)!! }
                }
                val expected = SmartCollectionBuilder.build(d.songs, d.stats, d.completions, d.now)
                assertEquals("run#$run step#$step", expected, out)
                assertEquals("canonical enum order, empties omitted", out.map { it.type }, allTypes.filter { t -> out.any { it.type == t } })
            }
        }
    }

    @Test fun capsAndTotalEligibleCountsSurviveTheDependencyAwareRefactor() {
        val songs = (1L..160L).map { song(it) }
        val comps = songs.map { comp(it.id, 5, 0, 5, 0.9f) } // every song qualifies for ALWAYS_FINISH
        val out = SmartCollectionsAssembler().also { it.onSongs(songs); it.onStats(emptyList()); it.onCompletions(comps) }.onDay(now0)!!
        val af = out.first { it.type == SmartCollectionType.ALWAYS_FINISH }
        assertEquals(50, af.songCount); assertEquals(160, af.totalEligibleCount); assertEquals(50, af.visibleLimit); assertTrue(af.isCapped)
        val never = out.first { it.type == SmartCollectionType.NEVER_PLAYED } // no stats rows -> every song never played
        assertEquals(100, never.songCount); assertEquals(160, never.totalEligibleCount)
    }

    @Test fun orphanStatsAndCompletionsNeverCreateCollections() {
        val songs = listOf(song(1, duration = 200_000L))
        val out = SmartCollectionsAssembler().also {
            it.onSongs(songs); it.onStats(listOf(stat(99, plays = 50, fav = true, lastListened = now0, skips = 9))); it.onCompletions(listOf(comp(99, 9, 0, 9, 1f)))
        }.onDay(now0)!!
        assertEquals(setOf(SmartCollectionType.NEVER_PLAYED, SmartCollectionType.RECENTLY_ADDED), out.map { it.type }.toSet())
    }

    // ── (c) which types are evaluated per upstream change ───────────────────────────────────────────────────────────────

    private fun primed(songCount: Int = 40): Triple<SmartCollectionsAssembler, MutableList<SmartCollectionType>, Data> {
        val seen = mutableListOf<SmartCollectionType>()
        val d = randomData(Random(5), songCount)
        val a = SmartCollectionsAssembler { seen += it }
        a.onSongs(d.songs); a.onStats(d.stats); a.onCompletions(d.completions); a.onDay(d.now)
        assertEquals("first complete evaluation covers every type exactly once", allTypes.sorted(), seen.sorted())
        seen.clear()
        return Triple(a, seen, d)
    }

    private fun types(vararg d: Dependency) = d.flatMap { SmartCollectionBuilder.typesWith(it) }.sorted()

    @Test fun songLibraryChangeReevaluatesEveryFamily() {
        val (a, seen, d) = primed(); a.onSongs(d.songs.drop(1)); assertEquals(allTypes.sorted(), seen.sorted())
    }

    @Test fun statsChangeEvaluatesOnlyStatsAndForgottenFamilies() {
        val (a, seen, d) = primed(); a.onStats(d.stats.drop(1))
        assertEquals(types(Dependency.STATS, Dependency.STATS_AND_TIME), seen.sorted())
        assertTrue(seen.none { SmartCollectionBuilder.dependencyOf(it) == Dependency.SONGS_ONLY || SmartCollectionBuilder.dependencyOf(it) == Dependency.COMPLETION })
    }

    @Test fun completionChangeEvaluatesOnlyTheTwoCompletionCollections() {
        val (a, seen, d) = primed(); a.onCompletions(d.completions.drop(1))
        assertEquals(listOf(SmartCollectionType.ALWAYS_FINISH, SmartCollectionType.USUALLY_ABANDON).sorted(), seen.sorted())
    }

    @Test fun dayTickEvaluatesOnlyForgottenGems() {
        val (a, seen, d) = primed(); a.onDay(d.now + day)
        assertEquals(listOf(SmartCollectionType.FORGOTTEN_GEMS), seen)
    }

    @Test fun aLargeLibraryCompletionChangeDoesNotEvaluateTheOtherNineCollections() {
        val (a, seen, d) = primed(songCount = 2_000)
        a.onCompletions(d.completions.map { it.copy(nativePlays = it.nativePlays + 1) })
        assertEquals(2, seen.size)
        a.onDay(d.now + day)
        assertEquals(3, seen.size) // + Forgotten Gems only
    }

    // ── flow level: the same guarantees through the real flow functions ─────────────────────────────────────────────────

    private fun <T> withCollected(flow: Flow<T>, block: suspend (latest: () -> T?) -> Unit) = runBlocking {
        var latest: T? = null
        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch { flow.collect { latest = it } }
        try { block { latest } } finally { job.cancel() }
    }

    @Test fun theAllCollectionsFlowRoutesEachInputToItsOwnFamilyOnly() {
        val d = randomData(Random(9), 50)
        val songs = MutableStateFlow(d.songs); val stats = MutableStateFlow(d.stats)
        val comps = MutableStateFlow(d.completions); val dayFlow = MutableStateFlow(d.now)
        val seen = mutableListOf<SmartCollectionType>()
        withCollected(observeSmartCollectionsFromInputs(songs, stats, comps, dayFlow) { seen += it }) { latest ->
            assertEquals(allTypes.size, seen.size); seen.clear()
            comps.value = d.completions.drop(1)
            assertEquals(listOf(SmartCollectionType.ALWAYS_FINISH, SmartCollectionType.USUALLY_ABANDON).sorted(), seen.sorted()); seen.clear()
            dayFlow.value = d.now + day
            assertEquals(listOf(SmartCollectionType.FORGOTTEN_GEMS), seen); seen.clear()
            stats.value = d.stats.drop(1)
            assertEquals(types(Dependency.STATS, Dependency.STATS_AND_TIME), seen.sorted()); seen.clear()
            assertEquals(SmartCollectionBuilder.build(d.songs, d.stats.drop(1), d.completions.drop(1), d.now + day), latest())
        }
    }

    // ── (d) type-scoped detail flows ────────────────────────────────────────────────────────────────────────────────────

    private class Probe {
        val stats = MutableStateFlow(emptyList<TrackStatsEntity>()); val comps = MutableStateFlow(emptyList<SongCompletionSummary>())
        val day = MutableStateFlow(now0Static)
        var statsRequested = 0; var compsRequested = 0; var dayRequested = 0; var evaluations = 0
        companion object { const val now0Static = 1_800_000_000_000L }
    }

    private fun detail(type: SmartCollectionType, songs: Flow<List<Song>>, p: Probe) = smartCollectionResultFlow(
        type, songs, { p.statsRequested++; p.stats }, { p.compsRequested++; p.comps }, { p.dayRequested++; p.day }, { p.evaluations++ },
    )

    @Test fun eachDetailTypeSubscribesOnlyToItsInputsAndReevaluatesOnlyForThem() {
        val songs = MutableStateFlow((1L..5L).map { song(it) })
        data class Expect(val stats: Boolean, val comps: Boolean, val day: Boolean)
        val expected = mapOf(
            SmartCollectionType.LONG_TRACKS to Expect(false, false, false),
            SmartCollectionType.RECENTLY_ADDED to Expect(false, false, false),
            SmartCollectionType.SHORT_TRACKS to Expect(false, false, false),
            SmartCollectionType.FAVORITES to Expect(true, false, false),
            SmartCollectionType.MOST_PLAYED to Expect(true, false, false),
            SmartCollectionType.NEVER_PLAYED to Expect(true, false, false),
            SmartCollectionType.FORGOTTEN_GEMS to Expect(true, false, true),
            SmartCollectionType.ALWAYS_FINISH to Expect(false, true, false),
            SmartCollectionType.USUALLY_ABANDON to Expect(false, true, false),
        )
        for ((type, e) in expected) {
            val p = Probe()
            withCollected(detail(type, songs, p)) { _ ->
                assertEquals("$type stats requested", e.stats, p.statsRequested > 0)
                assertEquals("$type completions requested", e.comps, p.compsRequested > 0)
                assertEquals("$type day requested", e.day, p.dayRequested > 0)
                val base = p.evaluations
                p.stats.value = listOf(stat(1, plays = 1)); assertEquals("$type stats emission", if (e.stats) base + 1 else base, p.evaluations)
                val afterStats = p.evaluations
                p.comps.value = listOf(comp(1, 3, 0, 3, 1f)); assertEquals("$type completion emission", if (e.comps) afterStats + 1 else afterStats, p.evaluations)
                val afterComps = p.evaluations
                p.day.value = p.day.value + day; assertEquals("$type day emission", if (e.day) afterComps + 1 else afterComps, p.evaluations)
            }
        }
    }

    @Test fun detailResultsEqualTheMonolithicResultForEveryType() {
        val rnd = Random(11)
        repeat(30) {
            val d = randomData(rnd, 40 + rnd.nextInt(130))
            for (type in allTypes) {
                val flow = smartCollectionResultFlow(type, MutableStateFlow(d.songs), { MutableStateFlow(d.stats) }, { MutableStateFlow(d.completions) }, { MutableStateFlow(d.now) })
                val got = runBlocking { flow.first() }
                assertEquals("$type", SmartCollectionBuilder.songsResultFor(type, d.songs, d.stats, d.completions, d.now), got)
            }
        }
    }

    // ── guards ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theRepositoryNoLongerRunsTheMonolithicAllElevenBuildOrOneSharedFlowPerDetail() {
        val src = File("src/main/kotlin/com/launchpoint/wavdrop/data/repository/SmartCollectionRepository.kt").readText()
            .lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }.joinToString("\n")
        assertFalse("combine-everything monolith must be gone", src.contains("SmartCollectionBuilder.build("))
        assertFalse(src.contains("songsResultFor("))
        assertTrue(src.contains("smartCollectionResultFlow("))
        assertTrue(src.contains("SmartCollectionsAssembler("))
    }
}
