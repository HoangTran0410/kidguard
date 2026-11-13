package dev.kidguard

import android.content.Context
import android.os.PowerManager

/** Tells the child once per burst that location was put back on by the parent's rule (toast, only with the screen on). */
object LocHint {
    private const val GAP_MS = 5 * 60_000L
    private var last = 0L

    fun maybe(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (now - last < GAP_MS) return
        if (!runCatching { ctx.getSystemService(PowerManager::class.java)?.isInteractive == true }.getOrDefault(false)) return
        last = now
        Toasts.show(ctx, ctx.getString(R.string.loc_kept))
    }
}
