package com.launchpoint.wavdrop.ui.screen.playlists

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Delivers a finished export result to the user: consume it exactly once, then show its message.
 *
 * Consuming moves the export state to Idle, which restarts any effect keyed on that state. The message is
 * therefore shown from [scope] (the composition-owned scope), not from the effect itself, so the state change
 * cannot cancel a snackbar that is being presented.
 */
class ExportResultNotifier(
    private val scope: CoroutineScope,
    private val consume: () -> Unit,
    private val show: suspend (String) -> Unit,
) {
    fun onState(state: PlaylistExportState) {
        if (state !is PlaylistExportState.Finished) return
        consume()
        scope.launch { show(state.message) }
    }
}
