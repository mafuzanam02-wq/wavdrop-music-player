package com.launchpoint.wavdrop.playback

/**
 * CF-2I1: the observable audio-session identity of the secondary player. Deliberately tiny: no volume, queue, song,
 * position, duration, EQ state, attributes or focus. This is an OBSERVATION, never occurrence identity: ownership stays
 * [CrossfadeTransitionKey] plus the secondary attempt token, so a reused Android session id can never revive an old transition.
 */
internal data class SecondaryAudioSessionSnapshot(
    val audioSessionId: Int,
)

/** A positive id is a real session; 0 (unassigned) and negatives are unavailable. Ids are never synthesized or normalized. */
internal fun validSecondaryAudioSessionId(audioSessionId: Int): Int? = audioSessionId.takeIf { it > 0 }

/** Diagnostic relationship between the primary and secondary sessions. Not an EQ-compatibility verdict. */
internal enum class CrossfadeAudioSessionRelationship {
    Unavailable,
    Shared,
    Distinct,
}

internal fun compareCrossfadeAudioSessions(
    primaryAudioSessionId: Int,
    secondaryAudioSessionId: Int?,
): CrossfadeAudioSessionRelationship {
    val primary = validSecondaryAudioSessionId(primaryAudioSessionId)
        ?: return CrossfadeAudioSessionRelationship.Unavailable
    val secondary = secondaryAudioSessionId?.let(::validSecondaryAudioSessionId)
        ?: return CrossfadeAudioSessionRelationship.Unavailable
    return if (primary == secondary) CrossfadeAudioSessionRelationship.Shared else CrossfadeAudioSessionRelationship.Distinct
}

/**
 * CF-2I1: read-only diagnostic seam, invoked at most once per exact preparation (at the Ready boundary). It cannot own,
 * cancel, retry or touch any EQ; an unavailable session is observation only.
 */
internal fun interface CrossfadeAudioSessionObserver {
    fun onSecondarySessionObserved(
        key: CrossfadeTransitionKey,
        primaryAudioSessionId: Int,
        secondaryAudioSessionId: Int?,
    )

    companion object {
        val NoOp = CrossfadeAudioSessionObserver { _, _, _ -> }
    }
}

/** One concise, metadata-free diagnostic line (key, session ids, relationship only). */
internal fun formatCrossfadeAudioSessionObservation(
    key: CrossfadeTransitionKey,
    primaryAudioSessionId: Int,
    secondaryAudioSessionId: Int?,
): String =
    "[crossfade-session] key=${key.queueGeneration}:${key.fromPlaybackIndex}->${key.toPlaybackIndex} " +
        "primary=$primaryAudioSessionId secondary=${secondaryAudioSessionId ?: "unavailable"} " +
        "relationship=${compareCrossfadeAudioSessions(primaryAudioSessionId, secondaryAudioSessionId)}"
