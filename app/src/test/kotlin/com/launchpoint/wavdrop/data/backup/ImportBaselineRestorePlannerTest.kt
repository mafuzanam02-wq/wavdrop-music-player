package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportBaselineRestorePlannerTest {

    private fun backupBaseline(
        songId: Long,
        sourceKey: String = "bpstat-2026-01",
        playCount: Int = 50,
        skipCount: Int = 5,
        importedAt: Long = 1_000L,
    ) = BackupImportBaseline(
        songId                = songId,
        sourceType            = "blackplayer_bpstat",
        sourceKey             = sourceKey,
        lastImportedPlayCount = playCount,
        lastImportedSkipCount = skipCount,
        lastImportedAt        = importedAt,
    )

    @Test
    fun `baseline is re-keyed to the resolved current song id`() {
        val plan = ImportBaselineRestorePlanner.plan(
            baselines     = listOf(backupBaseline(songId = 1L)),
            resolveSongId = { 42L },
            existing      = emptyList(),
        )
        assertEquals(1, plan.restored)
        val entity = plan.toUpsert.single()
        assertEquals(42L, entity.songId)
        assertEquals("blackplayer_bpstat", entity.sourceType)
        assertEquals("bpstat-2026-01", entity.sourceKey)
        assertEquals(50, entity.lastImportedPlayCount)
        assertEquals(5, entity.lastImportedSkipCount)
        assertEquals(1_000L, entity.lastImportedAt)
    }

    @Test
    fun `unmatched baseline is counted, not silently dropped`() {
        val plan = ImportBaselineRestorePlanner.plan(
            baselines     = listOf(backupBaseline(songId = 1L)),
            resolveSongId = { null },
            existing      = emptyList(),
        )
        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedUnmatched)
    }

    @Test
    fun `restore is idempotent - second restore upserts nothing`() {
        val baselines = listOf(backupBaseline(songId = 1L))

        val first = ImportBaselineRestorePlanner.plan(baselines, { 42L }, emptyList())
        assertEquals(1, first.restored)

        val second = ImportBaselineRestorePlanner.plan(baselines, { 42L }, first.toUpsert)
        assertEquals(0, second.restored)
        assertEquals(1, second.skippedExistingNewer)
    }

    @Test
    fun `newer local baseline is never regressed by an older backup`() {
        val local = ImportBaselineEntity(
            songId                = 42L,
            sourceType            = "blackplayer_bpstat",
            sourceKey             = "bpstat-2026-01",
            lastImportedPlayCount = 80,
            lastImportedSkipCount = 9,
            lastImportedAt        = 9_999L, // imported after the backup was taken
        )
        val plan = ImportBaselineRestorePlanner.plan(
            baselines     = listOf(backupBaseline(songId = 1L, importedAt = 1_000L)),
            resolveSongId = { 42L },
            existing      = listOf(local),
        )
        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedExistingNewer)
    }

    @Test
    fun `backup baseline newer than local one replaces it`() {
        val local = ImportBaselineEntity(
            songId                = 42L,
            sourceType            = "blackplayer_bpstat",
            sourceKey             = "bpstat-2026-01",
            lastImportedPlayCount = 10,
            lastImportedSkipCount = 1,
            lastImportedAt        = 500L,
        )
        val plan = ImportBaselineRestorePlanner.plan(
            baselines     = listOf(backupBaseline(songId = 1L, importedAt = 1_000L)),
            resolveSongId = { 42L },
            existing      = listOf(local),
        )
        assertEquals(1, plan.restored)
        assertEquals(50, plan.toUpsert.single().lastImportedPlayCount)
    }

    @Test
    fun `same song with two source keys restores both baselines`() {
        val plan = ImportBaselineRestorePlanner.plan(
            baselines = listOf(
                backupBaseline(songId = 1L, sourceKey = "bpstat-2026-01"),
                backupBaseline(songId = 1L, sourceKey = "bpstat-2026-03"),
            ),
            resolveSongId = { 42L },
            existing      = emptyList(),
        )
        assertEquals(2, plan.restored)
    }

    /**
     * Generation survival: baselines exported on device A, restored on device B
     * (re-keyed), then exported again from device B — the count must be stable.
     * Export is a straight table dump, so surviving the restore IS surviving the
     * next backup.
     */
    @Test
    fun `baselines survive backup - restore - backup with stable count`() {
        val gen1 = listOf(
            backupBaseline(songId = 1L, sourceKey = "k1"),
            backupBaseline(songId = 2L, sourceKey = "k2"),
            backupBaseline(songId = 3L, sourceKey = "k3"),
        )
        val newIds = mapOf(1L to 11L, 2L to 22L, 3L to 33L)

        val restore = ImportBaselineRestorePlanner.plan(gen1, { newIds[it] }, emptyList())
        assertEquals(3, restore.restored)

        // Device B's next backup exports its import_baselines table directly.
        val gen2 = restore.toUpsert.map { e ->
            BackupImportBaseline(
                songId                = e.songId,
                sourceType            = e.sourceType,
                sourceKey             = e.sourceKey,
                lastImportedPlayCount = e.lastImportedPlayCount,
                lastImportedSkipCount = e.lastImportedSkipCount,
                lastImportedAt        = e.lastImportedAt,
            )
        }
        assertEquals(gen1.size, gen2.size)
        assertEquals(
            gen1.map { it.sourceKey }.sorted(),
            gen2.map { it.sourceKey }.sorted(),
        )
    }
}
