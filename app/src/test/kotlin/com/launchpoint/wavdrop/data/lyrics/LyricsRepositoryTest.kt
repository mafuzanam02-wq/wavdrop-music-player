package com.launchpoint.wavdrop.data.lyrics

import com.launchpoint.wavdrop.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
