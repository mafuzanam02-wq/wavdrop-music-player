package com.launchpoint.wavdrop.ui.screen.playlists

import org.junit.Assert.assertEquals
import org.junit.Test

class AddSongsToPlaylistUiStateTest {

    @Test
    fun `selectedCount reflects selected song ids`() {
        val state = AddSongsToPlaylistUiState(selectedSongIds = listOf(3L, 1L, 9L))

        assertEquals(3, state.selectedCount)
    }
}
