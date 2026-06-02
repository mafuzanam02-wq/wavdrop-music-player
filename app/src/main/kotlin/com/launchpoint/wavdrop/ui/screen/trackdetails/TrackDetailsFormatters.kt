package com.launchpoint.wavdrop.ui.screen.trackdetails

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object TrackDetailsFormatters {

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val hours   = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    fun formatLastPlayed(epochMs: Long): String {
        if (epochMs == 0L) return "Never"
        val dt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(dt)
    }

    fun formatListeningTime(ms: Long): String {
        if (ms <= 0L) return "0 min"
        val totalMinutes = ms / 60_000L
        if (totalMinutes == 0L) return "< 1 min"
        val hours   = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0                -> "${hours}h"
            else                     -> "${totalMinutes}m"
        }
    }
}
