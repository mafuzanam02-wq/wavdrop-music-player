package com.launchpoint.wavdrop.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guards for the large-queue hardening: where a whole-queue push may still happen, and who repairs. */
class LargeQueueHardeningGuardTest {

    private val source = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()

    @Test fun `whole queue pushes are limited to fresh loads and the labelled last resort`() {
        val operations = Regex("""setMeasuredMediaItems\(\s*operation = ([^,\n]+),""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("\"preserve_search\"", "\"external_uri\"", "\"play_from_queue\"", "operation", "\"sync_player_queue:\$reason\""),
            operations,
        )
    }

    @Test fun `every full re-push names its reason and the planner has no full re-push action`() {
        val calls = Regex("""syncPlayerQueueAt\(""").findAll(source).count() - 1 // minus the definition
        val reasons = Regex("""reason = FullQueueSyncReason\.""").findAll(source).count()
        assertEquals(calls, reasons)
        assertFalse("FullQueueSync" in Regex("""enum class BatchQueuePlayerSyncAction \{[^}]*\}""").find(source)!!.value)
    }

    @Test fun `one reconciler owns physical repair`() {
        assertEquals(1, Regex("""physicalQueueReconciler\.reconcile\(""").findAll(source).count())
        // The dirty flag is cleared only by a completed reconciliation, a full re-push or a fresh queue; repair jobs are
        // bound to the queue generation.
        assertTrue("generation != queueGeneration" in source)
    }

    @Test fun `the bounded media item cache is unchanged`() {
        assertTrue("const val MEDIA_ITEM_CACHE_MAX_SIZE = 12_288" in source)
    }

    @Test fun `repair never issues playback commands`() {
        val reconciler = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PhysicalQueueReconciler.kt").readLines()
            .map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }.joinToString(" ")
        for (forbidden in listOf("seekTo", "prepare", "play()", "pause()", "setMediaItems", "clearMediaItems")) {
            assertFalse("reconciler must not call $forbidden", forbidden in reconciler)
        }
    }
}
