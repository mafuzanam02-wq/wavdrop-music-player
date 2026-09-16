package com.launchpoint.wavdrop.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSheetSemanticsTest {

    @Test
    fun `current index zero has no earlier section`() {
        val sections = queueSheetSections(currentIndex = 0, queueSize = 4)

        assertEquals(0, sections.earlierQueueCount)
        assertNull(sections.earlierQueueHeader)
    }

    @Test
    fun `current index greater than zero counts items before current`() {
        val sections = queueSheetSections(currentIndex = 3, queueSize = 6)

        assertEquals(3, sections.earlierQueueCount)
        assertEquals("Earlier in queue · 3", sections.earlierQueueHeader)
    }

    @Test
    fun `current item remains outside earlier section`() {
        val currentIndex = 2
        val sections = queueSheetSections(currentIndex = currentIndex, queueSize = 5)

        val earlierIndexes = 0 until sections.earlierQueueCount

        assertFalse(currentIndex in earlierIndexes)
        assertEquals(currentIndex, sections.currentItemIndex)
    }

    @Test
    fun `up next count stays based on items after current`() {
        val sections = queueSheetSections(currentIndex = 2, queueSize = 5)

        assertEquals(3, sections.upNextStartIndex)
        assertEquals(2, sections.upNextCount)
    }
}
