package com.launchpoint.wavdrop.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CF-2M8: the CF-2L natural-handoff architecture (secondary B -> wait for the primary's AUTO -> reconcile positions -> transfer
 * ownership) was RETIRED after the CF-2M7 physical pass. Deletion is the real guard (nothing compiles against it); this keeps its
 * defining concepts from quietly returning to production code. Comments are ignored, so history can still be described.
 */
class CrossfadeLegacyRetirementTest {

    private val retired = listOf(
        "HandoffPending", "NaturalHandoff", "NaturalTransfer", "NaturalTakeover", "NATURAL_TAKEOVER", "NATURAL_TRANSFER", "NATURAL_HANDOFF", "reconcileCrossfadePrimary", "planCrossfadePrimaryReconciliation",
        "planCrossfadePostAutoReconciliation", "PrimaryTakeoverFacts", "SecondaryHandoffSnapshot", "TRANSFER_TICK_INTERVAL_MS",
        "TRANSFER_ABORT", "TRANSFER_COMPLETE", "isNaturalTransferInProgress", "usesNaturalHandoff", "CrossfadeSecondaryPlayer",
        "ExoSecondaryPlayerBackend", "SecondaryPlayerBackend", "createCrossfadeProductionGraph", "CrossfadePreparationRuntime",
        "CrossfadeTimingDriver", "PrimaryGainBackend", "CrossfadePrimaryGainController", "NaturalHandoffPositionClock",
        "constructsLegacyCrossfadeGraph",
    )

    private fun productionCode(): Map<String, String> = File("src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { f ->
            f.name to f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
                .joinToString("\n")
        }

    @Test fun noProductionCodeReferencesTheRetiredHandoffArchitecture() {
        val hits = productionCode().flatMap { (name, code) -> retired.filter { code.contains(it) }.map { "$name: $it" } }
        assertTrue("retired CF-2L concepts reappeared: $hits", hits.isEmpty())
    }

    @Test fun theServiceHasExactlyOneCrossfadeArchitecture() {
        val service = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlaybackService.kt").readText()
        assertEquals(1, Regex("NextSlotPreparationDriver\\(").findAll(service).count())
        assertEquals(1, Regex("CrossfadePromotionRuntime\\(").findAll(service).count())
        assertTrue(service.contains("assembly.engine?.let { engine ->"))
    }

    @Test fun thereIsNoSecondPlayerSystemOnlyTheEnginesTwoSlots() {
        val builders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("ExoPlayer.Builder(") }
            .map { it.name }.toList()
        assertEquals(listOf("PlaybackAssembly.kt"), builders)
    }
}
