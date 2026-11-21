package org.tdgs.ph.planner

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tdgs.ph.PhApp
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.theme.PhTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val workoutId = Regex("[a-f0-9]{32}")
private fun body(vararg values: Pair<String, Any?>) = JSONObject(mapOf(*values))

internal class WorkoutPlannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as PhApp).repository.settings
        val fromReminder = intent.getStringExtra("workout_id")?.takeIf(workoutId::matches)
        setContent {
            val prefs by settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
            val hub by settings.config.collectAsState(initial = null)
            val endpoint = hub?.baseUrl?.let { Api.normalize(it) }
            val api = remember(endpoint) { endpoint?.let { PlannerApi(it, settings) } }
            PhTheme(prefs.theme) {
                val service = api
                if (service == null) {
                    Scaffold { padding ->
                        Column(Modifier.padding(padding).padding(16.dp)) {
                            Text("Connect your Pebble Health hub in Settings.")
                            TextButton(onClick = ::finish) { Text("Back") }
                        }
                    }
                } else {
                    PlannerScreen(service, intent.getBooleanExtra("model_settings", false), fromReminder, ::finish)
                }
            }
        }
    }
}

private enum class Section(val label: String) { CALENDAR("Calendar"), TRAINER("Trainer"), SETTINGS("Settings") }

@Composable
private fun PlannerScreen(api: PlannerApi, settingsFirst: Boolean, reminder: String?, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var state by remember(api) { mutableStateOf<JSONObject?>(null) }
    var month by remember(api) { mutableStateOf(YearMonth.now()) }
    var history by remember(api) { mutableStateOf(emptyList<JSONObject>()) }
    var section by remember(api) {
        mutableStateOf(if (settingsFirst) Section.SETTINGS else if (reminder != null) Section.TRAINER else Section.CALENDAR)
    }
    var selected by remember(api) { mutableStateOf(reminder) }
    var busy by remember(api) { mutableStateOf(false) }
    var needsLogin by remember(api) { mutableStateOf(false) }
    var notice by remember(api) { mutableStateOf("") }
    var edit by remember(api) { mutableStateOf<JSONObject?>(null) }
    var delete by remember(api) { mutableStateOf<JSONObject?>(null) }
    var message by remember(api) { mutableStateOf("") }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = ""
        scope.launch {
            try {
                action()
            } catch (error: PlannerLoginRequired) {
                state = null
                needsLogin = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "Planner request failed."
            } finally {
                busy = false
            }
        }
    }

    fun send(action: String, vararg values: Pair<String, Any?>, done: (() -> Unit)? = null) = run {
        val current = state ?: return@run
        val updated = api.mutate(body("action" to action, "revision" to current.optInt("revision"), *values))
        if (updated.has("revision")) state = updated
        done?.invoke()
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { destination ->
        if (destination != null) run {
            val content = api.calendar()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destination)?.use { it.write(content.toByteArray()) }
                    ?: error("Could not open the selected document.")
            }
            notice = "Calendar exported."
        }
    }

    LaunchedEffect(api) { run { state = api.load() } }
    LaunchedEffect(api, month, state != null) {
        history = emptyList()
        if (state != null) {
            try {
                history = api.history(month.toString()).objects("workouts")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notice = "Recorded workouts could not be loaded."
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(
            Modifier.padding(padding).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = back) { Text("Back") }
                    if (state != null) TextButton(enabled = !busy, onClick = {
                        run {
                            api.logout()
                            state = null
                            needsLogin = true
                        }
                    }) { Text("Sign out") }
                }
                Text("Workout planner", style = MaterialTheme.typography.headlineSmall)
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
                if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp))
            }
            val data = state
            if (data == null) {
                if (needsLogin) item {
                    PlannerLogin(busy) { username, password ->
                        run {
                            api.login(username, password)
                            state = api.load()
                            needsLogin = false
                        }
                    }
                } else if (!busy) item {
                    Button(onClick = { run { state = api.load() } }) { Text("Retry") }
                }
                return@LazyColumn
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Section.entries.forEach { tab ->
                        FilterChip(section == tab, { section = tab }, { Text(tab.label) })
                    }
                }
            }
            when (section) {
                Section.CALENDAR -> {
                    val timezone = runCatching { ZoneId.of(data.optString("timezone")) }.getOrDefault(ZoneId.systemDefault())
                    item {
                        Text(month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)), style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { month = month.minusMonths(1) }) { Text("Previous") }
                            TextButton(onClick = { month = month.plusMonths(1) }) { Text("Next") }
                            Button(enabled = !busy, onClick = {
                                val today = LocalDate.now(timezone)
                                val day = if (YearMonth.from(today) == month) today else month.atDay(1)
                                edit = body("start" to day.atTime(17, 0).format(timestamp), "minutes" to 30)
                            }) { Text("Add workout") }
                            TextButton(enabled = !busy, onClick = { export.launch("workouts.ics") }) { Text("Export .ics") }
                        }
                    }
                    val planned = data.objects("workouts").filter { it.optString("start").startsWith(month.toString()) }
                    if (planned.isEmpty()) item { Text("No planned workouts this month.") }
                    items(planned, key = { it.optString("id") }) { workout ->
                        val id = workout.optString("id")
                        val status = workout.optString("status", "planned")
                        PhCard {
                            WorkoutDescription(workout, showStatus = true)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(enabled = !busy, onClick = { edit = workout }) { Text("Edit") }
                                TextButton(enabled = !busy, onClick = { delete = workout }) { Text("Delete") }
                                TextButton(onClick = { selected = id; section = Section.TRAINER }) { Text("Ask Trainer") }
                                if (status !in setOf("skipped", "rescheduled")) {
                                    TextButton(onClick = {
                                        val insert = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                                            .putExtra(CalendarContract.Events.TITLE, workout.optString("title"))
                                            .putExtra(CalendarContract.Events.DESCRIPTION, workout.optString("details"))
                                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, workout.optLong("start_ms"))
                                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, workout.optLong("end_ms"))
                                        try {
                                            context.startActivity(insert)
                                        } catch (_: ActivityNotFoundException) {
                                            notice = "No calendar app is available. Use Export .ics."
                                        }
                                    }) { Text("Add to phone calendar") }
                                }
                            }
                        }
                    }
                    if (history.isNotEmpty()) item { Text("Recorded workouts", style = MaterialTheme.typography.titleMedium) }
                    items(history) { workout ->
                        PhCard {
                            WorkoutDescription(workout)
                            val metrics = workout.optJSONObject("metrics")
                            val distance = metrics?.optDouble("distance_m")?.takeIf { it.isFinite() }
                            if (distance != null) Text(String.format(locale, "%.2f km", distance / 1000))
                            if (!workout.isNull("rpe")) Text("Effort ${workout.optInt("rpe")}/10")
                        }
                    }
                }
                Section.TRAINER -> {
                    val current = data.objects("workouts").firstOrNull { it.optString("id") == selected }
                    val ready = data.optBoolean("trainer_ready")
                    if (current != null) item {
                        PhCard {
                            Text("Workout context", style = MaterialTheme.typography.titleMedium)
                            WorkoutDescription(current)
                            TextButton(onClick = { selected = null }) { Text("Clear workout context") }
                        }
                    }
                    items(data.objects("messages")) { turn ->
                        PhCard {
                            Text(if (turn.optString("role") == "user") "You" else "Trainer",
                                style = MaterialTheme.typography.titleMedium)
                            Text(turn.optString("text"))
                        }
                    }
                    items(data.objects("options"), key = { it.optString("id") }) { option ->
                        PhCard {
                            Text(option.optString("title"), style = MaterialTheme.typography.titleMedium)
                            Text(option.optString("summary"))
                            val removals = option.optJSONArray("remove_ids")
                            for (index in 0 until (removals?.length() ?: 0)) {
                                val removed = data.objects("workouts").firstOrNull { it.optString("id") == removals?.optString(index) }
                                if (removed != null) Text("Replace ${removed.optString("title")} · ${removed.optString("start").replace('T', ' ')}")
                            }
                            option.objects("workouts").forEach {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                WorkoutDescription(it)
                            }
                            Button(enabled = !busy, onClick = {
                                send("accept", "id" to option.optString("id"), done = {
                                    option.objects("workouts").firstOrNull()?.optString("start")?.take(7)
                                        ?.let { month = YearMonth.parse(it) }
                                    section = Section.CALENDAR
                                })
                            }) { Text("Accept plan") }
                        }
                    }
                    item {
                        if (!ready) Text("Trainer is currently unavailable.")
                        OutlinedTextField(message, { message = it.take(3000) }, label = { Text("Message Trainer") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = ready && !busy && message.isNotBlank(), onClick = {
                                send("chat", "message" to message, "workout_id" to selected, done = { message = "" })
                            }) { Text("Send") }
                            TextButton(enabled = ready && !busy, onClick = {
                                send("chat", "message" to "Suggest plans based on my saved setup and recent health.")
                            }) { Text("Suggest plans") }
                            if (current != null) TextButton(enabled = ready && !busy, onClick = {
                                send("chat", "message" to message.ifBlank { "Move this workout to another suitable day." },
                                    "workout_id" to selected, "reschedule" to true, done = { message = "" })
                            }) { Text("Propose a reschedule") }
                        }
                    }
                }
                Section.SETTINGS -> item {
                    PlannerSettings(data, busy) { action, values -> send(action, *values.toTypedArray(), done = {
                        notice = when (action) {
                            "test_model" -> "Trainer model responded."
                            "ntfy_test" -> "Test notification sent."
                            else -> "Saved."
                        }
                    }) }
                }
            }
        }
    }
    edit?.let { workout ->
        EditWorkout(workout, busy, { edit = null }) { values ->
            send("save", *values.toTypedArray(), done = {
                val start = values.firstOrNull { it.first == "start" }?.second as? String
                if (start != null) month = YearMonth.parse(start.take(7))
                edit = null
            })
        }
    }
    delete?.let { workout ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete workout?") },
            text = { Text(workout.optString("title")) },
            confirmButton = { Button(enabled = !busy, onClick = {
                send("delete", "id" to workout.optString("id"), done = { delete = null })
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun PlannerLogin(busy: Boolean, login: (String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sign in with your Pebble Health dashboard account.")
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && user.isNotBlank() && password.isNotBlank(), onClick = {
            login(user, password)
            password = ""
        }) { Text("Sign in") }
    }
}

@Composable
private fun WorkoutDescription(workout: JSONObject, showStatus: Boolean = false) {
    val title = workout.optString("title").ifBlank { workout.optString("kind", "Workout").replace('_', ' ') }
    val minutes = workout.optInt("minutes", (workout.optLong("seconds") / 60).toInt())
    Text(title, style = MaterialTheme.typography.titleMedium)
    val status = if (showStatus) {
        val recorded = workout.optString("status", "planned")
        if (recorded == "planned" && workout.optLong("end_ms", Long.MAX_VALUE) <= System.currentTimeMillis()) " · Unconfirmed"
        else " · " + recorded.replaceFirstChar(Char::uppercase)
    } else ""
    Text("${workout.optString("start").replace('T', ' ')} · $minutes min$status")
    workout.optString("details").takeIf(String::isNotBlank)?.let { Text(it) }
}

@Composable
private fun EditWorkout(
    item: JSONObject,
    busy: Boolean,
    cancel: () -> Unit,
    save: (List<Pair<String, Any?>>) -> Unit,
) {
    val context = LocalContext.current
    var title by remember(item) { mutableStateOf(item.optString("title")) }
    var start by remember(item) { mutableStateOf(LocalDateTime.parse(item.optString("start"))) }
    var duration by remember(item) { mutableStateOf(item.optInt("minutes", 30).toString()) }
    var details by remember(item) { mutableStateOf(item.optString("details")) }
    var status by remember(item) { mutableStateOf(item.optString("status", "planned")) }
    AlertDialog(onDismissRequest = cancel, title = { Text(if (item.has("id")) "Edit workout" else "Add workout") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Workout name") }, singleLine = true)
                TextButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> start = LocalDate.of(y, m + 1, d).atTime(start.toLocalTime()) },
                        start.year, start.monthValue - 1, start.dayOfMonth).show()
                }) { Text(start.toLocalDate().toString()) }
                TextButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> start = start.toLocalDate().atTime(h, m) },
                        start.hour, start.minute, true).show()
                }) { Text(start.toLocalTime().toString()) }
                OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(3) }, label = { Text("Minutes") }, singleLine = true)
                OutlinedTextField(details, { details = it.take(4000) }, label = { Text("Session details") }, minLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("planned", "done", "skipped", "rescheduled").forEach { value ->
                        FilterChip(status == value, { status = value }, { Text(value.replaceFirstChar(Char::uppercase)) })
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && title.isNotBlank() && duration.toIntOrNull()?.let { it in 1..720 } == true,
                onClick = {
                    save(listOf("id" to item.optString("id"), "title" to title, "start" to start.format(timestamp),
                        "minutes" to duration.toInt(), "details" to details, "status" to status))
                }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } })
}

@Composable
private fun PlannerSettings(data: JSONObject, busy: Boolean, submit: (String, List<Pair<String, Any?>>) -> Unit) {
    val context = LocalContext.current
    var priorities by remember { mutableStateOf(data.optString("priorities")) }
    var activities by remember { mutableStateOf(data.optString("activities")) }
    var goals by remember { mutableStateOf(data.optString("goals")) }
    var availability by remember { mutableStateOf(data.optString("availability")) }
    var timezone by remember { mutableStateOf(data.optString("timezone")) }
    var morning by remember { mutableStateOf(data.optString("morning")) }
    var followUp by remember { mutableStateOf(data.optBoolean("follow_up")) }
    val modelSettings = data.optJSONObject("model_settings") ?: JSONObject()
    var model by remember { mutableStateOf(modelSettings.optString("model")) }
    var endpoint by remember { mutableStateOf(modelSettings.optString("base_url")) }
    var webSearch by remember { mutableStateOf(modelSettings.optBoolean("web_search")) }
    val reminders = data.optJSONObject("ntfy") ?: JSONObject()
    var enabled by remember(reminders.optBoolean("enabled")) { mutableStateOf(reminders.optBoolean("enabled")) }
    var topic by remember(reminders.optString("topic")) { mutableStateOf(reminders.optString("topic")) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Goals and schedule", style = MaterialTheme.typography.titleMedium)
        PlannerField(priorities, { priorities = it }, "Priorities")
        PlannerField(activities, { activities = it }, "Activities and equipment")
        PlannerField(goals, { goals = it }, "Goals")
        PlannerField(availability, { availability = it }, "Availability")
        PlannerField(timezone, { timezone = it }, "Timezone")
        PlannerField(morning, { morning = it }, "Morning reminder (HH:MM)")
        Row { Checkbox(followUp, { followUp = it }); Text("Follow up one hour after workouts", Modifier.padding(top = 12.dp)) }
        Button(enabled = !busy, onClick = {
            submit("setup", listOf("priorities" to priorities, "activities" to activities, "goals" to goals,
                "availability" to availability, "timezone" to timezone, "morning" to morning, "follow_up" to followUp))
        }) { Text("Save setup") }
        HorizontalDivider()
        Text("ntfy reminders", style = MaterialTheme.typography.titleMedium)
        if (!reminders.optBoolean("available")) Text("ntfy is currently unavailable on the hub.")
        else {
            Text("In ntfy, subscribe to this topic on ${reminders.optString("server")}. Keep the topic private.")
            if (topic.isNotEmpty()) Text(topic)
            PlannerField(topic, { topic = it }, "Topic (blank generates a private topic)")
            Row { Checkbox(enabled, { enabled = it }); Text("Send workout reminders through ntfy", Modifier.padding(top = 12.dp)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    submit("ntfy_settings", listOf("enabled" to enabled, "topic" to topic))
                }) { Text("Save ntfy settings") }
                val savedTopic = reminders.optString("topic")
                if (savedTopic.isNotBlank()) {
                    TextButton(onClick = {
                        val host = Uri.parse(reminders.optString("server")).encodedAuthority
                        if (host != null) {
                            val uri = Uri.Builder().scheme("ntfy").encodedAuthority(host).appendPath(savedTopic).build()
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: ActivityNotFoundException) {
                                android.widget.Toast.makeText(context, "Open the ntfy app and add this topic.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("Open topic in ntfy") }
                }
                TextButton(enabled = !busy && reminders.optBoolean("enabled"), onClick = {
                    submit("ntfy_test", emptyList())
                }) { Text("Send test notification") }
            }
        }
        HorizontalDivider()
        Text("Trainer model and memory", style = MaterialTheme.typography.titleMedium)
        val endpoints = data.optJSONArray("allowed_model_endpoints")
        for (i in 0 until (endpoints?.length() ?: 0)) {
            val choice = endpoints?.optString(i).orEmpty()
            FilterChip(endpoint == choice, { endpoint = choice }, { Text(choice) })
        }
        PlannerField(model, { model = it }, "Model")
        Row {
            Checkbox(webSearch, { webSearch = it }, enabled = data.optBoolean("search_available"))
            Text("Allow public web search", Modifier.padding(top = 12.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                submit("model_settings", listOf("base_url" to endpoint, "model" to model, "web_search" to webSearch))
            }) { Text("Save model settings") }
            TextButton(enabled = !busy, onClick = {
                submit("test_model", listOf("base_url" to endpoint, "model" to model))
            }) { Text("Test connection") }
        }
        val notes = data.optJSONArray("notes")
        for (i in 0 until (notes?.length() ?: 0)) {
            val note = notes?.optString(i).orEmpty()
            Text(note)
            TextButton(enabled = !busy, onClick = { submit("forget_note", listOf("note" to note)) }) { Text("Remove saved note") }
        }
    }
}

@Composable
private fun PlannerField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth(), label = { Text(label) })
}
