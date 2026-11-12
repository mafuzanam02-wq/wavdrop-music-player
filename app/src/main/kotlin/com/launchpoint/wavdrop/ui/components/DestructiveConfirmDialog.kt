package com.launchpoint.wavdrop.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * P5-UX-02A. Wording for the three destructive confirmations this slice covers. Kept as plain strings so the consequences
 * stay unit-testable and identical wherever the same action is offered.
 */
internal object DestructiveDialogCopy {
    const val CANCEL = "Cancel"

    const val DELETE_TRACK_TITLE = "Delete from device?"
    const val DELETE_TRACK_CONFIRM = "Delete from device"
    fun deleteTrackBody(title: String): String =
        "\"$title\" will be permanently deleted from your device storage and removed from your library and from any playlists. " +
            "Your listening history and statistics for it are kept. Wavdrop cannot undo this. Android will ask you to confirm next."

    const val DELETE_PLAYLIST_TITLE = "Delete playlist?"
    const val DELETE_PLAYLIST_CONFIRM = "Delete playlist"
    fun deletePlaylistBody(name: String): String =
        "\"$name\" will be deleted. Its songs and audio files stay on your device. This can't be undone."

    const val REMOVE_LYRICS_TITLE = "Remove lyrics?"
    const val REMOVE_LYRICS_CONFIRM = "Remove lyrics"
    const val REMOVE_LYRICS_BODY =
        "Your saved custom lyrics for this track will be removed. If the audio file has embedded lyrics or a matching .lrc file " +
            "in the same folder, those will be shown instead."
}

/**
 * Standard Material 3 [AlertDialog]: in left-to-right layouts Cancel (dismissButton) sits to the left of the destructive
 * action (confirmButton), and the platform mirrors this for right-to-left. The destructive label uses the theme's error colour
 * and names its consequence; Cancel uses a neutral on-surface colour rather than the accent. Dismissal and Back run
 * [onDismiss] only, and the confirm action fires at most once per dialog instance.
 */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = DestructiveDialogCopy.CANCEL,
) {
    var confirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!confirmed) {
                        confirmed = true
                        onConfirm()
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) { Text(cancelLabel) }
        },
    )
}
