package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.model.MonthYear
import java.time.ZoneId

/**
 * Available analytics months/years derived from event TIMESTAMPS (PLAY + SKIP occurredAt), so a screen never needs the event
 * entities just to know which periods have activity. Calendar membership is always decided in Kotlin through [zone] with
 * [MonthYear.fromEpochMs] (never SQLite strftime), so DST and timezone boundaries behave exactly as before. This is the single
 * implementation: the entity-based builder overloads delegate to it.
 */
object AnalyticsPeriodIndex {

    fun availableMonths(timestamps: Collection<Long>, zone: ZoneId = ZoneId.systemDefault()): List<MonthYear> =
        timestamps
            .map { MonthYear.fromEpochMs(it, zone) }
            .distinct()
            .sortedDescending()

    fun availableYears(timestamps: Collection<Long>, zone: ZoneId = ZoneId.systemDefault()): List<Int> =
        timestamps
            .map { MonthYear.fromEpochMs(it, zone).year }
            .distinct()
            .sortedDescending()
}
