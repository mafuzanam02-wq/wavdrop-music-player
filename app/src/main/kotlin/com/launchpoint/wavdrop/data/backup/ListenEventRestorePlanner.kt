package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.Song
import java.time.Instant
import java.time.ZoneId

/**
 * Pure planner for restoring listen-event history from a Wavdrop backup.
 *
 * Event history is the source of truth for Monthly Reports, Wrapped, and any
 * current-month analytics — losing events on restore makes those screens empty
 * even when aggregate stats restored correctly.
 *
 * Rules (P5-STATS-18D identity contract):
 *  - Song identity is resolved by the caller (improved tier matcher), so events
 *    follow their track to the NEW local song id after a reinstall.
 *  - Timestamps ([BackupListenEvent.occurredAt]) are preserved exactly.
 *  - Two populations that never suppress each other:
 *      * ID-bearing events (non-null `eventId`): the stable id IS the identity. The same id with the same payload is one event
 *        (duplicate, skipped); the same id with a DIFFERENT payload is an identity conflict - it is reported, never inserted and
 *        never allowed to overwrite the stored row. Different ids with identical metadata are different events. The id is
 *        treated as globally unique (the app generates UUIDs at creation; the backup carries no per-event installation scope).
 *      * Legacy events (null `eventId`): no identity exists, so SOURCE MULTIPLICITY is the rule. Records with the same
 *        `(local songId, occurredAt, eventType, listenedMs, durationMs)` form a group; of an incoming group of I records with L
 *        already stored locally, exactly `max(0, I - L)` are inserted. Re-running is therefore idempotent, a destination that
 *        holds one of two keeps its own row and gains only the missing one, and with nothing stored (Recovery) all I are kept.
 *        Two identical legacy records are two SOURCE RECORDS; they are not claimed to be two distinct listening sessions.
 *  - A legacy record is never suppressed by an ID-bearing row and vice versa (an id is never invented for an old event).
 *  - Payload equality ignores `source` (restored rows are rewritten to [TrackListenEventEntity.SOURCE_MANUAL_RESTORE]).
 *  - Restored events use SOURCE_MANUAL_RESTORE; report builders do not filter
 *    by source, so restored events count in all reports.
 */
object ListenEventRestorePlanner {

    /** The identity-relevant payload of one event, already mapped to a LOCAL song id. */
    data class EventPayload(
        val songId: Long,
        val occurredAt: Long,
        val eventType: String,
        val listenedMs: Long,
        val durationMs: Long,
    )

    /**
     * What the destination already stores for the backup's time window. [byId] holds ID-bearing rows (id -> payload);
     * [legacyCounts] holds null-id rows counted per [legacyKey]. Neither is ever rewritten.
     */
    class ExistingListenEvents(
        val byId: Map<String, EventPayload> = emptyMap(),
        val legacyCounts: Map<String, Int> = emptyMap(),
    ) {
        companion object {
            val EMPTY = ExistingListenEvents()

            fun from(rows: Iterable<TrackListenEventEntity>): ExistingListenEvents {
                val ids = HashMap<String, EventPayload>()
                val legacy = HashMap<String, Int>()
                for (r in rows) accumulate(ids, legacy, r)
                return ExistingListenEvents(ids, legacy)
            }

            /** Adds one stored row. Streaming-friendly so callers can page through a large history. */
            fun accumulate(ids: MutableMap<String, EventPayload>, legacy: MutableMap<String, Int>, r: TrackListenEventEntity) {
                val id = r.eventId
                if (id != null) {
                    ids.putIfAbsent(id, EventPayload(r.songId, r.occurredAt, r.eventType, r.listenedMs, r.durationMs))
                } else {
                    legacy.merge(legacyKey(r.songId, r.occurredAt, r.eventType, r.listenedMs, r.durationMs), 1, Int::plus)
                }
            }
        }
    }

    data class Plan(
        val toInsert: List<TrackListenEventEntity>,
        val eventsInBackup: Int,
        val restored: Int,
        val skippedDuplicate: Int,
        val skippedUnmatched: Int,
        val skippedInvalidType: Int,
        /** Restored events whose timestamp falls inside the current calendar month. */
        val currentMonthRestored: Int,
        /** Same stable eventId as an existing/earlier event but a different payload: reported, not inserted, nothing overwritten. */
        val skippedConflict: Int = 0,
    ) {
        val skippedTotal: Int get() = skippedDuplicate + skippedUnmatched + skippedInvalidType + skippedConflict
    }

    /** Legacy 4-field key, still used by the Desktop overlay planner and the quarantine; NOT the live identity any more. */
    fun fingerprint(songId: Long, event: BackupListenEvent): String =
        "$songId|${event.occurredAt}|${event.eventType}|${event.listenedMs}"

    fun legacyKey(songId: Long, occurredAt: Long, eventType: String, listenedMs: Long, durationMs: Long): String =
        "$songId|$occurredAt|$eventType|$listenedMs|$durationMs"

    fun plan(
        events: List<BackupListenEvent>,
        resolveSong: (BackupListenEvent) -> Song?,
        existing: ExistingListenEvents = ExistingListenEvents.EMPTY,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        val validEventTypes = setOf(
            TrackListenEventEntity.TYPE_PLAY,
            TrackListenEventEntity.TYPE_SKIP,
        )
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val currentMonth = ListeningPeriodRange.month(now.year, now.monthValue, zone)

        // Only what THIS backup adds is copied; the stored maps are read through, never duplicated.
        val batchIds = HashMap<String, EventPayload>()
        val batchLegacy = HashMap<String, Int>()
        val toInsert = mutableListOf<TrackListenEventEntity>()
        var skippedDuplicate = 0
        var skippedConflict = 0
        var skippedUnmatched = 0
        var skippedInvalidType = 0
        var currentMonthRestored = 0

        for (event in events) {
            if (event.eventType !in validEventTypes ||
                event.occurredAt <= 0L ||
                event.durationMs < 0L ||
                !event.hasValidListenedTime()
            ) {
                skippedInvalidType++
                continue
            }
            val song = resolveSong(event)
            if (song == null) {
                skippedUnmatched++
                continue
            }
            val payload = EventPayload(song.id, event.occurredAt, event.eventType, event.listenedMs, event.durationMs)
            val incomingEventId = event.eventId
            if (incomingEventId != null) {
                val prior = batchIds[incomingEventId] ?: existing.byId[incomingEventId]
                if (prior != null) {
                    if (prior == payload) skippedDuplicate++ else skippedConflict++
                    continue
                }
                batchIds[incomingEventId] = payload
            } else {
                val key = legacyKey(song.id, event.occurredAt, event.eventType, event.listenedMs, event.durationMs)
                val seenInBatch = (batchLegacy[key] ?: 0) + 1
                batchLegacy[key] = seenInBatch
                // The first L incoming records of a group are accounted for by the L rows already stored.
                if (seenInBatch <= (existing.legacyCounts[key] ?: 0)) {
                    skippedDuplicate++
                    continue
                }
            }
            toInsert += TrackListenEventEntity(
                songId     = song.id,
                eventType  = event.eventType,
                occurredAt = event.occurredAt,
                listenedMs = event.listenedMs,
                durationMs = event.durationMs,
                source     = TrackListenEventEntity.SOURCE_MANUAL_RESTORE,
                eventId    = incomingEventId,  // carry through when present; null stays null
            )
            if (currentMonth.contains(event.occurredAt)) currentMonthRestored++
        }

        return Plan(
            toInsert             = toInsert,
            eventsInBackup       = events.size,
            restored             = toInsert.size,
            skippedDuplicate     = skippedDuplicate,
            skippedUnmatched     = skippedUnmatched,
            skippedInvalidType   = skippedInvalidType,
            currentMonthRestored = currentMonthRestored,
            skippedConflict      = skippedConflict,
        )
    }

    private fun BackupListenEvent.hasValidListenedTime(): Boolean = when (eventType) {
        TrackListenEventEntity.TYPE_PLAY -> listenedMs > 0L
        TrackListenEventEntity.TYPE_SKIP -> listenedMs == 0L
        else -> false
    }
}
