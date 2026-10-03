package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2H3A: listener slot semantics. */
class ExplicitPlayNextMutationListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitPlayNextMutationListenerRegistry().notifyExplicitPlayNextMutation()
    }

    @Test fun setNotifiesOncePerPlayNextMutationChange() {
        val r = ExplicitPlayNextMutationListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitPlayNextMutation()
        assertEquals(1, a)
        r.notifyExplicitPlayNextMutation()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitPlayNextMutationListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitPlayNextMutation()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitPlayNextMutationListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitPlayNextMutation()
        assertEquals(0, a)
    }

}
