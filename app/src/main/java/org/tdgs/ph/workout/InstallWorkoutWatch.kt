package org.tdgs.ph.workout

import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WorkoutWatchFiles : FileProvider()

@Composable
internal fun InstallWorkoutWatch() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = remember(context) {
        DefaultPebbleAndroidAppPicker.getInstance(context).apply { enableAutoSelect = false }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    val packages = remember(picker) { picker.getAllEligibleApps() }
    LaunchedEffect(picker) { selected = picker.getCurrentlySelectedApp() }
    if (packages.isEmpty()) Text("Install a Pebble phone app with PebbleKit 2 first.", style = MaterialTheme.typography.bodySmall)
    packages.forEach { name ->
        val label = remember(name) {
            runCatching { context.packageManager.getApplicationInfo(name, 0).loadLabel(context.packageManager).toString() }
                .getOrDefault(name)
        }
        TextButton(onClick = {
            scope.launch { picker.selectApp(name); selected = picker.getCurrentlySelectedApp() }
        }) { Text(if (selected == name) "Using $label" else "Use $label") }
    }
    TextButton(enabled = selected != null, onClick = {
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.cacheDir, "watchapps").apply { mkdirs() }
                    val file = File(directory, "Workout-Live.pbw")
                    context.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.watchapps", file)
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
                        .setPackage(selected).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.getOrNull()
            }
            if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
                Toast.makeText(context, "Could not open the watch app in the selected Pebble app.", Toast.LENGTH_LONG).show()
            }
        }
    }) { Text("Install Workout Live on Pebble") }
}
