package com.launchpoint.wavdrop.data.settings

data class ResumeBehaviorSettings(
    val pauseOnAudioDisconnect: Boolean = true,
    val rememberLastTrack: Boolean = true,
    val rememberPosition: Boolean = true,
    val restoreQueue: Boolean = true,
    val bluetoothResumeMode: HeadphoneResumeMode = HeadphoneResumeMode.RESUME_IF_INTERRUPTED,
    val wiredResumeMode: HeadphoneResumeMode = HeadphoneResumeMode.RESUME_IF_INTERRUPTED,
)
