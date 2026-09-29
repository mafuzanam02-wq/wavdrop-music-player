package com.launchpoint.wavdrop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataNavigationTest {

    @Test
    fun `valid metadata is trimmed and retained`() {
        assertEquals("Example Artist", metadataNavigationKey("  Example Artist  ", "Unknown Artist"))
        assertEquals("Example Album", metadataNavigationKey("Example Album", "Unknown Album"))
    }

    @Test
    fun `blank and whitespace metadata are unavailable`() {
        assertNull(metadataNavigationKey("", "Unknown Artist"))
        assertNull(metadataNavigationKey("   ", "Unknown Album"))
    }

    @Test
    fun `unknown labels are rejected case insensitively after trimming`() {
        assertNull(metadataNavigationKey("Unknown Artist", "Unknown Artist"))
        assertNull(metadataNavigationKey("  unknown artist  ", "Unknown Artist"))
        assertNull(metadataNavigationKey("UNKNOWN ALBUM", "Unknown Album"))
        assertNull(metadataNavigationKey(" <UnKnOwN> ", "Unknown Artist"))
    }
}
