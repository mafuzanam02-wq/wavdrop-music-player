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
import com.launchpoint.wavdrop.playback.PlaybackService
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
        private const val ARTWORK_TARGET_PX = 128

        // Unique request codes for PendingIntents — must not collide with each other.
        private const val RC_OPEN_APP  = 200
        private const val RC_PREVIOUS  = 201
        private const val RC_PLAY_PAUSE = 202
        private const val RC_NEXT      = 203

        // Size bands derived from Samsung One UI measurements.
        // short:    minH < 170dp  (~108dp default)
        // tall:     minH 170–299dp (~238dp)
        // expanded: minH >= 300dp  (~368dp+)
        private const val HEIGHT_TALL_DP     = 170
        private const val HEIGHT_EXPANDED_DP = 300

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
            val artwork: Bitmap? = if (hasActive) loadArtwork(context, snapshot?.albumId) else null

            for (id in ids) {
                try {
                    val options = manager.getAppWidgetOptions(id)
                    val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60)
                    val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
                    val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                    val layout = chooseLayout(hasActive, minH)
                    if (BuildConfig.DEBUG) Log.d(TAG, "[updater] id=$id minW=${minW}dp minH=${minH}dp maxW=${maxW}dp maxH=${maxH}dp → ${layoutName(layout)}")

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

        private fun chooseLayout(hasActive: Boolean, minHeightDp: Int): Int {
            if (!hasActive) return R.layout.wavdrop_widget_idle
            return when {
                minHeightDp >= HEIGHT_EXPANDED_DP -> R.layout.wavdrop_widget_expanded
                minHeightDp >= HEIGHT_TALL_DP     -> R.layout.wavdrop_widget_tall
                else                              -> R.layout.wavdrop_widget_short
            }
        }

        private fun loadArtwork(context: Context, albumId: Long?): Bitmap? {
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
            }.onFailure {
                if (BuildConfig.DEBUG) Log.d(TAG, "[updater] artwork load failed: ${it.message}")
            }.getOrNull()
        }

        private fun buildViews(
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
            views.setTextViewText(R.id.widget_title, snapshot?.title ?: "")
            views.setTextViewText(R.id.widget_artist, snapshot?.artist ?: "")

            // Artwork: background + thumbnail share the same bitmap.
            if (artwork != null) {
                views.setViewVisibility(R.id.widget_background, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_background, artwork)
                views.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_artwork, artwork)
            } else {
                views.setViewVisibility(R.id.widget_background, View.GONE)
                views.setViewVisibility(R.id.widget_scrim, View.GONE)
                // Show the play icon as a placeholder inside the dark thumbnail.
                views.setImageViewResource(R.id.widget_artwork, R.drawable.widget_ic_play)
            }

            // Play/pause icon and button background reflect current playback state.
            val isPlaying = snapshot?.isPlaying == true
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
            )
            views.setInt(
                R.id.widget_play_pause,
                "setBackgroundResource",
                if (isPlaying) R.drawable.widget_btn_accent_bg else R.drawable.widget_btn_idle_bg,
            )

            // Control PendingIntents — direct service calls, same path as notification controls.
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                serviceIntent(context, PlaybackService.ACTION_WIDGET_PREVIOUS, RC_PREVIOUS),
            )
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                serviceIntent(context, PlaybackService.ACTION_WIDGET_PLAY_PAUSE, RC_PLAY_PAUSE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                serviceIntent(context, PlaybackService.ACTION_WIDGET_NEXT, RC_NEXT),
            )

            return views
        }

        private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                context,
                requestCode,
                Intent(context, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun layoutName(res: Int): String = when (res) {
            R.layout.wavdrop_widget_idle     -> "idle"
            R.layout.wavdrop_widget_short    -> "short"
            R.layout.wavdrop_widget_tall     -> "tall"
            R.layout.wavdrop_widget_expanded -> "expanded"
            else                             -> res.toString()
        }
    }
}
