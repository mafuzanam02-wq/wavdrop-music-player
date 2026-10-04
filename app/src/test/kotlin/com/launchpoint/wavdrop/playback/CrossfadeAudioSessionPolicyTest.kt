package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2I1: pure session-id validity and primary/secondary relationship rules. */
class CrossfadeAudioSessionPolicyTest {

    @Test fun positiveIdIsValidAndNotNormalized() {
        assertEquals(1, validSecondaryAudioSessionId(1))
        assertEquals(12345, validSecondaryAudioSessionId(12345))
    }

    @Test fun zeroAndNegativeAreUnavailable() {
        assertNull(validSecondaryAudioSessionId(0))
        assertNull(validSecondaryAudioSessionId(-1))
        assertNull(validSecondaryAudioSessionId(Int.MIN_VALUE))
    }

    @Test fun invalidPrimaryIsUnavailableWhateverTheSecondary() {
        listOf(0, -1).forEach { primary ->
            listOf(null, 0, -3, 7).forEach { secondary ->
                assertEquals(CrossfadeAudioSessionRelationship.Unavailable, compareCrossfadeAudioSessions(primary, secondary))
            }
        }
    }

    @Test fun validPrimaryWithMissingOrInvalidSecondaryIsUnavailable() {
        assertEquals(CrossfadeAudioSessionRelationship.Unavailable, compareCrossfadeAudioSessions(5, null))
        assertEquals(CrossfadeAudioSessionRelationship.Unavailable, compareCrossfadeAudioSessions(5, 0))
        assertEquals(CrossfadeAudioSessionRelationship.Unavailable, compareCrossfadeAudioSessions(5, -2))
    }

    @Test fun samePositiveIdsAreShared() {
        assertEquals(CrossfadeAudioSessionRelationship.Shared, compareCrossfadeAudioSessions(9, 9))
    }

    @Test fun differentPositiveIdsAreDistinct() {
        assertEquals(CrossfadeAudioSessionRelationship.Distinct, compareCrossfadeAudioSessions(9, 10))
    }

    @Test fun formattedLineCarriesOnlyKeyIdsAndRelationship() {
        val key = CrossfadeTransitionKey(3L, 1, 2)
        val shared = formatCrossfadeAudioSessionObservation(key, 4, 4)
        assertEquals("[crossfade-session] key=3:1->2 primary=4 secondary=4 relationship=Shared", shared)
        val missing = formatCrossfadeAudioSessionObservation(key, 4, null)
        assertTrue(missing.endsWith("secondary=unavailable relationship=Unavailable"))
    }
}
