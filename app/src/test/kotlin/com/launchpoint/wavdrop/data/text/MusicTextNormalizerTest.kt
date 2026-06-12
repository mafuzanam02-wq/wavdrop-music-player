package com.launchpoint.wavdrop.data.text

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTextNormalizerTest {

    @Test
    fun `strict normalization trims lowercases and collapses whitespace`() {
        assertEquals("jole this city", MusicTextNormalizer.normalizeStrict("  Jole   This   City  "))
    }

    @Test
    fun `tolerant normalization matches accented and plain names`() {
        assertEquals(
            MusicTextNormalizer.normalizeTolerant("Jolé"),
            MusicTextNormalizer.normalizeTolerant("Jole"),
        )
    }

    @Test
    fun `tolerant normalization removes apostrophe variants`() {
        assertEquals(
            MusicTextNormalizer.normalizeTolerant("Don\u2019t"),
            MusicTextNormalizer.normalizeTolerant("Dont"),
        )
    }

    @Test
    fun `tolerant normalization treats underscores as spaces`() {
        assertEquals(
            MusicTextNormalizer.normalizeTolerant("Picture_Perfect"),
            MusicTextNormalizer.normalizeTolerant("Picture Perfect"),
        )
    }

    @Test
    fun `tolerant normalization strips low value quality suffixes`() {
        assertEquals("still", MusicTextNormalizer.normalizeTolerant("Still(256k)"))
        assertEquals("still", MusicTextNormalizer.normalizeTolerant("Still [320k]"))
        assertEquals("still", MusicTextNormalizer.normalizeTolerant("Still_(256k)"))
    }

    @Test
    fun `non latin text is preserved in comparison keys`() {
        assertEquals("軽注", MusicTextNormalizer.normalizeStrict("軽注"))
        assertEquals("軽注", MusicTextNormalizer.normalizeTolerant("軽注"))
    }
}
