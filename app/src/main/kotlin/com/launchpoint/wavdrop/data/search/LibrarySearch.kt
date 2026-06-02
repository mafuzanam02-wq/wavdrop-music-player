package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.model.Song

object LibrarySearch {

    fun filterSongs(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            song.title.lowercase().contains(q) ||
            song.artist.lowercase().contains(q) ||
            song.album.lowercase().contains(q)
        }
    }

    fun filterAlbums(albums: List<AlbumSummary>, query: String): List<AlbumSummary> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return albums
        return albums.filter { album ->
            album.albumKey.lowercase().contains(q) ||
            album.artist.lowercase().contains(q)
        }
    }

    fun filterFolders(folders: List<FolderSummary>, query: String): List<FolderSummary> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return folders
        return folders.filter { folder ->
            folder.displayName.lowercase().contains(q) ||
            folder.folderKey.lowercase().contains(q)
        }
    }

    fun filterArtists(
        artists: List<ArtistSummary>,
        songs: List<Song>,
        query: String,
    ): List<ArtistSummary> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return artists
        val songsByArtist = songs.groupBy { ArtistGrouper.artistKey(it).lowercase() }
        return artists.filter { artist ->
            artist.artistKey.lowercase().contains(q) ||
            songsByArtist[artist.artistKey.lowercase()].orEmpty().any { song ->
                song.title.lowercase().contains(q) ||
                song.album.lowercase().contains(q)
            }
        }
    }
}
