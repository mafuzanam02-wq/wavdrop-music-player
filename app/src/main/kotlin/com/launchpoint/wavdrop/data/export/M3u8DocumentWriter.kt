package com.launchpoint.wavdrop.data.export

import java.io.OutputStream

sealed interface ExportWriteResult {
    object Success : ExportWriteResult
    object NoOutputStream : ExportWriteResult
    /** The destination may now hold an empty or partial file; the app never deletes the user's document. */
    data class Failed(val detail: String?) : ExportWriteResult
}

object M3u8DocumentWriter {
    /** Opens with truncating "wt" first, then "w" (some providers reject "wt"). Success only after close() succeeds. */
    fun write(bytes: ByteArray, openOutput: (String) -> OutputStream?): ExportWriteResult {
        val stream = try {
            openOutput("wt") ?: openOutput("w")
        } catch (e: Exception) {
            return ExportWriteResult.Failed(e.message)
        } ?: return ExportWriteResult.NoOutputStream
        return try {
            stream.use { it.write(bytes); it.flush() }
            ExportWriteResult.Success
        } catch (e: Exception) {
            ExportWriteResult.Failed(e.message)
        }
    }
}
