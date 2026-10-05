package com.launchpoint.wavdrop.playback

/**
 * Release-readiness EVIDENCE model for the crossfade rollout (satisfied by the CF-2N1 physical sign-off; kept as the fail-closed contract). Pure, dependency-free and never shipped as runtime
 * state: it is not persisted, not a user setting and not a hidden flag. It only names what must be true before
 * [CrossfadeRolloutPolicy.RUNTIME_ENABLED] may be changed from false to true, and keeps automated evidence distinct from
 * physical-device evidence (a green JVM suite is never a substitute for a device run).
 *
 * Release contract (documented, deliberately not enforced by reflection, build hacks, environment variables or runtime
 * flags): `RUNTIME_ENABLED` may only change from false to true after the automated gate has passed and every required
 * physical condition below has been physically verified on the intended release build and device set, with the evidence
 * recorded in the QA checklist.
 */
internal data class CrossfadeRolloutReadiness(
    /** Automated evidence: the full JVM suite is green and the release build assembles. */
    val automatedGatePassed: Boolean,
    /** Physical: core overlap, repeat eligibility, duplicate occurrences and manual-interaction cancellation on a device. */
    val physicalCorePlaybackValidated: Boolean,
    /** Physical: foreground, background and lock-screen playback including system transport controls. */
    val physicalBackgroundValidated: Boolean,
    /** Physical: Bluetooth output, including disconnect during preparation and during an overlap, and reconnect. */
    val physicalBluetoothValidated: Boolean,
    /** Physical: wired output, including unplug during overlap and reconnect. */
    val physicalWiredValidated: Boolean,
    /**
     * Physical: the EQ compatibility POLICY works on device (EQ on -> crossfade unavailable and the EQ stays
     * audible; EQ off -> the saved preference resumes). This does NOT mean an Equalizer mirrored across both engine players exists.
     */
    val equalizerCompatibilityValidated: Boolean,
)

/** True only when every mandatory automated and physical safeguard is satisfied; anything missing fails closed. */
internal fun canEnableCrossfadeProduction(readiness: CrossfadeRolloutReadiness): Boolean =
    readiness.automatedGatePassed &&
        readiness.physicalCorePlaybackValidated &&
        readiness.physicalBackgroundValidated &&
        readiness.physicalBluetoothValidated &&
        readiness.physicalWiredValidated &&
        readiness.equalizerCompatibilityValidated
