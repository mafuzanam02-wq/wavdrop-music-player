package com.launchpoint.wavdrop.data.backup

/**
 * The degree to which a parsed backup's contents can be trusted.
 * Determined at parse time and carried through to the UI.
 */
enum class BackupIntegrityStatus {
    /** Checksum present and verified; manifest counts match. */
    VERIFIED,

    /**
     * Valid v1 structure, but predates checksum support.
     * Contents are structurally sound but cannot be verified.
     */
    UNVERIFIED_LEGACY,

    /** Malformed, corrupt, tampered, or unsupported format/version. */
    INVALID,
}
