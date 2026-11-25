@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.GroupCard
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    GroupCard(modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp), content = content)
}

/** The answer stays beside its topic; both columns wrap for large fonts and long translations. */
@Composable
internal fun MapRow(
    title: String,
    answer: String,
    glyph: Glyph,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val c = CassiniTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = if (subtitle != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(c.accentTint, CircleShape), contentAlignment = Alignment.Center) {
            CassiniIcon(glyph, 18.dp, 1.9f, c.accentInk, null)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = CassiniTheme.type.body16, color = c.ink)
            if (subtitle != null) Text(subtitle, style = CassiniTheme.type.caption, color = c.text2)
        }
        if (answer.isNotEmpty()) {
            Text(
                answer,
                Modifier.weight(0.8f).padding(end = 8.dp),
                style = CassiniTheme.type.body16,
                color = c.text2,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        CassiniIcon(Glyph.ChevronRight, 16.dp, 2f, c.text2, null)
    }
}

/**
 * A row that does something here instead of opening a page ("Export all meetings"): the icon and the title of a
 * [MapRow], no answer and no chevron. Disabled (nothing to export, or a transfer is running) it greys out.
 */
@Composable
internal fun ActionRow(title: String, glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(c.accentTint, CircleShape), contentAlignment = Alignment.Center) {
            CassiniIcon(glyph, 18.dp, 1.9f, c.accentInk, null)
        }
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 12.dp),
            style = CassiniTheme.type.body16,
            color = if (enabled) c.ink else c.text3,
        )
    }
}

@Composable
internal fun AboutRow(version: String, onClick: () -> Unit) {
    SettingsGroup(Modifier.padding(top = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ui_saturn_nav), null, Modifier.size(44.dp, 30.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    stringResource(R.string.ui_about_name_version, version),
                    style = CassiniTheme.type.button,
                    color = CassiniTheme.colors.ink,
                )
                Text(
                    stringResource(R.string.ui_settings_about_subtitle),
                    style = CassiniTheme.type.caption,
                    color = CassiniTheme.colors.text2,
                )
            }
            CassiniIcon(Glyph.ChevronRight, 16.dp, 2f, CassiniTheme.colors.text2, null)
        }
    }
}

/** D103 phone header: 64 dp with a regular 22 sp title, kept above the scrollable choices. */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit, backLabel: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton44(Glyph.Back, backLabel, onBack, modifier = Modifier.size(48.dp))
        Text(
            title,
            Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
            style = CassiniTheme.type.screenTitle.copy(fontWeight = FontWeight.Normal),
            color = CassiniTheme.colors.ink,
        )
    }
}
