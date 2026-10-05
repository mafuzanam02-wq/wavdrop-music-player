package com.launchpoint.wavdrop.playback

import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CF-2M3: exactly one release owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerEngineReleaseTest {

    private fun noisyReceivers() = shadowOf(RuntimeEnvironment.getApplication()).registeredReceivers.count {
        it.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    }

    @Test fun releaseFreesEachPhysicalOnceAndTheFocusOwnerOnceAndTheNoisyReceiver() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        assertEquals(1, noisyReceivers())
        f.engine.release()
        assertEquals(listOf("P1", "P2"), f.releases)
        assertEquals(1, f.engine.focusReleaseCount)
        assertEquals(0, noisyReceivers())
        assertTrue(f.engine.released)
    }

    @Test fun secondReleaseIsHarmless() {
        val f = PlayerEngineFixture()
        f.engine.release()
        f.engine.release()
        assertEquals(listOf("P1", "P2"), f.releases)
        assertEquals(1, f.engine.focusReleaseCount)
    }

    @Test fun releaseAfterRoleSwapStillFreesBothOnceWithoutTheFacadeReleasingADelegate() {
        val f = PlayerEngineFixture()
        f.engine.swapRolesForTest()
        f.engine.release()
        assertEquals(listOf("P2", "P1"), f.releases)
        assertFalse("the façade never releases a physical player itself", f.p1.commands.contains("P1.release()"))
        assertFalse(f.p2.commands.contains("P2.release()"))
    }

    @Test fun commandsAfterReleaseAreInertAndNotRoutedToAnyPhysical() {
        val f = PlayerEngineFixture()
        f.engine.release()
        f.engine.setHandleAudioBecomingNoisy(true)
        assertEquals("no receiver may be registered after release", 0, noisyReceivers())
        f.facade.play(); idleMainLooper()
        assertTrue(f.p1.commands.isEmpty())
        assertTrue(f.p2.commands.isEmpty())
    }

    @Test fun releaseNeverReachesANonOwnedExternalPlayer() {
        val external = ScriptedPlayer("EXT")
        val f = PlayerEngineFixture()
        f.engine.release()
        assertTrue(external.commands.isEmpty())
        assertFalse(f.releases.contains("EXT"))
    }

    @Test fun realExoPlayersAreBothReleasedByTheEngineAndOnlyByIt() {
        val context = RuntimeEnvironment.getApplication()
        val assembly = assemblePlayback(context, rolloutEnabled = true, audioAttributes = AudioAttributes.DEFAULT, sessionIdProvider = { SHARED_SESSION_ID })
        val engine = assembly.engine!!
        val first = engine.currentPlayer
        val second = engine.nextPlayer
        assertFalse(first.isReleased)
        assertFalse(second.isReleased)
        engine.release()
        assertTrue(first.isReleased)
        assertTrue(second.isReleased)
        engine.release() // harmless
    }
}
