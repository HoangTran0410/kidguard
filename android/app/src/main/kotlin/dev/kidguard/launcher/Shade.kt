package dev.kidguard

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * Pull the notification shade down (hidden StatusBarManager API, what Nova uses; EXPAND_STATUS_BAR is a normal permission).
 * Swipe down on the home: the left half opens the notifications, the right half the quick settings (HyperOS / One UI 7
 * split shade). Phones with one shade (Pixel, older ROMs) open it fully expanded on the right: still the toggles.
 */
object Shade {
    @SuppressLint("WrongConstant")
    fun expand(ctx: Context, settings: Boolean = false) {
        runCatching {
            val sb = ctx.getSystemService("statusbar") ?: return
            val c = Class.forName("android.app.StatusBarManager")
            if (settings) runCatching { c.getMethod("expandSettingsPanel").invoke(sb) }.getOrElse { c.getMethod("expandNotificationsPanel").invoke(sb) }
            else c.getMethod("expandNotificationsPanel").invoke(sb)
        }.onFailure { Log.w("KidGuard", "expand shade: $it") }
    }
}
