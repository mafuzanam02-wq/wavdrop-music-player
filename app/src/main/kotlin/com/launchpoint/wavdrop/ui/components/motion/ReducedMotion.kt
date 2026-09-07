package com.launchpoint.wavdrop.ui.components.motion

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.launchpoint.wavdrop.ui.theme.WavdropMotion

/**
 * Single source of truth for the OS "Remove animations" accessibility flag (WU-02).
 *
 * Replaces the ad-hoc `Settings.Global.getFloat(..., ANIMATOR_DURATION_SCALE, 1f) < 0.1f` reads
 * duplicated in WrappedScreen and QueueSheet. New motion primitives consume this so decorative
 * motion is dropped consistently while functional state changes remain visible.
 *
 * Read once per context and remembered — the scale rarely changes within a screen's lifetime, and
 * this keeps it off the hot path.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        WavdropMotion.isReducedMotion(scale)
    }
}
