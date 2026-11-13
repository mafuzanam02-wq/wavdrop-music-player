package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P5-WR-VID-02C: the export strip's states, controls, announcements and progress semantics on a real device. */
@RunWith(AndroidJUnit4::class)
class WrappedVideoExportBarInstrumentedTest {

    @get:Rule val rule = createComposeRule()

    private val state = mutableStateOf<WrappedVideoExportState>(WrappedVideoExportState.Idle)
    private val enabled = mutableStateOf(true)
    private var exports = 0
    private var cancels = 0
    private var shared: File? = null

    private fun show() = rule.setContent {
        WavdropTheme(themeMode = ThemeMode.DARK) {
            WrappedVideoExportBar(
                state = state.value,
                exportEnabled = enabled.value,
                shareEnabled = true,
                onExport = { exports++ },
                onCancel = { cancels++ },
                onShare = { shared = it },
            )
        }
    }

    @Test fun idleShowsAnEnabledExportButtonThatStartsOneExport() {
        show()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_BUTTON).assertIsEnabled().performClick()
        assertEquals(1, exports)
        rule.onNodeWithText("Export video").assertExists()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_CANCEL).assertDoesNotExist()
    }

    @Test fun exportIsDisabledForIneligibleContent() {
        enabled.value = false
        show()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_BUTTON).assertIsNotEnabled()
    }

    @Test fun preparingShowsPageCountAndAnIndeterminateBarWithACancelAction() {
        state.value = WrappedVideoExportState.Preparing("All Time", 7)
        show()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextContains("Preparing 7 slides", substring = true)
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextContains("All Time", substring = true)
        // Indeterminate: no numeric progress is exposed while slides are prepared.
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_CANCEL).performClick()
        assertEquals(1, cancels)
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_BUTTON).assertDoesNotExist()
    }

    @Test fun encodingWithUnknownProgressStaysIndeterminateAndKnownProgressIsDeterminate() {
        state.value = WrappedVideoExportState.Encoding("All Time", null)
        show()
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextEquals("Encoding video · All Time")
        state.value = WrappedVideoExportState.Encoding("All Time", 40)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextContains("40%", substring = true)
        val info = rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.4f, 0f..1f)))
        info.assertExists()
    }

    @Test fun statusChangesAreAnnouncedThroughALiveRegion() {
        state.value = WrappedVideoExportState.Preparing("All Time", 7)
        show()
        val node = rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
    }

    @Test fun cancellingOffersNoFurtherActions() {
        state.value = WrappedVideoExportState.Cancelling("All Time")
        show()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextContains("Cancelling", substring = true)
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_CANCEL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_BUTTON).assertDoesNotExist()
    }

    @Test fun completedOffersShareVideoWithTheFileAndNeverClaimsItWasShared() {
        val f = File("/tmp/wavdrop_wrapped_x.mp4")
        state.value = WrappedVideoExportState.Completed("All Time", "ALL_TIME", f)
        show()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextContains("Video ready", substring = true)
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_SHARE).performClick()
        assertEquals(f, shared)
        rule.onNodeWithText("Share video").assertExists()
        rule.onNodeWithText("Export again").assertExists()
        assertTrue(rule.onAllNodesWithTextCount("shared").let { it == 0 })
    }

    @Test fun failureShowsAMessageAndRetry() {
        state.value = WrappedVideoExportState.Failed("All Time", WrappedVideoExportFailure.EncodingFailed)
        show()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextContains("Couldn't encode", substring = true)
        rule.onNodeWithText("Retry").assertExists()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_BUTTON).performClick()
        assertEquals(1, exports)
    }

    @Test fun cancelledOffersANewExport() {
        state.value = WrappedVideoExportState.Cancelled("All Time")
        show()
        rule.onNodeWithTag(TAG_VIDEO_EXPORT_STATUS).assertTextEquals("Export cancelled.")
        rule.onNodeWithText("Export video").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().size
}
