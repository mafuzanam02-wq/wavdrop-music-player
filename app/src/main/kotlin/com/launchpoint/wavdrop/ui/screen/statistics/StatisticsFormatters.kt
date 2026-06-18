package com.launchpoint.wavdrop.ui.screen.statistics

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal object StatisticsFormatters {

    fun formatDurationSummary(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        val totalHours = totalMinutes / 60L
        val days = totalHours / 24L
        val hours = totalHours % 24L
        val minutes = totalMinutes % 60L

        return when {
            days > 0L -> if (hours > 0L) "${days}d ${hours}h" else "${days}d"
            totalHours > 0L -> if (minutes > 0L) "${totalHours}h ${minutes}m" else "${totalHours}h"
            else -> "${totalMinutes}m"
        }
    }

    fun formatLastPlayed(epochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs == null || epochMs <= 0L) return "Never"

        val zone = ZoneId.systemDefault()
        val playedDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

        return when (playedDate) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DATE_FORMATTER.format(playedDate)
        }
    }

    fun formatStreakDays(days: Int): String =
        if (days == 0) "—" else "$days day${if (days == 1) "" else "s"}"

    fun formatSkipRatio(totalPlays: Int, totalSkips: Int): String {
        if (totalPlays == 0) return "—"
        return "${totalSkips * 100 / totalPlays}%"
    }

    fun formatDayOfWeekShort(dow: DayOfWeek?): String =
        dow?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "—"

    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
}
