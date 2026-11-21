package org.tdgs.ph.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.theme.LocalMetrics

@Composable
internal fun TrainerWelcome() {
    PhCard {
        CardHeader("Training assistant", Icons.Rounded.AutoAwesome, LocalMetrics.current.sleep, chevron = false)
        Text("Plan your next workout", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
        Note("Ask for a workout, adjust an existing session, or get suggestions based on your goals and recent activity.")
    }
}

@Composable
internal fun TrainerTurn(turn: JSONObject) {
    val fromUser = turn.optString("role") == "user"
    val metrics = LocalMetrics.current
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.fillMaxWidth(.9f).background(
                if (fromUser) metrics.steps.copy(alpha = .14f) else metrics.card,
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (fromUser) 18.dp else 4.dp, bottomEnd = if (fromUser) 4.dp else 18.dp),
            ).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(if (fromUser) "You" else "Trainer", color = LocalMetrics.current.muted,
                style = MaterialTheme.typography.labelMedium)
            Text(turn.optString("text"), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun TrainerProposal(option: JSONObject, planned: List<JSONObject>, busy: Boolean, accept: () -> Unit) {
    val workouts = option.objects("workouts")
    val removalIds = option.optJSONArray("remove_ids")
    PhCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader("Proposed plan", Icons.Rounded.AutoAwesome, LocalMetrics.current.sleep, chevron = false)
            Text(option.optString("title"), style = MaterialTheme.typography.titleLarge)
            option.optString("summary").takeIf(String::isNotBlank)?.let { Text(it) }
            for (index in 0 until (removalIds?.length() ?: 0)) {
                val removed = planned.firstOrNull { it.optString("id") == removalIds?.optString(index) } ?: continue
                Text("Replaces ${removed.optString("title")} · ${removed.optString("start").replace('T', ' ')}",
                    color = LocalMetrics.current.muted, style = MaterialTheme.typography.bodySmall)
            }
            workouts.forEach {
                HorizontalDivider(color = LocalMetrics.current.track)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { WorkoutDescription(it) }
            }
            Button(enabled = !busy, onClick = accept) { Text("Accept plan") }
        }
    }
}

@Composable
internal fun TrainerComposer(
    workout: JSONObject?,
    ready: Boolean,
    busy: Boolean,
    message: String,
    change: (String) -> Unit,
    clearContext: () -> Unit,
    send: () -> Unit,
    suggest: () -> Unit,
    reschedule: () -> Unit,
) {
    Surface(color = LocalMetrics.current.card, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (workout != null) {
                InputChip(
                    selected = true, onClick = clearContext,
                    label = { Text("Workout: ${workout.optString("title")}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Clear workout context") },
                )
            }
            if (!ready) {
                Text("Trainer is currently unavailable.", color = LocalMetrics.current.muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    message, change, Modifier.weight(1f), enabled = ready && !busy,
                    label = { Text("Message Trainer") }, maxLines = 4, shape = RoundedCornerShape(18.dp),
                )
                IconButton(enabled = ready && !busy && message.isNotBlank(), onClick = send) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Send message", tint = if (ready && !busy && message.isNotBlank())
                        MaterialTheme.colorScheme.primary else LocalMetrics.current.muted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = ready && !busy, onClick = suggest) { Text("Suggest plans") }
                if (workout != null) TextButton(enabled = ready && !busy, onClick = reschedule) { Text("Propose a reschedule") }
            }
        }
    }
}
