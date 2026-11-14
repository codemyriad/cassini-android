package io.codemyriad.cassini.core.backup

/** Device-local D97 choices; restoring a library never restores an old cloud opt-in. */
data class BackupChoices(
    val transfer: Boolean = true,
    val cloud: Boolean = false,
    val noticeAcknowledged: Boolean = false,
    val quotaExceeded: Boolean = false,
) {
    /** Unknown transports require cloud consent. Android exposes transport flags from API 28 onward. */
    fun permits(api: Int, transportFlags: Int, cloudEnabled: Boolean = false): Boolean =
        if (api >= TRANSPORT_FLAGS_API && transportFlags and TRANSFER_FLAGS != 0) transfer else cloudEnabled && cloud

    val showTransferNotice: Boolean get() = transfer && !noticeAcknowledged

    /**
     * Whether an automatic copy can leave the phone, by the same rules as [permits]: cloud Auto Backup needs the
     * build flag and the opt-in; device-to-device transfer needs the choice and API 28's transport flags (the
     * API 26-27 rules exclude every backup domain). The first-run privacy line (D98) follows these values.
     */
    fun cloudActive(cloudEnabled: Boolean): Boolean = cloudEnabled && cloud

    fun transferActive(api: Int): Boolean = api >= TRANSPORT_FLAGS_API && transfer

    companion object {
        const val TRANSPORT_FLAGS_API = 28

        // BackupAgent: device-to-device (2), and API 36.1's direct cross-platform transfer (8).
        private const val TRANSFER_FLAGS = 2 or 8

        /** Corrupt or unreadable preferences cannot re-enable an earlier opt-out or cloud upload. */
        val UNREADABLE = BackupChoices(transfer = false, cloud = false)
    }
}
