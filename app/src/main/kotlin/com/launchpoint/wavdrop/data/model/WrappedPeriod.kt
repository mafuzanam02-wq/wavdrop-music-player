package com.launchpoint.wavdrop.data.model

import java.time.ZoneId

data class WrappedPeriod(
    val year: Int,
    val range: ListeningPeriodRange,
) {
    val label: String get() = year.toString()

    companion object {
        fun year(year: Int, zone: ZoneId = ZoneId.systemDefault()): WrappedPeriod =
            WrappedPeriod(
                year = year,
                range = ListeningPeriodRange.year(year, zone),
            )
    }
}
