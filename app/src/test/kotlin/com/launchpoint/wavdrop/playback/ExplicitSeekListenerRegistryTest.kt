package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2G2: listener slot semantics and the session-layer external-vs-app controller classification. */
class ExplicitSeekListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitSeekListenerRegistry().notifyExplicitSeek()
    }

    @Test fun setNotifiesOncePerSeek() {
        val r = ExplicitSeekListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitSeek()
        assertEquals(1, a)
        r.notifyExplicitSeek()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitSeekListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitSeek()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitSeekListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitSeek()
        assertEquals(0, a)
    }

    // Session layer: only an external user controller may trigger the explicit-seek hook.
    @Test fun externalControllerIsEligibleAndAppMarkedControllerIsSuppressed() {
        assertTrue(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = false))
        // The app controller also performs CF-2D2 handoff reconciliation seeks: it must never count as a user seek.
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = true))
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = false, isAppController = false))
    }
}
