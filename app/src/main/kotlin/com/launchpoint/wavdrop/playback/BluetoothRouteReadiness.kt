package com.launchpoint.wavdrop.playback

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal object BluetoothRouteReadiness {
    suspend fun awaitOutput(
        audioManager: AudioManager,
        timeoutMs: Long,
    ): Boolean {
        if (audioManager.hasBluetoothOutput()) return true

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                lateinit var callback: AudioDeviceCallback

                fun complete(ready: Boolean) {
                    if (!completed.compareAndSet(false, true)) return
                    runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
                    if (continuation.isActive) continuation.resume(ready)
                }

                callback = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                        if (addedDevices.any { device ->
                                device.isSink && BluetoothAudioDetector.isBluetoothAudioType(device.type)
                            }
                        ) {
                            complete(true)
                        }
                    }
                }

                continuation.invokeOnCancellation { complete(false) }
                runCatching {
                    audioManager.registerAudioDeviceCallback(
                        callback,
                        Handler(Looper.getMainLooper()),
                    )
                }.onFailure {
                    complete(false)
                    return@suspendCancellableCoroutine
                }

                if (audioManager.hasBluetoothOutput()) complete(true)
            }
        } ?: false
    }

    private fun AudioManager.hasBluetoothOutput(): Boolean = BluetoothOutputQuery.isConnected(this)
}

/** Single current-route query, shared by bounded readiness and the pre-play revalidation. */
internal object BluetoothOutputQuery {
    fun isConnected(audioManager: AudioManager): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.isSink && BluetoothAudioDetector.isBluetoothAudioType(device.type)
        }
}
