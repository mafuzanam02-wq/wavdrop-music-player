package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2G3: listener slot semantics and the session-layer external-vs-app controller classification. */
class ExplicitNavigationListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitNavigationListenerRegistry().notifyExplicitNavigation()
    }

    @Test fun setNotifiesOncePerNavigation() {
        val r = ExplicitNavigationListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitNavigation()
        assertEquals(1, a)
        r.notifyExplicitNavigation()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitNavigationListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitNavigation()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitNavigationListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitNavigation()
        assertEquals(0, a)
    }

    // Session layer: only an external user controller may trigger the explicit-navigation hook.
    @Test fun externalControllerIsEligibleAndAppMarkedControllerIsSuppressed() {
        assertTrue(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = false))
        // App-marked requests already notified in PlayerController.skipToNext/Previous (and the app controller performs CF-2D2 reconciliation seeks): never a second, session-layer navigation cancel.
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = true))
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = false, isAppController = false))
    }
}
