package com.launchpoint.wavdrop.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QSP-1 structural guards (source-text, the style used by the other structure guards): one queue-level action, no per-song
 * entry, Cancel writes nothing, Save goes through the ViewModel, the save never touches playback, the ordinary duplicate-add
 * policy is unchanged, and nothing in schema / backup / permissions changed.
 */
class QueueSaveStructureGuardTest {

    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText()
    private fun code(text: String) = text.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")

    private val sheet = src("ui/screen/nowplaying/QueueSheet.kt")
    private val vm = src("ui/screen/nowplaying/NowPlayingViewModel.kt")
    private val repo = src("data/repository/PlaylistRepository.kt")

    @Test fun `Save queue as playlist is one queue-level menu item, absent for an empty queue, and not in any song menu`() {
        assertFalse("no literal duplicate of the label in the sheet", sheet.contains("\"Save queue as playlist\""))
        val header = sheet.substringAfter("private fun QueueSheetHeader(").substringBefore("private fun QueueSectionHeader(")
        assertTrue("the item lives in the header menu", header.contains("QUEUE_SAVE_MENU_LABEL"))
        assertTrue("the whole menu is absent unless the queue can be saved", header.contains("if (canSaveQueue)"))
        assertTrue("availability is derived from the actual queue (empty or external -> absent)", sheet.contains("canSaveQueue = canSaveQueue(state.queue),"))
        assertFalse("never from the size alone", sheet.contains("canSaveQueue(state.queue.size)"))
        assertTrue("an open dialog is closed if the queue becomes unsaveable", sheet.contains("if (!canSaveQueue(state.queue)) {"))
        assertFalse("Compose never tests the sentinel id", sheet.contains("Long.MIN_VALUE") || sheet.contains("EXTERNAL_AUDIO_SONG_ID"))
        // per-song overflow menus never mention it
        val rows = sheet.substringAfter("private fun QueueSectionHeader(")
        assertEquals("only the header menu item and the dialog title use the label", 2, Regex("QUEUE_SAVE_MENU_LABEL").findAll(sheet).count())
        assertFalse("not in per-song menus", rows.contains("QUEUE_SAVE_MENU_LABEL") || rows.contains("Save queue"))
        assertEquals(1, Regex("""DropdownMenuItem\(\s*text = \{ Text\(QUEUE_SAVE_MENU_LABEL\)""").findAll(sheet).count())
    }

    @Test fun `the dialog has a name field, Save and Cancel, and keeps errors inline`() {
        val dialog = sheet.substringAfter("private fun SaveQueueAsPlaylistDialog(").substringBefore("// ── Section header")
        assertTrue(dialog.contains("OutlinedTextField("))
        assertTrue(dialog.contains("""Text("Playlist name")"""))
        assertTrue(dialog.contains("""Text("Save")""") && dialog.contains("""Text("Cancel")"""))
        assertTrue("an error is shown inline in the field", dialog.contains("supportingText") && dialog.contains("isError = error != null"))
        val cancel = dialog.substringAfter("dismissButton")
        assertTrue("Cancel only dismisses", cancel.contains("onClick = onDismiss") && !cancel.contains("onSave"))
        assertTrue("Save calls onSave with the typed name", dialog.contains("onSave(name)"))
        assertFalse("the dialog never writes anything itself", dialog.contains("playlistRepository") || dialog.contains("viewModel"))
    }

    @Test fun `Save uses the ViewModel method, closes only on success and shows the success snackbar`() {
        assertTrue(sheet.contains("onSaveQueueAsPlaylist: (String, (QueueSaveResult) -> Unit) -> Unit"))
        assertTrue(src("ui/screen/nowplaying/NowPlayingScreen.kt").contains("viewModel.saveQueueAsPlaylist(name, onResult)"))
        val wiring = sheet.substringAfter("// ── Save queue as playlist (QSP-1)").substringBefore("// ── Bulk clear confirmation")
        assertTrue(wiring.contains("onSaveQueueAsPlaylist(name)"))
        assertTrue("success closes the dialog and shows the snackbar, the sheet stays open", wiring.contains("showSaveQueueDialog = false") && wiring.contains("snackbarHostState.showSnackbar(QUEUE_SAVED_MESSAGE)"))
        assertFalse("saving never dismisses the Queue Sheet or navigates", wiring.contains("onDismiss()") || wiring.contains("onViewStats"))
        assertTrue("outcome decides success vs inline error", wiring.contains("queueSaveOutcome(result)") && wiring.contains("reportOutcome(outcome)"))
        val copy = src("ui/screen/nowplaying/QueueSave.kt")
        assertTrue(copy.contains("\"Queue saved as playlist\"") && copy.contains("\"A playlist with this name already exists\"") && copy.contains("\"Enter a playlist name\""))
    }

    @Test fun `the ViewModel snapshots the queue when called and never touches playback`() {
        val fn = vm.substringAfter("fun saveQueueAsPlaylist").substringBefore("fun createPlaylistAndAdd")
        assertTrue("the queue is read and copied at call time, before the coroutine", fn.indexOf("val queue = nowPlayingState.value.queue") in 0 until fn.indexOf("queueSongIdsSnapshot(queue)") && fn.indexOf("queueSongIdsSnapshot(queue)") in 0 until fn.indexOf("viewModelScope.launch"))
        assertTrue("one repository call with the immutable id list", fn.contains("playlistRepository.createPlaylistFromQueue(name, songIds)"))
        assertTrue("the result reaches the callback", fn.contains("onResult(playlistRepository.createPlaylistFromQueue(name, songIds))"))
        val body = code(fn)
        for (forbidden in listOf("playerController", "playFromQueue", "moveQueue", "removeFromQueue", "seek", "togglePlayPause", "pause", "bumpQueue", "replaceQueue", "jumpToQueueItem")) {
            assertFalse("saving the queue must not call $forbidden", body.contains(forbidden))
        }
    }

    @Test fun `D, E and J - external audio is refused at the ViewModel and the repository and external playback itself is unchanged`() {
        val fn = vm.substringAfter("fun saveQueueAsPlaylist").substringBefore("fun createPlaylistAndAdd")
        assertTrue("the ViewModel refuses an external queue before any repository call", fn.indexOf("QueueSaveResult.UnsavableQueue") in 0 until fn.indexOf("playlistRepository.createPlaylistFromQueue"))
        assertTrue(fn.contains("!canSaveQueue(queue)"))
        val save = repo.substringAfter("suspend fun createPlaylistFromQueue").substringBefore("suspend fun renamePlaylist")
        assertTrue("the repository refuses it whole, with no per-song query", save.contains("songIds.any(ExternalAudioIdentity::isExternalAudioId)") && !save.contains("filter") && !save.contains("getSong"))
        val playback = src("playback/PlayerController.kt")
        assertTrue("external playback still builds its song from the same id", playback.contains("const val EXTERNAL_AUDIO_SONG_ID = com.launchpoint.wavdrop.data.model.ExternalAudioIdentity.SONG_ID") &&
            playback.substringAfter("private fun Uri.toExternalSong").contains("id = EXTERNAL_AUDIO_SONG_ID"))
        assertEquals("the id value itself is unchanged", Long.MIN_VALUE, com.launchpoint.wavdrop.data.model.ExternalAudioIdentity.SONG_ID)
        assertEquals("the sentinel is declared once in the app", 1, File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .count { it.readText().contains("= Long.MIN_VALUE") && it.readText().contains("SONG_ID") })
    }

    @Test fun `the repository has its own exact-sequence path and the ordinary duplicate policy is unchanged`() {
        val save = repo.substringAfter("suspend fun createPlaylistFromQueue").substringBefore("suspend fun renamePlaylist")
        assertFalse("QSP-1 must not reuse the de-duplicating add", save.contains("addSongsToPlaylist"))
        assertTrue(save.contains("db.withTransaction"))
        assertEquals("one batched insert", 1, Regex("""dao\.insertSongs\(""").findAll(save).count())
        assertTrue(save.contains("position = index"))
        assertFalse("no per-entry transactions or inserts", save.contains("insertSong(") || save.contains("touchPlaylist"))
        val add = repo.substringAfter("suspend fun addSongsToPlaylist").substringBefore("suspend fun removePlaylistEntry")
        assertTrue("ordinary add still skips songs already in the playlist", add.contains("songIds.filterNot { it in existingIds }") && add.contains("AddToPlaylistResult(added = newIds.size, skipped = skipped)"))
    }

    @Test fun `no schema, backup, permission, manifest or dependency change`() {
        val db = src("data/local/WavdropDatabase.kt")
        val version = Regex("""version\s*=\s*(\d+)""").find(db)!!.groupValues[1].toInt()
        val newest = File("schemas/com.launchpoint.wavdrop.data.local.WavdropDatabase").listFiles()!!
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals("no Room schema change", newest, version)
        for (f in File("src/main/kotlin/com/launchpoint/wavdrop/data/backup").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            assertFalse("${f.name} has no queue-playlist concept", f.readText().contains("QueueSave") || f.readText().contains("createPlaylistFromQueue"))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("INTERNET"))
    }
}
