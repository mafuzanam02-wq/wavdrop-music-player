package com.launchpoint.wavdrop.data.legacy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BP-1: BlackPlayer .bpstat field 2 is a PERIOD play count, NOT a skip count. A .bpstat import must never change WavDrop's
 * skipCount, must not add the period count to the main play count, must write a skip baseline of 0 and must create no events.
 * The merge arithmetic the DAO performs is `MAX(current, imported)`, simulated here by [dao] so the repository-visible result
 * (counts after the import) can be asserted without a database.
 */
class BpstatMergePlanTest {

    private fun row(plays: Int, periodPlays: Int, lastPlayedMs: Long = 5_000L) = BlackPlayerStatImportRow(
        playCount = plays, periodPlayCount = periodPlays, title = "T", artist = "A", album = "B",
        filePath = "/storage/emulated/0/Music/t.mp3", dateAddedMs = 0L, lastPlayedMs = lastPlayedMs,
    )

    /** What `TrackStatsDao.mergeMaxStats` does to (playCount, skipCount) for this plan. */
    private fun dao(plan: BpstatMergePlan, playCount: Int, skipCount: Int) =
        maxOf(playCount, plan.importedPlayCount) to maxOf(skipCount, plan.importedSkipCount)

    private fun apply(currentPlays: Int, currentSkips: Int, row: BlackPlayerStatImportRow): Pair<Int, Int> =
        dao(planBpstatMerge(currentPlays, currentSkips, 0L, row), currentPlays, currentSkips)

    @Test fun theImportRaisesPlayCountThroughMaxAndLeavesAnExistingSkipCountUnchanged() {
        // local play 20 / skip 7; bpstat play 50 / period 12 -> play 50, skip 7 (NOT 12)
        assertEquals(50 to 7, apply(20, 7, row(plays = 50, periodPlays = 12)))
    }

    @Test fun aZeroSkipCountStaysZeroWhateverFieldTwoSays() {
        assertEquals(10 to 0, apply(0, 0, row(plays = 10, periodPlays = 999)))
        assertEquals(0 to 0, apply(0, 0, row(plays = 0, periodPlays = 5_000)))
    }

    @Test fun theImportedSkipValueIsAlwaysZeroSoMostSkippedCannotBeAffected() {
        for (period in listOf(0, 1, 25, 10_000_000)) {
            assertEquals(0, planBpstatMerge(5, 3, 0L, row(plays = 9, periodPlays = period)).importedSkipCount)
        }
        // before == after for any local skip count (Most Skipped orders by TrackStats.skipCount)
        for (local in listOf(0, 1, 7, 400)) assertEquals(local, apply(10, local, row(plays = 99, periodPlays = 77)).second)
    }

    @Test fun periodPlayCountIsNeverAddedToThePlayCount() {
        val plan = planBpstatMerge(20, 0, 0L, row(plays = 50, periodPlays = 12))
        assertEquals(50, plan.importedPlayCount)
        assertEquals(50 to 0, apply(20, 0, row(plays = 50, periodPlays = 12)))
        assertEquals("a lower main count never lowers local plays and the period count is not substituted", 20 to 0, apply(20, 0, row(plays = 5, periodPlays = 400)))
    }

    @Test fun repeatingTheSameImportDoesNotAlterSkipsOrPlays() {
        val r = row(plays = 100, periodPlays = 25)
        val first = apply(40, 3, r)
        val second = apply(first.first, first.second, r)
        assertEquals(first, second)
        assertEquals(0L, planBpstatMerge(first.first, first.second, 0L, r).effect.playDelta)
        assertFalse(planBpstatMerge(first.first, first.second, 0L, r).effect.anyUpdated)
    }

    @Test fun anOlderAlreadyHighSkipCountIsNotDecrementedOrReset() {
        // an older version imported field 2 (25) as skips: local is play 100 / skip 25. Re-importing the same file changes nothing.
        val r = row(plays = 100, periodPlays = 25)
        assertEquals(100 to 25, apply(100, 25, r))
        assertEquals(100 to 25, apply(100, 25, r))
        val plan = planBpstatMerge(100, 25, 0L, r)
        assertEquals(0L, plan.effect.skipDelta)
        assertTrue(plan.importedSkipCount < 25)
    }

    @Test fun theEffectReportsPlayDeltaOnlyAndNeverASkipDeltaOrListeningTime() {
        val plan = planBpstatMerge(20, 7, 4_000L, row(plays = 50, periodPlays = 12))
        assertEquals(30L, plan.effect.playDelta)
        assertEquals(0L, plan.effect.skipDelta)
        assertEquals(0L, plan.effect.listeningTimeDelta)
        assertEquals(0L, plan.importedListeningTimeMs)
        assertEquals(0L, plan.importedLastListenedAt)
    }

    @Test fun theBaselineRecordsThePlayCountAndASkipBaselineOfZero() {
        val plan = planBpstatMerge(0, 0, 0L, row(plays = 148, periodPlays = 2))
        assertEquals(148, plan.baselinePlayCount)
        assertEquals("the period count is never stored as a skip baseline", 0, plan.baselineSkipCount)
    }

    // ── source guards: no events, no skip claims in the apply path or the UI ─────────────────────────────────────────────

    private fun read(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText().replace("\r\n", "\n")

    @Test fun theApplyPathWritesNoListenEventsAndUsesThePlanForSkipsAndBaselines() {
        val body = read("data/repository/StatsRepository.kt").substringAfter("suspend fun applyBpstatImport").substringBefore("// ── Mapping")
            .lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        listOf("insertEvent", "ListenEvent", "recordEvent", "TrackListenEvent").forEach { assertFalse("apply path must not write events: $it", body.contains(it)) }
        assertTrue(body.contains("importedSkipCount       = plan.importedSkipCount"))
        assertTrue(body.contains("lastImportedSkipCount = plan.baselineSkipCount"))
        assertFalse("no skip figure is accumulated or reported", body.contains("skipsImported") || body.contains("row.skipCount"))
    }

    @Test fun thePreviewAndAppliedUiNoLongerClaimBlackPlayerSuppliedSkips() {
        val screen = read("ui/screen/bpstatpreview/BpstatPreviewScreen.kt")
        listOf("Skips updated", "Total skip count", "Matched file skips", "skipsImported", "totalSkipCount", ".skipCount").forEach {
            assertFalse("BpstatPreviewScreen still contains `$it`", screen.contains(it))
        }
        assertTrue(screen.contains("Total period plays (not imported)"))
        assertTrue(screen.contains("Matched file period plays (not imported)"))
        assertFalse(read("ui/screen/settings/SettingsBackupScreen.kt").contains("import play and skip counts"))
        assertFalse(read("ui/screen/settings/SettingsAboutScreen.kt").contains("play and skip counts"))
    }

    @Test fun noProductionBlackPlayerCodeStillTreatsFieldTwoAsSkips() {
        for (name in listOf("BlackPlayerStatImportRow.kt", "BlackPlayerImportResult.kt", "BpstatApplyResult.kt", "BpstatMatchResult.kt", "BpstatMatcher.kt")) {
            val code = read("data/legacy/$name").lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse("$name must not model field 2 as skips", code.contains("skipCount") || code.contains("totalSkipCount") || code.contains("skipsImported"))
        }
        val parser = read("data/legacy/BlackPlayerStatParser.kt").lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertFalse(parser.contains("isPlausibleSkipCount"))
        assertTrue(parser.contains("isPlausiblePlayCount(periodPlayCount)"))
    }
}
