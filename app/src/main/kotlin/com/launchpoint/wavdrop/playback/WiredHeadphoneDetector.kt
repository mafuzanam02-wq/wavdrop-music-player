package com.launchpoint.wavdrop.playback

/**
 * Pure helper that classifies AudioDeviceInfo type codes as wired audio outputs.
 *
 * Integer values mirror android.media.AudioDeviceInfo constants (API 23+).
 * Using literals makes this class testable without robolectric.
 */
internal object WiredHeadphoneDetector {
    private const val TYPE_WIRED_HEADPHONES = 3   // 3.5 mm headphones (output only)
    private const val TYPE_WIRED_HEADSET    = 4   // 3.5 mm headset with microphone
    private const val TYPE_USB_HEADSET      = 22  // USB-C / USB headset (API 26+)
    private const val TYPE_USB_DEVICE       = 11  // Generic USB audio class device

    fun isWiredOutputType(deviceType: Int): Boolean =
        deviceType == TYPE_WIRED_HEADPHONES ||
        deviceType == TYPE_WIRED_HEADSET    ||
        deviceType == TYPE_USB_HEADSET      ||
        deviceType == TYPE_USB_DEVICE
}
