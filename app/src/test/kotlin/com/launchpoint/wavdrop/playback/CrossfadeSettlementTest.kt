package com.launchpoint.wavdrop.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2L3: the pure settlement invariant, its DEBUG summary, and the structural proof that crossfade never issues primary transport. */
class CrossfadeSettlementTest {

    private val key = CrossfadeTransitionKey(1L, 1, 2)

    private fun settled() = CrossfadeSettlementSnapshot(
        state = CrossfadeState.Idle,
        hasNaturalObservedKey = false,
        hasSeekTarget = false,
        seekLeadMs = 0L,
        transferActive = false,
        primaryGainOwnerKey = null,
        secondaryOwned = false,
        secondaryStarted = false,
        closed = false,
    )

    @Test fun aFullyClearedIdleRuntimeIsSettled() {
        assertTrue(isCrossfadeFullySettled(settled()))
    }

    @Test fun idleAloneIsNotProofOfSettlement() {
        val leftovers = listOf(
            settled().copy(hasNaturalObservedKey = true),
            settled().copy(hasSeekTarget = true),
            settled().copy(seekLeadMs = 1L),
            settled().copy(transferActive = true),
            settled().copy(primaryGainOwnerKey = key),
            settled().copy(secondaryOwned = true),
            settled().copy(reconcileRequestCount = 1),
        )
        leftovers.forEachIndexed { i, s ->
            assertEquals(CrossfadeState.Idle, s.state)
            assertFalse("leftover #$i", isCrossfadeFullySettled(s))
        }
    }

    @Test fun anActiveStateIsNeverSettled() {
        assertFalse(isCrossfadeFullySettled(settled().copy(state = CrossfadeState.HandoffPending(key, 6_000L))))
    }

    @Test fun summaryNamesOnlyStatesAndFlags() {
        val line = formatCrossfadeSettlementSummary(settled().copy(transferActive = true, primaryGainOwnerKey = key))
        assertTrue(line.contains("crossfade=Idle"))
        assertTrue(line.contains("transferActive=true") && line.contains("primaryGainOwned=true") && line.contains("settled=false"))
        assertFalse(line.contains("content://"))
        assertEquals("crossfade=off", formatCrossfadeSettlementSummary(null))
    }

    // ── structural proof: the crossfade handoff/reconciliation path never issues play() / pause() ──

    private fun source(relative: String): String {
        val candidates = listOf("src/main/kotlin/com/launchpoint/wavdrop/playback/$relative", "app/src/main/kotlin/com/launchpoint/wavdrop/playback/$relative")
        val file = candidates.map(::File).firstOrNull { it.isFile } ?: error("source not found: $relative (user.dir=${System.getProperty("user.dir")})")
        return file.readText()
    }

    private val transportCall = Regex("""\.\s*(play|pause|playWhenReady|setPlayWhenReady)\b\s*(\(|=)""")

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun crossfadeRuntimeHandoffAndReconciliationSourcesIssueNoPrimaryPlayOrPause() {
        listOf(
            "CrossfadePreparationRuntime.kt",
            "CrossfadeNaturalHandoff.kt",
            "CrossfadePrimaryReconciliation.kt",
            "CrossfadePrimaryGainController.kt",
            "CrossfadeTimingDriver.kt",
            "CrossfadeProductionGraph.kt",
            "CrossfadeSettlement.kt",
        ).forEach { name ->
            val code = stripComments(source(name))
            assertFalse("$name must not issue transport", transportCall.containsMatchIn(code))
        }
    }

    @Test fun onlyTheSecondaryBackendMayStartPlaybackAndOnlyItsOwnPlayer() {
        val code = stripComments(source("CrossfadeSecondaryPlayer.kt"))
        // the one intentional start: the SECONDARY's own player, inside ExoSecondaryPlayerBackend.start (never the primary)
        assertEquals(1, Regex(Regex.escape("player.play()")).findAll(code).count())
        assertFalse(code.contains("player.pause()"))
    }

    @Test fun naturalReconciliationInPlayerControllerIsAnInternalSameItemSeekOnly() {
        val code = stripComments(source("PlayerController.kt"))
        val start = code.indexOf("fun reconcileCrossfadePrimaryAfterNaturalTransition")
        assertTrue(start >= 0)
        val body = code.substring(start, code.indexOf("private fun currentPlaybackIndex", start))
        assertTrue(body.contains("controller.seekTo(positionMs)"))
        assertFalse(transportCall.containsMatchIn(body))
        // no user-seek handling, hydration, queue replacement, stats or play-intent mutation on this path
        listOf("seekTo(", "ensurePlayerHydratedFromSession", "setMediaItems", "playQueue", "recordPlay", "onExplicitExternalTransport", "notifyCrossfade")
            .filter { it != "seekTo(" }
            .forEach { assertFalse("reconciliation must not call $it", body.contains(it)) }
        assertEquals(1, Regex("""seekTo\(""").findAll(body).count())
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
