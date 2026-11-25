package org.tdgs.ph.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Clears the "start on phone" prompt when the user chooses Not now or swipes it away.
 * The watch keeps its own detection state, so a dismissed prompt is not offered again
 * until the watch re-arms.
 */
class WorkoutPromptDismissed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchWorkoutCommands.clearRequest(context)
    }
}
