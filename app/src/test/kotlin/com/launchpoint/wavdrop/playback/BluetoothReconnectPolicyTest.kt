package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothReconnectPermissionPolicyTest {
    @Test
    fun `Android before 12 does not require nearby devices permission`() {
        assertFalse(BluetoothReconnectPermissionPolicy.requiresBluetoothConnect(30))
        assertTrue(
            BluetoothReconnectPermissionPolicy.canMonitorProfileConnections(
                sdkInt = 30,
                permissionGranted = false,
            )
        )
    }

    @Test
    fun `Android 12 reconnect monitoring requires granted permission`() {
        assertFalse(
            BluetoothReconnectPermissionPolicy.canMonitorProfileConnections(
                sdkInt = 31,
                permissionGranted = false,
            )
        )
        assertTrue(
            BluetoothReconnectPermissionPolicy.canMonitorProfileConnections(
                sdkInt = 31,
                permissionGranted = true,
            )
        )
    }

    @Test
    fun `permission request is limited to enabled reconnect modes`() {
        assertFalse(
            BluetoothReconnectPermissionPolicy.shouldRequest(
                sdkInt = 36,
                permissionGranted = false,
                requestedMode = HeadphoneResumeMode.OFF,
            )
        )
        assertFalse(
            BluetoothReconnectPermissionPolicy.shouldRequest(
                sdkInt = 36,
                permissionGranted = true,
                requestedMode = HeadphoneResumeMode.ALWAYS_RESUME,
            )
        )
        assertTrue(
            BluetoothReconnectPermissionPolicy.shouldRequest(
                sdkInt = 36,
                permissionGranted = false,
                requestedMode = HeadphoneResumeMode.RESUME_IF_INTERRUPTED,
            )
        )
    }
}

class ConnectionResumePolicyTest {
    @Test
    fun `off never resumes from a connection`() {
        assertFalse(decide(HeadphoneResumeMode.OFF, interrupted = true))
    }

    @Test
    fun `resume if interrupted requires pending interruption`() {
        assertFalse(decide(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = false))
        assertTrue(decide(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true))
    }

    @Test
    fun `always resume requires saved eligible session`() {
        assertTrue(decide(HeadphoneResumeMode.ALWAYS_RESUME))
        assertFalse(decide(HeadphoneResumeMode.ALWAYS_RESUME, hasSavedSession = false))
        assertFalse(decide(HeadphoneResumeMode.ALWAYS_RESUME, rememberLastTrack = false))
    }

    private fun decide(
        mode: HeadphoneResumeMode,
        interrupted: Boolean = false,
        hasSavedSession: Boolean = true,
        rememberLastTrack: Boolean = true,
    ): Boolean = ConnectionResumePolicy.shouldAttempt(
        rememberLastTrack = rememberLastTrack,
        mode = mode,
        interruptedPending = interrupted,
        hasSavedSession = hasSavedSession,
    )
}

class BluetoothResumeDebounceTest {
    @Test
    fun `classic and LE profile bursts produce one attempt inside window`() {
        val windowMs = 1_500L
        var lastAttemptAtMs = 0L
        var attempts = 0

        listOf(10_000L, 10_100L, 10_250L).forEach { nowMs ->
            if (BluetoothResumeDebounce.shouldAttempt(lastAttemptAtMs, nowMs, windowMs)) {
                lastAttemptAtMs = nowMs
                attempts += 1
            }
        }

        assertEquals(1, attempts)
        assertTrue(BluetoothResumeDebounce.shouldAttempt(lastAttemptAtMs, 11_500L, windowMs))
    }
}
