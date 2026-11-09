package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.export.ExportPlan
import com.launchpoint.wavdrop.data.export.ExportPrepareResult
import com.launchpoint.wavdrop.data.export.ExportWriteResult
import com.launchpoint.wavdrop.data.export.M3u8Entry
import com.launchpoint.wavdrop.data.export.PlannedEntry
import com.launchpoint.wavdrop.data.export.PlaylistExporter
import com.launchpoint.wavdrop.data.export.UnresolvedEntry
import com.launchpoint.wavdrop.data.export.UnresolvedReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

class PlaylistExportCoordinatorTest {
    private fun planned(n: Int, unresolved: Int = 0) = ExportPlan(
        playlistId = 1, playlistName = "Mix",
        entries = (0 until n).map { PlannedEntry(it, it + 1L, M3u8Entry("/m/$it.mp3", 1000, "A", "T$it")) },
        unresolved = (0 until unresolved).map { UnresolvedEntry(n + it, 100L + it, UnresolvedReason.SongRowMissing) },
    )

    private class FakeExporter(var prepared: () -> ExportPrepareResult, var write: (ExportPlan) -> ExportWriteResult = { ExportWriteResult.Success }) :
        PlaylistExporter {
        var writes = 0
        override suspend fun prepare(playlistId: Long) = prepared()
        override suspend fun write(plan: ExportPlan, openOutput: (String) -> OutputStream?): ExportWriteResult {
            writes++; return write(plan)
        }
    }

    private val sink: (String) -> OutputStream? = { null }

    @Test fun `complete playlist goes straight to the picker without a confirmation`() = runBlocking {
        val c = PlaylistExportCoordinator(1, FakeExporter({ ExportPrepareResult.Ready(planned(3)) }))
        c.begin()
        val s = c.state.value as PlaylistExportState.PickDestination
        assertEquals("Mix.m3u8", s.fileName)
    }

    @Test fun `incomplete playlist asks for confirmation with exact counts and nothing is written`() = runBlocking {
        val ex = FakeExporter({ ExportPrepareResult.Ready(planned(3, unresolved = 2)) })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin()
        val s = c.state.value as PlaylistExportState.NeedsConfirmation
        assertEquals(5, s.total); assertEquals(3, s.exportable); assertEquals(2, s.omitted)
        assertTrue(s.message.contains("2") && s.message.contains("3"))
        assertEquals(0, ex.writes)
    }

    @Test fun `dismissing the confirmation cancels without writing`() = runBlocking {
        val ex = FakeExporter({ ExportPrepareResult.Ready(planned(3, 2)) })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.dismissConfirmation()
        assertEquals(PlaylistExportState.Idle, c.state.value); assertEquals(0, ex.writes)
    }

    @Test fun `accepting the confirmation proceeds to the picker`() = runBlocking {
        val c = PlaylistExportCoordinator(1, FakeExporter({ ExportPrepareResult.Ready(planned(3, 2)) }))
        c.begin(); c.confirmIncomplete()
        assertTrue(c.state.value is PlaylistExportState.PickDestination)
    }

    @Test fun `picker cancellation writes nothing and reports cancellation`() = runBlocking {
        val ex = FakeExporter({ ExportPrepareResult.Ready(planned(2)) })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.destinationCancelled()
        val s = c.state.value as PlaylistExportState.Finished
        assertTrue(s.message.contains("cancel", ignoreCase = true)); assertFalse(s.success); assertEquals(0, ex.writes)
    }

    @Test fun `complete success message has counts and says device-local`() = runBlocking {
        val c = PlaylistExportCoordinator(1, FakeExporter({ ExportPrepareResult.Ready(planned(3)) }))
        c.begin(); c.destinationChosen(sink)
        val s = c.state.value as PlaylistExportState.Finished
        assertTrue(s.success)
        assertTrue(s.message.contains("3")); assertTrue(s.message.contains("device", ignoreCase = true))
        assertFalse(s.message.contains("omitted", ignoreCase = true))
    }

    @Test fun `partial success reports exported and omitted counts and is not called complete`() = runBlocking {
        val c = PlaylistExportCoordinator(1, FakeExporter({ ExportPrepareResult.Ready(planned(3, 2)) }))
        c.begin(); c.confirmIncomplete(); c.destinationChosen(sink)
        val s = c.state.value as PlaylistExportState.Finished
        assertTrue(s.success)
        assertTrue(s.message.contains("3") && s.message.contains("2") && s.message.contains("omitted", ignoreCase = true))
        assertFalse(s.message.contains("complete", ignoreCase = true) && !s.message.contains("incomplete", ignoreCase = true))
    }

    @Test fun `write failure never reports success and warns about a possibly empty file`() = runBlocking {
        val ex = FakeExporter({ ExportPrepareResult.Ready(planned(3)) }, { ExportWriteResult.Failed("disk full") })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.destinationChosen(sink)
        val s = c.state.value as PlaylistExportState.Finished
        assertFalse(s.success); assertTrue(s.message.contains("not", ignoreCase = true))
        assertTrue(s.message.contains("empty") || s.message.contains("incomplete"))
    }

    @Test fun `null output stream is reported as failure`() = runBlocking {
        val ex = FakeExporter({ ExportPrepareResult.Ready(planned(3)) }, { ExportWriteResult.NoOutputStream })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.destinationChosen(sink)
        assertFalse((c.state.value as PlaylistExportState.Finished).success)
    }

    @Test fun `playlist changed after confirmation aborts without writing`() = runBlocking {
        var current = planned(3, 1)
        val ex = FakeExporter({ ExportPrepareResult.Ready(current) })
        val c = PlaylistExportCoordinator(1, ex)
        c.begin(); c.confirmIncomplete()
        current = planned(4, 1) // playlist edited while the picker was open
        c.destinationChosen(sink)
        val s = c.state.value as PlaylistExportState.Finished
        assertFalse(s.success); assertEquals(0, ex.writes)
        assertTrue(s.message.contains("changed", ignoreCase = true))
    }

    @Test fun `empty wholly unresolved and missing playlists do not produce a success`() = runBlocking {
        for (r in listOf(ExportPrepareResult.Empty, ExportPrepareResult.NothingExportable(4), ExportPrepareResult.PlaylistMissing)) {
            val ex = FakeExporter({ r })
            val c = PlaylistExportCoordinator(1, ex)
            c.begin()
            val s = c.state.value as PlaylistExportState.Finished
            assertFalse(s.success); assertEquals(0, ex.writes)
        }
    }

    @Test fun `nothing exportable message names the unresolved count`() = runBlocking {
        val c = PlaylistExportCoordinator(1, FakeExporter({ ExportPrepareResult.NothingExportable(4) }))
        c.begin()
        assertTrue((c.state.value as PlaylistExportState.Finished).message.contains("4"))
    }

    @Test fun `consuming the finished state returns to idle`() = runBlocking {
        val c = PlaylistExportCoordinator(1, FakeExporter({ ExportPrepareResult.Empty }))
        c.begin(); c.consumeFinished()
        assertEquals(PlaylistExportState.Idle, c.state.value)
    }
}
