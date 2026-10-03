package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2H1: listener slot semantics and the session-layer external-vs-app controller classification. */
class ExplicitRepeatChangeListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitRepeatChangeListenerRegistry().notifyExplicitRepeatChange()
    }

    @Test fun setNotifiesOncePerRepeatChange() {
        val r = ExplicitRepeatChangeListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitRepeatChange()
        assertEquals(1, a)
        r.notifyExplicitRepeatChange()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitRepeatChangeListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitRepeatChange()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitRepeatChangeListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitRepeatChange()
        assertEquals(0, a)
    }

    // Session layer: only an external user controller may trigger the explicit-repeat-change hook.
    @Test fun externalControllerIsEligibleAndAppMarkedControllerIsSuppressed() {
        assertTrue(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = false))
        // App-marked requests (incl. the custom CYCLE_REPEAT command) already notified in PlayerController.cycleRepeatMode: never a second, session-layer repeat cancel.
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = true))
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = false, isAppController = false))
    }
}
