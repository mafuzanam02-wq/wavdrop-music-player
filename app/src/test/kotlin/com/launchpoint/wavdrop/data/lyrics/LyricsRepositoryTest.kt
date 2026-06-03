package com.launchpoint.wavdrop.data.lyrics

import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private fun song(id: Long) = Song(
        id = id, title = "T$id", artist = "A", album = "B",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private class FakeLyricsExtractor(
        private val responses: Map<String, LyricsResult> = emptyMap(),
        private val default: LyricsResult = LyricsResult.NotFound,
    ) : LyricsExtractor {
        var callCount = 0
        override fun extract(uri: String): LyricsResult {
            callCount++
            return responses[uri] ?: default
        }
    }

    private class FakeSidecarLyricsExtractor(
        private val responses: Map<Long, SidecarLyricsLookup> = emptyMap(),
        private val default: SidecarLyricsLookup = sidecarLookup(),
    ) : SidecarLyricsExtractor {
        var callCount = 0
        override fun lookup(song: Song): SidecarLyricsLookup {
            callCount++
            return responses[song.id] ?: default
        }
    }

    private class FakeLyricsOverrideDao(
        initial: LyricsOverrideEntity? = null,
    ) : LyricsOverrideDao {
        private val overrideFlow = MutableStateFlow(initial)

        override fun observeBySongId(songId: Long): Flow<LyricsOverrideEntity?> =
            overrideFlow

        override suspend fun getForSong(songId: Long, contentUri: String): LyricsOverrideEntity? =
            overrideFlow.value?.takeIf { it.songId == songId || it.contentUri == contentUri }

        override suspend fun getAllSnapshot(): List<LyricsOverrideEntity> =
            listOfNotNull(overrideFlow.value)

        override suspend fun upsert(entity: LyricsOverrideEntity) {
            overrideFlow.value = entity
        }

        override suspend fun deleteForSong(songId: Long, contentUri: String) {
            if (overrideFlow.value?.let { it.songId == songId || it.contentUri == contentUri } == true) {
                overrideFlow.value = null
            }
        }
    }

    companion object {
        private fun sidecarLookup(
            result: LyricsResult = LyricsResult.NotFound,
            sameFolderLrc: LyricsLookupStatus = LyricsLookupStatus.NOT_FOUND,
            sameFolderTxt: LyricsLookupStatus = LyricsLookupStatus.NOT_FOUND,
            folderPathUsed: String? = "Music",
            candidateFilenames: List<String> = listOf("T1.lrc", "T1.txt"),
        ) = SidecarLyricsLookup(
            result = result,
            sameFolderLrc = sameFolderLrc,
            sameFolderTxt = sameFolderTxt,
            folderPathUsed = folderPathUsed,
            candidateFilenames = candidateFilenames,
        )
    }

    // ── processRaw (pure logic in MediaMetadataLyricsExtractor) ──────────────

    @Test
    fun `processRaw null returns NotFound`() {
        assertEquals(LyricsResult.NotFound, MediaMetadataLyricsExtractor.processRaw(null))
    }

    @Test
    fun `processRaw empty string returns NotFound`() {
        assertEquals(LyricsResult.NotFound, MediaMetadataLyricsExtractor.processRaw(""))
    }

    @Test
    fun `processRaw blank string returns NotFound`() {
        assertEquals(LyricsResult.NotFound, MediaMetadataLyricsExtractor.processRaw("   "))
    }

    @Test
    fun `processRaw trims leading and trailing whitespace`() {
        assertEquals(
            LyricsResult.Available("Hello World"),
            MediaMetadataLyricsExtractor.processRaw("  Hello World  "),
        )
    }

    @Test
    fun `processRaw preserves internal whitespace and newlines`() {
        assertEquals(
            LyricsResult.Available("Line one\nLine two"),
            MediaMetadataLyricsExtractor.processRaw("Line one\nLine two"),
        )
    }

    // ── LyricsRepository cache behaviour ─────────────────────────────────────

    @Test
    fun `getLyrics returns available lyrics for matching song`() {
        val song = song(1)
        val fake = FakeLyricsExtractor(
            responses = mapOf(song.uri to LyricsResult.Available("Verse one")),
        )
        val sidecar = FakeSidecarLyricsExtractor(
            default = sidecarLookup(
                result = LyricsResult.Available("Sidecar"),
                sameFolderLrc = LyricsLookupStatus.FOUND,
            ),
        )
        val repo = LyricsRepository(fake, sidecar)

        val result = runBlocking { repo.getLyrics(song) }

        assertEquals(LyricsResult.Available("Verse one"), result)
        assertEquals(0, sidecar.callCount)
    }

    @Test
    fun `getLyrics returns custom override before embedded and sidecar lyrics`() {
        val song = song(31)
        val embedded = FakeLyricsExtractor(default = LyricsResult.Available("Embedded"))
        val sidecar = FakeSidecarLyricsExtractor(
            default = sidecarLookup(
                result = LyricsResult.Available("Sidecar"),
                sameFolderLrc = LyricsLookupStatus.FOUND,
            ),
        )
        val overrideDao = FakeLyricsOverrideDao(
            LyricsOverrideEntity(
                songId = song.id,
                contentUri = song.uri,
                lyrics = "Custom lyrics",
                updatedAt = 1L,
            ),
        )
        val repo = LyricsRepository(
            embedded,
            sidecar,
            LyricsOverrideRepository(overrideDao),
        )

        val result = runBlocking { repo.getLyrics(song) }

        assertEquals(LyricsResult.Available("Custom lyrics"), result)
        assertEquals(0, embedded.callCount)
        assertEquals(0, sidecar.callCount)
    }

    @Test
    fun `saveCustomLyrics invalidates cached lyrics and returns override`() {
        val song = song(32)
        val embedded = FakeLyricsExtractor(default = LyricsResult.Available("Embedded"))
        val repo = LyricsRepository(
            embedded,
            FakeSidecarLyricsExtractor(),
            LyricsOverrideRepository(FakeLyricsOverrideDao()),
        )

        val result = runBlocking {
            assertEquals(LyricsResult.Available("Embedded"), repo.getLyrics(song))
            repo.saveCustomLyrics(song, "Custom\nLyrics")
            repo.getLyrics(song)
        }

        assertEquals(LyricsResult.Available("Custom\nLyrics"), result)
    }

    @Test
    fun `clearCustomLyrics invalidates cached override and falls back`() {
        val song = song(33)
        val overrideDao = FakeLyricsOverrideDao(
            LyricsOverrideEntity(
                songId = song.id,
                contentUri = song.uri,
                lyrics = "Custom",
                updatedAt = 1L,
            ),
        )
        val repo = LyricsRepository(
            FakeLyricsExtractor(default = LyricsResult.Available("Embedded")),
            FakeSidecarLyricsExtractor(),
            LyricsOverrideRepository(overrideDao),
        )

        val result = runBlocking {
            assertEquals(LyricsResult.Available("Custom"), repo.getLyrics(song))
            repo.clearCustomLyrics(song)
            repo.getLyrics(song)
        }

        assertEquals(LyricsResult.Available("Embedded"), result)
    }

    @Test
    fun `getLyrics returns NotFound when extractor finds nothing`() {
        val song = song(2)
        val fake = FakeLyricsExtractor(default = LyricsResult.NotFound)
        val repo = LyricsRepository(fake, FakeSidecarLyricsExtractor(default = sidecarLookup()))

        val result = runBlocking { repo.getLyrics(song) }

        assertEquals(LyricsResult.NotFound, result)
    }

    @Test
    fun `getLyricsLookup reports embedded and sidecar not found diagnostics`() {
        val song = song(24)
        val embedded = FakeLyricsExtractor(default = LyricsResult.NotFound)
        val sidecar = FakeSidecarLyricsExtractor(
            default = sidecarLookup(
                folderPathUsed = "Music/Artist",
                candidateFilenames = listOf("Song.lrc", "Song.txt"),
            ),
        )
        val repo = LyricsRepository(embedded, sidecar)

        val lookup = runBlocking { repo.getLyricsLookup(song) }

        assertEquals(LyricsResult.NotFound, lookup.result)
        assertEquals(LyricsLookupStatus.NOT_FOUND, lookup.diagnostics.embeddedMetadata)
        assertEquals(LyricsLookupStatus.NOT_FOUND, lookup.diagnostics.sameFolderLrc)
        assertEquals(LyricsLookupStatus.NOT_FOUND, lookup.diagnostics.sameFolderTxt)
        assertEquals("Music/Artist", lookup.diagnostics.folderPathUsed)
        assertEquals(listOf("Song.lrc", "Song.txt"), lookup.diagnostics.candidateFilenames)
        assertEquals(song.uri, lookup.diagnostics.songContentUri)
    }

    @Test
    fun `getLyricsLookup reports embedded error and sidecar not found diagnostics`() {
        val song = song(25)
        val embedded = FakeLyricsExtractor(default = LyricsResult.Error("Embedded failed"))
        val sidecar = FakeSidecarLyricsExtractor(default = sidecarLookup())
        val repo = LyricsRepository(embedded, sidecar)

        val lookup = runBlocking { repo.getLyricsLookup(song) }

        assertEquals(LyricsResult.NotFound, lookup.result)
        assertEquals(LyricsLookupStatus.ERROR, lookup.diagnostics.embeddedMetadata)
        assertEquals(LyricsLookupStatus.NOT_FOUND, lookup.diagnostics.sameFolderLrc)
        assertEquals(LyricsLookupStatus.NOT_FOUND, lookup.diagnostics.sameFolderTxt)
    }

    @Test
    fun `getLyricsLookup reports sidecar found diagnostics`() {
        val song = song(26)
        val embedded = FakeLyricsExtractor(default = LyricsResult.NotFound)
        val sidecar = FakeSidecarLyricsExtractor(
            responses = mapOf(
                song.id to sidecarLookup(
                    result = LyricsResult.Available("Sidecar lyric"),
                    sameFolderLrc = LyricsLookupStatus.FOUND,
                    folderPathUsed = "Music/Artist",
                    candidateFilenames = listOf("Song.lrc", "Song.txt"),
                ),
            ),
        )
        val repo = LyricsRepository(embedded, sidecar)

        val lookup = runBlocking { repo.getLyricsLookup(song) }

        assertEquals(LyricsResult.Available("Sidecar lyric"), lookup.result)
        assertEquals(LyricsLookupStatus.NOT_FOUND, lookup.diagnostics.embeddedMetadata)
        assertEquals(LyricsLookupStatus.FOUND, lookup.diagnostics.sameFolderLrc)
        assertEquals(LyricsLookupStatus.NOT_FOUND, lookup.diagnostics.sameFolderTxt)
        assertEquals("Music/Artist", lookup.diagnostics.folderPathUsed)
        assertEquals(listOf("Song.lrc", "Song.txt"), lookup.diagnostics.candidateFilenames)
    }

    @Test
    fun `getLyrics returns sidecar lrc lyrics when embedded lyrics are missing`() {
        val song = song(21)
        val embedded = FakeLyricsExtractor(default = LyricsResult.NotFound)
        val sidecar = FakeSidecarLyricsExtractor(
            responses = mapOf(
                song.id to sidecarLookup(
                    result = LyricsResult.Available("LRC sidecar lyric"),
                    sameFolderLrc = LyricsLookupStatus.FOUND,
                ),
            ),
        )
        val repo = LyricsRepository(embedded, sidecar)

        val result = runBlocking { repo.getLyrics(song) }

        assertEquals(LyricsResult.Available("LRC sidecar lyric"), result)
        assertEquals(1, sidecar.callCount)
    }

    @Test
    fun `getLyrics returns sidecar txt lyrics when embedded lyrics are missing`() {
        val song = song(22)
        val embedded = FakeLyricsExtractor(default = LyricsResult.NotFound)
        val sidecar = FakeSidecarLyricsExtractor(
            responses = mapOf(
                song.id to sidecarLookup(
                    result = LyricsResult.Available("TXT sidecar lyric"),
                    sameFolderTxt = LyricsLookupStatus.FOUND,
                ),
            ),
        )
        val repo = LyricsRepository(embedded, sidecar)

        val result = runBlocking { repo.getLyrics(song) }

        assertEquals(LyricsResult.Available("TXT sidecar lyric"), result)
    }

    @Test
    fun `getLyrics can fall back to sidecar after embedded error`() {
        val song = song(23)
        val embedded = FakeLyricsExtractor(default = LyricsResult.Error("Embedded failed"))
        val sidecar = FakeSidecarLyricsExtractor(
            responses = mapOf(
                song.id to sidecarLookup(
                    result = LyricsResult.Available("Recovered from sidecar"),
                    sameFolderLrc = LyricsLookupStatus.FOUND,
                ),
            ),
        )
        val repo = LyricsRepository(embedded, sidecar)

        val result = runBlocking { repo.getLyrics(song) }

        assertEquals(LyricsResult.Available("Recovered from sidecar"), result)
    }

    @Test
    fun `getLyrics calls extractor only once on repeated calls for same song`() {
        val song = song(3)
        val fake = FakeLyricsExtractor(default = LyricsResult.Available("Cached"))
        val sidecar = FakeSidecarLyricsExtractor(
            default = sidecarLookup(
                result = LyricsResult.Available("Sidecar"),
                sameFolderLrc = LyricsLookupStatus.FOUND,
            ),
        )
        val repo = LyricsRepository(fake, sidecar)

        runBlocking {
            repo.getLyrics(song)
            repo.getLyrics(song)
        }

        assertEquals(1, fake.callCount)
        assertEquals(0, sidecar.callCount)
    }

    @Test
    fun `getLyrics caches separately per song id`() {
        val s1 = song(10)
        val s2 = song(20)
        val fake = FakeLyricsExtractor(
            responses = mapOf(
                s1.uri to LyricsResult.Available("Song 10"),
                s2.uri to LyricsResult.Available("Song 20"),
            ),
        )
        val repo = LyricsRepository(fake, FakeSidecarLyricsExtractor())

        runBlocking {
            assertEquals(LyricsResult.Available("Song 10"), repo.getLyrics(s1))
            assertEquals(LyricsResult.Available("Song 20"), repo.getLyrics(s2))
            repo.getLyrics(s1)
            repo.getLyrics(s2)
        }

        assertEquals(2, fake.callCount)
    }

    // ── observeLyrics reactive Flow ───────────────────────────────────────────

    /**
     * When an override exists, [LyricsRepository.observeLyrics] must emit the override
     * text immediately without touching the file extractors.
     */
    @Test
    fun `observeLyrics emits override immediately when override exists`() {
        val song = song(300)
        val overrideDao = FakeLyricsOverrideDao(
            LyricsOverrideEntity(song.id, song.uri, "Custom override", 1L),
        )
        val embedded = FakeLyricsExtractor(default = LyricsResult.Available("Embedded"))
        val repo = LyricsRepository(embedded, FakeSidecarLyricsExtractor(),
            LyricsOverrideRepository(overrideDao))

        val result = runBlocking { repo.observeLyrics(song).take(1).toList().single() }

        assertEquals(LyricsResult.Available("Custom override"), result)
        assertEquals(0, embedded.callCount) // file extractors not consulted
    }

    /**
     * With no override the flow emits [LyricsResult.Loading] first, then the file result.
     */
    @Test
    fun `observeLyrics emits loading then file result when no override`() {
        val song = song(301)
        val repo = LyricsRepository(
            FakeLyricsExtractor(default = LyricsResult.Available("File lyrics")),
            FakeSidecarLyricsExtractor(),
            LyricsOverrideRepository(FakeLyricsOverrideDao()),
        )

        val results = runBlocking { repo.observeLyrics(song).take(2).toList() }

        assertEquals(LyricsResult.Loading, results[0])
        assertEquals(LyricsResult.Available("File lyrics"), results[1])
    }

    /**
     * With no override and no file lyrics, the flow emits Loading then NotFound.
     */
    @Test
    fun `observeLyrics emits loading then NotFound when no lyrics anywhere`() {
        val song = song(302)
        val repo = LyricsRepository(
            FakeLyricsExtractor(default = LyricsResult.NotFound),
            FakeSidecarLyricsExtractor(default = sidecarLookup()),
            LyricsOverrideRepository(FakeLyricsOverrideDao()),
        )

        val results = runBlocking { repo.observeLyrics(song).take(2).toList() }

        assertEquals(LyricsResult.Loading, results[0])
        assertEquals(LyricsResult.NotFound, results[1])
    }

    /**
     * After [LyricsRepository.saveCustomLyrics] is called, a fresh [observeLyrics]
     * observation returns the saved override — verifying the Room override propagates.
     */
    @Test
    fun `observeLyrics returns saved override on subsequent observation`() {
        val song = song(303)
        val repo = LyricsRepository(
            FakeLyricsExtractor(default = LyricsResult.NotFound),
            FakeSidecarLyricsExtractor(default = sidecarLookup()),
            LyricsOverrideRepository(FakeLyricsOverrideDao()),
        )

        runBlocking {
            // Initial observation: no override → file fallback
            val initial = repo.observeLyrics(song).take(2).toList().last()
            assertEquals(LyricsResult.NotFound, initial)

            // Save an override
            repo.saveCustomLyrics(song, "Saved lyrics")

            // Subsequent observation: override now present → emits immediately
            val afterSave = repo.observeLyrics(song).take(1).toList().single()
            assertEquals(LyricsResult.Available("Saved lyrics"), afterSave)
        }
    }

    /**
     * After [LyricsRepository.clearCustomLyrics] is called, a fresh [observeLyrics]
     * observation falls back to file lyrics — verifying the override removal propagates.
     */
    @Test
    fun `observeLyrics falls back to file lyrics after override is cleared`() {
        val song = song(304)
        val overrideDao = FakeLyricsOverrideDao(
            LyricsOverrideEntity(song.id, song.uri, "Custom", 1L),
        )
        val repo = LyricsRepository(
            FakeLyricsExtractor(default = LyricsResult.Available("Embedded fallback")),
            FakeSidecarLyricsExtractor(),
            LyricsOverrideRepository(overrideDao),
        )

        runBlocking {
            // Initial observation: override exists
            val withOverride = repo.observeLyrics(song).take(1).toList().single()
            assertEquals(LyricsResult.Available("Custom"), withOverride)

            // Clear the override
            repo.clearCustomLyrics(song)

            // Subsequent observation: no override → file lookup
            val results = repo.observeLyrics(song).take(2).toList()
            assertEquals(LyricsResult.Loading, results[0])
            assertEquals(LyricsResult.Available("Embedded fallback"), results[1])
        }
    }

    /**
     * The override always takes precedence over embedded lyrics.
     */
    @Test
    fun `observeLyrics override wins over embedded and sidecar`() {
        val song = song(305)
        val repo = LyricsRepository(
            FakeLyricsExtractor(default = LyricsResult.Available("Embedded")),
            FakeSidecarLyricsExtractor(
                default = sidecarLookup(
                    result = LyricsResult.Available("Sidecar"),
                    sameFolderLrc = LyricsLookupStatus.FOUND,
                ),
            ),
            LyricsOverrideRepository(FakeLyricsOverrideDao(
                LyricsOverrideEntity(song.id, song.uri, "Override wins", 1L),
            )),
        )

        val result = runBlocking { repo.observeLyrics(song).take(1).toList().single() }

        assertEquals(LyricsResult.Available("Override wins"), result)
    }

    @Test
    fun `getLyrics caches final result and does not retry`() {
        val song = song(5)
        val fake = FakeLyricsExtractor(default = LyricsResult.Error("File not found"))
        val sidecar = FakeSidecarLyricsExtractor(
            default = sidecarLookup(
                result = LyricsResult.Error("Sidecar failed"),
                sameFolderLrc = LyricsLookupStatus.ERROR,
            ),
        )
        val repo = LyricsRepository(fake, sidecar)

        val result = runBlocking {
            repo.getLyrics(song)
            repo.getLyrics(song)
            repo.getLyrics(song)
        }

        assertEquals(LyricsResult.Error("File not found; Sidecar failed"), result)
        assertEquals(1, fake.callCount)
        assertEquals(1, sidecar.callCount)
    }
}
