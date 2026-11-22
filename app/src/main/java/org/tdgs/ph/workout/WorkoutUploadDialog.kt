package org.tdgs.ph.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.Repository
import org.tdgs.ph.planner.PlannerApi
import org.tdgs.ph.planner.PlannerLogin
import org.tdgs.ph.planner.PlannerLoginRequired

@Composable
internal fun WorkoutUploadDialog(repo: Repository, close: () -> Unit) {
    val config by repo.settings.config.collectAsState(initial = null)
    val hub = config
    val base = hub?.baseUrl?.let(Api::normalize)
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var signIn by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }

    LaunchedEffect(hub) {
        if (hub == null) return@LaunchedEffect
        busy = true
        try {
            WorkoutUploadWorker.resolveToken(repo.settings, hub)
            WorkoutUploadWorker.enqueueMissing(context)
            connected = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: PlannerLoginRequired) {
            signIn = true
        } catch (_: Exception) {
            error = "Could not connect to the dashboard. Reopen this screen to retry."
        } finally {
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("Dashboard sign-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    hub == null || base == null -> Text("Connect to your Pebble Health dashboard first.")
                    connected -> Text("Workout sync is connected. Saved workouts upload automatically.")
                    signIn -> PlannerLogin(busy) { username, password ->
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                PlannerApi(base, repo.settings).login(username, password)
                                WorkoutUploadWorker.resolveToken(repo.settings, hub)
                                WorkoutUploadWorker.enqueueMissing(context)
                                connected = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not connect."
                            } finally {
                                busy = false
                            }
                        }
                    }
                    busy -> Text("Checking your dashboard session…")
                }
                if (base != null) TextButton(onClick = { uri.openUri(base.toString()) }) { Text("Open web dashboard") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = close) { Text("Close") } },
    )
}
