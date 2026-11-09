package com.launchpoint.wavdrop.data.export

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePathResolverTest {
    private fun row(id: Long, data: String?, rel: String? = null, name: String? = null) = MediaPathRow(id, data)

    @Test fun `absolute data path is used verbatim including root-level files`() {
        assertEquals(
            "/storage/emulated/0/Music/a.mp3",
            MediaPathRules.pathFor(row(1, "/storage/emulated/0/Music/a.mp3", "Music/", "a.mp3")),
        )
        assertEquals(
            "/storage/emulated/0/Alexander William - Cuffley (Official Video) (1).mp3",
            MediaPathRules.pathFor(
                row(3, "/storage/emulated/0/Alexander William - Cuffley (Official Video) (1).mp3", "/", "x.mp3"),
            ),
        )
    }

    @Test fun `sd card data path is kept`() {
        assertEquals(
            "/storage/1A2B-3C4D/Music/b.mp3",
            MediaPathRules.pathFor(row(2, "/storage/1A2B-3C4D/Music/b.mp3", "Music/", "b.mp3")),
        )
    }

    @Test fun `null data is unresolved even when relative path and display name exist`() {
        assertNull(MediaPathRules.pathFor(row(4, null, "Music/", "x.mp3")))
        assertNull(MediaPathRules.pathFor(row(5, null, "/", "root.mp3")))
        assertNull(MediaPathRules.pathFor(row(6, "", "Music/", "x.mp3")))
        assertNull(MediaPathRules.pathFor(row(7, "   ", "Music/", "x.mp3")))
    }

    @Test fun `non-absolute and content references are never substituted for paths`() {
        assertNull(MediaPathRules.pathFor(row(8, "relative/x.mp3", "Music/", "x.mp3")))
        assertNull(MediaPathRules.pathFor(row(9, "content://media/external/audio/media/9", null, null)))
    }

    @Test fun `rows without data produce no map entry in a batch`() = runBlocking {
        val out = MediaPathRules.resolveInChunks(setOf(1L, 2L), 500) {
            listOf(row(1, "/a.mp3"), row(2, null, "Music/", "b.mp3"))
        }
        assertEquals(setOf(1L), out.keys)
    }

    @Test fun `large id sets are resolved in bounded batches not per song`() = runBlocking {
        val ids = (1L..2_300L).toSet()
        val calls = mutableListOf<Int>()
        val out = MediaPathRules.resolveInChunks(ids, chunkSize = 500) { chunk ->
            calls += chunk.size
            chunk.map { row(it, "/storage/emulated/0/Music/$it.mp3") }
        }
        assertEquals(listOf(500, 500, 500, 500, 300), calls)
        assertEquals(2_300, out.size)
        assertEquals("/storage/emulated/0/Music/7.mp3", out[7L])
    }

    @Test fun `stale ids simply have no entry`() = runBlocking {
        val out = MediaPathRules.resolveInChunks(setOf(1L, 2L), 500) { listOf(row(1, "/a.mp3")) }
        assertTrue(1L in out); assertTrue(2L !in out)
    }
}
