package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer

object LibrarySearch {

    fun filterSongs(songs: List<Song>, query: String): List<Song> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            MusicTextNormalizer.normalizeSearch(song.title).contains(q) ||
            MusicTextNormalizer.normalizeSearch(song.artist).contains(q) ||
            MusicTextNormalizer.normalizeSearch(song.album).contains(q)
        }
    }

    fun filterAlbums(albums: List<AlbumSummary>, query: String): List<AlbumSummary> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return albums
        return albums.filter { album ->
            MusicTextNormalizer.normalizeSearch(album.albumKey).contains(q) ||
            MusicTextNormalizer.normalizeSearch(album.artist).contains(q)
        }
    }

    fun filterFolders(folders: List<FolderSummary>, query: String): List<FolderSummary> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return folders
        return folders.filter { folder ->
            MusicTextNormalizer.normalizeSearch(folder.displayName).contains(q) ||
            MusicTextNormalizer.normalizeSearch(folder.folderKey).contains(q)
        }
    }

    fun filterArtists(
        artists: List<ArtistSummary>,
        songs: List<Song>,
        query: String,
    ): List<ArtistSummary> {
        val q = MusicTextNormalizer.normalizeSearch(query)
        if (q.isEmpty()) return artists
        val songsByArtist = songs.groupBy { MusicTextNormalizer.normalizeStrict(ArtistGrouper.artistKey(it)) }
        return artists.filter { artist ->
            MusicTextNormalizer.normalizeSearch(artist.artistKey).contains(q) ||
            songsByArtist[MusicTextNormalizer.normalizeStrict(artist.artistKey)].orEmpty().any { song ->
                MusicTextNormalizer.normalizeSearch(song.title).contains(q) ||
                MusicTextNormalizer.normalizeSearch(song.album).contains(q)
            }
        }
    }
}
