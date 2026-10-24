package com.launchpoint.wavdrop.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure authority primitives behind Slice 3: cold PLAY ownership, shared controller/route gating, media-button policy. */
class ColdPlayAuthorityTest {
    private var generation = 7L
    private val authority = ColdPlayAuthority(queueGeneration = { generation })

    @Test fun `fresh cold play is authorized`() {
        val ticket = authority.begin()
        assertTrue(authority.isCurrent(ticket))
        assertEquals(ColdPlayAuthorization.ALLOWED, authority.authorize(ticket))
    }

    @Test fun `pause supersedes an unfinished cold play immediately`() {
        val ticket = authority.begin()
        authority.supersede()
        assertFalse(authority.isCurrent(ticket))
        assertEquals(ColdPlayAuthorization.SUPERSEDED, authority.authorize(ticket))
    }

    @Test fun `a later explicit play supersedes the pause and plays normally`() {
        val first = authority.begin()
        authority.supersede() // pause
        val second = authority.begin() // later explicit PLAY
        assertEquals(ColdPlayAuthorization.SUPERSEDED, authority.authorize(first))
        assertEquals(ColdPlayAuthorization.ALLOWED, authority.authorize(second))
    }

    @Test fun `only the newest of several cold plays stays authoritative`() {
        val tickets = List(3) { authority.begin() }
        assertEquals(
            listOf(ColdPlayAuthorization.SUPERSEDED, ColdPlayAuthorization.SUPERSEDED, ColdPlayAuthorization.ALLOWED),
            tickets.map(authority::authorize),
        )
    }

    // ---------------------------------------------------------------- hydration STATE vs playback AUTHORITY

    private var hydrations = 0L
    private val hydrating = ColdPlayAuthority(queueGeneration = { generation }, hydrations = { hydrations })

    /** A session hydration by anyone: one generation bump that is counted as a hydration. */
    private fun hydrate() { generation++; hydrations++ }

    /** Anything else that moves the queue: an edit, a replacement. */
    private fun edit() { generation++ }

    @Test fun `a hydration of the saved session serves the newest play on its own authority`() {
        val ticket = hydrating.begin()
        hydrate()
        assertEquals(ColdPlayAuthorization.ALLOWED, hydrating.authorize(ticket))
    }

    @Test fun `an unknown generation change rejects the cold play`() {
        val ticket = hydrating.begin()
        edit()
        assertEquals(ColdPlayAuthorization.QUEUE_CHANGED, hydrating.authorize(ticket))
    }

    @Test fun `a hydration does not excuse an additional edit`() {
        val ticket = hydrating.begin()
        hydrate()
        edit()
        assertEquals(ColdPlayAuthorization.QUEUE_CHANGED, hydrating.authorize(ticket))
    }

    @Test fun `PLAY A then PLAY B then A hydrates - B plays on its own authority and A does not`() {
        val a = hydrating.begin()
        val b = hydrating.begin()
        hydrate()
        assertEquals(ColdPlayAuthorization.SUPERSEDED, hydrating.authorize(a))
        assertEquals(ColdPlayAuthorization.ALLOWED, hydrating.authorize(b))
    }

    @Test fun `PLAY A then PAUSE then PLAY B then A hydrates - B is still authorized, A never is`() {
        val a = hydrating.begin()
        hydrating.supersede() // PAUSE
        val b = hydrating.begin()
        hydrate() // A's already-running hydration lands
        assertEquals(ColdPlayAuthorization.SUPERSEDED, hydrating.authorize(a))
        assertEquals("B's own authority plus valid hydrated state", ColdPlayAuthorization.ALLOWED, hydrating.authorize(b))
    }

    @Test fun `PLAY A then PAUSE then PLAY B then PAUSE then A hydrates - nobody is authorized`() {
        val a = hydrating.begin()
        hydrating.supersede()
        val b = hydrating.begin()
        hydrating.supersede()
        hydrate()
        assertEquals(ColdPlayAuthorization.SUPERSEDED, hydrating.authorize(a))
        assertEquals(ColdPlayAuthorization.SUPERSEDED, hydrating.authorize(b))
    }

    @Test fun `PLAY A then a selection then PLAY B - the selection's queue replacement is not excused`() {
        val a = hydrating.begin()
        hydrating.supersede() // selection
        edit() // its queue replacement
        val b = hydrating.begin() // begins at the selection's generation
        hydrate() // A's late hydration (in production it is refused before mutating; here it is simply a count)
        assertEquals(ColdPlayAuthorization.SUPERSEDED, hydrating.authorize(a))
        assertEquals("B started after the selection; only a hydration moved the queue since", ColdPlayAuthorization.ALLOWED, hydrating.authorize(b))
        edit()
        assertEquals(ColdPlayAuthorization.QUEUE_CHANGED, hydrating.authorize(b))
    }

    @Test fun `PLAY A then navigation then PLAY B then an unrelated edit - rejected`() {
        hydrating.begin()
        hydrating.supersede() // navigation
        val b = hydrating.begin()
        edit()
        hydrate()
        assertEquals(ColdPlayAuthorization.QUEUE_CHANGED, hydrating.authorize(b))
    }

    @Test fun `teardown ends every request and a later request starts from a fresh baseline`() {
        val a = hydrating.begin()
        hydrating.supersede() // PlayerController.release()
        hydrate()
        val later = hydrating.begin()
        assertEquals(ColdPlayAuthorization.SUPERSEDED, hydrating.authorize(a))
        assertEquals(ColdPlayAuthorization.ALLOWED, hydrating.authorize(later))
        edit()
        assertEquals(ColdPlayAuthorization.QUEUE_CHANGED, hydrating.authorize(later))
    }

    @Test fun `PLAY A hydrates then the queue is edited then PLAY B - the hydration does not excuse the edit`() {
        hydrating.begin()
        hydrate()
        edit() // queue edit
        val b = hydrating.begin() // directly supersedes the pending A, so it keeps the run's baseline
        assertEquals(ColdPlayAuthorization.QUEUE_CHANGED, hydrating.authorize(b))
    }

    @Test fun `a completed play ends the run so the next play starts a fresh baseline`() {
        val a = hydrating.begin()
        hydrate()
        hydrating.complete(a)
        edit()
        val b = hydrating.begin()
        assertEquals(ColdPlayAuthorization.ALLOWED, hydrating.authorize(b))
        edit()
        assertEquals(ColdPlayAuthorization.QUEUE_CHANGED, hydrating.authorize(b))
    }

    @Test fun `repeated taps in one uninterrupted run share the baseline and only the newest plays`() {
        val taps = List(5) { hydrating.begin() }
        hydrate()
        assertEquals(
            List(4) { ColdPlayAuthorization.SUPERSEDED } + ColdPlayAuthorization.ALLOWED,
            taps.map(hydrating::authorize),
        )
    }

    @Test fun `the guard element reports whether only hydrations moved the queue`() {
        val ticket = hydrating.begin()
        assertTrue(ticket.ownedHydration.isQueueUndisturbed())
        hydrate()
        assertTrue(ticket.ownedHydration.isQueueUndisturbed())
        edit()
        assertFalse(ticket.ownedHydration.isQueueUndisturbed())
    }

    @Test fun `the guard element rides the coroutine context`() {
        val ticket = hydrating.begin()
        kotlinx.coroutines.runBlocking {
            assertEquals(null, kotlin.coroutines.coroutineContext[OwnedHydration.Key])
            kotlinx.coroutines.withContext(ticket.ownedHydration) {
                assertTrue(kotlin.coroutines.coroutineContext[OwnedHydration.Key] === ticket.ownedHydration)
            }
        }
    }

    @Test fun `supersession outranks a queue change in the verdict`() {
        val ticket = authority.begin()
        generation++
        authority.supersede()
        assertEquals(ColdPlayAuthorization.SUPERSEDED, authority.authorize(ticket))
    }

    @Test fun `a pending cold play makes the desired state playing so the next toggle is a pause`() {
        assertFalse(authority.desiredPlaying(physicallyPlaying = false))
        authority.begin()
        assertTrue(authority.hasPendingPlay)
        assertTrue("hydrating counts as playing", authority.desiredPlaying(physicallyPlaying = false))
        authority.supersede() // the toggle became a PAUSE
        assertFalse(authority.hasPendingPlay)
        assertFalse("the next toggle plays again", authority.desiredPlaying(physicallyPlaying = false))
    }

    @Test fun `completing an older cold play never clears a newer pending one`() {
        val old = authority.begin()
        val newest = authority.begin()
        authority.complete(old)
        assertTrue(authority.hasPendingPlay)
        authority.complete(newest)
        assertFalse(authority.hasPendingPlay)
    }

    @Test fun `physically playing is always desired playing`() {
        assertTrue(authority.desiredPlaying(physicallyPlaying = true))
    }

    // ---------------------------------------------------------------- media-button resolution

    @Test fun `play pause key during a pending cold play resolves to pause`() {
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) {
            assertTrue(MediaButtonPlayPausePolicy.shouldPauseForPendingColdPlay(key, KeyEvent.ACTION_DOWN, 0, true))
        }
    }

    @Test fun `media button policy ignores everything else`() {
        assertFalse("nothing pending", MediaButtonPlayPausePolicy.shouldPauseForPendingColdPlay(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0, false))
        assertFalse("key up", MediaButtonPlayPausePolicy.shouldPauseForPendingColdPlay(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP, 0, true))
        assertFalse("auto repeat", MediaButtonPlayPausePolicy.shouldPauseForPendingColdPlay(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 1, true))
        assertFalse("a plain PLAY key", MediaButtonPlayPausePolicy.shouldPauseForPendingColdPlay(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_DOWN, 0, true))
        assertFalse("NEXT", MediaButtonPlayPausePolicy.shouldPauseForPendingColdPlay(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, 0, true))
    }

    // ---------------------------------------------------------------- shared automatic-resume gate additions

    @Test fun `a replaced controller denies play after the request and route checks`() {
        val resume = AutomaticResumeAuthority()
        val token = resume.begin()
        assertEquals(
            PlayAuthorization.CONTROLLER_REPLACED,
            resume.authorizePlay(token, routeConnected = true, capturedQueueGeneration = 1, currentQueueGeneration = 1, controllerCurrent = false),
        )
        assertEquals(
            "route loss is reported before a controller change",
            PlayAuthorization.ROUTE_LOST,
            resume.authorizePlay(token, routeConnected = false, capturedQueueGeneration = 1, currentQueueGeneration = 1, controllerCurrent = false),
        )
    }

    @Test fun `controller replacement is transient so the entitlement survives`() {
        val outcome = BluetoothEntitlementPolicy.outcomeFor(PlayAuthorization.CONTROLLER_REPLACED, null)
        assertEquals(AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE, outcome)
        assertFalse(BluetoothEntitlementPolicy.shouldConsume(outcome))
    }

    @Test fun `wired and bluetooth authorities are independent instances`() {
        val bluetooth = AutomaticResumeAuthority()
        val wired = AutomaticResumeAuthority()
        val bt = bluetooth.begin()
        val w = wired.begin()
        wired.supersedeByRouteLoss() // wired headset removed
        assertTrue("a wired removal must not void a Bluetooth request", bluetooth.isCurrent(bt))
        assertEquals(ResumeInvalidation.ROUTE_LOST, wired.invalidationOf(w))
        bluetooth.supersedeByUser()
        wired.supersedeByUser()
        assertEquals(ResumeInvalidation.USER_TRANSPORT, bluetooth.invalidationOf(bt))
    }

    @Test fun `a newer wired request supersedes an older one`() {
        val wired = AutomaticResumeAuthority()
        val older = wired.begin()
        val newer = wired.begin()
        assertEquals(ResumeInvalidation.NEWER_REQUEST, wired.invalidationOf(older))
        assertTrue(wired.isCurrent(newer))
    }
}
