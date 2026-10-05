package com.launchpoint.wavdrop.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CF-2M4: shape evidence for the NEXT queue graft on a REAL ExoPlayer at queue sizes up to the MEDIA_ITEM_CACHE_MAX_SIZE bound
 * (12,288). Two variants: `trackless` (every item uses a trivial test source) and `production-factory` (every item except B is
 * built by Media3's real DefaultMediaSourceFactory, so per-item media-source construction cost is included; those sources are
 * never prepared, exactly as in production). JVM/Robolectric timings are NOT device latency; the assertions only catch
 * pathological (super-linear) scaling. Each point is the median of several runs. Output: `CF-2M4 perf ...` stdout lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NextSlotPerformanceTest {

    private data class Sample(val size: Int, val to: Int, val timing: NextSlotGraftTiming, val materializeNanos: Long)

    private fun songs(n: Int) = (0 until n).map {
        Song(
            id = it.toLong(), title = "T$it", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
            uri = "content://media/$it", dateAdded = 0L, trackNumber = 0, year = 2020,
        )
    }

    private fun measure(size: Int, to: Int, production: Boolean): Sample {
        val context = RuntimeEnvironment.getApplication()
        val songs = songs(size)
        val m0 = System.nanoTime()
        val items = songs.map { it.toPlaybackMediaItem() } // cold MediaItem materialization (no cache)
        val materializeNanos = System.nanoTime() - m0
        val fake = if (production) to.toString() else null
        val p1 = TestMediaSource.newPlayer(context, SHARED_SESSION_ID, fake)
        val p2 = TestMediaSource.newPlayer(context, SHARED_SESSION_ID, fake)
        val engine = PlayerEngine(context, p1, p2, SHARED_SESSION_ID, AudioAttributes.DEFAULT)
        try {
            val from = if (to == 0) 1 else to - 1
            engine.nextPreparation.request(NextSlotRequest(CrossfadeTransitionKey(1L, from, to), items))
            TestMediaSource.awaitCondition(timeoutMs = 60_000L) { engine.nextPreparation.state !is NextSlotState.PreparingTarget }
            val state = engine.nextPreparation.state
            assertTrue("size=$size to=$to production=$production state=$state", state is NextSlotState.Ready)
            assertEquals(size, p2.mediaItemCount)
            assertEquals(to, p2.currentMediaItemIndex)
            return Sample(size, to, engine.nextPreparation.lastGraftTiming!!, materializeNanos)
        } finally {
            engine.release()
        }
    }

    private fun median(size: Int, to: Int, production: Boolean, runs: Int): Sample =
        (0 until runs).map { measure(size, to, production) }.sortedBy { it.timing.totalNanos }[runs / 2]

    /** Least-noise total (JVM GC pauses only ever add time): used for the scaling ratio, while tables report medians. */
    private val minTotals = HashMap<String, Long>()
    private fun medianTrackingMin(label: String, size: Int, to: Int, production: Boolean, runs: Int): Sample {
        val all = (0 until runs).map { measure(size, to, production) }.sortedBy { it.timing.totalNanos }
        minTotals["$label/$size"] = all.first().timing.totalNanos
        return all[runs / 2]
    }

    private fun ms(nanos: Long) = "%.2f".format(nanos / 1_000_000.0)

    private fun report(label: String, s: Sample) {
        val t = s.timing
        println(
            "CF-2M4 perf [$label] size=${s.size} target=${s.to} before=${t.beforeCount} after=${t.afterCount} chunks=${t.chunkCount} " +
                "maxChunkMs=${ms(t.maxChunkNanos)} medianChunkMs=${ms(t.chunkNanos.sorted()[t.chunkNanos.size / 2])} lastChunkMs=${ms(t.chunkNanos.last())} maxVerifyMs=${ms(t.maxVerifyNanos)} " +
                "materializeColdMs=${ms(s.materializeNanos)} prependMs=${ms(t.prependNanos)} appendMs=${ms(t.appendNanos)} " +
                "verifyMs=${ms(t.verifyNanos)} totalGraftMs=${ms(t.totalNanos)} resultingItems=${s.size}",
        )
    }

    private fun scaling(label: String, production: Boolean) {
        median(1_000, 500, production, 3) // warm-up (JIT, class loading)
        val best = HashMap<Int, Sample>()
        for (size in listOf(2, 10, 100, 1_000, 5_000, 12_288)) {
            val to = if (size == 2) 1 else size / 2
            val sample = medianTrackingMin(label, size, to, production, if (size >= 5_000) 5 else 7)
            best[size] = sample
            report(label, sample)
        }
        report("$label, all-prepend", median(12_288, 12_287, production, 5))
        report("$label, all-append", median(12_288, 1, production, 5))

        val big = best.getValue(12_288).timing
        val expectedChunks = (big.beforeCount + 255) / 256 + (big.afterCount + 255) / 256
        assertEquals("[$label] chunk count at 12,288", expectedChunks, big.chunkCount)
        val mutationSum = big.prependNanos + big.appendNanos
        val median = big.chunkNanos.sorted()[big.chunkNanos.size / 2]
        println("CF-2M4 perf [$label] 12,288 chunk series (ms): " + big.chunkNanos.joinToString(" ") { ms(it) })
        assertTrue("[$label] a single insertion still did most of the work: max=${big.maxChunkNanos} sum=$mutationSum", big.maxChunkNanos * 3 < mutationSum)
        assertTrue("[$label] median chunk ${ms(median)} ms", median / 1_000_000.0 < 10.0)

        val t1k = minTotals.getValue("$label/1000").coerceAtLeast(1L)
        val t12k = minTotals.getValue("$label/12288")
        val ratio = t12k.toDouble() / t1k
        println("CF-2M4 perf [$label] scaling total(12288)/total(1000)=${"%.1f".format(ratio)} (linear ~12.3, quadratic ~151)")
        // The trackless variant makes per-item work nearly free, so it exposes the inherent O(items x chunks) timeline-derivation
        // term of chunking (observed ratios 27-100): a ratio there is noise-dominated and is only reported. The ratio assertion
        // uses the production-factory variant (stable, observed 8-15) and rejects an obvious quadratic regression (~151).
        if (production) assertTrue("[$label] graft scaling: ratio=$ratio (linear ~12, quadratic ~151)", ratio < 60.0)
        assertTrue("[$label] 12,288-item graft took ${t12k / 1_000_000} ms on the JVM", t12k / 1_000_000.0 < 5_000.0)
    }

    @Test fun graftScalesLinearlyWithTracklessSources() = scaling("trackless", production = false)

    @Test fun graftScalesLinearlyWithTheProductionMediaSourceFactory() = scaling("production-factory", production = true)
    /** Evidence for [NextSlotPreparation.GRAFT_CHUNK_SIZE]: cost of ONE Media3 insertion of n items (production factory). */
    @Test fun chunkSizeEvidenceForTheChosenGraftChunk() {
        val context = RuntimeEnvironment.getApplication()
        val items = songs(4_096).map { it.toPlaybackMediaItem() }
        fun oneInsertion(n: Int): Long {
            val p = TestMediaSource.newPlayer(context, SHARED_SESSION_ID, "-1")
            try {
                p.setMediaItem(items[0])
                val t0 = System.nanoTime()
                p.addMediaItems(items.subList(1, 1 + n))
                return System.nanoTime() - t0
            } finally {
                p.release()
            }
        }
        repeat(5) { oneInsertion(1_024) } // warm-up
        val results = listOf(64, 128, 256, 512, 1_024, 2_048, 4_000).associateWith { n ->
            (0 until 9).map { oneInsertion(n) }.sorted()[4]
        }
        results.forEach { (n, nanos) -> println("CF-2M4 perf [chunk-size evidence] insertion of $n items = ${ms(nanos)} ms (${"%.1f".format(nanos / 1000.0 / n)} us/item)") }
        val chosen = results.getValue(NextSlotPreparation.GRAFT_CHUNK_SIZE)
        assertTrue("one ${NextSlotPreparation.GRAFT_CHUNK_SIZE}-item chunk took ${ms(chosen)} ms", chosen / 1_000_000.0 < 25.0)
    }
}
