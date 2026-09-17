package com.launchpoint.wavdrop.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackServiceConnectionResultTest {

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun `connection retains transport and queue commands with custom session commands`() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        try {
            val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                context.packageName, 0, 0, 0, 0, true, Bundle.EMPTY, true,
            )
            val actual = PlaybackService.withWavdropCommands(session, controller)

            val requiredPlayerCommands = listOf(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
            )
            assertTrue(actual.isAccepted)
            requiredPlayerCommands.forEach { command ->
                assertTrue(actual.availablePlayerCommands.contains(command))
            }
            val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            for (command in defaults.availableSessionCommands.commands) {
                assertTrue(actual.availableSessionCommands.contains(command))
            }
            assertTrue(
                actual.availableSessionCommands.contains(
                    SessionCommand("com.launchpoint.wavdrop.TOGGLE_SHUFFLE", Bundle.EMPTY),
                ),
            )
            assertTrue(
                actual.availableSessionCommands.contains(
                    SessionCommand("com.launchpoint.wavdrop.CYCLE_REPEAT", Bundle.EMPTY),
                ),
            )
        } finally {
            session.release()
            player.release()
        }
    }
}
