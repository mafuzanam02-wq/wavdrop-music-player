package com.launchpoint.wavdrop.ui.components

import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.ui.screen.nowplaying.nowPlayingArtworkUri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-07: pure tests for the shared artwork seams — ownership/retention reducer for the large Now Playing surface, target sizing,
 * the retry policy, the appearance-mode rule and the architecture guards. Coil internals are deliberately not asserted.
 */
class ArtworkRenderDecisionTest {

    private val target = ArtworkTarget(1080, 1080)
    private fun key(uri: String, t: ArtworkTarget? = target) = ArtworkRequestKey(uri, t)
    private val a = key("content://media/external/audio/albumart/1")
    private val b = key("content://media/external/audio/albumart/2")
    private val c = key("content://media/external/audio/albumart/3")

    private fun reduce(s: ArtworkSurfaceState<String>, e: ArtworkEvent<String>, retain: Boolean = true) = ArtworkSurface.reduce(s, e, retain)
    private val idle: ArtworkSurfaceState<String> = ArtworkSurfaceState.Idle

    private fun showing(k: ArtworkRequestKey, art: String): ArtworkSurfaceState<String> =
        reduce(reduce(idle, ArtworkEvent.Requested(k)), ArtworkEvent.Succeeded(k, art))

    // ── states / contract A–D ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun noUriIsAPlaceholderAndClearsAnyRetainedArt() {
        assertNull(ArtworkSurface.displayed(idle))
        val s = reduce(showing(a, "A"), ArtworkEvent.NoArtwork)
        assertEquals(ArtworkSurfaceState.Idle, s)
        assertNull(ArtworkSurface.displayed(s))
        // a later load must not resurrect the old cover
        assertNull(ArtworkSurface.displayed(reduce(s, ArtworkEvent.Requested(b))))
    }

    @Test fun loadingWithNoPreviousShowsThePlaceholder() {
        assertNull(ArtworkSurface.displayed(reduce(idle, ArtworkEvent.Requested(a))))
    }

    @Test fun loadingWithAPreviousSuccessRetainsItOnTheLargeSurface() {
        val s = reduce(showing(a, "A"), ArtworkEvent.Requested(b))
        assertEquals("A", ArtworkSurface.displayed(s))
        assertEquals(ArtworkSurfaceState.Loading(b, Retained(a, "A")).key, (s as ArtworkSurfaceState.Loading).key)
    }

    @Test fun retentionOffNeverKeepsThePreviousCover() { // list-style surfaces
        assertNull(ArtworkSurface.displayed(reduce(showing(a, "A"), ArtworkEvent.Requested(b), retain = false)))
    }

    @Test fun successShowsTheNewImageAndBecomesTheRetainedOne() {
        val s = reduce(reduce(showing(a, "A"), ArtworkEvent.Requested(b)), ArtworkEvent.Succeeded(b, "B"))
        assertEquals("B", ArtworkSurface.displayed(s))
        assertEquals("B", ArtworkSurface.displayed(reduce(s, ArtworkEvent.Requested(c)))) // B is what gets retained next
    }

    @Test fun aDefinitiveFailureShowsThePlaceholderAndDropsThePreviousCover() {
        val s = reduce(reduce(showing(a, "A"), ArtworkEvent.Requested(b)), ArtworkEvent.Failed(b))
        assertTrue(s is ArtworkSurfaceState.Failed)
        assertNull("the previous track's cover must not masquerade as the current one", ArtworkSurface.displayed(s))
        assertNull(ArtworkSurface.displayed(reduce(s, ArtworkEvent.Requested(c)))) // and is not resurrected by the next request
    }

    // ── stale-completion protection ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun aLateSuccessForASupersededRequestIsIgnored() {
        // A showing; B requested; C requested (B superseded); B succeeds late; C succeeds
        var s = showing(a, "A")
        s = reduce(s, ArtworkEvent.Requested(b))
        s = reduce(s, ArtworkEvent.Requested(c))
        s = reduce(s, ArtworkEvent.Succeeded(b, "B")) // stale
        assertTrue(s is ArtworkSurfaceState.Loading && s.key == c)
        assertEquals("A", ArtworkSurface.displayed(s)) // still the retained previous, never B
        s = reduce(s, ArtworkEvent.Succeeded(c, "C"))
        assertEquals("C", ArtworkSurface.displayed(s))
        s = reduce(s, ArtworkEvent.Succeeded(b, "B")) // stale again, after C finished
        assertEquals("C", ArtworkSurface.displayed(s))
    }

    @Test fun aLateFailureForASupersededRequestCannotCorruptTheCurrentOne() {
        var s = showing(a, "A")
        s = reduce(s, ArtworkEvent.Requested(b))
        s = reduce(s, ArtworkEvent.Requested(c))
        s = reduce(s, ArtworkEvent.Failed(b)) // stale
        assertTrue(s is ArtworkSurfaceState.Loading && s.key == c)
        s = reduce(s, ArtworkEvent.Succeeded(c, "C"))
        s = reduce(s, ArtworkEvent.Failed(b)) // stale after success
        assertEquals("C", ArtworkSurface.displayed(s))
    }

    @Test fun aStaleResultAfterTheTrackLostItsArtworkIsIgnored() {
        var s = reduce(showing(a, "A"), ArtworkEvent.Requested(b))
        s = reduce(s, ArtworkEvent.NoArtwork)
        s = reduce(s, ArtworkEvent.Succeeded(b, "B"))
        assertEquals(ArtworkSurfaceState.Idle, s)
    }

    // ── same album / same URI ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anIdenticalRequestKeyIsANoOpSoSameAlbumTracksNeitherReloadNorFlash() {
        val s = showing(a, "A")
        assertEquals(s, reduce(s, ArtworkEvent.Requested(key(a.uri)))) // second song, same album: same URI + size
        val loading = reduce(showing(a, "A"), ArtworkEvent.Requested(b))
        assertEquals(loading, reduce(loading, ArtworkEvent.Requested(b)))
    }

    @Test fun aSizeChangeOfTheSameUriIsANewRequestThatKeepsTheCurrentCoverUntilReady() {
        val big = key(a.uri, ArtworkTarget(1600, 1600))
        var s = reduce(showing(a, "A"), ArtworkEvent.Requested(big))
        assertEquals("A", ArtworkSurface.displayed(s))
        s = reduce(s, ArtworkEvent.Succeeded(big, "A-big"))
        assertEquals("A-big", ArtworkSurface.displayed(s))
    }

    // ── sizing ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun thumbnailTargetsAreBoundedToTheSurfaceAndConvertDensityCorrectly() {
        assertEquals(ArtworkTarget(132, 132), ArtworkSizing.thumbnailTarget(44f, 3f)) // 44dp @3x
        assertEquals(ArtworkTarget(144, 144), ArtworkSizing.thumbnailTarget(48f, 3f))
        assertEquals(ArtworkTarget(110, 110), ArtworkSizing.thumbnailTarget(44f, 2.5f))
        assertEquals(ArtworkTarget(23, 23), ArtworkSizing.thumbnailTarget(22.4f, 1f)) // rounds up, never under-decodes
        assertEquals(ArtworkTarget(1, 1), ArtworkSizing.thumbnailTarget(0.1f, 1f))
        assertTrue("a 44dp row never asks for anything near full resolution", ArtworkSizing.thumbnailTarget(44f, 4f)!!.widthPx <= 176)
    }

    @Test fun invalidSurfaceSizesCreateNoRequestTarget() {
        assertNull(ArtworkSizing.thumbnailTarget(0f, 3f))
        assertNull(ArtworkSizing.thumbnailTarget(-4f, 3f))
        assertNull(ArtworkSizing.thumbnailTarget(44f, 0f))
        assertNull(ArtworkSizing.thumbnailTarget(Float.NaN, 3f))
        assertNull(ArtworkSizing.largeTarget(0, 800))
        assertNull(ArtworkSizing.largeTarget(800, 0))
        assertNull(ArtworkSizing.largeTarget(-1, -1))
    }

    @Test fun theLargeTargetUsesTheMeasuredSizeBucketedUpAndCapped() {
        val t = ArtworkSizing.largeTarget(1080, 1500)!!
        assertEquals(1088, t.widthPx)
        assertEquals(1536, t.heightPx)
        assertTrue(t.widthPx >= 1080 && t.heightPx >= 1500) // never decodes smaller than the surface
        val huge = ArtworkSizing.largeTarget(5000, 4000)!!
        assertEquals(ArtworkSizing.LARGE_MAX_PX, huge.widthPx)
        assertEquals(ArtworkSizing.LARGE_MAX_PX, huge.heightPx)
    }

    @Test fun layoutJitterWithinABucketKeepsTheSameRequestAndARealChangeMakesANewOne() {
        assertEquals(ArtworkSizing.largeTarget(1070, 1500), ArtworkSizing.largeTarget(1080, 1490)) // same buckets
        assertFalse(ArtworkSizing.largeTarget(1080, 1500) == ArtworkSizing.largeTarget(1600, 900)) // rotation
        assertEquals(key(a.uri, ArtworkSizing.largeTarget(1080, 1500)), key(a.uri, ArtworkSizing.largeTarget(1075, 1495))) // equal keys => no reload
    }

    // ── retry / appearance mode ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theLargeSurfaceRetriesAtMostOnceBeforeADefinitiveFailure() {
        assertTrue(ArtworkRetryPolicy.shouldRetry(1))
        assertFalse(ArtworkRetryPolicy.shouldRetry(ArtworkRetryPolicy.MAX_ATTEMPTS))
        assertEquals(2, ArtworkRetryPolicy.MAX_ATTEMPTS)
    }

    @Test fun onlyTheArtworkAppearanceModeRequestsArtworkAndNeedsAValidAlbumId() {
        assertEquals("content://media/external/audio/albumart/7", nowPlayingArtworkUri(NowPlayingBackground.ARTWORK, 7L))
        assertNull(nowPlayingArtworkUri(NowPlayingBackground.ARTWORK, 0L))
        assertNull(nowPlayingArtworkUri(NowPlayingBackground.ARTWORK, null))
        NowPlayingBackground.values().filter { it != NowPlayingBackground.ARTWORK }.forEach {
            assertNull("$it intentionally shows no artwork", nowPlayingArtworkUri(it, 7L))
        }
    }

    // ── architecture guards ──────────────────────────────────────────────────────────────────────────────────────────────

    private val root = File("src/main/kotlin/com/launchpoint/wavdrop")
    private fun code(f: File) = f.readText().lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }.joinToString("\n")

    @Test fun theSharedArtworkComponentNoLongerSubcomposesOrUsesBoxWithConstraints() {
        val src = code(File(root, "ui/components/ArtworkImage.kt"))
        assertFalse(src.contains("SubcomposeAsyncImage"))
        assertFalse(src.contains("BoxWithConstraints"))
        assertTrue(src.contains("AsyncImage("))
        assertTrue("requests come from the shared factory", src.contains("ArtworkRequestFactory.build("))
    }

    @Test fun noProductionCodeUsesSubcomposeAsyncImage() {
        val users = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { code(it).contains("SubcomposeAsyncImage") }.map { it.name }.toList()
        assertTrue("SubcomposeAsyncImage users: $users", users.isEmpty())
    }

    @Test fun everyFixedSizeArtworkCallSiteDeclaresItsRequestSize() {
        // Every ArtworkImage( ... ) call whose modifier is a fixed .size(X) also passes artworkSize.
        val missing = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ArtworkImage.kt" }.forEach { f ->
            val text = f.readText().replace("\r\n", "\n")
            Regex("""ArtworkImage\(\n(.*?)\n[ \t]*\)""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
                val body = m.groupValues[1]
                if (Regex("""\.size\(""").containsMatchIn(body) && !body.contains("artworkSize =") && !body.contains("retainPreviousOnLoad")) missing += f.name
            }
        }
        assertTrue("ArtworkImage call sites with a fixed size but no artworkSize: $missing", missing.isEmpty())
        assertNotNull(root)
    }

    @Test fun nowPlayingUsesTheRetainedRequestKeyedPathAndTheAppearanceRule() {
        val np = code(File(root, "ui/screen/nowplaying/NowPlayingScreen.kt"))
        assertTrue(np.contains("nowPlayingArtworkUri(npBackground, song.albumId)"))
        assertTrue(np.contains("retainPreviousOnLoad = true"))
    }
}
