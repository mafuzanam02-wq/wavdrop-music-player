package com.launchpoint.wavdrop.playback

import android.util.Log
import com.launchpoint.wavdrop.data.repository.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one-time playback session restore that loads the last queue/song/position into
 * [PlayerController] at app startup.
 *
 * Restore must run once per process regardless of which screen the user opens first.
 * Placing this responsibility in a @Singleton means it is no longer tied to any specific
 * screen ViewModel, so navigating directly to Now Playing, Settings, or any other route
 * works correctly.
 *
 * [restoreOnce] is called from [com.launchpoint.wavdrop.MainActivity.onCreate]. The
 * [hasTriggered] flag prevents duplicate restores on configuration changes because the
 * Singleton survives Activity recreation.
 *
 * [PlayerController.restoreSessionIfNeeded] has its own idempotency guard as a second
 * line of defence.
 */
@Singleton
class PlaybackStartupCoordinator @Inject constructor(
    private val songRepository: SongRepository,
    private val playerController: PlayerController,
) {
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var hasTriggered = false

    fun restoreOnce() {
        if (hasTriggered) return
        hasTriggered = true
        scope.launch {
            runCatching {
                val songs = songRepository.songs.first()
                playerController.restoreSessionIfNeeded(songs)
            }.onFailure { e ->
                Log.w(TAG, "Playback session restore failed at startup", e)
            }
        }
    }

    private companion object {
        const val TAG = "Wavdrop-Startup"
    }
}
