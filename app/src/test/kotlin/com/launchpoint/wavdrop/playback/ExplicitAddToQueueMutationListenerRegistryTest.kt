package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2H3B: listener slot semantics. */
class ExplicitAddToQueueMutationListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitAddToQueueMutationListenerRegistry().notifyExplicitAddToQueueMutation()
    }

    @Test fun setNotifiesOncePerAddToQueueMutationChange() {
        val r = ExplicitAddToQueueMutationListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitAddToQueueMutation()
        assertEquals(1, a)
        r.notifyExplicitAddToQueueMutation()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitAddToQueueMutationListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitAddToQueueMutation()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitAddToQueueMutationListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitAddToQueueMutation()
        assertEquals(0, a)
    }

}
