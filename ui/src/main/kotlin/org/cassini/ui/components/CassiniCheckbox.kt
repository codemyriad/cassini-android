// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/**
 * The checkbox square (board 04): 22 dp, radius 4; checked is an accent fill with a Check (16/2.2) in surface,
 * unchecked a 2 dp text2 border. It has no semantics of its own: [CassiniCheckboxRow] makes the whole row the toggle.
 * The Check is white in light (surface = onAccentFill, as the board). In dark, accent is the light #8FA6FF, where a
 * white Check has a contrast of 2.3:1; the dark surface gives 7.4:1.
 */
@Composable
fun CassiniCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.small
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(shape)
            .then(if (checked) Modifier.background(c.accent) else Modifier.border(2.dp, c.text2, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) CassiniIcon(Glyph.Check, 16.dp, 2.2f, c.surface, null)
    }
}

/**
 * A checkbox row (board 04, "Remember this voice on this phone"): the [CassiniCheckbox] 2 dp from the top, 12 dp gap,
 * [title] in `checkTitle` and [subtitle] in 13 sp text2 on the same 1.4 line height. The whole row is one
 * `toggleable` with the Checkbox role, at least 48 dp tall; disabled, it draws at 38 % and the subtitle says why.
 */
@Composable
@Suppress("LongParameterList") // The checkbox state, its callback, two texts and the enabled state.
fun CassiniCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    Row(
        modifier = modifier
            .fillMaxWidth()
            .touchTarget()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CassiniCheckbox(checked, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = t.checkTitle, color = c.ink)
            if (subtitle != null) {
                Text(text = subtitle, style = t.caption.copy(lineHeight = t.checkTitle.lineHeight), color = c.text2)
            }
        }
    }
}

@Preview
@Composable
internal fun CassiniCheckboxPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.surface)
                .padding(20.dp),
        ) {
            CassiniCheckboxRow(
                checked = true,
                onCheckedChange = {},
                title = "Remember this voice on this phone",
                subtitle = "Future notes will name Anna automatically. The voiceprint never leaves the phone.",
            )
            CassiniCheckboxRow(checked = false, onCheckedChange = {}, title = "Remember this voice on this phone")
        }
    }
}
