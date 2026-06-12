package com.launchpoint.wavdrop.data.smart

import com.launchpoint.wavdrop.data.model.SmartCollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartCollectionTypeParserTest {

    // ── Valid values ──────────────────────────────────────────────────────────

    @Test
    fun `all enum constants round-trip through fromRouteValue`() {
        SmartCollectionType.entries.forEach { expected ->
            val result = SmartCollectionType.fromRouteValue(expected.name)
            assertEquals("${expected.name} did not round-trip", expected, result)
        }
    }

    @Test fun `FAVORITES parses correctly`() {
        assertEquals(SmartCollectionType.FAVORITES, SmartCollectionType.fromRouteValue("FAVORITES"))
    }

    @Test fun `MOST_PLAYED parses correctly`() {
        assertEquals(SmartCollectionType.MOST_PLAYED, SmartCollectionType.fromRouteValue("MOST_PLAYED"))
    }

    @Test fun `SHORT_TRACKS parses correctly`() {
        assertEquals(SmartCollectionType.SHORT_TRACKS, SmartCollectionType.fromRouteValue("SHORT_TRACKS"))
    }

    // ── Invalid / null values ─────────────────────────────────────────────────

    @Test fun `null returns null`() {
        assertNull(SmartCollectionType.fromRouteValue(null))
    }

    @Test fun `blank string returns null`() {
        assertNull(SmartCollectionType.fromRouteValue(""))
    }

    @Test fun `unknown name returns null`() {
        assertNull(SmartCollectionType.fromRouteValue("UNKNOWN_TYPE"))
    }

    @Test fun `lowercase name returns null — matching is case-sensitive`() {
        assertNull(SmartCollectionType.fromRouteValue("favorites"))
    }

    @Test fun `mixed-case name returns null`() {
        assertNull(SmartCollectionType.fromRouteValue("Favorites"))
    }

    @Test fun `name with leading space returns null`() {
        assertNull(SmartCollectionType.fromRouteValue(" FAVORITES"))
    }

    @Test fun `old enum name that no longer exists returns null`() {
        // Simulates a stale back-stack entry from an older app version
        assertNull(SmartCollectionType.fromRouteValue("RECENTLY_SKIPPED"))
    }
}
