package com.launchpoint.wavdrop.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.MainActivity
import com.launchpoint.wavdrop.R
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WavdropWidgetEntryPoint {
    fun widgetStateStore(): WidgetStateStore
}

/**
 * Drives all RemoteViews updates for the Wavdrop home-screen widget.
 *
 * requestUpdate() is a static helper so PlaybackService can call it without
 * holding a reference to the injected singleton. The singleton instance is
 * injected into WavdropApp only to satisfy Hilt's eager-singleton graph; its
 * start() method is a no-op because state is pushed from PlaybackService.
 */
@Singleton
class WavdropWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** No-op: state is pushed from the PlaybackService ExoPlayer listener. */
    fun start() = Unit

    companion object {
        private const val TAG = "WavdropWidget"

        // Unique request codes for PendingIntents — must not collide with each other.
        internal const val RC_OPEN_APP  = 200
        internal const val RC_PREVIOUS  = 201
        internal const val RC_PLAY_PAUSE = 202
        internal const val RC_NEXT      = 203

        // Singleton scope: SupervisorJob ensures individual coroutine failures
        // do not cancel the scope. Lives for the process lifetime.
        private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Triggers a full RemoteViews rebuild for all active widget instances.
         * Safe to call from any thread or coroutine; returns immediately.
         */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            if (BuildConfig.DEBUG) Log.d(TAG, "[updater] requestUpdate called thread=${Thread.currentThread().name}")
            updateScope.launch {
                try {
                    performUpdate(appContext)
                } catch (e: Throwable) {
                    Log.e(TAG, "[updater] performUpdate FAILED: ${e::class.simpleName} ${e.message}", e)
                }
            }
        }

        private suspend fun performUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WavdropWidgetProvider::class.java)
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "[updater] active widget IDs: ${ids.size} → ${ids.toList()}")
            if (ids.isEmpty()) return

            val store = resolveStore(context)
            val snapshot = store?.load()
            if (BuildConfig.DEBUG) Log.d(TAG, "[updater] snapshot: title=${snapshot?.title} isPlaying=${snapshot?.isPlaying} hasActive=${snapshot?.hasActiveMedia}")

            val hasActive = snapshot?.hasActiveMedia == true

            // One decode per distinct target size for this update pass (never per widget instance, never full size).
            val artworkBySize = HashMap<Int, Bitmap?>()

            for (id in ids) {
                try {
                    val options = manager.getAppWidgetOptions(id)
                    val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
                    val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                    val layout = WidgetLayoutSelector.layoutFor(hasActive, minW, minH)
                    if (BuildConfig.DEBUG) Log.d(TAG, "[updater] id=$id minW=${minW}dp minH=${minH}dp maxW=${maxW}dp maxH=${maxH}dp → ${layoutName(layout)}")

                    val artwork: Bitmap? = if (hasActive) {
                        val target = WidgetLayoutSelector.artworkTargetPx(layout)
                        artworkBySize.getOrPut(target) { loadArtwork(context, snapshot?.albumId, target) }
                    } else null

                    val views = buildViews(context, layout, snapshot, artwork)
                    manager.updateAppWidget(id, views)
                    if (BuildConfig.DEBUG) Log.d(TAG, "[updater] id=$id updateAppWidget OK")
                } catch (e: Throwable) {
                    Log.e(TAG, "[updater] id=$id update FAILED: ${e::class.simpleName} ${e.message}", e)
                }
            }
        }

        private fun resolveStore(context: Context): WidgetStateStore? =
            runCatching {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    WavdropWidgetEntryPoint::class.java,
                ).widgetStateStore()
            }.onFailure {
                Log.e(TAG, "[updater] failed to resolve WidgetStateStore: ${it.message}", it)
            }.getOrNull()

        /**
         * Decodes the album art no larger than [targetPx] on its longest side. Bounds are read first and the decode is
         * sub-sampled, so a large embedded cover is never fully decoded into memory just to be shrunk.
         */
        private fun loadArtwork(context: Context, albumId: Long?, targetPx: Int): Bitmap? {
            val uri = ArtworkResolver.albumArtworkUri(albumId)?.let(Uri::parse) ?: return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                if (longest <= 0) return@runCatching null
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = WidgetLayoutSelector.sampleSizeFor(longest, targetPx)
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: return@runCatching null
                val decodedLongest = maxOf(decoded.width, decoded.height)
                if (decodedLongest <= targetPx) {
                    decoded
                } else {
                    val scale = targetPx.toFloat() / decodedLongest
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { if (it !== decoded) decoded.recycle() }
                }
            }.onFailure {
                if (BuildConfig.DEBUG) Log.d(TAG, "[updater] artwork load failed: ${it.message}")
            }.getOrNull()
        }

        internal fun buildViews(
            context: Context,
            layout: Int,
            snapshot: WidgetPlaybackSnapshot?,
            artwork: Bitmap?,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layout)

            // Every layout has widget_body as its root — tap opens the app.
            val openIntent = PendingIntent.getActivity(
                context,
                RC_OPEN_APP,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_body, openIntent)

            // Idle layout has no active IDs — return after setting the body tap.
            if (layout == R.layout.wavdrop_widget_idle) return views

            // Active state: populate track metadata.
            val state = WidgetRenderState.of(snapshot, hasArtwork = artwork != null)
            views.setTextViewText(R.id.widget_title, state.title)
            views.setTextViewText(R.id.widget_artist, state.artist)

            // Artwork: background + thumbnail share the same bitmap.
            if (artwork != null) {
                views.setViewVisibility(R.id.widget_background, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_background, artwork)
                views.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_artwork, artwork)
            } else {
                views.setViewVisibility(R.id.widget_background, View.GONE)
                views.setViewVisibility(R.id.widget_scrim, View.GONE)
                // Same placeholder tile in every layout when there is no artwork.
                views.setImageViewResource(R.id.widget_artwork, state.placeholderRes)
            }

            // Play/pause icon and button background reflect current playback state.
            views.setImageViewResource(R.id.widget_play_pause, state.playPauseIconRes)
            views.setInt(R.id.widget_play_pause, "setBackgroundResource", state.playPauseBackgroundRes)

            // Control PendingIntents — delivered to the non-exported WidgetActionReceiver,
            // which drives a MediaController to our session. The exported PlaybackService
            // no longer recognizes raw widget action strings (WD-03).
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                broadcastIntent(context, WidgetActionReceiver.ACTION_WIDGET_PREVIOUS, RC_PREVIOUS),
            )
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                broadcastIntent(context, WidgetActionReceiver.ACTION_WIDGET_PLAY_PAUSE, RC_PLAY_PAUSE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                broadcastIntent(context, WidgetActionReceiver.ACTION_WIDGET_NEXT, RC_NEXT),
            )

            return views
        }

        private fun broadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, WidgetActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun layoutName(res: Int): String = when (res) {
            R.layout.wavdrop_widget_idle     -> "idle"
            R.layout.wavdrop_widget_short    -> "compact"
            R.layout.wavdrop_widget_normal   -> "normal"
            R.layout.wavdrop_widget_tall     -> "tall"
            R.layout.wavdrop_widget_expanded -> "expanded"
            else                             -> res.toString()
        }
    }
}

/** What the active widget shows for a snapshot. Pure so it can be unit-tested without inflating layouts. */
internal data class WidgetRenderState(
    val title: String,
    val artist: String,
    val showsArtwork: Boolean,
    val placeholderRes: Int,
    val playPauseIconRes: Int,
    val playPauseBackgroundRes: Int,
) {
    companion object {
        fun of(snapshot: WidgetPlaybackSnapshot?, hasArtwork: Boolean): WidgetRenderState {
            val playing = snapshot?.isPlaying == true
            return WidgetRenderState(
                title = snapshot?.title ?: "",
                artist = snapshot?.artist ?: "",
                showsArtwork = hasArtwork,
                placeholderRes = R.drawable.widget_artwork_placeholder,
                playPauseIconRes = if (playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
                playPauseBackgroundRes = if (playing) R.drawable.widget_btn_accent_bg else R.drawable.widget_btn_idle_bg,
            )
        }
    }
}

/**
 * Pure size → layout policy (unit-tested). Sizes are the launcher-reported OPTION_APPWIDGET_MIN_WIDTH/MIN_HEIGHT in dp, i.e.
 * the dimensions guaranteed in every orientation, so a layout chosen from them always fits. A value <= 0 means the launcher
 * reported nothing; unknown width is ignored and unknown height falls back to the normal 4×2 layout.
 *
 *  - compact : height < 100dp, or width < 240dp (below the declared 245×115dp minimum; legacy/odd cells). 240 is the narrowest width
 *              the normal layout's fixed 238dp row (12+8+72+10+136) fits in without clipping or overlap.
 *  - normal  : 100–199dp tall (the 4×2 default; 64dp of fixed art/controls, text flexes)
 *  - tall    : 200–329dp tall (≈ 4×3, 4×4; fixed 194dp content)
 *  - expanded: ≥ 330dp tall (220dp fixed + text block; needs ~313dp at 1.3x font scale, ~324dp at 1.5x)
 *
 * Samsung One UI and other launchers report slightly different dp for the same grid cell count, which is why the bands are
 * ranges with headroom rather than exact cell sizes.
 */
internal object WidgetLayoutSelector {
    const val COMPACT_MAX_HEIGHT_DP = 100
    const val COMPACT_MAX_WIDTH_DP = 240
    const val NORMAL_MAX_HEIGHT_DP = 200
    const val TALL_MAX_HEIGHT_DP = 330

    fun layoutFor(hasActiveMedia: Boolean, minWidthDp: Int, minHeightDp: Int): Int {
        if (!hasActiveMedia) return R.layout.wavdrop_widget_idle
        val widthKnown = minWidthDp > 0
        val heightKnown = minHeightDp > 0
        return when {
            heightKnown && minHeightDp < COMPACT_MAX_HEIGHT_DP -> R.layout.wavdrop_widget_short
            widthKnown && minWidthDp < COMPACT_MAX_WIDTH_DP    -> R.layout.wavdrop_widget_short
            !heightKnown                                       -> R.layout.wavdrop_widget_normal
            minHeightDp < NORMAL_MAX_HEIGHT_DP                 -> R.layout.wavdrop_widget_normal
            minHeightDp < TALL_MAX_HEIGHT_DP                   -> R.layout.wavdrop_widget_tall
            else                                               -> R.layout.wavdrop_widget_expanded
        }
    }

    /** Longest-side bitmap size per layout: enough for the artwork slot on a high-density screen, small enough for the binder. */
    fun artworkTargetPx(layout: Int): Int = when (layout) {
        R.layout.wavdrop_widget_short    -> 192
        R.layout.wavdrop_widget_normal   -> 256
        R.layout.wavdrop_widget_tall     -> 320
        R.layout.wavdrop_widget_expanded -> 384
        else                             -> 256
    }

    /** Largest power-of-two sub-sample that still leaves the decoded longest side >= [targetPx]. */
    fun sampleSizeFor(longestSidePx: Int, targetPx: Int): Int {
        if (longestSidePx <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (longestSidePx / (sample * 2) >= targetPx) sample *= 2
        return sample
    }
}
