package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * P5-WR-VID-02B: lifecycle wrapper that turns a successful slide export into one MP4. All platform work sits behind
 * [WrappedVideoEngine] (Media3 Transformer in production), so ordering, ownership, cancellation and cleanup are
 * unit-tested here with fakes and never need a hardware codec.
 *
 * Contract:
 *  - input is the typed result of `generateWrappedSlides`, validated first ([validateWrappedVideoInput]);
 *  - output is written to a uniquely named `<name>.<uuid>.part` owned by this call, verified with [inspect] and only
 *    then moved to `<name>` without replacing anything; a completed MP4 is never overwritten;
 *  - on failure OR cancellation the `.part` file is removed; the source PNG directory, the manifest, previous videos
 *    and unrelated files are never touched (the caller owns the slides' lifetime);
 *  - the engine is genuinely cancelled and released on every path; a terminal result is delivered exactly once and
 *    callbacks arriving after cancellation are ignored; only one encode runs at a time per [WrappedVideoEncoder].
 */
internal enum class WrappedVideoFailure {
    Busy, InvalidInput, InvalidOutputName, OutputDirectoryUnavailable, OutputExists, EngineFailed, OutputInvalid,
    FinalizeFailed, Unexpected,
}

internal sealed interface WrappedVideoState {
    data object Preparing : WrappedVideoState
    /** [percent] is null when Media3 reports no progress; no value is ever invented. */
    data class Encoding(val percent: Int?) : WrappedVideoState
    data object Completed : WrappedVideoState
    data class Failed(val failure: WrappedVideoFailure) : WrappedVideoState
    data object Cancelled : WrappedVideoState
}

internal sealed interface WrappedVideoOutcome {
    class Completed(val file: File, val info: WrappedVideoInfo, val sizeBytes: Long, val encodeMs: Long) : WrappedVideoOutcome
    class Failed(val failure: WrappedVideoFailure, val detail: String) : WrappedVideoOutcome
}

/** What the platform encoder is asked to do: ordered still images, a duration each, one frame rate. */
internal class WrappedVideoRequest(val slides: List<WrappedVideoSlide>, val frameRate: Int)

internal class WrappedVideoEngineError(val code: String, val message: String)

internal interface WrappedVideoEngine {
    interface Listener {
        fun onCompleted()
        fun onError(error: WrappedVideoEngineError)
    }

    /** Must return promptly; completion arrives through [listener], possibly on another thread. */
    fun start(request: WrappedVideoRequest, outputPath: String, listener: Listener)

    /** Stops the encode and returns once the platform encoder has been released. */
    suspend fun cancel()

    /** Percentage 0..100, or null when the platform cannot report progress. */
    suspend fun progressPercent(): Int?

    fun release()
}

/** What [WrappedVideoInspector] reads back from a finished file (rotation is applied by [displayWidth]/[displayHeight]). */
internal data class WrappedVideoInfo(
    val videoMime: String?,
    val videoTrackCount: Int,
    val audioTrackCount: Int,
    val codedWidth: Int,
    val codedHeight: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
) {
    private val swapped get() = rotationDegrees == 90 || rotationDegrees == 270
    val displayWidth: Int get() = if (swapped) codedHeight else codedWidth
    val displayHeight: Int get() = if (swapped) codedWidth else codedHeight
}

/** Two frames at 30 fps: the documented tolerance between the planned and the container duration. */
internal const val WRAPPED_VIDEO_DURATION_TOLERANCE_MS = 2 * 1000L / WRAPPED_VIDEO_FRAME_RATE

/** Returns every reason the file is NOT an acceptable Wrapped video (empty list = acceptable). */
internal fun verifyWrappedVideoInfo(
    info: WrappedVideoInfo?,
    expectedDurationMs: Long,
    widthPx: Int = WRAPPED_EXPORT_WIDTH_PX,
    heightPx: Int = WRAPPED_EXPORT_HEIGHT_PX,
    toleranceMs: Long = WRAPPED_VIDEO_DURATION_TOLERANCE_MS,
): List<String> {
    if (info == null) return listOf("the file could not be opened as media")
    val problems = ArrayList<String>()
    if (info.videoTrackCount != 1) problems += "expected 1 video track, found ${info.videoTrackCount}"
    if (info.videoMime != "video/avc") problems += "video codec is ${info.videoMime}, expected video/avc"
    if (info.audioTrackCount != 0) problems += "unexpected audio track(s): ${info.audioTrackCount}"
    if (info.displayWidth != widthPx || info.displayHeight != heightPx) {
        problems += "displays as ${info.displayWidth}x${info.displayHeight} " +
            "(coded ${info.codedWidth}x${info.codedHeight}, rotation ${info.rotationDegrees}), expected ${widthPx}x$heightPx"
    }
    if (info.displayHeight <= info.displayWidth) problems += "not portrait"
    if (abs(info.durationMs - expectedDurationMs) > toleranceMs) {
        problems += "duration ${info.durationMs} ms differs from planned $expectedDurationMs ms by more than $toleranceMs ms"
    }
    return problems
}

internal class WrappedVideoEncoder(
    private val engineFactory: () -> WrappedVideoEngine,
    /** Reads a finished file back (platform media APIs); null when it cannot be opened. */
    private val inspect: suspend (File) -> WrappedVideoInfo?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decodable: (File) -> Boolean = { true },
    private val progressIntervalMs: Long = 250L,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val busy = AtomicBoolean(false)

    /** Callbacks that arrived after their encode had already ended (cancelled/finished); observable for tests. */
    val ignoredLateCallbacks = AtomicInteger(0)

    /** Delivers at most one terminal event for one encode, however many callbacks the platform fires. */
    private class Terminal {
        private val done = AtomicBoolean(false)
        fun claim(): Boolean = done.compareAndSet(false, true)
    }

    suspend fun encode(
        export: WrappedExportOutcome.Success,
        outputDir: File,
        outputName: String,
        onState: (WrappedVideoState) -> Unit = {},
    ): WrappedVideoOutcome {
        if (!busy.compareAndSet(false, true)) return WrappedVideoOutcome.Failed(WrappedVideoFailure.Busy, "another encode is running")
        var engine: WrappedVideoEngine? = null
        var part: File? = null
        var finalized = false
        try {
            onState(WrappedVideoState.Preparing)
            if (!isSafeVideoName(outputName)) {
                return fail(onState, WrappedVideoFailure.InvalidOutputName, outputName)
            }
            val validation = withContext(ioDispatcher) { validateWrappedVideoInput(export, decodable = decodable) }
            val input = when (validation) {
                is WrappedVideoInputResult.Invalid ->
                    return fail(onState, WrappedVideoFailure.InvalidInput, "${validation.problem}: ${validation.detail}")
                is WrappedVideoInputResult.Valid -> validation.input
            }
            val target = File(outputDir, outputName)
            // OWNERSHIP: `part` is non-null only if THIS call's exclusive createNewFile() succeeded. The step is the nested
            // `withContext(NonCancellable) { withContext(io) { .. } }` so its result always reaches the assignment below,
            // even if the caller is cancelled while the dispatcher returns; there is no suspension point between the file
            // being created and `part` being recorded, so the finally block can always delete it. A pre-existing file is
            // never owned (createNewFile fails), and the directory itself is shared and never deleted.
            val prepared = withContext(NonCancellable) {
                withContext(ioDispatcher) {
                    when {
                        !(outputDir.isDirectory || outputDir.mkdirs()) -> WrappedVideoFailure.OutputDirectoryUnavailable to null
                        target.exists() -> WrappedVideoFailure.OutputExists to null
                        else -> {
                            val p = File(outputDir, "$outputName.${UUID.randomUUID()}.part")
                            if (p.createNewFile()) null to p else WrappedVideoFailure.OutputDirectoryUnavailable to null
                        }
                    }
                }
            }
            part = prepared.second
            // A caller cancelled during setup gets the cancellation (not a stale failure or a started encode); the
            // finally block then removes the part file recorded above.
            currentCoroutineContext().ensureActive()
            if (part == null) return fail(onState, prepared.first!!, outputName)

            engine = engineFactory()
            val startedAt = nowNanos()
            onState(WrappedVideoState.Encoding(null))
            val engineError = runEngine(engine, WrappedVideoRequest(input.slides, WRAPPED_VIDEO_FRAME_RATE), part, onState)
            if (engineError != null) {
                return fail(onState, WrappedVideoFailure.EngineFailed, "${engineError.code}: ${engineError.message}")
            }
            val info = inspect(part)
            val problems = verifyWrappedVideoInfo(info, input.totalDurationMs)
            if (problems.isNotEmpty()) return fail(onState, WrappedVideoFailure.OutputInvalid, problems.joinToString("; "))
            // PUBLICATION POLICY. The commit point is the successful Files.move below.
            //  - Cancelled BEFORE the move starts: cancellation wins; nothing is published and the part file is deleted.
            //  - The move itself runs as nested NonCancellable work, so its result is always delivered here, whatever the
            //    caller does meanwhile. It never replaces an existing destination.
            //  - Move succeeded: the video IS published. `finalized` is set with no suspension point in between, the file
            //    is kept (never deleted as an unfinished part), and Completed is returned even if the caller was cancelled
            //    at that exact moment; the caller's cancellation then surfaces at its next suspension point.
            //  - Move failed: FinalizeFailed, and the part file is deleted.
            currentCoroutineContext().ensureActive()
            val moved = withContext(NonCancellable) {
                withContext(ioDispatcher) {
                    try {
                        Files.move(part.toPath(), target.toPath()) // never replaces an existing file
                        true
                    } catch (e: FileAlreadyExistsException) {
                        false
                    } catch (e: java.io.IOException) {
                        false
                    }
                }
            }
            if (!moved) return fail(onState, WrappedVideoFailure.FinalizeFailed, outputName)
            finalized = true
            val encodeMs = (nowNanos() - startedAt) / 1_000_000
            onState(WrappedVideoState.Completed)
            return WrappedVideoOutcome.Completed(target, info!!, target.length(), encodeMs)
        } catch (e: CancellationException) {
            onState(WrappedVideoState.Cancelled)
            throw e
        } catch (e: Exception) {
            return fail(onState, WrappedVideoFailure.Unexpected, e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            // Always: stop the platform encoder, then remove only the part file this call created.
            withContext(NonCancellable) {
                if (!finalized) engine?.let { runCatching { it.cancel() } }
                engine?.let { runCatching { it.release() } }
                withContext(ioDispatcher) { if (!finalized) part?.delete() }
                busy.set(false)
            }
        }
    }

    private fun fail(onState: (WrappedVideoState) -> Unit, f: WrappedVideoFailure, detail: String): WrappedVideoOutcome {
        onState(WrappedVideoState.Failed(f))
        return WrappedVideoOutcome.Failed(f, detail)
    }

    /** Returns null on success or the engine's error. Cancels the engine itself if the caller is cancelled. */
    private suspend fun runEngine(
        engine: WrappedVideoEngine,
        request: WrappedVideoRequest,
        part: File,
        onState: (WrappedVideoState) -> Unit,
    ): WrappedVideoEngineError? = coroutineScope {
        val progress = launch {
            while (isActive) {
                delay(progressIntervalMs)
                engine.progressPercent()?.let { onState(WrappedVideoState.Encoding(it)) }
            }
        }
        try {
            suspendCancellableCoroutine<WrappedVideoEngineError?> { cont ->
                val terminal = Terminal()
                fun once(error: WrappedVideoEngineError?) {
                    if (terminal.claim() && cont.isActive) cont.resumeWith(Result.success(error)) else ignoredLateCallbacks.incrementAndGet()
                }
                cont.invokeOnCancellation { terminal.claim() } // later callbacks of this run are ignored, never delivered
                try {
                    engine.start(request, part.path, object : WrappedVideoEngine.Listener {
                        override fun onCompleted() = once(null)
                        override fun onError(error: WrappedVideoEngineError) = once(error)
                    })
                } catch (e: Exception) {
                    once(WrappedVideoEngineError("START_FAILED", e.message ?: e.javaClass.simpleName))
                }
            }
        } finally {
            progress.cancel()
        }
    }

    private fun isSafeVideoName(name: String) =
        name.endsWith(".mp4") && name.length > 4 && name == File(name).name &&
            name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' } && !name.startsWith(".")
}
