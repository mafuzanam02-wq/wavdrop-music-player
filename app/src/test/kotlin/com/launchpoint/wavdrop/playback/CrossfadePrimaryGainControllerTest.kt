package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadePrimaryGainControllerTest {

    private class FakeBackend : PrimaryGainBackend {
        val writes = mutableListOf<Float>()
        var failWhen: (Float) -> Boolean = { false }
        var throwWhen: (Float) -> Boolean = { false }
        override fun setGain(gain: Float): Boolean {
            writes += gain
            if (throwWhen(gain)) throw IllegalStateException("boom")
            return !failWhen(gain)
        }
    }

    private val backend = FakeBackend()
    private val controller = CrossfadePrimaryGainController(backend)
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val keyB = CrossfadeTransitionKey(5L, 2, 3)

    @Test fun firstOwnerApplies() {
        assertTrue(controller.apply(keyA, 0.8f))
        assertEquals(keyA, controller.ownerKey)
        assertEquals(listOf(0.8f), backend.writes)
    }

    @Test fun sameOwnerMayUpdate() {
        assertTrue(controller.apply(keyA, 0.8f))
        assertTrue(controller.apply(keyA, 0.5f))
        assertEquals(listOf(0.8f, 0.5f), backend.writes)
    }

    @Test fun differentOwnerCannotApplyOrRestore() {
        assertTrue(controller.apply(keyA, 0.8f))
        assertFalse(controller.apply(keyB, 0.5f))
        assertFalse(controller.restore(keyB))
        assertEquals(listOf(0.8f), backend.writes) // no keyB mutation
        assertEquals(keyA, controller.ownerKey)
    }

    @Test fun matchingRestoreWritesExactlyOneAndClearsOwner() {
        controller.apply(keyA, 0.8f)
        assertTrue(controller.restore(keyA))
        assertEquals(listOf(0.8f, 1f), backend.writes)
        assertNull(controller.ownerKey)
    }

    @Test fun restoreWithoutOwnerIsHarmlessSuccess() {
        assertTrue(controller.restore(keyA))
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun failedApplyRetainsOwnerAndRestoreStillWorks() {
        backend.failWhen = { it == 0.7f }
        assertFalse(controller.apply(keyA, 0.7f))
        assertEquals(keyA, controller.ownerKey)
        assertTrue(controller.restore(keyA))
        assertEquals(listOf(0.7f, 1f), backend.writes)
        assertNull(controller.ownerKey)
    }

    @Test fun failedRestoreRetainsOwnerBlocksOthersAndLaterRestoreRecovers() {
        controller.apply(keyA, 0.7f)
        backend.failWhen = { it == 1f }
        assertFalse(controller.restore(keyA))
        assertEquals(keyA, controller.ownerKey)
        assertFalse(controller.apply(keyB, 0.4f))
        backend.failWhen = { false }
        assertTrue(controller.restore(keyA))
        assertNull(controller.ownerKey)
        assertTrue(controller.apply(keyB, 0.4f)) // free again
    }

    @Test fun invalidGainsAreRejectedWithoutBackendWriteOrOwnership() {
        for (g in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.01f)) {
            assertFalse("gain $g", controller.apply(keyA, g))
        }
        assertTrue(backend.writes.isEmpty())
        assertNull(controller.ownerKey)
    }

    @Test fun boundaryGainsAreValid() {
        assertTrue(controller.apply(keyA, 0f))
        assertTrue(controller.apply(keyA, 1f))
    }

    @Test fun backendExceptionsAreContained() {
        backend.throwWhen = { it == 0.6f }
        assertFalse(controller.apply(keyA, 0.6f))
        assertEquals(keyA, controller.ownerKey) // still restorable
        backend.throwWhen = { it == 1f }
        assertFalse(controller.restore(keyA))
        assertEquals(keyA, controller.ownerKey)
        backend.throwWhen = { false }
        assertTrue(controller.restore(keyA))
    }

    @Test fun unavailableBackendNeverPretendsSuccess() {
        val c = CrossfadePrimaryGainController(PrimaryGainBackend.Unavailable)
        assertFalse(c.apply(keyA, 0.5f))
        assertEquals(keyA, c.ownerKey)
    }
}
