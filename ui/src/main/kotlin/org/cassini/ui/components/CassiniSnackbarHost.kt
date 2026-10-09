// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cassini.ui.theme.CassiniTheme

/**
 * Where the snackbar sits: the bottom of the screen with 16 dp side margins and 24 dp below. The screen places the
 * host above the system insets and the Library bottom bar. When [message] is not null the snackbar shows, and after
 * [durationMs] it calls [onTimeout]. The caller computes the duration with
 * `AccessibilityManager.getRecommendedTimeoutMillis(10_000, FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS)`, so users
 * who need more time get it. A new [key] (by default the message) restarts the timer.
 */
@Composable
@Suppress("LongParameterList") // ui.md section 6: the message, its action, the timeout and the restart key.
fun CassiniSnackbarHost(
    message: String?,
    durationMs: Long,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    key: Any? = message,
) {
    val timeout by rememberUpdatedState(onTimeout)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (message != null) {
            LaunchedEffect(key, durationMs) {
                delay(durationMs)
                timeout()
            }
            CassiniSnackbar(message = message, actionLabel = actionLabel, onAction = onAction)
        }
    }
}

/**
 * The snackbar card (board 09): snackbarBg, radius 14, padding 6/6/6/16 (4 above and below the 48 dp action), the
 * [message] in `body14Plain` snackbarText and the [actionLabel] in 14/600 snackbarAction (visual 44 / layout 48,
 * padding 14). A polite live region; the action is also a custom accessibility action.
 */
@Composable
fun CassiniSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = CassiniTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CassiniTheme.shapes.row)
            .background(c.snackbarBg)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                if (actionLabel != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(actionLabel) {
                            onAction()
                            true
                        },
                    )
                }
            }
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = if (actionLabel != null) 6.dp else 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            // 2 dp more keeps a message of several lines 6 dp from the edges, as the board's padding.
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 2.dp),
            style = CassiniTheme.type.body14Plain,
            color = c.snackbarText,
        )
        if (actionLabel != null) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .touchTarget()
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(CassiniTheme.shapes.pill)
                        .indication(interaction, ripple())
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = actionLabel, style = CassiniTheme.type.label14, color = c.snackbarAction)
                }
            }
        }
    }
}

@Preview
@Composable
internal fun CassiniSnackbarPreview() {
    CassiniTheme {
        Box(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            CassiniSnackbar("“Call with supplier” moved to trash", actionLabel = "Undo")
        }
    }
}
