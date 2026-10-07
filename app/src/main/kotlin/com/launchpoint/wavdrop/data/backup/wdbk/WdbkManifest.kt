package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupManifest
import com.launchpoint.wavdrop.data.backup.WavdropBackupExporterV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupSectionParser
import org.json.JSONArray
import org.json.JSONObject

/** Thrown for any structural/contract violation of an untrusted WDBK container. The message is user-presentable. */
internal class WdbkFormatException(message: String, val kind: Kind = Kind.DAMAGED) : Exception(message) {
    enum class Kind { NOT_A_CONTAINER, DAMAGED, INTEGRITY, NEWER_VERSION, TOO_LARGE, UNSUPPORTED }
}

/**
 * One physical payload entry as recorded in the manifest. [sha256] is over the exact UNCOMPRESSED bytes of the
 * entry and [byteLength] is their length; ZIP CRC values are never relied on. History chunks additionally carry
 * [chunkIndex] and [eventCount].
 */
data class WdbkEntryDescriptor(
    val path: String,
    val section: String,
    val sectionVersion: Int,
    val required: Boolean,
    val byteLength: Long,
    val sha256: String,
    val chunkIndex: Int? = null,
    val eventCount: Int? = null,
)

/**
 * `manifest.json` of a WDBK container: container identity/version, the logical-backup identity it wraps, the
 * section/count manifest, the Wavdrop semantic integrity fingerprint
 * ([com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2]) and one [WdbkEntryDescriptor] per payload
 * entry. The manifest does not hash itself; it is the root the entry hashes hang from.
 */
data class WdbkManifest(
    val format: String,
    val containerMajor: Int,
    val containerMinor: Int,
    val logicalFormat: String,
    val logicalVersion: Int,
    val backupId: String,
    val sourceInstallationId: String,
    val exportedAtMs: Long,
    val producerPlatform: String,
    val appVersionCode: Int?,
    val appVersionName: String?,
    val requiredCapabilities: List<String>,
    val optionalCapabilities: List<String>,
    val counts: BackupManifest,
    /** [com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2.fingerprint] of the logical backup. */
    val fingerprint: String,
    val entries: List<WdbkEntryDescriptor>,
) {
    fun toJsonBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", format)
        put("containerMajor", containerMajor)
        put("containerMinor", containerMinor)
        put("logicalFormat", logicalFormat)
        put("logicalVersion", logicalVersion)
        put("backupId", backupId)
        put("sourceInstallationId", sourceInstallationId)
        put("exportedAt", exportedAtMs)
        put("producer", JSONObject().apply {
            put("platform", producerPlatform)
            appVersionCode?.let { put("appVersionCode", it) }
            appVersionName?.let { put("appVersionName", it) }
        })
        put("requiredCapabilities", JSONArray().apply { requiredCapabilities.forEach { put(it) } })
        put("optionalCapabilities", JSONArray().apply { optionalCapabilities.forEach { put(it) } })
        put("counts", WavdropBackupExporterV2.manifestObject(counts))
        put("integrity", JSONObject().apply {
            put("v", 2)
            put("fingerprint", fingerprint)
        })
        put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("path", e.path)
                    put("section", e.section)
                    put("sectionVersion", e.sectionVersion)
                    put("required", e.required)
                    put("byteLength", e.byteLength)
                    put("sha256", e.sha256)
                    e.chunkIndex?.let { put("chunkIndex", it) }
                    e.eventCount?.let { put("eventCount", it) }
                })
            }
        })
    }

    internal companion object {
        /**
         * Parses a decoded manifest root. Checks container identity first and the container major BEFORE reading
         * anything else, so a future-major manifest with a different shape still yields the clear
         * "newer version of Wavdrop" message rather than a confusing field error.
         */
        fun fromParsed(root: Any?): WdbkManifest {
            val map = root as? Map<*, *> ?: throw WdbkFormatException(NOT_A_BACKUP)
            if (map["format"] != WdbkContainerVersion.FORMAT) throw WdbkFormatException(NOT_A_BACKUP, WdbkFormatException.Kind.NOT_A_CONTAINER)
            val major = map.int("containerMajor")
            if (major > WdbkContainerVersion.MAJOR) {
                throw WdbkFormatException(NEWER_VERSION, WdbkFormatException.Kind.NEWER_VERSION)
            }
            if (major < WdbkContainerVersion.MAJOR) throw WdbkFormatException("Unsupported backup container version: $major")
            val minor = map.int("containerMinor")
            if (minor < 0) throw WdbkFormatException("Invalid container minor version")

            val logicalVersion = map.int("logicalVersion")
            if (logicalVersion > WdbkContainerVersion.LOGICAL_VERSION) {
                throw WdbkFormatException(NEWER_VERSION, WdbkFormatException.Kind.NEWER_VERSION)
            }
            val logicalFormat = map.string("logicalFormat")
            if (logicalFormat != WdbkContainerVersion.LOGICAL_FORMAT || logicalVersion != WdbkContainerVersion.LOGICAL_VERSION) {
                throw WdbkFormatException("Unsupported backup content: $logicalFormat v$logicalVersion")
            }

            val producer = map["producer"] as? Map<*, *>
            val integrity = map["integrity"] as? Map<*, *> ?: throw WdbkFormatException("Missing required integrity data")
            if ((integrity["v"] as? Long) != 2L) throw WdbkFormatException("Integrity data is invalid or not recognised")

            val entries = (map["entries"] as? List<*> ?: throw WdbkFormatException("Missing field: entries")).map { raw ->
                val e = raw as? Map<*, *> ?: throw WdbkFormatException("Manifest entry must be an object")
                WdbkEntryDescriptor(
                    path           = e.string("path"),
                    section        = e.string("section"),
                    sectionVersion = e.int("sectionVersion"),
                    required       = e["required"] as? Boolean ?: throw WdbkFormatException("Missing field: entries[].required"),
                    byteLength     = e.long("byteLength"),
                    sha256         = e.string("sha256"),
                    chunkIndex     = if (e.containsKey("chunkIndex")) e.int("chunkIndex") else null,
                    eventCount     = if (e.containsKey("eventCount")) e.int("eventCount") else null,
                )
            }
            val counts = map["counts"] as? Map<*, *> ?: throw WdbkFormatException("Missing field: counts")

            return WdbkManifest(
                format               = WdbkContainerVersion.FORMAT,
                containerMajor       = major,
                containerMinor       = minor,
                logicalFormat        = logicalFormat,
                logicalVersion       = logicalVersion,
                backupId             = map.string("backupId"),
                sourceInstallationId = map.string("sourceInstallationId"),
                exportedAtMs         = map.long("exportedAt"),
                producerPlatform     = producer?.get("platform") as? String ?: "",
                appVersionCode       = (producer?.get("appVersionCode") as? Long)?.toInt(),
                appVersionName       = producer?.get("appVersionName") as? String,
                requiredCapabilities = map.stringList("requiredCapabilities"),
                optionalCapabilities = map.stringList("optionalCapabilities"),
                counts               = WavdropBackupSectionParser.manifestFromCounts(counts),
                fingerprint          = integrity["fingerprint"] as? String ?: throw WdbkFormatException("Missing integrity fingerprint"),
                entries              = entries,
            )
        }

        const val NOT_A_BACKUP = "This file is not a valid Wavdrop backup."
        const val NEWER_VERSION =
            "This backup was created by a newer version of Wavdrop. Update Wavdrop and try again."

        private fun Map<*, *>.string(name: String): String =
            this[name] as? String ?: throw WdbkFormatException("Missing or invalid field: $name")

        private fun Map<*, *>.long(name: String): Long =
            this[name] as? Long ?: throw WdbkFormatException("Missing or invalid field: $name")

        private fun Map<*, *>.int(name: String): Int {
            val v = long(name)
            if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw WdbkFormatException("Field $name is out of the supported range")
            return v.toInt()
        }

        private fun Map<*, *>.stringList(name: String): List<String> {
            val list = this[name] as? List<*> ?: throw WdbkFormatException("Missing field: $name")
            return list.map { it as? String ?: throw WdbkFormatException("$name must contain strings") }
        }
    }
}
