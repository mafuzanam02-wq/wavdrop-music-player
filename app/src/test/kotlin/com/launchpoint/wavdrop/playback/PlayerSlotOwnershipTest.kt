package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** CF-2M3: the pure CURRENT/NEXT role table. No Android, no playback. */
class PlayerSlotOwnershipTest {

    private class Phys(val name: String)

    private fun table(): Triple<PlayerSlotTable<Phys>, PlayerSlot<Phys>, PlayerSlot<Phys>> {
        val a = PlayerSlot(0, Phys("A"))
        val b = PlayerSlot(1, Phys("B"))
        return Triple(PlayerSlotTable(a, b), a, b)
    }

    @Test fun firstSlotStartsCurrentSecondStartsNext() {
        val (t, a, b) = table()
        assertSame(a, t.current)
        assertSame(b, t.next)
        assertEquals(PlayerSlotRole.CURRENT, t.roleOf(a))
        assertEquals(PlayerSlotRole.NEXT, t.roleOf(b))
    }

    @Test fun swapExchangesRolesButNotSlotIdentity() {
        val (t, a, b) = table()
        t.swapRoles()
        assertSame(b, t.current)
        assertSame(a, t.next)
        assertEquals(0, a.id)
        assertEquals(1, b.id)
        assertEquals("A", a.player.name)
        t.swapRoles()
        assertSame(a, t.current)
    }

    @Test fun unknownSlotHasNoRole() {
        val (t, _, _) = table()
        assertNull(t.roleOf(PlayerSlot(2, Phys("C"))))
    }

    @Test fun rejectsSamePlayerInTwoSlots() {
        val p = Phys("A")
        try {
            PlayerSlotTable(PlayerSlot(0, p), PlayerSlot(1, p))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun rejectsDuplicateSlotIds() {
        try {
            PlayerSlotTable(PlayerSlot(0, Phys("A")), PlayerSlot(0, Phys("B")))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
