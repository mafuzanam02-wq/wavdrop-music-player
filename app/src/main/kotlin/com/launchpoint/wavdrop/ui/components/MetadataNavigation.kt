package com.launchpoint.wavdrop.ui.components

internal fun metadataNavigationKey(value: String, unknownLabel: String): String? =
    value.trim().takeIf { normalized ->
        normalized.isNotBlank() &&
            !normalized.equals(unknownLabel, ignoreCase = true) &&
            !normalized.equals("<unknown>", ignoreCase = true)
    }
