package com.launchpoint.wavdrop.data.lyrics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * P5-F17D: the production [MediaStoreSidecarLyricsExtractor] against a fake `media` provider that returns exactly the shapes the
 * Galaxy A15 returned for the Cuffley files (audio and LRC both in the storage root, `RELATIVE_PATH = "/"`, audio named
 * `… (1).mp3`, an identical LRC copy in `Music/`). Isolated, deterministic: no real MediaStore, no files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreSidecarLyricsExtractorTest {

    private class Row(
        val id: Long,
        val name: String,
        val relativePath: String?,
        val mediaType: Int?,
        val content: String? = null,
    )

    /** Minimal stand-in for MediaStore: audio-by-id, files-by-name, files-by-name-prefix (the collision query). */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sortOrder: String?): Cursor? {
            val cols = projection ?: arrayOf("_id")
            val segments = uri.pathSegments // external / audio / media / <id>   or   external / file
            val rows: List<Row> = when {
                segments.size == 4 && segments[1] == "audio" && segments[2] == "media" ->
                    listOfNotNull(table.firstOrNull { it.id == segments[3].toLong() })
                segments.size == 2 && segments[1] == "file" -> {
                    val wanted = args?.firstOrNull().orEmpty()
                    if (selection.orEmpty().contains("LIKE")) {
                        likeQueries++
                        if (likeBehavior == LikeBehavior.THROW) throw IllegalStateException("collision query failed")
                        if (likeBehavior == LikeBehavior.NULL_CURSOR) return null
                        val prefix = wanted.removeSuffix("%").replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\")
                        table.filter { it.name.startsWith(prefix, ignoreCase = true) }
                    } else {
                        table.filter { it.name == wanted }
                    }
                }
                else -> emptyList()
            }
            val cursor = MatrixCursor(cols)
            rows.forEach { r ->
                cursor.addRow(
                    cols.map { c ->
                        when (c) {
                            "_id" -> r.id
                            "_display_name" -> r.name
                            "relative_path" -> if (omitRelativePath) null else r.relativePath
                            "media_type" -> r.mediaType
                            "_data" -> null
                            else -> null
                        }
                    }.toTypedArray(),
                )
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private enum class LikeBehavior { NORMAL, THROW, NULL_CURSOR }

    private companion object {
        var table: List<Row> = emptyList()
        var likeBehavior = LikeBehavior.NORMAL
        var likeQueries = 0
        var omitRelativePath = false
        const val AUDIO = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
        const val NONE = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        const val LRC_BODY = "[00:01.00]hello\n[00:05.00]world\n"
    }

    @Before fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        likeBehavior = LikeBehavior.NORMAL
        likeQueries = 0
        omitRelativePath = false
    }

    @After fun tearDown() {
        table = emptyList()
    }

    private fun install(vararg rows: Row) {
        table = rows.toList()
        val resolver = shadowOf(RuntimeEnvironment.getApplication().contentResolver)
        rows.filter { it.content != null }.forEach { r ->
            resolver.registerInputStream(Uri.parse("content://media/external/file/${r.id}"), ByteArrayInputStream(r.content!!.toByteArray()))
        }
    }

    private fun songFor(audio: Row, folderPath: String? = null, title: String = audio.name.substringBeforeLast('.')) = Song(
        id = audio.id, title = title, artist = "Artist", album = "Album", albumId = 1L, duration = 1L,
        uri = "content://media/external/audio/media/${audio.id}", dateAdded = 0L, trackNumber = 0, year = 0, folderPath = folderPath,
    )

    private fun lookup(audio: Row, folderPath: String? = null, title: String = audio.name.substringBeforeLast('.')): SidecarLyricsLookup =
        MediaStoreSidecarLyricsExtractor(RuntimeEnvironment.getApplication()).lookup(songFor(audio, folderPath, title))

    private fun SidecarLyricsLookup.firstLineText(): String? = (result as? LyricsResult.Synced)?.lines?.first()?.text

    private fun lrc(id: Long, name: String, path: String?, text: String = "hello") =
        Row(id, name, path, NONE, "[00:01.00]$text\n[00:05.00]more\n")

    // 1. verified root + verified root
    @Test fun rootAudioAndRootLrcWithTheSameNameMatch() {
        val audio = Row(1, "Song.mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/", "root"))
        assertEquals("root", lookup(audio).firstLineText())
    }

    // Exact Cuffley names, with the Music/ copy present: the ROOT file is used and the Music copy is not.
    @Test fun cuffleyRootAudioWithSuffixFindsItsRootLrcNotTheMusicCopy() {
        val audio = Row(1000008662, "Alexander William - Cuffley (Official Video) (1).mp3", "/", AUDIO)
        install(
            audio,
            lrc(1000008663, "Alexander William - Cuffley (Official Video).lrc", "/", "root copy"),
            lrc(1000008673, "Alexander William - Cuffley (Official Video).lrc", "Music/", "music copy"),
        )
        assertEquals("root copy", lookup(audio).firstLineText())
    }

    // 2. unknown folder is not root
    @Test fun unknownFolderMetadataIsNotTreatedAsRoot() {
        val audio = Row(1, "Song.mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/"))
        omitRelativePath = true // provider returns no RELATIVE_PATH for the audio row (and for the lrc)
        assertEquals(LyricsResult.NotFound, lookup(audio, folderPath = null).result)
    }

    @Test fun missingAudioRowAndNoSongFolderIsNotTreatedAsRoot() {
        val audio = Row(1, "Song.mp3", "/", AUDIO)
        table = listOf(lrc(2, "Song.lrc", "/")) // the audio row cannot be read at all
        assertEquals(LyricsResult.NotFound, lookup(audio, folderPath = null).result)
    }

    // 3. root vs Music/
    @Test fun rootAudioDoesNotMatchAnLrcInMusic() {
        val audio = Row(1, "Song.mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "Music/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun musicAudioDoesNotMatchARootLrc() {
        val audio = Row(1, "Song.mp3", "Music/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    // 4. exact filenames keep priority over the relaxed one
    @Test fun exactSuffixedLrcWinsOverTheUnsuffixedOne() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/", "plain"), lrc(3, "Song (1).lrc", "/", "exact"))
        assertEquals("exact", lookup(audio).firstLineText())
    }

    // 5. (1) suffix can match its unsuffixed LRC when unambiguous
    @Test fun suffixedAudioMatchesUnsuffixedLrcWhenNoCompetingAudio() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/", "relaxed"))
        assertEquals("relaxed", lookup(audio).firstLineText())
    }

    @Test fun suffixTwoIsAlsoStripped() {
        val audio = Row(1, "Song (2).mp3", "Music/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "Music/", "relaxed"))
        assertEquals("relaxed", lookup(audio).firstLineText())
    }

    // 6. competing original audio in the same folder
    @Test fun relaxedMatchIsRejectedWhenTheUnsuffixedAudioExistsInTheSameFolder() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", "/", AUDIO), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun competingAudioWithAnotherExtensionAlsoBlocksTheRelaxedMatch() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.flac", "/", AUDIO), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun sameNamedAudioInAnotherFolderDoesNotBlockTheRelaxedMatch() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", "Music/", AUDIO), lrc(3, "Song.lrc", "/", "relaxed"))
        assertEquals("relaxed", lookup(audio).firstLineText())
    }

    // 7. inconclusive collision check
    @Test fun relaxedMatchIsRejectedWhenTheCollisionQueryThrows() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/"))
        likeBehavior = LikeBehavior.THROW
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun relaxedMatchIsRejectedWhenTheCollisionQueryReturnsNoCursor() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/"))
        likeBehavior = LikeBehavior.NULL_CURSOR
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    // the collision query is only needed (and only run) once a relaxed candidate file actually exists
    @Test fun noCollisionQueryIsRunWhenNoRelaxedLrcExists() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio)
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
        assertEquals(0, likeQueries)
    }

    // 8. ordinary nested lookup
    @Test fun nestedFolderExactLookupStillWorks() {
        val audio = Row(1, "Song.mp3", "Music/Album/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "Music/Album/", "nested"))
        assertEquals("nested", lookup(audio).firstLineText())
    }

    @Test fun nestedLookupFallsBackToSongFolderPathWhenTheAudioRowHasNone() {
        val audio = Row(1, "Song.mp3", null, AUDIO)
        install(audio, lrc(2, "Song.lrc", "Music/", "nested"))
        assertEquals("nested", lookup(audio, folderPath = "Music").firstLineText())
    }

    @Test fun anLrcInAnotherNestedFolderIsNotAccepted() {
        val audio = Row(1, "Song.mp3", "Music/A/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "Music/B/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    // 9. non matching / ambiguous stay NotFound
    @Test fun differentlyNamedLrcIsNotMatched() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, lrc(2, "Other Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun onlyATerminalNumberSuffixIsStripped() {
        val interior = Row(1, "Song (1) remix.mp3", "/", AUDIO)
        install(interior, lrc(2, "Song remix.lrc", "/"), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(interior).result)
        val meaningful = Row(4, "Song (live).mp3", "/", AUDIO)
        install(meaningful, lrc(5, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(meaningful).result)
    }

    // 10. LRC before TXT
    @Test fun lrcIsStillPreferredToTxt() {
        val audio = Row(1, "Song.mp3", "/", AUDIO)
        install(audio, Row(2, "Song.txt", "/", NONE, "plain text"), lrc(3, "Song.lrc", "/", "synced"))
        assertTrue(lookup(audio).result is LyricsResult.Synced)
    }

    @Test fun relaxedLrcIsPreferredToRelaxedTxt() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.txt", "/", NONE, "plain text"), lrc(3, "Song.lrc", "/", "synced"))
        assertTrue(lookup(audio).result is LyricsResult.Synced)
    }

    // ---- P5-F17D correction: metadata-title bypass ----

    @Test fun titleEqualToStrippedNameDoesNotBypassTheCollisionCheck() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", "/", AUDIO), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio, title = "Song").result)
    }

    @Test fun titleEqualToStrippedNameStillMatchesWhenNoCompetingOriginalExists() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, lrc(2, "Song.lrc", "/", "via title"))
        assertEquals("via title", lookup(audio, title = "Song").firstLineText())
    }

    @Test fun exactAudioFilenameStillNeedsNoCollisionCheckEvenWithTitleVariant() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", "/", AUDIO), lrc(3, "Song (1).lrc", "/", "exact"), lrc(4, "Song.lrc", "/", "plain"))
        assertEquals("exact", lookup(audio, title = "Song").firstLineText())
        assertEquals(0, likeQueries)
    }

    @Test fun ordinaryTitleFallbackForUnrelatedFilenamesIsUnaffected() {
        val audio = Row(1, "01 Track.mp3", "/", AUDIO)
        install(audio, Row(2, "Track Title.mp3", "/", AUDIO), lrc(3, "Track Title.lrc", "/", "title"))
        assertEquals("title", lookup(audio, title = "Track Title").firstLineText())
        assertEquals(0, likeQueries)
    }

    // ---- P5-F17D correction: inconclusive media classification ----

    @Test fun competingAudioClassifiedAsNoneBlocksTheRelaxedMatch() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", "/", NONE), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun competingAudioWithNullMediaTypeBlocksTheRelaxedMatch() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.flac", "/", null), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun nonAudioFileWithASimilarBasenameDoesNotBlock() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.txt", "/", NONE, "plain"), Row(4, "Song.jpg", "/", null), lrc(3, "Song.lrc", "/", "relaxed"))
        assertEquals("relaxed", lookup(audio).firstLineText())
    }

    @Test fun audioExtensionCandidateWithMissingFolderMetadataBlocks() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", null, NONE), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun audioTypedCandidateWithMissingFolderMetadataBlocks() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", null, AUDIO), lrc(3, "Song.lrc", "/"))
        assertEquals(LyricsResult.NotFound, lookup(audio).result)
    }

    @Test fun nonAudioCandidateWithMissingFolderMetadataDoesNotBlock() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.txt", null, NONE, "plain"), lrc(3, "Song.lrc", "/", "relaxed"))
        assertEquals("relaxed", lookup(audio).firstLineText())
    }

    @Test fun unclassifiedAudioExtensionInAnotherKnownFolderDoesNotBlock() {
        val audio = Row(1, "Song (1).mp3", "/", AUDIO)
        install(audio, Row(2, "Song.mp3", "Music/", null), lrc(3, "Song.lrc", "/", "relaxed"))
        assertEquals("relaxed", lookup(audio).firstLineText())
    }
}
