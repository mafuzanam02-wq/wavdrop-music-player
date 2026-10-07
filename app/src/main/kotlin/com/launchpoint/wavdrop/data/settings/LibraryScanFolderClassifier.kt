package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.Song
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * SE-1: the ONE path policy that decides which preset folder category (if any) a song's folder belongs to. Pure (no Android).
 * [LibraryScanSettingsRules] consults it; nothing else classifies folders, and the scanner applies the result in Kotlin after
 * reading MediaStore (never also in SQL).
 *
 * The input is the song's folder path only (MediaStore RELATIVE_PATH such as `Download/`, or the pre-Q DATA parent such as
 * `/storage/emulated/0/Download`). It is normalized to PATH SEGMENTS: URL-decoded, `\` -> `/`, empty segments dropped,
 * lower-cased, and a leading shared-storage prefix removed (`storage/emulated/<n>`, `storage/self/primary`, `sdcard`,
 * `mnt/sdcard`, `storage/<XXXX-XXXX>` SD-card volumes). Matching is by whole segment equality, never substring, so
 * `Music/My Downloads`, `Music/Telegram Tribute`, `Music/Signal Fire` and `Music/The Messengers` are ordinary music.
 *
 * Rules (after prefix removal, `s` = segments):
 *  - DOWNLOADS : `s[0]` is `download` or `downloads` (the shared-storage Downloads root and its descendants only; a folder
 *                named Download nested elsewhere is NOT the system folder).
 *  - RECORDINGS: `s[0]` is `recordings` or `recorder` (root-level only; `Music/Live Recordings` is NOT matched).
 *  - TELEGRAM  : `s[0]` is `telegram` (legacy root, `Telegram/Telegram Audio/...`), or the Android app-media/data root of a
 *                Telegram package: `android/(media|data)/<package>` with <package> in [TELEGRAM_PACKAGES].
 *  - SIGNAL    : `android/(media|data)/<package>` with <package> in [SIGNAL_PACKAGES].
 *  - MESSENGER : `android/(media|data)/<package>` with <package> in [MESSENGER_PACKAGES].
 * A folder NAME alone (no path) is deliberately not classified: without structure it could be any nested user folder.
 * A false exclusion is worse than missing an unusual OEM folder, so the lists are short and well-supported.
 */
object LibraryScanFolderClassifier {

    val TELEGRAM_PACKAGES: Set<String> = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
    )
    val SIGNAL_PACKAGES: Set<String> = setOf("org.thoughtcrime.securesms")
    val MESSENGER_PACKAGES: Set<String> = setOf("com.facebook.orca", "com.facebook.mlite")

    private val DOWNLOAD_ROOTS = setOf("download", "downloads")
    private val RECORDING_ROOTS = setOf("recordings", "recorder")
    private val SD_VOLUME_ID = Regex("^[0-9a-z]{4}-[0-9a-z]{4}$")

    /** The preset category [song]'s folder belongs to, or null (ordinary music). Categories are mutually exclusive. */
    fun classify(song: Song): LibraryScanExclusion? = classifyPath(song.folderPath)

    fun classifyPath(folderPath: String?): LibraryScanExclusion? {
        val segments = segments(folderPath)
        if (segments.isEmpty()) return null
        return LibraryScanExclusion.entries.firstOrNull { matches(it, segments) }
    }

    fun matches(exclusion: LibraryScanExclusion, song: Song): Boolean =
        matches(exclusion, segments(song.folderPath))

    private fun matches(exclusion: LibraryScanExclusion, s: List<String>): Boolean {
        if (s.isEmpty()) return false
        return when (exclusion) {
            LibraryScanExclusion.DOWNLOADS -> s[0] in DOWNLOAD_ROOTS
            LibraryScanExclusion.RECORDINGS -> s[0] in RECORDING_ROOTS
            LibraryScanExclusion.TELEGRAM -> s[0] == "telegram" || androidAppPackage(s) in TELEGRAM_PACKAGES
            LibraryScanExclusion.SIGNAL -> androidAppPackage(s) in SIGNAL_PACKAGES
            LibraryScanExclusion.MESSENGER -> androidAppPackage(s) in MESSENGER_PACKAGES
        }
    }

    /** `android/media/<pkg>/...` or `android/data/<pkg>/...` -> `<pkg>`, else null. */
    private fun androidAppPackage(s: List<String>): String? =
        if (s.size >= 3 && s[0] == "android" && (s[1] == "media" || s[1] == "data")) s[2] else null

    /** Normalized structural segments with the shared-storage prefix removed (empty for null/blank). */
    internal fun segments(folderPath: String?): List<String> {
        if (folderPath.isNullOrBlank()) return emptyList()
        val raw = runCatching { URLDecoder.decode(folderPath, StandardCharsets.UTF_8.name()) }.getOrDefault(folderPath)
        val all = raw.replace('\\', '/').split('/')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        return all.drop(storagePrefixLength(all))
    }

    private fun storagePrefixLength(s: List<String>): Int = when {
        s.size >= 3 && s[0] == "storage" && s[1] == "emulated" && s[2].all { it.isDigit() } -> 3
        s.size >= 3 && s[0] == "storage" && s[1] == "self" && s[2] == "primary" -> 3
        s.size >= 2 && s[0] == "storage" && SD_VOLUME_ID.matches(s[1]) -> 2
        s.size >= 2 && s[0] == "mnt" && s[1] == "sdcard" -> 2
        s.isNotEmpty() && s[0] == "sdcard" -> 1
        else -> 0
    }
}
