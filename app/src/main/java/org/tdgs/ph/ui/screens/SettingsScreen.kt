package org.tdgs.ph.ui.screens

import android.content.Intent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.tdgs.ph.checkin.TrainerCheckInSettings
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.HubConfig
import org.tdgs.ph.planner.WorkoutPlannerActivity
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.Segmented
import org.tdgs.ph.ui.theme.LocalMetrics
import org.tdgs.ph.widget.RefreshWorker
import org.tdgs.ph.workout.WorkoutEntry
import org.tdgs.ph.workout.WorkoutUploadDialog
import org.tdgs.ph.workout.InstallWorkoutWatch
import androidx.compose.material3.Switch

@Composable
fun SettingsScreen(repo: Repository) {
    val prefs by repo.settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
    val config by repo.settings.config.collectAsState(initial = null)
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val m = LocalMetrics.current
    var workoutSettings by remember { mutableStateOf(false) }
    if (workoutSettings) WorkoutUploadDialog(repo) { workoutSettings = false }
    Page(repo, "Settings") {
        item {
            PhCard(onClick = {
                context.startActivity(Intent(context, WorkoutPlannerActivity::class.java).putExtra("model_settings", true))
            }) {
                Text("Trainer settings", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            val workoutPreferences = remember { context.getSharedPreferences("workout_setup", 0) }
            var watch by remember { mutableStateOf(workoutPreferences.getBoolean("mirror_watch", false)) }
            var details by remember { mutableStateOf(workoutPreferences.getBoolean("sync_activity_details", false)) }
            PhCard {
                Text("Workouts", style = MaterialTheme.typography.titleMedium)
                InstallWorkoutWatch()
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Launch Pebble Health Workout on start", Modifier.weight(1f))
                    Switch(watch, { watch = it; workoutPreferences.edit().putBoolean("mirror_watch", it).apply() })
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Sync detailed workouts")
                    Switch(details, { details = it; workoutPreferences.edit().putBoolean("sync_activity_details", it).apply() })
                }
                Text("Detailed sync sends routes, charts, notes and sets to your dashboard. To add details to a previously synced workout, open it and tap Sync now.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item { TrainerCheckInSettings(repo) }
        item {
            PhCard {
                Text("Goals", style = MaterialTheme.typography.titleSmall)
                Text("Daily steps: ${Fmt.int(prefs.stepGoal)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                Slider(prefs.stepGoal.toFloat(), { v -> scope.launch { repo.settings.setStepGoal((v / 500).toInt() * 500) } },
                    valueRange = 2000f..20000f)
                Text("Sleep: ${prefs.sleepGoalH} h", style = MaterialTheme.typography.bodyMedium)
                Slider(prefs.sleepGoalH.toFloat(), { v -> scope.launch { repo.settings.setSleepGoal(v.toInt()) } },
                    valueRange = 5f..10f, steps = 4)
            }
        }
        item {
            PhCard {
                Text("Appearance", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 10.dp))
                val modes = listOf("system", "dark", "light")
                Segmented(listOf("System", "Dark", "Light"), modes.indexOf(prefs.theme).coerceAtLeast(0),
                    { scope.launch { repo.settings.setTheme(modes[it]) } })
            }
        }
        item {
            PhCard {
                Text("Hub", style = MaterialTheme.typography.titleSmall)
                Text(config?.baseUrl ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Text("Last refresh ${Fmt.ago(state.fetchedAt?.let { System.currentTimeMillis() / 1000 - it })}" +
                    (state.dashboard?.let { " · data ${Fmt.ago(it.lastReceived?.let { r -> it.now - r })}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = m.muted)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { repo.refresh(); RefreshWorker.updateWidgets(context) } }) { Text("Refresh now") }
                    OutlinedButton(onClick = { scope.launch { repo.settings.clear(); repo.forget() } }) { Text("Disconnect") }
                }
                OutlinedButton(onClick = { workoutSettings = true }) { Text("Dashboard sign-in") }
            }
        }
        item {
            PhCard {
                Text("Get better data from the watch", style = MaterialTheme.typography.titleSmall)
                Text(
                    "• Watch Settings → Health → HR Monitoring: 5 minutes (the most frequent background interval).\n" +
                        "• Turn on \"HR During Activity\" for continuous heart rate on walks and runs.\n" +
                        "• Keep GravelEx's health export enabled and exempt from battery optimisation.",
                    style = MaterialTheme.typography.bodySmall, color = m.muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            Text("Pebble Health ${appVersion(context)} · connects to your self-hosted hub. Not a medical device.",
                style = MaterialTheme.typography.bodySmall, color = m.muted, modifier = Modifier.padding(4.dp))
        }
    }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

@Composable
fun SetupScreen(repo: Repository) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val m = LocalMetrics.current
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Pebble Health", style = MaterialTheme.typography.displaySmall, color = m.steps)
        Text("Connect to your self-hosted health hub. Create a token on the hub under More → API tokens with the " +
            "\"summary:read\" and \"history:read\" scopes.", style = MaterialTheme.typography.bodyMedium, color = m.muted)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Hub address") },
            placeholder = { Text("https://health.example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth(), label = { Text("API token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val base = Api.normalize(url)
                if (base == null) { error = "Enter an https:// address"; return@Button }
                busy = true; error = null
                scope.launch {
                    val cfg = HubConfig(base.toString(), token)
                    repo.test(cfg).onSuccess {
                        repo.settings.save(cfg)
                        repo.refresh()
                    }.onFailure { error = it.message ?: "Connection failed" }
                    busy = false
                }
            },
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Connecting…" else "Connect") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("The token is encrypted with a key held in the phone's secure keystore.",
                style = MaterialTheme.typography.bodySmall, color = m.muted)
        }
        WorkoutEntry()
    }
}
