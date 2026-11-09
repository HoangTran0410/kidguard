package dev.kidguard

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup

/**
 * Notification dots on launcher icons, the way Pixel / One UI / Nova do it: a notification listener counts the
 * notifications each app is showing. Counts only: nothing is stored or sent (the parent never sees them).
 * Access comes from setup (`cmd notification allow_listener`, protocol "Setup steps"): writing
 * enabled_notification_listeners is ignored since Android 8 (measured on 8.1), and Device Owner has no API for it.
 * The "Chấm thông báo" switch only binds / unbinds the service (it stops listening when off).
 */
class NotifDots : NotificationListenerService() {
    override fun onListenerConnected() {
        me = this
        if (!wanted) { runCatching { requestUnbind() }; return }
        recount()
    }
    override fun onListenerDisconnected() { me = null; publish(emptyMap()) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recountSoon()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recountSoon()

    // a burst (a chat app posting 10 messages, a group cancel) is one binder round trip instead of one per callback
    private val later = Runnable { recount() }
    private fun recountSoon() { main.removeCallbacks(later); main.postDelayed(later, 250) }

    private fun recount() {
        val all = runCatching { activeNotifications }.getOrNull() ?: return
        val rk = runCatching { currentRanking }.getOrNull()
        val r = Ranking()
        val n = HashMap<String, Int>()
        for (s in all) {
            val f = s.notification.flags
            // what a dot means on Pixel: not ongoing (music, calls, our own guard), not a group summary, badge allowed
            if (s.isOngoing || f and Notification.FLAG_GROUP_SUMMARY != 0 || f and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
            if (s.packageName == packageName) continue
            if (rk != null && rk.getRanking(s.key, r) && !r.canShowBadge()) continue
            n[s.packageName] = (n[s.packageName] ?: 0) + 1
        }
        publish(n)
    }

    companion object {
        @Volatile private var me: NotifDots? = null
        @Volatile var counts: Map<String, Int> = emptyMap(); private set
        /** Called on the main thread with the packages whose count changed. */
        var onChange: ((Set<String>) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        private fun publish(n: Map<String, Int>) {
            val old = counts
            if (n == old) return
            counts = n
            val changed = (old.keys + n.keys).filterTo(HashSet()) { old[it] != n[it] }
            main.post { onChange?.invoke(changed) }
        }

        @Volatile private var wanted = true

        private var grantedAt = 0L
        private var grantedVal = false

        /** Binder call, so cached for 5 s (CustomActivity.build runs on every tap); [fresh] = ask the system now. */
        fun granted(ctx: Context, fresh: Boolean = false): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!fresh && grantedAt != 0L && now - grantedAt < 5_000) return grantedVal
            return query(ctx).also { grantedVal = it; grantedAt = now }
        }

        private fun query(ctx: Context): Boolean {
            val cn = ComponentName(ctx, NotifDots::class.java)
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            return if (android.os.Build.VERSION.SDK_INT >= 27) runCatching { nm.isNotificationListenerAccessGranted(cn) }.getOrDefault(false)
            else Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(cn.flattenToString()) == true
        }

        /** Opens the system switch for our listener (Android 11+: straight to KidGuard's own row, else the list). */
        fun ask(ctx: Context) {
            val cn = ComponentName(ctx, NotifDots::class.java)
            val direct = if (android.os.Build.VERSION.SDK_INT >= 30) android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString()) else null
            val list = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            for (i in listOfNotNull(direct, list)) if (runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }

        /** The dots switch (and release): listen or stop listening. Access itself stays as setup left it. */
        fun setOn(ctx: Context, on: Boolean) {
            wanted = on
            if (on) { if (me == null && granted(ctx)) runCatching { requestRebind(ComponentName(ctx, NotifDots::class.java)) } }
            else { me?.let { runCatching { it.requestUnbind() } }; me = null; publish(emptyMap()) }
        }
    }
}

/** An icon that carries a dot: app icons (one package) and folders (the sum of theirs). */
internal interface DotHost { val dotPkgs: Collection<String> }

internal object Dots {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun count(pkgs: Collection<String>): Int { val c = NotifDots.counts; return if (c.isEmpty()) 0 else pkgs.sumOf { c[it] ?: 0 } }

    /** POCO / One UI style (or a plain dot): a red disc with the count at the icon's top-right, no ring (POCO's reads fine without). */
    fun draw(c: Canvas, px: Int, n: Int, density: Float, number: Boolean = true) {
        if (n <= 0) return
        if (!number) {
            // Pixel-style plain dot
            val r = (px * 0.1f).coerceAtLeast(5 * density); val cx = px - r * 0.5f; val cy = r * 0.5f   // half out past the corner
            fill.color = 0xFFE5393B.toInt(); c.drawCircle(cx, cy, r, fill)
            return
        }
        val t = if (n > 99) "99+" else n.toString()
        val r = (px * 0.16f).coerceAtLeast(8 * density)
        num.textSize = r * (if (t.length > 2) 0.9f else 1.15f)
        val w = maxOf(2 * r, num.measureText(t) + r * 0.9f)
        // pokes out of the icon's top-right corner (One UI), drawn past the view: parents don't clip
        val cx = px - w / 2f + r * 0.55f; val cy = r * 0.45f
        fill.color = 0xFFE5393B.toInt()
        c.drawRoundRect(cx - w / 2, cy - r, cx + w / 2, cy + r, r, r, fill)
        c.drawText(t, cx, cy - (num.descent() + num.ascent()) / 2, num)
    }

    /** Redraws the icons of the changed packages only: one walk of the tree, no rebuild. */
    fun refresh(v: View, changed: Set<String>) {
        if (v is DotHost && v.dotPkgs.any { it in changed }) v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) refresh(v.getChildAt(i), changed)
    }
}
