package com.launchpoint.wavdrop.playback

/**
 * Pure helper that classifies AudioDeviceInfo type codes as Bluetooth audio outputs.
 *
 * Integer values mirror android.media.AudioDeviceInfo constants (API 23+).
 * Using literals makes this class testable without robolectric.
 */
internal object BluetoothAudioDetector {
    private const val TYPE_BLUETOOTH_SCO  = 7   // classic BT headset / hands-free
    private const val TYPE_BLUETOOTH_A2DP = 8   // classic BT stereo audio
    private const val TYPE_BLE_HEADSET    = 26  // BLE audio headset (API 31+)
    private const val TYPE_BLE_SPEAKER    = 27  // BLE audio speaker (API 31+)

    fun isBluetoothAudioType(deviceType: Int): Boolean =
        deviceType == TYPE_BLUETOOTH_SCO  ||
        deviceType == TYPE_BLUETOOTH_A2DP ||
        deviceType == TYPE_BLE_HEADSET    ||
        deviceType == TYPE_BLE_SPEAKER
}
