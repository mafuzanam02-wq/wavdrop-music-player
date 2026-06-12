package com.launchpoint.wavdrop

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.playback.PlaybackStartupCoordinator
import com.launchpoint.wavdrop.playback.PlayerController
import com.launchpoint.wavdrop.ui.navigation.WavdropNavGraph
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appSettingsRepository: AppSettingsRepository
    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var playbackStartupCoordinator: PlaybackStartupCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleExternalAudioIntent(intent)
        playbackStartupCoordinator.restoreOnce()
        setContent {
            val themeMode by appSettingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val accentColor by appSettingsRepository.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.MIDNIGHT_VIOLET)
            WavdropTheme(themeMode = themeMode, accentColor = accentColor) {
                WavdropNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalAudioIntent(intent)
    }

    private fun handleExternalAudioIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        playerController.playExternalUri(
            uri = uri,
            displayName = uri.displayName(),
        )
    }

    private fun Uri.displayName(): String? {
        if (scheme == "file") {
            return lastPathSegment
        }
        return runCatching {
            contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.useDisplayName()
        }.getOrNull()
    }

    private fun Cursor.useDisplayName(): String? = use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0) null else cursor.getString(index)
    }
}
