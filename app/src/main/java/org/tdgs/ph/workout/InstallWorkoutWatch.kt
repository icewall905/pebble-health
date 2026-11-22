package org.tdgs.ph.workout

import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
    TextButton(onClick = {
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.cacheDir, "watchapps").apply { mkdirs() }
                    val file = File(directory, "Workout-Live.pbw")
                    context.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.watchapps", file)
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
                        .setPackage("tdgs.gravel").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.getOrNull()
            }
            if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
                Toast.makeText(context, "Could not open the watch app in GravelEx.", Toast.LENGTH_LONG).show()
            }
        }
    }) { Text("Install Workout Live on Pebble") }
}
