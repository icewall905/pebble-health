package org.tdgs.ph

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.planner.WorkoutPlannerActivity
import org.tdgs.ph.ui.PhRoot
import org.tdgs.ph.ui.theme.PhTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = (application as PhApp).repository
        repo.loadCached()
        setContent {
            val prefs by repo.settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
            PhTheme(prefs.theme) { PhRoot(repo) }
        }
        if (savedInstanceState == null) openPlannerLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openPlannerLink(intent)
    }

    private fun openPlannerLink(intent: Intent) {
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "https" ||
            uri.host != "ph.tdgs.org" || uri.path != "/planner.html") return
        val open = Intent(this, WorkoutPlannerActivity::class.java)
            .putExtra("model_settings", uri.getQueryParameter("settings") == "1")
        uri.getQueryParameter("workout")?.takeIf { Regex("[a-f0-9]{32}").matches(it) }?.let {
            open.putExtra("workout_id", it)
        }
        startActivity(open)
    }
}
