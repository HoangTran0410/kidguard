package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.RemoteViews

/**
 * The child's shortcuts: a lock button (launcher shortcut, 1x1 widget, Quick Settings tile) and today's time left
 * (tile + 2x1 widget, tap = "ask for more time"). None of them shows settings or anything but KidGuard's own screen.
 * Text comes from Guard's tick ([publish]) only when it changes: no wakeups of its own. Pure part: QuickLogic.
 */
object Quick {
    private const val PREFS = "quick"
    const val PIN_ID = "lock_pin"            // the pinned shortcut (the manifest one is "lock" and can't be disabled by API)
    const val EXTRA_MORE = "kg.more"         // MainActivity: open "ask for more time"
    @Volatile private var last: String? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Locks the screen now. Accessibility first (Android 9+): it is the power button's lock, so fingerprint / face
     * still unlock. DevicePolicyManager.lockNow() (owner, or basic mode's device admin) also works but makes the next
     * unlock ask for the PIN / pattern, so it is only the fallback. The parent's "lock" command still uses lockNow.
     */
    fun lock(ctx: Context): Boolean {
        if (Store.of(ctx).released) return false
        val app = ctx.applicationContext
        val admin = Policy.isOwner(app) || Basic.adminActive(app)
        Log.i("KidGuard", "lock: acc=${Acc.running != null} admin=$admin")
        if (Build.VERSION.SDK_INT >= 28 && Acc.running?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true) {
            // with screen lock "None" (or the keyguard disabled) that action does nothing at all: then lockNow, which turns the screen off
            if (admin) main.postDelayed({ if (!lockedOrOff(app)) Log.i("KidGuard", "lock: no keyguard, lockNow " + Policy.lockNow(app)) }, 700)
            return true
        }
        return admin && Policy.lockNow(app)
    }

    private fun lockedOrOff(ctx: Context): Boolean =
        ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false ||
            ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** [lock] with a toast when it can't. */
    fun lockOrSay(ctx: Context) {
        if (!lock(ctx)) runCatching { Toasts.show(ctx.applicationContext, ctx.applicationContext.getString(R.string.q_lock_fail), true) }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last published time-left text, or the tile's name when there is none for today (Guard not run yet / yesterday's). */
    fun text(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("text", null)?.takeIf { p.getString("day", null) == Guard.day(System.currentTimeMillis()) }
            ?: ctx.applicationContext.getString(R.string.q_time)
    }

    /** From Guard's tick: a new text goes to the prefs, a listening tile and every placed widget; the same text does nothing. */
    fun publish(ctx: Context, text: String) {
        if (text == last) return
        last = text
        prefs(ctx).edit().putString("text", text).putString("day", Guard.day(System.currentTimeMillis())).apply()
        main.post { TimeTile.live?.show() }
        runCatching { updateWidgets(ctx) }.onFailure { Log.w("KidGuard", "widgets", it) }
    }

    fun updateWidgets(ctx: Context) {
        val wm = AppWidgetManager.getInstance(ctx) ?: return
        wm.getAppWidgetIds(ComponentName(ctx, TimeWidget::class.java)).takeIf { it.isNotEmpty() }?.let { wm.updateAppWidget(it, timeViews(ctx)) }
        wm.getAppWidgetIds(ComponentName(ctx, LockWidget::class.java)).takeIf { it.isNotEmpty() }?.let { wm.updateAppWidget(it, lockViews(ctx)) }
    }

    fun timeViews(ctx: Context) = RemoteViews(ctx.packageName, R.layout.w_time).apply {
        val app = ctx.applicationContext
        setTextViewText(R.id.w_title, "KidGuard · " + app.getString(R.string.q_today))
        setTextViewText(R.id.w_text, text(ctx))
        setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 21, moreIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun lockViews(ctx: Context) = RemoteViews(ctx.packageName, R.layout.w_lock).apply {
        val app = ctx.applicationContext
        setTextViewText(R.id.w_text, app.getString(R.string.q_lock_short))
        setContentDescription(R.id.w_root, app.getString(R.string.q_lock))
        setOnClickPendingIntent(R.id.w_root, PendingIntent.getBroadcast(ctx, 22, Intent(ctx, LockReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
    }

    /** KidGuard's own screen with "ask for more time" open: the only thing a tile / widget tap can show. */
    fun moreIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_MORE, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * The default home is this app's own kid launcher: add its 1x1 "lock" widget to the first page with room (or say
     * it is there already) and show the home. False when another launcher is the home (then [requestPin]).
     */
    fun addLockToOwnHome(ctx: android.app.Activity): Boolean {
        val home = runCatching { ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }.getOrNull()
        if (home?.packageName != ctx.packageName || home.name != HomeActivity::class.java.name) return false
        val m = HomeFile.load(ctx) ?: return false
        val msg = if (HomeLayout.hasWidget(m, "lock")) R.string.main_pin_lock_there else {
            val n = HomeLayout.placeWidget(m, "lock", 1, 1, 0) ?: return false
            HomeFile.save(ctx, n); R.string.main_pin_lock_added
        }
        Toasts.show(ctx, ctx.getString(msg), true)
        runCatching { ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    /** Asks the launcher to pin a "Lock screen" icon. False when this launcher can't. */
    fun requestPin(ctx: Context): Boolean = runCatching {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val app = ctx.applicationContext
        val info = ShortcutInfo.Builder(ctx, PIN_ID).setShortLabel(app.getString(R.string.q_lock)).setLongLabel(app.getString(R.string.q_lock))
            .setIcon(Icon.createWithResource(ctx, R.drawable.sc_lock))
            .setIntent(Intent(ctx, LockActivity::class.java).setAction(Intent.ACTION_VIEW)).build()
        sm.requestPinShortcut(info, null)
    }.getOrElse { Log.w("KidGuard", "pin shortcut", it); false }

    private val COMPONENTS = listOf(LockActivity::class.java, LockReceiver::class.java, LockTile::class.java, TimeTile::class.java,
        LockWidget::class.java, TimeWidget::class.java)

    /**
     * Guard start: on (cheap when already on). Release: off. The tiles leave Quick Settings, the widgets go away, the
     * shortcuts stop working (a pinned shortcut can't be removed, only disabled), since after release neither the device
     * admin nor the accessibility service is there to lock with.
     */
    fun setEnabled(ctx: Context, on: Boolean) {
        val pm = ctx.packageManager
        val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (c in COMPONENTS) runCatching {
            val cn = ComponentName(ctx, c)
            if (pm.getComponentEnabledSetting(cn) != want) pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
        }.onFailure { Log.w("KidGuard", "component $c", it) }
        runCatching {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
            val pinned = sm.pinnedShortcuts.any { it.id == PIN_ID }
            if (!pinned) return
            if (on) sm.enableShortcuts(listOf(PIN_ID)) else sm.disableShortcuts(listOf(PIN_ID))
        }.onFailure { Log.w("KidGuard", "shortcut enable=$on", it) }
    }
}

/** Launcher shortcut target: lock, then go. Theme.NoDisplay + no animation: nothing shows; not in recents. */
class LockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Quick.lockOrSay(this)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** The lock widget's tap (explicit PendingIntent, not exported: no other app can send it). */
class LockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Quick.lockOrSay(context)
}

class LockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, wm: AppWidgetManager, ids: IntArray) = wm.updateAppWidget(ids, Quick.lockViews(context))
}

class TimeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, wm: AppWidgetManager, ids: IntArray) = wm.updateAppWidget(ids, Quick.timeViews(context))
}

/** Quick Settings "Lock screen": locks, opens nothing. Long-press goes to MainActivity (QS_TILE_PREFERENCES), not App info. */
class LockTile : TileService() {
    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()
    override fun onStartListening() {
        val t = qsTile ?: return
        t.label = getString(R.string.q_lock)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = null
        t.icon = Icon.createWithResource(this, R.drawable.li_lock)
        t.state = Tile.STATE_ACTIVE
        t.updateTile()
    }
    override fun onClick() = Quick.lockOrSay(this)
}

/** Quick Settings time left: the text Guard last published. Tap: KidGuard's "ask for more time" (after unlocking). */
class TimeTile : TileService() {
    override fun getResources(): android.content.res.Resources = Lang.res(baseContext) ?: super.getResources()
    override fun onStartListening() { live = this; show() }
    override fun onStopListening() { if (live === this) live = null }

    fun show() {
        val t = qsTile ?: return
        t.label = Quick.text(this)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = "KidGuard"
        t.icon = Icon.createWithResource(this, R.drawable.li_timer)
        t.state = Tile.STATE_ACTIVE
        t.updateTile()
    }

    override fun onClick() { if (isLocked) unlockAndRun { open() } else open() }

    private fun open() {
        val i = Quick.moreIntent(this)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 21, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    companion object { @Volatile var live: TimeTile? = null }
}
