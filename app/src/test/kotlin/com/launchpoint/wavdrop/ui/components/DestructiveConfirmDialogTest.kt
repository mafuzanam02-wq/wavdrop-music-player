package com.launchpoint.wavdrop.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-UX-02A. The project has no JVM Compose-rendering infrastructure (ui-test is an androidTest-only dependency and needs a
 * debug manifest), so these tests pin (1) the exact consequence wording and (2) the source-level wiring: standard Material 3
 * [androidx.compose.material3.AlertDialog] ordering (Cancel = dismissButton, destructive = confirmButton), theme error colour,
 * neutral Cancel colour, and that every covered flow routes through the shared dialog. Rendered left/right placement, colour
 * contrast and TalkBack behaviour are NOT proven here and are listed for the later A15 verification.
 */
class DestructiveConfirmDialogTest {

    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText()
    private val dialog get() = src("ui/components/DestructiveConfirmDialog.kt")
    private val trackDetails get() = src("ui/screen/trackdetails/TrackDetailsScreen.kt")
    private val playlists get() = src("ui/screen/playlists/PlaylistsScreen.kt")
    private val playlistDetails get() = src("ui/screen/playlists/PlaylistDetailsScreen.kt")

    // A. Delete from device copy
    @Test fun `A delete-from-device copy states every consequence and nothing false`() {
        val body = DestructiveDialogCopy.deleteTrackBody("Song X")
        assertTrue(body.contains("\"Song X\""))
        assertTrue(body.contains("permanently deleted from your device storage"))
        assertTrue(body.contains("removed from your library and from any playlists"))
        assertTrue(body.contains("listening history and statistics for it are kept"))
        assertTrue(body.contains("cannot undo"))
        assertTrue("the Android system confirmation still follows", body.contains("Android will ask you to confirm"))
        assertEquals("Delete from device", DestructiveDialogCopy.DELETE_TRACK_CONFIRM)
    }

    // B / C. Playlist deletion copy and identical labels at both entry points
    @Test fun `B playlist copy says songs and audio files remain and it cannot be undone`() {
        val body = DestructiveDialogCopy.deletePlaylistBody("Mix")
        assertTrue(body.contains("\"Mix\" will be deleted"))
        assertTrue(body.contains("songs and audio files stay on your device"))
        assertTrue(body.contains("can't be undone"))
        assertFalse("must not imply audio is deleted", body.contains("permanently deleted from your device"))
    }

    @Test fun `C both playlist entry points use the same shared dialog and labels`() {
        for (s in listOf(playlists, playlistDetails)) {
            assertTrue(s.contains("DestructiveConfirmDialog("))
            assertTrue(s.contains("DestructiveDialogCopy.DELETE_PLAYLIST_TITLE"))
            assertTrue(s.contains("DestructiveDialogCopy.DELETE_PLAYLIST_CONFIRM"))
            assertTrue(s.contains("DestructiveDialogCopy.deletePlaylistBody("))
        }
        assertEquals("Delete playlist", DestructiveDialogCopy.DELETE_PLAYLIST_CONFIRM)
        assertFalse("the old duplicated raw dialogs are gone", playlists.contains("This cannot be undone."))
        assertFalse(playlistDetails.contains("This cannot be undone."))
    }

    // D / E / F. Lyrics clear needs positive confirmation; cancel and dismissal do nothing else
    @Test fun `D no Track Details path clears lyrics without the confirmation`() {
        assertEquals("clearCustomLyrics is called exactly once, inside the confirm handler", 1,
            Regex("lyricsViewModel\\.clearCustomLyrics").findAll(trackDetails).count())
        val confirmIdx = trackDetails.indexOf("DestructiveDialogCopy.REMOVE_LYRICS_CONFIRM")
        val clearIdx = trackDetails.indexOf("lyricsViewModel.clearCustomLyrics")
        assertTrue(confirmIdx in 0 until clearIdx)
        assertTrue("header Clear only opens the dialog", trackDetails.contains("onClearLyrics     = { showClearLyricsConfirmation = true }"))
        assertTrue("editor Clear only opens the dialog", trackDetails.contains("onClear = { showClearLyricsConfirmation = true }"))
    }

    @Test fun `E and F cancel and dismissal only hide the dialog`() {
        assertTrue(trackDetails.contains("onDismiss    = { showClearLyricsConfirmation = false }"))
        assertTrue(dialog.contains("onDismissRequest = onDismiss"))
        assertTrue(dialog.contains("onClick = onDismiss"))
        assertFalse("dismissal never routes to the confirm action", Regex("onDismissRequest\\s*=\\s*onConfirm").containsMatchIn(dialog))
    }

    @Test fun `lyrics copy says what is removed and what may remain`() {
        assertTrue(DestructiveDialogCopy.REMOVE_LYRICS_BODY.contains("saved custom lyrics"))
        assertTrue(DestructiveDialogCopy.REMOVE_LYRICS_BODY.contains("embedded lyrics"))
        assertTrue(DestructiveDialogCopy.REMOVE_LYRICS_BODY.contains(".lrc"))
        assertEquals("Remove lyrics", DestructiveDialogCopy.REMOVE_LYRICS_CONFIRM)
    }

    // G / H. Material 3 ordering and styling
    @Test fun `G standard Material 3 button slots - Cancel is dismissButton and destructive is confirmButton`() {
        val confirm = dialog.indexOf("confirmButton = {")
        val dismiss = dialog.indexOf("dismissButton = {")
        assertTrue(confirm >= 0 && dismiss > confirm)
        assertTrue(dialog.substring(confirm, dismiss).contains("confirmLabel"))
        assertTrue(dialog.substring(dismiss).contains("cancelLabel"))
        assertFalse("no custom action row", dialog.contains("Row("))
    }

    @Test fun `H destructive uses the theme error colour and Cancel stays neutral`() {
        val confirm = dialog.substringAfter("confirmButton = {").substringBefore("dismissButton = {")
        val dismiss = dialog.substringAfter("dismissButton = {")
        assertTrue(confirm.contains("colorScheme.error"))
        assertTrue(dismiss.contains("colorScheme.onSurfaceVariant"))
        assertFalse(dismiss.contains("colorScheme.error"))
        assertFalse("no hard-coded colour", Regex("Color\\(0x|Color\\.Red").containsMatchIn(dialog))
    }

    @Test fun `confirm fires at most once per dialog instance`() {
        assertTrue(dialog.contains("if (!confirmed)"))
        assertTrue(dialog.contains("confirmed = true"))
    }

    // I / J. Underlying behavior and out-of-scope code untouched
    @Test fun `I existing deletion path is unchanged`() {
        assertTrue(trackDetails.contains("viewModel.deleteFromDevice("))
        assertTrue(trackDetails.contains("deleteLauncher.launch("))
        assertTrue(trackDetails.contains("viewModel.onDeleteApproved(song)"))
        assertTrue(playlists.contains("viewModel.deletePlaylist(target.playlist.id)"))
        assertTrue(playlistDetails.contains("viewModel.deletePlaylist { onNavigateBack() }"))
    }

    @Test fun `J backup recovery and queue dialogs are not routed through the new dialog`() {
        assertFalse(src("ui/screen/backupimport/BackupImportPreviewScreen.kt").contains("DestructiveConfirmDialog"))
        assertFalse(src("ui/screen/nowplaying/QueueSheet.kt").contains("DestructiveConfirmDialog"))
        assertFalse(src("ui/screen/nowplaying/NowPlayingScreen.kt").contains("DestructiveConfirmDialog"))
    }
}
