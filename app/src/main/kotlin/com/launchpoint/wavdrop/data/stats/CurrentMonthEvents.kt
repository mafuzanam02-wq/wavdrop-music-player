package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.MonthYear
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** The events of exactly one calendar [month]; the month travels with its rows so a consumer can never pair them with another range. */
data class MonthScopedEvents(val month: MonthYear, val events: List<TrackListenEventEntity>)

/**
 * The wall-clock "current month" as a reactive key (WC-02). Emits once on collection, then re-reads the clock on every
 * [invalidation] emission and emits only when the calendar month changed. The clock alone defines "this month"; invalidations
 * (any analytics event timestamp emission) are merely the trigger that lets a long-lived screen notice a rollover without a timer.
 * An old or restored historical event therefore re-reads the same clock month and can never move the key backwards. Month
 * membership uses [zone] through [MonthYear.fromEpochMs], the same semantics as everywhere else.
 */
fun currentCalendarMonthFlow(
    invalidation: Flow<*>,
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: () -> Long = { System.currentTimeMillis() },
): Flow<MonthYear> = flow {
    emit(MonthYear.fromEpochMs(nowMs(), zone))
    invalidation.collect { emit(MonthYear.fromEpochMs(nowMs(), zone)) }
}.distinctUntilChanged()

/**
 * Events of the wall-clock current month only. The bounded range subscription is rebound ([flatMapLatest]) when
 * [currentCalendarMonthFlow] switches month; each emission carries the month it was queried for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun currentMonthEventsFlow(
    invalidation: Flow<*>,
    eventsInRange: (fromMs: Long, toMs: Long) -> Flow<List<TrackListenEventEntity>>,
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: () -> Long = { System.currentTimeMillis() },
): Flow<MonthScopedEvents> = currentCalendarMonthFlow(invalidation, zone, nowMs).flatMapLatest { month ->
    val range = ListeningPeriodRange.month(month.year, month.month, zone)
    eventsInRange(range.fromMs, range.toMs).map { MonthScopedEvents(month, it) }
}
