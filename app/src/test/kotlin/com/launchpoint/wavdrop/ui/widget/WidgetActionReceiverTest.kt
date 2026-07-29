package com.launchpoint.wavdrop.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WD-03 — widget action transport hardening.
 *
 * The receiver's action→command mapping is a pure function; the MediaController
 * plumbing needs a device/session and is verified manually. These tests pin the
 * mapping and confirm unknown/foreign actions are ignored (mapped to null), so a
 * stray or malicious action delivered to the receiver actuates nothing.
 */
class WidgetActionReceiverTest {

    @Test
    fun `known widget actions map to their commands`() {
        assertEquals(
            WidgetActionReceiver.WidgetCommand.PLAY_PAUSE,
            WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_WIDGET_PLAY_PAUSE),
        )
        assertEquals(
            WidgetActionReceiver.WidgetCommand.NEXT,
            WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_WIDGET_NEXT),
        )
        assertEquals(
            WidgetActionReceiver.WidgetCommand.PREVIOUS,
            WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_WIDGET_PREVIOUS),
        )
    }

    @Test
    fun `null action is ignored`() {
        assertNull(WidgetActionReceiver.commandFor(null))
    }

    @Test
    fun `unknown or legacy raw service action is ignored`() {
        assertNull(WidgetActionReceiver.commandFor("com.launchpoint.wavdrop.ACTION_WIDGET_PLAY_PAUSE"))
        assertNull(WidgetActionReceiver.commandFor("android.intent.action.VIEW"))
        assertNull(WidgetActionReceiver.commandFor(""))
    }
}
