package com.launchpoint.wavdrop.data.playlists

sealed interface PlaylistValidationResult {
    data object Valid         : PlaylistValidationResult
    data object BlankName     : PlaylistValidationResult
    data object DuplicateName : PlaylistValidationResult
}

object PlaylistNameRules {

    fun normalize(name: String): String = name.trim()

    fun validate(
        name: String,
        existingNames: Collection<String>,
    ): PlaylistValidationResult {
        val trimmed = normalize(name)
        if (trimmed.isBlank()) return PlaylistValidationResult.BlankName
        if (existingNames.any { it.equals(trimmed, ignoreCase = true) }) {
            return PlaylistValidationResult.DuplicateName
        }
        return PlaylistValidationResult.Valid
    }
}
