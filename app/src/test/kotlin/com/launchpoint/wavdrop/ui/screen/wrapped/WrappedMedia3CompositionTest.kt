package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P5-WR-VID-02B: the real Media3 1.11.1 composition built from a request (no codec involved). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WrappedMedia3CompositionTest {

    private fun request(durations: List<Int>) = WrappedVideoRequest(
        durations.mapIndexed { i, d ->
            val name = "slide_%02d_x.png".format(i + 1)
            WrappedVideoSlide(i, File("/tmp/wavdrop_wrapped_export_t/$name"), name, d)
        },
        frameRate = 30,
    )

    @Test fun `composition is one video sequence of image items in slide order`() {
        val c = buildWrappedVideoComposition(request(listOf(3_000, 3_000, 3_000, 3_000)))
        assertEquals(1, c.sequences.size)
        val items = c.sequences[0].editedMediaItems
        assertEquals(4, items.size)
        assertEquals(
            listOf("slide_01_x.png", "slide_02_x.png", "slide_03_x.png", "slide_04_x.png"),
            // Host paths differ per OS (Robolectric on Windows renders C:\tmp\...), so compare the file name only.
            items.map { Regex("slide_\\d\\d_x\\.png").find(it.mediaItem.localConfiguration!!.uri.toString())!!.value },
        )
    }

    @Test fun `every image item carries its duration and the 30 fps frame rate`() {
        val c = buildWrappedVideoComposition(request(listOf(3_000, 2_000, 4_500)))
        val items = c.sequences[0].editedMediaItems
        assertEquals(listOf(3_000_000L, 2_000_000L, 4_500_000L), items.map { it.durationUs })
        assertTrue(items.all { it.frameRate == 30 })
        // The 1.11.1 asset loader reads the still image's duration from the MediaItem itself.
        assertEquals(listOf(3_000L, 2_000L, 4_500L), items.map { it.mediaItem.localConfiguration!!.imageDurationMs })
        assertTrue("no audio is requested or forced", c.sequences[0].let { !it.forceAudioTrack })
    }
}
