package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.model.Song

object AlphabetIndex {

    fun firstIndexForSongLetter(songs: List<Song>, letter: Char): Int? =
        firstIndexForLetter(songs, letter) { it.title }

    fun firstIndexForAlbumLetter(albums: List<AlbumSummary>, letter: Char): Int? =
        firstIndexForLetter(albums, letter) { it.albumKey }

    fun firstIndexForArtistLetter(artists: List<ArtistSummary>, letter: Char): Int? =
        firstIndexForLetter(artists, letter) { it.artistKey }

    fun firstIndexForFolderLetter(folders: List<FolderSummary>, letter: Char): Int? =
        firstIndexForLetter(folders, letter) { it.displayName }

    private inline fun <T> firstIndexForLetter(
        items: List<T>,
        letter: Char,
        crossinline label: (T) -> String,
    ): Int? {
        val target = bucketForLetter(letter)
        return items.indexOfFirst { item -> bucketForText(label(item)) == target }
            .takeIf { it >= 0 }
    }

    private fun bucketForLetter(letter: Char): Char =
        if (letter == '#') '#' else letter.uppercaseChar().takeIf { it in 'A'..'Z' } ?: '#'

    private fun bucketForText(text: String): Char {
        val first = text.trim().firstOrNull()?.uppercaseChar() ?: return '#'
        return if (first in 'A'..'Z') first else '#'
    }
}
