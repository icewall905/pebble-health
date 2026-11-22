package org.tdgs.ph.workout

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import org.tdgs.ph.PhApp
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.ApiException
import org.tdgs.ph.data.HubConfig
import java.io.IOException
import java.security.MessageDigest

internal class WorkoutUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PhApp
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val config = app.repository.settings.currentWorkoutUpload() ?: return@withContext Result.success()
        if (binding(config) != inputData.getString("binding")) return@withContext Result.success()
        val store = WorkoutStore.get(app)
        val detail = store.detail(id) ?: return@withContext Result.success()
        if (detail.workout.active < 1000) return@withContext Result.success()
        try {
            Api().uploadWorkout(config, detail.pebbleFitPayload())
            store.markUploaded(id)
            app.repository.refresh()
            Result.success()
        } catch (error: ApiException) {
            if (error.status == 429 || (error.status ?: 0) >= 500) Result.retry() else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        fun workName(id: String) = "workout-upload:$id"

        private val enqueueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun enqueue(context: Context, id: String) {
            val app = context.applicationContext as PhApp
            enqueueScope.launch {
                val config = app.repository.settings.currentWorkoutUpload() ?: return@launch
                val request = OneTimeWorkRequestBuilder<WorkoutUploadWorker>()
                    .setInputData(workDataOf("id" to id, "binding" to binding(config)))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
            }
        }

        // A queued workout cannot migrate to a different hub or account after settings change.
        private fun binding(config: HubConfig): String = MessageDigest.getInstance("SHA-256")
            .digest("${config.baseUrl}\n${config.token}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
