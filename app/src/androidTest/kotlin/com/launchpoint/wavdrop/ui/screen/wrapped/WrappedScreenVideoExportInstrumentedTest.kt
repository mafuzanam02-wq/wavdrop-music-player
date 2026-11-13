package com.launchpoint.wavdrop.ui.screen.wrapped

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanner
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-WR-VID-02C: the REAL Wrapped screen (real pager, real ViewModel over an in-memory Room database seeded with
 * synthetic listening history, real slide host, real Media3 encoder), driven through its UI. Nothing in the export
 * pipeline is mocked. Files this test produces in the app's video/slide directories are removed afterwards; files that
 * were already there are never touched.
 */
@RunWith(AndroidJUnit4::class)
class WrappedScreenVideoExportInstrumentedTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = inst.targetContext
    private val videoDir get() = wrappedVideoDir(ctx.cacheDir)
    private val slideBase get() = wrappedExportBaseDir(ctx.cacheDir)
    private val evidence get() = File(ctx.getExternalFilesDir(null)!!, "vid02c").also { it.mkdirs() }

    private lateinit var db: WavdropDatabase
    private lateinit var dsScope: CoroutineScope
    private val show = mutableStateOf(true)
    private var videosBefore = emptySet<String>()
    private var slidesBefore = emptySet<String>()
    private val prefsFiles = ArrayList<File>()

    @Before fun before() {
        videoDir.mkdirs(); slideBase.mkdirs()
        videosBefore = videoDir.list().orEmpty().toSet()
        slidesBefore = slideBase.list().orEmpty().toSet()
        db = Room.inMemoryDatabaseBuilder(ctx, WavdropDatabase::class.java).allowMainThreadQueries().build()
        dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun after() {
        db.close()
        dsScope.cancel()
        prefsFiles.forEach { it.delete(); File(it.path + ".tmp").delete() }
        videoDir.listFiles()?.filter { it.name !in videosBefore }?.forEach { it.deleteRecursively() }
        slideBase.listFiles()?.filter { it.name !in slidesBefore }?.forEach { it.deleteRecursively() }
    }

    private val newVideos get() = videoDir.listFiles().orEmpty().filter { it.name !in videosBefore && it.name.endsWith(".mp4") }
    private val newParts get() = videoDir.listFiles().orEmpty().filter { it.name !in videosBefore && it.name.endsWith(".part") }
    private val newSlideDirs get() = slideBase.listFiles().orEmpty().filter { it.name !in slidesBefore }

    private fun seed(withData: Boolean) = runBlocking {
        if (!withData) return@runBlocking
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val songs = (1L..5L).map {
            SongEntity(it, "Track $it", "Artist ${it % 3}", "Album ${it % 2}", it, 200_000L, "content://media/$it", 0L, it.toInt(), 2024)
        }
        db.songDao().upsertAll(songs)
        songs.forEach { s ->
            db.trackStatsDao().insertIfAbsent(
                TrackStatsEntity(s.id, s.uri, playCount = 40 - s.id.toInt() * 3, skipCount = s.id.toInt(), lastPlayedAt = now, lastListenedAt = now, totalListeningTimeMs = 3_600_000L * s.id),
            )
        }
        val events = ArrayList<TrackListenEventEntity>()
        // This month, last month, and a year ago: Monthly, Yearly and All Time all have content.
        for (offset in listOf(1L, 2L, 3L, 40L, 70L, 400L)) {
            for (s in songs) {
                events += TrackListenEventEntity(
                    songId = s.id, eventType = "PLAY", occurredAt = now - offset * day - s.id * 60_000L,
                    listenedMs = 180_000L, durationMs = 200_000L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
                )
            }
        }
        db.trackListenEventDao().insertAll(events)
    }

    private fun mount(withData: Boolean = true) {
        seed(withData)
        val prefsFile = File(ctx.cacheDir, "vid02c_prefs_${System.nanoTime()}.preferences_pb").also { prefsFiles += it }
        val store = PreferenceDataStoreFactory.create(scope = dsScope) { prefsFile }
        lateinit var vm: WrappedViewModel
        rule.runOnUiThread {
            val songRepo = SongRepository(db, db.songDao(), db.playlistDao(), MediaStoreScanner(ctx), LibraryScanSettingsRepository(store), db.trackIdentityDao())
            val statsRepo = StatsRepository(db, db.trackStatsDao(), db.importBaselineDao(), db.trackListenEventDao())
            vm = WrappedViewModel(songRepo, statsRepo, AppSettingsRepository(store))
        }
        rule.setContent {
            WavdropTheme(themeMode = ThemeMode.DARK) {
                if (show.value) {
                    WrappedScreen(
                        onNavigateBack = {}, onTrackDetailsClick = {}, onArtistClick = {}, onAlbumClick = {},
                        viewModel = vm,
                    )
                }
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(tag: String, timeoutMs: Long = 120_000) = rule.waitUntil(timeoutMs) { exists(tag) }
    private fun statusText(): String =
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun hasStatus(sub: String): Boolean = exists(TAG_VIDEO_EXPORT_STATUS) && statusText().contains(sub)
    private fun awaitStatus(sub: String, timeoutMs: Long = 180_000) = rule.waitUntil(timeoutMs) { hasStatus(sub) }
    private fun shot(name: String) {
        val bmp: Bitmap? = inst.uiAutomation.takeScreenshot()
        if (bmp != null) File(evidence, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun info(f: File) = runBlocking { inspectWrappedVideo(f) }
    private fun click(tag: String) = rule.onNodeWithTag(tag).performClick()

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test fun exportButtonIsOfferedOnlyWhenTheReportHasContent() {
        mount(withData = false)
        rule.waitUntil(60_000) { rule.onAllNodesWithContentDescription("Wrapped appearance settings").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        assertFalse("no export action on an empty/loading Wrapped screen", exists(TAG_VIDEO_EXPORT_BUTTON))
    }

    @Test fun exportFromTheRealScreenProducesAMp4AndTheImageShareButtonIsUntouched() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_BUTTON).assertIsEnabled()
        assertEquals("image sharing still present", 1, rule.onAllNodesWithContentDescription("Share this slide").fetchSemanticsNodes().size)
        shot("screen_ready")
        click(TAG_VIDEO_EXPORT_BUTTON)
        rule.waitUntil(30_000) { hasStatus("Preparing") || hasStatus("Encoding") }
        shot("screen_preparing_or_encoding")
        awaitStatus("Video ready")
        shot("screen_completed")
        awaitTag(TAG_VIDEO_EXPORT_SHARE)
        val mp4 = newVideos.single()
        val i = info(mp4)
        assertNotNull(i)
        assertEquals("portrait 720x1280 H.264, no audio", "video/avc", i!!.videoMime)
        assertEquals(0, i.audioTrackCount)
        assertEquals(720, i.displayWidth); assertEquals(1280, i.displayHeight)
        assertTrue("some slides' worth of video, multiple of 3 s", i.durationMs in 6_000..40_000)
        assertTrue("only the MP4 remains: no part, no slide directories", newParts.isEmpty() && newSlideDirs.isEmpty())
        assertEquals("image sharing still present afterwards", 1, rule.onAllNodesWithContentDescription("Share this slide").fetchSemanticsNodes().size)
        mp4.copyTo(File(evidence, "screen_export.mp4"), overwrite = true)
    }

    @Test fun duplicateTapsCreateOneJobAndChangingTheReportMidExportKeepsTheOriginalSnapshot() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        click(TAG_VIDEO_EXPORT_BUTTON)
        // A second tap right away: the button is gone (replaced by Cancel), so nothing can start a second job.
        rule.waitUntil(30_000) { hasStatus("Preparing") || hasStatus("Encoding") }
        val label = statusText().substringAfter("· ").substringBefore(" ·")
        assertFalse("export button hidden while active", exists(TAG_VIDEO_EXPORT_BUTTON))
        // The user switches the report while the export runs.
        rule.onNode(hasText("All Time") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertTrue("running export keeps reporting its own snapshot ($label), not the new selection", statusText().contains(label))
        shot("screen_report_changed_midexport")
        awaitStatus("Video ready")
        assertTrue(statusText().contains(label))
        assertEquals("exactly one MP4", 1, newVideos.size)
        // A new export for the NEW selection is now possible and produces a second, separate file.
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        val first = newVideos.single()
        click(TAG_VIDEO_EXPORT_BUTTON)
        awaitStatus("Video ready")
        rule.waitUntil(60_000) { newVideos.size == 2 }
        assertTrue(first.exists())
    }

    @Test fun cancelDuringPreparationFromTheUiLeavesNoFiles() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        click(TAG_VIDEO_EXPORT_BUTTON)
        awaitTag(TAG_VIDEO_EXPORT_CANCEL, 30_000)
        click(TAG_VIDEO_EXPORT_CANCEL)
        awaitStatus("cancelled")
        shot("screen_cancelled")
        rule.waitForIdle()
        assertTrue(newVideos.isEmpty() && newParts.isEmpty() && newSlideDirs.isEmpty())
        // ... and the user can immediately export again.
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        click(TAG_VIDEO_EXPORT_BUTTON)
        awaitStatus("Video ready")
        assertEquals(1, newVideos.size)
    }

    @Test fun cancelDuringEncodingFromTheUiLeavesNoFiles() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        click(TAG_VIDEO_EXPORT_BUTTON)
        awaitStatus("Encoding")
        click(TAG_VIDEO_EXPORT_CANCEL)
        rule.waitUntil(120_000) { hasStatus("cancelled") || hasStatus("Video ready") }
        if (hasStatus("cancelled")) {
            rule.waitForIdle()
            assertTrue("no video, part or slide directory", newVideos.isEmpty() && newParts.isEmpty() && newSlideDirs.isEmpty())
        } else {
            assertEquals("already published when cancel arrived: kept", 1, newVideos.size)
        }
    }

    @Test fun leavingTheScreenDuringAnExportCleansUpAndNothingCrashes() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        click(TAG_VIDEO_EXPORT_BUTTON)
        awaitStatus("Encoding")
        rule.runOnUiThread { show.value = false }
        rule.waitForIdle()
        rule.waitUntil(120_000) { newParts.isEmpty() && newSlideDirs.isEmpty() }
        // Whatever was published before the screen left (at most one finished MP4) is complete, never a fragment.
        for (f in newVideos) assertTrue(f.length() > 0L)
    }

    @Test fun shareBuildsAChooserForAContentUriAndFailuresAreReportedNotThrown() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        click(TAG_VIDEO_EXPORT_BUTTON)
        awaitStatus("Video ready")
        val mp4 = newVideos.single()
        val act = rule.activity
        var captured: Intent? = null
        assertEquals(WrappedVideoShareResult.Opened, launchWrappedVideoShare(act, mp4) { captured = it })
        val chooser = captured!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/mp4", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        assertEquals(WrappedVideoShareResult.NoActivityAvailable, launchWrappedVideoShare(act, mp4) { throw ActivityNotFoundException() })
        assertEquals(WrappedVideoShareResult.Failed, launchWrappedVideoShare(act, mp4) { throw SecurityException("no") })
        assertEquals(WrappedVideoShareResult.FileUnavailable, launchWrappedVideoShare(act, File(videoDir, "wavdrop_wrapped_missing.mp4")) { fail() })
        assertEquals("a failed share never deletes the MP4", true, mp4.exists())
        // A chooser failure is not a state change: the screen still offers the video.
        assertTrue(exists(TAG_VIDEO_EXPORT_SHARE))
    }

    @Test fun shareVideoButtonOpensTheRealChooserAndTheVideoStaysReadable() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        click(TAG_VIDEO_EXPORT_BUTTON)
        awaitStatus("Video ready")
        val mp4 = newVideos.single()
        val before = mp4.length()
        click(TAG_VIDEO_EXPORT_SHARE)
        Thread.sleep(3_000) // evidence only: let the system chooser draw before the screenshot
        shot("screen_chooser")
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) // dismiss
        Thread.sleep(1_000)
        assertTrue("MP4 survives the chooser being dismissed", mp4.exists() && mp4.length() == before)
        val i = info(mp4)
        assertNotNull(i)
        assertEquals(1280, i!!.displayHeight)
    }

    @Test fun shareThisSlideStillWritesAPngIntoItsOwnDirectoryAndOpensItsChooser() {
        mount()
        awaitTag(TAG_VIDEO_EXPORT_BUTTON)
        val shareDir = File(ctx.cacheDir, WRAPPED_SHARE_DIR).also { it.mkdirs() }
        val had = shareDir.list().orEmpty().toSet()
        rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription("Share this slide").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Share this slide").performClick()
        rule.waitUntil(60_000) { shareDir.list().orEmpty().any { it !in had && it.endsWith(".png") } }
        val png = shareDir.listFiles().orEmpty().single { it.name !in had && it.name.endsWith(".png") }
        try {
            Thread.sleep(2_500) // evidence only: let the system chooser draw
            shot("screen_image_share_chooser")
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(800)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(png.path, bounds)
            assertTrue("image share wrote a real PNG", bounds.outWidth > 0 && bounds.outHeight > 0)
            assertTrue("no video export was started by the image share", newVideos.isEmpty() && newSlideDirs.isEmpty())
        } finally {
            png.delete()
        }
    }

    private fun fail(): Nothing = throw AssertionError("must not be called")
}
