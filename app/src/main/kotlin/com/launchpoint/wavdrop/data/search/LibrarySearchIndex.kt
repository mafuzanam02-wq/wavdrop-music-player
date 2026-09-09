package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer

/**
 * A [Song] paired with its precomputed normalized searchable fields (Phase 9).
 *
 * Lightweight by design: it holds three extra normalized strings plus a reference to the existing
 * [song]. No artwork, bitmap, URI, or lyric text is duplicated — the heavy [Song] payload is shared
 * by reference. For a 25k-song library this projection is a few MB of short strings.
 */
class SearchableSong(
    val song: Song,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val normalizedAlbum: String,
)

/**
 * In-memory normalized search projection over a library snapshot (Phase 9).
 *
 * The problem it solves: [LibrarySearch] / `buildGroupedSearchResults` re-normalized every song's
 * title/artist/album (via the heavy [MusicTextNormalizer]) and re-grouped the whole library into
 * artists/albums/folders on *every keystroke*. That work depends only on the library, not the
 * query, so this index precomputes it ONCE per library snapshot ([from]) and each query then only
 * runs cheap substring matching against the precomputed normalized fields.
 *
 * Semantics are identical to [LibrarySearch]: same matched fields, same predicates, same input
 * ordering, same normalization ([MusicTextNormalizer.normalizeSearch] / [normalizeStrict]). The
 * groupings are produced by the same [ArtistGrouper]/[AlbumGrouper]/[FolderGrouper] used before, so
 * their order and contents are unchanged — only *when* they are computed differs.
 *
 * Rebuild [from] only when the library (or playlists/collections) actually change; reuse the same
 * instance across many queries. Pure and UI-free so it lives in the data layer and is unit-testable.
 */
class LibrarySearchIndex private constructor(
    /** Original library snapshot in source order (returned verbatim for a blank song query). */
    val songs: List<Song>,
    private val searchableSongs: List<SearchableSong>,
    private val artists: List<IndexedArtist>,
    private val albums: List<IndexedAlbum>,
    private val folders: List<IndexedFolder>,
    private val playlists: List<IndexedPlaylist>,
    private val smartCollections: List<IndexedSmartCollection>,
    /** Songs grouped by strict-normalized artist key, mirroring [LibrarySearch.filterArtists]. */
    private val songsByStrictArtistKey: Map<String, List<SearchableSong>>,
) {

    /** Mirrors [LibrarySearch.filterSongs]: matches normalized title/artist/album, source order. */
    fun filterSongs(query: String): List<Song> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return songs
        return searchableSongs
            .filter { it.normalizedTitle.contains(q) || it.normalizedArtist.contains(q) || it.normalizedAlbum.contains(q) }
            .map { it.song }
    }

    /** Mirrors [LibrarySearch.filterArtists]: matches artist key or any of that artist's song titles/albums. */
    fun filterArtists(query: String): List<ArtistSummary> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return artists.map { it.summary }
        return artists
            .filter { artist ->
                artist.normalizedKey.contains(q) ||
                    songsByStrictArtistKey[artist.strictKey].orEmpty().any {
                        it.normalizedTitle.contains(q) || it.normalizedAlbum.contains(q)
                    }
            }
            .map { it.summary }
    }

    /** Mirrors [LibrarySearch.filterAlbums]: matches album key or album artist. */
    fun filterAlbums(query: String): List<AlbumSummary> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return albums.map { it.summary }
        return albums
            .filter { it.normalizedKey.contains(q) || it.normalizedArtist.contains(q) }
            .map { it.summary }
    }

    /** Mirrors [LibrarySearch.filterFolders]: matches display name or folder key. */
    fun filterFolders(query: String): List<FolderSummary> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return folders.map { it.summary }
        return folders
            .filter { it.normalizedDisplayName.contains(q) || it.normalizedKey.contains(q) }
            .map { it.summary }
    }

    /** Mirrors [LibrarySearch.filterPlaylists]: matches playlist name only. */
    fun filterPlaylists(query: String): List<PlaylistSummary> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return playlists.map { it.summary }
        return playlists.filter { it.normalizedName.contains(q) }.map { it.summary }
    }

    /** Mirrors [LibrarySearch.filterSmartCollections]: matches title or description. */
    fun filterSmartCollections(query: String): List<SmartCollection> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return smartCollections.map { it.collection }
        return smartCollections
            .filter { it.normalizedTitle.contains(q) || it.normalizedDescription.contains(q) }
            .map { it.collection }
    }

    private class IndexedArtist(val summary: ArtistSummary, val normalizedKey: String, val strictKey: String)
    private class IndexedAlbum(val summary: AlbumSummary, val normalizedKey: String, val normalizedArtist: String)
    private class IndexedFolder(val summary: FolderSummary, val normalizedDisplayName: String, val normalizedKey: String)
    private class IndexedPlaylist(val summary: PlaylistSummary, val normalizedName: String)
    private class IndexedSmartCollection(val collection: SmartCollection, val normalizedTitle: String, val normalizedDescription: String)

    companion object {
        /**
         * Builds the projection for a library snapshot. This is the only place the heavy
         * normalization/grouping runs; do it once per library change, not per query.
         */
        fun from(
            songs: List<Song>,
            playlists: List<PlaylistSummary> = emptyList(),
            smartCollections: List<SmartCollection> = emptyList(),
        ): LibrarySearchIndex {
            val searchable = songs.map { song ->
                SearchableSong(
                    song = song,
                    normalizedTitle = MusicTextNormalizer.normalizeSearch(song.title),
                    normalizedArtist = MusicTextNormalizer.normalizeSearch(song.artist),
                    normalizedAlbum = MusicTextNormalizer.normalizeSearch(song.album),
                )
            }
            val byStrictArtistKey = searchable.groupBy {
                MusicTextNormalizer.normalizeStrict(ArtistGrouper.artistKey(it.song))
            }
            val indexedArtists = ArtistGrouper.group(songs).map { artist ->
                IndexedArtist(
                    summary = artist,
                    normalizedKey = MusicTextNormalizer.normalizeSearch(artist.artistKey),
                    strictKey = MusicTextNormalizer.normalizeStrict(artist.artistKey),
                )
            }
            val indexedAlbums = AlbumGrouper.group(songs).map { album ->
                IndexedAlbum(
                    summary = album,
                    normalizedKey = MusicTextNormalizer.normalizeSearch(album.albumKey),
                    normalizedArtist = MusicTextNormalizer.normalizeSearch(album.artist),
                )
            }
            val indexedFolders = FolderGrouper.groupSongsByFolder(songs).map { folder ->
                IndexedFolder(
                    summary = folder,
                    normalizedDisplayName = MusicTextNormalizer.normalizeSearch(folder.displayName),
                    normalizedKey = MusicTextNormalizer.normalizeSearch(folder.folderKey),
                )
            }
            val indexedPlaylists = playlists.map {
                IndexedPlaylist(summary = it, normalizedName = MusicTextNormalizer.normalizeSearch(it.name))
            }
            val indexedSmartCollections = smartCollections.map {
                IndexedSmartCollection(
                    collection = it,
                    normalizedTitle = MusicTextNormalizer.normalizeSearch(it.title),
                    normalizedDescription = MusicTextNormalizer.normalizeSearch(it.description),
                )
            }
            return LibrarySearchIndex(
                songs = songs,
                searchableSongs = searchable,
                artists = indexedArtists,
                albums = indexedAlbums,
                folders = indexedFolders,
                playlists = indexedPlaylists,
                smartCollections = indexedSmartCollections,
                songsByStrictArtistKey = byStrictArtistKey,
            )
        }
    }
}
