package com.launchpoint.wavdrop.data.repository

sealed class LibrarySyncResult {
    data class Success(val songCount: Int) : LibrarySyncResult()
    /** Scan returned no songs but the existing library was preserved rather than deleted. */
    data class EmptyPreserved(val reason: String) : LibrarySyncResult()

    /**
     * The scan could not complete (permission revoked, MediaStore failure, cursor error).
     * No database mutation was performed; the existing library is left untouched. A failed
     * scan is deliberately distinct from [EmptyPreserved] / [Success] with 0 songs (WB-02).
     */
    data class Failed(val reason: String) : LibrarySyncResult()
}
