package com.launchpoint.wavdrop.data.export

import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Resolves song ids to device file paths. Absent keys are unresolvable. */
interface MediaPathSource {
    suspend fun resolvePaths(songIds: Set<Long>): Map<Long, String>
}

data class MediaPathRow(val id: Long, val data: String?)

object MediaPathRules {
    const val CHUNK_SIZE = 500 // well under SQLite's 999 bound-variable limit

    /** Only a verified MediaStore DATA path counts: absolute, never synthesized from names or content:// URIs. */
    fun pathFor(row: MediaPathRow): String? =
        row.data?.takeIf { it.isNotBlank() && it.startsWith("/") }

    suspend fun resolveInChunks(
        ids: Set<Long>,
        chunkSize: Int,
        query: suspend (List<Long>) -> List<MediaPathRow>,
    ): Map<Long, String> {
        val out = HashMap<Long, String>(ids.size)
        for (chunk in ids.toList().chunked(chunkSize)) {
            for (row in query(chunk)) pathFor(row)?.let { out[row.id] = it }
        }
        return out
    }
}

/** Read-only, batched MediaStore lookup of DATA. No permissions beyond what the library scan already holds. */
class MediaStorePathResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaPathSource {

    override suspend fun resolvePaths(songIds: Set<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        MediaPathRules.resolveInChunks(songIds, MediaPathRules.CHUNK_SIZE) { chunk -> queryChunk(chunk) }
    }

    @Suppress("DEPRECATION") // DATA is verified on-device (F19B preflight) as the usable path source.
    private fun queryChunk(ids: List<Long>): List<MediaPathRow> {
        val selection = "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",") { "?" }})"
        val rows = ArrayList<MediaPathRow>(ids.size)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
            selection, ids.map { it.toString() }.toTypedArray(), null,
        )?.use { c ->
            while (c.moveToNext()) rows += MediaPathRow(c.getLong(0), c.getString(1))
        }
        return rows
    }
}
