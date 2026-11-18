@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import io.codemyriad.cassini.ui.components.SecondaryButton
import io.codemyriad.cassini.ui.components.SecondaryButtonSize
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** D106 floating notice after a saved meeting (L-59). Keep and close keep backup on. */
@Composable
internal fun BackupCard(onEvent: (LibraryEvent) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Surface(
        modifier.fillMaxWidth().testTag("backup-notice"),
        shape = RoundedCornerShape(20.dp),
        color = c.raised,
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CassiniIcon(Glyph.CloudBackup, 22.dp, 1.7f, c.accentInk, null)
                Text(
                    stringResource(R.string.ui_backup_notice_title),
                    Modifier.weight(1f).semantics { heading() },
                    style = CassiniTheme.type.button,
                    color = c.ink,
                )
                IconButton44(
                    Glyph.Close,
                    stringResource(R.string.ui_backup_card_close),
                    { onEvent(LibraryEvent.DismissBackupCard) },
                    Modifier.offset(x = 8.dp),
                    iconSize = 15.dp,
                    stroke = 2.2f,
                )
            }
            Text(
                stringResource(R.string.ui_backup_notice_body),
                Modifier.padding(top = 8.dp),
                style = CassiniTheme.type.body14,
                color = c.text2,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    stringResource(R.string.ui_backup_notice_keep),
                    { onEvent(LibraryEvent.DismissBackupCard) },
                    Modifier.weight(1f),
                    size = SecondaryButtonSize.Medium,
                    fillWidth = true,
                )
                SecondaryButton(
                    stringResource(R.string.ui_backup_notice_phone_only),
                    { onEvent(LibraryEvent.KeepOnPhoneOnly) },
                    Modifier.weight(1f),
                    size = SecondaryButtonSize.Medium,
                    fillWidth = true,
                )
            }
        }
    }
}
