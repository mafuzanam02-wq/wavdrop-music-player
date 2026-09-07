package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.SmartCollectionType

/**
 * Pure rules for the "which 3 Smart Collections appear on Home" preference (WU-01).
 *
 * The persisted contract is exactly [HOME_SMART_COLLECTION_SLOTS] valid, unique
 * [SmartCollectionType] identifiers, stored by their stable enum names, whose order
 * IS the Home display order. All parsing/sanitising lives here so the repository,
 * the selector ViewModel, and the Home projection share one deterministic policy and
 * a stale or forward-incompatible preference can never crash Home.
 *
 * This is a presentation preference only — it never touches collection membership,
 * listen-history calculations, or playback.
 */
object HomeSmartCollectionSelection {

    const val HOME_SMART_COLLECTION_SLOTS = 3

    /**
     * Default Home selection for new and pre-existing users (list order = display order).
     * Matches the three collections Home has always surfaced first: Always Finish,
     * Forgotten Gems, then Usually Abandon.
     */
    val DEFAULT: List<SmartCollectionType> = listOf(
        SmartCollectionType.ALWAYS_FINISH,
        SmartCollectionType.FORGOTTEN_GEMS,
        SmartCollectionType.USUALLY_ABANDON,
    )

    /** Parses stored enum-name ids, dropping blank/unknown values while preserving order. */
    fun parse(ids: List<String>): List<SmartCollectionType> =
        ids.mapNotNull { SmartCollectionType.fromRouteValue(it.trim()) }

    /** Serialises a selection to stable enum-name ids for persistence. */
    fun serialize(types: List<SmartCollectionType>): List<String> = types.map { it.name }

    /**
     * Deterministically coerces a stored/edited selection into exactly [slots] valid,
     * unique collections whenever at least [slots] collections are [available]:
     *
     * - retains recognised entries in their given order,
     * - drops duplicates and anything not in [available] (unknown / future-removed ids),
     * - backfills missing slots from [fallback] (default order), then from [available].
     *
     * When fewer than [slots] collections exist overall the result is simply as many
     * valid unique collections as are available.
     */
    fun sanitize(
        stored: List<SmartCollectionType>,
        available: List<SmartCollectionType> = SmartCollectionType.entries,
        fallback: List<SmartCollectionType> = DEFAULT,
        slots: Int = HOME_SMART_COLLECTION_SLOTS,
    ): List<SmartCollectionType> {
        if (slots <= 0) return emptyList()
        val availableSet = available.toSet()
        val result = LinkedHashSet<SmartCollectionType>()

        fun fill(from: Iterable<SmartCollectionType>) {
            for (type in from) {
                if (result.size >= slots) break
                if (type in availableSet) result.add(type)
            }
        }

        fill(stored)   // 1. keep valid stored ids, in stored order, de-duplicated
        fill(fallback) // 2. backfill from default/fallback order
        fill(available) // 3. backfill from remaining available collections
        return result.toList()
    }

    /** Convenience: parse raw stored ids then [sanitize] to the exact-three contract. */
    fun sanitizeIds(
        ids: List<String>,
        available: List<SmartCollectionType> = SmartCollectionType.entries,
        fallback: List<SmartCollectionType> = DEFAULT,
        slots: Int = HOME_SMART_COLLECTION_SLOTS,
    ): List<SmartCollectionType> =
        sanitize(parse(ids), available = available, fallback = fallback, slots = slots)
}
