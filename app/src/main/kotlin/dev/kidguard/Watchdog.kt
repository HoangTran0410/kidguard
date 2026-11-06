package dev.kidguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** STUB (core branch fills it): an alarm every ~15 min that starts Guard again if it was killed. */
class Watchdog : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Guard.start(context) }

    companion object {
        fun schedule(ctx: Context) {}
    }
}
