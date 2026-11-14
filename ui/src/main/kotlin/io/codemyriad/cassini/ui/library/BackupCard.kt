@file:Suppress("FunctionNaming")

package io.codemyriad.cassini.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.BannerCard
import io.codemyriad.cassini.ui.components.InkButton
import io.codemyriad.cassini.ui.components.SecondaryButton
import io.codemyriad.cassini.ui.theme.Tone

/**
 * The once-only backup card (PARITY Wave 6 contract §3, D98), shown while encrypted cloud backup is on: "Keep them on
 * this phone only", what is copied and why, then "Keep on this phone only" (cloud backup and device-to-device transfer
 * off) and "Keep backup on". The close button ("Dismiss backup notice") keeps backup on too.
 */
@Composable
internal fun BackupCard(onEvent: (LibraryEvent) -> Unit, modifier: Modifier = Modifier) {
    BannerCard(
        tone = Tone.Info,
        title = stringResource(R.string.ui_backup_card_title),
        body = stringResource(R.string.ui_backup_card_body),
        modifier = modifier,
        onDismiss = { onEvent(LibraryEvent.DismissBackupCard) },
        dismissLabel = stringResource(R.string.ui_backup_card_close),
        actions = {
            InkButton(
                stringResource(R.string.ui_backup_card_action),
                onClick = { onEvent(LibraryEvent.KeepOnPhoneOnly) },
            )
            SecondaryButton(
                stringResource(R.string.ui_backup_card_dismiss),
                onClick = { onEvent(LibraryEvent.DismissBackupCard) },
            )
        },
    )
}
