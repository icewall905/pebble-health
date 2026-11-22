package org.tdgs.ph.workout

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.Repository
import org.tdgs.ph.data.Workout
import org.tdgs.ph.planner.PlannerApi
import org.tdgs.ph.planner.PlannerLogin
import org.tdgs.ph.planner.PlannerLoginRequired
import java.util.UUID

@Composable
internal fun DashboardActivityRemoval(repo: Repository, workout: Workout) {
    if (workout.editRevision == null || workout.editFingerprint == null) return
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var signIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun apply(api: PlannerApi) {
        api.editWorkout(JSONObject().put("operation", "edit").put("operation_id", UUID.randomUUID().toString())
            .put("id", workout.id).put("ignored", !workout.ignored)
            .put("expected_revisions", JSONObject().put(workout.id, workout.editRevision))
            .put("expected_fingerprints", JSONObject().put(workout.id, workout.editFingerprint)))
        confirm = false
        repo.refresh()
    }
    val action = if (workout.ignored) "Restore activity" else "Remove from dashboard"
    TextButton(enabled = !busy, onClick = { confirm = true; error = null }) { Text(action) }
    if (confirm) {
        val hub by repo.settings.config.collectAsState(initial = null)
        val base = hub?.baseUrl?.let(Api::normalize)
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = false },
            title = { Text(action) },
            text = {
                Column {
                    Text(if (workout.ignored) "Return this activity to your history and training totals."
                        else "Hide this activity from history and training totals. You can restore it from Show removed.")
                    if (signIn && base != null) PlannerLogin(busy) { username, password ->
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                val api = PlannerApi(base, repo.settings)
                                api.login(username, password)
                                apply(api)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Could not update activity." }
                            finally { busy = false }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text("Cancel") } },
            confirmButton = {
                if (!signIn && base != null) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try { apply(PlannerApi(base, repo.settings)) }
                        catch (e: CancellationException) { throw e }
                        catch (_: PlannerLoginRequired) { signIn = true }
                        catch (e: Exception) { error = e.message ?: "Could not update activity." }
                        finally { busy = false }
                    }
                }) { Text(action) }
            },
        )
    }
}
