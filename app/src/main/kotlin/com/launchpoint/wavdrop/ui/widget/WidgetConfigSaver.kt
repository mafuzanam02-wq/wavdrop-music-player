package com.launchpoint.wavdrop.ui.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "Save" step of the widget configuration screen, free of UI so its ordering and failure behaviour can be unit-tested.
 *
 * Android does NOT send an initial update when a configuration activity is used, so the activity is responsible for the first
 * render and must only report RESULT_OK once it exists. [commit] therefore:
 *  1. persists the new appearance for this widget id (an invalid id is refused and nothing is stored),
 *  2. waits — with a timeout, so there is never an endless spinner — for a render pass that started after step 1
 *     ([renderFirstUpdate]; in production the existing serialising update coordinator, not a second pipeline),
 *  3. returns [Result.Saved] only if that render succeeded.
 * If anything fails, times out or is cancelled, the PREVIOUS appearance is written back and then [reconcile] is requested.
 *
 * Why [reconcile]: giving up on the wait (timeout/cancel) does not stop a render pass that is already executing — it may have
 * read the NEW appearance and may still push it to the launcher after the rollback. Persisted state would then say "previous"
 * while the widget shows "new". [reconcile] asks the same serialising coordinator for one more pass of this widget, issued strictly
 * AFTER the rollback was written, so it queues behind any in-flight pass, reads the rolled-back value, and is the last render.
 * A pass that was still only queued reads the rolled-back value itself, and the extra pass coalesces with it. It is not awaited
 * (the screen never waits on it) and is requested only when something was persisted; successful saves never trigger it.
 *
 * Only one commit can ever run: concurrent/duplicate calls get [Result.Busy], and after a successful Save further calls stay [Result.Busy].
 */
internal class WidgetConfigSaver(
    private val store: WidgetAppearanceStore,
    private val renderFirstUpdate: suspend (appWidgetId: Int) -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val reconcile: (appWidgetId: Int) -> Unit = {},
) {
    enum class Result { Saved, Failed, Busy }

    private val claimed = AtomicBoolean(false)

    suspend fun commit(appWidgetId: Int, appearance: WidgetAppearance, previous: WidgetAppearance): Result {
        if (!claimed.compareAndSet(false, true)) return Result.Busy
        var saved = false
        var persisted = false
        try {
            if (!store.save(appWidgetId, appearance)) return Result.Failed
            persisted = true
            saved = try {
                withTimeoutOrNull(timeoutMs) { renderFirstUpdate(appWidgetId) } == true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                false // a throwing renderer is a failed render, never a crash of the configuration screen
            }
            return if (saved) Result.Saved else Result.Failed
        } finally {
            if (!saved) {
                // Roll back (also when cancelled, e.g. the screen was closed mid-save) and allow another attempt.
                withContext(NonCancellable) {
                    store.save(appWidgetId, previous)
                    // Order matters: only AFTER the rollback is persisted, so the reconciling pass reads it.
                    if (persisted) runCatching { reconcile(appWidgetId) }
                }
                claimed.set(false)
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}
