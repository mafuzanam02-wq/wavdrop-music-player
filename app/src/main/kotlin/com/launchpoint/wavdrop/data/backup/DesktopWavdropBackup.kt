package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity

data class DesktopBackupPlaylist(
    val id: String,
    val name: String,
    /** Desktop-local song IDs in playlist order. Each references a song in [DesktopWavdropBackup.songs]. */
    val songIds: List<String>,
)

data class DesktopWavdropBackup(
    val schemaVersion: Int,
    val exportedAt: String,
    val appName: String,
    val sourcePlatform: String?,
    val folderPath: String?,
    val songs: List<DesktopBackupSong>,
    val playlists: List<DesktopBackupPlaylist> = emptyList(),
    val listenEvents: List<DesktopBackupListenEvent> = emptyList(),
)

data class DesktopBackupSong(
    /** Desktop-local string ID (e.g. SHA-1 hash). Not portable to Android. */
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val playCount: Int,
    val totalListeningTimeMs: Long,
    val lastPlayedAt: Long,
    val favorite: Boolean,
)

data class DesktopBackupListenEvent(
    /** Desktop-local string ID. Resolved through [DesktopWavdropBackup.songs] when possible. */
    val songId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val occurredAt: Long,
    val listenedMs: Long,
    val eventType: String,
    val source: String,
)

data class DesktopWavdropBackupParseResult(
    val backup: DesktopWavdropBackup?,
    val error: String?,
)

object DesktopWavdropBackupParser {
    const val APP_NAME = "wavdrop-desktop-lab"
    const val SOURCE_PLATFORM_DESKTOP = "desktop"
    const val SUPPORTED_SCHEMA_VERSION = 1

    /**
     * Detects desktop-origin backup content before full parsing.
     * Accepts both the legacy pure-desktop format (appName field) and the newer
     * shared format that also carries sourcePlatform = "desktop".
     */
    fun isDesktopBackupContent(content: String): Boolean =
        (content.contains("\"appName\"") && content.contains("\"$APP_NAME\"")) ||
            (content.contains("\"sourcePlatform\"") && content.contains("\"$SOURCE_PLATFORM_DESKTOP\""))

    fun parse(content: String): DesktopWavdropBackupParseResult {
        if (content.isBlank()) return failure("The selected file is empty.")
        val root = runCatching { org.json.JSONObject(content) }
            .getOrElse { return failure("Malformed JSON") }

        val appName = root.optString("appName")
        val sourcePlatform = root.optString("sourcePlatform").takeIf { it.isNotBlank() }

        val isDesktop = appName == APP_NAME || sourcePlatform == SOURCE_PLATFORM_DESKTOP
        if (!isDesktop) return failure("Invalid desktop backup format")

        val schemaVersion = parseSchemaVersion(root)
            ?: return failure("Unsupported desktop backup version: ${root.opt("schemaVersion")}")

        val songsArray = root.optJSONArray("songs") ?: return failure("Missing field: songs")
        val songs = buildList {
            for (i in 0 until songsArray.length()) {
                val item = songsArray.optJSONObject(i) ?: return failure("Invalid songs[$i]")
                add(
                    DesktopBackupSong(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        playCount = item.optInt("playCount", 0).coerceAtLeast(0),
                        totalListeningTimeMs = item.optLong("totalListeningTimeMs", 0L).coerceAtLeast(0L),
                        lastPlayedAt = if (item.isNull("lastPlayedAt")) 0L else item.optLong("lastPlayedAt", 0L),
                        favorite = item.optBoolean("favorite", false),
                    ),
                )
            }
        }

        val playlists = run {
            val arr = root.optJSONArray("playlists") ?: return@run emptyList<DesktopBackupPlaylist>()
            val result = mutableListOf<DesktopBackupPlaylist>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val name = p.optString("name").trim()
                if (name.isBlank()) continue
                val songIdArr = p.optJSONArray("songIds")
                val songIds = mutableListOf<String>()
                if (songIdArr != null) {
                    for (j in 0 until songIdArr.length()) {
                        val sid = songIdArr.optString(j)
                        if (sid.isNotBlank()) songIds += sid
                    }
                }
                result += DesktopBackupPlaylist(
                    id      = p.optString("id").ifBlank { i.toString() },
                    name    = name,
                    songIds = songIds,
                )
            }
            result
        }

        val listenEvents = run {
            val arr = root.optJSONArray("listenEvents") ?: return@run emptyList<DesktopBackupListenEvent>()
            val result = mutableListOf<DesktopBackupListenEvent>()
            for (i in 0 until arr.length()) {
                val event = arr.optJSONObject(i) ?: continue
                val source = event.optString("source")
                if (source != TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK) continue

                val songId = event.optString("songId").takeIf { it.isNotBlank() }
                val title = event.optString("title")
                val artist = event.optString("artist")
                val album = event.optString("album")
                val hasMetadataIdentity = title.isNotBlank() && artist.isNotBlank() && album.isNotBlank()
                if (songId == null && !hasMetadataIdentity) continue

                val occurredAt = event.optLong("occurredAt", 0L)
                val listenedMs = event.optLong("listenedMs", 0L)
                val durationMs = event.optLong("durationMs", 0L)
                if (occurredAt <= 0L || listenedMs <= 0L || durationMs < 0L) continue

                val eventType = event.optString("eventType")
                    .ifBlank { TrackListenEventEntity.TYPE_PLAY }
                if (eventType != TrackListenEventEntity.TYPE_PLAY &&
                    eventType != TrackListenEventEntity.TYPE_SKIP
                ) {
                    continue
                }

                result += DesktopBackupListenEvent(
                    songId     = songId,
                    title      = title,
                    artist     = artist,
                    album      = album,
                    durationMs = durationMs,
                    occurredAt = occurredAt,
                    listenedMs = listenedMs,
                    eventType  = eventType,
                    source     = source,
                )
            }
            result
        }

        return DesktopWavdropBackupParseResult(
            backup = DesktopWavdropBackup(
                schemaVersion  = schemaVersion,
                exportedAt     = root.optString("exportedAt"),
                appName        = appName.ifBlank { APP_NAME },
                sourcePlatform = sourcePlatform,
                folderPath     = root.optString("folderPath").takeIf { it.isNotBlank() },
                songs          = songs,
                playlists      = playlists,
                listenEvents   = listenEvents,
            ),
            error = null,
        )
    }

    private fun parseSchemaVersion(root: org.json.JSONObject): Int? {
        if (!root.has("schemaVersion") || root.isNull("schemaVersion")) {
            return SUPPORTED_SCHEMA_VERSION
        }
        val raw = root.opt("schemaVersion")
        val version = when (raw) {
            is Int -> raw
            is Long -> raw
                .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                ?.toInt()
            is Number -> raw.toInt().takeIf { raw.toDouble() == it.toDouble() }
            else -> null
        }
        return version?.takeIf { it == SUPPORTED_SCHEMA_VERSION }
    }

    private fun failure(message: String) = DesktopWavdropBackupParseResult(
        backup = null,
        error = message,
    )
}
