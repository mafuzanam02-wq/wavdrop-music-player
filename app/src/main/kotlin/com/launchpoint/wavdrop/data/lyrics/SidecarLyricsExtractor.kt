package com.launchpoint.wavdrop.data.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.launchpoint.wavdrop.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface SidecarLyricsExtractor {
    fun lookup(song: Song): SidecarLyricsLookup

    fun extract(song: Song): LyricsResult = lookup(song).result
}

@Singleton
class MediaStoreSidecarLyricsExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : SidecarLyricsExtractor {

    override fun lookup(song: Song): SidecarLyricsLookup {
        val audioInfo = readAudioInfo(song)
        // Root ("/") is only ever accepted from a verified MediaStore RELATIVE_PATH; unknown metadata never means root.
        val folder: SidecarFolder? = audioInfo?.folder
            ?: song.folderPath?.let(SidecarFolder::parse)?.takeIf { it is SidecarFolder.Path }
        val exactNames = SidecarLyricsCandidates.displayNames(audioInfo?.displayName, song.title)
        val relaxedNames = SidecarLyricsCandidates.relaxedDisplayNames(audioInfo?.displayName)
            .filterNot { relaxed -> exactNames.any { it.equals(relaxed, ignoreCase = true) } }
        val candidateFilenames = exactNames + relaxedNames
        val relaxedBase = SidecarLyricsCandidates.duplicateSuffixStrippedBase(audioInfo?.displayName)
        var competingAudio: Boolean? = null
        var competingChecked = false
        if (folder == null) {
            return SidecarLyricsLookup(
                result = LyricsResult.NotFound,
                sameFolderLrc = LyricsLookupStatus.NOT_FOUND,
                sameFolderTxt = LyricsLookupStatus.NOT_FOUND,
                folderPathUsed = null,
                candidateFilenames = candidateFilenames,
            )
        }

        var firstError: LyricsResult.Error? = null
        var lrcStatus = LyricsLookupStatus.NOT_FOUND
        var txtStatus = LyricsLookupStatus.NOT_FOUND
        for (displayName in candidateFilenames) {
            // Any candidate named after the suffix-stripped audio name is ambiguous, whether it came from the filename or the title.
            val isRelaxed = relaxedBase != null && SidecarLyricsCandidates.baseOf(displayName).equals(relaxedBase, ignoreCase = true)
            val result = readSidecar(folder, displayName) {
                if (!isRelaxed) return@readSidecar true
                if (relaxedBase == null) return@readSidecar false
                if (!competingChecked) {
                    competingAudio = hasCompetingAudio(folder, relaxedBase)
                    competingChecked = true
                }
                competingAudio == false // null (inconclusive) or true -> reject
            }
            when (result) {
                is LyricsResult.Available,
                is LyricsResult.Synced -> {
                    if (displayName.isLrcFilename()) lrcStatus = LyricsLookupStatus.FOUND
                    if (displayName.isTxtFilename()) txtStatus = LyricsLookupStatus.FOUND
                    return SidecarLyricsLookup(
                        result = result,
                        sameFolderLrc = lrcStatus,
                        sameFolderTxt = txtStatus,
                        folderPathUsed = folder.display,
                        candidateFilenames = candidateFilenames,
                    )
                }
                is LyricsResult.Error -> {
                    if (firstError == null) firstError = result
                    if (displayName.isLrcFilename()) lrcStatus = LyricsLookupStatus.ERROR
                    if (displayName.isTxtFilename()) txtStatus = LyricsLookupStatus.ERROR
                }
                LyricsResult.Loading,
                LyricsResult.NotFound -> Unit
            }
        }

        return SidecarLyricsLookup(
            result = firstError ?: LyricsResult.NotFound,
            sameFolderLrc = lrcStatus,
            sameFolderTxt = txtStatus,
            folderPathUsed = folder.display,
            candidateFilenames = candidateFilenames,
        )
    }

    private fun readAudioInfo(song: Song): AudioInfo? {
        val projection = buildList {
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        return runCatching {
            context.contentResolver.query(
                Uri.parse(song.uri),
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val displayName = cursor.getNullableString(displayNameCol)
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    cursor.getNullableString(relativePathCol)?.let(SidecarFolder::parse)
                } else {
                    @Suppress("DEPRECATION")
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    cursor.getNullableString(dataCol)
                        ?.substringBeforeLast('/', missingDelimiterValue = "")
                        ?.toFolderPath()
                        ?.let(SidecarFolder::parse)
                }
                AudioInfo(displayName = displayName, folder = folder)
            }
        }.getOrNull()
    }

    /** [accept] runs only after the sidecar file was found, so extra checks (collision) are not paid for misses. */
    private inline fun readSidecar(
        folder: SidecarFolder,
        displayName: String,
        accept: () -> Boolean,
    ): LyricsResult {
        val sidecarUri = findSidecarUri(folder = folder, displayName = displayName)
            ?: return LyricsResult.NotFound
        if (!accept()) return LyricsResult.NotFound

        return runCatching {
            context.contentResolver.openInputStream(sidecarUri)?.use { input ->
                input.bufferedReader().use { reader ->
                    SidecarLyricsContent.resolve(displayName, reader.readText())
                }
            }
        }.fold(
            onSuccess = { result -> result ?: LyricsResult.NotFound },
            onFailure = { error ->
                LyricsResult.Error(error.message ?: "Could not read sidecar lyrics")
            },
        )
    }

    private fun findSidecarUri(
        folder: SidecarFolder,
        displayName: String,
    ): Uri? {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        // Best effort: MediaStore may not expose text sidecars on every Android version/provider.
        // Keep the query narrow by display name, then accept only files in the same folder.
        return runCatching {
            context.contentResolver.query(
                filesUri,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val candidatePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getNullableString(relativePathCol)?.let(SidecarFolder::parse)
                    } else {
                        cursor.getNullableString(dataCol)
                            ?.substringBeforeLast('/', missingDelimiterValue = "")
                            ?.toFolderPath()
                            ?.let(SidecarFolder::parse)
                    }
                    if (folder.accepts(candidatePath)) {
                        return@use ContentUris.withAppendedId(filesUri, cursor.getLong(idCol))
                    }
                }
                null
            }
        }.getOrNull()
    }

    /** true = a possible audio file named exactly [base] exists in [folder]; null = a possible one has unknown folder, or the query failed. */
    private fun hasCompetingAudio(folder: SidecarFolder, base: String): Boolean? {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()
        val pattern = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%"
        return runCatching {
            context.contentResolver.query(
                filesUri,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf(pattern),
                null,
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                } else {
                    -1
                }
                var found = false
                var inconclusive = false
                while (cursor.moveToNext()) {
                    val name = cursor.getNullableString(nameCol) ?: continue
                    if (!name.substringBeforeLast('.').equals(base, ignoreCase = true)) continue
                    // Potentially audio: classified as audio OR carrying an audio extension (MediaStore may leave type NONE/null).
                    val typedAudio = !cursor.isNull(typeCol) && cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
                    val audioExtension = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
                    if (!typedAudio && !audioExtension) continue
                    val path = if (pathCol >= 0) {
                        cursor.getNullableString(pathCol)?.let(SidecarFolder::parse)
                    } else {
                        cursor.getNullableString(dataCol)
                            ?.substringBeforeLast('/', missingDelimiterValue = "")
                            ?.toFolderPath()
                            ?.let(SidecarFolder::parse)
                    }
                    if (path == null) inconclusive = true else if (folder.accepts(path)) found = true
                }
                if (found) true else if (inconclusive) null else false
            }
        }.getOrNull()
    }

    private fun android.database.Cursor.getNullableString(columnIndex: Int): String? =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex) else null

    private fun String.toFolderPath(): String? =
        trim()
            .trim('/', '\\')
            .takeIf { it.isNotBlank() }

    private companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "alac", "aif", "aiff", "amr", "mka", "m4b", "mp2", "ape", "wv", "dsf", "mid", "midi",
        )
    }

    private fun String.isLrcFilename(): Boolean =
        endsWith(".lrc", ignoreCase = true)

    private fun String.isTxtFilename(): Boolean =
        endsWith(".txt", ignoreCase = true)

    private data class AudioInfo(
        val displayName: String?,
        val folder: SidecarFolder?,
    )
}

/**
 * Where an audio file or a candidate sidecar lives. [Root] comes only from a MediaStore `RELATIVE_PATH` of exactly "/";
 * absent or unusable folder metadata is `null` and never means root.
 */
internal sealed interface SidecarFolder {
    val display: String

    data object Root : SidecarFolder {
        override val display = "/"
    }

    data class Path(val normalized: String, override val display: String) : SidecarFolder

    /** True when [candidate] is a verified matching location. Unknown (null) never matches. */
    fun accepts(candidate: SidecarFolder?): Boolean = when (this) {
        Root -> candidate == Root
        is Path -> candidate is Path &&
            (candidate.normalized == normalized || candidate.normalized.endsWith("/$normalized"))
    }

    companion object {
        fun parse(raw: String?): SidecarFolder? {
            val trimmed = raw?.trim() ?: return null
            val inner = trimmed.replace('\\', '/').trim('/')
            if (inner.isBlank()) return if (trimmed == "/") Root else null
            return Path(normalized = inner.lowercase(), display = trimmed.trim('/', '\\'))
        }
    }
}

internal object SidecarLyricsCandidates {
    fun displayNames(
        audioDisplayName: String?,
        title: String,
    ): List<String> {
        val bases = linkedSetOf<String>()
        audioDisplayName?.baseNameWithoutExtension()?.takeIf { it.isNotBlank() }?.let(bases::add)
        title.trim().takeIf { it.isNotBlank() }?.let(bases::add)

        return listOf("lrc", "txt").flatMap { extension ->
            bases.map { base -> "$base.$extension" }
        }
    }

    private val duplicateSuffix = Regex("""^(.*\S)\s+\(\d{1,3}\)$""")

    /** Audio base name minus one terminal " (N)" duplicate-number suffix, or null when it has none. */
    fun duplicateSuffixStrippedBase(audioDisplayName: String?): String? {
        val base = audioDisplayName?.baseNameWithoutExtension() ?: return null
        return duplicateSuffix.matchEntire(base)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /** Fallback names (lrc before txt); tried only after every exact name. */
    fun baseOf(displayName: String): String = displayName.baseNameWithoutExtension()

    fun relaxedDisplayNames(audioDisplayName: String?): List<String> {
        val base = duplicateSuffixStrippedBase(audioDisplayName) ?: return emptyList()
        return listOf("lrc", "txt").map { "$base.$it" }
    }

    private fun String.baseNameWithoutExtension(): String {
        val name = substringAfterLast('/').substringAfterLast('\\').trim()
        val extensionIndex = name.lastIndexOf('.')
        return if (extensionIndex > 0) name.substring(0, extensionIndex) else name
    }
}

/** Pure raw-text → result decision for a sidecar file, separated from MediaStore I/O. */
internal object SidecarLyricsContent {
    /**
     * `.lrc` with usable timestamps → [LyricsResult.Synced]; an untimed/unusable `.lrc` and every
     * `.txt` → existing cleaned plain text. Null when there is no lyric text at all.
     */
    fun resolve(displayName: String, raw: String): LyricsResult? {
        if (displayName.endsWith(".lrc", ignoreCase = true)) {
            LrcParser.parse(raw)?.let { parsed ->
                return LyricsResult.Synced(lines = parsed.lines, plainText = parsed.plainText)
            }
        }
        return LyricsTextCleaner.clean(raw)?.let { LyricsResult.Available(it) }
    }
}
