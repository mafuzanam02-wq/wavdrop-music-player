package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioDetectorTest {

    // ── Bluetooth types ──────────────────────────────────────────────────────────

    @Test
    fun `TYPE_BLUETOOTH_A2DP (8) is bluetooth audio`() {
        assertTrue(BluetoothAudioDetector.isBluetoothAudioType(8))
    }

    @Test
    fun `TYPE_BLUETOOTH_SCO (7) is bluetooth audio`() {
        assertTrue(BluetoothAudioDetector.isBluetoothAudioType(7))
    }

    @Test
    fun `TYPE_BLE_HEADSET (26) is bluetooth audio`() {
        assertTrue(BluetoothAudioDetector.isBluetoothAudioType(26))
    }

    @Test
    fun `TYPE_BLE_SPEAKER (27) is bluetooth audio`() {
        assertTrue(BluetoothAudioDetector.isBluetoothAudioType(27))
    }

    // ── Non-Bluetooth types ──────────────────────────────────────────────────────

    @Test
    fun `TYPE_WIRED_HEADSET (3) is not bluetooth audio`() {
        assertFalse(BluetoothAudioDetector.isBluetoothAudioType(3))
    }

    @Test
    fun `TYPE_WIRED_HEADPHONES (4) is not bluetooth audio`() {
        assertFalse(BluetoothAudioDetector.isBluetoothAudioType(4))
    }

    @Test
    fun `TYPE_BUILTIN_SPEAKER (2) is not bluetooth audio`() {
        assertFalse(BluetoothAudioDetector.isBluetoothAudioType(2))
    }

    @Test
    fun `TYPE_BUILTIN_EARPIECE (1) is not bluetooth audio`() {
        assertFalse(BluetoothAudioDetector.isBluetoothAudioType(1))
    }

    @Test
    fun `TYPE_USB_DEVICE (11) is not bluetooth audio`() {
        assertFalse(BluetoothAudioDetector.isBluetoothAudioType(11))
    }

    @Test
    fun `unknown type 0 is not bluetooth audio`() {
        assertFalse(BluetoothAudioDetector.isBluetoothAudioType(0))
    }

    @Test
    fun `unknown type 99 is not bluetooth audio`() {
        assertFalse(BluetoothAudioDetector.isBluetoothAudioType(99))
    }
}
