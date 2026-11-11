package com.launchpoint.wavdrop.ui.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Serialises and coalesces widget update requests so overlapping requests (playback state changes, resizes, appearance saves)
 * can never interleave or finish out of order.
 *
 *  - At most ONE [run] executes at a time.
 *  - A request that arrives while a pass is running is merged into a single follow-up pass; "all widgets" absorbs any id set.
 *  - [run] reads current state at the moment it executes, and every request made before the last pass started is covered by
 *    that pass or one after it. So the last pass always renders the latest state: an older request can never overwrite a newer one.
 *  - [requestAndAwait] suspends until a pass that STARTED AFTER the request was made has finished: true if [run] returned normally
 *    for that widget, false if it threw (a failed pass is reported to [onError] and never stops later requests).
 *    If the request is merged into an "all widgets" pass, the awaited ids are first rendered by an explicit targeted pass, so
 *    an awaited widget's success/failure is never hidden inside a pass that did not check it.
 *
 * `target == null` means every widget; otherwise only those ids. [run] signals failure for a targeted pass by throwing.
 */
internal class WidgetUpdateCoordinator(
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
    private val run: suspend (target: Set<Int>?) -> Unit,
) {
    private class Waiter(val ids: Set<Int>, val done: CompletableDeferred<Boolean> = CompletableDeferred())

    private val lock = Any()
    private var pendingAll = false
    private val pendingIds = mutableSetOf<Int>()
    private var pendingWaiters = mutableListOf<Waiter>()
    private var running = false

    /** @param ids null = all widgets. Fire-and-forget. */
    fun request(ids: Collection<Int>?) {
        if (enqueue(ids, waiter = null)) scope.launch { drain() }
    }

    /**
     * Requests an update of [ids] and waits for the pass that covers it. The caller must have finished any state change the
     * pass should render BEFORE calling (the pass reads state when it runs, which is after this call).
     */
    suspend fun requestAndAwait(ids: Collection<Int>): Boolean {
        if (ids.isEmpty()) return false
        val waiter = Waiter(ids.toSet())
        if (enqueue(ids, waiter)) scope.launch { drain() }
        return waiter.done.await()
    }

    /** @return true if the caller must start the drain loop. */
    private fun enqueue(ids: Collection<Int>?, waiter: Waiter?): Boolean = synchronized(lock) {
        if (ids == null) pendingAll = true else pendingIds += ids
        if (waiter != null) pendingWaiters += waiter
        if (running) {
            false
        } else {
            running = true
            true
        }
    }

    private suspend fun drain() {
        while (true) {
            var waiters: List<Waiter> = emptyList()
            val target: Set<Int>? = synchronized(lock) {
                if (!pendingAll && pendingIds.isEmpty()) {
                    running = false
                    return
                }
                val t = if (pendingAll) null else pendingIds.toSet()
                waiters = pendingWaiters
                pendingWaiters = mutableListOf()
                pendingAll = false
                pendingIds.clear()
                t
            }
            try {
                if (target == null && waiters.isNotEmpty()) {
                    // An awaited widget was absorbed into an all-widgets pass: render exactly those ids first so their result is known.
                    val ok = runPass(waiters.flatMap { it.ids }.toSet())
                    waiters.forEach { it.done.complete(ok) }
                    runPass(null)
                } else {
                    val ok = runPass(target)
                    waiters.forEach { it.done.complete(ok) }
                }
            } catch (e: CancellationException) {
                waiters.forEach { it.done.complete(false) }
                synchronized(lock) { running = false }
                throw e
            }
        }
    }

    /** Runs one pass; a failure is reported and returned as false (cancellation propagates). */
    private suspend fun runPass(target: Set<Int>?): Boolean = try {
        run(target)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        onError(t)
        false
    }
}
