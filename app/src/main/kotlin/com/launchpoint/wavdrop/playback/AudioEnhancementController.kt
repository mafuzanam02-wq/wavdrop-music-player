package com.launchpoint.wavdrop.playback

import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.settings.AudioEnhancementSettings
import com.launchpoint.wavdrop.data.settings.AudioEnhancementsRepository
import com.launchpoint.wavdrop.data.settings.EqualizerCapabilities
import com.launchpoint.wavdrop.data.settings.EqualizerPlatformPreset
import com.launchpoint.wavdrop.data.settings.EqPresetType
import com.launchpoint.wavdrop.data.settings.WavdropPresetCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the Android Equalizer effect attached to ExoPlayer's audio session.
 * Driven entirely by [AudioEnhancementsRepository] — no UI component controls this directly.
 *
 * Lifecycle contract enforced by PlaybackService:
 *   1. attach(player)                  — after ExoPlayer.build(), starts observing settings
 *   2. onAudioSessionIdChanged(id)     — forwarded from Player.Listener
 *   3. release()                       — in onDestroy(), BEFORE player.release()
 *
 * Safety rules:
 *   - EQ is only created when enabled AND session ID is valid (> 0).
 *   - Every AudioEffect and Equalizer call is wrapped in try/catch(RuntimeException).
 *   - A failure logs and continues; it never propagates to the caller.
 *   - release() is idempotent.
 */
internal class AudioEnhancementController(
    private val repository: AudioEnhancementsRepository,
    private val scope: CoroutineScope,
) {
    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = 0
    private var isReleased: Boolean = false

    // Cached latest settings; initialised to defaults (EQ disabled) so that
    // onAudioSessionIdChanged arriving before the first Flow emission is safe.
    private var cachedSettings: AudioEnhancementSettings = AudioEnhancementSettings()

    // The settings that were last fully applied to the Equalizer.
    // Null means nothing has been applied yet (or the Equalizer was released).
    // Cleared in releaseEqualizer() so that a session change forces a full reapply.
    private var lastAppliedSettings: AudioEnhancementSettings? = null

    // Capabilities — populated once on first successful Equalizer construction.
    private var bandCount: Int = 0
    private var bandLevelRange: Pair<Short, Short> = 0.toShort() to 0.toShort()
    private var platformPresets: List<String> = emptyList()

    // ── Public lifecycle ──────────────────────────────────────────────────────

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun attach(player: ExoPlayer) {
        currentSessionId = player.audioSessionId
        if (BuildConfig.DEBUG) Log.d(TAG, "[attach] sessionId=$currentSessionId")

        // Collect settings and re-apply on every change.
        // The first emission drives the initial EQ state (default: disabled).
        scope.launch {
            repository.settings.collect { settings ->
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "[settings] emission received: eqEnabled=${settings.eqEnabled}" +
                            " preset=${settings.eqPresetType}" +
                            " wavdropId=${settings.eqWavdropPresetId}" +
                            " platformIndex=${settings.eqPlatformPresetIndex}" +
                            " customLevels.size=${settings.eqCustomBandLevels.size}",
                    )
                }
                cachedSettings = settings
                applySettings(settings)
            }
        }
    }

    fun onAudioSessionIdChanged(newSessionId: Int) {
        if (isReleased) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[sessionChanged] already released — ignoring id=$newSessionId")
            return
        }
        if (newSessionId == currentSessionId) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[sessionChanged] same ID=$newSessionId — no-op")
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[sessionChanged] new=$newSessionId current=$currentSessionId")
        releaseEqualizer()
        currentSessionId = newSessionId
        if (newSessionId > 0) {
            applySettings(cachedSettings)
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "[sessionChanged] new ID invalid/zero — EQ deferred")
        }
    }

    fun release() {
        if (isReleased) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[release] already released — no-op")
            return
        }
        isReleased = true
        if (BuildConfig.DEBUG) Log.d(TAG, "[release] releasing controller sessionId=$currentSessionId")
        releaseEqualizer()
    }

    // ── Settings application ──────────────────────────────────────────────────

    private fun applySettings(settings: AudioEnhancementSettings) {
        if (isReleased) return

        if (!settings.eqEnabled) {
            // No-op if EQ is already absent — avoids repeated release calls on periodic
            // DataStore ticks where the disabled setting re-emits unchanged.
            if (equalizer == null) return
            if (BuildConfig.DEBUG) Log.d(TAG, "[apply] EQ disabled by settings — releasing active EQ")
            releaseEqualizer()
            return
        }

        if (currentSessionId <= 0) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[apply] EQ enabled but sessionId=$currentSessionId invalid — deferring")
            return
        }

        // Ensure an Equalizer instance exists.
        if (equalizer == null) {
            tryCreateEqualizer(currentSessionId)
            if (equalizer == null) return  // creation failed, already logged
        }

        // Skip reapplication when the Equalizer is active and settings are unchanged.
        // This guards against session-ID-triggered reapply paths with identical settings.
        if (settings == lastAppliedSettings) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[apply] settings unchanged and EQ active — skipping reapplication")
            return
        }

        val eq = equalizer ?: return

        // Apply the desired preset to the bands; capture a debug summary if in DEBUG mode.
        val debugSummary = applyPreset(eq, settings)

        // Enable the effect.
        val enableResult = try {
            eq.setEnabled(true)
        } catch (e: RuntimeException) {
            Log.e(TAG, "[apply] setEnabled(true) threw — releasing EQ: ${e.message}", e)
            releaseEqualizer()
            return
        }
        val actuallyEnabled = try { eq.enabled } catch (e: RuntimeException) { false }

        lastAppliedSettings = settings

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "[apply] EQ active: $debugSummary" +
                    " setEnabled=$enableResult enabled=$actuallyEnabled" +
                    " sessionId=$currentSessionId",
            )
        }
    }

    // Applies the active preset to [eq] and returns a debug summary string for the [apply] log.
    // The summary is produced by the pure buildEqApplyDebugSummary() helper; this method only
    // adds the Equalizer side-effects (applyFlat / applyBandLevels / usePreset) and the
    // platform readback needed to pass back to the helper.
    private fun applyPreset(eq: Equalizer, settings: AudioEnhancementSettings): String {
        when (settings.eqPresetType) {
            EqPresetType.FLAT -> applyFlat(eq)

            EqPresetType.WAVDROP_PRESET -> {
                val id = settings.eqWavdropPresetId
                val preset = WavdropPresetCatalog.findById(id)
                if (preset == null) {
                    Log.w(TAG, "[applyPreset] WAVDROP_PRESET id=$id not found in catalog — flat fallback")
                    applyFlat(eq)
                } else if (preset.bandLevels.size != bandCount) {
                    Log.w(TAG, "[applyPreset] WAVDROP_PRESET '${preset.id}' size mismatch levels=${preset.bandLevels.size} != bands=$bandCount — flat fallback")
                    applyFlat(eq)
                } else {
                    applyBandLevels(eq, preset.bandLevels, "WAVDROP '${preset.id}'")
                }
            }

            EqPresetType.PLATFORM -> {
                val index = settings.eqPlatformPresetIndex
                if (index != null && index >= 0 && index < platformPresets.size) {
                    try {
                        eq.usePreset(index.toShort())
                        if (BuildConfig.DEBUG) {
                            val name     = platformPresets.getOrNull(index) ?: "unknown"
                            val readback = (0 until bandCount).map { b ->
                                try { eq.getBandLevel(b.toShort()).toInt() } catch (e: RuntimeException) { Int.MIN_VALUE }
                            }
                            val confirmedPreset = try { eq.currentPreset.toInt() } catch (e: RuntimeException) { -1 }
                            return buildEqApplyDebugSummary(settings, bandCount, platformName = name, platformReadback = readback) +
                                " confirmedIndex=$confirmedPreset"
                        }
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "[applyPreset] usePreset($index) threw — falling back to flat: ${e.message}", e)
                        applyFlat(eq)
                    }
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[applyPreset] PLATFORM invalid index=$index presets=${platformPresets.size} — falling back to flat")
                    }
                    applyFlat(eq)
                }
            }

            EqPresetType.CUSTOM -> {
                val levels = settings.eqCustomBandLevels
                if (levels.size != bandCount) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[applyPreset] CUSTOM size mismatch: saved=${levels.size} bands=$bandCount — falling back to flat")
                    }
                    applyFlat(eq)
                } else {
                    applyBandLevels(eq, levels, "CUSTOM")
                }
            }
        }
        val platformName = if (settings.eqPresetType == EqPresetType.PLATFORM) {
            platformPresets.getOrNull(settings.eqPlatformPresetIndex ?: -1)
        } else null
        return buildEqApplyDebugSummary(settings, bandCount, platformName = platformName)
    }

    // Clamps each level to the device's band level range and writes it via setBandLevel.
    // Used by both WAVDROP_PRESET and CUSTOM branches to avoid duplicating the loop.
    private fun applyBandLevels(eq: Equalizer, levels: List<Int>, sourceLabel: String) {
        val (min, max) = bandLevelRange
        var errors = 0
        for (i in levels.indices) {
            val clamped = levels[i].coerceIn(min.toInt(), max.toInt()).toShort()
            try {
                eq.setBandLevel(i.toShort(), clamped)
            } catch (e: RuntimeException) {
                errors++
                if (BuildConfig.DEBUG) Log.d(TAG, "[applyPreset] $sourceLabel setBandLevel($i) threw: ${e.message}")
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[applyPreset] $sourceLabel applied bands=$bandCount errors=$errors")
    }

    private fun applyFlat(eq: Equalizer) {
        var errors = 0
        for (i in 0 until bandCount) {
            try {
                eq.setBandLevel(i.toShort(), 0)
            } catch (e: RuntimeException) {
                errors++
                if (BuildConfig.DEBUG) Log.d(TAG, "[applyFlat] band $i threw: ${e.message}")
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[applyFlat] bands=$bandCount errors=$errors")
    }

    // ── Equalizer creation ────────────────────────────────────────────────────

    private fun tryCreateEqualizer(sessionId: Int) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[create] attempting Equalizer(0, $sessionId)")

        val eq = try {
            Equalizer(0, sessionId)
        } catch (e: RuntimeException) {
            Log.e(TAG, "[create] constructor threw — device may not support effects: ${e.message}", e)
            return
        }

        val bands = try {
            eq.numberOfBands.toInt()
        } catch (e: RuntimeException) {
            Log.e(TAG, "[create] numberOfBands threw: ${e.message}", e)
            eq.safeRelease()
            return
        }
        if (bands <= 0) {
            if (BuildConfig.DEBUG) Log.d(TAG, "[create] bandCount=$bands invalid — aborting")
            eq.safeRelease()
            return
        }

        val range = try {
            eq.bandLevelRange
        } catch (e: RuntimeException) {
            Log.e(TAG, "[create] bandLevelRange threw: ${e.message}", e)
            eq.safeRelease()
            return
        }

        val presetCount = try { eq.numberOfPresets.toInt() } catch (e: RuntimeException) { 0 }
        val presets = (0 until presetCount).mapNotNull { i ->
            try { eq.getPresetName(i.toShort()) } catch (e: RuntimeException) { null }
        }

        val centerFreqsHz = (0 until bands).map { i ->
            try { eq.getCenterFreq(i.toShort()) / 1000 } catch (e: RuntimeException) { 0 }
        }

        // Cache capabilities for preset validation and band clamping.
        bandCount = bands
        bandLevelRange = range[0] to range[1]
        platformPresets = presets

        repository.updateCapabilities(
            EqualizerCapabilities(
                bandCount            = bands,
                minLevelMb           = range[0].toInt(),
                maxLevelMb           = range[1].toInt(),
                centerFrequenciesHz  = centerFreqsHz,
                platformPresets      = presets.mapIndexed { i, name ->
                    EqualizerPlatformPreset(index = i.toShort(), name = name)
                },
            ),
        )

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "[create] capabilities: sessionId=$sessionId" +
                    " bands=$bands" +
                    " range=[${range[0]}..${range[1]}]mB" +
                    " freqsHz=$centerFreqsHz" +
                    " presets=$presetCount $presets",
            )
        }

        equalizer = eq
    }

    // ── Equalizer teardown ────────────────────────────────────────────────────

    private fun releaseEqualizer() {
        val eq = equalizer ?: return
        equalizer = null
        lastAppliedSettings = null  // force full reapply if EQ is recreated (e.g. after session change)
        eq.safeRelease()
        if (BuildConfig.DEBUG) Log.d(TAG, "[releaseEqualizer] released sessionId=$currentSessionId")
    }

    private fun Equalizer.safeRelease() {
        try {
            release()
        } catch (e: RuntimeException) {
            Log.e(TAG, "[safeRelease] release() threw: ${e.message}", e)
        }
    }

    private companion object {
        private const val TAG = "WavdropAudioEffects"
    }
}
