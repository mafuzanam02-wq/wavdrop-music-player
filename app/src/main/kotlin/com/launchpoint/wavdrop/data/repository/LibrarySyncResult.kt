package com.launchpoint.wavdrop.data.repository

sealed class LibrarySyncResult {
    data class Success(val songCount: Int) : LibrarySyncResult()
    /** Scan returned no songs but the existing library was preserved rather than deleted. */
    data class EmptyPreserved(val reason: String) : LibrarySyncResult()
}
