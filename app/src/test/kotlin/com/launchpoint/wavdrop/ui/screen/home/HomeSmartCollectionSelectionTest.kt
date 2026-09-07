package com.launchpoint.wavdrop.ui.screen.home

import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSmartCollectionSelectionTest {

    @Test
    fun `home cards use product priority instead of enum order`() {
        val collections = listOf(
            collection(SmartCollectionType.FAVORITES),
            collection(SmartCollectionType.MOST_PLAYED),
            collection(SmartCollectionType.RECENTLY_PLAYED),
            collection(SmartCollectionType.FORGOTTEN_GEMS),
            collection(SmartCollectionType.ALWAYS_FINISH),
            collection(SmartCollectionType.USUALLY_ABANDON),
        )

        assertEquals(
            listOf(
                SmartCollectionType.ALWAYS_FINISH,
                SmartCollectionType.FORGOTTEN_GEMS,
                SmartCollectionType.USUALLY_ABANDON,
            ),
            selectHomeSmartCollections(collections).map { it.type },
        )
    }

    @Test
    fun `empty higher priority collections are skipped and lower priorities fill cards`() {
        val collections = listOf(
            collection(SmartCollectionType.ALWAYS_FINISH, songCount = 0),
            collection(SmartCollectionType.FORGOTTEN_GEMS),
            collection(SmartCollectionType.USUALLY_ABANDON, songCount = 0),
            collection(SmartCollectionType.NEVER_PLAYED),
            collection(SmartCollectionType.FAVORITES),
            collection(SmartCollectionType.MOST_PLAYED),
        )

        assertEquals(
            listOf(
                SmartCollectionType.FORGOTTEN_GEMS,
                SmartCollectionType.NEVER_PLAYED,
                SmartCollectionType.FAVORITES,
            ),
            selectHomeSmartCollections(collections).map { it.type },
        )
    }

    @Test
    fun `home shows at most three smart collection cards`() {
        val collections = SmartCollectionType.entries.map(::collection)

        assertEquals(3, selectHomeSmartCollections(collections).size)
        assertEquals(2, selectHomeSmartCollections(collections, limit = 2).size)
        assertEquals(0, selectHomeSmartCollections(collections, limit = 0).size)
    }

    @Test
    fun `configured order drives home card order`() {
        val collections = listOf(
            collection(SmartCollectionType.FAVORITES),
            collection(SmartCollectionType.MOST_PLAYED),
            collection(SmartCollectionType.NEVER_PLAYED),
        )

        val configured = listOf(
            SmartCollectionType.NEVER_PLAYED,
            SmartCollectionType.FAVORITES,
            SmartCollectionType.MOST_PLAYED,
        )

        assertEquals(
            configured,
            selectHomeSmartCollections(collections, configuredOrder = configured).map { it.type },
        )
    }

    @Test
    fun `empty configured collection is skipped and backfilled preserving existing behavior`() {
        val configured = listOf(
            SmartCollectionType.ALWAYS_FINISH,
            SmartCollectionType.FORGOTTEN_GEMS,
            SmartCollectionType.USUALLY_ABANDON,
        )
        val collections = listOf(
            collection(SmartCollectionType.ALWAYS_FINISH, songCount = 0),
            collection(SmartCollectionType.FORGOTTEN_GEMS),
            collection(SmartCollectionType.USUALLY_ABANDON),
            collection(SmartCollectionType.FAVORITES),
        )

        assertEquals(
            listOf(
                SmartCollectionType.FORGOTTEN_GEMS,
                SmartCollectionType.USUALLY_ABANDON,
                SmartCollectionType.FAVORITES,
            ),
            selectHomeSmartCollections(collections, configuredOrder = configured).map { it.type },
        )
    }

    @Test
    fun `projected collections expose unique stable keys for item animation`() {
        // Home renders these with key = { it.id } and animates placement (WU-03); duplicate or
        // unstable keys would drop or mis-animate items. Guard that the projection keeps ids unique.
        val collections = SmartCollectionType.entries.map(::collection)
        val projected = selectHomeSmartCollections(collections)
        val ids = projected.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(projected.map { it.type.name }, ids)
    }

    @Test
    fun `home priority inventory covers every implemented collection exactly once`() {
        assertEquals(SmartCollectionType.entries.toSet(), HOME_SMART_COLLECTION_PRIORITY.toSet())
        assertEquals(
            SmartCollectionType.entries.size,
            HOME_SMART_COLLECTION_PRIORITY.size,
        )
    }

    private fun collection(
        type: SmartCollectionType,
        songCount: Int = 1,
    ) = SmartCollection(
        id = type.name,
        title = type.name,
        description = type.name,
        type = type,
        songCount = songCount,
    )
}
