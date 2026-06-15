package com.launchpoint.wavdrop.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.launchpoint.wavdrop.data.model.Song

/**
 * Launches the system share sheet for a local audio track using its MediaStore content URI.
 * Calls [onFailure] if the URI is blank or if no activity can handle the intent.
 */
fun shareSong(context: Context, song: Song, onFailure: () -> Unit) {
    if (song.uri.isBlank()) { onFailure(); return }
    runCatching {
        val uri = Uri.parse(song.uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, song.displayTitle))
    }.onFailure { onFailure() }
}
