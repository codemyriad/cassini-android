@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.components.PrimaryButton
import io.codemyriad.cassini.ui.components.SecondaryButton
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** D103 floating notice after a saved meeting. Primary and close keep the existing D98 choices. */
@Composable
internal fun BackupCard(onEvent: (LibraryEvent) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Surface(
        modifier.fillMaxWidth().testTag("backup-notice"),
        shape = RoundedCornerShape(20.dp),
        color = c.raised,
        shadowElevation = 10.dp,
    ) {
        Box(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 14.dp)) {
                CassiniIcon(Glyph.PhoneTransfer, 24.dp, 1.7f, c.accentInk, null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        stringResource(R.string.ui_backup_card_title),
                        Modifier.padding(end = 36.dp).semantics { heading() },
                        style = CassiniTheme.type.button,
                        color = c.ink,
                    )
                    Text(
                        stringResource(R.string.ui_backup_card_body),
                        Modifier.padding(top = 4.dp, end = 8.dp),
                        style = CassiniTheme.type.body14,
                        color = c.text2,
                    )
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            stringResource(R.string.ui_backup_card_dismiss),
                            { onEvent(LibraryEvent.DismissBackupCard) },
                        )
                        SecondaryButton(
                            stringResource(R.string.ui_backup_card_action),
                            { onEvent(LibraryEvent.KeepOnPhoneOnly) },
                        )
                    }
                }
            }
            IconButton44(
                Glyph.Close,
                stringResource(R.string.ui_backup_card_close),
                { onEvent(LibraryEvent.DismissBackupCard) },
                Modifier.align(Alignment.TopEnd),
                iconSize = 15.dp,
                stroke = 2.2f,
            )
        }
    }
}
