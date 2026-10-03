package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2H3D: listener slot semantics. */
class ExplicitQueueRemovalListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitQueueRemovalListenerRegistry().notifyExplicitQueueRemoval()
    }

    @Test fun setNotifiesOncePerQueueRemovalChange() {
        val r = ExplicitQueueRemovalListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitQueueRemoval()
        assertEquals(1, a)
        r.notifyExplicitQueueRemoval()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitQueueRemovalListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitQueueRemoval()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitQueueRemovalListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitQueueRemoval()
        assertEquals(0, a)
    }

}
