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
import androidx.core.content.ContextCompat
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
    fun widgetAppearanceStore(): WidgetAppearanceStore
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

        @Volatile private var appContext: Context? = null

        // Every request funnels through ONE coordinator: passes never overlap, bursts coalesce, and each pass reads the latest
        // playback snapshot and appearances when it runs — so an appearance save and a playback update can never leave a
        // widget showing stale state.
        private val coordinator = WidgetUpdateCoordinator(
            scope = updateScope,
            onError = { Log.e(TAG, "[updater] performUpdate FAILED: ${it::class.simpleName} ${it.message}", it) },
            run = { target -> appContext?.let { performUpdate(it, target) } },
        )

        /**
         * Triggers a full RemoteViews rebuild for all active widget instances.
         * Safe to call from any thread or coroutine; returns immediately.
         */
        fun requestUpdate(context: Context) {
            appContext = context.applicationContext
            if (BuildConfig.DEBUG) Log.d(TAG, "[updater] requestUpdate(all) thread=${Thread.currentThread().name}")
            coordinator.request(null)
        }

        /** Refreshes only the given widget instances (e.g. after one widget's appearance was saved). */
        fun requestUpdate(context: Context, appWidgetIds: Collection<Int>) {
            appContext = context.applicationContext
            if (BuildConfig.DEBUG) Log.d(TAG, "[updater] requestUpdate(ids=$appWidgetIds)")
            coordinator.request(appWidgetIds)
        }

        /**
         * Requests a render of ONE widget and suspends until a pass that started after this call has finished.
         * Returns true only if that widget was rendered by `updateAppWidget`; false if rendering failed or the id is not (or no
         * longer) one of Wavdrop's widgets. Used by the configuration screen so the first render exists before RESULT_OK.
         * Playback (all-widget) passes are unaffected: a failure there is only logged.
         */
        suspend fun requestUpdateAndAwait(context: Context, appWidgetId: Int): Boolean {
            appContext = context.applicationContext
            return coordinator.requestAndAwait(listOf(appWidgetId))
        }

        /** Runs [block] against the appearance store off the main thread; [onDone] always runs (used to finish goAsync()). */
        internal fun withAppearanceStore(context: Context, onDone: () -> Unit = {}, block: suspend (WidgetAppearanceStore) -> Unit) {
            val appCtx = context.applicationContext
            updateScope.launch {
                try {
                    resolveAppearanceStore(appCtx)?.let { block(it) }
                } catch (e: Throwable) {
                    Log.e(TAG, "[updater] appearance store work FAILED: ${e::class.simpleName} ${e.message}", e)
                } finally {
                    onDone()
                }
            }
        }

        private suspend fun performUpdate(context: Context, target: Set<Int>?) {
            val manager = AppWidgetManager.getInstance(context)
            val allIds = manager.getAppWidgetIds(
                ComponentName(context, WavdropWidgetProvider::class.java)
            )
            // A targeted request drops ids that are not (or no longer) Wavdrop widgets; for an AWAITED request that is a failure,
            // reported below, so a removed/foreign id can never be reported as rendered.
            val ids = if (target == null) allIds else allIds.filter { it in target }.toIntArray()
            val missing = target?.filter { it !in allIds }.orEmpty()
            if (BuildConfig.DEBUG) Log.d(TAG, "[updater] widget IDs to update: ${ids.size} → ${ids.toList()} missing=$missing")
            if (ids.isEmpty()) {
                if (missing.isNotEmpty()) throw WidgetRenderException("widget ids not present: $missing")
                return
            }

            val store = resolveStore(context)
            val snapshot = store?.load()
            // Playback-driven passes fall back to the default look if the appearance read fails; a TARGETED pass (an awaited
            // first render after configuration) must not pretend success with the wrong look, so it fails instead.
            val appearances = resolveAppearanceStore(context)?.runCatching { load(ids.toList()) }?.getOrNull()
                ?: if (target != null) throw WidgetRenderException("appearance store unavailable") else emptyMap()
            val failed = mutableListOf<Int>()
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

                    val views = buildViews(context, layout, snapshot, artwork, appearances[id] ?: WidgetAppearance.DEFAULT)
                    manager.updateAppWidget(id, views)
                    if (BuildConfig.DEBUG) Log.d(TAG, "[updater] id=$id updateAppWidget OK")
                } catch (e: Throwable) {
                    failed += id
                    Log.e(TAG, "[updater] id=$id update FAILED: ${e::class.simpleName} ${e.message}", e)
                }
            }
            // Tell the coordinator (and any awaiting configuration screen) that this pass did not fully succeed.
            if (target != null && (failed.isNotEmpty() || missing.isNotEmpty())) {
                throw WidgetRenderException("render failed for $failed, missing $missing")
            }
        }

        private fun resolveAppearanceStore(context: Context): WidgetAppearanceStore? =
            runCatching {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    WavdropWidgetEntryPoint::class.java,
                ).widgetAppearanceStore()
            }.onFailure {
                Log.e(TAG, "[updater] failed to resolve WidgetAppearanceStore: ${it.message}", it)
            }.getOrNull()

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
            appearance: WidgetAppearance = WidgetAppearance.DEFAULT,
            resolveColor: (Context, Int) -> Int = ContextCompat::getColor,
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
            val state = WidgetRenderState.of(snapshot, hasArtwork = artwork != null, appearance = appearance)
            views.setTextViewText(R.id.widget_title, state.title)
            views.setTextViewText(R.id.widget_artist, state.artist)

            // Card background: only the Artwork style paints the artwork behind the card, under a preset-strength scrim.
            // Solid (and any missing artwork) leaves the opaque dark card; the artwork bitmap is then not even attached here.
            if (state.showsArtworkBackground && artwork != null) {
                views.setViewVisibility(R.id.widget_background, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_background, artwork)
                views.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
                // View.setBackgroundColor(@ColorInt) is a @RemotableViewMethod (API 1); setBackgroundResource expects a drawable
                // id, so the color resource is resolved to an ARGB int first (see setScrimColor).
                views.setScrimColor(R.id.widget_scrim, resolveColor(context, state.scrimColorRes))
            } else {
                views.setViewVisibility(R.id.widget_background, View.GONE)
                views.setViewVisibility(R.id.widget_scrim, View.GONE)
            }

            // Thumbnail: always the artwork when there is one (every style), else the same placeholder tile in every layout.
            if (artwork != null) {
                views.setImageViewBitmap(R.id.widget_artwork, artwork)
            } else {
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
    val showsArtworkBackground: Boolean,
    val scrimColorRes: Int,
    val placeholderRes: Int,
    val playPauseIconRes: Int,
    val playPauseBackgroundRes: Int,
) {
    companion object {
        fun of(
            snapshot: WidgetPlaybackSnapshot?,
            hasArtwork: Boolean,
            appearance: WidgetAppearance = WidgetAppearance.DEFAULT,
        ): WidgetRenderState {
            val playing = snapshot?.isPlaying == true
            val look = appearance.effective()
            return WidgetRenderState(
                title = snapshot?.title ?: "",
                artist = snapshot?.artist ?: "",
                showsArtwork = hasArtwork,
                showsArtworkBackground = hasArtwork && look.style == WidgetBackgroundStyle.ARTWORK,
                scrimColorRes = look.intensity.scrimColorRes,
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

/**
 * Paints the artwork overlay. `View.setBackgroundColor(@ColorInt int)` is annotated `@RemotableViewMethod` (since API 1, so it is
 * available at the project's minSdk 26), which is what lets RemoteViews invoke it by name through [RemoteViews.setInt].
 * `setBackgroundResource` is NOT used for colors: it takes a drawable id, and a `R.color` id is not a documented drawable input.
 */
internal fun RemoteViews.setScrimColor(viewId: Int, @androidx.annotation.ColorInt argb: Int) {
    setInt(viewId, SET_BACKGROUND_COLOR_METHOD, argb)
}

internal const val SET_BACKGROUND_COLOR_METHOD = "setBackgroundColor"

/** A targeted widget render did not complete (render error, or the id is not a Wavdrop widget any more). */
internal class WidgetRenderException(message: String) : RuntimeException(message)
