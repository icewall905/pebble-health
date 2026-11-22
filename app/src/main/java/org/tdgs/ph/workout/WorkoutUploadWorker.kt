package org.tdgs.ph.workout

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import org.tdgs.ph.PhApp
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.ApiException
import org.tdgs.ph.data.HubConfig
import org.tdgs.ph.data.Settings
import org.tdgs.ph.planner.PlannerApi
import org.tdgs.ph.planner.PlannerLoginRequired
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest

internal class WorkoutUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PhApp
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val settings = app.repository.settings
        val config = settings.current() ?: return@withContext Result.failure()
        if (binding(config) != inputData.getString("binding")) return@withContext Result.success()
        val store = WorkoutStore.get(app)
        val detail = store.detail(id) ?: return@withContext Result.success()
        if (detail.workout.active < 1000) return@withContext Result.success()
        try {
            val token = resolveToken(settings, config)
            val payload = detail.pebbleFitPayload()
            try {
                Api().uploadWorkout(token, payload)
            } catch (error: ApiException) {
                if (error.status != 401 && error.status != 403) throw error
                Api().uploadWorkout(resolveToken(settings, config, rejected = token.token), payload)
            }
            store.markUploaded(id)
            app.repository.refresh()
            Result.success()
        } catch (_: PlannerLoginRequired) {
            Result.failure()
        } catch (error: ApiException) {
            if (error.status == 429 || (error.status ?: 0) >= 500) Result.retry() else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        fun workName(id: String) = "workout-upload:$id"

        private val tokenMutex = Mutex()

        suspend fun resolveToken(settings: Settings, hub: HubConfig, rejected: String? = null): HubConfig =
            tokenMutex.withLock {
                if (settings.current() != hub) throw PlannerLoginRequired()
                settings.currentWorkoutUpload()?.takeIf { it.token != rejected }?.let { return@withLock it }
                val base = Api.normalize(hub.baseUrl) ?: throw PlannerLoginRequired()
                val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
                if (settings.plannerCookie(origin) == null) throw PlannerLoginRequired()
                val token = PlannerApi(base, settings).workoutUploadToken()
                settings.saveWorkoutUpload(hub, token)
                hub.copy(token = token)
            }

        private val enqueueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun enqueue(context: Context, id: String) {
            val app = context.applicationContext as PhApp
            enqueueScope.launch {
                val config = app.repository.settings.current() ?: return@launch
                schedule(app, id, config)
            }
        }

        private fun schedule(context: Context, id: String, config: HubConfig) {
            val request = OneTimeWorkRequestBuilder<WorkoutUploadWorker>()
                .setInputData(workDataOf("id" to id, "binding" to binding(config)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueMissing(context: Context) {
            val app = context.applicationContext as PhApp
            enqueueScope.launch {
                val settings = app.repository.settings
                val hub = settings.current() ?: return@launch
                val base = Api.normalize(hub.baseUrl) ?: return@launch
                val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
                if (settings.currentWorkoutUpload() == null && settings.plannerCookie(origin) == null) return@launch
                WorkoutStore.get(app).history().filter { !it.workout.uploaded && it.workout.active >= 1000 }
                    .forEach { schedule(app, it.workout.id, hub) }
            }
        }

        // A queued workout cannot migrate to a different hub or account after settings change.
        private fun binding(config: HubConfig): String = MessageDigest.getInstance("SHA-256")
            .digest("${config.baseUrl}\n${config.token}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
