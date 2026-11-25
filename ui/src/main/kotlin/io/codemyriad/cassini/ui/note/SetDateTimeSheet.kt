// Composables are nouns in PascalCase; the numbers follow the app's other sheets (Name a speaker, Merge).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.CassiniSheet
import io.codemyriad.cassini.ui.components.PrimaryButton
import io.codemyriad.cassini.ui.components.SecondaryButton
import io.codemyriad.cassini.ui.components.SecondaryButtonSize
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.LocalFormat
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * "Set date and time" (D112, L-87): the sheet a meeting's actions open under Rename. [date] and [time] are the
 * prefill the ViewModel chose: the meeting's start when it has one; otherwise the imported file's last-saved time
 * minus its length, with [fromFileSaved] true ("Filled in from when the file was saved. Change it if it's wrong.");
 * otherwise today at the current time.
 */
@Immutable
data class SetDateTimeUi(
    val noteId: String,
    val date: LocalDate,
    val time: LocalTime,
    val fromFileSaved: Boolean = false,
)

/** Which Material 3 picker is open above the sheet. */
enum class StartPicker { DATE, TIME }

/**
 * The sheet in its [CassiniSheet] window (beside the Library on a wide window it rises from the bottom of the right
 * pane, at its width). Date opens the Material 3 date picker and Started at the time picker; Save sends the chosen
 * day and minute to [onSave], and Cancel, a swipe or a scrim tap end in [onDismiss]. The choices survive rotation.
 */
@Composable
fun SetDateTimeSheet(ui: SetDateTimeUi, onSave: (LocalDate, LocalTime) -> Unit, onDismiss: () -> Unit) {
    var date by rememberSaveable(ui) { mutableStateOf(ui.date) }
    var time by rememberSaveable(ui) { mutableStateOf(ui.time) }
    var picker by rememberSaveable(ui) { mutableStateOf<StartPicker?>(null) }
    CassiniSheet(onDismissRequest = onDismiss) { hider ->
        SetDateTimeSheetContent(
            ui = ui,
            date = date,
            time = time,
            onPick = { picker = it },
            onCancel = { hider.hide() },
            onSave = { onSave(date, time) },
        )
    }
    when (picker) {
        StartPicker.DATE -> StartDatePicker(date, onPick = {
            date = it
            picker = null
        }, onDismiss = { picker = null })
        StartPicker.TIME -> StartTimePicker(time, onPick = {
            time = it
            picker = null
        }, onDismiss = { picker = null })
        null -> Unit
    }
}

/**
 * The sheet's content, also drawn inline in screenshots: the title "When was this recorded?" (`sheetTitle`), the
 * saved-time line while the values are still that suggestion, the Date and Started at rows (56 dp, value at the end),
 * then Cancel and Save, which go below each other at large font scales.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // The prefill, the two values and the three callbacks.
fun SetDateTimeSheetContent(
    ui: SetDateTimeUi,
    date: LocalDate,
    time: LocalTime,
    onPick: (StartPicker) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 25.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_meeting_set_date_time_title),
            modifier = Modifier.semantics { heading() },
            style = CassiniTheme.type.sheetTitle,
            color = c.ink,
        )
        // The line describes the suggestion; once the person changes it, it no longer applies.
        if (ui.fromFileSaved && date == ui.date && time == ui.time) {
            Text(
                text = stringResource(R.string.ui_meeting_set_date_time_saved_hint),
                modifier = Modifier.padding(top = 4.dp),
                style = CassiniTheme.type.body14,
                color = c.text2,
            )
        }
        Column(Modifier.padding(top = 12.dp)) {
            StartRow(
                label = stringResource(R.string.ui_meeting_set_date_time_date),
                value = Dates.shortDate(date, withYear = true),
                onClick = { onPick(StartPicker.DATE) },
                modifier = Modifier.testTag("start-date"),
            )
            StartRow(
                label = stringResource(R.string.ui_meeting_set_date_time_time),
                value = Dates.time(time),
                onClick = { onPick(StartPicker.TIME) },
                modifier = Modifier.testTag("start-time"),
            )
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                text = stringResource(R.string.ui_action_cancel),
                onClick = onCancel,
                size = SecondaryButtonSize.Regular,
            )
            PrimaryButton(
                text = stringResource(R.string.ui_action_save),
                onClick = onSave,
                modifier = Modifier.testTag("start-save"),
            )
        }
    }
}

/** One row: the label in ink, the value in accent ink and a chevron at the end; the whole 56 dp row opens its picker. */
@Composable
private fun StartRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = CassiniTheme.type.body16, color = c.ink)
        Text(text = value, style = CassiniTheme.type.body16, color = c.accentInk)
        CassiniIcon(Glyph.ChevronRight, size = 18.dp, stroke = 1.8f, tint = c.text2, contentDescription = null)
    }
}

/**
 * The Material 3 date picker (`DatePickerDialog`), opened on [date]. Its state counts days in UTC milliseconds, so
 * the chosen day is read back in UTC, whatever the phone's zone. OK is the platform's own localized word.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StartDatePicker(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        ?: onDismiss()
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** The Material 3 time picker (`TimePickerDialog`), opened on [time], in the phone's 12- or 24-hour form. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StartTimePicker(time: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = time.hour,
        initialMinute = time.minute,
        is24Hour = LocalFormat.current.is24Hour,
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        title = { Text(stringResource(R.string.ui_meeting_set_date_time_time)) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_action_cancel)) } },
    ) {
        TimePicker(state = state)
    }
}
