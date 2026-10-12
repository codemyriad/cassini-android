// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6. The file
// is named after its composable, not after the PrivacyLineStyle enum it also holds.
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/** The two privacy lines: [Library] under the search field and [SavedVoices] under the Saved voices top bar. */
enum class PrivacyLineStyle { Library, SavedVoices }

/**
 * A teal shield and a privacy statement. [PrivacyLineStyle.Library]: shield 14/2, gap 6, `caption` in text2,
 * padding start/end 4 and top 10 (inside the 16 dp search column). [PrivacyLineStyle.SavedVoices]: shield 18/2
 * top-aligned 2 dp down, gap 10, `about` in text3, margins 8 top, 20 horizontal, 16 bottom.
 */
@Composable
fun PrivacyLine(text: String, modifier: Modifier = Modifier, style: PrivacyLineStyle = PrivacyLineStyle.Library) {
    val c = CassiniTheme.colors
    if (style == PrivacyLineStyle.Library) {
        Row(
            modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CassiniIcon(Glyph.Shield, 14.dp, 2f, c.teal, null)
            Spacer(Modifier.width(6.dp))
            Text(text = text, style = CassiniTheme.type.caption, color = c.text2)
        }
    } else {
        Row(
            modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CassiniIcon(Glyph.Shield, 18.dp, 2f, c.teal, null, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = text, style = CassiniTheme.type.about, color = c.text3)
        }
    }
}

@Preview
@Composable
internal fun PrivacyLinePreview() {
    CassiniTheme {
        Column(Modifier.background(CassiniTheme.colors.bg)) {
            PrivacyLine("Transcribed on this phone. Audio never leaves it.")
            PrivacyLine("Voiceprints stay on this phone.", style = PrivacyLineStyle.SavedVoices)
        }
    }
}
