package com.launchpoint.wavdrop.data.backup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * JSON encode / strict decode / validate for [PendingHistoryBackup], shared by BOTH packagings (`.wdbk` extension entries and the
 * additive legacy-JSON root key) so a field can never be mapped two ways.
 *
 * Shape of one section (schemaVersion 1; unknown extra fields inside a record are ignored, unknown versions are never read):
 * ```
 * { "schemaVersion": 1,
 *   "tracks":          [ {originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year,
 *                         sourceUri?, sourceFolderPath?, restoredAt, status} ],
 *   "stats":           [ {track, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt?} ],
 *   "lyricsOverrides": [ {track, lyrics, updatedAt, originFingerprint} ],
 *   "importBaselines": [ {track, sourceType, sourceKey, playCount, originFingerprint} ],
 *   "playlistEntries": [ {track, playlistName, position, originFingerprint, sourcePlaylistId?, sourcePlaylistCreatedAt?, sourcePlaylistUpdatedAt?} ],
 *   "events":          [ {track, eventType, occurredAt, listenedMs, durationMs, originFingerprint} ] }   // legacy JSON only
 * ```
 * `track` is the parent's `originKey`. In the `.wdbk` form `events` live in separate chunk entries and the main entry instead
 * declares `eventCount` and `eventChunkCount`.
 *
 * Integrity: the legacy form carries `integrity: {v:1, sha256}` computed by [digest] over the canonical content; a mismatch rejects
 * the backup. That detects damage; it is NOT authentication (anyone can recompute it), exactly like the primary fingerprint.
 * The primary semantic fingerprint does not cover this extension in either packaging.
 */
internal object PendingHistoryCodec {

    const val STATUS_PENDING = "PENDING"
    const val DAMAGED = "The quarantined history in this backup is damaged and cannot be restored."

    // ── Encode ────────────────────────────────────────────────────────────────

    fun coreObject(core: PendingHistoryCore, eventCount: Long, eventChunkCount: Int): JSONObject = JSONObject().apply {
        put("schemaVersion", PendingHistoryLimits.SCHEMA_VERSION)
        put("eventCount", eventCount)
        put("eventChunkCount", eventChunkCount)
        coreArrays(core)
    }

    fun eventsArray(events: List<PendingHistoryEvent>): JSONArray = JSONArray().apply { events.forEach { put(eventJson(it)) } }

    /** Legacy-JSON form: one object, events inline, own digest. */
    fun legacyObject(history: PendingHistoryBackup): JSONObject = JSONObject().apply {
        put("schemaVersion", PendingHistoryLimits.SCHEMA_VERSION)
        put("integrity", JSONObject().apply {
            put("v", 1)
            put("sha256", digest(history))
        })
        coreArrays(history.core)
        put("events", eventsArray(history.events))
    }

    private fun JSONObject.coreArrays(core: PendingHistoryCore) {
        put("tracks", JSONArray().apply { core.tracks.forEach { put(trackJson(it)) } })
        put("stats", JSONArray().apply { core.stats.forEach { put(statsJson(it)) } })
        put("lyricsOverrides", JSONArray().apply { core.lyricsOverrides.forEach { put(lyricsJson(it)) } })
        put("importBaselines", JSONArray().apply { core.importBaselines.forEach { put(baselineJson(it)) } })
        put("playlistEntries", JSONArray().apply { core.playlistEntries.forEach { put(playlistEntryJson(it)) } })
    }

    private fun trackJson(t: PendingHistoryTrack) = JSONObject().apply {
        put("originKey", t.originKey)
        put("backupFingerprint", t.backupFingerprint)
        put("backupSongId", t.backupSongId)
        put("title", t.title)
        put("artist", t.artist)
        put("album", t.album)
        put("duration", t.duration)
        put("trackNumber", t.trackNumber)
        put("year", t.year)
        t.sourceUri?.let { put("sourceUri", it) }
        t.sourceFolderPath?.let { put("sourceFolderPath", it) }
        put("restoredAt", t.restoredAt)
        put("status", t.status)
    }

    private fun statsJson(s: PendingHistoryStats) = JSONObject().apply {
        put("track", s.trackOriginKey)
        put("playCount", s.playCount)
        put("skipCount", s.skipCount)
        put("lastPlayedAt", s.lastPlayedAt)
        put("totalListeningTimeMs", s.totalListeningTimeMs)
        put("isFavorite", s.isFavorite)
        s.lastListenedAt?.let { put("lastListenedAt", it) }   // absent == null: a v1-sourced row, never a fabricated 0
    }

    private fun eventJson(e: PendingHistoryEvent) = JSONObject().apply {
        put("track", e.trackOriginKey)
        put("eventType", e.eventType)
        put("occurredAt", e.occurredAt)
        put("listenedMs", e.listenedMs)
        put("durationMs", e.durationMs)
        put("originFingerprint", e.originFingerprint)
    }

    private fun lyricsJson(l: PendingHistoryLyrics) = JSONObject().apply {
        put("track", l.trackOriginKey)
        put("lyrics", l.lyrics)
        put("updatedAt", l.updatedAt)
        put("originFingerprint", l.originFingerprint)
    }

    private fun baselineJson(b: PendingHistoryBaseline) = JSONObject().apply {
        put("track", b.trackOriginKey)
        put("sourceType", b.sourceType)
        put("sourceKey", b.sourceKey)
        put("playCount", b.playCount)
        put("originFingerprint", b.originFingerprint)
    }

    private fun playlistEntryJson(p: PendingHistoryPlaylistEntry) = JSONObject().apply {
        put("track", p.trackOriginKey)
        put("playlistName", p.playlistName)
        put("position", p.position)
        put("originFingerprint", p.originFingerprint)
        p.sourcePlaylistId?.let { put("sourcePlaylistId", it) }
        p.sourcePlaylistCreatedAt?.let { put("sourcePlaylistCreatedAt", it) }
        p.sourcePlaylistUpdatedAt?.let { put("sourcePlaylistUpdatedAt", it) }
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    /** The main `.wdbk` entry: the non-event records plus the declared event counts. */
    class ParsedCore(val core: PendingHistoryCore, val eventCount: Long, val eventChunkCount: Int)

    /** Reads `schemaVersion` of a parsed section; anything that is not an integer is malformed (not "newer"). */
    fun schemaVersion(section: Map<*, *>): Long =
        section["schemaVersion"] as? Long ?: throw BackupParseException(DAMAGED)

    fun parseCore(section: Map<*, *>): ParsedCore {
        val eventCount = section.long("eventCount", "eventCount", 0, PendingHistoryLimits.MAX_EVENTS.toLong())
        val chunks = section.long("eventChunkCount", "eventChunkCount", 0, 100_000)
        return ParsedCore(readCore(section), eventCount, chunks.toInt())
    }

    private fun readCore(section: Map<*, *>): PendingHistoryCore {
        val tracks = section.array("tracks", PendingHistoryLimits.MAX_TRACKS).mapObjects("tracks") { p, o ->
            PendingHistoryTrack(
                originKey = o.str("$p.originKey", "originKey", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                backupFingerprint = o.str("$p.backupFingerprint", "backupFingerprint", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                backupSongId = o.str("$p.backupSongId", "backupSongId", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                title = o.str("$p.title", "title", PendingHistoryLimits.MAX_TEXT_CHARS),
                artist = o.str("$p.artist", "artist", PendingHistoryLimits.MAX_TEXT_CHARS),
                album = o.str("$p.album", "album", PendingHistoryLimits.MAX_TEXT_CHARS),
                duration = o.long("$p.duration", "duration", 0),
                trackNumber = o.int("$p.trackNumber", "trackNumber"),
                year = o.int("$p.year", "year"),
                sourceUri = o.optStr("$p.sourceUri", "sourceUri", PendingHistoryLimits.MAX_TEXT_CHARS),
                sourceFolderPath = o.optStr("$p.sourceFolderPath", "sourceFolderPath", PendingHistoryLimits.MAX_TEXT_CHARS),
                restoredAt = o.long("$p.restoredAt", "restoredAt", 0),
                status = o.str("$p.status", "status", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
            )
        }
        val stats = section.array("stats", PendingHistoryLimits.MAX_TRACKS).mapObjects("stats") { p, o ->
            PendingHistoryStats(
                trackOriginKey = o.str("$p.track", "track", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                playCount = o.int("$p.playCount", "playCount", 0),
                skipCount = o.int("$p.skipCount", "skipCount", 0),
                lastPlayedAt = o.long("$p.lastPlayedAt", "lastPlayedAt", 0),
                totalListeningTimeMs = o.long("$p.totalListeningTimeMs", "totalListeningTimeMs", 0),
                isFavorite = o.bool("$p.isFavorite", "isFavorite"),
                lastListenedAt = o.optLong("$p.lastListenedAt", "lastListenedAt", 0),
            )
        }
        val lyrics = section.array("lyricsOverrides", PendingHistoryLimits.MAX_TRACKS).mapObjects("lyricsOverrides") { p, o ->
            PendingHistoryLyrics(
                trackOriginKey = o.str("$p.track", "track", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                lyrics = o.str("$p.lyrics", "lyrics", PendingHistoryLimits.MAX_LYRICS_CHARS),
                updatedAt = o.long("$p.updatedAt", "updatedAt", 0),
                originFingerprint = o.str("$p.originFingerprint", "originFingerprint", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
            )
        }
        val baselines = section.array("importBaselines", PendingHistoryLimits.MAX_CHILDREN_PER_KIND).mapObjects("importBaselines") { p, o ->
            PendingHistoryBaseline(
                trackOriginKey = o.str("$p.track", "track", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                sourceType = o.str("$p.sourceType", "sourceType", PendingHistoryLimits.MAX_TEXT_CHARS),
                sourceKey = o.str("$p.sourceKey", "sourceKey", PendingHistoryLimits.MAX_TEXT_CHARS),
                playCount = o.int("$p.playCount", "playCount", 0),
                originFingerprint = o.str("$p.originFingerprint", "originFingerprint", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
            )
        }
        val entries = section.array("playlistEntries", PendingHistoryLimits.MAX_CHILDREN_PER_KIND).mapObjects("playlistEntries") { p, o ->
            PendingHistoryPlaylistEntry(
                trackOriginKey = o.str("$p.track", "track", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                playlistName = o.str("$p.playlistName", "playlistName", PendingHistoryLimits.MAX_TEXT_CHARS),
                position = o.int("$p.position", "position", 0),
                originFingerprint = o.str("$p.originFingerprint", "originFingerprint", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                sourcePlaylistId = o.optStr("$p.sourcePlaylistId", "sourcePlaylistId", PendingHistoryLimits.MAX_KEY_CHARS),
                sourcePlaylistCreatedAt = o.optLong("$p.sourcePlaylistCreatedAt", "sourcePlaylistCreatedAt", 0),
                sourcePlaylistUpdatedAt = o.optLong("$p.sourcePlaylistUpdatedAt", "sourcePlaylistUpdatedAt", 0),
            )
        }
        return PendingHistoryCore(tracks, stats, lyrics, baselines, entries)
    }

    /** Parses one array of events (an `.wdbk` chunk, or the inline legacy array). Bounded BEFORE mapping. */
    fun parseEvents(list: List<*>, maxEvents: Int): List<PendingHistoryEvent> {
        if (list.size > maxEvents) throw BackupParseException(DAMAGED)
        return list.mapObjects("events") { p, o ->
            PendingHistoryEvent(
                trackOriginKey = o.str("$p.track", "track", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                eventType = o.str("$p.eventType", "eventType", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
                occurredAt = o.long("$p.occurredAt", "occurredAt", 0),
                listenedMs = o.long("$p.listenedMs", "listenedMs", 0),
                durationMs = o.long("$p.durationMs", "durationMs", 0),
                originFingerprint = o.str("$p.originFingerprint", "originFingerprint", PendingHistoryLimits.MAX_KEY_CHARS, nonBlank = true),
            )
        }
    }

    /** Result of reading the legacy-JSON root key. */
    sealed interface LegacyParse {
        data class Supported(val history: PendingHistoryBackup) : LegacyParse
        /** A well-formed section of a version this build does not read. Ignored, with a warning; never treated as supported data. */
        data class UnsupportedVersion(val version: Long) : LegacyParse
    }

    fun parseLegacy(root: Any?): LegacyParse {
        val section = root as? Map<*, *> ?: throw BackupParseException(DAMAGED)
        val version = schemaVersion(section)
        if (version != PendingHistoryLimits.SCHEMA_VERSION.toLong()) return LegacyParse.UnsupportedVersion(version)
        val core = readCore(section)
        val events = parseEvents(section.array("events", PendingHistoryLimits.MAX_EVENTS), PendingHistoryLimits.MAX_EVENTS)
        val history = PendingHistoryBackup(core, events)
        validate(history)

        val integrity = section["integrity"] as? Map<*, *> ?: throw BackupParseException(DAMAGED)
        if (integrity["v"] as? Long != 1L) throw BackupParseException(DAMAGED)
        val declared = integrity["sha256"] as? String ?: throw BackupParseException(DAMAGED)
        if (!declared.equals(digest(history), ignoreCase = true)) throw BackupParseException(WavdropBackupParser.INTEGRITY_ERROR)
        return LegacyParse.Supported(history)
    }

    // ── Validation (cross-record) ─────────────────────────────────────────────

    /**
     * Everything a single record cannot check on its own. Throws [BackupParseException] on the first violation; nothing has been
     * written by then, so a malformed section can never reach the database.
     */
    fun validate(history: PendingHistoryBackup) {
        val core = history.core
        if (core.tracks.size > PendingHistoryLimits.MAX_TRACKS || history.events.size > PendingHistoryLimits.MAX_EVENTS) {
            throw BackupParseException(DAMAGED)
        }

        val keys = HashSet<String>(core.tracks.size * 2)
        for (t in core.tracks) {
            if (t.status != STATUS_PENDING) throw BackupParseException(DAMAGED)
            if (t.originKey != "${t.backupFingerprint}|${t.backupSongId}") throw BackupParseException(DAMAGED)
            if (!keys.add(t.originKey)) throw BackupParseException(DAMAGED)   // two tracks claiming one origin key
        }
        uniqueRefs(core.stats.map { it.trackOriginKey }, keys)           // 1:1 with its track
        uniqueRefs(core.lyricsOverrides.map { it.trackOriginKey }, keys) // 1:1 with its track
        uniqueFingerprints(core.lyricsOverrides.map { it.originFingerprint })
        refsExist(core.importBaselines.map { it.trackOriginKey }, keys)
        uniqueFingerprints(core.importBaselines.map { it.originFingerprint })
        refsExist(core.playlistEntries.map { it.trackOriginKey }, keys)
        uniqueFingerprints(core.playlistEntries.map { it.originFingerprint })
        refsExist(history.events.map { it.trackOriginKey }, keys)
        uniqueFingerprints(history.events.map { it.originFingerprint })
    }

    private fun uniqueRefs(refs: List<String>, keys: Set<String>) {
        val seen = HashSet<String>(refs.size * 2)
        for (r in refs) if (r !in keys || !seen.add(r)) throw BackupParseException(DAMAGED)
    }

    private fun refsExist(refs: List<String>, keys: Set<String>) {
        for (r in refs) if (r !in keys) throw BackupParseException(DAMAGED)
    }

    private fun uniqueFingerprints(fps: List<String>) {
        val seen = HashSet<String>(fps.size * 2)
        for (f in fps) if (!seen.add(f)) throw BackupParseException(DAMAGED)
    }

    // ── Digest ────────────────────────────────────────────────────────────────

    /**
     * SHA-256 (hex) over a canonical, order-independent, length-prefixed rendering of every field. Independent of JSON formatting,
     * key order and record order, so any packaging that decodes to the same records yields the same digest. Call on validated data
     * (keys / fingerprints unique), which makes the sort total.
     */
    fun digest(history: PendingHistoryBackup): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun token(v: Any?) {
            val s = when (v) { null -> "~"; is Boolean -> if (v) "t" else "f"; else -> "=$v" }
            md.update("${s.length}:".toByteArray(Charsets.UTF_8)); md.update(s.toByteArray(Charsets.UTF_8)); md.update(0)
        }
        fun section(name: String, count: Int) { token("#$name"); token(count) }
        val c = history.core
        section("tracks", c.tracks.size)
        c.tracks.sortedBy { it.originKey }.forEach { t ->
            token(t.originKey); token(t.backupFingerprint); token(t.backupSongId); token(t.title); token(t.artist); token(t.album)
            token(t.duration); token(t.trackNumber); token(t.year); token(t.sourceUri); token(t.sourceFolderPath); token(t.restoredAt); token(t.status)
        }
        section("stats", c.stats.size)
        c.stats.sortedBy { it.trackOriginKey }.forEach { s ->
            token(s.trackOriginKey); token(s.playCount); token(s.skipCount); token(s.lastPlayedAt); token(s.totalListeningTimeMs)
            token(s.isFavorite); token(s.lastListenedAt)
        }
        section("lyrics", c.lyricsOverrides.size)
        c.lyricsOverrides.sortedBy { it.originFingerprint }.forEach { l ->
            token(l.originFingerprint); token(l.trackOriginKey); token(l.lyrics); token(l.updatedAt)
        }
        section("baselines", c.importBaselines.size)
        c.importBaselines.sortedBy { it.originFingerprint }.forEach { b ->
            token(b.originFingerprint); token(b.trackOriginKey); token(b.sourceType); token(b.sourceKey); token(b.playCount)
        }
        section("playlistEntries", c.playlistEntries.size)
        c.playlistEntries.sortedBy { it.originFingerprint }.forEach { p ->
            token(p.originFingerprint); token(p.trackOriginKey); token(p.playlistName); token(p.position)
            token(p.sourcePlaylistId); token(p.sourcePlaylistCreatedAt); token(p.sourcePlaylistUpdatedAt)
        }
        section("events", history.events.size)
        history.events.sortedBy { it.originFingerprint }.forEach { e ->
            token(e.originFingerprint); token(e.trackOriginKey); token(e.eventType); token(e.occurredAt); token(e.listenedMs); token(e.durationMs)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Field helpers (strict; Double is never coerced to an integer) ─────────

    private fun Map<*, *>.array(name: String, max: Int): List<*> {
        val list = this[name] as? List<*> ?: throw BackupParseException(DAMAGED)
        if (list.size > max) throw BackupParseException(DAMAGED)
        return list
    }

    private fun <T> List<*>.mapObjects(arrayName: String, transform: (path: String, item: Map<*, *>) -> T): List<T> =
        mapIndexed { i, v ->
            transform("$arrayName[$i]", v as? Map<*, *> ?: throw BackupParseException(DAMAGED))
        }

    private fun Map<*, *>.str(path: String, name: String, maxChars: Int, nonBlank: Boolean = false): String {
        val v = this[name] as? String ?: throw BackupParseException("Field $path must be a string")
        if (v.length > maxChars || (nonBlank && v.isBlank())) throw BackupParseException(DAMAGED)
        return v
    }

    private fun Map<*, *>.optStr(path: String, name: String, maxChars: Int): String? {
        val raw = this[name] ?: return null
        val v = raw as? String ?: throw BackupParseException("Field $path must be a string")
        if (v.length > maxChars) throw BackupParseException(DAMAGED)
        return v
    }

    private fun Map<*, *>.long(path: String, name: String, min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): Long {
        val v = this[name] as? Long ?: throw BackupParseException("Field $path must be an integer")
        if (v < min || v > max) throw BackupParseException("Field $path is out of the supported range")
        return v
    }

    private fun Map<*, *>.optLong(path: String, name: String, min: Long): Long? {
        val raw = this[name] ?: return null
        val v = raw as? Long ?: throw BackupParseException("Field $path must be an integer")
        if (v < min) throw BackupParseException("Field $path is out of the supported range")
        return v
    }

    private fun Map<*, *>.int(path: String, name: String, min: Int = Int.MIN_VALUE): Int =
        long(path, name, min.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun Map<*, *>.bool(path: String, name: String): Boolean =
        this[name] as? Boolean ?: throw BackupParseException("Field $path must be a boolean")
}
