package dev.kidguard.parent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** BOOT_COMPLETED / MY_PACKAGE_REPLACED: the background check must not wait for the parent to open the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { if (Store.notify(c)) CheckJob.schedule(c) }
    }
}
