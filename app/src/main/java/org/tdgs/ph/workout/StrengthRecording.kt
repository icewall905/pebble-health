@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.tdgs.ph.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val exercises = listOf(
    "1-hand chest press", "Bench press", "Biceps curl", "Bulgarian split squat",
    "Cable fly", "Calf raise", "Chest press", "Deadlift", "Dumbbell row", "Goblet squat",
    "Hip thrust", "Lat pulldown", "Lateral raise", "Leg curl", "Leg extension", "Leg press",
    "Overhead press", "Plank", "Pull-up", "Push-up", "Romanian deadlift", "Seated row",
    "Shoulder press", "Squat", "Triceps pulldown",
)
private val restPresets = listOf(30, 60, 90)
private val weightPresets = listOf("10", "15", "20", "25", "30", "40")

@Composable
internal fun StrengthRecording(state: LiveWorkout) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("strength_recording", 0) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var choose by rememberSaveable { mutableStateOf(false) }
    var favoriteLimit by rememberSaveable { mutableStateOf(false) }

    fun select(name: String) {
        WorkoutRecorder.command(context, WorkoutRecorder.SELECT_EXERCISE) { putExtra("exercise", name) }
        choose = false
        favoriteLimit = false
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Set log", style = MaterialTheme.typography.titleLarge)
                Text("${state.sets} logged", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (favorites.isNotEmpty()) {
                Text("Favorites", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    favorites.sorted().forEach { name ->
                        FilterChip(selected = state.exercise == name, onClick = { select(name) }, label = { Text(name) })
                    }
                }
            }
            OutlinedButton(onClick = { choose = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.exercise.isBlank()) "Find exercise" else "Change exercise")
            }
            if (state.exercise.isNotBlank()) {
                Text(state.exercise, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    val name = state.exercise
                    if (name in favorites) favorites = favorites - name
                    else if (favorites.size < 5) favorites = favorites + name
                    else { favoriteLimit = true; return@TextButton }
                    favoriteLimit = false
                    prefs.edit().putStringSet("favorites", favorites).apply()
                }) { Text(if (state.exercise in favorites) "Remove from favorites" else "Add to favorites") }
                if (favoriteLimit) Text("You have five favorites. Select one to remove it first.",
                    style = MaterialTheme.typography.bodySmall)
                key(state.exercise) {
                    val previous = state.lastSet?.takeIf { it.exercise == state.exercise }
                    var count by rememberSaveable { mutableIntStateOf(1) }
                    var reps by rememberSaveable { mutableStateOf((previous?.reps ?: 10).toString()) }
                    var weight by rememberSaveable { mutableStateOf(previous?.kg?.toString().orEmpty()) }
                    Text("Sets to log", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { n ->
                            FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(reps, { reps = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
                            label = { Text("Reps per set") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(weight, { weight = it.take(8) }, Modifier.weight(1f),
                            label = { Text("Weight (kg)") }, placeholder = { Text("Optional") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    val kilos = weight.replace(',', '.').toDoubleOrNull()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        weightPresets.forEach { preset ->
                            FilterChip(selected = kilos == preset.toDouble(), onClick = { weight = preset },
                                label = { Text("$preset kg") })
                        }
                    }
                    val repCount = reps.toIntOrNull()
                    Button(modifier = Modifier.fillMaxWidth(), enabled = !state.paused && repCount != null && repCount in 1..999 &&
                        (weight.isBlank() || kilos != null && kilos in 0.0..10000.0), onClick = {
                        WorkoutRecorder.command(context, WorkoutRecorder.SET) {
                            putExtra("exercise", state.exercise)
                            putExtra("reps", repCount ?: 0)
                            putExtra("kg", kilos ?: Double.NaN)
                            putExtra("count", count)
                        }
                    }) { Text(if (count == 1) "Log set" else "Log $count sets") }
                    state.lastSet?.takeIf { it.exercise == state.exercise }?.let {
                        val load = it.kg?.let { kg -> " at ${org.tdgs.ph.ui.Fmt.dec(kg, 2)} kg" }.orEmpty()
                        Text("Last logged: ${it.reps} reps$load", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (choose) ExercisePicker(favorites, dismiss = { choose = false }, select = ::select)
}

@Composable
internal fun StrengthRest(state: LiveWorkout) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("strength_recording", 0) }
    var preset by remember { mutableIntStateOf(prefs.getInt("rest_seconds", 60)) }
    fun start(seconds: Int) = WorkoutRecorder.command(context, WorkoutRecorder.REST) { putExtra("seconds", seconds) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set rest", style = MaterialTheme.typography.titleMedium)
            if (state.restSeconds > 0 || state.restComplete) {
                Text(if (state.restComplete) "Rest complete" else "${state.restSeconds / 60}:${(state.restSeconds % 60).toString().padStart(2, '0')}",
                    Modifier.semantics { if (state.restComplete) liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = { start(0) }) { Text(if (state.restComplete) "Dismiss" else "Cancel rest") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    restPresets.forEach { seconds ->
                        FilterChip(enabled = !state.paused, selected = preset == seconds, onClick = {
                            preset = seconds
                            prefs.edit().putInt("rest_seconds", seconds).apply()
                        }, label = { Text("$seconds s") })
                    }
                }
                FilledTonalButton(enabled = !state.paused, modifier = Modifier.fillMaxWidth(), onClick = { start(preset) }) {
                    Text("Start $preset s rest")
                }
            }
        }
    }
}

@Composable
private fun ExercisePicker(favorites: Set<String>, dismiss: () -> Unit, select: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    val query = filter.trim()
    val matches = (favorites.sorted() + exercises).distinctBy { it.lowercase() }.filter { name ->
        query.split(Regex("\\s+")).all { name.contains(it, ignoreCase = true) }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Find exercise") }, text = {
        Column {
            OutlinedTextField(filter, { filter = it.take(100) }, label = { Text("Search exercises") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 250.dp)) {
                items(matches, key = { it }) { name ->
                    TextButton(onClick = { select(name) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                        Text(name, Modifier.fillMaxWidth())
                    }
                }
                if (query.isNotEmpty() && matches.none { it.equals(query, ignoreCase = true) }) item {
                    TextButton(onClick = { select(query) }) { Text("Use “$query”") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
