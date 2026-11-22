package org.tdgs.ph.workout

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** Use recent movement within one GPS segment. A stale or sparse fix yields no live pace. */
internal fun recentPace(route: List<Fix>, active: Long): Long? {
    val last = route.lastOrNull() ?: return null
    if (active - last.active > 7000) return null
    val first = route.asReversed().takeWhile {
        it.segment == last.segment && last.active - it.active <= 20_000
    }.last()
    val elapsed = last.active - first.active
    val meters = last.meters - first.meters
    return if (elapsed >= 5000 && meters >= 10) (elapsed * 1000 / meters).toLong() else null
}

internal class WorkoutWatch(private val context: Context) {
    private var transaction = 0
    fun update(state: LiveWorkout, finished: Boolean = false) {
        val values = listOf(state.active.div(1000).toInt(), state.distance.roundToInt(),
            state.livePace?.div(1000)?.toInt() ?: 0, state.bpm ?: 0,
            if (finished) 2 else if (state.paused) 1 else 0)
        val data = JSONArray()
        values.forEachIndexed { key, value ->
            data.put(JSONObject().put("key", key).put("type", "uint").put("length", 4).put("value", value))
        }
        // Only send to the installed GravelEx phone companion. The watch app must be open.
        context.sendBroadcast(Intent("com.getpebble.action.app.SEND").setPackage("tdgs.gravel")
            .putExtra("uuid", WATCH_UUID)
            .putExtra("transaction_id", transaction++ and 255)
            .putExtra("msg_data", data.toString()))
    }

    companion object {
        private val WATCH_UUID = UUID.fromString("23a97c1c-672f-4285-9b6c-5244cae8526e")
    }
}
