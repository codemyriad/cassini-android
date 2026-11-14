package io.codemyriad.cassini.core.backup

/**
 * Device-local backup choices (D97, D98); restoring a library never restores the source phone's choices.
 *
 * - [cloud]: encrypted Android cloud backup (Auto Backup through Google or Seedvault). On by default (D98).
 * - [transfer]: direct device-to-device transfer during a new phone's setup (D97). On by default.
 * - [voices]: "Include saved voices in backups". On by default; off keeps every voiceprint out of both channels.
 * - [noticeAcknowledged]: the once-only "Keep them on this phone only" card was answered, or a choice was changed in
 *   Settings.
 * - [quotaExceeded] / [cloudQuotaExceeded]: the last transfer / cloud attempt was refused for its size.
 */
data class BackupChoices(
    val transfer: Boolean = true,
    val cloud: Boolean = true,
    val voices: Boolean = true,
    val noticeAcknowledged: Boolean = false,
    val quotaExceeded: Boolean = false,
    val cloudQuotaExceeded: Boolean = false,
) {
    /**
     * Whether the agent may emit library bytes to this transport. Android exposes transport flags from API 28; older
     * versions cannot tell transfer from cloud, so they refuse both. Device-to-device transfer follows [transfer].
     * Every other transport is cloud and needs [cloud] plus client-side encryption, so "Encrypted cloud backup" is
     * true whenever bytes leave; an unencrypted or unknown transport is refused.
     */
    fun permits(api: Int, transportFlags: Int, cloudEnabled: Boolean = false): Boolean = when {
        api < TRANSPORT_FLAGS_API -> false
        isTransfer(api, transportFlags) -> transfer
        transportFlags and CLIENT_SIDE_ENCRYPTION != 0 -> cloudEnabled && cloud
        else -> false
    }

    /**
     * Whether an automatic copy can leave the phone, by the same rules as [permits]: cloud needs the build flag, the
     * choice and API 28's transport flags; so does device-to-device transfer (the API 26-27 rules exclude every backup
     * domain). The first-run privacy line, the Settings copy and the phone-only card (D98) follow these values.
     */
    fun cloudActive(api: Int, cloudEnabled: Boolean): Boolean = api >= TRANSPORT_FLAGS_API && cloudEnabled && cloud

    fun transferActive(api: Int): Boolean = api >= TRANSPORT_FLAGS_API && transfer

    /** Cloud backup and transfer both off: only now may copy say "this phone only" (PARITY Wave 6 contract §3). */
    fun phoneOnly(api: Int, cloudEnabled: Boolean): Boolean = !cloudActive(api, cloudEnabled) && !transferActive(api)

    /** The "Keep them on this phone only" card shows once, while cloud backup is on (D98). */
    fun showBackupCard(api: Int, cloudEnabled: Boolean): Boolean = cloudActive(api, cloudEnabled) && !noticeAcknowledged

    companion object {
        const val TRANSPORT_FLAGS_API = 28

        // BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED.
        private const val CLIENT_SIDE_ENCRYPTION = 1

        // BackupAgent: device-to-device (2), and API 36.1's direct cross-platform transfer (8).
        private const val TRANSFER_FLAGS = 2 or 8

        /** A device-to-device transport, by the same flags as [permits]. */
        fun isTransfer(api: Int, transportFlags: Int): Boolean =
            api >= TRANSPORT_FLAGS_API && transportFlags and TRANSFER_FLAGS != 0

        /** Corrupt or unreadable preferences cannot re-enable an earlier opt-out or cloud upload. */
        val UNREADABLE = BackupChoices(transfer = false, cloud = false, voices = false)
    }
}
