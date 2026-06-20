package com.launchpoint.wavdrop.data.model

import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface WrappedPeriod {
    val scope: WrappedScope
    val range: ListeningPeriodRange
    val shortLabel: String
    val displayLabel: String
    val shareFilenameLabel: String
    val accessibilityLabel: String

    data class Monthly(
        val month: MonthYear,
        override val range: ListeningPeriodRange,
    ) : WrappedPeriod {
        override val scope: WrappedScope = WrappedScope.MONTHLY
        override val shortLabel: String
            get() = SHORT_MONTH_FORMATTER.format(YearMonth.of(month.year, month.month))
        override val displayLabel: String
            get() = month.toDisplayLabel()
        override val shareFilenameLabel: String
            get() = "%04d-%02d".format(Locale.US, month.year, month.month)
        override val accessibilityLabel: String
            get() = "Monthly Wrapped for $displayLabel"
    }

    data class Yearly(
        val year: Int,
        override val range: ListeningPeriodRange,
    ) : WrappedPeriod {
        override val scope: WrappedScope = WrappedScope.YEARLY
        override val shortLabel: String get() = year.toString()
        override val displayLabel: String get() = year.toString()
        override val shareFilenameLabel: String get() = year.toString()
        override val accessibilityLabel: String get() = "Yearly Wrapped for $year"
    }

    companion object {
        private val SHORT_MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

        fun month(
            month: MonthYear,
            zone: ZoneId = ZoneId.systemDefault(),
        ): WrappedPeriod =
            Monthly(
                month = month,
                range = ListeningPeriodRange.month(month.year, month.month, zone),
            )

        fun year(
            year: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): WrappedPeriod =
            Yearly(
                year = year,
                range = ListeningPeriodRange.year(year, zone),
            )
    }
}
