package com.launchpoint.wavdrop.data.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,       // milliseconds
    val uri: String,          // content:// URI — use for playback and art lookup
    val dateAdded: Long,      // epoch seconds (from MediaStore)
    val trackNumber: Int,
    val year: Int,
    val folderPath: String? = null,
    val folderName: String? = null,
) {
    val displayTitle: String
        get() = SongDisplayMetadata.from(title = title, artist = artist).title

    val displayArtist: String
        get() = SongDisplayMetadata.from(title = title, artist = artist).artist
}

data class SongDisplayMetadata(
    val title: String,
    val artist: String,
) {
    companion object {
        private val whitespace = Regex("\\s+")
        private val extension = Regex("\\.(?:mp3|m4a|flac|wav|ogg|aac|opus)$", RegexOption.IGNORE_CASE)
        private val artistTitleSeparator = Regex("\\s+[-\u2010-\u2015]+\\s+")
        private val bracketedNoise = Regex(
            "\\s*[\\(\\[]\\s*(?:\\d{2,4}\\s*k|official\\s+lyrics?\\s+video|official\\s+lyric\\s+video|official\\s+lyrics?|official\\s+video|official\\s+audio|lyric\\s+video|lyrics?|audio|hd)\\s*[\\)\\]]\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val trailingNoise = Regex(
            "\\s*(?:[-\u2010-\u2015]\\s*)?(?:\\d{2,4}\\s*k|official\\s+lyrics?\\s+video|official\\s+lyric\\s+video|official\\s+lyrics?|official\\s+video|official\\s+audio|lyric\\s+video|lyrics?|audio|hd)\\s*$",
            RegexOption.IGNORE_CASE,
        )

        fun from(title: String?, artist: String?): SongDisplayMetadata {
            val rawTitle = title.orEmpty().trim()
            val rawArtist = artist.orEmpty().trim()
            val titleKnown = rawTitle.hasKnownMetadata("Unknown Title")
            val artistKnown = rawArtist.hasKnownMetadata("Unknown Artist")

            if (titleKnown && artistKnown) {
                return SongDisplayMetadata(rawTitle, rawArtist)
            }

            val filenameLikeSource = normalizeFilenameLikeText(rawTitle)
            val split = splitArtistTitle(filenameLikeSource)
            val fallbackTitle = split?.title ?: cleanPart(filenameLikeSource)
            val fallbackArtist = split?.artist

            return SongDisplayMetadata(
                title = when {
                    titleKnown -> fallbackTitle.takeIf { it.isNotBlank() } ?: rawTitle
                    fallbackTitle.isNotBlank() -> fallbackTitle
                    else -> rawTitle.ifBlank { "Unknown Title" }
                },
                artist = when {
                    artistKnown -> rawArtist
                    !fallbackArtist.isNullOrBlank() -> fallbackArtist
                    else -> rawArtist.ifBlank { "Unknown Artist" }
                },
            )
        }

        private fun splitArtistTitle(value: String): ArtistTitle? {
            val parts = artistTitleSeparator.split(value, limit = 2)
            if (parts.size != 2) return null
            val artist = cleanPart(parts[0])
            val title = cleanPart(parts[1])
            if (artist.isBlank() || title.isBlank()) return null
            return ArtistTitle(artist = artist, title = title)
        }

        private fun normalizeFilenameLikeText(value: String): String =
            value
                .replace(extension, "")
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun cleanPart(value: String): String {
            var current = value.trim()
            while (true) {
                val next = current
                    .replace(bracketedNoise, "")
                    .replace(trailingNoise, "")
                    .trim()
                if (next == current) return next.collapseWhitespace()
                current = next
            }
        }

        private fun String.hasKnownMetadata(unknownLabel: String): Boolean =
            isNotBlank() &&
                !equals(unknownLabel, ignoreCase = true) &&
                !equals("<unknown>", ignoreCase = true)

        private fun String.collapseWhitespace(): String =
            replace(whitespace, " ")

        private data class ArtistTitle(
            val artist: String,
            val title: String,
        )
    }
}
