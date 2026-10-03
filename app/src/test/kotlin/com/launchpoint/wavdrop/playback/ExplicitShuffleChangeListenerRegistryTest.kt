package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2H2: listener slot semantics. */
class ExplicitShuffleChangeListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitShuffleChangeListenerRegistry().notifyExplicitShuffleChange()
    }

    @Test fun setNotifiesOncePerShuffleChange() {
        val r = ExplicitShuffleChangeListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitShuffleChange()
        assertEquals(1, a)
        r.notifyExplicitShuffleChange()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitShuffleChangeListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitShuffleChange()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitShuffleChangeListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitShuffleChange()
        assertEquals(0, a)
    }

}
