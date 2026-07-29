package com.launchpoint.wavdrop.playback

/**
 * Exported PlaybackService start commands must not actuate app-private reconnect
 * behavior. Reconnect events are handled by the non-exported receiver in-process.
 */
object PlaybackServiceStartCommandPolicy {
    fun reconnectOutputKindForStartCommand(action: String?, outputKind: String?): String? = null
}
