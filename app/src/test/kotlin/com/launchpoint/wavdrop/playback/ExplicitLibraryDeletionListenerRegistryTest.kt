package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2H3E: listener slot semantics. */
class ExplicitLibraryDeletionListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ExplicitLibraryDeletionListenerRegistry().notifyExplicitLibraryDeletion()
    }

    @Test fun setNotifiesOncePerLibraryDeletionChange() {
        val r = ExplicitLibraryDeletionListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyExplicitLibraryDeletion()
        assertEquals(1, a)
        r.notifyExplicitLibraryDeletion()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ExplicitLibraryDeletionListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyExplicitLibraryDeletion()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ExplicitLibraryDeletionListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyExplicitLibraryDeletion()
        assertEquals(0, a)
    }

}
