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
        val folderPath = audioInfo?.folderPath ?: song.folderPath?.toFolderPath()
        val candidateFilenames = SidecarLyricsCandidates.displayNames(audioInfo?.displayName, song.title)
        if (folderPath.isNullOrBlank()) {
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
            when (val result = readSidecar(folderPath, displayName)) {
                is LyricsResult.Available -> {
                    if (displayName.isLrcFilename()) lrcStatus = LyricsLookupStatus.FOUND
                    if (displayName.isTxtFilename()) txtStatus = LyricsLookupStatus.FOUND
                    return SidecarLyricsLookup(
                        result = result,
                        sameFolderLrc = lrcStatus,
                        sameFolderTxt = txtStatus,
                        folderPathUsed = folderPath,
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
            folderPathUsed = folderPath,
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
                val folderPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    cursor.getNullableString(relativePathCol)?.toFolderPath()
                } else {
                    @Suppress("DEPRECATION")
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    cursor.getNullableString(dataCol)
                        ?.substringBeforeLast('/', missingDelimiterValue = "")
                        ?.toFolderPath()
                }
                AudioInfo(displayName = displayName, folderPath = folderPath)
            }
        }.getOrNull()
    }

    private fun readSidecar(
        folderPath: String,
        displayName: String,
    ): LyricsResult {
        val sidecarUri = findSidecarUri(folderPath = folderPath, displayName = displayName)
            ?: return LyricsResult.NotFound

        return runCatching {
            context.contentResolver.openInputStream(sidecarUri)?.use { input ->
                input.bufferedReader().use { reader ->
                    LyricsTextCleaner.clean(reader.readText())
                }
            }
        }.fold(
            onSuccess = { cleaned ->
                if (cleaned == null) LyricsResult.NotFound else LyricsResult.Available(cleaned)
            },
            onFailure = { error ->
                LyricsResult.Error(error.message ?: "Could not read sidecar lyrics")
            },
        )
    }

    private fun findSidecarUri(
        folderPath: String,
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
                        cursor.getNullableString(relativePathCol)?.toFolderPath()
                    } else {
                        cursor.getNullableString(dataCol)
                            ?.substringBeforeLast('/', missingDelimiterValue = "")
                            ?.toFolderPath()
                    }
                    if (sameFolder(candidatePath, folderPath)) {
                        return@use ContentUris.withAppendedId(filesUri, cursor.getLong(idCol))
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun sameFolder(candidatePath: String?, expectedPath: String): Boolean {
        val candidate = candidatePath.normalizedFolderPath()
        val expected = expectedPath.normalizedFolderPath()
        if (candidate.isBlank() || expected.isBlank()) return false
        return candidate == expected || candidate.endsWith("/$expected")
    }

    private fun android.database.Cursor.getNullableString(columnIndex: Int): String? =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex) else null

    private fun String.toFolderPath(): String? =
        trim()
            .trim('/', '\\')
            .takeIf { it.isNotBlank() }

    private fun String?.normalizedFolderPath(): String =
        this
            ?.replace('\\', '/')
            ?.trim()
            ?.trim('/')
            ?.lowercase()
            .orEmpty()

    private fun String.isLrcFilename(): Boolean =
        endsWith(".lrc", ignoreCase = true)

    private fun String.isTxtFilename(): Boolean =
        endsWith(".txt", ignoreCase = true)

    private data class AudioInfo(
        val displayName: String?,
        val folderPath: String?,
    )
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

    private fun String.baseNameWithoutExtension(): String {
        val name = substringAfterLast('/').substringAfterLast('\\').trim()
        val extensionIndex = name.lastIndexOf('.')
        return if (extensionIndex > 0) name.substring(0, extensionIndex) else name
    }
}
