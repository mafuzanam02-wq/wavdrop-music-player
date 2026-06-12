package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongSyncPolicyTest {

    // -----------------------------------------------------------------------
    // shouldPreserveOnEmptyScan
    // -----------------------------------------------------------------------

    @Test
    fun `selected-folder mode with configured folders preserves on empty scan`() {
        val settings = LibraryScanSettings(
            scanMode           = LibraryScanMode.SELECTED_FOLDERS,
            selectedFolderUris = listOf("content://com.android.externalstorage.documents/tree/primary:Music"),
        )
        assertTrue(SongSyncPolicy.shouldPreserveOnEmptyScan(settings))
    }

    @Test
    fun `selected-folder mode with no configured folders does not preserve on empty scan`() {
        val settings = LibraryScanSettings(
            scanMode           = LibraryScanMode.SELECTED_FOLDERS,
            selectedFolderUris = emptyList(),
        )
        assertFalse(SongSyncPolicy.shouldPreserveOnEmptyScan(settings))
    }

    @Test
    fun `whole-device mode does not preserve on empty scan`() {
        val settings = LibraryScanSettings(
            scanMode           = LibraryScanMode.WHOLE_DEVICE,
            selectedFolderUris = listOf("content://anything"),
        )
        assertFalse(SongSyncPolicy.shouldPreserveOnEmptyScan(settings))
    }

    @Test
    fun `default settings do not preserve on empty scan`() {
        assertFalse(SongSyncPolicy.shouldPreserveOnEmptyScan(LibraryScanSettings()))
    }

    // -----------------------------------------------------------------------
    // computeStaleIds
    // -----------------------------------------------------------------------

    @Test
    fun `stale ids are current ids not present in active ids`() {
        val current = setOf(1L, 2L, 3L, 4L, 5L)
        val active  = setOf(1L, 3L, 5L)
        assertEquals(setOf(2L, 4L), SongSyncPolicy.computeStaleIds(current, active))
    }

    @Test
    fun `no stale ids when all current ids are active`() {
        val current = setOf(10L, 20L, 30L)
        val active  = setOf(10L, 20L, 30L, 40L)
        assertTrue(SongSyncPolicy.computeStaleIds(current, active).isEmpty())
    }

    @Test
    fun `all current ids are stale when active is empty`() {
        val current = setOf(1L, 2L, 3L)
        val active  = emptySet<Long>()
        assertEquals(current, SongSyncPolicy.computeStaleIds(current, active))
    }

    @Test
    fun `empty current and empty active produces empty stale set`() {
        assertTrue(SongSyncPolicy.computeStaleIds(emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun `handles large id sets beyond sqlite variable limit`() {
        val current = (1L..2000L).toSet()
        val active  = (1L..1500L).toSet()
        val stale   = SongSyncPolicy.computeStaleIds(current, active)
        assertEquals(500, stale.size)
        assertTrue(stale.all { it in 1501L..2000L })
    }

    @Test
    fun `chunking 500-element batches covers all stale ids`() {
        val staleIds = (1L..1200L).toList()
        val chunks   = staleIds.chunked(500)
        assertEquals(3, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(500, chunks[1].size)
        assertEquals(200, chunks[2].size)
        assertEquals(staleIds.toSet(), chunks.flatten().toSet())
    }
}
