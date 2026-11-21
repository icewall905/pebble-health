@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.tdgs.ph.checkin

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun TrainerCheckInSettings(repo: Repository) {
    val settings by repo.settings.checkInSettings.collectAsState(initial = CheckInSettings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(CheckInNotifier.permitted(context)) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted && CheckInNotifier.permitted(context)
        if (allowed) scope.launch { repo.settings.setCheckInsEnabled(true) }
    }
    LifecycleResumeEffect(Unit) {
        allowed = CheckInNotifier.permitted(context)
        onPauseOrDispose { }
    }

    PhCard {
        CardHeader("Trainer check-ins", Icons.Rounded.Notifications, LocalMetrics.current.sleep, chevron = false)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Send daily check-ins", Modifier.weight(1f))
            Switch(checked = settings.enabled, onCheckedChange = { enabled ->
                when {
                    !enabled -> scope.launch {
                        repo.settings.setCheckInsEnabled(false)
                        CheckInNotifier.clear(context)
                    }
                    allowed -> scope.launch { repo.settings.setCheckInsEnabled(true) }
                    Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> openNotificationSettings(context)
                }
            })
        }
        Note("Get short updates on your daily steps and rolling 7-day intensity goal. Messages arrive near the selected times when Android can refresh the hub.")
        if (!allowed) {
            TextButton(onClick = { openNotificationSettings(context) }) { Text("Notification settings") }
        }
        if (settings.enabled) {
            settings.times.sorted().forEach { minute ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { editing = minute }, modifier = Modifier.weight(1f)) {
                        Text(LocalTime.of(minute / 60, minute % 60)
                            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
                    }
                    if (settings.times.size > 1) {
                        TextButton(onClick = {
                            scope.launch { repo.settings.setCheckInTimes(settings.times - minute) }
                        }) { Text("Remove") }
                    }
                }
            }
            if (settings.times.size < 3) TextButton(onClick = { editing = -1 }) { Text("Add time") }
            Note("If you’re signed in to Trainer, it writes the message using these goal totals.")
        }
    }

    editing?.let { old ->
        val initial = if (old >= 0) old else defaultCheckInTimes.sorted().firstOrNull { it !in settings.times } ?: 9 * 60
        val picker = rememberTimePickerState(initial / 60, initial % 60,
            android.text.format.DateFormat.is24HourFormat(context))
        val chosen = picker.hour * 60 + picker.minute
        val duplicate = chosen != old && chosen in settings.times
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Check-in time") },
            text = { Column {
                TimeInput(picker)
                if (duplicate) Text("A check-in is already set for this time.", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = {
                TextButton(enabled = !duplicate, onClick = {
                    scope.launch { repo.settings.setCheckInTimes(settings.times - old + chosen) }
                    editing = null
                }) { Text("Save time") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}
