package com.launchpoint.wavdrop.playback

import android.util.Log

/** A crossfade gain must be finite and within 0..1; ownership layers never clamp, they fail closed. */
internal fun isValidCrossfadeGain(gain: Float): Boolean = gain.isFinite() && gain in 0f..1f

/**
 * Narrow seam over the primary player's crossfade gain (its volume). Not a generic player abstraction.
 * Contract: [gain] is finite within 0..1; true means it was safely applied, false that it could not be
 * trusted/applied. Implementations must not throw through the crossfade runtime.
 */
internal fun interface PrimaryGainBackend {
    fun setGain(gain: Float): Boolean

    /** Used when no primary seam is wired: never pretends a gain was applied. */
    object Unavailable : PrimaryGainBackend {
        override fun setGain(gain: Float): Boolean = false
    }
}

/**
 * CF-2C6: occurrence-owned primary gain. At most one [CrossfadeTransitionKey] owns the primary gain; another key
 * can neither change nor restore it (song ids play no role). Ownership is claimed BEFORE the backend is touched
 * and is kept when a write fails, so a partially applied lowered volume always has a restoration path. Ownership
 * is released only by a successful restore to 1f. Main-thread confined.
 */
internal class CrossfadePrimaryGainController(private val backend: PrimaryGainBackend) {
    var ownerKey: CrossfadeTransitionKey? = null
        private set

    /** Applies [gain] for [key]. False for an invalid gain, a different current owner, or a failed backend write. */
    fun apply(key: CrossfadeTransitionKey, gain: Float): Boolean {
        if (!isValidCrossfadeGain(gain)) return false
        val owner = ownerKey
        if (owner != null && owner != key) return false
        ownerKey = key // claim first: a failed/partial write must stay restorable
        return write(gain)
    }

    /** True when nothing needs restoring or [key]'s restore to 1f succeeded; false for a stale key or a failure. */
    fun restore(key: CrossfadeTransitionKey): Boolean {
        val owner = ownerKey ?: return true
        if (owner != key) return false
        if (!write(1f)) return false // owner retained: gain state is uncertain
        ownerKey = null
        return true
    }

    private fun write(gain: Float): Boolean = try {
        backend.setGain(gain)
    } catch (e: Exception) {
        Log.w(TAG, "primary crossfade gain write failed", e)
        false
    }

    private companion object {
        const val TAG = "WavdropCrossfade"
    }
}
