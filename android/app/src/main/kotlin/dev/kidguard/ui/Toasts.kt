package dev.kidguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Every toast in the app goes through here (owner 2026-11-11: "fix 1 chỗ là ăn hết").
 * Android 12+ / MIUI cut a text toast at 2 lines (~50 chars on a phone): keep each toast string within [MAX];
 * `ToastsTest` fails the build when a string used here is longer, in any language. Longer explanations go in a Modal.
 * Main thread always; a new toast replaces the one still showing instead of queueing behind it.
 */
object Toasts {
    const val MAX = 50
    private val main = Handler(Looper.getMainLooper())
    private var last: Toast? = null

    fun show(ctx: Context, text: CharSequence, long: Boolean = false) {
        val app = ctx.applicationContext ?: ctx
        val run = Runnable {
            runCatching {
                last?.cancel()
                last = Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    /** Resolved with [ctx] (not the app context) so the app's chosen language applies. */
    fun show(ctx: Context, res: Int, long: Boolean = false) = show(ctx, ctx.getString(res), long)
}
