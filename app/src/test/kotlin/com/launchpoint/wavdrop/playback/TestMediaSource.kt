package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.robolectric.Shadows.shadowOf

/**
 * CF-2M4 test infrastructure: a REAL ExoPlayer that needs no codecs. Every media item becomes a trackless, instantly-prepared
 * source of [DURATION_US]; the renderer list is empty. The player, its Timeline, its playlist-edit semantics and its
 * playback thread are genuine Media3, so graft behaviour is proven on the real implementation, not on a model of it.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object TestMediaSource {
    const val DURATION_US = 180_000_000L

    private class Source(private val item: MediaItem) : BaseMediaSource() {
        override fun getMediaItem(): MediaItem = item
        override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
            refreshSourceInfo(SinglePeriodTimeline(DURATION_US, true, false, false, null, item))
        }

        override fun maybeThrowSourceInfoRefreshError() = Unit
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod = Period()
        override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit
        override fun releaseSourceInternal() = Unit
    }

    private class Period : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) = callback.onPrepared(this)
        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY
        override fun selectTracks(
            selections: Array<ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long = positionUs

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long = positionUs
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs
        override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false
        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    private val factory = object : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = this
        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = this
        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = Source(mediaItem)
    }

    private fun mixedFactory(context: Context, fakeMediaId: String) = object : MediaSource.Factory {
        private val production = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = this
        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = this
        override fun getSupportedTypes(): IntArray = production.supportedTypes
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            if (mediaItem.mediaId == fakeMediaId) Source(mediaItem) else production.createMediaSource(mediaItem)
    }

    /**
     * A genuine ExoPlayer (no focus, no noisy handling) over the trackless source. With [productionFactoryExceptMediaId] set,
     * every item EXCEPT that one is created by Media3's real DefaultMediaSourceFactory (production per-item cost; those
     * sources are never prepared), so graft timing includes real media-source construction.
     */
    fun newPlayer(context: Context, sessionId: Int? = null, productionFactoryExceptMediaId: String? = null): ExoPlayer =
        ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf<Renderer>(object : androidx.media3.exoplayer.NoSampleRenderer() { override fun getName(): String = "NoSample"; override fun render(positionUs: Long, elapsedRealtimeUs: Long) = Unit }) })
            .setMediaSourceFactory(if (productionFactoryExceptMediaId == null) factory else mixedFactory(context, productionFactoryExceptMediaId))
            .build()
            .also { if (sessionId != null) it.audioSessionId = sessionId }

    /** Lets the real playback thread and main looper run for [ms] so any asynchronous report would have arrived. */
    fun settle(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Idles the main looper until [condition] holds (the real playback thread posts back asynchronously). */
    fun awaitCondition(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "condition not reached within ${timeoutMs}ms" }
    }
}
