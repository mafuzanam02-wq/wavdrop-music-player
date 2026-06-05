package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphoneResumeModeTest {

    // ── Enum shape ────────────────────────────────────────────────────────────

    @Test
    fun `HeadphoneResumeMode has exactly three entries`() {
        assertEquals(3, HeadphoneResumeMode.entries.size)
    }

    @Test
    fun `HeadphoneResumeMode entries have distinct non-blank display names`() {
        val names = HeadphoneResumeMode.entries.map { it.displayName }
        assertEquals(HeadphoneResumeMode.entries.size, names.distinct().size)
        names.forEach { assertNotEquals("", it.trim()) }
    }

    @Test
    fun `HeadphoneResumeMode entries have distinct non-blank descriptions`() {
        val descs = HeadphoneResumeMode.entries.map { it.description }
        assertEquals(HeadphoneResumeMode.entries.size, descs.distinct().size)
        descs.forEach { assertNotEquals("", it.trim()) }
    }

    @Test
    fun `HeadphoneResumeMode valueOf roundtrip for all entries`() {
        HeadphoneResumeMode.entries.forEach { mode ->
            assertEquals(mode, HeadphoneResumeMode.valueOf(mode.name))
        }
    }

    @Test
    fun `first entry is OFF`() {
        assertEquals(HeadphoneResumeMode.OFF, HeadphoneResumeMode.entries.first())
    }

    @Test
    fun `second entry is RESUME_IF_INTERRUPTED`() {
        assertEquals(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, HeadphoneResumeMode.entries[1])
    }

    @Test
    fun `third entry is ALWAYS_RESUME`() {
        assertEquals(HeadphoneResumeMode.ALWAYS_RESUME, HeadphoneResumeMode.entries[2])
    }

    // ── OFF mode ──────────────────────────────────────────────────────────────

    @Test
    fun `OFF shouldResume returns false when not interrupted`() {
        assertFalse(HeadphoneResumeMode.OFF.shouldResume(wasInterrupted = false))
    }

    @Test
    fun `OFF shouldResume returns false even when interrupted`() {
        assertFalse(HeadphoneResumeMode.OFF.shouldResume(wasInterrupted = true))
    }

    // ── RESUME_IF_INTERRUPTED mode ────────────────────────────────────────────

    @Test
    fun `RESUME_IF_INTERRUPTED shouldResume returns true when interrupted`() {
        assertTrue(HeadphoneResumeMode.RESUME_IF_INTERRUPTED.shouldResume(wasInterrupted = true))
    }

    @Test
    fun `RESUME_IF_INTERRUPTED shouldResume returns false when not interrupted`() {
        assertFalse(HeadphoneResumeMode.RESUME_IF_INTERRUPTED.shouldResume(wasInterrupted = false))
    }

    // ── ALWAYS_RESUME mode ────────────────────────────────────────────────────

    @Test
    fun `ALWAYS_RESUME shouldResume returns true when interrupted`() {
        assertTrue(HeadphoneResumeMode.ALWAYS_RESUME.shouldResume(wasInterrupted = true))
    }

    @Test
    fun `ALWAYS_RESUME shouldResume returns true when not interrupted`() {
        assertTrue(HeadphoneResumeMode.ALWAYS_RESUME.shouldResume(wasInterrupted = false))
    }

    // ── Display name and description values ───────────────────────────────────

    @Test
    fun `OFF displayName is Off`() {
        assertEquals("Off", HeadphoneResumeMode.OFF.displayName)
    }

    @Test
    fun `RESUME_IF_INTERRUPTED displayName is Resume if interrupted`() {
        assertEquals("Resume if interrupted", HeadphoneResumeMode.RESUME_IF_INTERRUPTED.displayName)
    }

    @Test
    fun `ALWAYS_RESUME displayName is Always resume`() {
        assertEquals("Always resume", HeadphoneResumeMode.ALWAYS_RESUME.displayName)
    }

    // ── ResumeBehaviorSettings uses enum defaults ─────────────────────────────

    @Test
    fun `ResumeBehaviorSettings default bluetoothResumeMode is RESUME_IF_INTERRUPTED`() {
        assertEquals(
            HeadphoneResumeMode.RESUME_IF_INTERRUPTED,
            ResumeBehaviorSettings().bluetoothResumeMode,
        )
    }

    @Test
    fun `ResumeBehaviorSettings default wiredResumeMode is RESUME_IF_INTERRUPTED`() {
        assertEquals(
            HeadphoneResumeMode.RESUME_IF_INTERRUPTED,
            ResumeBehaviorSettings().wiredResumeMode,
        )
    }

    @Test
    fun `ResumeBehaviorSettings copy updates bluetoothResumeMode`() {
        val original = ResumeBehaviorSettings()
        val updated  = original.copy(bluetoothResumeMode = HeadphoneResumeMode.ALWAYS_RESUME)
        assertEquals(HeadphoneResumeMode.ALWAYS_RESUME, updated.bluetoothResumeMode)
        assertEquals(original.wiredResumeMode, updated.wiredResumeMode)
    }

    @Test
    fun `ResumeBehaviorSettings copy updates wiredResumeMode`() {
        val original = ResumeBehaviorSettings()
        val updated  = original.copy(wiredResumeMode = HeadphoneResumeMode.OFF)
        assertEquals(HeadphoneResumeMode.OFF, updated.wiredResumeMode)
        assertEquals(original.bluetoothResumeMode, updated.bluetoothResumeMode)
    }
}
