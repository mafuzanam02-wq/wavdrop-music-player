package com.launchpoint.wavdrop.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-UI-14: the canonical Media3 item publishes the song's album-art REFERENCE (never decoded pixels) next to the unchanged title,
 * artist, album, album-id extra and media id, on every path that creates items: normal playback, browse, Full resumption and the
 * bounded resumption query. Robolectric supplies a real `Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LockScreenArtworkMetadataTest {

    private fun song(id: Long, albumId: Long = id, title: String = "Title $id", artist: String = "Artist $id", album: String = "Album $id") = Song(
        id = id, title = title, artist = artist, album = album, albumId = albumId, duration = 200_000L,
        uri = "content://media/external/audio/media/$id", dateAdded = 0L, trackNumber = 1, year = 2020,
    )

    private fun assertMetadataPreserved(song: Song, item: MediaItem) {
        assertEquals(song.id.toString(), item.mediaId)
        assertEquals(song.uri, item.localConfiguration?.uri?.toString())
        assertEquals(song.displayTitle, item.mediaMetadata.title.toString())
        assertEquals(song.displayArtist, item.mediaMetadata.artist.toString())
        assertEquals(song.album, item.mediaMetadata.albumTitle.toString())
        assertEquals(song.albumId, item.mediaMetadata.extras?.getLong(WAVDROP_ALBUM_ID_EXTRA))
    }

    // ── a track with valid artwork / without artwork ─────────────────────────────────────────────────────────────────────

    @Test fun aTrackWithAnAlbumIdPublishesTheAlbumArtReferenceAndKeepsEverythingElse() {
        val s = song(7, albumId = 42)
        val item = s.toPlaybackMediaItem()
        assertEquals(Uri.parse("content://media/external/audio/albumart/42"), item.mediaMetadata.artworkUri)
        assertNull("only a reference is published; nothing is decoded here", item.mediaMetadata.artworkData)
        assertMetadataPreserved(s, item)
    }

    @Test fun aTrackWithoutAnAlbumIdPublishesNoArtworkAndStillPlaysWithFullMetadata() {
        listOf(0L, -1L).forEach { albumId ->
            val s = song(8, albumId = albumId)
            val item = s.toPlaybackMediaItem()
            assertNull("no artwork reference for albumId=$albumId", item.mediaMetadata.artworkUri)
            assertNull(item.mediaMetadata.artworkData)
            assertMetadataPreserved(s, item)
        }
    }

    @Test fun theArtworkReferenceIsTheSameOneTheInAppScreensUse() {
        val s = song(9, albumId = 1234)
        assertEquals(
            com.launchpoint.wavdrop.data.artwork.ArtworkResolver.albumArtworkUri(1234L),
            s.toPlaybackMediaItem().mediaMetadata.artworkUri.toString(),
        )
        assertEquals(s.artworkUriOrNull(), s.toPlaybackMediaItem().mediaMetadata.artworkUri)
    }

    // ── queue identity is untouched ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun songsSharingAnAlbumShareTheArtworkButNeverTheMediaId() {
        val a = song(1, albumId = 5).toPlaybackMediaItem()
        val b = song(2, albumId = 5).toPlaybackMediaItem()
        assertEquals(a.mediaMetadata.artworkUri, b.mediaMetadata.artworkUri)
        assertEquals("1", a.mediaId)
        assertEquals("2", b.mediaId)
    }

    @Test fun theSameSongAtTwoQueuePositionsBuildsEqualItemsSoOccurrenceIdentityStaysPositional() {
        val s = song(3, albumId = 9)
        assertEquals(s.toPlaybackMediaItem().mediaId, s.toPlaybackMediaItem().mediaId)
        assertEquals(s.toPlaybackMediaItem().mediaMetadata, s.toPlaybackMediaItem().mediaMetadata)
    }

    @Test fun theArtworkReferenceSurvivesTheBundleRoundTripControllersReceive() {
        val s = song(4, albumId = 77)
        // Sessions send controllers the local-configuration form (toBundle() alone deliberately omits the playback URI).
        val restored = MediaItem.fromBundle(s.toPlaybackMediaItem().toBundleIncludeLocalConfiguration())
        assertEquals(Uri.parse("content://media/external/audio/albumart/77"), restored.mediaMetadata.artworkUri)
        assertMetadataPreserved(s, restored)
    }

    // ── browse / resumption paths ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun browseItemsKeepTheArtworkAndTheirPlayableFlags() {
        val s = song(5, albumId = 21)
        val item = WavdropMediaLibrary.songItem(s)
        assertEquals(Uri.parse("content://media/external/audio/albumart/21"), item.mediaMetadata.artworkUri)
        assertMetadataPreserved(s, item)
        assertTrue(item.mediaMetadata.isPlayable == true)
    }

    private fun snapshot(ids: List<Long>, current: Int) = PlaybackSessionSnapshot(
        queueSongIds = ids, playbackOrder = null, currentSongId = ids[current], currentIndex = current,
        positionMs = 12_000L, repeatMode = RepeatMode.OFF, shuffleEnabled = false, updatedAtMs = 0L,
    )

    private val library = listOf(song(1, albumId = 11), song(2, albumId = 0), song(3, albumId = 33))

    @Test fun fullResumptionRestoresItemsWithTheSameMetadataAndArtworkAsNormalPlayback() {
        val result = PlaybackResumptionMapper.map(snapshot(listOf(1, 2, 3), current = 2), ResumeBehaviorSettings(), library)
        val plan = (result as PlaybackResumptionResult.Ready).plan
        val media3 = plan.toMedia3Resumption()
        assertEquals(3, media3.mediaItems.size)
        assertEquals(2, media3.startIndex)
        assertEquals(12_000L, media3.startPositionMs)
        media3.mediaItems.forEachIndexed { i, item ->
            assertMetadataPreserved(library[i], item)
            assertEquals(library[i].toPlaybackMediaItem().mediaMetadata, item.mediaMetadata)
        }
        assertNotNull(media3.mediaItems[0].mediaMetadata.artworkUri)
        assertNull("a song without an album id restores without artwork", media3.mediaItems[1].mediaMetadata.artworkUri)
    }

    @Test fun theBoundedResumptionQueryReturnsOnlyTheCurrentItemWithItsArtwork() {
        val result = PlaybackResumptionMapper.map(
            snapshot(listOf(1, 2, 3), current = 2), ResumeBehaviorSettings(), library, boundedModeFor = { true },
        )
        val plan = (result as PlaybackResumptionResult.Ready).plan
        val query = plan.toQueryResult()
        assertEquals(1, query.mediaItems.size)
        assertMetadataPreserved(library[2], query.mediaItems.single())
        assertEquals(Uri.parse("content://media/external/audio/albumart/33"), query.mediaItems.single().mediaMetadata.artworkUri)
    }

    @Test fun theRecentBrowseItemCarriesTheResumableSongsArtwork() {
        val recent = WavdropMediaLibrary.recentChildren(snapshot(listOf(1, 2, 3), current = 0), ResumeBehaviorSettings(), library)
        assertEquals(1, recent.size)
        assertEquals(Uri.parse("content://media/external/audio/albumart/11"), recent.single().mediaMetadata.artworkUri)
    }
}
