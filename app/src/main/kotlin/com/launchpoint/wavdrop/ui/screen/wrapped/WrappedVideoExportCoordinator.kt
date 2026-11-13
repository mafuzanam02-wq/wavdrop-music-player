package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P5-WR-VID-02C: the small state machine that runs ONE user-visible video export: immutable plan -> slide PNGs
 * (VID-02A2) -> MP4 (VID-02B). Pure Kotlin: every platform step is injected, so ordering, cancellation, cleanup and
 * stale-callback rules are unit-tested without Compose, Media3 or a device.
 *
 * Contract:
 *  - one export at a time, also across coordinators in this process ([WrappedVideoExportGuard]); a second [start]
 *    while one is active (including while it is still cancelling) is refused, so a tap storm cannot create jobs;
 *  - the plan is the only input; it is immutable and captured by [start], so later changes of the screen's period or
 *    appearance cannot alter the export (the UI never feeds them in);
 *  - cancellation reaches both stages: cancelling the job cancels slide generation (which removes its directory) or
 *    the encoder (which stops Media3 and removes its `.part`);
 *  - the slide directory of THIS export is deleted on every terminal path once the MP4 is published, failed or
 *    cancelled; the published MP4 is never deleted here;
 *  - PUBLICATION POLICY (mirrors [WrappedVideoEncoder]): if the encoder returns `Completed`, the MP4 is on disk, so
 *    the state becomes [WrappedVideoExportState.Completed] even when a cancel arrived at that exact moment; cancel
 *    never turns a published video into "Cancelled";
 *  - every state write carries the run that made it; callbacks of an older run can never touch a newer one.
 */
internal sealed interface WrappedVideoExportState {
    data object Idle : WrappedVideoExportState
    /** Slide generation. No percentage exists for this stage, so none is ever shown; [pageCount] is known up front. */
    data class Preparing(val periodLabel: String, val pageCount: Int) : WrappedVideoExportState
    /** [percent] is null while Media3 reports nothing. */
    data class Encoding(val periodLabel: String, val percent: Int?) : WrappedVideoExportState
    data class Cancelling(val periodLabel: String) : WrappedVideoExportState
    data class Completed(val periodLabel: String, val reportKey: String, val file: File) : WrappedVideoExportState
    data class Failed(val periodLabel: String, val reason: WrappedVideoExportFailure) : WrappedVideoExportState
    data class Cancelled(val periodLabel: String) : WrappedVideoExportState
}

internal enum class WrappedVideoExportFailure { SlidesFailed, EncodingFailed, Unexpected }

internal val WrappedVideoExportState.isActive: Boolean
    get() = this is WrappedVideoExportState.Preparing || this is WrappedVideoExportState.Encoding ||
        this is WrappedVideoExportState.Cancelling

/** Process-wide "one video export at a time"; held from [start] until every cleanup step of that run has finished. */
internal class WrappedVideoExportGuard {
    private val held = AtomicBoolean(false)
    fun tryAcquire(): Boolean = held.compareAndSet(false, true)
    fun release() = held.set(false)
    val isHeld: Boolean get() = held.get()

    companion object {
        val shared = WrappedVideoExportGuard()
    }
}

internal class WrappedVideoExportCoordinator(
    private val scope: CoroutineScope,
    /** Generates the slides (production: mounts the render host, runs [exportWrappedSlides], unmounts it). */
    private val generateSlides: suspend (WrappedExportPlan) -> WrappedExportOutcome,
    /** Encodes the validated slides and reports Media3 progress; returns the encoder's typed outcome. */
    private val encode: suspend (WrappedExportOutcome.Success, WrappedExportPlan, (WrappedVideoState) -> Unit) -> WrappedVideoOutcome,
    /** Removes orphans left by an earlier process death; only runs while the guard is held, i.e. no export is live. */
    private val sweepOrphans: () -> Int = { 0 },
    /** Deletes this export's slide directory; false means the directory could not be fully removed. */
    private val deleteSlides: (File) -> Boolean = { it.deleteRecursively() },
    /** Cleanup problems are observable but never change a published result into a failure. */
    private val onCleanupProblem: (String) -> Unit = {},
    private val guard: WrappedVideoExportGuard = WrappedVideoExportGuard.shared,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<WrappedVideoExportState>(WrappedVideoExportState.Idle)
    val state: StateFlow<WrappedVideoExportState> = _state

    private class Run(val id: Long, val plan: WrappedExportPlan) {
        var job: Job? = null
        var cancelRequested = false
        var terminal = false
    }

    private var current: Run? = null
    private var nextId = 0L

    @OptIn(DelicateCoroutinesApi::class)
    /** Starts exporting [plan]; false (and nothing changes) if an export is already active or the guard is held. */
    fun start(plan: WrappedExportPlan): Boolean {
        val run: Run
        synchronized(lock) {
            if (_state.value.isActive || !guard.tryAcquire()) return false
            run = Run(++nextId, plan)
            current = run
            _state.value = WrappedVideoExportState.Preparing(plan.periodLabel, plan.pageCount)
        }
        try {
            // ATOMIC: a cancel that lands before the first dispatch must still run execute()'s cleanup and release the guard.
            run.job = scope.launch(start = CoroutineStart.ATOMIC) { execute(run) }
        } catch (e: Throwable) {
            synchronized(lock) { finish(run, WrappedVideoExportState.Failed(plan.periodLabel, WrappedVideoExportFailure.Unexpected)) }
            guard.release()
            return false
        }
        return true
    }

    /** Suspends until the current run (including all of its cleanup) has completely finished; returns at once if none. */
    internal suspend fun joinCurrent() {
        val j = synchronized(lock) { current?.job }
        j?.join()
    }

    /** Requests cancellation of the active export. A no-op in every non-active state (a finished MP4 is untouched). */
    fun cancel() {
        val job: Job?
        synchronized(lock) {
            val run = current ?: return
            val s = _state.value
            if (run.terminal || !s.isActive || run.cancelRequested) return
            run.cancelRequested = true
            _state.value = WrappedVideoExportState.Cancelling(run.plan.periodLabel)
            job = run.job
        }
        job?.cancel()
    }

    /** Back to Idle from a finished state (Completed/Failed/Cancelled). Never affects an active export. */
    fun dismiss() {
        synchronized(lock) { if (!_state.value.isActive) _state.value = WrappedVideoExportState.Idle }
    }

    /** The screen is going away: cancel, and drop any finished state (the MP4 file itself stays on disk). */
    fun release() {
        cancel()
        dismiss()
    }

    private fun isLive(run: Run) = current === run && !run.terminal

    /** Applies a progress-type update only while [run] is still the live, uncancelled run. */
    private fun progress(run: Run, newState: WrappedVideoExportState) {
        synchronized(lock) {
            if (isLive(run) && !run.cancelRequested) _state.value = newState
        }
    }

    /** Writes the run's one terminal state. Caller holds the lock. */
    private fun finish(run: Run, terminalState: WrappedVideoExportState) {
        if (current !== run || run.terminal) return
        run.terminal = true
        _state.value = terminalState
    }

    private suspend fun execute(run: Run) {
        val plan = run.plan
        var slideDir: File? = null
        var published: File? = null
        try {
            // Housekeeping only: a failing sweep (old cache files) must never fail the new export.
            try {
                withContext(ioDispatcher) { sweepOrphans() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onCleanupProblem("cache sweep failed: ${e.javaClass.simpleName}")
            }
            currentCoroutineContext().ensureActive()
            val slides = generateSlides(plan)
            val success = when (slides) {
                is WrappedExportOutcome.Failed -> {
                    synchronized(lock) { finish(run, WrappedVideoExportState.Failed(plan.periodLabel, WrappedVideoExportFailure.SlidesFailed)) }
                    return
                }
                is WrappedExportOutcome.Success -> slides
            }
            slideDir = success.directory
            progress(run, WrappedVideoExportState.Encoding(plan.periodLabel, null))
            currentCoroutineContext().ensureActive()
            val outcome = encode(success, plan) { s ->
                if (s is WrappedVideoState.Encoding) progress(run, WrappedVideoExportState.Encoding(plan.periodLabel, s.percent))
            }
            when (outcome) {
                is WrappedVideoOutcome.Completed -> {
                    published = outcome.file
                    // No suspension point between the encoder's commit and this write: a cancel that arrived at the
                    // final moment must not relabel a published MP4 as an incomplete export.
                    synchronized(lock) {
                        finish(run, WrappedVideoExportState.Completed(plan.periodLabel, plan.reportKey, outcome.file))
                    }
                }
                is WrappedVideoOutcome.Failed ->
                    synchronized(lock) { finish(run, WrappedVideoExportState.Failed(plan.periodLabel, WrappedVideoExportFailure.EncodingFailed)) }
            }
        } catch (e: CancellationException) {
            synchronized(lock) { finish(run, WrappedVideoExportState.Cancelled(plan.periodLabel)) }
            throw e
        } catch (e: Exception) {
            synchronized(lock) { finish(run, WrappedVideoExportState.Failed(plan.periodLabel, WrappedVideoExportFailure.Unexpected)) }
        } finally {
            // Always, even when cancelled: this export's slide directory goes away (the MP4, if any, stays), then the
            // process-wide guard is released so the next export may start. Cleanup trouble is reported, never fatal.
            withContext(NonCancellable) {
                val dir = slideDir
                if (dir != null) {
                    try {
                        val removed = withContext(ioDispatcher) { deleteSlides(dir) }
                        if (!removed) onCleanupProblem("slide directory not fully removed: ${dir.name}")
                    } catch (e: Exception) {
                        onCleanupProblem("slide directory cleanup failed: ${dir.name}: ${e.javaClass.simpleName}")
                    }
                }
                synchronized(lock) {
                    // A run that somehow ended without a terminal state must not leave the UI stuck in "active".
                    if (current === run && !run.terminal) {
                        finish(run, WrappedVideoExportState.Failed(plan.periodLabel, WrappedVideoExportFailure.Unexpected))
                    }
                }
                guard.release()
            }
        }
    }
}

/**
 * P5-WR-VID-04: completed Wrapped MP4s become eligible for deletion once strictly older than this. The age is the file's
 * last-modified time, an approximation of "cache age" (it is not a verified export-completion timestamp). The grace
 * period is also the only protection for a video an external app may still be reading: a chooser gives no signal when
 * a receiver finishes, so none is assumed.
 */
internal const val WRAPPED_VIDEO_RETENTION_DAYS = 7L
internal val WRAPPED_VIDEO_RETENTION_MILLIS: Long = java.util.concurrent.TimeUnit.DAYS.toMillis(WRAPPED_VIDEO_RETENTION_DAYS)

/** Exactly the name [wrappedVideoFileName] produces for a UUID export id. */
private val COMPLETED_VIDEO_NAME = Regex(
    "^" + Regex.escape(WRAPPED_VIDEO_FILE_PREFIX) +
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.mp4$",
)

private val sweepLog = java.util.logging.Logger.getLogger("WrappedVideoRetention")

/**
 * True when [file] is a completed-export MP4 that retention may delete: a regular, non-symlink file with the exact
 * `wavdrop_wrapped_<UUID>.mp4` name, directly inside [videoDir] by canonical path, and strictly older than
 * [retentionMillis]. There is no other exemption: age alone decides. A missing/zero timestamp or a future one is never expired; the comparison only subtracts
 * two positive longs with `modified <= now`, so it cannot overflow.
 */
internal fun isExpiredWrappedVideo(
    file: File,
    videoDir: File,
    nowMillis: Long,
    retentionMillis: Long = WRAPPED_VIDEO_RETENTION_MILLIS,
): Boolean = try {
    val path = file.toPath()
    val attrs = java.nio.file.Files.readAttributes(
        path, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS,
    )
    val canonical = file.canonicalFile
    val modified = attrs.lastModifiedTime().toMillis()
    COMPLETED_VIDEO_NAME.matches(file.name) &&
        attrs.isRegularFile && !attrs.isSymbolicLink &&
        canonical.parentFile == videoDir.canonicalFile && canonical.name == file.name &&
        modified > 0L && modified <= nowMillis && nowMillis - modified > retentionMillis
} catch (e: java.io.IOException) {
    false
} catch (e: SecurityException) {
    false
}

/**
 * Housekeeping that runs only while the export guard is held (so no export of this process can own anything below):
 *  - removes what an earlier PROCESS left behind: slide directories named by the export prefix and `.part` files;
 *  - removes completed MP4s that are strictly older than the retention period (see [isExpiredWrappedVideo]). The seven
 *    days are the grace window for an external app still holding a shared video; no video is exempt merely because it
 *    was the last one published. The export about to start has not created its file yet, so it can never be swept.
 * Every other name, folder and file is never touched. A deletion that fails is reported through [onProblem] and skipped;
 * nothing here throws for a single bad entry. Returns the number of entries removed.
 */
internal fun sweepWrappedVideoOrphans(
    slideBaseDir: File,
    videoDir: File,
    nowMillis: Long = System.currentTimeMillis(),
    deleteFile: (File) -> Boolean = { it.delete() },
    onProblem: (String) -> Unit = { sweepLog.warning(it) },
): Int {
    var removed = 0
    slideBaseDir.listFiles()?.forEach { f ->
        if (f.isDirectory && f.name.startsWith(WRAPPED_EXPORT_DIR_PREFIX) && f.deleteRecursively()) removed++
    }
    videoDir.listFiles()?.forEach { f ->
        try {
            val orphanPart = f.isFile && f.name.startsWith(WRAPPED_VIDEO_FILE_PREFIX) && f.name.endsWith(".part")
            val expired = !orphanPart && isExpiredWrappedVideo(f, videoDir, nowMillis)
            if (orphanPart || expired) {
                if (deleteFile(f)) removed++ else onProblem("could not delete ${if (expired) "expired video" else "part file"}: ${f.name}")
            }
        } catch (e: Exception) {
            onProblem("cleanup failed for ${f.name}: ${e.javaClass.simpleName}")
        }
    }
    return removed
}

internal const val WRAPPED_VIDEO_DIR = "wrapped_video_export"
internal const val WRAPPED_VIDEO_FILE_PREFIX = "wavdrop_wrapped_"

internal fun wrappedVideoDir(cacheDir: File): File = File(cacheDir, WRAPPED_VIDEO_DIR)

internal fun wrappedVideoFileName(plan: WrappedExportPlan): String = "$WRAPPED_VIDEO_FILE_PREFIX${plan.exportId}.mp4"

/** Export is offered only for a loaded report that has something to show. */
internal fun isWrappedVideoExportEligible(summary: com.launchpoint.wavdrop.data.model.WrappedSummary): Boolean =
    !summary.emptyState.isEmpty
