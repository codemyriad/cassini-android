// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.cassini.ui.R
import org.cassini.ui.theme.CassiniTheme

/** The dialog's distance from the screen edges. The dialog is at most 560 dp wide (M3). */
private val DialogMargin = 24.dp
private val DialogMaxWidth = 560.dp

/**
 * The app's dialog: an M3 `BasicAlertDialog` showing [CassiniDialogContent]. The window fills the screen
 * (`usePlatformDefaultWidth = false`, `decorFitsSystemWindows = false`) and does not dim: the content draws the scrim
 * token (#1F2A39 at 50 %) itself, as the sheets do, and a tap on it calls [onDismissRequest]. The card is centred in
 * the safe area above the keyboard, with a 24 dp margin on each side, so screenshots can place the same content inline
 * (`DialogBoardFrame` in test sources) exactly where the window puts it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // ui.md section 6: title, body or content slot, actions and the dismiss request.
fun CassiniDialog(
    onDismissRequest: () -> Unit,
    title: String,
    actions: @Composable FlowRowScope.() -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(CassiniTheme.colors.scrim)
                    .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                    .clearAndSetSemantics {},
            )
            CassiniDialogContent(
                title = title,
                actions = actions,
                modifier = modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    // Taps on the card do not reach the scrim.
                    .pointerInput(Unit) { detectTapGestures {} },
                body = body,
                content = content,
            )
        }
    }
}

/**
 * The dialog card: surface, radius 28, padding 24, gap 16; [title] in `sheetTitle` (a heading); [body] in `body14`
 * text3 and/or a custom [content] slot (a text field, key/value rows); [actions] end-aligned with an 8 dp gap
 * (they wrap when they do not fit). It keeps a 24 dp margin from the screen edges.
 */
@Composable
fun CassiniDialogContent(
    title: String,
    actions: @Composable FlowRowScope.() -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = CassiniTheme.colors
    Box(modifier.padding(horizontal = DialogMargin), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = DialogMaxWidth)
                .fillMaxWidth()
                .clip(CassiniTheme.shapes.dialog)
                .background(c.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = CassiniTheme.type.sheetTitle,
                color = c.ink,
            )
            if (body != null) Text(text = body, style = CassiniTheme.type.body14, color = c.text3)
            content?.invoke(this)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                content = actions,
            )
        }
    }
}

/**
 * The usual pair of dialog buttons: [cancelLabel] (`SecondaryButton` regular) then the confirm button, a
 * `PrimaryButton` or, when [destructive], the red `DestructiveButton`.
 */
@Composable
@Suppress("LongParameterList") // Both buttons with their labels, the style and the confirm state.
fun DialogActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelLabel: String = stringResource(R.string.ui_action_cancel),
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
) {
    SecondaryButton(cancelLabel, onClick = onCancel, size = SecondaryButtonSize.Regular)
    if (destructive) {
        DestructiveButton(confirmLabel, onClick = onConfirm, enabled = confirmEnabled, busy = busy)
    } else {
        PrimaryButton(confirmLabel, onClick = onConfirm, enabled = confirmEnabled, busy = busy)
    }
}

@Preview
@Composable
internal fun CassiniDialogPreview() {
    CassiniTheme {
        Box(Modifier.background(CassiniTheme.colors.scrim)) {
            CassiniDialogContent(
                title = "Delete for good?",
                body = "Its audio, transcript and voiceprints are deleted. This can’t be undone.",
                actions = { DialogActions("Delete", onConfirm = {}, onCancel = {}, destructive = true) },
            )
        }
    }
}
