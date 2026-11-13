package com.launchpoint.wavdrop.ui.screen.wrapped

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * P5-WR-VID-02B: Media3 Transformer 1.11.1 adapter. One sequence of still images (an image item needs an explicit
 * duration and frame rate), hard cuts, H.264, no audio, fully offline. No effect is applied: the 720x1280 PNGs are
 * already the target size, so `media3-effect` is not used directly.
 */
internal fun buildWrappedVideoComposition(request: WrappedVideoRequest): Composition {
    val items = request.slides.map { slide ->
        // 1.11.1: a still image needs its display duration on the MediaItem (setImageDurationMs); without it the
        // asset loader fails with "Could not retrieve required duration". The EditedMediaItem duration mirrors it.
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(slide.file))
            .setImageDurationMs(slide.durationMs.toLong())
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setDurationUs(slide.durationMs * 1_000L)
            .setFrameRate(request.frameRate)
            .build()
    }
    return Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
}

/**
 * Transformer must be created, started, queried and cancelled on ONE application looper (the main looper unless told
 * otherwise); every such call is therefore posted to [looper]. The encode itself runs on Transformer's own threads, so
 * the UI thread is never blocked: this class only posts short calls and suspends for their results.
 */
internal class Media3WrappedVideoEngine(
    context: Context,
    private val looper: Looper = Looper.getMainLooper(),
) : WrappedVideoEngine {
    private val appContext = context.applicationContext
    private val handler = Handler(looper)

    // Touched only on [looper].
    private var transformer: Transformer? = null

    override fun start(request: WrappedVideoRequest, outputPath: String, listener: WrappedVideoEngine.Listener) {
        handler.post {
            try {
                val t = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    // Encode the portrait frames as portrait where the encoder allows it, instead of coding a
                    // landscape file that only a rotation flag turns upright.
                    .setPortraitEncodingEnabled(true)
                    .setLooper(looper)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            listener.onCompleted()
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            // Keep the whole cause chain: "Asset loader error" alone says nothing about why.
                            val chain = generateSequence<Throwable>(exportException) { it.cause }
                                .joinToString(" <- ") { it.javaClass.simpleName + ": " + (it.message ?: "") }
                            listener.onError(WrappedVideoEngineError(exportException.errorCodeName, chain))
                        }
                    })
                    .build()
                transformer = t
                t.start(buildWrappedVideoComposition(request), outputPath)
            } catch (e: Exception) {
                listener.onError(WrappedVideoEngineError("START_FAILED", e.message ?: e.javaClass.simpleName))
            }
        }
    }

    override suspend fun cancel() = suspendCancellableCoroutine<Unit> { cont ->
        handler.post {
            try {
                transformer?.cancel() // the real Transformer cancellation, not just the waiting coroutine
            } finally {
                cont.resumeWith(Result.success(Unit))
            }
        }
    }

    override suspend fun progressPercent(): Int? = suspendCancellableCoroutine { cont ->
        handler.post {
            val holder = ProgressHolder()
            val state = transformer?.getProgress(holder)
            cont.resumeWith(Result.success(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null))
        }
    }

    override fun release() {
        handler.post {
            transformer?.removeAllListeners() // a stale callback can never reach a later encode
            transformer = null
        }
    }
}

/** Reads a finished MP4 back with the platform media APIs (not with Transformer's own report). */
internal suspend fun inspectWrappedVideo(file: File): WrappedVideoInfo? = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    val retriever = MediaMetadataRetriever()
    try {
        extractor.setDataSource(file.path)
        var videoMime: String? = null
        var videos = 0
        var audios = 0
        var w = 0
        var h = 0
        var durationUs = 0L
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            when {
                mime.startsWith("video/") -> {
                    videos++
                    videoMime = mime
                    w = f.getInteger(MediaFormat.KEY_WIDTH)
                    h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = f.getLong(MediaFormat.KEY_DURATION)
                }
                mime.startsWith("audio/") -> audios++
            }
        }
        retriever.setDataSource(file.path)
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        WrappedVideoInfo(videoMime, videos, audios, w, h, rotation, durationUs / 1_000)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
        runCatching { retriever.release() }
    }
}

/** Bounds-only decode: proves the platform can read the PNG without allocating its pixels. */
internal fun wrappedSlideDecodable(file: File): Boolean {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    return o.outWidth > 0 && o.outHeight > 0
}
