package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.search.LibrarySearch

private const val COLLAPSED_SEARCH_RESULT_LIMIT = 5
private const val EXPANDED_SEARCH_RESULT_LIMIT  = 20

/**
 * Shared grouped library search UI: Songs / Artists / Albums sections with
 * query highlighting. Used by the Songs screen and Home search so both
 * entry points present the same search experience.
 */
data class SongSearchActions(
    val currentSongId: Long?,
    val favoriteSongIds: Set<Long>,
    val onSongClick: (Song) -> Unit,
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onToggleFavorite: (Song, Boolean) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onTrackDetailsClick: (Long) -> Unit,
    val onFolderClick: (String) -> Unit,
    val onShare: (Song) -> Unit,
)

internal data class GroupedSearchResults(
    val songs: List<Song>,
    val artists: List<ArtistSummary>,
    val albums: List<AlbumSummary>,
    val playlists: List<PlaylistSummary>,
    val smartCollections: List<SmartCollection> = emptyList(),
    val folders: List<com.launchpoint.wavdrop.data.model.FolderSummary>,
    val songsCapped: Boolean = false,
    val artistsCapped: Boolean = false,
    val albumsCapped: Boolean = false,
    val playlistsCapped: Boolean = false,
    val smartCollectionsCapped: Boolean = false,
    val foldersCapped: Boolean = false,
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() &&
            artists.isEmpty() &&
            albums.isEmpty() &&
            playlists.isEmpty() &&
            smartCollections.isEmpty() &&
            folders.isEmpty()
}

@Composable
fun GroupedSearchContent(
    songs: List<Song>,
    query: String,
    songActions: SongSearchActions,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onFolderClick: (String) -> Unit = {},
    playlists: List<PlaylistSummary> = emptyList(),
    onPlaylistClick: (Long) -> Unit = {},
    smartCollections: List<SmartCollection> = emptyList(),
    onSmartCollectionClick: (SmartCollectionType) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val results = remember(songs, playlists, smartCollections, query) {
        buildGroupedSearchResults(songs = songs, playlists = playlists, smartCollections = smartCollections, query = query)
    }

    // Expansion state resets when the query changes.
    var songsExpanded           by remember(query) { mutableStateOf(false) }
    var artistsExpanded         by remember(query) { mutableStateOf(false) }
    var albumsExpanded          by remember(query) { mutableStateOf(false) }
    var playlistsExpanded       by remember(query) { mutableStateOf(false) }
    var smartCollectionsExpanded by remember(query) { mutableStateOf(false) }
    var foldersExpanded         by remember(query) { mutableStateOf(false) }

    if (results.isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SearchEmptyState(
                title = "No results",
                message = "Try a song, artist, album, playlist, collection, or folder name.",
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
    ) {
        if (results.songs.isNotEmpty()) {
            val displayedSongs = results.songs.take(
                if (songsExpanded) EXPANDED_SEARCH_RESULT_LIMIT else COLLAPSED_SEARCH_RESULT_LIMIT,
            )
            item(key = "songs_header") {
                SearchSectionHeader(title = "Songs", count = results.songs.size, isCapped = results.songsCapped)
            }
            items(displayedSongs, key = { "song_${it.id}" }) { song ->
                SearchSongResultRow(
                    song = song,
                    query = query,
                    actions = songActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                SearchDivider()
            }
            if (results.songs.size > COLLAPSED_SEARCH_RESULT_LIMIT) {
                item(key = "songs_more") {
                    ShowMoreRow(
                        expanded    = songsExpanded,
                        hiddenCount = results.songs.size - displayedSongs.size,
                        label       = "songs",
                        isCapped    = results.songsCapped,
                        onToggle    = { songsExpanded = !songsExpanded },
                    )
                }
            }
        }
        if (results.artists.isNotEmpty()) {
            val displayedArtists = results.artists.take(
                if (artistsExpanded) EXPANDED_SEARCH_RESULT_LIMIT else COLLAPSED_SEARCH_RESULT_LIMIT,
            )
            item(key = "artists_header") {
                SearchSectionHeader(title = "Artists", count = results.artists.size, isCapped = results.artistsCapped)
            }
            items(displayedArtists, key = { "artist_${it.artistKey}" }) { artist ->
                SearchArtistRow(
                    artist = artist,
                    query = query,
                    onClick = { onArtistClick(artist.artistKey) },
                )
                SearchDivider()
            }
            if (results.artists.size > COLLAPSED_SEARCH_RESULT_LIMIT) {
                item(key = "artists_more") {
                    ShowMoreRow(
                        expanded    = artistsExpanded,
                        hiddenCount = results.artists.size - displayedArtists.size,
                        label       = "artists",
                        isCapped    = results.artistsCapped,
                        onToggle    = { artistsExpanded = !artistsExpanded },
                    )
                }
            }
        }
        if (results.albums.isNotEmpty()) {
            val displayedAlbums = results.albums.take(
                if (albumsExpanded) EXPANDED_SEARCH_RESULT_LIMIT else COLLAPSED_SEARCH_RESULT_LIMIT,
            )
            item(key = "albums_header") {
                SearchSectionHeader(title = "Albums", count = results.albums.size, isCapped = results.albumsCapped)
            }
            items(displayedAlbums, key = { "album_${it.albumKey}" }) { album ->
                SearchAlbumRow(
                    album = album,
                    query = query,
                    onClick = { onAlbumClick(album.albumKey) },
                )
                SearchDivider()
            }
            if (results.albums.size > COLLAPSED_SEARCH_RESULT_LIMIT) {
                item(key = "albums_more") {
                    ShowMoreRow(
                        expanded    = albumsExpanded,
                        hiddenCount = results.albums.size - displayedAlbums.size,
                        label       = "albums",
                        isCapped    = results.albumsCapped,
                        onToggle    = { albumsExpanded = !albumsExpanded },
                    )
                }
            }
        }
        if (results.playlists.isNotEmpty()) {
            val displayedPlaylists = results.playlists.take(
                if (playlistsExpanded) EXPANDED_SEARCH_RESULT_LIMIT else COLLAPSED_SEARCH_RESULT_LIMIT,
            )
            item(key = "playlists_header") {
                SearchSectionHeader(title = "Playlists", count = results.playlists.size, isCapped = results.playlistsCapped)
            }
            items(displayedPlaylists, key = { "playlist_${it.id}" }) { playlist ->
                SearchPlaylistRow(
                    playlist = playlist,
                    query = query,
                    onClick = { onPlaylistClick(playlist.id) },
                )
                SearchDivider()
            }
            if (results.playlists.size > COLLAPSED_SEARCH_RESULT_LIMIT) {
                item(key = "playlists_more") {
                    ShowMoreRow(
                        expanded    = playlistsExpanded,
                        hiddenCount = results.playlists.size - displayedPlaylists.size,
                        label       = "playlists",
                        isCapped    = results.playlistsCapped,
                        onToggle    = { playlistsExpanded = !playlistsExpanded },
                    )
                }
            }
        }
        if (results.smartCollections.isNotEmpty()) {
            val displayedSmartCollections = results.smartCollections.take(
                if (smartCollectionsExpanded) EXPANDED_SEARCH_RESULT_LIMIT else COLLAPSED_SEARCH_RESULT_LIMIT,
            )
            item(key = "smart_collections_header") {
                SearchSectionHeader(title = "Smart Collections", count = results.smartCollections.size, isCapped = results.smartCollectionsCapped)
            }
            items(displayedSmartCollections, key = { "smart_${it.type.name}" }) { collection ->
                SearchSmartCollectionRow(
                    collection = collection,
                    query      = query,
                    onClick    = { onSmartCollectionClick(collection.type) },
                )
                SearchDivider()
            }
            if (results.smartCollections.size > COLLAPSED_SEARCH_RESULT_LIMIT) {
                item(key = "smart_collections_more") {
                    ShowMoreRow(
                        expanded    = smartCollectionsExpanded,
                        hiddenCount = results.smartCollections.size - displayedSmartCollections.size,
                        label       = "smart collections",
                        isCapped    = results.smartCollectionsCapped,
                        onToggle    = { smartCollectionsExpanded = !smartCollectionsExpanded },
                    )
                }
            }
        }
        if (results.folders.isNotEmpty()) {
            val displayedFolders = results.folders.take(
                if (foldersExpanded) EXPANDED_SEARCH_RESULT_LIMIT else COLLAPSED_SEARCH_RESULT_LIMIT,
            )
            item(key = "folders_header") {
                SearchSectionHeader(title = "Folders", count = results.folders.size, isCapped = results.foldersCapped)
            }
            items(displayedFolders, key = { "folder_${it.folderKey}" }) { folder ->
                SearchFolderRow(
                    folder = folder,
                    query = query,
                    onClick = { onFolderClick(folder.folderKey) },
                )
                SearchDivider()
            }
            if (results.folders.size > COLLAPSED_SEARCH_RESULT_LIMIT) {
                item(key = "folders_more") {
                    ShowMoreRow(
                        expanded    = foldersExpanded,
                        hiddenCount = results.folders.size - displayedFolders.size,
                        label       = "folders",
                        isCapped    = results.foldersCapped,
                        onToggle    = { foldersExpanded = !foldersExpanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSongResultRow(
    song: Song,
    query: String,
    actions: SongSearchActions,
    modifier: Modifier = Modifier,
) {
    val isFavorite = song.id in actions.favoriteSongIds
    val highlightStyle = searchHighlightStyle()
    SongRowWithOverflow(
        song             = song,
        isCurrent        = song.id == actions.currentSongId,
        isFavorite       = isFavorite,
        onPlay           = { actions.onSongClick(song) },
        onPlayNext       = { actions.onPlayNext(song) },
        onAddToQueue     = { actions.onAddToQueue(song) },
        onToggleFavorite = { actions.onToggleFavorite(song, isFavorite) },
        onAddToPlaylist  = { actions.onAddToPlaylist(song) },
        onTrackDetails   = { actions.onTrackDetailsClick(song.id) },
        onViewFolder     = song.searchFolderKey()?.let { key -> { actions.onFolderClick(key) } },
        onShare          = { actions.onShare(song) },
        modifier         = modifier,
        highlightedTitle = highlightQuery(song.displayTitle, query, highlightStyle),
        highlightedArtist = highlightQuery(song.displayArtist, query, highlightStyle),
    )
}

@Composable
private fun SearchArtistRow(
    artist: ArtistSummary,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val artworkSize = if (compact) 40.dp else 44.dp
    val highlightStyle = searchHighlightStyle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtworkImage(
            artworkUri = artist.artworkUri,
            contentDescription = "Artist image for ${artist.artistKey}",
            placeholderIcon = Icons.Default.Person,
            modifier = Modifier.size(artworkSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightQuery(artist.artistKey, query, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artistSearchMeta(artist),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchAlbumRow(
    album: AlbumSummary,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val artworkSize = if (compact) 48.dp else 52.dp
    val highlightStyle = searchHighlightStyle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(album.albumId),
            contentDescription = "Album artwork for ${album.albumKey}",
            placeholderIcon = Icons.Default.Album,
            modifier = Modifier.size(artworkSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightQuery(album.albumKey, query, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = highlightQuery(album.artist, query, highlightStyle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${album.songCount} songs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchPlaylistRow(
    playlist: PlaylistSummary,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val iconSize = if (compact) 36.dp else 40.dp
    val highlightStyle = searchHighlightStyle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            modifier = Modifier.size(iconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightQuery(playlist.name, query, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.songCount} songs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun SearchSmartCollectionRow(
    collection: SmartCollection,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val iconSize = if (compact) 36.dp else 40.dp
    val highlightStyle = searchHighlightStyle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            modifier = Modifier.size(iconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightQuery(collection.title, query, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = highlightQuery(collection.description, query, highlightStyle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${collection.songCount} songs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun SearchFolderRow(
    folder: com.launchpoint.wavdrop.data.model.FolderSummary,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val iconSize = if (compact) 36.dp else 40.dp
    val highlightStyle = searchHighlightStyle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            modifier = Modifier.size(iconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightQuery(folder.displayName, query, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.songCount} songs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    count: Int,
    isCapped: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val countLabel = if (isCapped) "$count+" else "$count"
    Text(
        text = "$title ($countLabel)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ShowMoreRow(
    expanded: Boolean,
    hiddenCount: Int,
    label: String,
    isCapped: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when {
        !expanded         -> "Show $hiddenCount more $label"
        isCapped          -> "Show fewer · Showing first $EXPANDED_SEARCH_RESULT_LIMIT"
        else              -> "Show fewer"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SearchDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun SearchEmptyState(
    title: String,
    message: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun searchHighlightStyle(): SpanStyle =
    SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )

internal fun buildGroupedSearchResults(
    songs: List<Song>,
    query: String,
    playlists: List<PlaylistSummary> = emptyList(),
    smartCollections: List<SmartCollection> = emptyList(),
): GroupedSearchResults {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) {
        return GroupedSearchResults(
            songs = songs,
            artists = emptyList(),
            albums = emptyList(),
            playlists = emptyList(),
            smartCollections = emptyList(),
            folders = emptyList(),
        )
    }
    val allFolders               = FolderGrouper.groupSongsByFolder(songs)
    val allMatchedSongs          = LibrarySearch.filterSongs(songs, trimmedQuery)
    val allMatchedArtists        = LibrarySearch.filterArtists(ArtistGrouper.group(songs), songs, trimmedQuery)
    val allMatchedAlbums         = LibrarySearch.filterAlbums(AlbumGrouper.group(songs), trimmedQuery)
    val allMatchedPlaylists      = LibrarySearch.filterPlaylists(playlists, trimmedQuery)
    val allMatchedSmartCollections = LibrarySearch.filterSmartCollections(smartCollections, trimmedQuery)
    val allMatchedFolders        = LibrarySearch.filterFolders(allFolders, trimmedQuery)
    return GroupedSearchResults(
        songs                  = allMatchedSongs.take(EXPANDED_SEARCH_RESULT_LIMIT),
        artists                = allMatchedArtists.take(EXPANDED_SEARCH_RESULT_LIMIT),
        albums                 = allMatchedAlbums.take(EXPANDED_SEARCH_RESULT_LIMIT),
        playlists              = allMatchedPlaylists.take(EXPANDED_SEARCH_RESULT_LIMIT),
        smartCollections       = allMatchedSmartCollections.take(EXPANDED_SEARCH_RESULT_LIMIT),
        folders                = allMatchedFolders.take(EXPANDED_SEARCH_RESULT_LIMIT),
        songsCapped            = allMatchedSongs.size > EXPANDED_SEARCH_RESULT_LIMIT,
        artistsCapped          = allMatchedArtists.size > EXPANDED_SEARCH_RESULT_LIMIT,
        albumsCapped           = allMatchedAlbums.size > EXPANDED_SEARCH_RESULT_LIMIT,
        playlistsCapped        = allMatchedPlaylists.size > EXPANDED_SEARCH_RESULT_LIMIT,
        smartCollectionsCapped = allMatchedSmartCollections.size > EXPANDED_SEARCH_RESULT_LIMIT,
        foldersCapped          = allMatchedFolders.size > EXPANDED_SEARCH_RESULT_LIMIT,
    )
}

private fun highlightQuery(
    text: String,
    query: String,
    highlightStyle: SpanStyle,
): AnnotatedString {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return AnnotatedString(text)

    val sourceLower = text.lowercase()
    val queryLower = trimmedQuery.lowercase()
    return buildAnnotatedString {
        var currentIndex = 0
        while (currentIndex < text.length) {
            val matchIndex = sourceLower.indexOf(queryLower, startIndex = currentIndex)
            if (matchIndex < 0) {
                append(text.substring(currentIndex))
                break
            }
            append(text.substring(currentIndex, matchIndex))
            withStyle(highlightStyle) {
                append(text.substring(matchIndex, matchIndex + trimmedQuery.length))
            }
            currentIndex = matchIndex + trimmedQuery.length
        }
    }
}

private fun artistSearchMeta(artist: ArtistSummary): String = buildString {
    append("${artist.songCount} songs")
    if (artist.albumCount > 0) append(" - ${artist.albumCount} albums")
}

private fun Song.searchFolderKey(): String? {
    val hasFolderMetadata = !folderPath
        ?.trim()
        ?.trim('/', '\\')
        .isNullOrBlank()
    if (!hasFolderMetadata) return null
    return FolderGrouper.folderKey(this)
        .takeUnless { it == FolderGrouper.UNKNOWN_FOLDER }
        ?.takeIf { it.isNotBlank() }
}
