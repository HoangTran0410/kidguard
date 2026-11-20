package dev.kidguard.parent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject

/**
 * The kid's own notifications mirrored on this phone (kid rule `watch.notif`, docs/flows notifications section):
 * one quiet InboxStyle notification per kid (fixed id, newest lines last, "+N more"), channel [CHANNEL] (low: no sound).
 * Sources: FCM "nf" ([Push.handle]) -> `GET state?nf=<cursor>` on the kid's relay (a few KB), and the ~15 min [CheckJob]
 * from the whole state (LAN-only kids, missed pushes). Both go through [show] with the same per-kid cursor ([Logic.nfPlan]).
 * Text never travels through FCM: only the relay / LAN reply carries it.
 */
object KidNotif {
    const val CHANNEL = "kg_kidnotif"
    private const val KEY = "kidnotif"

    /** Wanted here: the app's notifications on, this setting on, and the OS lets us post. */
    fun on(c: Context) = Store.notify(c) && Store.kidNotif(c) && CheckJob.canNotify(c)

    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.ch_kidnotif), NotificationManager.IMPORTANCE_LOW))
    }

    /** FCM "nf": the rows after our cursor from the kid's relay. Network: not on the main thread. */
    fun fromRelay(c: Context, k: Logic.KidCfg) {
        if (!on(c)) return
        val (base, id, token) = Logic.pushRoute(k) ?: return
        val cur = Store.nfCursor(c, k.key) ?: 0L
        val r = Net.call("GET", "$base/c/$id/state?nf=$cur", mapOf("X-KG-Token" to token), null, 15_000)
        if (r.status != 200) return
        val o = runCatching { JSONObject(String(r.body, Charsets.UTF_8)) }.getOrNull() ?: return
        show(c, k, Logic.nfRows(o.optJSONArray("n")), o.optLong("t"), o.optBoolean("more"))
    }

    /** [rows] from state.notifs (or the `?nf=` reply), [stateT] = the kid's clock in that state. */
    fun show(c: Context, k: Logic.KidCfg, rows: List<Logic.NfRow>, stateT: Long, more: Boolean) {
        if (!on(c)) { Store.setNfCursor(c, k.key, -1); return }   // off: the next look after turning it on starts from there
        val s = synchronized(Store) {   // FCM and the background check may run together
            val cur = Store.nfCursor(c, k.key)
            val p = Logic.nfPlan(rows, cur ?: 0L, stateT, cur == null, more)
            Store.setNfCursor(c, k.key, p.cursor)
            if (p.show.isEmpty()) return
            val prev = if (Notif.showing(c, k, KEY)) Logic.NfShown.fromJson(Store.nfShown(c, k.key)) else null   // swiped away: start over
            Logic.nfMerge(prev, p.show.map(Logic::nfLine), p.more).also { Store.setNfShown(c, k.key, it.toJson()) }
        }
        post(c, k, s)
    }

    private fun post(c: Context, k: Logic.KidCfg, s: Logic.NfShown) {
        channel(c)
        val total = s.lines.size + s.extra
        val st = Notification.InboxStyle().setBigContentTitle(k.name)
        s.lines.forEach { st.addLine(it) }
        when {
            s.extra > 0 -> st.setSummaryText(c.resources.getQuantityString(R.plurals.nf_more, s.extra, s.extra))
            s.more -> st.setSummaryText(c.getString(R.string.nf_more_some))
        }
        val n = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(c.resources.getQuantityString(R.plurals.nf_title, total, k.name, total))
            .setContentText(s.lines.lastOrNull().orEmpty()).setStyle(st)
            .setContentIntent(Notif.openKid(c, k)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SOCIAL).build()
        c.getSystemService(NotificationManager::class.java).notify(Notif.nid(k, KEY), n)
    }
}
