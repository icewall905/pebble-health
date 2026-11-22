package org.tdgs.ph.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.ApiException
import org.tdgs.ph.data.Repository

@Composable
internal fun WorkoutUploadDialog(repo: Repository, close: () -> Unit) {
    val config by repo.settings.config.collectAsState(initial = null)
    val existing by repo.settings.workoutUploadConfig.collectAsState(initial = null)
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val token = input.trim()
    val hub = config
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("Workout uploads") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hub == null) {
                    Text("Connect to your PebbleFit hub from the app’s setup screen to send workouts.")
                } else {
                    Text("Create a history upload token in your PebbleFit web dashboard and paste it here.")
                    Api.normalize(hub.baseUrl)?.let { url ->
                        TextButton(onClick = { uri.openUri(url.toString()) }) { Text("Open PebbleFit web dashboard") }
                    }
                    OutlinedTextField(input, { input = it.take(220) }, label = { Text("History upload token") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (hub == null) {
                TextButton(onClick = close) { Text("Close") }
            } else {
                TextButton(enabled = !busy && token.length in 1..200 && token.none(Char::isWhitespace), onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            Api().checkWorkoutToken(hub.copy(token = token))
                            repo.settings.saveWorkoutUpload(hub, token)
                            close()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ApiException) {
                            error = e.message
                        } catch (_: Exception) {
                            error = "Could not save the token. Check the connection and hub settings."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Checking…" else "Save token") }
            }
        },
        dismissButton = {
            if (existing != null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            repo.settings.clearWorkoutUpload()
                            close()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "Could not remove the upload token."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Remove token") }
            } else if (hub != null) {
                TextButton(enabled = !busy, onClick = close) { Text("Cancel") }
            }
        },
    )
}
