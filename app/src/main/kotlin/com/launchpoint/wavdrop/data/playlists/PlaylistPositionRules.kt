package com.launchpoint.wavdrop.data.playlists

data class PlaylistPositionEntry(
    val songId: Long,
    val position: Int,
)

object PlaylistPositionRules {

    fun append(
        current: List<PlaylistPositionEntry>,
        songIds: List<Long>,
    ): List<PlaylistPositionEntry> {
        if (songIds.isEmpty()) return reindex(current.sortedBy { it.position })
        val normalized = reindex(current.sortedBy { it.position })
        val start = normalized.size
        return normalized + songIds.mapIndexed { index, songId ->
            PlaylistPositionEntry(songId = songId, position = start + index)
        }
    }

    fun removeAtPosition(
        current: List<PlaylistPositionEntry>,
        position: Int,
    ): List<PlaylistPositionEntry> {
        return reindex(current.sortedBy { it.position }.filterNot { it.position == position })
    }

    fun move(
        current: List<PlaylistPositionEntry>,
        fromPosition: Int,
        toPosition: Int,
    ): List<PlaylistPositionEntry> {
        val ordered = current.sortedBy { it.position }
        val fromIndex = ordered.indexOfFirst { it.position == fromPosition }
        val toIndex = ordered.indexOfFirst { it.position == toPosition }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) {
            return reindex(ordered)
        }

        val mutable = ordered.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return reindex(mutable)
    }

    fun reindex(entries: List<PlaylistPositionEntry>): List<PlaylistPositionEntry> {
        return entries
            .mapIndexed { index, entry -> entry.copy(position = index) }
    }
}
