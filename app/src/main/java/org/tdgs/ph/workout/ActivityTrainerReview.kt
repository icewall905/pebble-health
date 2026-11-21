@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.tdgs.ph.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.Repository
import org.tdgs.ph.planner.PlannerApi
import org.tdgs.ph.planner.PlannerLogin
import org.tdgs.ph.planner.PlannerLoginRequired
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.SectionTitle
import org.tdgs.ph.ui.theme.LocalMetrics

@Composable
internal fun TrainerReviewEntry(open: () -> Unit) {
    PhCard(onClick = open) {
        CardHeader("Trainer review", Icons.Rounded.AutoAwesome, LocalMetrics.current.sleep)
        Note("Review the activity minute by minute.")
    }
}

@Composable
internal fun ActivityTrainerReview(
    repo: Repository,
    title: String,
    detail: WorkoutDetail? = null,
    hubId: String? = null,
    back: () -> Unit,
) {
    val hub by repo.settings.config.collectAsState(initial = null)
    val endpoint = hub?.baseUrl?.let(Api::normalize)
    val api = remember(endpoint) { endpoint?.let { PlannerApi(it, repo.settings) } }
    val scope = rememberCoroutineScope()
    var busy by remember(api) { mutableStateOf(false) }
    var login by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var result by remember(api) { mutableStateOf<JSONObject?>(null) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (_: PlannerLoginRequired) {
                login = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not review activity."
            } finally {
                busy = false
            }
        }
    }

    val evidence = result?.optJSONObject("timeline")
    val columns = evidence?.optJSONArray("columns")
    val minutes = evidence?.optJSONArray("minutes")
    val activeClock = evidence?.optString("clock") == "active"
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Trainer review") },
            navigationIcon = {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle(title) }
            if (result == null) item {
                PhCard {
                    Note("Trainer uses the available minute metrics and activity totals. Phone reviews share those metrics with your configured Trainer. Route coordinates, names, and notes stay on your phone.")
                    if (login && api != null) {
                        PlannerLogin(busy) { user, password ->
                            run { api.login(user, password); login = false }
                        }
                    } else if (api == null) {
                        Note("Connect your Pebble Health hub to use Trainer.")
                    } else {
                        Button(enabled = !busy, onClick = {
                            run {
                                val payload = if (detail != null) withContext(Dispatchers.Default) {
                                    JSONObject(detail.trainerEvidence().toString())
                                } else JSONObject().put("id", requireNotNull(hubId))
                                result = api.activityReview(payload)
                            }
                        }) { Text(if (busy) "Reviewing activity…" else "Generate review") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            result?.let { review ->
                item {
                    PhCard {
                        Text(review.optString("reply"), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (minutes != null && columns != null) {
                item {
                    SectionTitle(if (activeClock) "Active minutes" else "Elapsed minutes")
                    Note(if (activeClock) "Synced heart rate uses active time. Pauses are omitted from this timeline."
                        else "Minutes without samples are shown as gaps.")
                }
                items((0 until minutes.length()).toList(), key = { it }) { index ->
                    val row = minutes.getJSONArray(index)
                    val metrics = (2 until columns.length()).mapNotNull { column ->
                        if (row.isNull(column)) null else minuteMetric(columns.getString(column), row.getDouble(column))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("Minute ${row.getInt(0)}", Modifier.width(88.dp), style = MaterialTheme.typography.labelLarge)
                        Text(buildList {
                            if (row.getInt(1) < 60) add("${row.getInt(1)} s")
                            addAll(metrics.ifEmpty { listOf("No samples") })
                        }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LocalMetrics.current.muted)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun minuteMetric(key: String, value: Double): String? {
    val n = Fmt.int(value)
    return when (key) {
        "hr" -> "$n bpm avg"
        "peak_hr" -> "$n bpm peak"
        "hr_seconds" -> "$n s HR"
        "distance_m" -> "$n m"
        "gps_seconds" -> "$n s GPS"
        "steps" -> "$n steps"
        "active" -> if (value == 1.0) "Active" else "Inactive"
        "elevation_m" -> "$n m elevation"
        else -> null
    }
}
