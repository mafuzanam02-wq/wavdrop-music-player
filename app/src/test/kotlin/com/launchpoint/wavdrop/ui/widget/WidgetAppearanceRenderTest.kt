package com.launchpoint.wavdrop.ui.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.launchpoint.wavdrop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * P5-UI-15B — appearance rendering decisions, the unchanged control PendingIntents, the configuration-Activity contract and the
 * static resource wiring. The XML/source checks here do NOT prove how the widget looks on a Samsung launcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetAppearanceRenderTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun snapshot(playing: Boolean = false) =
        WidgetPlaybackSnapshot("Title", "Artist", 5L, playing, hasActiveMedia = true, updatedAt = 0L)

    private fun look(style: WidgetBackgroundStyle, intensity: WidgetArtworkIntensity = WidgetArtworkIntensity.BALANCED) =
        WidgetAppearance(style, intensity)

    // ── 7. Artwork Light / Balanced / Dark ────────────────────────────────────────────────────────────────────────

    @Test fun artworkStyleShowsTheArtworkBackgroundWithAPresetScrimPerIntensity() {
        val scrims = WidgetArtworkIntensity.entries.associateWith {
            val s = WidgetRenderState.of(snapshot(), hasArtwork = true, appearance = look(WidgetBackgroundStyle.ARTWORK, it))
            assertTrue("artwork background for $it", s.showsArtworkBackground)
            assertTrue(s.showsArtwork)
            s.scrimColorRes
        }
        assertEquals(R.color.widget_scrim_light, scrims.getValue(WidgetArtworkIntensity.LIGHT))
        assertEquals(R.color.widget_scrim_balanced, scrims.getValue(WidgetArtworkIntensity.BALANCED))
        assertEquals(R.color.widget_scrim_dark, scrims.getValue(WidgetArtworkIntensity.DARK))
        assertEquals("three distinct presets", 3, scrims.values.toSet().size)
    }

    @Test fun theScrimPresetsGoFromLighterToDarkerAndBalancedIsTheOriginalLook() {
        fun alpha(name: String): Int {
            val xml = File(listOf("src/main/res/values/widget_colors.xml", "app/src/main/res/values/widget_colors.xml").first { File(it).exists() }).readText()
            return Regex("""name="$name">#([0-9A-Fa-f]{2})""").find(xml)!!.groupValues[1].toInt(16)
        }
        val light = alpha("widget_scrim_light"); val balanced = alpha("widget_scrim_balanced"); val dark = alpha("widget_scrim_dark")
        assertTrue("light < balanced < dark", light < balanced && balanced < dark)
        assertEquals("Balanced equals the pre-customization scrim (#D9)", 0xD9, balanced)
        assertTrue("even Light keeps a majority-dark overlay so text stays legible", light >= 0x80)
    }

    @Test fun theDefaultAppearanceRendersExactlyLikeBalancedArtwork() {
        val default = WidgetRenderState.of(snapshot(), hasArtwork = true)
        val balanced = WidgetRenderState.of(snapshot(), hasArtwork = true, appearance = look(WidgetBackgroundStyle.ARTWORK, WidgetArtworkIntensity.BALANCED))
        assertEquals(default, balanced)
    }

    // ── 8. Solid ignores artwork-background intensity ─────────────────────────────────────────────────────────────

    @Test fun solidNeverShowsTheArtworkAsABackgroundWhateverTheStoredIntensity() {
        val states = WidgetArtworkIntensity.entries.map {
            WidgetRenderState.of(snapshot(), hasArtwork = true, appearance = look(WidgetBackgroundStyle.SOLID, it))
        }
        states.forEach {
            assertFalse(it.showsArtworkBackground)
            assertTrue("the thumbnail still has the artwork", it.showsArtwork)
        }
        assertEquals("solid output is identical for every stored intensity", 1, states.toSet().size)
    }

    @Test fun solidDoesNotApplyIntensityAndHidesItInTheConfigurationUi() {
        assertFalse(look(WidgetBackgroundStyle.SOLID, WidgetArtworkIntensity.DARK).intensityApplies)
        assertTrue(look(WidgetBackgroundStyle.ARTWORK).intensityApplies)
        assertEquals(WidgetArtworkIntensity.BALANCED, look(WidgetBackgroundStyle.SOLID, WidgetArtworkIntensity.DARK).effective().intensity)
    }

    // ── 9. missing artwork / inactive playback ───────────────────────────────────────────────────────────────────

    @Test fun missingArtworkNeverShowsABackgroundInAnyStyleAndKeepsThePlaceholder() {
        WidgetBackgroundStyle.entries.forEach { style ->
            val s = WidgetRenderState.of(snapshot(), hasArtwork = false, appearance = look(style, WidgetArtworkIntensity.LIGHT))
            assertFalse(s.showsArtworkBackground)
            assertFalse(s.showsArtwork)
            assertEquals(R.drawable.widget_artwork_placeholder, s.placeholderRes)
        }
    }

    @Test fun inactivePlaybackStaysOnTheIdleLayoutForEveryAppearanceAndSize() {
        listOf(0 to 0, 245 to 115, 320 to 400).forEach { (w, h) ->
            assertEquals(R.layout.wavdrop_widget_idle, WidgetLayoutSelector.layoutFor(false, w, h))
        }
        // and building the views for every appearance/no-snapshot combination never throws
        WidgetBackgroundStyle.entries.forEach { style ->
            WavdropWidgetUpdater.buildViews(context, R.layout.wavdrop_widget_idle, null, null, look(style))
            WavdropWidgetUpdater.buildViews(context, R.layout.wavdrop_widget_normal, null, null, look(style))
        }
    }

    @Test fun everyActiveLayoutBuildsForEveryAppearanceWithAndWithoutArtwork() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        listOf(
            R.layout.wavdrop_widget_short, R.layout.wavdrop_widget_normal,
            R.layout.wavdrop_widget_tall, R.layout.wavdrop_widget_expanded,
        ).forEach { layout ->
            WidgetBackgroundStyle.entries.forEach { style ->
                WidgetArtworkIntensity.entries.forEach { intensity ->
                    assertNotNull(WavdropWidgetUpdater.buildViews(context, layout, snapshot(true), bitmap, look(style, intensity), resolveColor = ::presetArgb))
                    assertNotNull(WavdropWidgetUpdater.buildViews(context, layout, snapshot(false), null, look(style, intensity)))
                }
            }
        }
    }

    // ── 10. Play/Pause and navigation PendingIntents are unchanged by appearance ─────────────────────────────────────

    private fun broadcast(code: Int, action: String) = PendingIntent.getBroadcast(
        context, code, Intent(context, WidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    @Test fun controlAndOpenAppPendingIntentsAreTheSameForEveryAppearance() {
        WidgetBackgroundStyle.entries.forEach { style ->
            WidgetArtworkIntensity.entries.forEach { intensity ->
                WavdropWidgetUpdater.buildViews(context, R.layout.wavdrop_widget_normal, snapshot(true), null, look(style, intensity))
                listOf(
                    WavdropWidgetUpdater.RC_PREVIOUS to WidgetActionReceiver.ACTION_WIDGET_PREVIOUS,
                    WavdropWidgetUpdater.RC_PLAY_PAUSE to WidgetActionReceiver.ACTION_WIDGET_PLAY_PAUSE,
                    WavdropWidgetUpdater.RC_NEXT to WidgetActionReceiver.ACTION_WIDGET_NEXT,
                ).forEach { (code, action) ->
                    val pi = broadcast(code, action)
                    assertNotNull("$action missing for $style/$intensity", pi)
                    assertEquals(action, shadowOf(pi).savedIntent.action)
                    assertEquals(WidgetActionReceiver::class.java.name, shadowOf(pi).savedIntent.component?.className)
                }
                val open = PendingIntent.getActivity(
                    context, WavdropWidgetUpdater.RC_OPEN_APP,
                    Intent(context, com.launchpoint.wavdrop.MainActivity::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                assertNotNull(open)
            }
        }
    }

    @Test fun playPauseIconStillFollowsPlaybackStateInEveryAppearance() {
        WidgetBackgroundStyle.entries.forEach { style ->
            assertEquals(R.drawable.widget_ic_pause, WidgetRenderState.of(snapshot(true), true, look(style)).playPauseIconRes)
            assertEquals(R.drawable.widget_ic_play, WidgetRenderState.of(snapshot(false), true, look(style)).playPauseIconRes)
        }
    }

    // ── 13. configuration Activity contract ──────────────────────────────────────────────────────────────────────

    @Test fun theWidgetIdIsReadFromTheLauncherIntentAndMissingMeansInvalid() {
        val withId = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 17)
        assertEquals(17, WidgetConfigContract.widgetIdFrom(withId))
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, WidgetConfigContract.widgetIdFrom(Intent()))
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, WidgetConfigContract.widgetIdFrom(null))
    }

    @Test fun onlyARealIdOwnedByOurProviderIsUsable() {
        val ours = intArrayOf(3, 4, 9)
        assertTrue(WidgetConfigContract.isUsable(4, ours))
        assertFalse("invalid id", WidgetConfigContract.isUsable(AppWidgetManager.INVALID_APPWIDGET_ID, ours))
        assertFalse("someone else's widget id", WidgetConfigContract.isUsable(77, ours))
        assertFalse("no widgets at all", WidgetConfigContract.isUsable(4, intArrayOf()))
    }

    @Test fun theResultDataCarriesTheWidgetIdBackToTheLauncher() {
        val data = WidgetConfigContract.resultData(23)
        assertEquals(23, data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    private fun activitySource(): String =
        listOf("src/main/kotlin/com/launchpoint/wavdrop/ui/widget/WidgetConfigActivity.kt", "app/src/main/kotlin/com/launchpoint/wavdrop/ui/widget/WidgetConfigActivity.kt")
            .map(::File).first { it.exists() }.readText()

    @Test fun theActivityCancelsFirstSavesBeforeOkAndRejectsBadIdsWithoutAnyUi() {
        val src = activitySource()
        val onCreate = src.substringAfter("override fun onCreate").substringBefore("private fun cancel")
        // 1. invalid/missing/foreign id → RESULT_CANCELED + finish before any UI or storage access
        val rejectBlock = onCreate.substringAfter("if (!WidgetConfigContract.isUsable").substringBefore("}")
        assertTrue(rejectBlock.contains("RESULT_CANCELED") && rejectBlock.contains("finish()"))
        assertTrue("the invalid path returns before setContent", onCreate.indexOf("isUsable") < onCreate.indexOf("setContent"))
        // 2. a valid launch is RESULT_CANCELED (with the id) until Save succeeds
        assertTrue(onCreate.contains("setResult(Activity.RESULT_CANCELED, WidgetConfigContract.resultData(appWidgetId))"))
        // 3. Cancel returns RESULT_CANCELED and never touches the store
        val cancel = src.substringAfter("private fun cancel").substringBefore("private fun save")
        assertTrue(cancel.contains("RESULT_CANCELED") && cancel.contains("finish()") && !cancel.contains("widgetAppearanceStore"))
        // 4. Save is delegated to WidgetConfigSaver (persist -> awaited first render -> Saved); RESULT_OK only in the Saved branch
        val save = src.substringAfter("private fun save")
        assertTrue(save.contains("saver.commit(id, appearance, previous)"))
        assertEquals("RESULT_OK is only ever set after a successful save", 1, Regex("RESULT_OK").findAll(codeOnly(src.substringAfter("override fun onCreate"))).count())
    }

    // ── static resource / manifest wiring (not a visual proof) ───────────────────────────────────────────────────

    private fun mainFile(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    @Test fun theProviderDeclaresTheConfigurationActivityAndReconfigureSupport() {
        val info = mainFile("res/xml/wavdrop_widget_info.xml").readText()
        assertTrue(info.contains("android:configure=\"com.launchpoint.wavdrop.ui.widget.WidgetConfigActivity\""))
        assertTrue(info.contains("android:widgetFeatures=\"reconfigurable\""))
        assertTrue("still no polling", info.contains("android:updatePeriodMillis=\"0\""))
    }

    @Test fun theConfigurationActivityIsRegisteredWithTheConfigureActionAndTheReceiverStaysPrivate() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mainFile("AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val config = (0 until activities.length).map { activities.item(it) }
            .first { it.attributes.getNamedItem("android:name").nodeValue == ".ui.widget.WidgetConfigActivity" }
        assertEquals("true", config.attributes.getNamedItem("android:exported").nodeValue)
        val actions = (config as org.w3c.dom.Element).getElementsByTagName("action")
        assertEquals("android.appwidget.action.APPWIDGET_CONFIGURE", actions.item(0).attributes.getNamedItem("android:name").nodeValue)
        assertEquals("only the configure action", 1, actions.length)
        val receivers = doc.getElementsByTagName("receiver")
        val actionReceiver = (0 until receivers.length).map { receivers.item(it) }
            .first { it.attributes.getNamedItem("android:name").nodeValue == ".ui.widget.WidgetActionReceiver" }
        assertEquals("false", actionReceiver.attributes.getNamedItem("android:exported").nodeValue)
    }

    @Test fun theProviderCleansUpAndMigratesAppearanceOnLauncherEvents() {
        val src = mainFile("kotlin/com/launchpoint/wavdrop/ui/widget/WavdropWidgetProvider.kt").readText()
        assertTrue(src.substringAfter("override fun onDeleted").substringBefore("override fun onDisabled").contains("it.remove(appWidgetIds.toList())"))
        assertTrue(src.substringAfter("override fun onDisabled").substringBefore("override fun onRestored").contains("clearAll()"))
        assertTrue(src.substringAfter("override fun onRestored").contains("it.migrate(oldWidgetIds, newWidgetIds)"))
    }

    @Test fun theUpdaterRoutesEveryRequestThroughTheSerialisingCoordinatorAndAddsNoNewBroadcastSurface() {
        val src = mainFile("kotlin/com/launchpoint/wavdrop/ui/widget/WavdropWidgetUpdater.kt").readText()
        assertEquals("no direct launches of performUpdate outside the coordinator", 1, Regex("""performUpdate\(it, target\)""").findAll(src).count())
        assertTrue(src.contains("coordinator.request(null)") && src.contains("coordinator.request(appWidgetIds)"))
        assertFalse(src.contains("AlarmManager"))
        val receiver = mainFile("kotlin/com/launchpoint/wavdrop/ui/widget/WidgetActionReceiver.kt").readText()
        assertEquals(3, Regex("""const val ACTION_WIDGET_""").findAll(receiver).count())
    }

    @Test fun noPlaybackServiceOrPlaybackPackageChangesAreNeededForAppearance() {
        val updater = mainFile("kotlin/com/launchpoint/wavdrop/ui/widget/WavdropWidgetUpdater.kt").readText()
        assertFalse(updater.contains("PlaybackService::class"))
        assertFalse(updater.contains("import com.launchpoint.wavdrop.playback.PlaybackService"))
        assertNull(Regex("""import com\.launchpoint\.wavdrop\.playback""").find(updater))
    }

    // ── P5-UI-15B1: the overlay is painted with a RemoteViews-supported background-COLOR operation ───────────────────
    // LIMITATION: this unit-test environment has no app resources, so a real update of Wavdrop's own widget layouts cannot be
    // applied here. The mechanism is exercised on a framework layout instead; it is not a launcher/Samsung verification.

    private fun colorsXml() = File(listOf("src/main/res/values/widget_colors.xml", "app/src/main/res/values/widget_colors.xml").first { File(it).exists() }).readText()

    private fun xmlArgb(name: String): Int =
        Regex("""name="$name">#([0-9A-Fa-f]{8})""").find(colorsXml())!!.groupValues[1].toLong(16).toInt()

    /** Stands in for ContextCompat.getColor (no resources in this environment): resolves an R.color id from widget_colors.xml. */
    private fun presetArgb(@Suppress("UNUSED_PARAMETER") context: Context, res: Int): Int = presetArgb(res)
    private fun presetArgb(res: Int): Int = when (res) {
        R.color.widget_scrim_light -> xmlArgb("widget_scrim_light")
        R.color.widget_scrim_balanced -> xmlArgb("widget_scrim_balanced")
        R.color.widget_scrim_dark -> xmlArgb("widget_scrim_dark")
        else -> error("unexpected scrim color resource $res")
    }

    @Test fun theThreePresetsResolveToTheExactArgbValues() {
        assertEquals(0x8C16131F.toInt(), presetArgb(WidgetArtworkIntensity.LIGHT.scrimColorRes))
        assertEquals("Balanced stays the original #D916131F", 0xD916131F.toInt(), presetArgb(WidgetArtworkIntensity.BALANCED.scrimColorRes))
        assertEquals(0xF216131F.toInt(), presetArgb(WidgetArtworkIntensity.DARK.scrimColorRes))
        assertEquals(WidgetArtworkIntensity.BALANCED, WidgetAppearance.DEFAULT.intensity)
    }

    @Test fun artworkStyleResolvesExactlyTheSelectedPresetAndSolidOrMissingArtworkResolvesNone() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        WidgetArtworkIntensity.entries.forEach { intensity ->
            val asked = mutableListOf<Int>()
            WavdropWidgetUpdater.buildViews(
                context, R.layout.wavdrop_widget_normal, snapshot(true), bitmap, look(WidgetBackgroundStyle.ARTWORK, intensity),
                resolveColor = { _, res -> asked += res; presetArgb(res) },
            )
            assertEquals("Artwork/$intensity resolves its own preset once", listOf(intensity.scrimColorRes), asked)

            val solidAsked = mutableListOf<Int>()
            WavdropWidgetUpdater.buildViews(
                context, R.layout.wavdrop_widget_normal, snapshot(true), bitmap, look(WidgetBackgroundStyle.SOLID, intensity),
                resolveColor = { _, res -> solidAsked += res; presetArgb(res) },
            )
            assertTrue("Solid never paints an overlay", solidAsked.isEmpty())

            val noArtAsked = mutableListOf<Int>()
            WavdropWidgetUpdater.buildViews(
                context, R.layout.wavdrop_widget_normal, snapshot(true), null, look(WidgetBackgroundStyle.ARTWORK, intensity),
                resolveColor = { _, res -> noArtAsked += res; presetArgb(res) },
            )
            assertTrue("no artwork → no overlay", noArtAsked.isEmpty())
        }
    }

    @Test fun setBackgroundColorIsAnAnnotatedRemotableViewMethodOnView() {
        val method = android.view.View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
        assertTrue(
            "View.setBackgroundColor must be @RemotableViewMethod for RemoteViews.setInt to call it",
            method.annotations.any { it.annotationClass.java.name == "android.view.RemotableViewMethod" },
        )
        assertEquals("setBackgroundColor", SET_BACKGROUND_COLOR_METHOD)
    }

    @Test fun theColorOperationActuallyPaintsAViewWhenAppliedThroughRemoteViews() {
        // Real RemoteViews → real apply() on a framework layout (TextView text1), using the same helper the widget uses.
        listOf(WidgetArtworkIntensity.LIGHT, WidgetArtworkIntensity.BALANCED, WidgetArtworkIntensity.DARK).forEach { intensity ->
            val argb = presetArgb(intensity.scrimColorRes)
            val views = android.widget.RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
            views.setScrimColor(android.R.id.text1, argb)
            val root = views.apply(context, android.widget.FrameLayout(context))
            val background = root.findViewById<android.view.View>(android.R.id.text1).background
            assertTrue(background is android.graphics.drawable.ColorDrawable)
            assertEquals("exact ARGB reaches the view for $intensity", argb, (background as android.graphics.drawable.ColorDrawable).color)
        }
    }

    @Test fun theUpdaterNoLongerFeedsAColorResourceIntoSetBackgroundResource() {
        val src = mainFile("kotlin/com/launchpoint/wavdrop/ui/widget/WavdropWidgetUpdater.kt").readText()
        assertFalse(src.contains("\"setBackgroundResource\", state.scrimColorRes"))
        assertTrue(src.contains("views.setScrimColor(R.id.widget_scrim, resolveColor(context, state.scrimColorRes))"))
        // the only remaining setBackgroundResource use is the play/pause DRAWABLE selector
        assertEquals(1, Regex("\"setBackgroundResource\"").findAll(src).count())
        assertTrue(src.contains("\"setBackgroundResource\", state.playPauseBackgroundRes"))
        // the scrim view is a TextView (a View: setBackgroundColor applies)
        val layoutXml = mainFile("res/layout/wavdrop_widget_normal.xml").readText()
        assertTrue(Regex("""<TextView\s+android:id="@\+id/widget_scrim"""").containsMatchIn(layoutXml))
    }

    /** Source with whole-line `//` comments removed, so a comment mentioning a result code is not counted as a call. */
    private fun codeOnly(source: String): String = source.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
}
