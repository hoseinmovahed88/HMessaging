@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hmessaging.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hmessaging.R
import com.hmessaging.util.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** A one-tap time to send at, and what to call it. */
private data class Preset(val labelRes: Int, val at: () -> LocalDateTime)

/**
 * Choosing when to send.
 *
 * Almost every scheduled message is "in an hour", "this evening" or "tomorrow morning", and making
 * all of them cost a calendar, a clock and two confirmations was the wrong trade. The presets
 * answer it in one tap; the calendar is still there for the message that genuinely needs a date,
 * behind one more tap rather than in front of every one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initialEpochMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initial = TimeFormat.toLocal(initialEpochMillis)
    var customising by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    if (!customising) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.schedule_send)) },
            confirmButton = {
                TextButton(onClick = { customising = true }) {
                    Text(stringResource(R.string.schedule_pick_exact))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            },
            text = {
                Column {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        presets().forEach { preset ->
                            val at = preset.at()
                            SuggestionChip(
                                onClick = { onConfirm(TimeFormat.toEpochMillis(at)) },
                                label = { Text(stringResource(preset.labelRes)) },
                            )
                        }
                    }
                    // Today, in whichever calendar is in use — so a preset is picked against a
                    // date the reader recognises rather than against nothing.
                    Text(
                        text = TimeFormat.full(System.currentTimeMillis()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = stringResource(R.string.schedule_preset_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
        )
        return
    }

    if (pickedDate == null) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = dateState.selectedDateMillis
                        pickedDate = if (millis != null) {
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        } else {
                            initial.toLocalDate()
                        }
                    },
                ) { Text(stringResource(R.string.next)) }
            },
            dismissButton = {
                TextButton(onClick = { customising = false }) { Text(stringResource(R.string.back)) }
            },
            text = {
                Column {
                    // The Material date picker works in UTC millis while the rest of the app works
                    // in local time, so the chosen day comes back through LocalDate rather than
                    // arithmetic.
                    DatePicker(state = dateState, modifier = Modifier.weight(1f, fill = false))
                    // It is also Gregorian and cannot be told otherwise, so the day it is pointing
                    // at is written underneath in the calendar the reader counts in.
                    val chosen = dateState.selectedDateMillis
                    if (chosen != null && TimeFormat.persianCalendar) {
                        Text(
                            text = TimeFormat.persianDate(
                                Instant.ofEpochMilli(chosen).atZone(ZoneOffset.UTC).toLocalDate(),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
            },
        )
    } else {
        val timeState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = pickedDate ?: initial.toLocalDate()
                        val time = LocalTime.of(timeState.hour, timeState.minute)
                        onConfirm(TimeFormat.toEpochMillis(date.atTime(time)))
                    },
                ) { Text(stringResource(R.string.schedule_send)) }
            },
            dismissButton = {
                TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.back)) }
            },
            text = { TimePicker(state = timeState) },
        )
    }
}

/**
 * Built fresh each time the dialog opens, so "this evening" means tonight rather than an evening
 * that has already passed. Times that have gone by today move to tomorrow for the same reason.
 */
private fun presets(): List<Preset> {
    val now = LocalDateTime.now()
    fun todayOrTomorrowAt(hour: Int): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, 0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }
    return listOf(
        Preset(R.string.schedule_in_1_hour) { now.plusHours(1) },
        Preset(R.string.schedule_in_3_hours) { now.plusHours(3) },
        Preset(R.string.schedule_this_evening) { todayOrTomorrowAt(EVENING_HOUR) },
        // Explicitly tomorrow, not "today if it is still before nine": at 2am the label would be
        // pointing at a time seven hours away and calling it tomorrow.
        Preset(R.string.schedule_tomorrow_morning) {
            now.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0)
        },
        Preset(R.string.schedule_next_week) { now.plusWeeks(1) },
    )
}

/** Time-of-day only, used for the active-hours window of an auto-reply rule. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDayPickerDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / MINUTES_PER_HOUR,
        initialMinute = initialMinuteOfDay % MINUTES_PER_HOUR,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * MINUTES_PER_HOUR + state.minute) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}

private const val EVENING_HOUR = 20
private const val MORNING_HOUR = 9
private const val MINUTES_PER_HOUR = 60
