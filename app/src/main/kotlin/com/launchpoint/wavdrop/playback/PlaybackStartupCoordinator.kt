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
 * Owns the playback session restore that loads the last queue/song/position into
 * [PlayerController] at app startup.
 *
 * Placing this responsibility in a @Singleton means it is no longer tied to any specific
 * screen ViewModel, so navigating directly to Now Playing, Settings, or any other route
 * works correctly.
 *
 * [restoreOnce] is called from [com.launchpoint.wavdrop.MainActivity.onCreate] (which re-runs on
 * every Activity recreation). Restore is coordinated by [StartupRestoreGate] rather than a plain
 * "fire once" boolean: a successful/terminal restore becomes an idempotent no-op, but a transient
 * failure (controller not connected yet, Media3 setup failure, song-repository error) leaves the
 * gate eligible so a later [restoreOnce] can try again — no process restart required.
 *
 * [PlayerController.ensurePlayerHydratedFromSession] has its own active-queue guard and mutex as a
 * second line of defence, so a duplicate that slips through never overwrites live playback.
 */
@Singleton
class PlaybackStartupCoordinator @Inject constructor(
    private val songRepository: SongRepository,
    private val playerController: PlayerController,
) {
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gate = StartupRestoreGate()

    fun restoreOnce() {
        if (!gate.beginAttempt()) return
        scope.launch {
            runCatching {
                val songs = songRepository.songs.first()
                playerController.restoreSessionIfNeeded(songs)
            }.onSuccess { result ->
                gate.completeAttempt(result)
                if (!StartupRestoreDecision.isTerminal(result)) {
                    Log.w(TAG, "Playback session restore transient result=$result; remains retry-eligible")
                }
            }.onFailure { e ->
                gate.completeWithTransientFailure()
                Log.w(TAG, "Playback session restore failed at startup; remains retry-eligible", e)
            }
        }
    }

    private companion object {
        const val TAG = "Wavdrop-Startup"
    }
}
