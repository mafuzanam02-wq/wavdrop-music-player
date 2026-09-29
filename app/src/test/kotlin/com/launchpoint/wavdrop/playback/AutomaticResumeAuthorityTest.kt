package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.junit.Test

class AutomaticResumeAuthorityTest {
    private val authority = AutomaticResumeAuthority()

    private fun authorize(
        token: Long,
        routeConnected: Boolean = true,
        captured: Long = 5L,
        current: Long = 5L,
        bumps: Int = 0,
    ) = authority.authorizePlay(token, routeConnected, captured, current, bumps)

    @Test
    fun `fresh request is authorized`() {
        val token = authority.begin()
        assertTrue(authority.isCurrent(token))
        assertNull(authority.invalidationOf(token))
        assertEquals(PlayAuthorization.ALLOWED, authorize(token))
    }

    @Test
    fun `manual pause invalidates pending automatic resume`() {
        val token = authority.begin()
        authority.supersedeByUser()
        assertEquals(PlayAuthorization.STALE, authorize(token))
        assertEquals(ResumeInvalidation.USER_TRANSPORT, authority.invalidationOf(token))
    }

    @Test
    fun `explicit play supersedes pending request and does not block later requests`() {
        val old = authority.begin()
        authority.supersedeByUser()
        assertEquals(PlayAuthorization.STALE, authorize(old))
        val next = authority.begin()
        assertEquals(PlayAuthorization.ALLOWED, authorize(next))
        assertEquals(ResumeInvalidation.USER_TRANSPORT, authority.invalidationOf(old))
    }

    @Test
    fun `request A becomes stale after request B and B owns authority`() {
        val a = authority.begin()
        val b = authority.begin()
        assertEquals(PlayAuthorization.STALE, authorize(a))
        assertEquals(ResumeInvalidation.NEWER_REQUEST, authority.invalidationOf(a))
        assertEquals(PlayAuthorization.ALLOWED, authorize(b))
    }

    @Test
    fun `duplicate events leave at most one current authority`() {
        val tokens = List(5) { authority.begin() }
        assertEquals(1, tokens.count { authority.isCurrent(it) })
        assertTrue(authority.isCurrent(tokens.last()))
    }

    @Test
    fun `route disappearing before play denies an otherwise current request`() {
        val token = authority.begin()
        assertEquals(PlayAuthorization.ROUTE_LOST, authorize(token, routeConnected = false))
    }

    @Test
    fun `route loss event voids pending request`() {
        val token = authority.begin()
        authority.supersedeByRouteLoss()
        assertEquals(PlayAuthorization.STALE, authorize(token))
        assertEquals(ResumeInvalidation.ROUTE_LOST, authority.invalidationOf(token))
    }

    @Test
    fun `late delayed retry from stale request cannot play`() {
        val token = authority.begin()
        assertEquals(PlayAuthorization.ALLOWED, authorize(token)) // initial play
        authority.supersedeByUser() // user pauses during the 700 ms wait
        assertEquals(PlayAuthorization.STALE, authorize(token)) // retry check
    }

    @Test
    fun `retry is denied when route vanishes during the delay`() {
        val token = authority.begin()
        assertEquals(PlayAuthorization.ALLOWED, authorize(token))
        assertEquals(PlayAuthorization.ROUTE_LOST, authorize(token, routeConnected = false))
    }

    @Test
    fun `unknown and zero tokens never authorize`() {
        assertEquals(PlayAuthorization.STALE, authorize(0L))
        authority.begin()
        assertEquals(PlayAuthorization.STALE, authorize(99L))
    }

    @Test
    fun `queue mutation denies stale occurrence based request`() {
        val token = authority.begin()
        assertEquals(PlayAuthorization.QUEUE_CHANGED, authorize(token, captured = 5L, current = 6L))
    }

    @Test
    fun `hydration may bump queue generation exactly once`() {
        val token = authority.begin()
        assertEquals(PlayAuthorization.ALLOWED, authorize(token, captured = 5L, current = 6L, bumps = 1))
        assertEquals(PlayAuthorization.QUEUE_CHANGED, authorize(token, captured = 5L, current = 7L, bumps = 1))
        assertEquals(PlayAuthorization.QUEUE_CHANGED, authorize(token, captured = 5L, current = 5L, bumps = 1))
    }

    @Test
    fun `user supersession wins over later route loss for reason reporting`() {
        val token = authority.begin()
        authority.supersedeByUser()
        authority.supersedeByRouteLoss()
        assertEquals(ResumeInvalidation.USER_TRANSPORT, authority.invalidationOf(token))
    }

    // --- Entitlement consumption ---------------------------------------------------------------

    @Test
    fun `definitive outcomes consume entitlement`() {
        listOf(
            AutomaticResumeOutcome.PLAY_ISSUED,
            AutomaticResumeOutcome.SKIPPED_BY_SETTING,
            AutomaticResumeOutcome.NO_SESSION,
            AutomaticResumeOutcome.ALREADY_PLAYING,
            AutomaticResumeOutcome.SUPERSEDED_BY_USER,
        ).forEach { assertTrue("$it", BluetoothEntitlementPolicy.shouldConsume(it)) }
    }

    @Test
    fun `transient outcomes keep entitlement retryable`() {
        listOf(
            AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE,
            AutomaticResumeOutcome.SETUP_FAILED,
            AutomaticResumeOutcome.ROUTE_LOST,
            AutomaticResumeOutcome.QUEUE_CHANGED,
            AutomaticResumeOutcome.SUPERSEDED_BY_NEWER_REQUEST,
        ).forEach { assertFalse("$it", BluetoothEntitlementPolicy.shouldConsume(it)) }
    }

    @Test
    fun `denial maps to matching outcome`() {
        assertEquals(
            AutomaticResumeOutcome.SUPERSEDED_BY_USER,
            BluetoothEntitlementPolicy.outcomeFor(PlayAuthorization.STALE, ResumeInvalidation.USER_TRANSPORT),
        )
        assertEquals(
            AutomaticResumeOutcome.SUPERSEDED_BY_NEWER_REQUEST,
            BluetoothEntitlementPolicy.outcomeFor(PlayAuthorization.STALE, ResumeInvalidation.NEWER_REQUEST),
        )
        assertEquals(
            AutomaticResumeOutcome.ROUTE_LOST,
            BluetoothEntitlementPolicy.outcomeFor(PlayAuthorization.ROUTE_LOST, null),
        )
        assertEquals(
            AutomaticResumeOutcome.QUEUE_CHANGED,
            BluetoothEntitlementPolicy.outcomeFor(PlayAuthorization.QUEUE_CHANGED, null),
        )
    }

    @Test
    fun `interrupted-only mode without entitlement does not resume`() {
        assertFalse(HeadphoneResumeMode.RESUME_IF_INTERRUPTED.shouldResume(false))
        assertTrue(HeadphoneResumeMode.RESUME_IF_INTERRUPTED.shouldResume(true))
    }

    @Test
    fun `entitlement survives a transient failure then is consumed by the successful retry`() {
        var pending = true
        fun finish(outcome: AutomaticResumeOutcome) {
            if (BluetoothEntitlementPolicy.shouldConsume(outcome)) pending = false
        }
        finish(AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE)
        assertTrue(pending)
        finish(AutomaticResumeOutcome.PLAY_ISSUED)
        assertFalse(pending)
    }

    @Test
    fun `already playing reconnect is a definitive no-op that issues no play`() {
        // ALREADY_PLAYING is returned before any authorize/play step, and consumes deliberately.
        assertTrue(BluetoothEntitlementPolicy.shouldConsume(AutomaticResumeOutcome.ALREADY_PLAYING))
    }

    @Test
    fun `permission denied gate still blocks automatic resume`() {
        assertFalse(BluetoothReconnectPermissionPolicy.canMonitorProfileConnections(31, permissionGranted = false))
    }

    // --- External transport at the Player boundary -----------------------------------------------

    /** Mirrors PreviousBehaviorPlayer.play()/pause() -> PlayerController.onExplicitExternalTransport. */
    private fun playerTransportCall(hasController: Boolean, isAppController: Boolean) {
        if (ExternalTransportPolicy.isExternalUserController(hasController, isAppController)) {
            authority.supersedeByUser()
        }
    }

    @Test
    fun `external controller is external user transport for play and pause alike`() {
        assertTrue(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = false))
    }

    @Test
    fun `app controller is not external`() {
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = true, isAppController = true))
    }

    @Test
    fun `call without a session controller is not external`() {
        assertFalse(ExternalTransportPolicy.isExternalUserController(hasController = false, isAppController = false))
    }

    @Test
    fun `external pause before initial automatic play denies the request`() {
        val token = authority.begin()
        playerTransportCall(hasController = true, isAppController = false)
        assertEquals(PlayAuthorization.STALE, authorize(token))
    }

    @Test
    fun `external pause during retry window blocks the delayed retry`() {
        val token = authority.begin()
        assertEquals(PlayAuthorization.ALLOWED, authorize(token))
        playerTransportCall(hasController = true, isAppController = false)
        assertEquals(PlayAuthorization.STALE, authorize(token))
    }

    @Test
    fun `external play supersedes request and a later request is still authorized`() {
        val token = authority.begin()
        playerTransportCall(hasController = true, isAppController = false)
        assertEquals(PlayAuthorization.STALE, authorize(token))
        assertEquals(PlayAuthorization.ALLOWED, authorize(authority.begin()))
    }

    @Test
    fun `automatic play from the app controller does not invalidate its own token`() {
        val token = authority.begin()
        playerTransportCall(hasController = true, isAppController = true)
        assertTrue(authority.isCurrent(token))
        assertEquals(PlayAuthorization.ALLOWED, authorize(token))
    }

    @Test
    fun `app controller hint key is stable`() {
        assertEquals("com.launchpoint.wavdrop.APP_CONTROLLER", ExternalTransportPolicy.APP_CONTROLLER_HINT)
    }

    // --- Entitlement settlement ------------------------------------------------------------------

    private fun settle(token: Long, raw: AutomaticResumeOutcome) =
        BluetoothEntitlementPolicy.settle(raw, authority.invalidationOf(token))

    @Test
    fun `route loss after initial play settles to ROUTE_LOST and keeps entitlement`() {
        val a = authority.begin()
        assertEquals(PlayAuthorization.ALLOWED, authorize(a))
        authority.supersedeByRouteLoss()
        val final = settle(a, AutomaticResumeOutcome.PLAY_ISSUED)
        assertEquals(AutomaticResumeOutcome.ROUTE_LOST, final)
        assertFalse(BluetoothEntitlementPolicy.shouldConsume(final))
    }

    @Test
    fun `old request cannot consume entitlement recorded by a later interruption`() {
        val a = authority.begin()
        authority.supersedeByRouteLoss() // fresh interruption recorded
        assertFalse(BluetoothEntitlementPolicy.shouldConsume(settle(a, AutomaticResumeOutcome.PLAY_ISSUED)))
        assertFalse(BluetoothEntitlementPolicy.shouldConsume(settle(a, AutomaticResumeOutcome.ALREADY_PLAYING)))
    }

    @Test
    fun `user pause after initial play settles to SUPERSEDED_BY_USER and retry cannot play`() {
        val a = authority.begin()
        assertEquals(PlayAuthorization.ALLOWED, authorize(a))
        playerTransportCall(hasController = true, isAppController = false)
        val final = settle(a, AutomaticResumeOutcome.PLAY_ISSUED)
        assertEquals(AutomaticResumeOutcome.SUPERSEDED_BY_USER, final)
        assertTrue(BluetoothEntitlementPolicy.shouldConsume(final))
        assertEquals(PlayAuthorization.STALE, authorize(a))
    }

    @Test
    fun `superseded older request does not consume and newer request stays authoritative`() {
        val a = authority.begin()
        val b = authority.begin()
        val finalA = settle(a, AutomaticResumeOutcome.PLAY_ISSUED)
        assertEquals(AutomaticResumeOutcome.SUPERSEDED_BY_NEWER_REQUEST, finalA)
        assertFalse(BluetoothEntitlementPolicy.shouldConsume(finalA))
        assertTrue(authority.isCurrent(b))
        assertTrue(BluetoothEntitlementPolicy.shouldConsume(settle(b, AutomaticResumeOutcome.PLAY_ISSUED)))
    }

    @Test
    fun `uninvalidated request keeps its raw outcome`() {
        val a = authority.begin()
        assertEquals(AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE, settle(a, AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE))
        assertEquals(AutomaticResumeOutcome.PLAY_ISSUED, settle(a, AutomaticResumeOutcome.PLAY_ISSUED))
    }

    // --- Entitlement write ownership -------------------------------------------------------------

    /**
     * Models consumeBluetoothEntitlement: write false, run [duringClear] (events landing while the
     * write is suspended), then reconcile exactly like PlayerController. Returns final pending.
     */
    private fun consumeWith(token: Long, initialPending: Boolean = true, duringClear: () -> Unit = {}): Boolean {
        var pending = initialPending
        pending = false // suspending write in flight
        duringClear()
        if (BluetoothEntitlementPolicy.shouldRestoreAfterClear(authority.invalidationOf(token))) pending = true
        return pending
    }

    @Test
    fun `route loss during clear leaves entitlement pending`() {
        val a = authority.begin()
        assertTrue(consumeWith(a) { authority.supersedeByRouteLoss() })
    }

    @Test
    fun `newer request during clear leaves entitlement pending`() {
        val a = authority.begin()
        assertTrue(consumeWith(a) { authority.begin() })
    }

    @Test
    fun `explicit user transport during clear leaves entitlement cleared`() {
        val a = authority.begin()
        assertFalse(consumeWith(a) { authority.supersedeByUser() })
    }

    @Test
    fun `clear with no invalidation leaves entitlement cleared`() {
        val a = authority.begin()
        assertFalse(consumeWith(a))
    }

    @Test
    fun `user takeover after route loss during clear still reports user`() {
        val a = authority.begin()
        assertFalse(consumeWith(a) { authority.supersedeByRouteLoss(); authority.supersedeByUser() })
    }

    // --- Serialized attempts ----------------------------------------------------------------------

    /**
     * Model of resumeForBluetooth: begin() outside the lock, then attempt+settlement inside it.
     * [body] runs inside the critical section after a gate; it returns the raw outcome.
     */
    private class Harness(val authority: AutomaticResumeAuthority) {
        val mutex = kotlinx.coroutines.sync.Mutex()
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var pending = true

        fun launch(
            scope: kotlinx.coroutines.CoroutineScope,
            name: String,
            entered: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred(),
            gate: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred<Unit>().also { it.complete(Unit) },
            raw: AutomaticResumeOutcome = AutomaticResumeOutcome.PLAY_ISSUED,
        ): Pair<Long, kotlinx.coroutines.Job> {
            val token = authority.begin() // outside the mutex
            val job = scope.launch {
                mutex.withLock {
                    log += "$name:enter"
                    entered.complete(Unit)
                    gate.await() // suspension point (e.g. the DataStore write)
                    val outcome = BluetoothEntitlementPolicy.settle(raw, authority.invalidationOf(token))
                    if (BluetoothEntitlementPolicy.shouldConsume(outcome)) {
                        pending = false
                        if (BluetoothEntitlementPolicy.shouldRestoreAfterClear(authority.invalidationOf(token))) {
                            pending = true
                        }
                    }
                    log += "$name:settled:$outcome"
                }
            }
            return token to job
        }
    }

    @Test
    fun `newer request cannot settle before older request exits the critical section`() = kotlinx.coroutines.runBlocking {
        val h = Harness(authority)
        val aEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (tokenA, jobA) = h.launch(this, "A", entered = aEntered, gate = gateA)
        aEntered.await()
        val (tokenB, jobB) = h.launch(this, "B")
        assertEquals(ResumeInvalidation.NEWER_REQUEST, authority.invalidationOf(tokenA)) // immediate
        assertTrue(authority.isCurrent(tokenB))
        kotlinx.coroutines.yield()
        assertEquals(listOf("A:enter"), h.log.toList()) // B is waiting
        gateA.complete(Unit)
        jobA.join(); jobB.join()
        assertEquals(
            listOf("A:enter", "A:settled:SUPERSEDED_BY_NEWER_REQUEST", "B:enter", "B:settled:PLAY_ISSUED"),
            h.log.toList(),
        )
    }

    @Test
    fun `older request cannot restore entitlement after newer request completed`() = kotlinx.coroutines.runBlocking {
        val h = Harness(authority)
        val aEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (_, jobA) = h.launch(this, "A", entered = aEntered, gate = gateA)
        aEntered.await()
        val (_, jobB) = h.launch(this, "B")
        gateA.complete(Unit)
        jobA.join(); jobB.join()
        // B, the last mutator, consumed it; A's restore ran strictly before B.
        assertFalse(h.pending)
        assertTrue(h.log.indexOf("A:settled:SUPERSEDED_BY_NEWER_REQUEST") < h.log.indexOf("B:enter"))
    }

    @Test
    fun `B consumes entitlement that A left pending`() = kotlinx.coroutines.runBlocking {
        val h = Harness(authority)
        val aEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (_, jobA) = h.launch(this, "A", entered = aEntered, gate = gateA)
        aEntered.await()
        val (_, jobB) = h.launch(this, "B", raw = AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE)
        gateA.complete(Unit)
        jobA.join(); jobB.join()
        assertTrue(h.pending) // A restored/preserved; B's transient failure keeps it
    }

    @Test
    fun `route loss while A holds the lock preserves entitlement`() = kotlinx.coroutines.runBlocking {
        val h = Harness(authority)
        val aEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (tokenA, jobA) = h.launch(this, "A", entered = aEntered, gate = gateA)
        aEntered.await()
        authority.supersedeByRouteLoss() // immediate, not blocked by the mutex
        assertEquals(PlayAuthorization.STALE, authority.authorizePlay(tokenA, true, 5L, 5L)) // no retry play
        gateA.complete(Unit)
        jobA.join()
        assertTrue(h.pending)
        assertTrue(h.log.contains("A:settled:ROUTE_LOST"))
    }

    @Test
    fun `user takeover while A holds the lock is immediate and clears entitlement`() = kotlinx.coroutines.runBlocking {
        val h = Harness(authority)
        val aEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (tokenA, jobA) = h.launch(this, "A", entered = aEntered, gate = gateA)
        aEntered.await()
        authority.supersedeByUser() // not blocked by the mutex
        assertEquals(ResumeInvalidation.USER_TRANSPORT, authority.invalidationOf(tokenA))
        gateA.complete(Unit)
        jobA.join()
        assertFalse(h.pending)
        assertTrue(h.log.contains("A:settled:SUPERSEDED_BY_USER"))
    }
}
