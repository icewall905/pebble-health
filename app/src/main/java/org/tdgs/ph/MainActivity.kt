package org.tdgs.ph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.tdgs.ph.data.Prefs
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
    }
}
