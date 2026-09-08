package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WrappedVisualStyleTest {

    @Test
    fun `default is Glass Flow`() {
        assertSame(WrappedVisualStyle.GLASS_FLOW, WrappedVisualStyle.DEFAULT)
    }

    @Test
    fun `null persisted value falls back to default`() {
        assertEquals(WrappedVisualStyle.DEFAULT, WrappedVisualStyle.fromStorage(null))
    }

    @Test
    fun `unknown persisted value falls back to default`() {
        assertEquals(WrappedVisualStyle.DEFAULT, WrappedVisualStyle.fromStorage("HOLOGRAPHIC"))
        assertEquals(WrappedVisualStyle.DEFAULT, WrappedVisualStyle.fromStorage(""))
        assertEquals(WrappedVisualStyle.DEFAULT, WrappedVisualStyle.fromStorage("glass_flow"))
    }

    @Test
    fun `every valid name round-trips`() {
        WrappedVisualStyle.entries.forEach { style ->
            assertEquals(style, WrappedVisualStyle.fromStorage(style.name))
        }
    }
}
