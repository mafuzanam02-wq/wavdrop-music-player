package com.launchpoint.wavdrop.data.backup

/** Synthetic pending-history fixtures for P5-STATS-17B. Nothing here comes from a real library. */
internal object PendingHistoryTestData {

    /**
     * A snapshot's worth of quarantine: two tracks, one of them with every child kind and a legacy (null-provenance) playlist row,
     * stats whose `lastListenedAt` is null for one track (v1-sourced) and set for the other, and events that carry no eventId at all.
     */
    fun history(fp: String = "fpA", restoredAt: Long = 1_700_000_000_000L): PendingHistoryBackup {
        val k1 = "$fp|1"
        val k2 = "$fp|2"
        return PendingHistoryBackup(
            core = PendingHistoryCore(
                tracks = listOf(
                    PendingHistoryTrack(k1, fp, "1", "Gone Song", "Artist", "Album", 181_000L, 3, 2019, "content://media/1", "Music/Old/", restoredAt, "PENDING"),
                    PendingHistoryTrack(k2, fp, "2", "Other Song", "Artist 2", "Album 2", 200_000L, 0, 0, null, null, restoredAt + 5, "PENDING"),
                ),
                stats = listOf(
                    PendingHistoryStats(k1, 40, 5, 1_600_000_000_000L, 4_000_000L, true, lastListenedAt = null),
                    PendingHistoryStats(k2, 9, 0, 1_600_000_100_000L, 900_000L, false, lastListenedAt = 1_600_000_050_000L),
                ),
                lyricsOverrides = listOf(PendingHistoryLyrics(k1, "line one\nline \"two\" é中", 7_000L, "$k1|lyrics")),
                importBaselines = listOf(PendingHistoryBaseline(k1, "blackplayer", "key/with\nnewline", 4, "$k1|blackplayer|key/with\nnewline")),
                playlistEntries = listOf(
                    PendingHistoryPlaylistEntry(k1, "Roadtrip", 0, "$k1|pl:11|0", "11", 10L, 20L),
                    // provenance unknown (a pre-v10 row): stays null, is never invented
                    PendingHistoryPlaylistEntry(k1, "Old name based", 4, "$k1|Old name based|4", null, null, null),
                    PendingHistoryPlaylistEntry(k2, "Roadtrip", 1, "$k2|pl:11|1", "11", 10L, 20L),
                ),
            ),
            events = listOf(
                PendingHistoryEvent(k1, "PLAY", 1_000L, 60_000L, 180_000L, "$k1|1000|PLAY|60000"),
                PendingHistoryEvent(k1, "PLAY", 2_000L, 61_000L, 180_000L, "$k1|2000|PLAY|61000"),
                PendingHistoryEvent(k1, "SKIP", 3_000L, 1_000L, 180_000L, "$k1|3000|SKIP|1000"),
                PendingHistoryEvent(k2, "PLAY", 4_000L, 62_000L, 200_000L, "$k2|4000|PLAY|62000"),
            ),
        )
    }

    /** A fixture with enough events to need several chunks. */
    fun manyEvents(count: Int, fp: String = "fpBig"): PendingHistoryBackup {
        val h = history(fp)
        val k1 = "$fp|1"
        return h.copy(events = List(count) { i -> PendingHistoryEvent(k1, "PLAY", 10_000L + i, 60_000L, 180_000L, "$k1|${10_000L + i}|PLAY|60000") })
    }
}
