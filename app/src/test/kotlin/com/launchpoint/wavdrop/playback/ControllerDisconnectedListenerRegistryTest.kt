package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2F4: listener slot semantics and the identity gate that decides whether a disconnect notifies at all. */
class ControllerDisconnectedListenerRegistryTest {

    @Test fun noListenerIsHarmless() {
        ControllerDisconnectedListenerRegistry().notifyControllerDisconnected()
    }

    @Test fun setNotifiesOncePerDisconnect() {
        val r = ControllerDisconnectedListenerRegistry()
        var a = 0
        r.set { a++ }
        r.notifyControllerDisconnected()
        assertEquals(1, a)
        r.notifyControllerDisconnected()
        assertEquals(2, a)
    }

    @Test fun replacementNotifiesOnlyTheNewListener() {
        val r = ControllerDisconnectedListenerRegistry()
        var a = 0
        var b = 0
        r.set { a++ }
        r.set { b++ }
        r.notifyControllerDisconnected()
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test fun clearedListenerIsNeverNotified() {
        val r = ControllerDisconnectedListenerRegistry()
        var a = 0
        r.set { a++ }
        r.set(null)
        r.notifyControllerDisconnected()
        assertEquals(0, a)
    }

    // Mirrors PlayerController.onDisconnected: notify only when the identity guard passes, before clearing the reference.
    private class FakeController

    private class Harness {
        var current: FakeController? = null
        val order = mutableListOf<String>()
        val registry = ControllerDisconnectedListenerRegistry().apply {
            set { order += "notified(current still set=${current != null})" }
        }

        fun onDisconnected(controller: FakeController) {
            if (ControllerAttemptOwnership.shouldApplyDisconnect(current === controller)) {
                registry.notifyControllerDisconnected()
                current = null
                order += "cleared"
            }
        }
    }

    @Test fun currentControllerDisconnectNotifiesBeforeTheReferenceIsCleared() {
        val h = Harness()
        val a = FakeController()
        h.current = a
        h.onDisconnected(a)
        assertEquals(listOf("notified(current still set=true)", "cleared"), h.order)
    }

    @Test fun staleControllerDisconnectNeverNotifiesAndLeavesTheNewControllerAlone() {
        val h = Harness()
        val a = FakeController()
        val b = FakeController()
        h.current = b // B is authoritative now
        h.onDisconnected(a) // late disconnect of A
        assertEquals(emptyList<String>(), h.order)
        assertEquals(b, h.current)
    }

    @Test fun repeatedDisconnectOfTheSameControllerNotifiesOnlyOnce() {
        val h = Harness()
        val a = FakeController()
        h.current = a
        h.onDisconnected(a)
        h.onDisconnected(a)
        assertEquals(listOf("notified(current still set=true)", "cleared"), h.order)
    }
}
