package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode

internal object BluetoothReconnectPermissionPolicy {
    private const val ANDROID_12_API = 31

    fun requiresBluetoothConnect(sdkInt: Int): Boolean = sdkInt >= ANDROID_12_API

    fun canMonitorProfileConnections(sdkInt: Int, permissionGranted: Boolean): Boolean =
        !requiresBluetoothConnect(sdkInt) || permissionGranted

    fun shouldRequest(
        sdkInt: Int,
        permissionGranted: Boolean,
        requestedMode: HeadphoneResumeMode,
    ): Boolean =
        requestedMode != HeadphoneResumeMode.OFF &&
            !canMonitorProfileConnections(sdkInt, permissionGranted)
}

internal object ConnectionResumePolicy {
    fun shouldAttempt(
        rememberLastTrack: Boolean,
        mode: HeadphoneResumeMode,
        interruptedPending: Boolean,
        hasSavedSession: Boolean,
    ): Boolean =
        rememberLastTrack &&
            hasSavedSession &&
            mode.shouldResume(interruptedPending)
}

internal object BluetoothResumeDebounce {
    fun shouldAttempt(lastAttemptAtMs: Long, nowMs: Long, windowMs: Long): Boolean =
        lastAttemptAtMs <= 0L ||
            nowMs < lastAttemptAtMs ||
            nowMs - lastAttemptAtMs >= windowMs
}
