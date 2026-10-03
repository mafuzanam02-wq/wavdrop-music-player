package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2H3C: listener slot semantics. */
class ExplicitQueueReorderListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitQueueReorderListenerRegistry().notifyExplicitQueueReorder()
    }

    @Test fun setNotifiesOncePerQueueReorderChange() {
        val r = ExplicitQueueReorderListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitQueueReorder()
        assertEquals(1, a)
        r.notifyExplicitQueueReorder()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitQueueReorderListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitQueueReorder()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitQueueReorderListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitQueueReorder()
        assertEquals(0, a)
    }

}
