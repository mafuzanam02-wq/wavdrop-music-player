package com.launchpoint.wavdrop.ui.screen.home

import com.launchpoint.wavdrop.data.settings.HomeSectionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeListeningActivityPreviewTest {

    private val enabled = setOf(
        HomeSectionId.RECENTLY_PLAYED,
        HomeSectionId.MOST_PLAYED,
    )

    @Test
    fun `recently played is preferred when both previews are available`() {
        assertEquals(
            HomeListeningActivityPreview.RECENTLY_PLAYED,
            selectHomeListeningActivityPreview(
                visibleSections = enabled,
                hasRecentlyPlayed = true,
                hasMostPlayed = true,
            ),
        )
    }

    @Test
    fun `most played is fallback when recent is unavailable`() {
        assertEquals(
            HomeListeningActivityPreview.MOST_PLAYED,
            selectHomeListeningActivityPreview(
                visibleSections = enabled,
                hasRecentlyPlayed = false,
                hasMostPlayed = true,
            ),
        )
    }

    @Test
    fun `most played fallback cannot render when listening activity is disabled`() {
        assertNull(
            selectHomeListeningActivityPreview(
                visibleSections = setOf(HomeSectionId.LIBRARY_SHORTCUT),
                hasRecentlyPlayed = false,
                hasMostPlayed = true,
            ),
        )
    }

    @Test
    fun `enabled listening activity with no available songs renders no preview`() {
        assertNull(
            selectHomeListeningActivityPreview(
                visibleSections = enabled,
                hasRecentlyPlayed = false,
                hasMostPlayed = false,
            ),
        )
    }
}
