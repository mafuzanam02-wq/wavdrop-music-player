package com.launchpoint.wavdrop.ui.screen.wrapped

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-WR-VID-02C on a real device: the PRODUCTION wiring (screen-scoped controller, hidden render host, coordinator,
 * real Slide export + real Media3 encode) driven without any mock, plus the FileProvider security and a real
 * out-of-process receiver. Fixtures are synthetic; every file this test creates carries the `vid02c-` export id and is
 * removed by id, so existing app data is never touched.
 */
@RunWith(AndroidJUnit4::class)
class WrappedVideoExportFlowInstrumentedTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val videoDir get() = wrappedVideoDir(ctx.cacheDir)
    private val slideBase get() = wrappedExportBaseDir(ctx.cacheDir)
    private val evidence get() = File(ctx.getExternalFilesDir(null)!!, "vid02c").also { it.mkdirs() }

    private val show = mutableStateOf(true)
    private var controller: WrappedVideoExportController? = null
    private val ids = ArrayList<String>()

    @Before fun mount() {
        removeOwnFiles()
        rule.setContent {
            WavdropTheme(themeMode = ThemeMode.DARK) {
                if (show.value) {
                    val c = rememberWrappedVideoExportController()
                    SideEffect { controller = c }
                    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.size(100.dp).testTag("probe"))
                        WrappedVideoExportHostMount(c)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @After fun cleanup() = removeOwnFiles()

    private fun removeOwnFiles() {
        videoDir.listFiles()?.filter { f -> f.name.contains("vid02c-") }?.forEach { it.delete() }
        slideBase.listFiles()?.filter { it.name.contains("vid02c-") }?.forEach { it.deleteRecursively() }
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private val settings = WrappedVisualSettings(false, WrappedBackgroundIntensity.MEDIUM, WrappedFallbackTheme.AUTO)
    private fun song(id: Long, title: String, artist: String, album: String, albumId: Long) =
        Song(id, title, artist, album, albumId, 200_000L, "content://x/$id", 0L, 1, 2024)
    private fun stat(s: Song, plays: Int, skips: Int = 0) = SongStatsSummary(s, plays, skips, 1_700_000_000_000L, plays * 200_000L)
    private val songs = listOf(
        song(1, "Neon Skyline", "The Midnight Echoes", "City Lights", 7),
        song(2, "Paper Boats", "Harbour & Hale", "Low Tide", 8),
        song(3, "Quiet Rooms", "Soft Machines", "Rooms", 10),
    )

    private fun summary(period: WrappedPeriod) = WrappedSummary(
        period = period,
        totalPlayCount = 1234, totalSkipCount = 87, totalListeningTimeMs = 52L * 3_600_000L,
        uniqueSongsPlayedCount = 312, uniqueArtistsPlayedCount = 96, uniqueAlbumsPlayedCount = 40,
        listeningDaysCount = 87, busiestDay = LocalDate.of(2025, 6, 14), busiestDayPlayCount = 41,
        averagePlaysPerActiveDay = 14.2,
        topSongs = songs.mapIndexed { i, s -> stat(s, 120 - i * 17) },
        topArtists = listOf(ArtistReportSummary("The Midnight Echoes", 12, 3, 300, 4, 60_000_000L, 1_700_000_000_000L)),
        topAlbums = listOf(AlbumReportSummary("City Lights", "The Midnight Echoes", 10, 280, 3, 50_000_000L, 1_700_000_000_000L)),
        mostPlayedSong = stat(songs[0], 120), mostPlayedArtist = null, mostPlayedAlbum = null,
        mostSkippedTrack = stat(songs[2], 4, skips = 19),
        recentlyPlayed = songs.map { stat(it, 3) },
        emptyState = ListeningAnalyticsEmptyState(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, true, true),
        longestStreak = 14, currentStreak = 5, mostActiveDayOfWeek = DayOfWeek.FRIDAY, mostActiveHour = 21,
        averageListeningTimePerActiveDayMs = 36L * 60_000L, mostReplayedTrack = stat(songs[0], 120),
    )

    private fun plan(period: WrappedPeriod, tag: String): WrappedExportPlan {
        val id = "vid02c-$tag-${System.nanoTime()}"
        ids += id
        return buildWrappedExportPlan(summary(period), settings, WrappedVisualStyle.DEFAULT, true, exportId = id, artworkUriFor = { null })
    }

    private val coordinator get() = controller!!.coordinator
    private fun state() = coordinator.state.value

    private fun start(plan: WrappedExportPlan): Boolean {
        var started = false
        rule.runOnUiThread { started = coordinator.start(plan) }
        return started
    }

    private fun cancel() = rule.runOnUiThread { coordinator.cancel() }

    private fun awaitState(what: String, timeoutMs: Long = 90_000, p: (WrappedVideoExportState) -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { p(state()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val main = Thread.getAllStackTraces().entries.filter { it.key.name == "main" }.joinToString { en -> en.value.take(12).joinToString(" | ") }
            throw AssertionError("$what: timed out in state ${state()}; host=${controller?.host != null}; main=$main", e)
        }
        assertTrue(what, p(state()))
    }

    private fun awaitIdle() = runBlocking { coordinator.joinCurrent() }

    private fun leftovers(plan: WrappedExportPlan): List<String> =
        (videoDir.listFiles().orEmpty().filter { it.name.contains(plan.exportId) } +
            slideBase.listFiles().orEmpty().filter { it.name.contains(plan.exportId) }).map { it.name }

    private fun info(file: File) = runBlocking { inspectWrappedVideo(file) }
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun exportToCompletion(p: WrappedExportPlan): WrappedVideoExportState.Completed {
        assertTrue(start(p))
        awaitState("completed") { it is WrappedVideoExportState.Completed || it is WrappedVideoExportState.Failed }
        val s = state()
        assertTrue("export must complete, was $s", s is WrappedVideoExportState.Completed)
        awaitIdle()
        return s as WrappedVideoExportState.Completed
    }

    // ---- user flows --------------------------------------------------------------------------------------------

    @Test fun allTimeExportProducesAValidMp4RemovesItsSlidesAndLeavesTheLayoutAlone() {
        val p = plan(WrappedPeriod.AllTime, "all")
        val before = rule.onNodeWithTag("probe").getBoundsInRoot()
        assertTrue(start(p))
        assertTrue(state().isActive)
        rule.waitForIdle()
        assertEquals("hosting the 360x640 composition must not move or resize anything", before, rule.onNodeWithTag("probe").getBoundsInRoot())
        awaitState("completed") { it is WrappedVideoExportState.Completed || it is WrappedVideoExportState.Failed }
        val done = state() as WrappedVideoExportState.Completed
        awaitIdle()
        assertEquals(p.reportKey, done.reportKey)
        assertEquals(videoDir.canonicalFile, done.file.canonicalFile.parentFile)
        val problems = verifyWrappedVideoInfo(info(done.file), p.totalDurationMs.toLong())
        assertTrue("MP4 must verify: $problems", problems.isEmpty())
        assertTrue("7 slides x 3 s", p.totalDurationMs == 21_000)
        assertEquals("only the finished video remains", listOf(done.file.name), leftovers(p))
        assertNull("render host unmounted after termination", controller!!.host)
        assertEquals(before, rule.onNodeWithTag("probe").getBoundsInRoot())
        done.file.copyTo(File(evidence, "all_time.mp4"), overwrite = true)
    }

    @Test fun yearlyReportWithMilestonesCompletes() {
        val p = plan(WrappedPeriod.year(2025, ZoneOffset.UTC), "yearly")
        assertTrue("fixture must include the milestone page", p.includesMilestonePage)
        val done = exportToCompletion(p)
        val problems = verifyWrappedVideoInfo(info(done.file), p.totalDurationMs.toLong())
        assertTrue("MP4 must verify: $problems", problems.isEmpty())
        assertEquals(listOf(done.file.name), leftovers(p))
        done.file.copyTo(File(evidence, "yearly_milestones.mp4"), overwrite = true)
    }

    @Test fun cancelDuringPreparationLeavesNothingBehind() {
        val p = plan(WrappedPeriod.AllTime, "cprep")
        assertTrue(start(p))
        assertTrue(state() is WrappedVideoExportState.Preparing)
        cancel()
        assertTrue(state() is WrappedVideoExportState.Cancelling || state() is WrappedVideoExportState.Cancelled)
        awaitState("cancelled") { it is WrappedVideoExportState.Cancelled }
        awaitIdle()
        assertEquals(emptyList<String>(), leftovers(p))
        assertNull(controller!!.host)
    }

    @Test fun cancelDuringEncodingStopsTheEncoderAndLeavesNothingBehind() {
        val p = plan(WrappedPeriod.AllTime, "cenc")
        assertTrue(start(p))
        awaitState("encoding") { it is WrappedVideoExportState.Encoding }
        cancel()
        awaitState("cancelled") { it is WrappedVideoExportState.Cancelled || it is WrappedVideoExportState.Completed }
        awaitIdle()
        val s = state()
        if (s is WrappedVideoExportState.Cancelled) {
            assertEquals("no video, no .part, no slides", emptyList<String>(), leftovers(p))
        } else {
            // The encode had already published: policy says the MP4 is kept and reported as Completed.
            assertEquals(1, leftovers(p).size)
            assertTrue(File(videoDir, leftovers(p).single()).exists())
        }
        assertNull(controller!!.host)
        // The encoder is reusable afterwards.
        val again = exportToCompletion(plan(WrappedPeriod.AllTime, "cenc-again"))
        assertTrue(again.file.exists())
    }

    @Test fun duplicateStartIsRefusedAndRepeatedExportsDoNotTouchEachOther() {
        val a = plan(WrappedPeriod.AllTime, "dup-a")
        assertTrue(start(a))
        assertFalse("second tap while active", start(plan(WrappedPeriod.AllTime, "dup-ignored")))
        awaitState("completed") { it is WrappedVideoExportState.Completed || it is WrappedVideoExportState.Failed }
        val first = state() as WrappedVideoExportState.Completed
        awaitIdle()
        val shaBefore = sha(first.file)
        val second = exportToCompletion(plan(WrappedPeriod.AllTime, "dup-b"))
        assertTrue(first.file != second.file)
        assertTrue(first.file.exists() && second.file.exists())
        assertEquals("the first export is untouched by the second", shaBefore, sha(first.file))
    }

    @Test fun leavingTheScreenDuringAnExportCancelsItAndCleansUp() {
        val p = plan(WrappedPeriod.AllTime, "leave")
        assertTrue(start(p))
        awaitState("encoding") { it is WrappedVideoExportState.Encoding }
        val c = coordinator
        rule.runOnUiThread { show.value = false } // the screen leaves composition
        rule.waitForIdle()
        rule.waitUntil(120_000) { !c.state.value.isActive }
        runBlocking { c.joinCurrent() }
        val s = c.state.value
        assertTrue("cancelled (or published if it had already committed): $s", s is WrappedVideoExportState.Cancelled || s is WrappedVideoExportState.Completed || s is WrappedVideoExportState.Idle)
        assertFalse("no slide directory", slideBase.listFiles().orEmpty().any { it.name.contains(p.exportId) })
        assertFalse("no .part", videoDir.listFiles().orEmpty().any { it.name.contains(p.exportId) && it.name.endsWith(".part") })
    }

    // ---- FileProvider security and a real receiver ---------------------------------------------------------------

    private val authority get() = ctx.packageName + ".fileprovider"

    @Test fun fileProviderMapsOnlyTheVideoDirectoryAndStillMapsTheImageShareDirectory() {
        videoDir.mkdirs(); slideBase.mkdirs()
        val ok = File(videoDir, "wavdrop_wrapped_vid02c-fp.mp4").also { it.writeBytes(byteArrayOf(1)) }
        val uri = FileProvider.getUriForFile(ctx, authority, ok)
        assertEquals("content", uri.scheme)
        assertTrue(uri.toString().contains("/wrapped_video/"))

        val shareDir = File(ctx.cacheDir, WRAPPED_SHARE_DIR).also { it.mkdirs() }
        val image = File(shareDir, "wavdrop_wrapped_vid02c-fp.png").also { it.writeBytes(byteArrayOf(1)) }
        assertTrue("image sharing unchanged", FileProvider.getUriForFile(ctx, authority, image).toString().contains("/wrapped_share/"))

        val slideDir = File(slideBase, WRAPPED_EXPORT_DIR_PREFIX + "vid02c-fp").also { it.mkdirs() }
        val slide = File(slideDir, "slide_01_intro.png").also { it.writeBytes(byteArrayOf(1)) }
        val cacheRoot = File(ctx.cacheDir, "vid02c-fp-root.txt").also { it.writeBytes(byteArrayOf(1)) }
        val files = File(ctx.filesDir, "vid02c-fp-files.txt").also { it.parentFile?.mkdirs(); it.writeBytes(byteArrayOf(1)) }
        val sibling = File(ctx.cacheDir, "wrapped_video_export_evil").also { it.mkdirs() }
        val siblingFile = File(sibling, "wavdrop_wrapped_vid02c-fp.mp4").also { it.writeBytes(byteArrayOf(1)) }
        val traversal = File(videoDir, "../vid02c-fp-root.txt")
        try {
            for (f in listOf(slide, cacheRoot, files, siblingFile, traversal)) {
                try {
                    FileProvider.getUriForFile(ctx, authority, f)
                    fail("FileProvider must not map ${f.path}")
                } catch (expected: IllegalArgumentException) {
                    // not mapped: cannot be shared
                }
            }
            assertFalse(isWrappedVideoShareable(slide, videoDir))
            assertFalse(isWrappedVideoShareable(siblingFile, videoDir))
            assertTrue(isWrappedVideoShareable(ok, videoDir))
        } finally {
            slideDir.deleteRecursively(); cacheRoot.delete(); files.delete(); sibling.deleteRecursively(); image.delete(); ok.delete()
        }
    }

    @Test fun aRealReceivingAppReadsTheGrantedVideoButNotWriteNorAnUngrantedSibling() {
        val done = exportToCompletion(plan(WrappedPeriod.AllTime, "recv"))
        val ungranted = File(videoDir, "wavdrop_wrapped_ungranted.mp4").also { it.writeBytes(byteArrayOf(9, 9, 9)) }
        var reported: String? = null
        try {
            val uri = FileProvider.getUriForFile(ctx, authority, done.file)
            // The receiver is a component of the TEST package: a different package and uid than the app that owns the
            // provider, so a successful read can only come from the URI grant carried by this ACTION_SEND intent.
            val intent = buildWrappedVideoShareIntent(uri)
                .setClassName(inst.context.packageName, "com.launchpoint.wavdrop.ui.screen.wrapped.WrappedVideoReceiverActivity")
            rule.runOnUiThread {
                rule.activity.activityResultRegistry.register(
                    "vid02c-receiver", androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                ) { r -> reported = r.data?.getStringExtra("report") ?: "no-report" }.launch(intent)
            }
            rule.waitUntil(60_000) { reported != null }
            val report = reported!!
            val kv = report.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("video/mp4", kv["type"])
            assertEquals("content", kv["scheme"])
            assertEquals(done.file.length().toString(), kv["readBytes"])
            assertEquals("the receiver read exactly the published bytes", sha(done.file), kv["readSha256"])
            assertTrue("write must be refused: $report", kv["write"] != "ALLOWED")
            assertTrue("ungranted read must be refused: $report", kv["ungrantedRead"] != "ALLOWED")
            File(evidence, "receiver_result.txt").writeText(report)
            // After the receiving app is gone the published video is still complete and playable.
            assertTrue(done.file.exists())
            val problems = verifyWrappedVideoInfo(info(done.file), plan(WrappedPeriod.AllTime, "x").totalDurationMs.toLong())
            assertTrue("still a valid MP4: $problems", problems.isEmpty())
        } finally {
            ungranted.delete()
        }
    }
}
