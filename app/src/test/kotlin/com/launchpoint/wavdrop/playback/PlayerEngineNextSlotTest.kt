package com.launchpoint.wavdrop.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** CF-2M4: topology/gate invariants and source guards: prepares B but never plays or promotes it, no third player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerEngineNextSlotTest {

    private fun src(name: String) =
        File("src/main/kotlin/com/launchpoint/wavdrop/playback/$name").readText()

    private fun code(name: String) = src(name).lines()
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test fun gateIsFalseAndShippingBuildsOnePlayerWithNoNextPreparation() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
        val assembly = assemblePlayback(RuntimeEnvironment.getApplication(), false, AudioAttributes.DEFAULT)
        assertNull(assembly.engine)
        assertEquals(1, assembly.topology.physicalPlayerCount)
        assertSame(assembly.primaryPlayer, assembly.logicalPlayer)
        assembly.primaryPlayer.release()
    }

    @Test fun gatedTopologyStillHasExactlyTwoPlayers() {
        val assembly = assemblePlayback(RuntimeEnvironment.getApplication(), true, AudioAttributes.DEFAULT, sessionIdProvider = { SHARED_SESSION_ID })
        val engine = assembly.engine!!
        assertEquals(2, assembly.topology.physicalPlayerCount)
        assertEquals(NextSlotState.Idle, engine.nextPreparation.state)
        engine.release()
    }

    @Test fun preparationSourceNeverStartsPromotesSeeksOrTouchesTheFacade() {
        val forbidden = listOf(
            ".play()", "playWhenReady = true", "setPlayWhenReady(true", ".seekTo(", "swapRoles", "replaceDelegate",
            "facade", "AudioFocusManager", "setVolume", ".volume =", "registerReceiver", "ExoPlayer.Builder",
        )
        for (file in listOf("NextSlotPreparation.kt", "NextSlotPreparationDriver.kt")) {
            val c = code(file)
            forbidden.forEach { assertFalse("$file must not contain `$it` (CF-2M4 prepares B but never plays it)", c.contains(it)) }
        }
    }

    @Test fun onlyTheEngineAndPreparationFilesKnowAboutNextPreparation() {
        val users = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { val t = it.readText(); t.contains("nextPreparation") || t.contains("NextSlotPreparation") }
            .map { it.name }.toSet()
        assertEquals(setOf("PlayerEngine.kt", "NextSlotPreparation.kt", "NextSlotPreparationDriver.kt", "PlaybackService.kt", "CrossfadePromotionRuntime.kt"), users)
    }

    @Test fun productionStillNeverSwapsRolesInCf2m4() {
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "PlayerEngine.kt" && it.name != "SessionFacade.kt" && it.name != "PlayerSlots.kt" }
            .filter { val t = it.readText(); t.contains("swapRolesForTest") || t.contains("replaceDelegate(") }
            .map { it.name }.toList()
        assertTrue("$offenders", offenders.isEmpty())
    }

    @Test fun engineReleaseKeepsPreparationDetachedAndFocusReleasedOnce() {
        val f = PlayerEngineFixture()
        f.engine.nextPreparation.request(NextSlotRequest(nextSlotKey(2), nextSlotQueue("A", "B", "C")))
        f.engine.release()
        assertEquals(1, f.engine.focusReleaseCount)
        assertEquals(listOf("P1", "P2"), f.releases)
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
    }
}
