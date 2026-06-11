package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * File-selection gating for restore/import flows.
 *
 * SAF pickers cannot reliably filter by extension (and providers often mislabel
 * MIME types), so each flow validates the selected file AFTER picking:
 *  - Wavdrop restore accepts `.json` names, or content that structurally looks
 *    like a Wavdrop backup when the provider reports no usable name.
 *  - BlackPlayer import accepts only `.bpstat` names.
 */
object ImportFileValidation {

    const val WAVDROP_WRONG_FILE_MESSAGE   = "Choose a Wavdrop backup JSON file."
    const val WAVDROP_NOT_A_BACKUP_MESSAGE = "This does not look like a Wavdrop backup file."
    const val BPSTAT_WRONG_FILE_MESSAGE    = "Choose a BlackPlayer .bpstat file."

    /** Resolves the user-visible file name via OpenableColumns, falling back to the URI path. */
    fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0 && cursor.moveToFirst() && !cursor.isNull(col)) {
                        cursor.getString(col)
                    } else {
                        null
                    }
                }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    fun isLikelyWavdropBackupFileName(name: String?): Boolean =
        name?.trim()?.lowercase()?.endsWith(".json") == true

    fun isLikelyBlackPlayerStatsFileName(name: String?): Boolean =
        name?.trim()?.lowercase()?.endsWith(".bpstat") == true

    /**
     * Cheap structural sniff used when the file name is unavailable or not `.json`:
     * a Wavdrop backup is a JSON object that declares the wavdrop_backup format.
     * This avoids running the full parser on arbitrary binary/text files.
     */
    fun isLikelyWavdropBackupContent(content: String): Boolean {
        val head = content.trimStart()
        return head.startsWith("{") && content.contains("\"${WavdropBackupParser.SUPPORTED_FORMAT}\"")
    }
}
