package com.launchpoint.wavdrop.ui.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.launchpoint.wavdrop.MainActivity
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
import org.w3c.dom.Element

/**
 * P5-UI-15A — widget visual foundation. Covers the size→layout policy, the real inflated RemoteViews for every layout
 * (long text, missing artwork, play/pause state), the control PendingIntents, and that nothing in the playback transport
 * changed. The visual result on a launcher is NOT proven here; that needs the physical QA checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WavdropWidgetUpdaterTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val activeLayouts = listOf(
        R.layout.wavdrop_widget_short,
        R.layout.wavdrop_widget_normal,
        R.layout.wavdrop_widget_tall,
        R.layout.wavdrop_widget_expanded,
    )

    private fun snapshot(
        title: String = "Title",
        artist: String = "Artist",
        playing: Boolean = false,
        albumId: Long = 5L,
    ) = WidgetPlaybackSnapshot(title, artist, albumId, playing, hasActiveMedia = true, updatedAt = 0L)


    // ── layout selection by size ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun idleStateAlwaysUsesTheIdleLayoutWhateverTheSize() {
        listOf(0 to 0, 245 to 115, 400 to 400).forEach { (w, h) ->
            assertEquals(R.layout.wavdrop_widget_idle, WidgetLayoutSelector.layoutFor(false, w, h))
        }
    }

    @Test fun theDeclaredFourByTwoMinimumGetsTheNormalLayout() {
        assertEquals(R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, 245, 115))
        assertEquals(R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, 320, 130))
        assertEquals(R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, 300, 199))
    }

    @Test fun belowTheDeclaredMinimumFallsBackToTheCompactStrip() {
        assertEquals(R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, 300, 99))
        assertEquals(R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, 219, 150))
        assertEquals(R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, 100, 60))
    }

    @Test fun tallerPlacementsUseTheLargerLayoutsInsteadOfStretchingTheSmallOne() {
        assertEquals(R.layout.wavdrop_widget_tall, WidgetLayoutSelector.layoutFor(true, 300, 200))
        assertEquals(R.layout.wavdrop_widget_tall, WidgetLayoutSelector.layoutFor(true, 300, 299))
        assertEquals(R.layout.wavdrop_widget_tall, WidgetLayoutSelector.layoutFor(true, 300, 300))
        assertEquals(R.layout.wavdrop_widget_expanded, WidgetLayoutSelector.layoutFor(true, 300, 520))
    }

    @Test fun narrowWidthWinsOverGenerousHeight() {
        // A tall but narrow placement cannot fit the vertical layouts' control row, so width is checked too.
        assertEquals(R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, 200, 400))
    }

    @Test fun incompleteLauncherDimensionsFallBackSafely() {
        assertEquals("nothing reported → normal 4×2", R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, 0, 0))
        assertEquals("width unknown is ignored", R.layout.wavdrop_widget_tall, WidgetLayoutSelector.layoutFor(true, 0, 250))
        assertEquals("height unknown with a roomy width", R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, 320, 0))
        assertEquals("height unknown with a too-narrow width", R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, 150, 0))
    }

    @Test fun everyReachableLayoutIsReachableAndHasABoundedArtworkTarget() {
        val reached = setOf(
            WidgetLayoutSelector.layoutFor(true, 100, 60),
            WidgetLayoutSelector.layoutFor(true, 300, 150),
            WidgetLayoutSelector.layoutFor(true, 300, 250),
            WidgetLayoutSelector.layoutFor(true, 300, 400),
        )
        assertEquals(activeLayouts.toSet(), reached)
        activeLayouts.forEach {
            val px = WidgetLayoutSelector.artworkTargetPx(it)
            assertTrue("artwork stays well inside the binder budget", px in 128..384)
        }
    }

    @Test fun artworkSubSamplingNeverDecodesBelowTheTargetAndShrinksBigCovers() {
        assertEquals(1, WidgetLayoutSelector.sampleSizeFor(200, 256))
        assertEquals(1, WidgetLayoutSelector.sampleSizeFor(256, 256))
        assertEquals(2, WidgetLayoutSelector.sampleSizeFor(600, 256))
        assertEquals(8, WidgetLayoutSelector.sampleSizeFor(3000, 256))
        assertEquals(1, WidgetLayoutSelector.sampleSizeFor(0, 256))
        listOf(300, 512, 1000, 3000, 4096).forEach { longest ->
            val sample = WidgetLayoutSelector.sampleSizeFor(longest, 256)
            assertTrue(longest / sample >= 256 || sample == 1)
        }
    }

    // ── content: long text, missing artwork, active vs idle, play/pause ─────────────────────────────────────────────
    // The unit-test classpath has no app resources, so layout structure is verified by parsing the layout XML itself, and
    // the state decisions by the pure WidgetRenderState the updater applies to the RemoteViews.

    private val layoutFiles = mapOf(
        "wavdrop_widget_short" to 36,
        "wavdrop_widget_normal" to 44,
        "wavdrop_widget_tall" to 48,
        "wavdrop_widget_expanded" to 48,
    )

    private fun resFile(path: String): File =
        listOf("src/main/res/$path", "app/src/main/res/$path").map(::File).first { it.exists() }

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resFile(path)).documentElement

    private fun Element.all(): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            out += e
            val kids = e.childNodes
            for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(::walk)
        }
        walk(this)
        return out
    }

    private fun Element.attr(name: String) = getAttribute("android:$name")
    private fun Element.byId(id: String) = all().firstOrNull { it.attr("id") == "@+id/$id" }
    private fun Element.dp(name: String) = attr(name).removeSuffix("dp").toFloatOrNull()

    @Test fun everyActiveLayoutBoundsAndEllipsizesLongTitleAndArtist() {
        layoutFiles.keys.forEach { name ->
            val root = parse("layout/$name.xml")
            val title = root.byId("widget_title")!!
            val artist = root.byId("widget_artist")!!
            assertTrue("$name title lines", title.attr("maxLines").toInt() in 1..2)
            assertEquals("$name artist is one line", "1", artist.attr("maxLines"))
            assertEquals("$name title ellipsizes", "end", title.attr("ellipsize"))
            assertEquals("$name artist ellipsizes", "end", artist.attr("ellipsize"))
            assertEquals("$name title width is bounded by its column", "match_parent", title.attr("layout_width"))
        }
    }

    @Test fun theRenderStateCarriesLongTitlesUntruncatedForTheTextViewToEllipsize() {
        val longTitle = "A".repeat(300)
        val state = WidgetRenderState.of(snapshot(title = longTitle, artist = "B".repeat(300)), hasArtwork = true)
        assertEquals(longTitle, state.title)
        assertEquals(300, state.artist.length)
    }

    @Test fun missingArtworkUsesOnePlaceholderAndNothingCrashesWithoutASnapshot() {
        val missing = WidgetRenderState.of(snapshot(), hasArtwork = false)
        assertFalse(missing.showsArtwork)
        assertEquals(R.drawable.widget_artwork_placeholder, missing.placeholderRes)
        val none = WidgetRenderState.of(null, hasArtwork = false)
        assertEquals("", none.title)
        assertEquals("", none.artist)
    }

    @Test fun everyActiveLayoutCanHideTheAtmosphereAndScrimAndHasARoundedArtworkSlot() {
        layoutFiles.keys.forEach { name ->
            val root = parse("layout/$name.xml")
            assertEquals("gone", root.byId("widget_background")!!.attr("visibility"))
            assertEquals("gone", root.byId("widget_scrim")!!.attr("visibility"))
            val art = root.byId("widget_artwork")!!
            assertEquals("@drawable/widget_artwork_bg", art.attr("background"))
            assertEquals("true", art.attr("clipToOutline"))
            assertEquals("square artwork in $name", art.attr("layout_width"), art.attr("layout_height"))
        }
    }

    @Test fun playAndPauseIconsFollowThePlaybackState() {
        val playing = WidgetRenderState.of(snapshot(playing = true), hasArtwork = true)
        val paused = WidgetRenderState.of(snapshot(playing = false), hasArtwork = true)
        assertEquals(R.drawable.widget_ic_pause, playing.playPauseIconRes)
        assertEquals(R.drawable.widget_btn_accent_bg, playing.playPauseBackgroundRes)
        assertEquals(R.drawable.widget_ic_play, paused.playPauseIconRes)
        assertEquals(R.drawable.widget_btn_idle_bg, paused.playPauseBackgroundRes)
    }

    @Test fun theIdleLayoutOnlyOpensTheAppAndHasNoTransportControls() {
        val root = parse("layout/wavdrop_widget_idle.xml")
        assertNotNull(root.byId("widget_body"))
        assertNull(root.byId("widget_play_pause"))
        assertNull(root.byId("widget_previous"))
        assertNull(root.byId("widget_next"))
    }

    @Test fun everyActiveLayoutHasAllThreeControlsAndTheTapToOpenBody() {
        layoutFiles.keys.forEach { name ->
            val root = parse("layout/$name.xml")
            listOf("widget_body", "widget_previous", "widget_play_pause", "widget_next").forEach {
                assertNotNull("$name is missing $it", root.byId(it))
            }
        }
    }

    @Test fun controlTouchTargetsKeepTheirMinimumSizeWhereTheLayoutAllowsIt() {
        layoutFiles.forEach { (name, minDp) ->
            val root = parse("layout/$name.xml")
            listOf("widget_previous", "widget_play_pause", "widget_next").forEach { id ->
                val v = root.byId(id)!!
                assertTrue("$name/$id width ${v.attr("layout_width")}", v.dp("layout_width")!! >= minDp)
                assertTrue("$name/$id height ${v.attr("layout_height")}", v.dp("layout_height")!! >= minDp)
            }
        }
        // The two roomy layouts and the 4×2 default keep the full 48dp height; only width may flex on the narrow 4×2.
        listOf("wavdrop_widget_tall", "wavdrop_widget_expanded").forEach { name ->
            listOf("widget_previous", "widget_play_pause", "widget_next").forEach { id ->
                assertTrue(parse("layout/$name.xml").byId(id)!!.dp("layout_height")!! >= 48f)
            }
        }
    }

    // ── geometry at the size thresholds (static XML arithmetic; NOT a rendered check) ───────────────────────────────────
    // Text line height is modelled as 1.17 × text size × font scale (Roboto/One UI ≈ 1.17). 1sp counts as `scale` dp.

    private val lineFactor = 1.17f
    private val fontScales = listOf(1.0f, 1.3f, 1.5f)
    private fun textHeight(sp: Float, lines: Int, scale: Float) = sp * scale * lineFactor * lines

    private fun Element.num(name: String): Float = dp(name) ?: 0f
    private fun Element.parent(): Element = parentNode as Element
    private fun Element.siblingAfter(): Element {
        var n = nextSibling
        while (n != null && n !is Element) n = n.nextSibling
        return n as Element
    }
    private fun Element.textSp(): Float = attr("textSize").removeSuffix("sp").toFloat()
    private fun controls(root: Element) = listOf("widget_previous", "widget_play_pause", "widget_next").map { root.byId(it)!! }
    private fun controlsWidth(root: Element) = controls(root).sumOf { (it.num("layout_width") + it.num("layout_marginStart")).toDouble() }.toFloat()
    private fun isFlexible(e: Element) = e.attr("layout_height") == "0dp" && e.attr("layout_weight") == "1"

    @Test fun layoutSelectionBoundariesAreExactlyWhereTheGeometryIsProven() {
        val w = 300
        assertEquals(R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, w, 99))
        assertEquals(R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, w, 100))
        assertEquals(R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, w, 199))
        assertEquals(R.layout.wavdrop_widget_tall, WidgetLayoutSelector.layoutFor(true, w, 200))
        assertEquals(R.layout.wavdrop_widget_tall, WidgetLayoutSelector.layoutFor(true, w, 329))
        assertEquals(R.layout.wavdrop_widget_expanded, WidgetLayoutSelector.layoutFor(true, w, 330))
        assertEquals(R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, 239, 150))
        assertEquals(R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, 240, 150))
        assertEquals("the declared minimum width stays in the normal layout", R.layout.wavdrop_widget_normal, WidgetLayoutSelector.layoutFor(true, 245, 115))
        assertEquals(R.layout.wavdrop_widget_short, WidgetLayoutSelector.layoutFor(true, 239, 400))
        assertEquals(R.layout.wavdrop_widget_expanded, WidgetLayoutSelector.layoutFor(true, 240, 400))
    }

    @Test fun normalLayoutFitsItsControlsInTheNarrowestWidthTheSelectorSendsIt() {
        val root = parse("layout/wavdrop_widget_normal.xml")
        val art = root.byId("widget_artwork")!!
        val row = art.parent()
        val column = art.siblingAfter()
        val needed = row.num("paddingStart") + row.num("paddingEnd") + art.num("layout_width") + column.num("layout_marginStart") + controlsWidth(root)
        assertTrue("normal needs ${needed}dp of width", needed <= WidgetLayoutSelector.COMPACT_MAX_WIDTH_DP)
        assertTrue("and still fits the declared 245dp minimum", needed <= 245f)
        // The three controls are fixed-size (no weights), so they cannot be squeezed into overlapping each other.
        controls(root).forEach {
            assertEquals("no weighted control in normal", "", it.attr("layout_weight"))
            assertTrue(it.num("layout_height") >= 48f)
        }
        assertTrue("play/pause is the largest", root.byId("widget_play_pause")!!.num("layout_width") >= 48f)
        assertTrue("prev/next keep at least 44dp", root.byId("widget_previous")!!.num("layout_width") >= 44f)
        assertTrue(root.byId("widget_next")!!.num("layout_width") >= 44f)
    }

    @Test fun normalLayoutFitsItsHeightAtTheDeclaredMinimumEvenWithLargerFonts() {
        val root = parse("layout/wavdrop_widget_normal.xml")
        val art = root.byId("widget_artwork")!!
        val row = art.parent()
        val controlsRow = root.byId("widget_play_pause")!!.parent()
        val textBlock = root.byId("widget_title")!!.parent()
        val fixed = row.num("paddingTop") + row.num("paddingBottom") + controlsRow.num("layout_height")
        val minHeight = 115f
        assertTrue("art fits", art.num("layout_height") + row.num("paddingTop") + row.num("paddingBottom") <= minHeight)
        assertTrue("controls row cannot be squeezed by text", isFlexible(textBlock) && controlsRow.attr("layout_height").endsWith("dp"))
        val budget = minHeight - fixed
        listOf(1.0f, 1.3f).forEach { scale ->
            val need = textHeight(root.byId("widget_title")!!.textSp(), 1, scale) + root.byId("widget_artist")!!.num("layout_marginTop") +
                textHeight(root.byId("widget_artist")!!.textSp(), 1, scale)
            assertTrue("two text lines need ${need}dp of the ${budget}dp left at scale $scale", need <= budget)
        }
    }

    @Test fun expandedLayoutFitsItsWorstCaseAtItsThresholdAndKeepsTheControlsVisible() {
        val root = parse("layout/wavdrop_widget_expanded.xml")
        val art = root.byId("widget_artwork")!!
        val column = art.parent()
        val controlsRow = root.byId("widget_play_pause")!!.parent()
        val textBlock = root.byId("widget_title")!!.parent()
        val title = root.byId("widget_title")!!
        val artist = root.byId("widget_artist")!!
        assertEquals("two-line title is allowed", "2", title.attr("maxLines"))
        assertTrue("text flexes, art and controls are fixed", isFlexible(textBlock) && art.attr("layout_height").endsWith("dp") && controlsRow.attr("layout_height").endsWith("dp"))
        val fixed = column.num("paddingTop") + column.num("paddingBottom") + art.num("layout_height") + controlsRow.num("layout_marginTop") + controlsRow.num("layout_height")
        val threshold = WidgetLayoutSelector.TALL_MAX_HEIGHT_DP.toFloat()
        fontScales.filter { it <= 1.3f }.forEach { scale ->
            val text = textBlock.num("paddingTop") + textHeight(title.textSp(), 2, scale) + artist.num("layout_marginTop") + textHeight(artist.textSp(), 1, scale)
            assertTrue("expanded needs ${fixed + text}dp at font scale $scale but activates at ${threshold}dp", fixed + text <= threshold)
        }
        assertTrue("fixed parts alone leave real room for text at the threshold", threshold - fixed >= 100f)
        val availableWidth = 240f - column.num("paddingStart") - column.num("paddingEnd")
        assertTrue("controls ${controlsWidth(root)}dp fit ${availableWidth}dp", controlsWidth(root) <= availableWidth)
        assertTrue(controls(root).all { it.num("layout_height") >= 48f && it.num("layout_width") >= 48f })
    }

    @Test fun tallLayoutIsFixedHeightSoLargeFontsClipTextNotControls() {
        val root = parse("layout/wavdrop_widget_tall.xml")
        val art = root.byId("widget_artwork")!!
        val infoRow = art.parent()
        val column = infoRow.parent()
        val controlsRow = root.byId("widget_play_pause")!!.parent().parent()
        assertTrue("info row has a fixed height", infoRow.attr("layout_height").endsWith("dp"))
        val fixed = column.num("paddingTop") + column.num("paddingBottom") + infoRow.num("layout_height") +
            controlsRow.num("layout_marginTop") + root.byId("widget_play_pause")!!.num("layout_height")
        assertTrue("tall needs ${fixed}dp and activates at ${WidgetLayoutSelector.NORMAL_MAX_HEIGHT_DP}dp", fixed <= WidgetLayoutSelector.NORMAL_MAX_HEIGHT_DP)
        val title = root.byId("widget_title")!!
        val artist = root.byId("widget_artist")!!
        fontScales.filter { it <= 1.3f }.forEach { scale ->
            val text = textHeight(title.textSp(), title.attr("maxLines").toInt(), scale) + artist.num("layout_marginTop") + textHeight(artist.textSp(), 1, scale)
            assertTrue("two-line title + artist (${text}dp) fit the ${infoRow.num("layout_height")}dp row at scale $scale", text <= infoRow.num("layout_height"))
        }
        // Weighted equal cells: at the 240dp minimum each cell is wider than the widest button.
        val cellWidth = (240f - column.num("paddingStart") - column.num("paddingEnd")) / 3f
        assertTrue(controls(root).all { it.num("layout_width") <= cellWidth })
    }

    @Test fun compactStripFitsItsHeightAndALegibleTextWidthAtARealisticFourByOneWidth() {
        val root = parse("layout/wavdrop_widget_short.xml")
        val art = root.byId("widget_artwork")!!
        val row = art.parent()
        assertTrue(row.num("paddingTop") + row.num("paddingBottom") + art.num("layout_height") <= 64f)
        val chrome = row.num("paddingStart") + row.num("paddingEnd") + art.num("layout_width") + 14f
        assertTrue("controls ${controlsWidth(root)} + chrome $chrome leave >= 60dp for text at 280dp", 280f - chrome - controlsWidth(root) >= 60f)
        assertTrue(controls(root).all { it.num("layout_height") >= 40f })
    }

    @Test fun theUnusedLegacyLayoutsAreGoneAndEverythingReachableStillExists() {
        assertFalse(resFile("layout").resolve("wavdrop_widget_compact.xml").exists())
        assertFalse(resFile("layout").resolve("wavdrop_widget_loading.xml").exists())
        (layoutFiles.keys + "wavdrop_widget_idle").forEach { assertTrue(resFile("layout/$it.xml").exists()) }
    }

    // ── PendingIntents ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun existingBroadcast(requestCode: Int, action: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    @Test fun controlPendingIntentsTargetTheNonExportedReceiverWithTheirOwnActions() {
        activeLayouts.forEach { layout ->
            WavdropWidgetUpdater.buildViews(context, layout, snapshot(), null)
            val expected = listOf(
                WavdropWidgetUpdater.RC_PREVIOUS to WidgetActionReceiver.ACTION_WIDGET_PREVIOUS,
                WavdropWidgetUpdater.RC_PLAY_PAUSE to WidgetActionReceiver.ACTION_WIDGET_PLAY_PAUSE,
                WavdropWidgetUpdater.RC_NEXT to WidgetActionReceiver.ACTION_WIDGET_NEXT,
            )
            expected.forEach { (code, action) ->
                val pi = existingBroadcast(code, action)
                assertNotNull("no PendingIntent for $action", pi)
                val shadow = shadowOf(pi)
                assertTrue(shadow.isBroadcastIntent)
                val sent = shadow.savedIntent
                assertEquals(action, sent.action)
                assertEquals(WidgetActionReceiver::class.java.name, sent.component?.className)
            }
        }
        assertEquals(
            "request codes are distinct",
            4,
            setOf(
                WavdropWidgetUpdater.RC_OPEN_APP, WavdropWidgetUpdater.RC_PREVIOUS,
                WavdropWidgetUpdater.RC_PLAY_PAUSE, WavdropWidgetUpdater.RC_NEXT,
            ).size,
        )
    }

    @Test fun theBodyTapOpensTheAppAndNothingElse() {
        WavdropWidgetUpdater.buildViews(context, R.layout.wavdrop_widget_normal, snapshot(), null)
        val open = PendingIntent.getActivity(
            context,
            WavdropWidgetUpdater.RC_OPEN_APP,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNotNull(open)
        val shadow = shadowOf(open)
        assertTrue(shadow.isActivityIntent)
        assertEquals(MainActivity::class.java.name, shadow.savedIntent.component?.className)
        assertNull("the open intent carries no extra action", shadow.savedIntent.action)
    }

    // ── guards: transport and exposure unchanged, no polling, view→intent pairing ───────────────────────────────────

    private fun source(name: String): String {
        val roots = listOf("src/main/kotlin/com/launchpoint/wavdrop/ui/widget/", "app/src/main/kotlin/com/launchpoint/wavdrop/ui/widget/")
        return roots.map { File(it + name) }.first { it.exists() }.readText()
    }

    @Test fun eachControlViewIsPairedWithItsOwnActionInTheUpdaterSource() {
        val src = source("WavdropWidgetUpdater.kt")
        val pairs = mapOf(
            "R.id.widget_previous" to "ACTION_WIDGET_PREVIOUS",
            "R.id.widget_play_pause" to "ACTION_WIDGET_PLAY_PAUSE",
            "R.id.widget_next" to "ACTION_WIDGET_NEXT",
        )
        pairs.forEach { (view, action) ->
            val regex = Regex("""setOnClickPendingIntent\(\s*${Regex.escape(view)},\s*broadcastIntent\(context,\s*WidgetActionReceiver\.$action""")
            assertTrue("$view must click $action", regex.containsMatchIn(src))
        }
    }

    @Test fun noPeriodicPollingAndNoNewBroadcastSurface() {
        val info = File("src/main/res/xml/wavdrop_widget_info.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/xml/wavdrop_widget_info.xml")
        assertTrue(info.readText().contains("android:updatePeriodMillis=\"0\""))
        val receiver = source("WidgetActionReceiver.kt")
        assertEquals("still exactly three widget actions", 3, Regex("""const val ACTION_WIDGET_""").findAll(receiver).count())
        val manifest = (File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")).readText()
        val receiverBlock = manifest.substringAfter("android:name=\".ui.widget.WidgetActionReceiver\"").substringBefore("/>")
        assertTrue(receiverBlock.contains("android:exported=\"false\""))
        assertFalse(source("WavdropWidgetUpdater.kt").contains("AlarmManager"))
    }
}
