package com.launchpoint.wavdrop.data.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MonthYear(val year: Int, val month: Int) : Comparable<MonthYear> {

    override fun compareTo(other: MonthYear): Int =
        compareValuesBy(this, other, { it.year }, { it.month })

    fun toDisplayLabel(): String = MONTH_FORMATTER.format(YearMonth.of(year, month))

    companion object {
        private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

        fun fromEpochMs(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): MonthYear {
            val local = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            return MonthYear(year = local.year, month = local.monthValue)
        }
    }
}
