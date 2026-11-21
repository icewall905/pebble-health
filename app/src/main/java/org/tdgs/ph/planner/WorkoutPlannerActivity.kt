package org.tdgs.ph.planner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.saveable.rememberSaveable
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.Segmented
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.window.DialogProperties
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val workoutId = Regex("[a-f0-9]{32}")
private fun body(vararg values: Pair<String, Any?>) = JSONObject(mapOf(*values))

internal class WorkoutPlannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerScreen(api: PlannerApi, settingsFirst: Boolean, reminder: String?, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var state by remember(api) { mutableStateOf<JSONObject?>(null) }
    var dateOverride by rememberSaveable(api) { mutableStateOf<String?>(null) }
    var calendarView by rememberSaveable(api) { mutableStateOf(CalendarView.MONTH) }
    var history by remember(api) { mutableStateOf(emptyMap<YearMonth, List<JSONObject>>()) }
    var historyError by remember(api) { mutableStateOf(false) }
    var section by rememberSaveable(api) {
        mutableStateOf(if (settingsFirst) Section.SETTINGS else if (reminder != null) Section.TRAINER else Section.CALENDAR)
    }
    var selected by rememberSaveable(api) { mutableStateOf(reminder) }
    var busy by remember(api) { mutableStateOf(false) }
    var needsLogin by remember(api) { mutableStateOf(false) }
    var notice by remember(api) { mutableStateOf("") }
    var edit by remember(api) { mutableStateOf<JSONObject?>(null) }
    var delete by remember(api) { mutableStateOf<JSONObject?>(null) }
    var message by rememberSaveable(api) { mutableStateOf("") }

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

    val data = state
    val timezone = runCatching { ZoneId.of(data?.optString("timezone").orEmpty()) }.getOrDefault(ZoneId.systemDefault())
    val today = LocalDate.now(timezone)
    val day = dateOverride?.let(LocalDate::parse) ?: today
    val month = YearMonth.from(day)
    val weekStart = day.startOfWeek(locale)
    val historyMonths = when (calendarView) {
        CalendarView.WEEK -> setOf(YearMonth.from(weekStart), YearMonth.from(weekStart.plusDays(6)))
        CalendarView.MONTH -> {
            val gridStart = month.atDay(1).startOfWeek(locale)
            val gridEnd = month.atEndOfMonth().startOfWeek(locale).plusDays(6)
            setOf(YearMonth.from(gridStart), month, YearMonth.from(gridEnd))
        }
        CalendarView.AGENDA -> setOf(month)
    }
    val planned = remember(data) { data?.objects("workouts").orEmpty() }
    val calendar = remember(planned, history, historyMonths) {
        calendarWorkouts(planned, historyMonths.flatMap { history[it].orEmpty() })
    }
    val current = planned.firstOrNull { it.optString("id") == selected }
    val ready = data?.optBoolean("trainer_ready") == true
    val turns = remember(data) { data?.objects("messages").orEmpty() }
    val proposals = remember(data) { data?.objects("options").orEmpty() }
    val showingTrainer = data != null && section == Section.TRAINER
    val trainerList = rememberLazyListState()
    val plannerList = rememberLazyListState()
    var accountMenu by remember { mutableStateOf(false) }

    LaunchedEffect(api) { run { state = api.load() } }
    LaunchedEffect(api, timezone, historyMonths, data != null && section == Section.CALENDAR) {
        historyError = false
        history = emptyMap()
        if (data != null && section == Section.CALENDAR) {
            for (period in historyMonths.sortedBy { if (it == month) 0 else 1 }) {
                try {
                    history = history + (period to api.history(period.toString()).objects("workouts"))
                } catch (error: PlannerLoginRequired) {
                    state = null
                    needsLogin = true
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    historyError = true
                }
            }
        }
    }
    LaunchedEffect(showingTrainer, turns.lastOrNull()?.toString(), proposals.lastOrNull()?.toString()) {
        if (showingTrainer && (turns.isNotEmpty() || proposals.isNotEmpty())) {
            val welcomeCount = if (turns.isEmpty()) 1 else 0
            val latest = if (proposals.isNotEmpty()) turns.size + welcomeCount else turns.lastIndex
            trainerList.scrollToItem(latest)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Workout planner") },
                    navigationIcon = {
                        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (data != null) Box {
                            IconButton(enabled = !busy, onClick = { accountMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, "Planner account")
                            }
                            DropdownMenu(accountMenu, { accountMenu = false }) {
                                DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                                    accountMenu = false
                                    run {
                                        api.logout()
                                        message = ""
                                        selected = null
                                        dateOverride = null
                                        state = null
                                        needsLogin = true
                                    }
                                })
                            }
                        }
                    },
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (notice.isNotBlank()) {
                    Text(notice, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                if (data != null) {
                    Segmented(Section.entries.map { it.label }, section.ordinal, {
                        val next = Section.entries[it]
                        if (next != section && next != Section.TRAINER) plannerList.requestScrollToItem(0)
                        section = next
                    }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        },
        bottomBar = {
            if (showingTrainer) {
                TrainerComposer(
                    current, ready, busy, message, { message = it.take(3000) }, { selected = null },
                    send = { send("chat", "message" to message, "workout_id" to selected, done = { message = "" }) },
                    suggest = { send("chat", "message" to "Suggest plans based on my saved setup and recent health.") },
                    reschedule = {
                        send("chat", "message" to message.ifBlank { "Move this workout to another suitable day." },
                            "workout_id" to selected, "reschedule" to true, done = { message = "" })
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = if (showingTrainer) trainerList else plannerList,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (data == null) {
                if (needsLogin) item {
                    PhCard {
                        PlannerLogin(busy) { username, password ->
                            run {
                                api.login(username, password)
                                state = api.load()
                                needsLogin = false
                            }
                        }
                    }
                } else if (!busy) item {
                    Button(onClick = { run { state = api.load() } }) { Text("Retry") }
                }
                return@LazyColumn
            }
            when (section) {
                Section.CALENDAR -> {
                    item("calendar") {
                        PlannerCalendarHeader(day, today, calendarView, locale, calendar, busy,
                            selectDay = { dateOverride = it.toString() },
                            selectView = { calendarView = it },
                            add = { edit = body("start" to day.atTime(17, 0).format(timestamp), "minutes" to 30) },
                            export = { export.launch("workouts.ics") },
                        )
                    }
                    if (historyError) item { Note("Some recorded workouts could not be loaded.") }
                    val visible = calendar.filter {
                        when (calendarView) {
                            CalendarView.MONTH -> it.day == day
                            CalendarView.WEEK -> it.day >= weekStart && it.day < weekStart.plusDays(7)
                            CalendarView.AGENDA -> YearMonth.from(it.day) == month
                        }
                    }.groupBy { it.day }.toMutableMap()
                    if (calendarView != CalendarView.AGENDA) visible.putIfAbsent(day, emptyList())
                    val heading = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
                    if (visible.isEmpty()) item {
                        PhCard {
                            Text("No workouts this month", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    visible.toSortedMap().forEach { (date, entries) ->
                        item("date-$date") {
                            Text(date.format(heading), Modifier.padding(start = 4.dp, top = 4.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (date == day && calendarView != CalendarView.AGENDA)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        if (entries.isEmpty()) item("empty-$date") {
                            PhCard { Text("No workouts on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        items(entries, key = { "${it.recorded}-${it.data.optString("id")}" }) { entry ->
                            val workout = entry.data
                            CalendarWorkoutCard(entry, busy, locale,
                                edit = { edit = workout },
                                delete = { delete = workout },
                                ask = { selected = workout.optString("id"); section = Section.TRAINER },
                                addToPhone = {
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
                                },
                            )
                        }
                    }
                }
                Section.TRAINER -> {
                    if (turns.isEmpty()) item { TrainerWelcome() }
                    items(turns) { TrainerTurn(it) }
                    items(proposals, key = { it.optString("id") }) { option ->
                        TrainerProposal(option, planned, busy) {
                            send("accept", "id" to option.optString("id"), done = {
                                option.objects("workouts").firstOrNull()?.optString("start")?.take(10)
                                    ?.let { dateOverride = it }
                                plannerList.requestScrollToItem(0)
                                section = Section.CALENDAR
                            })
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
                if (start != null) dateOverride = start.take(10)
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

internal fun workoutStatus(workout: JSONObject): String {
    val status = workout.optString("status", "planned")
    return if (status == "planned" && workout.optLong("end_ms", Long.MAX_VALUE) <= System.currentTimeMillis())
        "Unconfirmed" else status.replaceFirstChar(Char::uppercase)
}

@Composable
internal fun WorkoutDescription(workout: JSONObject, timeOnly: Boolean = false) {
    val title = workout.optString("title").ifBlank { Fmt.kind(workout.optString("kind", "Workout")) }
    val minutes = workout.optInt("minutes", (workout.optLong("seconds") / 60).toInt())
    Text(title, style = MaterialTheme.typography.titleMedium)
    val start = workout.optString("start")
    val locale = LocalConfiguration.current.locales[0]
    val time = runCatching {
        val dateTime = LocalDateTime.parse(start)
        val formatter = if (timeOnly) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        dateTime.format(formatter.withLocale(locale))
    }.getOrDefault(start.replace('T', ' '))
    Text("$time · $minutes min", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    workout.optString("details").takeIf(String::isNotBlank)?.let { Text(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditWorkout(
    item: JSONObject,
    busy: Boolean,
    cancel: () -> Unit,
    save: (List<Pair<String, Any?>>) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var choosingDate by remember(item) { mutableStateOf(false) }
    var choosingTime by remember(item) { mutableStateOf(false) }
    var title by remember(item) { mutableStateOf(item.optString("title")) }
    var start by remember(item) { mutableStateOf(LocalDateTime.parse(item.optString("start"))) }
    var duration by remember(item) { mutableStateOf(item.optInt("minutes", 30).toString()) }
    var details by remember(item) { mutableStateOf(item.optString("details")) }
    var status by remember(item) { mutableStateOf(item.optString("status", "planned")) }
    AlertDialog(onDismissRequest = cancel, title = { Text(if (item.has("id")) "Edit workout" else "Add workout") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Workout name") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { choosingDate = true }) {
                        Text(start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
                    }
                    TextButton(onClick = { choosingTime = true }) {
                        Text(start.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)))
                    }
                }
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
    if (choosingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis =
            start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    picker.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()
                        start = date.atTime(start.toLocalTime())
                    }
                    choosingDate = false
                }) { Text("Set date") }
            },
            dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
    if (choosingTime) {
        val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        val config = LocalConfiguration.current
        val horizontal = config.screenHeightDp < 480 && config.screenWidthDp >= 560
        val layout = if (horizontal) TimePickerLayoutType.Horizontal else TimePickerLayoutType.Vertical
        val picker = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = is24Hour)
        AlertDialog(
            onDismissRequest = { choosingTime = false },
            title = { Text("Workout time") },
            modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TimePicker(picker, layoutType = layout)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    start = start.toLocalDate().atTime(picker.hour, picker.minute)
                    choosingTime = false
                }) { Text("Set time") }
            },
            dismissButton = { TextButton(onClick = { choosingTime = false }) { Text("Cancel") } },
        )
    }
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
        PhCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
        PhCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
        PhCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

@Composable
private fun PlannerField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth(), label = { Text(label) })
}
