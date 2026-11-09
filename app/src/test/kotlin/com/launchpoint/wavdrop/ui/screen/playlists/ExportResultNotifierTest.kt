package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.export.ExportPlan
import com.launchpoint.wavdrop.data.export.ExportPrepareResult
import com.launchpoint.wavdrop.data.export.ExportWriteResult
import com.launchpoint.wavdrop.data.export.M3u8Entry
import com.launchpoint.wavdrop.data.export.PlannedEntry
import com.launchpoint.wavdrop.data.export.PlaylistExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

/**
 * Models the screen: a LaunchedEffect keyed on the export state is `collectLatest` (a new state cancels the
 * previous effect run), and `show` suspends while a snackbar is on screen. Compose itself is not exercised;
 * the on-device behavior remains a manual gate.
 */
class ExportResultNotifierTest {
    private val plan = ExportPlan(
        1, "Mix",
        (0 until 2).map { PlannedEntry(it, it + 1L, M3u8Entry("/m/$it.mp3", 1000, "A", "T$it")) },
        emptyList(),
    )

    private class FakeExporter(
        var prepare: () -> ExportPrepareResult,
        var write: () -> ExportWriteResult = { ExportWriteResult.Success },
    ) : PlaylistExporter {
        override suspend fun prepare(playlistId: Long) = prepare()
        override suspend fun write(plan: ExportPlan, openOutput: (String) -> OutputStream?) = write()
    }

    private class Screen(val c: PlaylistExportCoordinator, scope: CoroutineScope) {
        val started = mutableListOf<String>()
        val completed = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        var pickerLaunches = 0
        private val notifier = ExportResultNotifier(scope, consume = { c.consumeFinished() }) { m ->
            started += m
            hold?.await()
            completed += m
        }

        /** One "composition": observe state like the screen's LaunchedEffect. */
        fun observe(scope: CoroutineScope): Job = scope.launch {
            c.state.collectLatest { s ->
                if (s is PlaylistExportState.PickDestination && c.pickerLaunched()) pickerLaunches++
                notifier.onState(s)
            }
        }
    }

    private suspend fun settle() = repeat(8) { yield() }

    private fun exporter(
        prepare: () -> ExportPrepareResult = { ExportPrepareResult.Ready(plan) },
        write: () -> ExportWriteResult = { ExportWriteResult.Success },
    ) = FakeExporter(prepare, write)

    @Test fun `success notification is delivered`() = runBlocking {
        val s = Screen(PlaylistExportCoordinator(1, exporter()), this)
        val obs = s.observe(this)
        s.c.begin(); settle()
        s.c.destinationChosen { null }; settle()
        assertEquals(1, s.completed.size)
        assertTrue(s.completed.single().startsWith("Exported 2 songs"))
        obs.cancel()
    }

    @Test fun `cancellation notification is delivered`() = runBlocking {
        val s = Screen(PlaylistExportCoordinator(1, exporter()), this)
        val obs = s.observe(this)
        s.c.begin(); settle()
        s.c.destinationCancelled(); settle()
        assertEquals(listOf("Export cancelled. Nothing was written."), s.completed)
        obs.cancel()
    }

    @Test fun `failure notification is delivered for preparation and writing`() = runBlocking {
        val s = Screen(PlaylistExportCoordinator(1, exporter(prepare = { throw IOException("x") })), this)
        val obs = s.observe(this)
        s.c.begin(); settle()
        assertEquals(1, s.completed.size); assertTrue(s.completed.single().contains("nothing was exported"))
        obs.cancel()

        val w = Screen(PlaylistExportCoordinator(1, exporter(write = { ExportWriteResult.Failed("x") })), this)
        val obs2 = w.observe(this)
        w.c.begin(); settle(); w.c.destinationChosen { null }; settle()
        assertEquals(1, w.completed.size); assertTrue(w.completed.single().startsWith("Export failed"))
        obs2.cancel()
    }

    @Test fun `consuming the result does not cancel delivery of a visible message`() = runBlocking {
        val s = Screen(PlaylistExportCoordinator(1, exporter(prepare = { ExportPrepareResult.Empty })), this)
        s.hold = CompletableDeferred() // the snackbar stays on screen
        val obs = s.observe(this)
        s.c.begin(); settle()
        assertEquals(PlaylistExportState.Idle, s.c.state.value) // consumed -> state changed to Idle
        assertEquals(1, s.started.size)
        s.hold!!.complete(Unit); settle()
        assertEquals(s.started, s.completed) // presentation finished despite the state change
        obs.cancel()
    }

    @Test fun `result is consumed exactly once and not replayed after screen recreation`() = runBlocking {
        val s = Screen(PlaylistExportCoordinator(1, exporter(prepare = { ExportPrepareResult.Empty })), this)
        val first = s.observe(this)
        s.c.begin(); settle()
        first.cancel()
        s.observe(this).also { settle(); it.cancel() } // recreated screen observes the same state holder
        assertEquals(1, s.started.size)
    }

    @Test fun `a second export gets its own notification`() = runBlocking {
        val s = Screen(PlaylistExportCoordinator(1, exporter()), this)
        val obs = s.observe(this)
        s.c.begin(); settle(); s.c.destinationCancelled(); settle()
        s.c.begin(); settle(); s.c.destinationChosen { null }; settle()
        assertEquals(2, s.completed.size)
        assertTrue(s.completed[0].startsWith("Export cancelled"))
        assertTrue(s.completed[1].startsWith("Exported 2 songs"))
        obs.cancel()
    }

    @Test fun `picker is still launched exactly once`() = runBlocking {
        val s = Screen(PlaylistExportCoordinator(1, exporter()), this)
        val obs = s.observe(this)
        s.c.begin(); settle()
        val recreated = s.observe(this); settle() // a second observer must not launch another picker
        assertEquals(1, s.pickerLaunches)
        recreated.cancel(); obs.cancel()
    }
}
