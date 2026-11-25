@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.tdgs.ph.workout

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.tdgs.ph.PhApp
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.theme.LocalMetrics
import org.tdgs.ph.ui.theme.PhTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun WorkoutEntry() {
    val context = LocalContext.current
    val recording by WorkoutRecorder.live.collectAsState()
    val open = { context.startActivity(Intent(context, WorkoutActivity::class.java)) }
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        FilledTonalButton(onClick = open, modifier = Modifier.weight(1f)) {
            Text(if (recording == null) "Record workout" else "View recording")
        }
        TextButton(onClick = {
            context.startActivity(Intent(context, WorkoutActivity::class.java).putExtra("manual_entry", true))
        }) { Text("Add past activity") }
    }
}

internal class WorkoutActivity : ComponentActivity() {
    private var screenVisible by mutableStateOf(false)
    private var watchSport by mutableStateOf<Sport?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWatchLaunch(intent)
        watchSport = Sport.entries.firstOrNull { it.name == savedInstanceState?.getString("watch_setup_sport") }
        enableEdgeToEdge()
        val repo = (application as PhApp).repository
        setContent {
            val prefs by repo.settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
            PhTheme(prefs.theme) {
                key(watchSport) {
                    WorkoutScreen(repo, watchSport == null && intent.getBooleanExtra("history", false),
                        intent.getStringExtra("workout_id").takeIf { watchSport == null },
                        watchSport == null && intent.getBooleanExtra("manual_entry", false),
                        watchSport, screenVisible, ::finish)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("watch_setup_sport", watchSport?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureWatchLaunch(intent)
    }

    private fun configureWatchLaunch(intent: Intent) {
        val show = intent.getBooleanExtra("show_watch_workout", false)
        setShowWhenLocked(show)
        setTurnScreenOn(show)
    }

    override fun onPostResume() {
        super.onPostResume()
        val sport = Sport.entries.firstOrNull { it.name == intent.getStringExtra("watch_start_sport") } ?: return
        intent.removeExtra("watch_start_sport")
        if (WorkoutRecorder.live.value != null) return
        if (WatchWorkoutCommands.gpsGranted(this, sport) && runCatching {
            startForegroundService(WatchWorkoutCommands.startIntent(this, sport)
                .putExtra("phone_display_open", true))
        }.isSuccess) return
        watchSport = sport
    }

    override fun onStart() { super.onStart(); screenVisible = true }
    override fun onStop() { screenVisible = false; super.onStop() }
}

@Composable
private fun WorkoutScreen(repo: Repository, openHistory: Boolean, initialId: String?, manualEntry: Boolean, watchSport: Sport?, visible: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    val live by WorkoutRecorder.live.collectAsState()
    val finished by WorkoutRecorder.finished.collectAsState()
    val error by WorkoutRecorder.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(manualEntry) }
    var saving by remember { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(openHistory) }
    var detailId by rememberSaveable { mutableStateOf(initialId) }
    var trainerReview by rememberSaveable(detailId) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val storeResult by produceState<Result<WorkoutStore>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { WorkoutStore.get(context) } }
    }
    val store = storeResult?.getOrNull()

    LaunchedEffect(live?.workout?.id) {
        if (live != null) adding = false
    }

    LaunchedEffect(finished) {
        finished?.let {
            detailId = it
            history = true
            refresh++
            WorkoutRecorder.finished.compareAndSet(it, null)
        }
    }
    LaunchedEffect(error) {
        error?.let {
            refresh++
            snackbar.showSnackbar(it)
            WorkoutRecorder.error.compareAndSet(it, null)
        }
    }

    val title = live?.workout?.sport?.label ?: if (detailId != null) "Workout review" else if (history) "Workout history" else "Log workout"
    val back = {
        when {
            live != null -> close()
            trainerReview -> trainerReview = false
            detailId != null && initialId != null -> close()
            detailId != null -> detailId = null
            history -> history = false
            else -> close()
        }
    }
    BackHandler(enabled = !adding) { back() }

    Scaffold(
        topBar = {
            if (!trainerReview || live != null) TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (detailId == null && live == null) {
                        TextButton(onClick = { adding = true }) { Text("Add past") }
                        if (!history) TextButton(onClick = { history = true }) { Text("History") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Box(Modifier.padding(insets).fillMaxSize()) {
            val recording = live
            when {
                storeResult?.isFailure == true -> Text("Workout storage is unavailable.", Modifier.padding(20.dp))
                store == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                recording != null -> Recording(recording)
                detailId != null -> Review(repo, store, detailId!!, snackbar, trainerReview, { trainerReview = it }) {
                    detailId = null
                    refresh++
                }
                history -> History(store, refresh) { detailId = it }
                else -> Setup(visible, watchSport)
            }
            if (adding && store != null) {
                WorkoutLogEditor(null, saving, close = {
                    adding = false
                    if (manualEntry && detailId == null) close()
                }) { workout ->
                    saving = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { runCatching { store.saveLog(workout, new = true) } }
                        saving = false
                        if (saved.isSuccess) {
                            WorkoutUploadWorker.enqueue(context, workout.id)
                            adding = false
                            history = true
                            detailId = workout.id
                            refresh++
                        } else snackbar.showSnackbar("Could not save activity.")
                    }
                }
            }
        }
    }
}

private data class StrapChoice(val label: String, val address: String)

@SuppressLint("MissingPermission")
@Composable
private fun Setup(visible: Boolean, watchSport: Sport?) {
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("workout_setup", 0) }
    var sport by rememberSaveable { mutableStateOf(watchSport ?: Sport.run) }
    var gps by rememberSaveable { mutableStateOf(sport.gpsDefault) }
    var address by rememberSaveable { mutableStateOf(settings.getString("strap_address", null)) }
    var strapName by rememberSaveable { mutableStateOf(settings.getString("strap_name", null)) }
    var mirrorWatch by rememberSaveable { mutableStateOf(watchSport != null || settings.getBoolean("mirror_watch", false)) }
    var maxBpm by remember { mutableIntStateOf(settings.getInt("max_bpm", 0)) }
    var editingMax by remember { mutableStateOf(false) }
    if (editingMax) MaxHeartRateDialog(maxBpm, { editingMax = false }) {
        settings.edit().putInt("max_bpm", it).apply()
        maxBpm = it
        editingMax = false
    }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val armed by WorkoutRecorder.armed.collectAsState()
    val found = remember { mutableStateMapOf<String, StrapChoice>() }
    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun start() {
        if (!visible) return
        try {
            WorkoutRecorder.command(context, WorkoutRecorder.START) {
                putExtra("sport", sport.name)
                putExtra("gps", gps)
                putExtra("strap", address)
                putExtra("mirror_watch", mirrorWatch)
            }
            starting = true
        } catch (_: RuntimeException) {
            message = "Open the workout screen and try starting again."
        }
    }
    LaunchedEffect(starting) {
        if (starting) {
            delay(3000)
            starting = false
        }
    }
    val scanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (bluetoothPermissions.all(::granted)) scanning = true
        else message = "Allow Bluetooth access to find a chest strap."
    }
    val startPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when {
            gps && !granted(Manifest.permission.ACCESS_FINE_LOCATION) ->
                message = "Allow precise location for a route, or turn off phone GPS."
            address != null && Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT) ->
                message = "Allow Nearby devices for heart rate, or remove the selected strap."
            else -> start()
        }
    }

    fun armWatch() {
        if (!visible) return
        runCatching { WorkoutRecorder.command(context, WorkoutRecorder.ARM) }
            .onFailure { message = "Could not prepare for a watch start." }
    }
    val armPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) armWatch()
        else message = "Allow precise location to prepare for outdoor workouts from the watch."
    }

    LaunchedEffect(scanning, visible) {
        if (!visible) scanning = false
        if (scanning) {
            delay(12_000)
            scanning = false
            if (found.isEmpty()) message = "No straps found. Put on the strap, turn on Bluetooth, and scan again."
        }
    }
    DisposableEffect(scanning, visible) {
        if (!scanning || !visible) return@DisposableEffect onDispose {}
        found.clear()
        val scanner = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner }.getOrNull()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
                val suffix = device.address.takeLast(5)
                found[device.address] = StrapChoice(name ?: "Heart rate sensor ($suffix)", device.address)
            }

            override fun onScanFailed(errorCode: Int) {
                message = "Bluetooth scan failed. Try again."
                scanning = false
            }
        }
        if (scanner == null) {
            message = "Turn on Bluetooth to find your chest strap."
            scanning = false
        } else {
            runCatching { scanner.startScan(listOf(heartScanFilter), heartScanSettings, callback) }.onFailure {
                message = "Could not scan. Check Bluetooth permissions."
                scanning = false
            }
        }
        onDispose { runCatching { scanner?.stopScan(callback) } }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Activity", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sport.entries.forEach { option ->
                    FilterChip(selected = sport == option, onClick = { sport = option; gps = option.gpsDefault },
                        label = { Text(option.label) })
                }
            }
        }
        item {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone GPS", style = MaterialTheme.typography.titleMedium)
                    Text("Record your route, distance, pace and elevation.", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = gps, onCheckedChange = { gps = it })
            }
        }
        item {
            HorizontalDivider()
            Text("Heart rate", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text(strapName ?: "Use a Bluetooth chest strap, such as Garmin HRM-Pro.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    message = null
                    if (scanning) scanning = false else if (bluetoothPermissions.all(::granted)) scanning = true
                    else if (Build.VERSION.SDK_INT >= 31) scanPermission.launch(bluetoothPermissions)
                    else scanPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }) { Text(if (scanning) "Stop scan" else "Find chest strap") }
                if (address != null) TextButton(onClick = {
                    address = null
                    strapName = null
                    settings.edit().remove("strap_address").remove("strap_name").apply()
                }) { Text("Remove") }
            }
        }
        items(found.values.sortedBy { it.label }, key = { it.address }) { strap ->
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                address = strap.address
                strapName = strap.label
                scanning = false
                found.clear()
                message = null
                settings.edit().putString("strap_address", strap.address).putString("strap_name", strap.label).apply()
            }) { Text(strap.label) }
        }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Live stats on Pebble", style = MaterialTheme.typography.titleMedium)
                    Text("Opens Pebble Health Workout on the watch when you start. Install it from Pebble Health Settings.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(mirrorWatch, { mirrorWatch = it; settings.edit().putBoolean("mirror_watch", it).apply() })
            }
        }
        item {
            TextButton(onClick = { editingMax = true }) {
                Text(if (maxBpm == 0) "Set max HR for zones" else "Max heart rate: $maxBpm bpm")
            }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !starting, onClick = {
                if (armed) WorkoutRecorder.command(context, WorkoutRecorder.DISARM)
                else {
                    val permissions = buildList {
                        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (permissions.isEmpty()) armWatch() else armPermission.launch(permissions.toTypedArray())
                }
            }) { Text(if (armed) "Stop waiting for watch" else "Ready for watch start") }
            var autoOpen by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
            val launchDisplaySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                autoOpen = Settings.canDrawOverlays(context)
            }
            TextButton(onClick = {
                launchDisplaySettings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            }) { Text(if (autoOpen) "Auto-open live display: On" else "Enable auto-open live display") }
            Text("Allow Display over other apps in Android settings to open the live workout from your Pebble.",
                style = MaterialTheme.typography.bodySmall)
            Text(if (armed) "Open Pebble Health Workout on your Pebble and pick a sport."
                else "Prepare for a watch start before locking your phone. Waits up to one hour.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary) } }
        item {
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = !scanning && !starting, onClick = {
                message = null
                val required = buildList {
                    if (gps && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    if (address != null && Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (required.isEmpty()) start() else startPermission.launch(required.toTypedArray())
            }) { Text(if (starting) "Starting…" else "Start ${sport.label.lowercase()}") }
        }
    }
}

private fun livePaceLabel(value: Long, cycling: Boolean): String =
    if (cycling) speed(value / 1000.0) else duration(value) + " /km"

@Composable
private fun Recording(state: LiveWorkout) {
    val context = LocalContext.current
    var finish by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val sport = state.workout.sport
    val strength = sport == Sport.strength
    val routeMode = state.gpsStatus.isNotEmpty() && !strength
    val command = { action: String -> WorkoutRecorder.command(context, action) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text(sport.label + if (state.paused) " · Paused" else " · Active time", color = MaterialTheme.colorScheme.primary)
                Text(duration(state.active), fontSize = 54.sp, fontWeight = FontWeight.SemiBold, lineHeight = 60.sp)
            }
            item {
                HorizontalDivider()
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                        if (routeMode) Box(Modifier.weight(1f)) { Readout("Distance", distance(state.distance)) }
                        if (strength) Box(Modifier.weight(1f)) { Readout("Sets logged", state.sets.toString()) }
                        Box(Modifier.weight(1f)) { Readout(if (state.heartFromWatch) "Watch heart rate" else "Heart rate", state.bpm?.let { "$it bpm" } ?: "—") }
                    }
                    state.heartZone?.let { zone ->
                        Readout("Heart-rate zone", if (zone == 0) "Below zone 1" else "Zone $zone")
                    }
                    if (routeMode) Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                        val cycling = state.workout.sport == Sport.cycle
                        Box(Modifier.weight(1f)) {
                            Readout(if (cycling) "Live speed" else "Live pace", state.livePace?.let { livePaceLabel(it, cycling) } ?: "—")
                        }
                        Box(Modifier.weight(1f)) {
                            val avg = if (state.distance >= 10 && state.active > 0) (state.active * 1000 / state.distance).toLong() else null
                            Readout(if (cycling) "Avg speed" else "Avg pace", avg?.let { livePaceLabel(it, cycling) } ?: "—")
                        }
                    }
                    if (!strength) Text("${state.laps} laps", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (strength) {
                item { StrengthRest(state) }
                item { StrengthRecording(state) }
            }
            if (state.gpsStatus.isNotEmpty() || state.strapStatus.isNotEmpty() || state.watchStatus.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.gpsStatus.isNotEmpty()) Text(state.gpsStatus)
                        if (state.strapStatus.isNotEmpty()) Text(state.strapStatus)
                        state.strapBattery?.let { Text("Chest strap battery: $it%") }
                        if (state.watchStatus.isNotEmpty()) Text(state.watchStatus)
                        if (state.mirrorWatch && state.watchStatus != "Live stats on Pebble") {
                            TextButton(onClick = { command(WorkoutRecorder.OPEN_WATCH) }) { Text("Open Pebble Health Workout") }
                        }
                    }
                }
            }
            if (routeMode && state.route.isNotEmpty()) item { WorkoutMap(state.route, follow = true) }
            else if (routeMode) item { Text("Map appears after the first usable GPS fix.") }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = { command(if (state.paused) WorkoutRecorder.RESUME else WorkoutRecorder.PAUSE) }) {
                    Text(if (state.paused) "Resume" else "Pause")
                }
                if (!strength) OutlinedButton(modifier = Modifier.weight(1f), enabled = !state.paused,
                    onClick = { command(WorkoutRecorder.LAP) }) { Text("Lap") }
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { finish = true }) { Text("Finish workout") }
        }
    }
    if (finish) AlertDialog(
        onDismissRequest = { finish = false }, title = { Text("Finish workout?") },
        text = {
            Column {
                Text("Save this recording to your workout history.")
                TextButton(onClick = { finish = false; discard = true }) { Text("Discard workout…") }
            }
        },
        confirmButton = { TextButton(onClick = { finish = false; command(WorkoutRecorder.FINISH) }) { Text("Finish and save") } },
        dismissButton = { TextButton(onClick = { finish = false }) { Text("Keep recording") } },
    )
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("Discard workout?") },
        text = { Text("This recording and its data will be deleted.") },
        confirmButton = { TextButton(onClick = { discard = false; command(WorkoutRecorder.DISCARD) }) {
            Text("Discard workout", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep recording") } },
    )
}

@Composable
private fun History(store: WorkoutStore, refresh: Int, open: (String) -> Unit) {
    val workouts by produceState<Result<List<WorkoutStore.HistoryEntry>>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { store.history() } }
    }
    val entries = workouts?.getOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        when {
            workouts?.isFailure == true -> item { Text("Could not load workout history.") }
            entries == null -> item { CircularProgressIndicator() }
            entries.isEmpty() -> item { Text("Your saved workouts will appear here.") }
            else -> items(entries, key = { it.workout.id }) { row ->
                val entry = row.workout
                Row(Modifier.fillMaxWidth().clickable { open(entry.id) }.padding(vertical = 14.dp),
                    Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title ?: entry.sport.label, style = MaterialTheme.typography.titleMedium)
                        Text(workoutDate(entry.start) + if (entry.interrupted) " · Interrupted" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(duration(entry.active), color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Review(
    repo: Repository, store: WorkoutStore, id: String, snackbar: SnackbarHostState,
    trainerReview: Boolean, showTrainerReview: (Boolean) -> Unit, back: () -> Unit,
) {
    val dashboard = repo.state.collectAsState().value.dashboard
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val work by remember(context, id) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WorkoutUploadWorker.workName(id))
    }.collectAsState(initial = emptyList())
    val busy = work.any { !it.state.isFinished }
    val uploadFailed = work.any { it.state == WorkInfo.State.FAILED }
    var delete by remember { mutableStateOf(false) }
    var effort by remember { mutableStateOf(false) }
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var savingEdit by remember { mutableStateOf(false) }
    var section by rememberSaveable(id) { mutableStateOf("Overview") }
    val result by produceState<Result<WorkoutDetail?>?>(null, id, version) {
        value = withContext(Dispatchers.IO) { runCatching { store.detail(id) } }
    }
    val detail = result?.getOrNull()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) {
            scope.launch {
                val resultMessage = try {
                    withContext(Dispatchers.IO) {
                        val recording = store.detail(id)?.takeIf { it.route.isNotEmpty() } ?: error("Route unavailable")
                        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Document unavailable")
                        stream.bufferedWriter(Charsets.UTF_8).use(recording::writeGpx)
                    }
                    "GPX saved"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    "Could not save GPX. Choose another file location."
                }
                snackbar.showSnackbar(resultMessage)
            }
        }
    }

    val exportStrap = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val recorded = store.detail(id) ?: error("Recording unavailable")
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Document unavailable")
                    stream.bufferedWriter(Charsets.UTF_8).use { recorded.writeStrapCsv(it) }
                }
                "Chest strap data saved"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Could not save chest strap data."
            }
            snackbar.showSnackbar(message)
        }
    }

    if (detail == null) {
        if (result == null) CircularProgressIndicator(Modifier.padding(20.dp))
        else Text("Could not open this workout.", Modifier.padding(20.dp))
        return
    }
    val workout = detail.workout
    if (trainerReview) {
        ActivityTrainerReview(repo, workout.title ?: workout.sport.label, detail = detail, back = { showTrainerReview(false) })
        return
    }
    LaunchedEffect(work) {
        if (!workout.uploaded && work.any { it.state == WorkInfo.State.SUCCEEDED }) version++
    }
    val uploaded = workout.uploaded || dashboard?.workouts?.any { it.id == "phone:${workout.id}" } == true
    val calories = dashboard?.let { data ->
        val workoutDay = Instant.ofEpochMilli(workout.start).atZone(Fmt.zone(data.tz)).toLocalDate().toString()
        val weight = data.weight.filter { it.date <= workoutDay }.maxByOrNull { it.date }
        workout.estimatedCalories(weight?.kg)
    }
    val series = remember(detail) { workoutSeries(detail) }
    val tabs = buildList {
        add("Overview")
        if (series.heart.isNotEmpty() || series.pace.isNotEmpty() || series.altitude.isNotEmpty()) add("Charts")
        if (series.splits.isNotEmpty() || detail.laps.isNotEmpty()) add("Splits")
        if (detail.sets.isNotEmpty()) add("Sets")
    }
    val selected = section.takeIf { it in tabs } ?: "Overview"
    Column(Modifier.fillMaxSize()) {
        if (tabs.size > 1) PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected)) {
            tabs.forEach { tab -> Tab(selected == tab, onClick = { section = tab }, text = { Text(tab) }) }
        }
        key(selected) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (selected == "Overview") item {
                    Text("${workoutDate(workout.start)} · ${if (workout.manual) "${workout.sport.label} · Manual entry" else workout.sport.label}",
                        color = MaterialTheme.colorScheme.primary)
                    Text(workout.title ?: workout.sport.label, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(if (uploaded) "Saved on this phone · Synced to dashboard" else "Saved on this phone",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(enabled = !busy, onClick = { editing = true }) { Text("Edit activity") }
                    if (workout.interrupted) Text("Interrupted recording", color = MaterialTheme.colorScheme.secondary)
                }
                if (selected == "Overview") item {
                    if (detail.route.isNotEmpty()) WorkoutMap(detail.route)
                    else Text("This activity has no GPS samples. Turn on Phone GPS before starting a recording to get a map.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (selected == "Overview") item {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Readout("Active time", duration(workout.active))
                        if (!workout.manual) Readout("Elapsed", duration(workout.end - workout.start))
                    }
                    if (calories != null) {
                        Column(Modifier.padding(top = 12.dp)) {
                            Readout("Est. calories", "$calories kcal")
                            Note("Based on active time and your logged weight.")
                        }
                    }
                    if (detail.route.isNotEmpty() || workout.manualDistance != null) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.SpaceBetween) {
                            Readout("Distance", distance(detail.distance))
                            if (detail.distance > 0 && workout.active > 0) {
                                if (workout.sport == Sport.cycle) {
                                    Readout("Avg speed", speed(workout.active / detail.distance))
                                } else Readout("Avg pace", pace(workout.active, detail.distance))
                            }
                            series.elevationGain?.let { Readout("Ascent", "${it.toInt()} m") }
                        }
                    }
                }
                if (selected == "Overview" && series.heart.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Readout("Avg heart rate", "${series.averageBpm} bpm")
                        Readout("Peak heart rate", "${series.heart.maxOf { it.value }.toInt()} bpm")
                    }
                }
                if (selected == "Charts" && series.heart.isNotEmpty()) item {
                    WorkoutChart("Heart rate", series.heart, workout.active, LocalMetrics.current.heart, gapMs = 5000) { "${it.toInt()} bpm" }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Readout("Average", "${series.averageBpm} bpm")
                        Readout("Peak", "${series.heart.maxOf { it.value }.toInt()} bpm")
                    }
                }
                if (selected == "Charts" && detail.heart.any { !it.fromWatch }) item {
                    val beats = remember(detail.heart) { detail.heart.sumOf { it.rrTicks.size } }
                    if (beats > 0) Text("$beats beat-to-beat intervals recorded")
                    OutlinedButton(onClick = { exportStrap.launch("chest-strap.csv") }) { Text("Export chest strap CSV") }
                }
                if (selected == "Charts" && series.heart.isNotEmpty()) item { HeartZones(detail.heart) }
                if (selected == "Charts" && series.pace.isNotEmpty()) item {
                    if (workout.sport == Sport.cycle) {
                        val speeds = remember(series.pace) { series.pace.map { it.copy(value = 3_600_000 / it.value) } }
                        WorkoutChart("Speed", speeds, workout.active, MaterialTheme.colorScheme.primary) {
                            speed(3600 / it)
                        }
                    } else {
                        WorkoutChart("Pace", series.pace, workout.active, MaterialTheme.colorScheme.primary, reverse = true) {
                            duration(it.toLong()) + " /km"
                        }
                    }
                }
                if (selected == "Charts" && series.altitude.isNotEmpty()) item {
                    WorkoutChart("Elevation", series.altitude, workout.active, MaterialTheme.colorScheme.tertiary) { "${it.toInt()} m" }
                }
                if (selected == "Splits" && series.splits.isNotEmpty()) item {
                    Text("Kilometre splits", style = MaterialTheme.typography.titleLarge)
                    series.splits.forEachIndexed { index, split ->
                        BreakdownRow("${index + 1} km", duration(split))
                    }
                }
                if (selected == "Splits" && detail.laps.isNotEmpty()) item {
                    Text("Laps", style = MaterialTheme.typography.titleLarge)
                    (detail.laps + Lap(workout.active, detail.distance)).mapIndexedNotNull { index, lap ->
                        val previous = detail.laps.getOrNull(index - 1) ?: Lap(0, 0.0)
                        val time = lap.active - previous.active
                        if (time <= 0) null else index + 1 to (duration(time) + "  ·  " + distance(lap.meters - previous.meters))
                    }.forEach { (number, summary) -> BreakdownRow("Lap $number", summary) }
                }
                if (selected == "Sets") item {
                    Text("Strength sets", style = MaterialTheme.typography.titleLarge)
                    detail.sets.forEachIndexed { index, set ->
                        BreakdownRow("${index + 1}. ${set.exercise}", "${set.reps} reps" + (set.kg?.let { "  ·  $it kg" } ?: ""))
                    }
                }
                if (selected == "Overview") item { TrainerReviewEntry { showTrainerReview(true) } }
                if (selected == "Overview" && workout.notes != null) item {
                    Text("Notes", style = MaterialTheme.typography.titleMedium)
                    Text(workout.notes)
                }
                if (selected == "Overview") item {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("Perceived effort")
                        TextButton(enabled = !busy, onClick = { effort = true }) {
                            Text(workout.rpe?.let { "$it / 10" } ?: "Add effort")
                        }
                    }
                    if (!uploaded) {
                        Text(if (busy) "Saved on this phone. Dashboard sync queued."
                            else if (uploadFailed) "Saved on this phone. Check Dashboard sign-in in Settings to sync."
                            else "Saved on this phone. Dashboard sync runs in the background.")
                    }
                    TextButton(enabled = !busy && workout.active >= 1000, onClick = {
                        WorkoutUploadWorker.enqueue(context, id)
                        scope.launch { snackbar.showSnackbar("Workout queued for dashboard sync.") }
                    }) { Text("Sync now") }
                    if (detail.route.isNotEmpty()) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            export.launch("${workout.sport.name}-${Instant.ofEpochMilli(workout.start).atZone(ZoneId.systemDefault()).toLocalDate()}.gpx")
                        }) { Text("Save GPX") }
                    }
                    dashboard?.workouts?.firstOrNull { it.id == "phone:$id" }?.let { DashboardActivityRemoval(repo, it) }
                    TextButton(enabled = !busy, onClick = { delete = true }) { Text("Delete from phone") }
                }
            }
        }
    }
    if (editing) WorkoutLogEditor(workout, savingEdit, lockMetrics = uploaded, close = { editing = false }) { updated ->
        savingEdit = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { store.saveLog(updated, new = false, lockMetrics = uploaded) } }
            savingEdit = false
            if (saved.isSuccess) {
                version++
                editing = false
            } else snackbar.showSnackbar("Could not update activity.")
        }
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false }, title = { Text("Delete workout from phone?") },
        text = { Text("The local recording, route and sensor samples will be removed.") },
        confirmButton = {
            TextButton(onClick = {
                delete = false
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { runCatching { store.delete(id) }.isSuccess }
                    if (saved) back() else snackbar.showSnackbar("Could not delete workout.")
                }
            }) { Text("Delete workout") }
        },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } },
    )
    if (effort) {
        var value by remember { mutableFloatStateOf(workout.rpe?.toFloat() ?: 5f) }
        fun save(rpe: Int?) {
            effort = false
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { store.effort(id, rpe) }.isSuccess }
                if (saved) version++ else snackbar.showSnackbar("Could not save effort.")
            }
        }
        AlertDialog(
            onDismissRequest = { effort = false }, title = { Text("Perceived effort") },
            text = {
                Column {
                    Text("${value.toInt()} / 10")
                    Slider(value, { value = it }, valueRange = 1f..10f, steps = 8)
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Easy"); Text("Maximum") }
                }
            },
            confirmButton = { TextButton(onClick = { save(value.toInt()) }) { Text("Save effort") } },
            dismissButton = { TextButton(onClick = { save(null) }) { Text("Clear effort") } },
        )
    }
}

@Composable
private fun HeartZones(heart: List<Pulse>) {
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("workout_setup", 0) }
    var maxBpm by remember { mutableIntStateOf(settings.getInt("max_bpm", 0)) }
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text("Heart-rate zones", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { editing = true }) { Text(if (maxBpm == 0) "Set max HR" else "$maxBpm bpm max") }
    }
    if (maxBpm in 100..240) {
        val seconds = remember(heart, maxBpm) { zoneSeconds(heart, maxBpm) }
        val total = seconds.sum()
        val labels = listOf("Below 50%", "50–59%", "60–69%", "70–79%", "80–89%", "90% and above")
        for ((index, label) in labels.withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                Text(label)
                Text(duration(seconds[index] * 1000L))
            }
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else seconds[index].toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
    if (editing) MaxHeartRateDialog(maxBpm, { editing = false }) {
        settings.edit().putInt("max_bpm", it).apply()
        maxBpm = it
        editing = false
    }
}

@Composable
private fun MaxHeartRateDialog(maxBpm: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(maxBpm.takeIf { it > 0 }?.toString().orEmpty()) }
    val value = input.toIntOrNull()?.takeIf { it in 100..240 }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Maximum heart rate") },
        text = {
            OutlinedTextField(input, { input = it.take(3) }, label = { Text("Maximum BPM") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { value?.let(save) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BreakdownRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value)
    }
    HorizontalDivider()
}

@Composable
private fun Readout(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

private val shortDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm")
private fun workoutDate(epoch: Long) = shortDate.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))
