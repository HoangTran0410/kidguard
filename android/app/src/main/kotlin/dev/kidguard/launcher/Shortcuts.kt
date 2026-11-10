package dev.kidguard

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process

/**
 * Apps' pinned shortcuts on the kid home (item kind [HomeLayout.SHORTCUT]). Only the default home may read and start
 * them (LauncherApps). A shortcut is shown, paused and hidden exactly like its app, so it is never a way around a
 * rule; the Guard still enforces whatever it opens.
 */
object Shortcuts {
    /** AppE.cls / IconCache key of a shortcut: "sc:" + its id (an app's activity class never starts with it). */
    const val PREFIX = "sc:"

    private fun la(ctx: Context) = ctx.getSystemService(LauncherApps::class.java)

    private fun info(ctx: Context, pkg: String, id: String): ShortcutInfo? = runCatching {
        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setShortcutIds(listOf(id)).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        la(ctx)?.getShortcuts(q, Process.myUserHandle())?.firstOrNull()
    }.getOrNull()

    /** Its icon; the app's own when the shortcut can't be read (not the default home any more, removed by the app). */
    fun icon(ctx: Context, pkg: String, id: String): Drawable? =
        info(ctx, pkg, id)?.let { i -> runCatching { la(ctx)?.getShortcutIconDrawable(i, ctx.resources.displayMetrics.densityDpi) }.getOrNull() }
            ?: runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** False when it can't start (disabled by its app, not pinned, we are not the default home). */
    fun start(ctx: Context, pkg: String, id: String, bounds: Rect?, opts: Bundle?): Boolean =
        runCatching { la(ctx)!!.startShortcut(pkg, id, bounds, opts, Process.myUserHandle()); true }.getOrDefault(false)
}

/**
 * The system's CONFIRM_PIN_SHORTCUT: an app asked to pin a shortcut (requestPinShortcut) and this is the default home.
 * Accepted at once (the kid asked for it in that app) onto the first free cell, with a toast; no UI of its own.
 * Widgets pinned this way (requestPinAppWidget) are declined: the home's widget picker adds those.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { add() }
        finish()
    }

    private fun add() {
        val req = getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent) ?: return
        if (req.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || !req.isValid) return
        val sc = req.shortcutInfo ?: return
        val m = HomeFile.load(this) ?: return
        val label = (sc.shortLabel ?: sc.longLabel ?: "").toString().ifBlank { sc.`package` }
        if (!req.accept()) return
        HomeFile.save(this, HomeLayout.addShortcut(m, sc.`package`, sc.id, label))
        Toasts.show(this, getString(R.string.hm_sc_added, label))
    }
}
