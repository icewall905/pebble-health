package org.tdgs.ph

import android.app.Application
import androidx.work.WorkManager
import org.tdgs.ph.data.Repository
import org.tdgs.ph.data.Settings
import org.tdgs.ph.widget.RefreshWorker

class PhApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this, Settings(this))
        RefreshWorker.schedule(this)
        // The previous planner polled locally. ntfy sends reminders from the hub.
        WorkManager.getInstance(this).cancelUniqueWork("workout-planner-reminders")
    }
}
