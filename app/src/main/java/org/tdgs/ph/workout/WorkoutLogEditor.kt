@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.tdgs.ph.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@Composable
internal fun WorkoutLogEditor(existing: Workout?, saving: Boolean, lockMetrics: Boolean = false, close: () -> Unit, save: (Workout) -> Unit) {
    val manual = !lockMetrics && (existing?.manual ?: true)
    val zone = ZoneId.systemDefault()
    val initial = existing?.start ?: (System.currentTimeMillis() - 30 * 60_000)
    var start by rememberSaveable { mutableLongStateOf(initial / 60_000 * 60_000) }
    var sport by rememberSaveable { mutableStateOf(existing?.sport ?: Sport.workout) }
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes.orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf((existing?.active?.div(60_000) ?: 30).toString()) }
    var kilometers by rememberSaveable {
        mutableStateOf(existing?.manualDistance?.div(1000)?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty())
    }
    var types by remember { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf("") }
    val dateTime = Instant.ofEpochMilli(start).atZone(zone)
    val elapsed = minutes.toLongOrNull()?.takeIf { it in 1..2880 }?.times(60_000)
    val meters = kilometers.trim().replace(',', '.').toDoubleOrNull()?.times(1000)
    val validDistance = kilometers.isBlank() ||
        (elapsed != null && meters != null && meters in 0.0..(40.0 * elapsed / 1000))
    val validTime = elapsed != null && start >= 946684800000L && start + elapsed <= System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text(if (existing == null) "Add past activity" else "Edit activity") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lockMetrics) {
                    Text("${sport.label} · Sent to PebbleFit")
                } else Box {
                    OutlinedButton(onClick = { types = true }, enabled = !saving) { Text(sport.label) }
                    DropdownMenu(expanded = types, onDismissRequest = { types = false }) {
                        Sport.entries.forEach { type ->
                            DropdownMenuItem(text = { Text(type.label) }, onClick = { sport = type; types = false })
                        }
                    }
                }
                OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                    enabled = !saving, label = { Text("Name (optional)") })
                if (manual) {
                    OutlinedButton(onClick = { picker = "date" }, enabled = !saving) {
                        Text(dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                    }
                    OutlinedButton(onClick = { picker = "time" }, enabled = !saving) {
                        Text(dateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
                    }
                    OutlinedTextField(minutes, { minutes = it.take(4) }, Modifier.fillMaxWidth(), singleLine = true,
                        enabled = !saving, label = { Text("Duration in minutes") },
                        isError = !validTime,
                        supportingText = {
                            if (!validTime) Text("Enter 1–2880 minutes and a time that has already ended.")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(kilometers, { kilometers = it.take(12) }, Modifier.fillMaxWidth(), singleLine = true,
                        enabled = !saving, label = { Text("Distance in km (optional)") },
                        isError = !validDistance,
                        supportingText = { if (!validDistance) Text("Check the distance for this duration.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                OutlinedTextField(notes, { notes = it.take(2000) }, Modifier.fillMaxWidth(),
                    enabled = !saving, label = { Text("Notes (optional)") }, maxLines = 4)
            }
        },
        confirmButton = {
            Button(enabled = !saving && (!manual || (validTime && validDistance)), onClick = {
                val base = existing ?: Workout(UUID.randomUUID().toString(), sport, start, start, 0, manual = true)
                val updated = base.copy(sport = sport, title = title.trim().ifBlank { null },
                    notes = notes.trim().ifBlank { null })
                save(if (manual && elapsed != null) updated.copy(
                    start = start, end = start + elapsed, active = elapsed, manualDistance = meters,
                ) else updated)
            }) { Text(if (saving) "Saving…" else "Save activity") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = close) { Text("Cancel") } },
    )
    if (picker == "date") {
        val state = rememberDatePickerState(initialSelectedDateMillis =
            dateTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picker = "" }, confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let {
                    val date = Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()
                    start = date.atTime(dateTime.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                }
                picker = ""
            }) { Text("Set date") }
        }, dismissButton = { TextButton(onClick = { picker = "" }) { Text("Cancel") } }) {
            DatePicker(state)
        }
    }
    if (picker == "time") {
        val state = rememberTimePickerState(dateTime.hour, dateTime.minute,
            android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(onDismissRequest = { picker = "" }, title = { Text("Start time") },
            text = { TimeInput(state) },
            confirmButton = {
                TextButton(onClick = {
                    start = dateTime.toLocalDate().atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli()
                    picker = ""
                }) { Text("Set time") }
            }, dismissButton = { TextButton(onClick = { picker = "" }) { Text("Cancel") } })
    }
}
