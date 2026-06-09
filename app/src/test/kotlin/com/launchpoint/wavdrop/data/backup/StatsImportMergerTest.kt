package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsImportMergerTest {

    // ── anyUpdated flag ───────────────────────────────────────────────────────

    @Test
    fun `same values produce zero deltas and anyUpdated is false`() {
        val effect = compute(
            currentPlay = 10, currentSkip = 3, currentListeningMs = 5000L,
            importedPlay = 10, importedSkip = 3, importedListeningMs = 5000L,
        )

        assertEquals(0L, effect.playDelta)
        assertEquals(0L, effect.skipDelta)
        assertEquals(0L, effect.listeningTimeDelta)
        assertFalse(effect.anyUpdated)
    }

    @Test
    fun `imported play higher than current produces positive play delta`() {
        val effect = compute(
            currentPlay = 5, currentSkip = 0, currentListeningMs = 0L,
            importedPlay = 12, importedSkip = 0, importedListeningMs = 0L,
        )

        assertEquals(7L, effect.playDelta)
        assertEquals(0L, effect.skipDelta)
        assertTrue(effect.anyUpdated)
    }

    @Test
    fun `imported play lower than current produces zero play delta`() {
        val effect = compute(
            currentPlay = 20, currentSkip = 0, currentListeningMs = 0L,
            importedPlay = 10, importedSkip = 0, importedListeningMs = 0L,
        )

        assertEquals(0L, effect.playDelta)
        assertFalse(effect.anyUpdated)
    }

    @Test
    fun `imported skip higher than current produces positive skip delta`() {
        val effect = compute(
            currentPlay = 0, currentSkip = 2, currentListeningMs = 0L,
            importedPlay = 0, importedSkip = 8, importedListeningMs = 0L,
        )

        assertEquals(0L, effect.playDelta)
        assertEquals(6L, effect.skipDelta)
        assertTrue(effect.anyUpdated)
    }

    @Test
    fun `imported skip lower than current produces zero skip delta`() {
        val effect = compute(
            currentPlay = 0, currentSkip = 10, currentListeningMs = 0L,
            importedPlay = 0, importedSkip = 3, importedListeningMs = 0L,
        )

        assertEquals(0L, effect.skipDelta)
        assertFalse(effect.anyUpdated)
    }

    @Test
    fun `imported listening time higher produces positive listening delta`() {
        val effect = compute(
            currentPlay = 0, currentSkip = 0, currentListeningMs = 1_000L,
            importedPlay = 0, importedSkip = 0, importedListeningMs = 60_000L,
        )

        assertEquals(59_000L, effect.listeningTimeDelta)
        assertTrue(effect.anyUpdated)
    }

    @Test
    fun `imported listening time lower produces zero listening delta`() {
        val effect = compute(
            currentPlay = 0, currentSkip = 0, currentListeningMs = 60_000L,
            importedPlay = 0, importedSkip = 0, importedListeningMs = 1_000L,
        )

        assertEquals(0L, effect.listeningTimeDelta)
        assertFalse(effect.anyUpdated)
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `importing same file twice yields zero effect the second time`() {
        val firstEffect = compute(
            currentPlay = 0, currentSkip = 0, currentListeningMs = 0L,
            importedPlay = 15, importedSkip = 4, importedListeningMs = 90_000L,
        )
        assertTrue(firstEffect.anyUpdated)

        // Simulate: after first import, current == imported.
        val secondEffect = compute(
            currentPlay = 15, currentSkip = 4, currentListeningMs = 90_000L,
            importedPlay = 15, importedSkip = 4, importedListeningMs = 90_000L,
        )

        assertEquals(0L, secondEffect.playDelta)
        assertEquals(0L, secondEffect.skipDelta)
        assertEquals(0L, secondEffect.listeningTimeDelta)
        assertFalse(secondEffect.anyUpdated)
    }

    // ── BlackPlayer (zero listening time) ─────────────────────────────────────

    @Test
    fun `zero imported listening time leaves listening delta zero`() {
        val effect = compute(
            currentPlay = 5, currentSkip = 1, currentListeningMs = 30_000L,
            importedPlay = 10, importedSkip = 3, importedListeningMs = 0L,
        )

        assertEquals(5L, effect.playDelta)
        assertEquals(2L, effect.skipDelta)
        assertEquals(0L, effect.listeningTimeDelta)
        assertTrue(effect.anyUpdated)
    }

    // ── All-zero baseline ─────────────────────────────────────────────────────

    @Test
    fun `fresh song with no existing stats takes full imported values`() {
        val effect = compute(
            currentPlay = 0, currentSkip = 0, currentListeningMs = 0L,
            importedPlay = 100, importedSkip = 25, importedListeningMs = 300_000L,
        )

        assertEquals(100L, effect.playDelta)
        assertEquals(25L, effect.skipDelta)
        assertEquals(300_000L, effect.listeningTimeDelta)
        assertTrue(effect.anyUpdated)
    }

    // ── Local stats already higher ────────────────────────────────────────────

    @Test
    fun `local stats higher than import in all dimensions leaves all deltas zero`() {
        val effect = compute(
            currentPlay = 200, currentSkip = 50, currentListeningMs = 600_000L,
            importedPlay = 100, importedSkip = 25, importedListeningMs = 300_000L,
        )

        assertEquals(0L, effect.playDelta)
        assertEquals(0L, effect.skipDelta)
        assertEquals(0L, effect.listeningTimeDelta)
        assertFalse(effect.anyUpdated)
    }

    // ── Mixed partial update ──────────────────────────────────────────────────

    @Test
    fun `only play count higher produces only play delta`() {
        val effect = compute(
            currentPlay = 5, currentSkip = 10, currentListeningMs = 40_000L,
            importedPlay = 8, importedSkip = 8, importedListeningMs = 30_000L,
        )

        assertEquals(3L, effect.playDelta)
        assertEquals(0L, effect.skipDelta)
        assertEquals(0L, effect.listeningTimeDelta)
        assertTrue(effect.anyUpdated)
    }

    private fun compute(
        currentPlay: Int,
        currentSkip: Int,
        currentListeningMs: Long,
        importedPlay: Int,
        importedSkip: Int,
        importedListeningMs: Long,
    ): StatsImportMerger.MergeEffect = StatsImportMerger.computeEffect(
        currentPlayCount        = currentPlay,
        currentSkipCount        = currentSkip,
        currentListeningTimeMs  = currentListeningMs,
        importedPlayCount       = importedPlay,
        importedSkipCount       = importedSkip,
        importedListeningTimeMs = importedListeningMs,
    )
}
