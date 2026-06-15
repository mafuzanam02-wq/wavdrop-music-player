package com.launchpoint.wavdrop.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
private val WidgetIdleCircle = ColorProvider(Color(0xFF2A2536))
private val WidgetOnSurface  = ColorProvider(Color(0xFFEDEAF4))
private val WidgetSubtle     = ColorProvider(Color(0xFFA9A3B8))

private const val ARTWORK_TARGET_PX = 256

class WavdropWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read playback state once per (re)composition request. Safe when no
        // controller/song exists: falls back to the idle text below.
        val state = runCatching {
            playerController(context).nowPlayingState.value
        }.getOrNull()

        val title     = state?.song?.displayTitle?.takeIf { it.isNotBlank() } ?: "Wavdrop"
        val artist    = state?.song?.displayArtist?.takeIf { it.isNotBlank() } ?: "Ready to play"
        val isPlaying = state?.isPlaying == true

        // Decode artwork in-process (we hold the audio permission; the launcher
        // does not), downscaled so the RemoteViews bitmap stays small.
        val artwork = withContext(Dispatchers.IO) {
            loadAlbumArtwork(context, state?.song?.albumId)
        }

        provideContent {
            WidgetContent(
                title     = title,
                artist    = artist,
                isPlaying = isPlaying,
                artwork   = artwork,
            )
        }
    }
}

private fun loadAlbumArtwork(context: Context, albumId: Long?): Bitmap? {
    val uriString = ArtworkResolver.albumArtworkUri(albumId) ?: return null
    return runCatching {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }?.let { full ->
            if (full.width > ARTWORK_TARGET_PX || full.height > ARTWORK_TARGET_PX) {
                val scale = ARTWORK_TARGET_PX.toFloat() / maxOf(full.width, full.height)
                Bitmap.createScaledBitmap(
                    full,
                    (full.width * scale).toInt().coerceAtLeast(1),
                    (full.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== full) full.recycle() }
            } else {
                full
            }
        }
    }.getOrNull()
}

@Composable
private fun WidgetContent(
    title: String,
    artist: String,
    isPlaying: Boolean,
    artwork: Bitmap?,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Artwork + metadata row ─────────────────────────────────────────
        Row(
            modifier          = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artwork != null) {
                Image(
                    provider           = ImageProvider(artwork),
                    contentDescription = "Album artwork",
                    contentScale       = ContentScale.Crop,
                    modifier           = GlanceModifier
                        .size(56.dp)
                        .cornerRadius(10.dp),
                )
            } else {
                Box(
                    modifier = GlanceModifier
                        .size(56.dp)
                        .cornerRadius(10.dp)
                        .background(WidgetIdleCircle),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider           = ImageProvider(R.drawable.widget_ic_play),
                        contentDescription = null,
                        modifier           = GlanceModifier.size(22.dp),
                    )
                }
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = title,
                    style = TextStyle(
                        color      = WidgetOnSurface,
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(1.dp))
                Text(
                    text = artist,
                    style = TextStyle(
                        color    = WidgetSubtle,
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        // ── Controls row ───────────────────────────────────────────────────
        Row(
            modifier            = GlanceModifier.fillMaxWidth(),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WidgetControlButton(
                drawableRes        = R.drawable.widget_ic_skip_previous,
                contentDescription = "Previous",
                action             = actionRunCallback<PreviousAction>(),
            )
            Spacer(GlanceModifier.width(24.dp))
            // Accent circle while playing; muted circle while paused, so the
            // state change is obvious beyond the icon swap.
            WidgetControlButton(
                drawableRes        = if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                action             = actionRunCallback<PlayPauseAction>(),
                circleColor        = if (isPlaying) WidgetAccent else WidgetIdleCircle,
            )
            Spacer(GlanceModifier.width(24.dp))
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
    circleColor: ColorProvider? = null,
) {
    Image(
        provider           = ImageProvider(drawableRes),
        contentDescription = contentDescription,
        modifier           = GlanceModifier
            .size(if (circleColor != null) 40.dp else 32.dp)
            .cornerRadius(20.dp)
            .background(circleColor ?: ColorProvider(Color(0x00000000)))
            .padding(if (circleColor != null) 9.dp else 5.dp)
            .clickable(action),
    )
}

// ── Actions ────────────────────────────────────────────────────────────────
// MediaController (inside PlayerController) is main-thread-only, while Glance
// runs ActionCallbacks on a worker thread — so every command hops to Main.
// WavdropWidgetUpdater observes the resulting state change and re-renders the
// widget; the explicit update() here just makes the tap feel instant.

internal class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching { playerController(context).skipToPrevious() }
        }
        WavdropWidget().update(context, glanceId)
    }
}

internal class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching { playerController(context).togglePlayPause() }
        }
        WavdropWidget().update(context, glanceId)
    }
}

internal class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) {
            runCatching { playerController(context).skipToNext() }
        }
        WavdropWidget().update(context, glanceId)
    }
}

// ── Receiver ───────────────────────────────────────────────────────────────

class WavdropWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WavdropWidget()
}
