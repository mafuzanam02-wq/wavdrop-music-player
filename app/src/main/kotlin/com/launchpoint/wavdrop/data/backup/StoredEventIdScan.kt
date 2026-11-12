package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.dao.StoredEventIdentityRow
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao

internal const val EVENT_ID_SCAN_PAGE = 5_000

/**
 * The stored payload of every ID-bearing event whose id is in [incomingIds] and that passes [include], wherever its timestamp lies
 * (P5-STATS-18D1, shared with Recovery by P5-STATS-18D2).
 *
 * `track_listen_events.eventId` has no index (adding one needs a Room migration, which these fixes deliberately avoid), so an
 * `IN (...)` lookup would re-scan the table once per chunk and is capped by SQLite's bound-parameter limit. Instead the ID-bearing
 * rows are streamed ONCE in rowid order, [EVENT_ID_SCAN_PAGE] at a time (indexed rowid range, no sort), and only matches are kept.
 * Cost: one sequential pass, O(rows in the table); memory: O(|incomingIds|), never the table. Skipped entirely when there are no
 * ids. If the table holds the same id twice, the lowest rowid wins (the planner then reports any disagreement).
 */
internal suspend fun TrackListenEventDao.scanPayloadsForIds(
    incomingIds: Set<String>,
    include: (StoredEventIdentityRow) -> Boolean = { true },
): Map<String, ListenEventRestorePlanner.EventPayload> {
    if (incomingIds.isEmpty()) return emptyMap()
    val found = HashMap<String, ListenEventRestorePlanner.EventPayload>()
    var afterId = Long.MIN_VALUE
    while (true) {
        val page = getEventIdentityPage(afterId, EVENT_ID_SCAN_PAGE)
        if (page.isEmpty()) break
        for (r in page) {
            if (r.eventId in incomingIds && include(r)) {
                found.putIfAbsent(r.eventId, ListenEventRestorePlanner.EventPayload(r.songId, r.occurredAt, r.eventType, r.listenedMs, r.durationMs))
            }
        }
        if (page.size < EVENT_ID_SCAN_PAGE) break
        afterId = page.last().id
    }
    return found
}
