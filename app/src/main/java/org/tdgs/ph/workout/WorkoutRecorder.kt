package org.tdgs.ph.workout

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import java.util.UUID

internal data class LiveWorkout(
    val workout: Workout,
    val active: Long = 0,
    val paused: Boolean = false,
    val route: List<Fix> = emptyList(),
    val distance: Double = 0.0,
    val gpsStatus: String = "",
    val strapStatus: String = "",
    val bpm: Int? = null,
    val laps: Int = 0,
    val sets: Int = 0,
    val exercise: String = "",
    val lastSet: StrengthSet? = null,
    val restSeconds: Int = 0,
    val restComplete: Boolean = false,
    val livePace: Long? = null,
    val mirrorWatch: Boolean = false,
    val watchStatus: String = "",
    val maxBpm: Int = 0,
    val strapBattery: Int? = null,
) {
    val watchToken: UInt get() = workout.id.hashCode().toUInt()
    val heartZone: Int? get() = bpm?.let { heartZone(it.toDouble(), maxBpm) }
}

internal class WorkoutRecorder : Service(), LocationListener {
    private var watch: WorkoutWatch? = null
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private val paceFixes = ArrayDeque<Fix>()
    private val store by lazy { WorkoutStore.get(this) }
    private val location by lazy { getSystemService(LocationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var runStart: Long? = null
    private var accumulated = 0L
    private var sessionStart = 0L
    private var sessionWall = 0L
    private var lastPulseTime = 0L
    private var lastBpm: Int? = null
    private var lastFix: Fix? = null
    private var lastGpsTime = 0L
    private var segment = 0
    private var wantsGps = false
    private var strap: ChestStrap? = null
    private var seconds = 0
    private var previewStride = 1
    private var acceptedFixes = 0
    private val expireWatchStandby = Runnable { if (armed.value && live.value == null) shutdown() }
    private var restDeadline = 0L
    private val restWakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:strength-rest"
        ).apply { setReferenceCounted(false) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val state = live.value ?: return
            val restSeconds = ((restDeadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
            val completedRest = restDeadline != 0L && restSeconds == 0
            if (completedRest) {
                clearRestDeadline()
                notifyRestComplete()
            }
            val active = activeMs()
            if (seconds++ % 5 == 0) {
                if (!persist { store.checkpoint(state.workout.id, wallTime(), active) }) return
                if (Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state.workout.sport))
                }
            }
            val current = state.copy(
                active = active,
                restSeconds = restSeconds,
                restComplete = state.restComplete || completedRest,
                livePace = if (state.paused) null else recentPace(paceFixes.toList(), active),
                bpm = lastBpm.takeIf { runStart != null && SystemClock.elapsedRealtime() - lastPulseTime <= 5000 },
                gpsStatus = if (wantsGps && runStart != null && state.gpsStatus == "GPS recording" &&
                    SystemClock.elapsedRealtime() - lastGpsTime > 15_000) "Waiting for GPS…" else state.gpsStatus,
            )
            live.value = current
            if (current.mirrorWatch && seconds % 2 == 1 && watchJob?.isActive != true) {
                val connection = watch
                if (connection != null) watchJob = watchScope.launch {
                    val status = try { connection.update(current) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { "Watch connection failed. Check Settings → Workouts." }
                    live.value?.takeIf { it.workout.id == current.workout.id }?.let {
                        live.value = it.copy(watchStatus = status)
                    }
                }
            }
            if (active >= MAX_ACTIVE_MS || SystemClock.elapsedRealtime() - sessionStart >= MAX_ACTIVE_MS) {
                finish()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra("watch_token") == true &&
            live.value?.watchToken != intent.getIntExtra("watch_token", 0).toUInt()) return START_NOT_STICKY
        when (intent?.action) {
            ARM -> if (live.value == null && !armed.value) armForWatch()
            DISARM -> if (live.value == null) shutdown()
            START -> if (live.value == null) {
                val sport = intent.getStringExtra("sport")?.let { Sport.valueOf(it) }
                if (sport == null) shutdown() else start(
                    sport, intent.getBooleanExtra("gps", false), intent.getStringExtra("strap"),
                    intent.getBooleanExtra("mirror_watch", false), intent.getBooleanExtra("from_watch", false)
                )
            }
            OPEN_WATCH -> launchWatch()
            PAUSE -> pause()
            RESUME -> resume()
            LAP -> live.value?.takeIf { !it.paused }?.let { state ->
                if (persist { store.lap(state.workout.id, Lap(activeMs(), state.distance)) }) {
                    live.value = state.copy(laps = state.laps + 1)
                }
            }
            SELECT_EXERCISE -> live.value?.takeIf { it.workout.sport == Sport.strength }?.let { state ->
                val exercise = intent.getStringExtra("exercise").orEmpty().trim()
                if (exercise.length in 1..100) live.value = state.copy(exercise = exercise)
            }
            REST -> live.value?.takeIf { it.workout.sport == Sport.strength }?.let { state ->
                val seconds = intent.getIntExtra("seconds", 0)
                if (seconds == 0 || seconds in 1..600 && !state.paused) {
                    clearRestDeadline()
                    if (seconds > 0) {
                        restDeadline = SystemClock.elapsedRealtime() + seconds * 1000L
                        restWakeLock.acquire(seconds * 1000L + 3000L)
                    }
                    getSystemService(NotificationManager::class.java).cancel(REST_NOTIFICATION)
                    live.value = state.copy(restSeconds = seconds, restComplete = false)
                }
            }
            SET -> {
                val state = live.value
                val exercise = intent.getStringExtra("exercise").orEmpty().trim()
                val reps = intent.getIntExtra("reps", 0)
                val count = intent.getIntExtra("count", 1)
                val kg = intent.getDoubleExtra("kg", Double.NaN).takeIf { it.isFinite() && it in 0.0..10000.0 }
                val set = StrengthSet(exercise, reps, kg)
                if (state?.workout?.sport == Sport.strength && !state.paused && exercise.length in 1..100 &&
                    reps in 1..999 && count in 1..10 && persist { store.sets(state.workout.id, set, count) }
                ) {
                    live.value = state.copy(sets = state.sets + count, exercise = exercise, lastSet = set)
                }
            }
            REPEAT_SET -> live.value?.takeIf { !it.paused }?.let { state ->
                state.lastSet?.let { set ->
                    if (persist { store.sets(state.workout.id, set, 1) }) {
                        live.value = state.copy(sets = state.sets + 1, exercise = set.exercise)
                    }
                }
            }
            FINISH -> finish()
            DISCARD -> finish(discard = true)
            else -> if (live.value == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun armForWatch() {
        val gps = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bluetooth = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        if (!promote(null, gps, bluetooth)) {
            error.value = "Could not prepare for a watch start. Open Record workout and try again."
            shutdown()
            return
        }
        armed.value = true
        handler.postDelayed(expireWatchStandby, 60 * 60 * 1000L)
    }

    private fun promote(sport: Sport?, gps: Boolean, bluetooth: Boolean): Boolean {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Workout recording", NotificationManager.IMPORTANCE_LOW)
        )
        return try {
            val types = (if (gps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
                (if (bluetooth) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            val notification = notification(sport)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification, if (types == 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else types)
            } else {
                startForeground(NOTIFICATION, notification, types)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun start(sport: Sport, gps: Boolean, address: String?, mirrorWatch: Boolean, fromWatch: Boolean) {
        wantsGps = gps && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasStrap = address != null && (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT))
        if ((fromWatch && gps && !wantsGps) || (!armed.value && !promote(sport, wantsGps, hasStrap))) {
            error.value = "Android could not start recording. Check workout permissions and try again."
            if (fromWatch) WatchWorkoutCommands.requestOnPhone(this, sport)
            shutdown()
            return
        }
        armed.value = false
        WatchWorkoutCommands.clearRequest(this)
        handler.removeCallbacks(expireWatchStandby)

        val now = System.currentTimeMillis()
        val workout = Workout(UUID.randomUUID().toString(), sport, now, now, 0)
        if (!persist { store.start(workout) }) return
        error.value = null
        finished.value = null
        live.value = LiveWorkout(
            workout,
            mirrorWatch = mirrorWatch,
            watchStatus = if (mirrorWatch) "Opening Workout Live on Pebble…" else "",
            maxBpm = getSharedPreferences("workout_setup", 0).getInt("max_bpm", 0),
            gpsStatus = if (gps && !wantsGps) "Precise location permission unavailable" else "",
        )
        sessionWall = now
        sessionStart = SystemClock.elapsedRealtime()
        runStart = sessionStart
        if (mirrorWatch) {
            watch = WorkoutWatch(this)
            if (!fromWatch) launchWatch()
        }
        requestGps()
        if (hasStrap) {
            strap = ChestStrap(this, address, { status ->
                lastBpm = null
                live.value?.let { live.value = it.copy(strapStatus = status, bpm = null, strapBattery = null) }
            }, ::recordPulse, battery = { level ->
                live.value?.let { live.value = it.copy(strapBattery = level) }
            }).also { it.connect() }
        }
        handler.post(ticker)
    }

    private fun launchWatch() {
        val connection = watch ?: return
        live.value?.let { live.value = it.copy(watchStatus = "Opening Workout Live on Pebble…") }
        val previous = watchJob
        watchJob = watchScope.launch {
            try {
                previous?.cancelAndJoin()
                connection.launch()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next live update reports the connection status.
            }
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun wallTime(now: Long = SystemClock.elapsedRealtime()) = sessionWall + (now - sessionStart)

    private fun activeMs(now: Long = SystemClock.elapsedRealtime()) =
        accumulated + (runStart?.let { (now - it).coerceAtLeast(0) } ?: 0)

    @SuppressLint("MissingPermission")
    private fun requestGps() {
        if (!wantsGps) return
        val status = try {
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, this, Looper.getMainLooper())
            if (location.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Waiting for GPS…" else "Turn on phone location"
        } catch (_: RuntimeException) {
            "Phone GPS unavailable"
        }
        live.value?.let { live.value = it.copy(gpsStatus = status) }
    }

    override fun onProviderDisabled(provider: String) {
        live.value?.let { live.value = it.copy(gpsStatus = "Turn on phone location") }
    }

    override fun onProviderEnabled(provider: String) {
        live.value?.let { live.value = it.copy(gpsStatus = "Waiting for GPS…") }
    }

    override fun onLocationChanged(fix: Location) {
        val state = live.value ?: return
        val stamp = fix.elapsedRealtimeNanos / 1_000_000
        if (stamp < (runStart ?: return) || SystemClock.elapsedRealtime() - stamp !in 0..10_000 ||
            !fix.hasAccuracy() || fix.accuracy !in 0f..25f) return
        lastGpsTime = stamp
        if (state.gpsStatus != "GPS recording") live.value = state.copy(gpsStatus = "GPS recording")
        val point = appendFix(lastFix, Fix(wallTime(stamp), activeMs(stamp), segment, fix.latitude, fix.longitude,
            fix.altitude.takeIf { fix.hasAltitude() && it.isFinite() }, fix.accuracy, 0.0)) ?: return
        if (!persist { store.fix(state.workout.id, point) }) return
        segment = point.segment
        lastFix = point
        paceFixes.addLast(point)
        while (point.active - paceFixes.first().active > 20_000) paceFixes.removeFirst()
        val current = live.value ?: return
        acceptedFixes++
        var preview = current.route
        if (preview.size >= 1200) {
            preview = preview.filterIndexed { index, _ -> index % 2 == 0 }
            previewStride = (previewStride * 2).coerceAtMost(1024)
        }
        if (acceptedFixes % previewStride == 0 || preview.lastOrNull()?.segment != point.segment) preview = preview + point
        live.value = current.copy(distance = point.meters, route = preview)
    }

    private fun recordPulse(measurement: HeartMeasurement) {
        val state = live.value ?: return
        if (state.paused) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPulseTime < 500 && measurement.rrTicks.isEmpty() && measurement.energyKj == null) return
        val bpm = measurement.bpm
        val sample = Pulse(activeMs(now), bpm, wallTime(now), segment, measurement.rrTicks, measurement.energyKj)
        if (!persist { store.pulse(state.workout.id, sample) }) return
        lastPulseTime = now
        lastBpm = bpm
        live.value = state.copy(bpm = bpm)
    }

    private fun pause() {
        val state = live.value?.takeIf { !it.paused } ?: return
        accumulated = activeMs()
        paceFixes.clear()
        runStart = null
        segment++
        lastBpm = null
        runCatching { location.removeUpdates(this) }
        if (persist { store.checkpoint(state.workout.id, wallTime(), accumulated) }) {
            live.value = state.copy(active = accumulated, paused = true, bpm = null, livePace = null, gpsStatus = if (wantsGps) "GPS paused" else "")
        }
    }

    private fun resume() {
        val state = live.value?.takeIf { it.paused } ?: return
        runStart = SystemClock.elapsedRealtime()
        live.value = state.copy(paused = false)
        requestGps()
    }

    private fun finish(discard: Boolean = false) {
        val state = live.value ?: return
        if (persist {
            if (discard) store.discardRecording(state.workout.id)
            else store.checkpoint(state.workout.id, wallTime(), activeMs(), finished = true)
        }) {
            val connection = watch
            watch = null
            val final = state.copy(active = activeMs(), livePace = null)
            val previous = watchJob
            watchJob = null
            if (connection != null) CoroutineScope(Dispatchers.Main.immediate).launch {
                try {
                    previous?.cancelAndJoin()
                    connection.update(final, finished = true, discarded = discard)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A failed watch message does not affect the local save. */ }
                finally { connection.close() }
            }
            if (discard) {
                error.value = "Workout discarded."
            } else {
                WorkoutUploadWorker.enqueue(this, state.workout.id)
                finished.value = state.workout.id
            }
            shutdown()
        }
    }

    private fun persist(operation: () -> Unit): Boolean = try {
        operation()
        true
    } catch (_: android.database.sqlite.SQLiteException) {
        error.value = "Recording stopped because the workout could not be written to storage."
        runCatching { store.recover() }
        shutdown()
        false
    }

    private fun shutdown() {
        watchJob?.cancel()
        watch?.close()
        watch = null
        handler.removeCallbacksAndMessages(null)
        clearRestDeadline()
        getSystemService(NotificationManager::class.java).cancel(REST_NOTIFICATION)
        runCatching { location.removeUpdates(this) }
        strap?.close()
        strap = null
        live.value = null
        armed.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        live.value?.let { runCatching { store.recover() } }
        shutdown()
        watchScope.cancel()
        super.onDestroy()
    }

    private fun clearRestDeadline() {
        if (restDeadline != 0L && restWakeLock.isHeld) restWakeLock.release()
        restDeadline = 0
    }

    private fun notifyRestComplete() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REST_CHANNEL, "Strength rest timer", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        val open = Intent(this, WorkoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        manager.notify(REST_NOTIFICATION, Notification.Builder(this, REST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Rest complete")
            .setContentText("Ready for your next set.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }

    private fun notification(sport: Sport?): Notification {
        val open = Intent(this, WorkoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(sport?.label ?: "Ready for watch start")
            .setContentText(when {
                sport == null -> "Open Workout Live on your Pebble to start."
                live.value?.paused == true -> "Workout paused"
                else -> "Workout recording"
            })
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val START = "start"
        const val ARM = "arm_watch"
        const val DISARM = "disarm_watch"
        const val DISCARD = "discard"
        const val REPEAT_SET = "repeat_set"
        const val OPEN_WATCH = "open_watch"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val LAP = "lap"
        const val SET = "set"
        const val REST = "rest"
        const val SELECT_EXERCISE = "select_exercise"
        const val FINISH = "finish"
        private const val CHANNEL = "gravel_workouts"
        private const val REST_CHANNEL = "strength_rest"
        private const val REST_NOTIFICATION = 0x3713
        private const val NOTIFICATION = 0x3712
        private const val MAX_ACTIVE_MS = 172_800_000L
        val armed = MutableStateFlow(false)
        val live = MutableStateFlow<LiveWorkout?>(null)
        val finished = MutableStateFlow<String?>(null)
        val error = MutableStateFlow<String?>(null)

        fun command(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, WorkoutRecorder::class.java).setAction(action).apply(configure)
            if (action == START || action == ARM) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
