package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.export.ExportPlan
import com.launchpoint.wavdrop.data.export.ExportPrepareResult
import com.launchpoint.wavdrop.data.export.ExportWriteResult
import com.launchpoint.wavdrop.data.export.PlaylistExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream

sealed interface PlaylistExportState {
    object Idle : PlaylistExportState
    object Preparing : PlaylistExportState
    data class NeedsConfirmation(val total: Int, val exportable: Int, val omitted: Int) : PlaylistExportState {
        val message: String
            get() = "$omitted of $total ${songs(total)} can't be exported because they are no longer in your " +
                "library or have no usable file path. Export the other $exportable anyway? " +
                "The file will be an incomplete copy of this playlist."
    }
    /** The UI should open the document picker exactly once, then call [PlaylistExportCoordinator.pickerLaunched]. */
    data class PickDestination(val fileName: String) : PlaylistExportState
    /** The picker was launched; waiting for its result. Never re-launched by a recreated screen. */
    object AwaitingDestination : PlaylistExportState
    object Writing : PlaylistExportState
    data class Finished(val success: Boolean, val message: String) : PlaylistExportState
}

private fun songs(n: Int) = if (n == 1) "song" else "songs"

private const val PARTIAL_OUTPUT_WARNING =
    "The destination file may be empty or incomplete; delete or replace it."

/**
 * Export state machine, independent of Android: prepare -> (confirm if incomplete) -> pick destination ->
 * re-verify the playlist is unchanged -> write. The saved playlist is never modified.
 *
 * Every non-cancellation failure ends in [PlaylistExportState.Finished] (success = false) with the pending plan
 * cleared, so the user can retry. Cancellation is rethrown after the state is made terminal.
 */
class PlaylistExportCoordinator(
    private val playlistId: Long,
    private val exporter: PlaylistExporter,
) {
    private val _state = MutableStateFlow<PlaylistExportState>(PlaylistExportState.Idle)
    val state: StateFlow<PlaylistExportState> = _state.asStateFlow()

    private var pending: ExportPlan? = null

    suspend fun begin() {
        val s = _state.value
        if (s is PlaylistExportState.Preparing || s is PlaylistExportState.Writing ||
            s is PlaylistExportState.PickDestination || s is PlaylistExportState.AwaitingDestination
        ) return
        pending = null
        _state.value = PlaylistExportState.Preparing
        val result = try {
            exporter.prepare(playlistId)
        } catch (e: CancellationException) {
            reset(); throw e
        } catch (e: Exception) {
            fail("Couldn't read this playlist's files, so nothing was exported. Please try again.")
            return
        }
        when (result) {
            is ExportPrepareResult.Ready -> {
                pending = result.plan
                _state.value = if (result.plan.unresolvedCount > 0) {
                    PlaylistExportState.NeedsConfirmation(
                        result.plan.totalCount, result.plan.resolvedCount, result.plan.unresolvedCount,
                    )
                } else {
                    PlaylistExportState.PickDestination(result.plan.suggestedFileName)
                }
            }
            ExportPrepareResult.Empty -> fail("This playlist is empty. Nothing was exported.")
            ExportPrepareResult.PlaylistMissing -> fail("This playlist no longer exists. Nothing was exported.")
            is ExportPrepareResult.NothingExportable -> fail(
                "None of the ${result.totalCount} ${songs(result.totalCount)} in this playlist can be exported " +
                    "(not in your library or no usable file path). Nothing was exported.",
            )
        }
    }

    fun confirmIncomplete() {
        val plan = pending ?: return
        if (_state.value is PlaylistExportState.NeedsConfirmation) {
            _state.value = PlaylistExportState.PickDestination(plan.suggestedFileName)
        }
    }

    fun dismissConfirmation() {
        if (_state.value is PlaylistExportState.NeedsConfirmation) reset()
    }

    /** Returns true only for the single caller that must open the picker. */
    fun pickerLaunched(): Boolean {
        if (_state.value !is PlaylistExportState.PickDestination) return false
        _state.value = PlaylistExportState.AwaitingDestination
        return true
    }

    fun destinationCancelled() {
        if (!awaitingPicker()) return
        pending = null
        _state.value = PlaylistExportState.Finished(false, "Export cancelled. Nothing was written.")
    }

    suspend fun destinationChosen(openOutput: (String) -> OutputStream?) {
        val confirmed = pending
        if (!awaitingPicker() || confirmed == null) return
        _state.value = PlaylistExportState.Writing

        val current = try {
            (exporter.prepare(playlistId) as? ExportPrepareResult.Ready)?.plan
        } catch (e: CancellationException) {
            fail("Export was interrupted before writing. Nothing was written by WavDrop, but the destination " +
                "file may exist empty; delete it if so.")
            throw e
        } catch (e: Exception) {
            fail("Couldn't re-check the playlist, so it was not exported. $PARTIAL_OUTPUT_WARNING")
            return
        }
        if (current != confirmed) {
            fail(
                "The playlist or its files changed during export. Nothing was written by WavDrop; " +
                    "export again to get an up-to-date file. $PARTIAL_OUTPUT_WARNING",
            )
            return
        }

        val result = try {
            exporter.write(current, openOutput)
        } catch (e: CancellationException) {
            fail("Export was interrupted. The playlist was not fully exported. $PARTIAL_OUTPUT_WARNING")
            throw e
        } catch (e: Exception) {
            fail("Export failed while writing. The playlist was not exported. $PARTIAL_OUTPUT_WARNING")
            return
        }
        pending = null
        _state.value = when (result) {
            ExportWriteResult.Success -> PlaylistExportState.Finished(true, successMessage(current))
            ExportWriteResult.NoOutputStream -> PlaylistExportState.Finished(
                false,
                "Export failed: the destination could not be opened. The playlist was not exported. " +
                    PARTIAL_OUTPUT_WARNING,
            )
            is ExportWriteResult.Failed -> PlaylistExportState.Finished(
                false,
                "Export failed while writing. The playlist was not exported. $PARTIAL_OUTPUT_WARNING",
            )
        }
    }

    fun consumeFinished() {
        if (_state.value is PlaylistExportState.Finished) reset()
    }

    private fun awaitingPicker(): Boolean =
        _state.value.let { it is PlaylistExportState.PickDestination || it is PlaylistExportState.AwaitingDestination }

    private fun successMessage(plan: ExportPlan): String =
        if (plan.unresolvedCount == 0) {
            "Exported ${plan.resolvedCount} ${songs(plan.resolvedCount)} to M3U8. " +
                "File paths are specific to this device."
        } else {
            "Exported ${plan.resolvedCount} of ${plan.totalCount} ${songs(plan.totalCount)}; " +
                "${plan.unresolvedCount} omitted. The file is an incomplete copy, and its paths are specific " +
                "to this device."
        }

    private fun fail(message: String) {
        pending = null
        _state.value = PlaylistExportState.Finished(false, message)
    }

    private fun reset() {
        pending = null
        _state.value = PlaylistExportState.Idle
    }
}
