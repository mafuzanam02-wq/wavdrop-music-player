package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationControlsSettingTest {

    // ── Enum shape ────────────────────────────────────────────────────────────

    @Test
    fun `has exactly four entries`() {
        assertEquals(4, NotificationControlsSetting.entries.size)
    }

    @Test
    fun `entries have distinct non-blank display names`() {
        val names = NotificationControlsSetting.entries.map { it.displayName }
        assertEquals(NotificationControlsSetting.entries.size, names.distinct().size)
        names.forEach { assertNotEquals("", it.trim()) }
    }

    @Test
    fun `valueOf roundtrip for all entries`() {
        NotificationControlsSetting.entries.forEach { setting ->
            assertEquals(setting, NotificationControlsSetting.valueOf(setting.name))
        }
    }

    @Test
    fun `first entry is STANDARD`() {
        assertEquals(NotificationControlsSetting.STANDARD, NotificationControlsSetting.entries.first())
    }

    // ── STANDARD ──────────────────────────────────────────────────────────────

    @Test
    fun `STANDARD does not include shuffle`() {
        assertFalse(NotificationControlsSetting.STANDARD.includeShuffle)
    }

    @Test
    fun `STANDARD does not include repeat`() {
        assertFalse(NotificationControlsSetting.STANDARD.includeRepeat)
    }

    // ── STANDARD_SHUFFLE ──────────────────────────────────────────────────────

    @Test
    fun `STANDARD_SHUFFLE includes shuffle`() {
        assertTrue(NotificationControlsSetting.STANDARD_SHUFFLE.includeShuffle)
    }

    @Test
    fun `STANDARD_SHUFFLE does not include repeat`() {
        assertFalse(NotificationControlsSetting.STANDARD_SHUFFLE.includeRepeat)
    }

    // ── STANDARD_REPEAT ───────────────────────────────────────────────────────

    @Test
    fun `STANDARD_REPEAT does not include shuffle`() {
        assertFalse(NotificationControlsSetting.STANDARD_REPEAT.includeShuffle)
    }

    @Test
    fun `STANDARD_REPEAT includes repeat`() {
        assertTrue(NotificationControlsSetting.STANDARD_REPEAT.includeRepeat)
    }

    // ── STANDARD_SHUFFLE_REPEAT ───────────────────────────────────────────────

    @Test
    fun `STANDARD_SHUFFLE_REPEAT includes shuffle`() {
        assertTrue(NotificationControlsSetting.STANDARD_SHUFFLE_REPEAT.includeShuffle)
    }

    @Test
    fun `STANDARD_SHUFFLE_REPEAT includes repeat`() {
        assertTrue(NotificationControlsSetting.STANDARD_SHUFFLE_REPEAT.includeRepeat)
    }

    // ── Display names ─────────────────────────────────────────────────────────

    @Test
    fun `STANDARD displayName is Standard`() {
        assertEquals("Standard", NotificationControlsSetting.STANDARD.displayName)
    }

    @Test
    fun `STANDARD_SHUFFLE displayName is Standard + Shuffle`() {
        assertEquals("Standard + Shuffle", NotificationControlsSetting.STANDARD_SHUFFLE.displayName)
    }

    @Test
    fun `STANDARD_REPEAT displayName is Standard + Repeat`() {
        assertEquals("Standard + Repeat", NotificationControlsSetting.STANDARD_REPEAT.displayName)
    }

    @Test
    fun `STANDARD_SHUFFLE_REPEAT displayName is Standard + Shuffle and Repeat`() {
        assertEquals("Standard + Shuffle & Repeat", NotificationControlsSetting.STANDARD_SHUFFLE_REPEAT.displayName)
    }

    // ── Coverage: only shuffle/repeat entries have their respective flags ──────

    @Test
    fun `exactly two entries include shuffle`() {
        val count = NotificationControlsSetting.entries.count { it.includeShuffle }
        assertEquals(2, count)
    }

    @Test
    fun `exactly two entries include repeat`() {
        val count = NotificationControlsSetting.entries.count { it.includeRepeat }
        assertEquals(2, count)
    }

    @Test
    fun `entries with neither flag are only STANDARD`() {
        val neither = NotificationControlsSetting.entries.filter { !it.includeShuffle && !it.includeRepeat }
        assertEquals(listOf(NotificationControlsSetting.STANDARD), neither)
    }
}
