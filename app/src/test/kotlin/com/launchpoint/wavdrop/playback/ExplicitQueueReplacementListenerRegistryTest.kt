package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2H3F: listener slot semantics. */
class ExplicitQueueReplacementListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitQueueReplacementListenerRegistry().notifyExplicitQueueReplacement()
    }

    @Test fun setNotifiesOncePerQueueReplacementChange() {
        val r = ExplicitQueueReplacementListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitQueueReplacement()
        assertEquals(1, a)
        r.notifyExplicitQueueReplacement()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitQueueReplacementListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitQueueReplacement()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitQueueReplacementListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitQueueReplacement()
        assertEquals(0, a)
    }

}
