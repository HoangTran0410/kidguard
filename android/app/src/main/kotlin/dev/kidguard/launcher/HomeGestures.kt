package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import org.json.JSONObject

/**
 * Gestures on empty home-grid space: double tap, swipe up, swipe down, each mapped to an action (GestLogic ids). Prefs "hm_gest"
 * (device-protected like the rest). Swipes default to "default" = the launcher's old behaviour (up: drawer follows the finger;
 * down: left half notifications, right half control center), so nothing changes until the user picks. Hooks: HomeActivity.SwipeUp
 * (swipes) and Pager.onTouchEvent (double tap).
 */
object Gest {
    private const val PREFS = "hm_gest"
    private var cache: Map<String, Pair<String, String>>? = null   // gesture -> (action, app package)
    private var tracker: GestLogic.TapTracker? = null

    fun taps(ctx: Context): GestLogic.TapTracker = tracker ?: GestLogic.TapTracker(ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()).also { tracker = it }

    private fun prefs(ctx: Context) = ctx.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized private fun load(ctx: Context): Map<String, Pair<String, String>> = cache ?: run {
        val p = prefs(ctx)
        GestLogic.GESTURES.associateWith { g ->
            val pkg = p.getString("${g}_app", "") ?: ""
            GestLogic.clean(g, p.getString(g, null), pkg, true) to pkg
        }.also { cache = it }
    }

    /** The stored action; "lock" falls back to the default where this phone can't lock. */
    fun action(ctx: Context, g: String): String = load(ctx)[g]?.first?.let { GestLogic.clean(g, it, app(ctx, g), lockOk(ctx)) } ?: GestLogic.default(g)
    fun app(ctx: Context, g: String): String = load(ctx)[g]?.second ?: ""

    @Synchronized fun set(ctx: Context, g: String, action: String, pkg: String = "") {
        if (g !in GestLogic.GESTURES) return
        val a = GestLogic.clean(g, action, pkg, true)
        prefs(ctx).edit().putString(g, a).putString("${g}_app", if (a == GestLogic.APP) pkg else "").apply()
        cache = null
    }

    /** For backups: {"dbl":{"a":..,"app":..},"up":..,"down":..}. */
    fun toJson(ctx: Context) = JSONObject().also { o ->
        GestLogic.GESTURES.forEach { g -> o.put(g, JSONObject().put("a", load(ctx)[g]?.first ?: GestLogic.default(g)).put("app", app(ctx, g))) }
    }

    fun fromJson(ctx: Context, o: JSONObject?) {
        o ?: return
        for (g in GestLogic.GESTURES) { val j = o.optJSONObject(g) ?: continue; set(ctx, g, j.optString("a", GestLogic.default(g)).take(16), j.optString("app").take(200)) }
    }

    /** Lock is offered only where it works: kid flavor (the home flavor has no admin / accessibility) with Device Owner, admin or accessibility (API 28+). */
    fun lockOk(ctx: Context): Boolean = !Flavor.HOME && !Store.of(ctx).released &&
        (Policy.isOwner(ctx) || Basic.adminActive(ctx) || (Build.VERSION.SDK_INT >= 28 && Acc.running != null))

    /**
     * Pulls the notification shade down (hidden StatusBarManager API, the same Nova uses), else the accessibility global action.
     * Quick-settings blocking (rule launcher.shade) is done by Acc however the shade opened; the notifications stay usable.
     */
    @SuppressLint("WrongConstant")
    fun shade(ctx: Context) {
        val ok = runCatching {
            val sb = ctx.getSystemService("statusbar") ?: error("no statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }.isSuccess
        if (!ok) {
            Log.w("KidGuard", "gesture shade: reflection failed")
            Acc.running?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        }
    }
}

/** Runs the action of gesture [g] (the caller keeps the old behaviour for "default"). */
internal fun HomeActivity.gestRun(g: String) {
    when (Gest.action(this, g)) {
        GestLogic.SEARCH -> {
            openDrawer()
            main.postDelayed({ drawer?.search?.let { s -> s.requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(s, 0) } }, 260)
        }
        GestLogic.DRAWER -> openDrawer()
        GestLogic.SHADE -> Gest.shade(this)
        GestLogic.LOCK -> if (Gest.lockOk(this)) Quick.lockOrSay(this)
        GestLogic.TODAY -> if (pager.minPage == 0) pager.snapTo(0)
        GestLogic.APP -> {
            val a = launcherApps()[Gest.app(this, g)]
            if (a == null) { Toasts.show(this, R.string.gest_app_gone); return }
            Recent.note(this, a.pkg)
            if (!Flavor.HOME) runCatching { AccPause.pause(this, a.pkg) }
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(a.pkg, a.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            runCatching { startActivity(i) }
        }
    }
}

/** A home page is showing and nothing is being dragged / edited / open: the only time gestures count. */
internal fun HomeActivity.gestFree() = !pager.dragging && pager.page > 0 && !onLibrary() && drag == null && !editing && overlay == null && popup?.isShowing != true
