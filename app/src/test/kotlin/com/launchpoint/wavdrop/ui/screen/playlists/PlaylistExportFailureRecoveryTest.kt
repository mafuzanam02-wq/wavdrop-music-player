package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.export.ExportPlan
import com.launchpoint.wavdrop.data.export.ExportPrepareResult
import com.launchpoint.wavdrop.data.export.ExportWriteResult
import com.launchpoint.wavdrop.data.export.M3u8Entry
import com.launchpoint.wavdrop.data.export.PlannedEntry
import com.launchpoint.wavdrop.data.export.PlaylistExporter
import com.launchpoint.wavdrop.data.export.UnresolvedEntry
import com.launchpoint.wavdrop.data.export.UnresolvedReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

class PlaylistExportFailureRecoveryTest {
    private fun plan(n: Int = 3, unresolved: Int = 0) = ExportPlan(
        1, "Mix",
        (0 until n).map { PlannedEntry(it, it + 1L, M3u8Entry("/m/$it.mp3", 1000, "A", "T$it")) },
        (0 until unresolved).map { UnresolvedEntry(n + it, 100L + it, UnresolvedReason.SongRowMissing) },
    )

    private class Scripted(
        var prepare: suspend () -> ExportPrepareResult,
        var write: suspend (ExportPlan) -> ExportWriteResult = { ExportWriteResult.Success },
    ) : PlaylistExporter {
        var prepares = 0
        var writes = 0
        override suspend fun prepare(playlistId: Long): ExportPrepareResult { prepares++; return prepare() }
        override suspend fun write(plan: ExportPlan, openOutput: (String) -> OutputStream?): ExportWriteResult {
            writes++; return write(plan)
        }
    }

    private val sink: (String) -> OutputStream? = { null }

    private fun finished(c: PlaylistExportCoordinator) = c.state.value as PlaylistExportState.Finished

    @Test fun `exception while preparing finishes with a failure message instead of sticking`() = runBlocking {
        val c = PlaylistExportCoordinator(1, Scripted({ throw SecurityException("media query denied") }))
        c.begin()
        val f = finished(c)
        assertFalse(f.success); assertTrue(f.message.isNotBlank()); assertTrue(f.message.contains("nothing", ignoreCase = true))
    }

    @Test fun `room or repository failure while preparing is recoverable`() = runBlocking {
        val c = PlaylistExportCoordinator(1, Scripted({ throw IllegalStateException("db closed") }))
        c.begin()
        assertFalse(finished(c).success)
    }

    @Test fun `exception while writing is a failure that warns about partial output`() = runBlocking {
        val ex = Scripted({ ExportPrepareResult.Ready(plan()) }, { throw IOException("provider died") })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.destinationChosen(sink)
        val f = finished(c)
        assertFalse(f.success)
        assertTrue(f.message.contains("empty") || f.message.contains("incomplete"))
        assertEquals(1, ex.writes)
    }

    @Test fun `failure on revalidation after an incomplete-export confirmation clears the pending plan`() = runBlocking {
        var n = 0
        val ex = Scripted({ if (n++ == 0) ExportPrepareResult.Ready(plan(3, 2)) else throw IOException("boom") })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.confirmIncomplete(); c.destinationChosen(sink)
        assertFalse(finished(c).success); assertEquals(0, ex.writes)
        // a stray repeated picker result must not write anything
        c.destinationChosen(sink)
        assertEquals(0, ex.writes)
    }

    @Test fun `retry after a failed preparation works`() = runBlocking {
        var fail = true
        val ex = Scripted({ if (fail) throw IOException("x") else ExportPrepareResult.Ready(plan()) })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); assertFalse(finished(c).success)
        fail = false
        c.consumeFinished(); c.begin()
        assertTrue(c.state.value is PlaylistExportState.PickDestination)
    }

    @Test fun `retry after a failed write performs exactly one new write`() = runBlocking {
        var failWrite = true
        val ex = Scripted(
            { ExportPrepareResult.Ready(plan()) },
            { if (failWrite) ExportWriteResult.Failed("x") else ExportWriteResult.Success },
        )
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.destinationChosen(sink)
        assertFalse(finished(c).success); assertEquals(1, ex.writes)
        c.destinationChosen(sink) // no pending plan: ignored
        assertEquals(1, ex.writes)
        failWrite = false
        c.consumeFinished(); c.begin(); c.destinationChosen(sink)
        assertTrue(finished(c).success); assertEquals(2, ex.writes)
    }

    @Test fun `cancellation during preparation is rethrown and leaves no stuck state`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = PlaylistExportCoordinator(1, Scripted({ gate.await(); ExportPrepareResult.Empty }))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { c.begin() }
        assertEquals(PlaylistExportState.Preparing, c.state.value)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(PlaylistExportState.Idle, c.state.value)
    }

    @Test fun `cancellation during writing is rethrown and never reports success`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val ex = Scripted({ ExportPrepareResult.Ready(plan()) }, { gate.await(); ExportWriteResult.Success })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { c.destinationChosen(sink) }
        assertEquals(PlaylistExportState.Writing, c.state.value)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        val f = finished(c)
        assertFalse(f.success)
        assertTrue(f.message.contains("empty") || f.message.contains("incomplete"))
    }

    @Test fun `picker launch is one-shot so recreation cannot open a second picker`() = runBlocking {
        val c = PlaylistExportCoordinator(1, Scripted({ ExportPrepareResult.Ready(plan()) }))
        c.begin()
        assertTrue(c.state.value is PlaylistExportState.PickDestination)
        assertTrue(c.pickerLaunched())          // first observer launches
        assertEquals(PlaylistExportState.AwaitingDestination, c.state.value)
        assertFalse(c.pickerLaunched())         // a recreated screen must not launch again
    }

    @Test fun `result is accepted while awaiting the picker`() = runBlocking {
        val ex = Scripted({ ExportPrepareResult.Ready(plan()) })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.pickerLaunched(); c.destinationChosen(sink)
        assertTrue(finished(c).success); assertEquals(1, ex.writes)
    }

    @Test fun `cancelled picker while awaiting writes nothing`() = runBlocking {
        val ex = Scripted({ ExportPrepareResult.Ready(plan()) })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.pickerLaunched(); c.destinationCancelled()
        assertFalse(finished(c).success); assertEquals(0, ex.writes)
    }
}
