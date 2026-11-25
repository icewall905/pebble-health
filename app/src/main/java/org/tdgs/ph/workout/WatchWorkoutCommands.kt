package org.tdgs.ph.workout

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import org.tdgs.ph.R
import java.util.UUID

internal val watchSports = listOf(Sport.run, Sport.walk, Sport.hike, Sport.cycle, Sport.strength,
    Sport.tennis, Sport.rowing, Sport.yoga, Sport.beat_saber, Sport.workout)

class WatchWorkoutCommands : BasePebbleListenerService() {
    private val sender = lazy { DefaultPebbleSender(applicationContext) }
    private var lastAccepted: Pair<WatchIdentifier, UInt>? = null

    override fun onCreate() {
        super.onCreate()
        DefaultPebbleAndroidAppPicker.getInstance(applicationContext).enableAutoSelect = false
    }

    override fun onDestroy() {
        coroutineScope.cancel()
        if (sender.isInitialized()) sender.value.close()
        super.onDestroy()
    }

    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID != WORKOUT_WATCH_UUID) return ReceiveResult.Nack
        val request = watch to (data.number(23u) ?: return ReceiveResult.Nack)
        if (request == lastAccepted) return ReceiveResult.Ack
        val command = data.number(20u)
        val argument = data.number(21u)
        val state = WorkoutRecorder.live.value
        if (command == 9u || command == 10u) {
            // Return the watch's transport ACK before sending a separate reply.
            coroutineScope.launch {
                delay(150)
                try {
                    val response = if (command == 9u) watchSensorStatus(this@WatchWorkoutCommands, WorkoutRecorder.live.value)
                        else watchHistory(this@WatchWorkoutCommands)
                    withTimeoutOrNull(5000) { sender.value.sendDataToPebble(WORKOUT_WATCH_UUID, response, listOf(watch)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The watch keeps any cached results and lets the user try again.
                }
            }
            lastAccepted = request
            return ReceiveResult.Ack
        }
        if (command == 12u) {
            val key = when (argument) {
                1u -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                2u -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                3u -> KeyEvent.KEYCODE_MEDIA_NEXT
                else -> return ReceiveResult.Nack
            }
            val audio = getSystemService(AudioManager::class.java)
            if (runCatching {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }.isFailure) return ReceiveResult.Nack
            lastAccepted = request
            return ReceiveResult.Ack
        }
        if (command == 1u) {
            if (state != null) return ReceiveResult.Nack
            val sport = argument?.toInt()?.let(watchSports::getOrNull) ?: return ReceiveResult.Nack
            // Start the recording itself first and only then think about the display: the
            // phone is often locked in a pocket, and a locked screen must not stop tracking.
            // REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND keeps the start
            // allowed while this app is in the background.
            if (gpsGranted(this, sport) && runCatching {
                if (WorkoutRecorder.armed.value) startService(startIntent(this, sport))
                else startForegroundService(startIntent(this, sport))
            }.getOrNull() != null) {
                lastAccepted = request
                return ReceiveResult.Ack
            }
            requestOnPhone(this, sport)
            return ReceiveResult.Nack
        }
        if (state == null || !state.mirrorWatch || data.number(22u)?.and(WATCH_SESSION_TOKEN_MASK) != state.watchToken) return ReceiveResult.Nack
        val action = when (command) {
            2u -> WorkoutRecorder.PAUSE
            3u -> WorkoutRecorder.RESUME
            4u -> WorkoutRecorder.LAP.takeIf { !state.paused && state.workout.sport != Sport.strength }
            5u -> WorkoutRecorder.REST.takeIf { !state.paused && state.workout.sport == Sport.strength }
            6u -> WorkoutRecorder.FINISH
            7u -> WorkoutRecorder.DISCARD
            11u -> WorkoutRecorder.WATCH_HEART.takeIf { !state.paused && argument != null && argument in 25u..250u }
            8u -> WorkoutRecorder.REPEAT_SET.takeIf { !state.paused && state.lastSet != null }
            else -> null
        } ?: return ReceiveResult.Nack
        return if (runCatching {
            WorkoutRecorder.command(this, action) {
                putExtra("watch_token", state.watchToken.toInt())
                if (action == WorkoutRecorder.WATCH_HEART) putExtra("bpm", argument?.toInt() ?: 0)
                if (action == WorkoutRecorder.REST) {
                    val rest = getSharedPreferences("strength_recording", 0).getInt("rest_seconds", 60)
                    putExtra("seconds", rest.takeIf { it in 30..90 } ?: 60)
                }
            }
        }.isSuccess) {
            lastAccepted = request
            ReceiveResult.Ack
        } else ReceiveResult.Nack
    }

    companion object {
        private const val CHANNEL = "watch_workout_start"
        private const val NOTIFICATION = 0x3714
        private const val DISMISS_ACTION = "org.tdgs.ph.workout.PROMPT_DISMISSED"

        private fun PebbleDictionary.number(key: UInt): UInt? = when (val item = this[key]) {
            is PebbleDictionaryItem.UInt32 -> item.value
            is PebbleDictionaryItem.Int32 -> item.value.toUInt()
            else -> null
        }

        internal fun gpsGranted(context: Context, sport: Sport) =
            !sport.gpsDefault || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        internal fun startIntent(context: Context, sport: Sport) =
            Intent(context, WorkoutRecorder::class.java).setAction(WorkoutRecorder.START).apply {
                putExtra("sport", sport.name)
                putExtra("gps", sport.gpsDefault)
                putExtra("mirror_watch", true)
                putExtra("from_watch", true)
                putExtra("strap", context.getSharedPreferences("workout_setup", 0).getString("strap_address", null))
            }

        internal fun phoneIntent(context: Context, sport: Sport? = null) =
            Intent(context, WorkoutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("show_watch_workout", true)
                .apply { sport?.let { putExtra("watch_start_sport", it.name) } }

        internal fun clearRequest(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)

        private fun dismissIntent(context: Context) =
            Intent(context, WorkoutPromptDismissed::class.java).setAction(DISMISS_ACTION)

        // Uploaded to the watch as a notification, so it reads as a prompt there: the
        // CATEGORY_WORKOUT icon, a sport in the title and a Start button.
        internal fun requestOnPhone(context: Context, sport: Sport) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Workout prompts", NotificationManager.IMPORTANCE_HIGH))
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(context, NOTIFICATION, phoneIntent(context, sport), flags)
            val dismiss = PendingIntent.getBroadcast(context, NOTIFICATION, dismissIntent(context), flags)
            val detected = when (sport) {
                Sport.walk -> "Walk detected"
                Sport.run -> "Run detected"
                else -> "${sport.label} detected"
            }
            manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setCategory(Notification.CATEGORY_WORKOUT)
                // The phone may be locked when the walk is detected.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentTitle(detected)
                .setContentText("Tap to record on your phone")
                .setStyle(Notification.BigTextStyle().bigText(
                    "Start recording on your phone to add GPS and your chest strap. " +
                        "Press Start on your watch, or open this prompt."))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Start", open).build())
                .addAction(Notification.Action.Builder(null, "Not now", dismiss).build())
                .setDeleteIntent(dismiss)
                .setTimeoutAfter(120_000L)
                .setAutoCancel(true)
                .build())
        }
    }
}
