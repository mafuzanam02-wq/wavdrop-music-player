package com.launchpoint.wavdrop.playback

import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteMisuseException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import com.launchpoint.wavdrop.data.repository.StatsEventConflictException
import com.launchpoint.wavdrop.data.repository.StatsWriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/*
 * P5-STATS-19C - bounded, serialized, in-process delivery of playback statistics.
 *
 * What it guarantees:  playback callbacks never wait for database I/O (submit is a non-suspending, non-blocking channel offer); tasks
 *   are written one at a time, in submission order; every task keeps the identity, payload and detection timestamp it was created
 *   with; transient failures retry the EXACT same task (same event id) so a commit whose acknowledgement was lost is recognised by the
 *   idempotent writer as ALREADY_APPLIED; memory is bounded (queue + parked + one in flight).
 * What it does NOT guarantee: durability across process death (there is no outbox), or delivery when the bounded buffers overflow.
 *   Overflow and exhaustion are never silent: every loss is reported as a StatsDeliveryOutcome and counted. See the DECISION REQUIRED
 *   in the slice report for the queue-full / eviction policy.
 */

/** One unit of work. Captured at detection time and never mutated, so a retry is byte-for-byte the original request. */
internal sealed class StatsTask {
    /** Diagnostic label only. Carries ids, never content URIs or titles. */
    abstract val key: String

    data class Play(
        val occurrence: PlayOccurrence, val songId: Long, val contentUri: String, val listenedMs: Long, val durationMs: Long,
    ) : StatsTask() { override val key get() = "PLAY:${occurrence.eventId}" }

    data class Skip(
        val occurrence: PlayOccurrence, val songId: Long, val contentUri: String, val durationMs: Long,
    ) : StatsTask() { override val key get() = "SKIP:${occurrence.eventId}" }

    data class ListenStart(val occurredAtMs: Long, val songId: Long, val contentUri: String) : StatsTask() {
        override val key get() = "LISTEN:$songId@$occurredAtMs"
    }
}

/** What kind of failure a write raised, which decides whether (and how often) it is retried. */
internal enum class StatsFailureKind {
    /** SQLite busy / locked contention: transient. */
    BUSY,

    /** Disk I/O error or storage temporarily full: transient. */
    TRANSIENT_IO,

    /** Constraint, corruption, read-only, bad arguments, misuse...: retrying cannot help. */
    PERMANENT,

    /** [StatsEventConflictException]: the id is stored with a different payload. Never retried. */
    CONFLICT,

    /** Anything unrecognised. Retried once (the write is idempotent) and then abandoned, not parked. */
    UNKNOWN,
}

internal object StatsFailureClassifier {
    /** Cancellation is NOT a failure and must be rethrown by the caller before classification. */
    fun classify(t: Throwable): StatsFailureKind = when {
        t is StatsEventConflictException -> StatsFailureKind.CONFLICT
        t is IllegalArgumentException -> StatsFailureKind.PERMANENT
        t is SQLiteDatabaseLockedException -> StatsFailureKind.BUSY
        t is SQLiteConstraintException || t is SQLiteDatabaseCorruptException || t is SQLiteReadOnlyDatabaseException ||
            t is SQLiteAccessPermException || t is SQLiteMisuseException || t is SQLiteCantOpenDatabaseException ||
            t is SQLiteBindOrColumnIndexOutOfRangeException -> StatsFailureKind.PERMANENT
        t is SQLiteDiskIOException || t is SQLiteFullException || t is java.io.IOException -> StatsFailureKind.TRANSIENT_IO
        t is SQLiteException && looksBusy(t.message) -> StatsFailureKind.BUSY
        else -> StatsFailureKind.UNKNOWN
    }

    private fun looksBusy(m: String?): Boolean {
        val s = m?.lowercase() ?: return false
        return "database is locked" in s || "sqlite_busy" in s || "(code 5" in s || "(code 6" in s
    }
}

/** Delivery states, as seen by diagnostics. */
internal enum class StatsDeliveryState {
    /** Written for the first time. */
    APPLIED,

    /** Already stored (e.g. an earlier attempt committed but its acknowledgement was lost). */
    ALREADY_APPLIED,

    /** An attempt failed transiently; the same task will be tried again after a delay. */
    RETRYING,

    /** In-call retries ran out; the task is retained (bounded) for one more try after the next successful write. */
    UNRESOLVED,

    /** The task will not be written: retries/rounds exhausted, evicted, or cancelled. DATA LOSS - see [StatsDeliveryOutcome.detail]. */
    ABANDONED,

    /** A non-retryable failure (permanent / integrity conflict). DATA LOSS. */
    FAILED,

    /** Never accepted: the queue was full, or the delivery scope was shut down. DATA LOSS. */
    REJECTED,
}

internal data class StatsDeliveryOutcome(
    val key: String,
    val state: StatsDeliveryState,
    val attempt: Int,
    val failure: StatsFailureKind? = null,
    val detail: String? = null,
)

/** All limits in one place so the policy is explicit and testable. Defaults are conservative: a few seconds, never minutes. */
internal data class StatsDeliveryPolicy(
    /** Maximum tasks waiting to be written. A full queue REJECTS the new task (see class comment). */
    val queueCapacity: Int = 256,
    /** Delays before attempts 2..n for BUSY / TRANSIENT_IO. Attempts = size + 1. Total wait 4.25 s. */
    val retryDelaysMs: List<Long> = listOf(250L, 1_000L, 3_000L),
    /** Delays for UNKNOWN failures: one retry only. */
    val unknownRetryDelaysMs: List<Long> = listOf(250L),
    /** Maximum tasks retained after in-call retries are exhausted; the oldest is evicted (reported) beyond this. */
    val parkedCapacity: Int = 32,
    /** How many redelivery rounds a parked task gets (the in-call retry sequence counts as round 1). */
    val maxRounds: Int = 3,
)


/**
 * Single-consumer queue. Consumer lifecycle: started lazily by [submit], restarted by the next [submit] if it ever stops while the scope
 * is still active, and ended for good only when the PARENT scope ends.
 *
 * P5-STATS-19C1 - accounting. Every ACCEPTED task is wrapped in an [Entry] that can report a terminal outcome (APPLIED, ALREADY_APPLIED,
 * FAILED, ABANDONED) at most once. Cancelling a worker alone (scope still active) abandons only its in-flight task: buffered and parked
 * tasks are kept and the next [submit] restarts the consumer. When the PARENT scope ends, [terminate] reports every remaining task
 * (in-flight via the consumer, buffered, parked) as ABANDONED. Cancelling a scope does NOT by itself cancel the [Channel] or run its
 * `onUndeliveredElement`, so [terminate] drains the buffer itself with `tryReceive`; it runs from three places, each idempotent:
 * the consumer's cancellation path, a completion handler registered on the parent Job (covers "no consumer alive"), and [submit] on a
 * dead scope. It is memory-only: no suspension, no database access.
 */
internal class StatsDeliveryQueue(
    private val scopeProvider: () -> CoroutineScope,
    private val writer: PlayEventWriter,
    private val policy: StatsDeliveryPolicy = StatsDeliveryPolicy(),
    private val delayer: suspend (Long) -> Unit = { delay(it) },
    /** Test seam: called just BEFORE a non-terminal report is attempted (the window in which termination could intervene). */
    private val beforeProgressReport: (StatsDeliveryState) -> Unit = {},
    private val onOutcome: (StatsDeliveryOutcome) -> Unit = {},
) {
    /**
     * An accepted task. P5-STATS-19C2: every report about it goes through [report], which holds THIS task's own monitor while it emits, so
     * (a) a terminal outcome is reported at most once and (b) once it has been reported, no RETRYING / UNRESOLVED can follow it: a
     * non-terminal report that loses the race is suppressed. Only this per-task monitor is held while the listener runs - never the
     * queue lock - and nothing else is acquired under it, so it cannot deadlock.
     */
    private class Entry(val task: StatsTask) {
        private var terminal = false            // guarded by this
        fun report(isTerminal: Boolean, emit: () -> Unit): Boolean = synchronized(this) {
            if (terminal) return false
            if (isTerminal) terminal = true
            emit()
            true
        }
    }

    private class Parked(val entry: Entry, val rounds: Int)

    private val channel = Channel<Entry>(
        capacity = policy.queueCapacity,
        // Only fires if an element was handed to a receiver that was cancelled before taking it (or the channel itself is cancelled,
        // which this class never does). It must NOT be relied on for scope cancellation; see terminate().
        onUndeliveredElement = { it.finish(StatsDeliveryState.ABANDONED, 0, null, "cancelled-before-delivery") },
    )
    private val lock = Any()                           // guards consumer, watchedJob, terminated and parked
    private var consumer: Job? = null
    private var watchedJob: Job? = null
    private var terminated = false
    private val parked = ArrayDeque<Parked>()
    private val parkedSize = AtomicInteger(0)
    private val counts = Array(StatsDeliveryState.values().size) { AtomicInteger(0) }

    /** Number of times [state] has been reported. Thread-safe; for diagnostics and tests. */
    fun count(state: StatsDeliveryState): Int = counts[state.ordinal].get()

    /** Tasks currently retained after exhausting their in-call retries. */
    val parkedCount: Int get() = parkedSize.get()

    /** Offers [task] without ever blocking or suspending. Returns false (and reports REJECTED) if it was not accepted. */
    fun submit(task: StatsTask): Boolean {
        val scope = scopeProvider()
        if (!scope.isActive) {
            terminate()                                // account for anything still buffered or parked
            emit(task.key, StatsDeliveryState.REJECTED, 0, null, "scope-cancelled")
            return false
        }
        val sent = channel.trySend(Entry(task))
        if (sent.isFailure) {
            emit(task.key, StatsDeliveryState.REJECTED, 0, null, if (sent.isClosed) "closed" else "queue-full")
            return false
        }
        ensureConsumer(scope)
        return true
    }

    private fun ensureConsumer(scope: CoroutineScope) {
        var watch: Job? = null
        synchronized(lock) {
            val job = scope.coroutineContext[Job]
            if (job != null && job !== watchedJob) { watchedJob = job; watch = job }
            if (consumer?.isActive != true) consumer = scope.launch { runConsumer() }
        }
        // Registered outside the lock: if the Job already completed the handler runs immediately.
        watch?.invokeOnCompletion { terminate() }
    }

    private suspend fun runConsumer() {
        try {
            while (true) {
                val entry = channel.receiveCatching().getOrNull() ?: return     // closed by terminate()
                try {
                    deliver(entry)
                } catch (e: CancellationException) {
                    entry.finish(StatsDeliveryState.ABANDONED, 0, null, "cancelled-in-flight")
                    throw e
                } catch (t: Throwable) {
                    // Defensive: deliver() classifies write failures itself, so only a bug or a throwing listener lands here.
                    // The consumer must survive it.
                    entry.finish(StatsDeliveryState.ABANDONED, 0, StatsFailureKind.UNKNOWN, "internal-error: ${t.javaClass.simpleName}")
                }
            }
        } catch (e: CancellationException) {
            // A cancelled WORKER under a live scope keeps buffered and parked tasks (the next submit restarts it); only the end of the
            // PARENT scope is terminal.
            if (!scopeProvider().isActive) terminate()
            throw e
        }
    }

    /**
     * Terminal, memory-only cleanup for the end of the parent scope. Idempotent and thread-safe. Closes the channel, then reports every
     * buffered and parked task as ABANDONED. The in-flight task is reported by the consumer's own cancellation path.
     */
    private fun terminate() {
        val parkedSnapshot: List<Parked>
        synchronized(lock) {
            if (terminated) return
            terminated = true
            parkedSnapshot = parked.toList()
            parked.clear()
            parkedSize.set(0)
        }
        channel.close()
        while (true) {
            val entry = channel.tryReceive().getOrNull() ?: break
            entry.finish(StatsDeliveryState.ABANDONED, 0, null, "scope-cancelled-buffered")
        }
        parkedSnapshot.forEach { it.entry.finish(StatsDeliveryState.ABANDONED, it.rounds, null, "scope-cancelled-parked") }
    }

    private suspend fun attemptOnce(task: StatsTask): StatsWriteResult = when (task) {
        is StatsTask.Play -> writer.recordPlayOccurrence(task.occurrence, task.songId, task.contentUri, task.listenedMs, task.durationMs)
        is StatsTask.Skip -> writer.recordSkipOccurrence(task.occurrence, task.songId, task.contentUri, task.durationMs)
        is StatsTask.ListenStart -> {
            writer.recordListenStartAt(task.occurredAtMs, task.songId, task.contentUri)
            StatsWriteResult.APPLIED
        }
    }

    private fun success(entry: Entry, result: StatsWriteResult, attempt: Int) = entry.finish(
        if (result == StatsWriteResult.ALREADY_APPLIED) StatsDeliveryState.ALREADY_APPLIED else StatsDeliveryState.APPLIED,
        attempt, null, null,
    )

    private suspend fun deliver(entry: Entry) {
        val task = entry.task
        var attempt = 0
        while (true) {
            attempt++
            var result: StatsWriteResult? = null
            var thrown: Throwable? = null
            try {
                result = attemptOnce(task)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                thrown = t
            }
            if (result != null) {
                success(entry, result, attempt)
                redeliverParked()           // the database just worked: give retained tasks another chance
                return
            }
            val failure: Throwable = thrown!!
            val kind = StatsFailureClassifier.classify(failure)
            if (kind == StatsFailureKind.PERMANENT || kind == StatsFailureKind.CONFLICT) {
                entry.finish(StatsDeliveryState.FAILED, attempt, kind, failure.javaClass.simpleName)
                return
            }
            val delays = if (kind == StatsFailureKind.UNKNOWN) policy.unknownRetryDelaysMs else policy.retryDelaysMs
            if (attempt - 1 < delays.size) {
                entry.progress(StatsDeliveryState.RETRYING, attempt, kind, failure.javaClass.simpleName)
                delayer(delays[attempt - 1])  // cancellable: cancelling the scope ends the retry sequence
                continue
            }
            if (kind == StatsFailureKind.UNKNOWN) {
                entry.finish(StatsDeliveryState.ABANDONED, attempt, kind, "retries-exhausted-unknown")
            } else {
                park(Parked(entry, rounds = 1), attempt, kind)
            }
            return
        }
    }

    private fun park(p: Parked, attempt: Int, kind: StatsFailureKind) {
        var evicted: Parked? = null
        var dead = false
        synchronized(lock) {
            if (terminated) {
                dead = true
            } else {
                if (parked.size >= policy.parkedCapacity) evicted = parked.removeFirst()
                parked.addLast(p)
                parkedSize.set(parked.size)
            }
        }
        evicted?.entry?.finish(StatsDeliveryState.ABANDONED, 0, null, "parked-evicted")
        if (dead) p.entry.finish(StatsDeliveryState.ABANDONED, attempt, kind, "scope-cancelled-parked")
        else p.entry.progress(StatsDeliveryState.UNRESOLVED, attempt, kind, "parked-round-${p.rounds}")
    }

    /** One attempt per retained task, oldest first. Stops at the first transient failure (the database is still unhealthy). */
    private suspend fun redeliverParked() {
        val batch = synchronized(lock) {
            if (parked.isEmpty()) return
            parked.toList().also { parked.clear(); parkedSize.set(0) }
        }
        val keep = ArrayList<Parked>()
        var stopped = false
        try {
            for ((i, p) in batch.withIndex()) {
                if (stopped) { keep += p; continue }
                try {
                    success(p.entry, attemptOnce(p.entry.task), p.rounds + 1)
                } catch (e: CancellationException) {
                    keep += batch.subList(i, batch.size)
                    throw e
                } catch (t: Throwable) {
                    val kind = StatsFailureClassifier.classify(t)
                    when {
                        kind == StatsFailureKind.PERMANENT || kind == StatsFailureKind.CONFLICT ->
                            p.entry.finish(StatsDeliveryState.FAILED, p.rounds + 1, kind, t.javaClass.simpleName)
                        p.rounds + 1 >= policy.maxRounds ->
                            p.entry.finish(StatsDeliveryState.ABANDONED, p.rounds + 1, kind, "parked-rounds-exhausted")
                        else -> {
                            keep += Parked(p.entry, p.rounds + 1)
                            p.entry.progress(StatsDeliveryState.UNRESOLVED, p.rounds + 1, kind, "parked-round-${p.rounds + 1}")
                            stopped = true
                        }
                    }
                }
            }
        } finally {
            // Put survivors back - unless the scope ended while they were checked out, in which case they are lost: report them.
            var dead = false
            synchronized(lock) {
                if (terminated) dead = true else { parked.addAll(keep); parkedSize.set(parked.size) }
            }
            if (dead) keep.forEach { it.entry.finish(StatsDeliveryState.ABANDONED, it.rounds, null, "scope-cancelled-parked") }
        }
    }

    /** Reports a TERMINAL outcome for this accepted task, at most once. */
    private fun Entry.finish(state: StatsDeliveryState, attempt: Int, failure: StatsFailureKind?, detail: String?) {
        report(true) { emit(task.key, state, attempt, failure, detail) }
    }

    /** Reports a NON-terminal outcome (RETRYING / UNRESOLVED); suppressed if the task already reached a terminal state. */
    private fun Entry.progress(state: StatsDeliveryState, attempt: Int, failure: StatsFailureKind?, detail: String?) {
        beforeProgressReport(state)
        report(false) { emit(task.key, state, attempt, failure, detail) }
    }

    private fun emit(key: String, state: StatsDeliveryState, attempt: Int, failure: StatsFailureKind?, detail: String?) {
        counts[state.ordinal].incrementAndGet()
        try {
            onOutcome(StatsDeliveryOutcome(key, state, attempt, failure, detail))
        } catch (e: Exception) {
            // A diagnostics listener must never break delivery.
        }
    }
}
