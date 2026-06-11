package com.launchpoint.wavdrop.data.backup

object WavdropBackupParser {
    const val SUPPORTED_FORMAT = "wavdrop_backup"
    const val SUPPORTED_VERSION = 1

    private val requiredRootFields = listOf(
        "app",
        "format",
        "version",
        "songs",
        "trackStats",
        "importBaselines",
    )

    fun parse(content: String): WavdropBackupImportResult {
        if (content.isBlank()) {
            return failure("The selected file is empty.")
        }

        val root = try {
            JsonReader(content).parse() as? Map<*, *> ?: return failure("Malformed JSON")
        } catch (_: JsonParseException) {
            return failure("Malformed JSON")
        }

        requiredRootFields.firstOrNull { !root.hasField(it) }?.let { field ->
            return failure("Missing field: $field")
        }

        val format = root["format"] as? String
        if (format != SUPPORTED_FORMAT) {
            return failure("Invalid backup format")
        }

        val version = try {
            root.requiredInt("version", "version")
        } catch (_: BackupParseException) {
            return failure("Unsupported backup version: ${root["version"]}")
        }
        if (version != SUPPORTED_VERSION) {
            return failure("Unsupported backup version: $version")
        }

        return try {
            val songs = root.requiredArray("songs").mapObjects("songs") { index, item ->
                BackupSong(
                    id = item.requiredLong("songs[$index].id", "id"),
                    uri = item.requiredString("songs[$index].uri", "uri"),
                    title = item.requiredString("songs[$index].title", "title"),
                    artist = item.requiredString("songs[$index].artist", "artist"),
                    album = item.requiredString("songs[$index].album", "album"),
                    albumId = item.requiredLong("songs[$index].albumId", "albumId"),
                    duration = item.requiredLong("songs[$index].duration", "duration"),
                    dateAdded = item.requiredLong("songs[$index].dateAdded", "dateAdded"),
                    trackNumber = item.requiredInt("songs[$index].trackNumber", "trackNumber"),
                    year = item.requiredInt("songs[$index].year", "year"),
                    // Optional identity fields added after v1 launch; absent in older backups.
                    folderPath = item["folderPath"] as? String,
                    folderName = item["folderName"] as? String,
                )
            }

            val trackStats = root.requiredArray("trackStats").mapObjects("trackStats") { index, item ->
                BackupTrackStats(
                    songId = item.requiredLong("trackStats[$index].songId", "songId"),
                    contentUri = item.requiredString("trackStats[$index].contentUri", "contentUri"),
                    playCount = item.requiredInt("trackStats[$index].playCount", "playCount"),
                    skipCount = item.requiredInt("trackStats[$index].skipCount", "skipCount"),
                    lastPlayedAt = item.requiredLong("trackStats[$index].lastPlayedAt", "lastPlayedAt"),
                    totalListeningTimeMs = item.requiredLong(
                        "trackStats[$index].totalListeningTimeMs",
                        "totalListeningTimeMs",
                    ),
                    isFavorite = item.requiredBoolean("trackStats[$index].isFavorite", "isFavorite"),
                )
            }

            val importBaselines = root.requiredArray("importBaselines")
                .mapObjects("importBaselines") { index, item ->
                    BackupImportBaseline(
                        songId = item.requiredLong("importBaselines[$index].songId", "songId"),
                        sourceType = item.requiredString(
                            "importBaselines[$index].sourceType",
                            "sourceType",
                        ),
                        sourceKey = item.requiredString(
                            "importBaselines[$index].sourceKey",
                            "sourceKey",
                        ),
                        lastImportedPlayCount = item.requiredInt(
                            "importBaselines[$index].lastImportedPlayCount",
                            "lastImportedPlayCount",
                        ),
                        lastImportedSkipCount = item.requiredInt(
                            "importBaselines[$index].lastImportedSkipCount",
                            "lastImportedSkipCount",
                        ),
                        lastImportedAt = item.requiredLong(
                            "importBaselines[$index].lastImportedAt",
                            "lastImportedAt",
                        ),
                    )
                }

            val lyricsOverrides = (root["lyricsOverrides"] as? List<*>)
                ?.mapObjects("lyricsOverrides") { index, item ->
                    BackupLyricsOverride(
                        songId     = item.requiredLong("lyricsOverrides[$index].songId", "songId"),
                        contentUri = item.requiredString("lyricsOverrides[$index].contentUri", "contentUri"),
                        lyrics     = item.requiredString("lyricsOverrides[$index].lyrics", "lyrics"),
                        updatedAt  = item.requiredLong("lyricsOverrides[$index].updatedAt", "updatedAt"),
                    )
                } ?: emptyList()

            val preferences = (root["preferences"] as? Map<*, *>)?.let { obj ->
                BackupPreferences(
                    startupDestination          = obj["startupDestination"] as? String,
                    mostPlayedPeriod            = obj["mostPlayedPeriod"] as? String,
                    mostPlayedLimit             = obj["mostPlayedLimit"] as? String,
                    homeVisibleSections         = (obj["homeVisibleSections"] as? List<*>)
                        ?.filterIsInstance<String>(),
                    scanMode                    = obj["scanMode"] as? String,
                    selectedFolderUris          = (obj["selectedFolderUris"] as? List<*>)
                        ?.filterIsInstance<String>(),
                    minimumTrackDurationSeconds = (obj["minimumTrackDurationSeconds"] as? Long)?.toInt(),
                    themeMode                   = obj["themeMode"] as? String,
                    accentColor                 = obj["accentColor"] as? String,
                    launcherIcon                = obj["launcherIcon"] as? String,
                    compactMode                 = obj["compactMode"] as? Boolean,
                    backupFileMode              = obj["backupFileMode"] as? String,
                    autoBackupInterval          = obj["autoBackupInterval"] as? String,
                )
            }

            val playlists = (root["playlists"] as? List<*>)
                ?.mapObjects("playlists") { pi, playlist ->
                    BackupPlaylist(
                        id        = playlist.requiredLong("playlists[$pi].id", "id"),
                        name      = playlist.requiredString("playlists[$pi].name", "name"),
                        createdAt = playlist.requiredLong("playlists[$pi].createdAt", "createdAt"),
                        updatedAt = playlist.requiredLong("playlists[$pi].updatedAt", "updatedAt"),
                        songs     = (playlist["songs"] as? List<*>)
                            ?.mapObjects("playlists[$pi].songs") { si, song ->
                                BackupPlaylistSong(
                                    songId     = song.requiredLong("playlists[$pi].songs[$si].songId", "songId"),
                                    contentUri = song.requiredString("playlists[$pi].songs[$si].contentUri", "contentUri"),
                                    position   = song.requiredInt("playlists[$pi].songs[$si].position", "position"),
                                    title      = song.requiredString("playlists[$pi].songs[$si].title", "title"),
                                    artist     = song.requiredString("playlists[$pi].songs[$si].artist", "artist"),
                                    album      = song.requiredString("playlists[$pi].songs[$si].album", "album"),
                                )
                            } ?: emptyList(),
                    )
                } ?: emptyList()

            val listenEvents = (root["listenEvents"] as? List<*>)
                ?.mapObjects("listenEvents") { index, item ->
                    BackupListenEvent(
                        songId     = item.requiredLong("listenEvents[$index].songId", "songId"),
                        contentUri = item.requiredString("listenEvents[$index].contentUri", "contentUri"),
                        title      = item.requiredString("listenEvents[$index].title", "title"),
                        artist     = item.requiredString("listenEvents[$index].artist", "artist"),
                        album      = item.requiredString("listenEvents[$index].album", "album"),
                        eventType  = item.requiredString("listenEvents[$index].eventType", "eventType"),
                        occurredAt = item.requiredLong("listenEvents[$index].occurredAt", "occurredAt"),
                        listenedMs = item.requiredLong("listenEvents[$index].listenedMs", "listenedMs"),
                        durationMs = item.requiredLong("listenEvents[$index].durationMs", "durationMs"),
                        source     = item.requiredString("listenEvents[$index].source", "source"),
                    )
                } ?: emptyList()

            success(
                WavdropBackup(
                    exportedAt      = root["exportedAt"] as? String ?: "",
                    songs           = songs,
                    trackStats      = trackStats,
                    importBaselines = importBaselines,
                    lyricsOverrides = lyricsOverrides,
                    preferences     = preferences,
                    playlists       = playlists,
                    listenEvents    = listenEvents,
                )
            )
        } catch (e: BackupParseException) {
            failure(e.message ?: "Invalid backup file")
        }
    }

    private fun success(backup: WavdropBackup) = WavdropBackupImportResult(
        backup = backup,
        error = null,
    )

    private fun failure(message: String) = WavdropBackupImportResult(
        backup = null,
        error = message,
    )
}

private class BackupParseException(message: String) : IllegalArgumentException(message)
private class JsonParseException(message: String) : IllegalArgumentException(message)

private fun Map<*, *>.hasField(name: String): Boolean = containsKey(name) && this[name] != null

private fun Map<*, *>.requiredArray(name: String): List<*> {
    if (!hasField(name)) throw BackupParseException("Missing field: $name")
    return this[name] as? List<*>
        ?: throw BackupParseException("Field $name must be an array")
}

private fun <T> List<*>.mapObjects(
    arrayName: String,
    transform: (index: Int, item: Map<*, *>) -> T,
): List<T> = mapIndexed { index, value ->
    val item = value as? Map<*, *>
        ?: throw BackupParseException("Field $arrayName[$index] must be an object")
    transform(index, item)
}

private fun Map<*, *>.requiredString(path: String, name: String): String {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    return this[name] as? String
        ?: throw BackupParseException("Field $path must be a string")
}

private fun Map<*, *>.requiredLong(path: String, name: String): Long {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    return when (val value = this[name]) {
        is Long -> value
        is Double -> value.toWholeLongOrNull()
        else -> null
    } ?: throw BackupParseException("Field $path must be a number")
}

private fun Map<*, *>.requiredInt(path: String, name: String): Int {
    val longValue = requiredLong(path, name)
    if (longValue < Int.MIN_VALUE || longValue > Int.MAX_VALUE) {
        throw BackupParseException("Field $path must be a number")
    }
    return longValue.toInt()
}

private fun Map<*, *>.requiredBoolean(path: String, name: String): Boolean {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    return this[name] as? Boolean
        ?: throw BackupParseException("Field $path must be a boolean")
}

private fun Double.toWholeLongOrNull(): Long? {
    val asLong = toLong()
    return if (isFinite() && this == asLong.toDouble()) asLong else null
}

private class JsonReader(private val input: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (index != input.length) fail("Unexpected trailing content")
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= input.length) fail("Unexpected end of input")
        return when (val char = input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-' -> parseNumber()
            in '0'..'9' -> parseNumber()
            else -> fail("Unexpected character: $char")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        consume('{')
        skipWhitespace()
        if (tryConsume('}')) return emptyMap()

        val output = linkedMapOf<String, Any?>()
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("Expected object key")
            val key = parseString()
            skipWhitespace()
            consume(':')
            output[key] = parseValue()
            skipWhitespace()
            when {
                tryConsume(',') -> Unit
                tryConsume('}') -> return output
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        consume('[')
        skipWhitespace()
        if (tryConsume(']')) return emptyList()

        val output = mutableListOf<Any?>()
        while (true) {
            output += parseValue()
            skipWhitespace()
            when {
                tryConsume(',') -> Unit
                tryConsume(']') -> return output
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        consume('"')
        val output = StringBuilder()
        while (index < input.length) {
            when (val char = input[index++]) {
                '"' -> return output.toString()
                '\\' -> output.append(parseEscape())
                else -> output.append(char)
            }
        }
        fail("Unterminated string")
    }

    private fun parseEscape(): Char {
        if (index >= input.length) fail("Unterminated escape")
        return when (val escaped = input[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail("Invalid escape: $escaped")
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > input.length) fail("Invalid unicode escape")
        val hex = input.substring(index, index + 4)
        index += 4
        return hex.toIntOrNull(16)?.toChar()
            ?: fail("Invalid unicode escape")
    }

    private fun parseNumber(): Number {
        val start = index
        if (peek() == '-') index++
        consumeDigits()
        val hasFraction = if (peek() == '.') {
            index++
            consumeDigits()
            true
        } else {
            false
        }
        val hasExponent = if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            consumeDigits()
            true
        } else {
            false
        }

        val raw = input.substring(start, index)
        return if (hasFraction || hasExponent) {
            raw.toDoubleOrNull() ?: fail("Invalid number")
        } else {
            raw.toLongOrNull() ?: fail("Invalid number")
        }
    }

    private fun consumeDigits() {
        val start = index
        while (peek() in '0'..'9') index++
        if (start == index) fail("Expected digit")
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!input.startsWith(literal, index)) fail("Expected $literal")
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (peek().isWhitespace()) index++
    }

    private fun consume(expected: Char) {
        if (!tryConsume(expected)) fail("Expected '$expected'")
    }

    private fun tryConsume(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun peek(): Char = input.getOrNull(index) ?: '\u0000'

    private fun fail(message: String): Nothing {
        throw JsonParseException(message)
    }
}
