// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme

/**
 * The app's text field (board 04): [label] in `fieldLabel` text3 6 dp above a 52 dp box (radius 14, surface) with the
 * text in `field` ink, 16 dp from the box's edge (the board's 14 dp padding inside its 2 dp border). The border is
 * 2 dp accent when focused, 2 dp redInk with an [error] (shown below in `caption` redInk), else 1 dp line2.
 *
 * [showFocus] = true draws the focused border without focusing the field, so no caret blinks (screenshot fixtures);
 * null (the app) follows the real focus. The label and the error belong to the field's accessibility node.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // ui.md section 6 plus the usual keyboard options of a text field.
fun CassiniTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    showFocus: Boolean? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val shape = CassiniTheme.shapes.row
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val looksFocused = showFocus ?: focused
    val (width, color) = when {
        error != null -> 2.dp to c.redInk
        looksFocused -> 2.dp to c.accent
        else -> 1.dp to c.line2
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (error != null) error(error) },
        enabled = enabled,
        textStyle = t.field.copy(color = c.ink),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Column {
                Text(text = label, style = t.fieldLabel, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(shape)
                        .background(c.surface)
                        .border(width, color, shape)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    inner()
                }
                if (error != null) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(top = 6.dp),
                        style = t.caption,
                        color = c.redInk,
                    )
                }
            }
        },
    )
}

@Preview
@Composable
internal fun CassiniTextFieldPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CassiniTextField("Anna", onValueChange = {}, label = "Name", showFocus = true)
            CassiniTextField("", onValueChange = {}, label = "Name", error = "Enter a name.")
        }
    }
}
