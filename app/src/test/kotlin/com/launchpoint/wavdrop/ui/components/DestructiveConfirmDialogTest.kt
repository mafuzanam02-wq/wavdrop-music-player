package com.launchpoint.wavdrop.ui.components

import com.launchpoint.wavdrop.ui.screen.nowplaying.QueueBulkClear
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ───────────── P5-UX-03B: Queue and Now Playing adopt the shared dialog ─────────────
    // NOTE: the copy assertions exercise real code (QueueBulkClear / DestructiveDialogCopy). The wiring assertions are
    // source-structure checks: they do NOT prove rendered placement, colours, Back-key or runtime behaviour (A15 QA slice).

    private val queue get() = src("ui/screen/nowplaying/QueueSheet.kt")
    private val nowPlaying get() = src("ui/screen/nowplaying/NowPlayingScreen.kt")
    private val queueDialogBlock get() =
        queue.substringAfter("val bulkClear = pendingBulkClear").substringBefore("dialog (Phase C)")
    private val lyricsFn get() = nowPlaying.substringAfter("private fun LyricsEditorDialog(")
    private val lyricsConfirmBlock get() = lyricsFn.substringAfter("if (showClearConfirm) {").substringBefore("} else {")

    // A / B. Both bulk clears share one dialog with explicit labels
    @Test fun `A and B both queue bulk clears share one DestructiveConfirmDialog with explicit labels`() {
        assertEquals(1, Regex("DestructiveConfirmDialog\\(").findAll(queueDialogBlock).count())
        assertTrue(queueDialogBlock.contains("title = bulkClear.dialogTitle"))
        assertTrue(queueDialogBlock.contains("message = bulkClear.dialogBody(count)"))
        assertTrue(queueDialogBlock.contains("confirmLabel = bulkClear.confirmLabel"))
        assertFalse("raw AlertDialog is gone from the bulk-clear block", queueDialogBlock.contains("AlertDialog("))
        assertEquals("Clear earlier", QueueBulkClear.Earlier.confirmLabel)
        assertEquals("Clear Up Next", QueueBulkClear.UpNext.confirmLabel)
        assertEquals("Clear earlier queue?", QueueBulkClear.Earlier.dialogTitle)
        assertEquals("Clear Up Next?", QueueBulkClear.UpNext.dialogTitle)
    }

    // C. Counts and queue-specific messages
    @Test fun `C queue messages keep the count and do not imply files or history are deleted`() {
        val earlier = QueueBulkClear.Earlier.dialogBody(3)
        val upNext = QueueBulkClear.UpNext.dialogBody(1)
        assertTrue(earlier.startsWith("3 previously played tracks will be removed from the playback queue."))
        assertTrue(upNext.startsWith("1 upcoming track will be removed from the playback queue."))
        assertTrue(QueueBulkClear.UpNext.dialogBody(2).startsWith("2 upcoming tracks "))
        for (m in listOf(earlier, upNext)) {
            assertTrue(m.contains("The song playing now stays."))
            assertTrue(m.contains("Your music files, playlists and listening history are not affected."))
            assertFalse(m.contains("deleted"))
        }
        assertNotEquals(earlier, QueueBulkClear.UpNext.dialogBody(3))
    }

    // D. Cancel / Back never run the clear
    @Test fun `D dismissal only clears the pending request and never runs the bulk clear`() {
        assertTrue(queueDialogBlock.contains("onDismiss = { pendingBulkClear = null }"))
        assertEquals(1, Regex("runBulkClear\\(").findAll(queueDialogBlock).count())
        val onConfirm = queueDialogBlock.substringAfter("onConfirm = {").substringBefore("onDismiss =")
        assertTrue(onConfirm.contains("runBulkClear(bulkClear)"))
        assertFalse(queueDialogBlock.substringAfter("onDismiss =").contains("runBulkClear"))
    }

    // E. Existing queue feedback intact
    @Test fun `E existing clear callbacks and snackbar feedback are unchanged`() {
        assertTrue(queue.contains("QueueBulkClear.Earlier -> onClearEarlier()"))
        assertTrue(queue.contains("QueueBulkClear.UpNext -> onClearUpNext()"))
        assertTrue(queue.contains("snackbarHostState.showSnackbar(kind.clearedMessage)"))
        assertEquals("Earlier queue cleared", QueueBulkClear.Earlier.clearedMessage)
        assertEquals("Up Next cleared", QueueBulkClear.UpNext.clearedMessage)
        assertTrue(queueDialogBlock.contains("LaunchedEffect(Unit) { pendingBulkClear = null }"))
        assertTrue(queue.contains("pendingBulkClear = QueueBulkClear.Earlier"))
        assertTrue(queue.contains("pendingBulkClear = QueueBulkClear.UpNext"))
    }

    // F / G. Now Playing lyrics removal uses shared copy and the Keep editing label
    @Test fun `F and G Now Playing uses the shared lyrics copy and keeps the Keep editing label`() {
        val block = lyricsConfirmBlock
        assertTrue(block.contains("DestructiveConfirmDialog("))
        assertTrue(block.contains("title = DestructiveDialogCopy.REMOVE_LYRICS_TITLE"))
        assertTrue(block.contains("message = DestructiveDialogCopy.REMOVE_LYRICS_BODY"))
        assertTrue(block.contains("confirmLabel = DestructiveDialogCopy.REMOVE_LYRICS_CONFIRM"))
        assertTrue(block.contains("cancelLabel = \"Keep editing\""))
        assertFalse(nowPlaying.contains("Your custom lyrics for this track will be removed."))
        assertFalse(block.contains("AlertDialog("))
    }

    // H. Cancel keeps the editor and the draft
    @Test fun `H cancel returns to the editor without clearing and the draft state outlives the confirmation`() {
        assertTrue(lyricsConfirmBlock.contains("onDismiss = { showClearConfirm = false }"))
        assertFalse(lyricsConfirmBlock.substringAfter("onDismiss =").contains("onClear"))
        // The draft and the flag live in the composable's own remember, above the if/else, so swapping dialogs cannot reset them.
        val ifIdx = lyricsFn.indexOf("if (showClearConfirm)")
        assertTrue(lyricsFn.indexOf("var text by remember") in 0 until ifIdx)
        assertTrue(lyricsFn.indexOf("var showClearConfirm by remember") in 0 until ifIdx)
    }

    // I. Only confirmation reaches the existing clear callback
    @Test fun `I only the confirmation reaches the existing lyrics-clear callback`() {
        assertEquals(1, Regex("onConfirm\\s*=\\s*onClear").findAll(lyricsFn).count())
        assertEquals("parameter declaration plus that single use", 2, Regex("\\bonClear\\b").findAll(lyricsFn.substringBefore("private fun ArtworkWithLyricsOverlay")).count())
        assertTrue(lyricsFn.contains("onClick = { showClearConfirm = true }"))
        assertTrue(nowPlaying.contains("clearCustomLyrics"))
    }

    @Test fun `editor Clear is an error-coloured text button and Save stays the primary action`() {
        val clear = lyricsFn.substringAfter("if (hasCustomLyrics) {").substringBefore("Spacer(")
        assertTrue(clear.contains("TextButton("))
        assertTrue(clear.contains("colorScheme.error"))
        assertFalse(nowPlaying.contains("OutlinedButton"))
        assertTrue(lyricsFn.substringAfter("confirmButton = {").substringBefore("dismissButton = {").contains("Button("))
    }

    // J. Styling and native placement are retained in the (unmodified) shared component
    @Test fun `J shared dialog styling and native button slots are retained and now used by Queue and Now Playing`() {
        assertTrue(queue.contains("DestructiveConfirmDialog"))
        assertTrue(nowPlaying.contains("DestructiveConfirmDialog"))
        assertTrue(dialog.contains("colorScheme.error"))
        assertTrue(dialog.indexOf("confirmButton = {") in 0 until dialog.indexOf("dismissButton = {"))
        assertFalse(dialog.contains("Row("))
    }

    // K. Track Details and playlist deletion dialogs unchanged
    @Test fun `K Track Details and playlist dialogs are unchanged`() {
        assertTrue(trackDetails.contains("DestructiveDialogCopy.DELETE_TRACK_CONFIRM"))
        assertTrue(trackDetails.contains("DestructiveDialogCopy.REMOVE_LYRICS_CONFIRM"))
        for (s in listOf(playlists, playlistDetails)) assertTrue(s.contains("DestructiveDialogCopy.DELETE_PLAYLIST_CONFIRM"))
    }

    // L. Backup Recovery stays outside the shared dialog
    @Test fun `L backup recovery is not routed through the shared dialog`() {
        assertFalse(src("ui/screen/backupimport/BackupImportPreviewScreen.kt").contains("DestructiveConfirmDialog"))
    }
}
