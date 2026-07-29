package com.launchpoint.wavdrop

/**
 * Validates external audio open requests before MainActivity hands a Uri to playback.
 * Kept pure so explicit-intent bypass cases can be pinned in JVM tests.
 */
object ExternalAudioIntentValidator {
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private val allowedSchemes = setOf("content", "file")

    fun isAllowedAudioViewIntent(
        action: String?,
        uriString: String?,
        intentMimeType: String?,
        resolvedMimeTypeProvider: (String) -> String? = { null },
    ): Boolean {
        if (action != ACTION_VIEW) return false
        val uri = uriString?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val scheme = uri.substringBefore(':', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotBlank() }
            ?: return false
        if (scheme !in allowedSchemes) return false
        if (uri.substringAfter(':', missingDelimiterValue = "").isBlank()) return false

        if (isSupportedAudioMimeType(intentMimeType)) return true
        val resolvedType = runCatching { resolvedMimeTypeProvider(uri) }.getOrNull()
        if (isSupportedAudioMimeType(resolvedType)) return true

        return scheme == "file" && isSupportedAudioFileExtension(uri)
    }

    fun isSupportedAudioMimeType(mimeType: String?): Boolean {
        val normalized = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return normalized == "audio/*" || normalized.startsWith("audio/")
    }

    private fun isSupportedAudioFileExtension(uri: String): Boolean {
        val path = uri.substringBefore('?').substringBefore('#').lowercase()
        return listOf(
            ".mp3",
            ".m4a",
            ".mp4",
            ".aac",
            ".flac",
            ".ogg",
            ".oga",
            ".wav",
            ".opus",
            ".amr",
            ".mid",
            ".midi",
        ).any(path::endsWith)
    }
}
