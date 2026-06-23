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
 * Rules:
 *  - Song identity is resolved by the caller (improved tier matcher), so events
 *    follow their track to the NEW local song id after a reinstall.
 *  - Timestamps ([BackupListenEvent.occurredAt]) are preserved exactly.
 *  - Dedupe key is (songId, occurredAt, eventType, listenedMs): only true
 *    duplicates are skipped — same-song listens at different times all restore.
 *  - Restored events use SOURCE_MANUAL_RESTORE; report builders do not filter
 *    by source, so restored events count in all reports.
 */
object ListenEventRestorePlanner {

    data class Plan(
        val toInsert: List<TrackListenEventEntity>,
        val eventsInBackup: Int,
        val restored: Int,
        val skippedDuplicate: Int,
        val skippedUnmatched: Int,
        val skippedInvalidType: Int,
        /** Restored events whose timestamp falls inside the current calendar month. */
        val currentMonthRestored: Int,
    ) {
        val skippedTotal: Int get() = skippedDuplicate + skippedUnmatched + skippedInvalidType
    }

    fun fingerprint(songId: Long, event: BackupListenEvent): String =
        "$songId|${event.occurredAt}|${event.eventType}|${event.listenedMs}"

    fun plan(
        events: List<BackupListenEvent>,
        resolveSong: (BackupListenEvent) -> Song?,
        existingFingerprints: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        /**
         * Existing local non-null eventIds (P2-B1). An incoming event with a non-null eventId
         * already in this set — or already seen earlier in this batch — is a duplicate. Null/legacy
         * events never use this path; they fall through to the [existingFingerprints] fallback.
         */
        existingEventIds: Set<String> = emptySet(),
    ): Plan {
        val validEventTypes = setOf(
            TrackListenEventEntity.TYPE_PLAY,
            TrackListenEventEntity.TYPE_SKIP,
        )
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val currentMonth = ListeningPeriodRange.month(now.year, now.monthValue, zone)

        val seen = existingFingerprints.toHashSet()
        val seenEventIds = existingEventIds.toHashSet()
        val toInsert = mutableListOf<TrackListenEventEntity>()
        var skippedDuplicate = 0
        var skippedUnmatched = 0
        var skippedInvalidType = 0
        var currentMonthRestored = 0

        for (event in events) {
            if (event.eventType !in validEventTypes ||
                event.occurredAt <= 0L ||
                event.listenedMs <= 0L ||
                event.durationMs < 0L
            ) {
                skippedInvalidType++
                continue
            }
            val song = resolveSong(event)
            if (song == null) {
                skippedUnmatched++
                continue
            }
            // eventId dedup: only when the incoming eventId is non-null. A match against an
            // existing local non-null eventId — or one already seen earlier in this batch —
            // is a duplicate. Null/legacy events skip this and use the fingerprint fallback.
            val incomingEventId = event.eventId
            if (incomingEventId != null && incomingEventId in seenEventIds) {
                skippedDuplicate++
                continue
            }
            val key = fingerprint(song.id, event)
            if (key in seen) {
                skippedDuplicate++
                continue
            }
            seen += key
            if (incomingEventId != null) seenEventIds += incomingEventId
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
        )
    }
}
