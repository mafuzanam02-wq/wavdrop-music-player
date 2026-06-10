package com.launchpoint.wavdrop.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.launchpoint.wavdrop.MainActivity
import com.launchpoint.wavdrop.R
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point so the widget (which lives outside the normal DI graph)
 * can reach the singleton PlayerController in the app process.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WavdropWidgetEntryPoint {
    fun playerController(): PlayerController
}

private fun playerController(context: Context): PlayerController =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        WavdropWidgetEntryPoint::class.java,
    ).playerController()

// Fixed dark palette: widgets render outside the app theme, so we keep a
// premium dark look with the Wavdrop violet accent regardless of system theme.
private val WidgetBackground = ColorProvider(Color(0xFF16131F))
private val WidgetAccent     = ColorProvider(Color(0xFF7C4DFF))
private val WidgetOnSurface  = ColorProvider(Color(0xFFEDEAF4))
private val WidgetSubtle     = ColorProvider(Color(0xFFA9A3B8))

class WavdropWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read playback state once per (re)composition request. Safe when no
        // controller/song exists: falls back to the idle text below.
        val state = runCatching {
            playerController(context).nowPlayingState.value
        }.getOrNull()

        val title     = state?.song?.title?.takeIf { it.isNotBlank() } ?: "Wavdrop"
        val artist    = state?.song?.artist?.takeIf { it.isNotBlank() } ?: "Ready to play"
        val isPlaying = state?.isPlaying == true

        provideContent {
            WidgetContent(title = title, artist = artist, isPlaying = isPlaying)
        }
    }
}

@Composable
private fun WidgetContent(
    title: String,
    artist: String,
    isPlaying: Boolean,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(
                color      = WidgetOnSurface,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = artist,
            style = TextStyle(
                color    = WidgetSubtle,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(10.dp))
        Row(
            modifier              = GlanceModifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {
            WidgetControlButton(
                drawableRes        = R.drawable.widget_ic_skip_previous,
                contentDescription = "Previous",
                action             = actionRunCallback<PreviousAction>(),
            )
            Spacer(GlanceModifier.width(20.dp))
            WidgetControlButton(
                drawableRes        = if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                action             = actionRunCallback<PlayPauseAction>(),
                accent             = true,
            )
            Spacer(GlanceModifier.width(20.dp))
            WidgetControlButton(
                drawableRes        = R.drawable.widget_ic_skip_next,
                contentDescription = "Next",
                action             = actionRunCallback<NextAction>(),
            )
        }
    }
}

@Composable
private fun WidgetControlButton(
    drawableRes: Int,
    contentDescription: String,
    action: androidx.glance.action.Action,
    accent: Boolean = false,
) {
    Image(
        provider           = ImageProvider(drawableRes),
        contentDescription = contentDescription,
        modifier           = GlanceModifier
            .size(if (accent) 44.dp else 36.dp)
            .cornerRadius(22.dp)
            .background(if (accent) WidgetAccent else ColorProvider(Color(0x00000000)))
            .padding(if (accent) 10.dp else 6.dp)
            .clickable(action),
    )
}

// ── Actions ────────────────────────────────────────────────────────────────

internal class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { playerController(context).skipToPrevious() }
        WavdropWidget().update(context, glanceId)
    }
}

internal class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { playerController(context).togglePlayPause() }
        WavdropWidget().update(context, glanceId)
    }
}

internal class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { playerController(context).skipToNext() }
        WavdropWidget().update(context, glanceId)
    }
}

// ── Receiver ───────────────────────────────────────────────────────────────

class WavdropWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WavdropWidget()
}
