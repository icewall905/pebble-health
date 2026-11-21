package org.tdgs.ph.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.tdgs.ph.PhApp
import org.tdgs.ph.checkin.CheckInNotifier
import java.util.concurrent.TimeUnit

/** Refreshes the cached dashboard every 30 minutes on a network, then redraws widgets. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PhApp).repository
        val hub = repo.settings.current() ?: return Result.success()
        val result = repo.refresh()
        TodayWidget.refreshAll(applicationContext)
        result.getOrNull()?.let { CheckInNotifier.afterRefresh(applicationContext, repo, hub, it) }
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        suspend fun updateWidgets(context: Context) = TodayWidget.refreshAll(context)
    }
}
