package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings

/** Stable browse-node IDs. Never localized titles; never colliding with numeric song IDs. */
internal object WavdropLibraryIds {
    const val ROOT = "wavdrop_root"
    const val RECENT = "wavdrop_recent"
    const val SONGS = "wavdrop_songs"

    /** Root returned only for LibraryParams.isRecent (System UI resumption); children are the playable item. */
    const val RECENT_ROOT = "wavdrop_recent_root"
}

/**
 * Pure browse-tree logic for [PlaybackService]'s MediaLibrarySession callbacks.
 *
 * ROOT -> { RECENT, SONGS }; SONGS -> playable songs (mediaId = Song.id); RECENT -> at most one
 * playable song identifying the resumable session. The RECENT item only names the SONG. Actual
 * resumption stays occurrence-safe via onPlaybackResumption / [PlaybackResumptionMapper]; queue
 * occurrence is never encoded into a browse item ID.
 */
internal object WavdropMediaLibrary {
    fun isBrowseNode(mediaId: String): Boolean =
        mediaId == WavdropLibraryIds.ROOT ||
            mediaId == WavdropLibraryIds.RECENT ||
            mediaId == WavdropLibraryIds.SONGS ||
            mediaId == WavdropLibraryIds.RECENT_ROOT

    private fun browsableNode(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    fun rootItem(): MediaItem = browsableNode(WavdropLibraryIds.ROOT, "Wavdrop")

    fun recentNode(): MediaItem = browsableNode(WavdropLibraryIds.RECENT, "Recently played")

    fun songsNode(): MediaItem = browsableNode(WavdropLibraryIds.SONGS, "Songs")

    fun recentRootNode(): MediaItem = browsableNode(WavdropLibraryIds.RECENT_ROOT, "Wavdrop recent")

    /** Root for a library-root request: the dedicated recent root for isRecent, else the normal root. */
    fun rootFor(isRecent: Boolean): MediaItem = if (isRecent) recentRootNode() else rootItem()

    fun rootChildren(): List<MediaItem> = listOf(recentNode(), songsNode())

    /** Canonical playback item (same mediaId/URI/metadata) flagged playable and not browsable. */
    fun songItem(song: Song): MediaItem {
        val base = song.toPlaybackMediaItem()
        return base.buildUpon()
            .setMediaMetadata(
                base.mediaMetadata.buildUpon()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }

    /** Zero-based [page] of [pageSize]; negative or zero sizes yield an empty page. */
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        val to = minOf(from + pageSize, items.size.toLong())
        return items.subList(from.toInt(), to.toInt())
    }

    fun songChildren(songs: List<Song>, page: Int, pageSize: Int): List<MediaItem> =
        page(songs, page, pageSize).map(::songItem)

    /**
     * The one resumable song, or empty. Reuses [PlaybackResumptionMapper], so a missing session,
     * settings-disabled resumption, unavailable songs and an ambiguous duplicate occurrence all
     * yield empty rather than a guessed first occurrence.
     */
    fun recentChildren(
        snapshot: PlaybackSessionSnapshot?,
        settings: ResumeBehaviorSettings,
        songs: List<Song>,
    ): List<MediaItem> =
        when (val result = PlaybackResumptionMapper.map(snapshot, settings, songs)) {
            is PlaybackResumptionResult.Ready ->
                listOf(songItem(result.plan.libraryQueue[result.plan.currentLibraryIndex]))
            is PlaybackResumptionResult.Unavailable -> emptyList()
        }

    /** Item lookup for known nodes and songs; null for anything unknown (no first-match guessing). */
    fun item(mediaId: String, songs: List<Song>): MediaItem? = when (mediaId) {
        WavdropLibraryIds.ROOT -> rootItem()
        WavdropLibraryIds.RECENT -> recentNode()
        WavdropLibraryIds.SONGS -> songsNode()
        WavdropLibraryIds.RECENT_ROOT -> recentRootNode()
        else -> mediaId.toLongOrNull()?.let { id -> songs.firstOrNull { it.id == id }?.let(::songItem) }
    }
}
