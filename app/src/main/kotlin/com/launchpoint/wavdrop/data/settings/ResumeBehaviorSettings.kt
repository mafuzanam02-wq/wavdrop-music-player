package com.launchpoint.wavdrop.data.settings

data class ResumeBehaviorSettings(
    val pauseOnAudioDisconnect: Boolean = true,
    val rememberLastTrack: Boolean = true,
    val rememberPosition: Boolean = true,
    val restoreQueue: Boolean = true,
    val autoResumeOnHeadphones: Boolean = false,
    val autoResumeOnBluetooth: Boolean = false,
)
