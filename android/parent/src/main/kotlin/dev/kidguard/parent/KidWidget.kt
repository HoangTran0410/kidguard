package dev.kidguard.parent

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * Home-screen widget: per kid (max 3) name, time left / Locked / Paused / Bedtime, last seen, buttons Lock and +15 min; a tap on the
 * row opens the app on that kid. Data = the last state [CheckJob] fetched (every ~15 min, push, app start), kept per kid hash in
 * [Store.widgetData] (no tokens: only [WidgetLogic.Snap]). Buttons run in the background like the notification buttons ([Notif.answer]
 * routes) and show "Sending... / Sent / Not sent" in the row for a few seconds ([WidgetLogic.Fb]).
 */
class KidWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        render(c, m, ids)
        // freshly added (or the phone restarted): get real numbers now instead of waiting for the next check
        val old = Store.widgetData(c).let { WidgetLogic.parseAll(it) }.values.maxOfOrNull { it.at } ?: 0L
        if (System.currentTimeMillis() - old > 5 * 60_000L) async { refreshAll(c) }
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, intArrayOf(id))

    override fun onReceive(c: Context, i: Intent) {
        val ctx = c.applicationContext
        when (i.action) {
            ACT_LOCK, ACT_PLUS -> press(ctx, i)
            ACT_REDRAW -> update(ctx)
            else -> super.onReceive(c, i)
        }
    }

    /** Runs [f] on a thread and keeps this broadcast alive (up to ~10 s of system budget) until done. */
    private fun async(f: () -> Unit) {
        val pr = goAsync()
        Thread({ try { f() } catch (e: Exception) { android.util.Log.w("KGParent", "widget", e) } finally { pr.finish() } }, "kg-widget").start()
    }

    private fun press(c: Context, i: Intent) {
        val k = Notif.kidOf(c, i.getStringExtra(Notif.EXTRA_KID)) ?: return
        val h = Notif.kidHash(k)
        val plus = i.action == ACT_PLUS
        if (!setFb(c, h, WidgetLogic.Fb("sending", System.currentTimeMillis() + WidgetLogic.SENDING_MS), onlyIfFree = true)) return   // second tap while sending
        update(c)
        async {
            val res = try { if (plus) Net2.plus(k, WidgetLogic.PLUS_MIN) else Net2.lock(k) } catch (e: Exception) { Act.Res.FAIL }
            val ok = res == Act.Res.OK || res == Act.Res.QUEUED
            val until = System.currentTimeMillis() + WidgetLogic.RESULT_MS
            setFb(c, h, WidgetLogic.Fb(if (ok) "sent" else "fail", until))
            update(c)
            c.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, until + 500,
                PendingIntent.getBroadcast(c, 0x7701, Intent(c, KidWidget::class.java).setAction(ACT_REDRAW), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            if (ok) refreshKid(c, k)   // the new numbers (LAN: at once; relay: the phone has not synced yet, the next check shows them)
        }
    }

    /** The routes of [Notif.answer]: the kid's own target, then (LAN kid with relay) the relay. */
    private object Net2 {
        private fun routes(k: Logic.KidCfg): List<Triple<String, String, String>> {   // base, token, via
            val lan = k.via == "lan"
            return listOf(Triple(k.target + (if (lan) "/api" else "/c/${k.id}"), k.token, k.via)) +
                (if (lan && k.hasRelay) listOf(Triple(k.relay + "/c/${k.relayId}", k.relayToken, "relay")) else emptyList())
        }
        private fun h(token: String) = mapOf("X-KG-Token" to token, "Content-Type" to "application/json")

        fun lock(k: Logic.KidCfg): Act.Res {
            val body = JSONObject().put("type", "lock").toString().toByteArray()
            for ((base, token, _) in routes(k)) {
                val r = Net.call("POST", "$base/cmd", h(token), body, 8_000)
                if (r.status != null) return Act.cmdResult(r.status, String(r.body, Charsets.UTF_8))
            }
            return Act.Res.FAIL
        }

        /** Today's limit + [min]: GET state, edit the whole rules object, PUT it (the same grant rule as a request answer, [Logic.grantRules]). */
        fun plus(k: Logic.KidCfg, min: Int): Act.Res {
            for ((base, token, via) in routes(k)) {
                val s = Net.call("GET", "$base/state", h(token), null, 8_000)
                if (s.status == null) continue
                if (s.status != 200) return Act.Res.FAIL
                val body = runCatching { JSONObject(String(s.body, Charsets.UTF_8)) }.getOrNull() ?: return Act.Res.FAIL
                val day = Act.stateOf(body, via)?.optString("day").orEmpty()
                val rules = Act.pickRules(body, via)
                if (day.isEmpty() || rules == null) return Act.Res.FAIL
                val next = Logic.grantRules(rules, Act.nowWhy(body, via), day, System.currentTimeMillis(), min)
                val p = Net.call("PUT", "$base/rules", h(token), next.toString().toByteArray(), 8_000)
                if (p.status == null) continue
                return if (p.status == 200) Act.Res.OK else Act.Res.FAIL
            }
            return Act.Res.FAIL
        }
    }

    companion object {
        private const val ACT_LOCK = "dev.kidguard.parent.WIDGET_LOCK"
        private const val ACT_PLUS = "dev.kidguard.parent.WIDGET_PLUS"
        private const val ACT_REDRAW = "dev.kidguard.parent.WIDGET_REDRAW"

        private fun ids(c: Context): IntArray = AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, KidWidget::class.java))
        /** At least one widget is on a home screen. */
        fun active(c: Context): Boolean = ids(c).isNotEmpty()

        /** Redraws every widget from the stored snapshots. */
        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c); val ids = ids(c)
            if (ids.isNotEmpty()) render(c, m, ids)
        }

        /** Keeps what [s] says about [k] and redraws. Called by [CheckJob] for every successful fetch. */
        fun save(c: Context, k: Logic.KidCfg, s: Logic.Seen) {
            val snap = s.snap ?: return
            if (!s.fetchOk || !active(c)) return
            synchronized(KidWidget::class.java) {
                val all = WidgetLogic.parseAll(Store.widgetData(c)).toMutableMap()
                all[Notif.kidHash(k)] = snap
                Store.setWidgetData(c, WidgetLogic.toJsonAll(all, Logic.parseKids(Store.kids(c)).map { Notif.kidHash(it) }.toSet()))
            }
            update(c)
        }

        private fun refreshKid(c: Context, k: Logic.KidCfg) { CheckJob.fetch(k)?.let { save(c, k, it) } }
        /** Fetches the (max 3) shown kids; for the check when notifications are off and for a new widget. */
        fun refreshAll(c: Context) { for (k in Logic.parseKids(Store.kids(c)).take(WidgetLogic.MAX_KIDS)) refreshKid(c, k) }

        private fun setFb(c: Context, h: String, f: WidgetLogic.Fb, onlyIfFree: Boolean = false): Boolean = synchronized(KidWidget::class.java) {
            val now = System.currentTimeMillis()
            val m = WidgetLogic.fbParse(Store.widgetFb(c)).toMutableMap()
            if (onlyIfFree && WidgetLogic.busy(m[h], now)) return false
            m[h] = f
            Store.setWidgetFb(c, WidgetLogic.fbJson(m, now))
            true
        }

        private fun render(c: Context, m: AppWidgetManager, ids: IntArray) {
            val kids = Logic.parseKids(Store.kids(c)).take(WidgetLogic.MAX_KIDS)
            val snaps = WidgetLogic.parseAll(Store.widgetData(c))
            val fbs = WidgetLogic.fbParse(Store.widgetFb(c))
            val now = System.currentTimeMillis()
            for (id in ids) {
                val o = m.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val hgt = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                // narrow or short: drop the "seen" line so the name + status + buttons still fit
                val compact = w < 200 || hgt / maxOf(1, kids.size) < 62
                val root = RemoteViews(c.packageName, R.layout.widget)
                root.removeAllViews(R.id.w_rows)
                if (kids.isEmpty()) {
                    root.setViewVisibility(R.id.w_empty, android.view.View.VISIBLE)
                    root.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 0x7702, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    root.setViewVisibility(R.id.w_empty, android.view.View.GONE)
                    for (k in kids) root.addView(R.id.w_rows, row(c, k, snaps[Notif.kidHash(k)], fbs[Notif.kidHash(k)], now, compact))
                }
                m.updateAppWidget(id, root)
            }
        }

        private fun row(c: Context, k: Logic.KidCfg, s: WidgetLogic.Snap?, fb: WidgetLogic.Fb?, now: Long, compact: Boolean): RemoteViews {
            val r = RemoteViews(c.packageName, R.layout.widget_row)
            val st = WidgetLogic.status(s)
            r.setTextViewText(R.id.w_name, k.name)
            r.setTextViewText(R.id.w_status, when (st.kind) {
                WidgetLogic.Kind.LEFT -> c.getString(R.string.w_left, WidgetLogic.duration(st.min))
                WidgetLogic.Kind.NO_LIMIT -> c.getString(R.string.w_nolimit)
                WidgetLogic.Kind.LOCKED -> c.getString(R.string.w_locked)
                WidgetLogic.Kind.PAUSED -> c.getString(R.string.w_paused)
                WidgetLogic.Kind.BEDTIME -> c.getString(R.string.w_bedtime)
                WidgetLogic.Kind.UNKNOWN -> c.getString(R.string.w_nodata)
            })
            val warn = st.kind == WidgetLogic.Kind.LOCKED || st.kind == WidgetLogic.Kind.PAUSED || st.kind == WidgetLogic.Kind.BEDTIME
            r.setTextColor(R.id.w_status, c.getColor(if (warn) R.color.w_warn else R.color.w_text))
            val fbOn = fb != null && fb.until > now
            val line = if (fbOn) when (fb!!.kind) {
                "sending" -> c.getString(R.string.ans_sending)
                "sent" -> c.getString(R.string.w_sent)
                else -> c.getString(R.string.w_failed)
            } else seenText(c, WidgetLogic.ago(s?.seenMs ?: 0, now))
            r.setTextViewText(R.id.w_seen, line)
            // feedback must stay visible even in the compact layout
            r.setViewVisibility(R.id.w_seen, if (compact && !fbOn) android.view.View.GONE else android.view.View.VISIBLE)
            r.setTextViewText(R.id.w_plus, c.getString(R.string.w_plus, WidgetLogic.PLUS_MIN))
            val h = Notif.kidHash(k)
            r.setOnClickPendingIntent(R.id.w_row, Notif.openKid(c, k))
            r.setOnClickPendingIntent(R.id.w_lock, button(c, ACT_LOCK, h))
            r.setOnClickPendingIntent(R.id.w_plus, button(c, ACT_PLUS, h))
            return r
        }

        private fun seenText(c: Context, a: WidgetLogic.Ago): String = when (a.unit) {
            WidgetLogic.Unit.NEVER -> c.getString(R.string.w_seen_never)
            WidgetLogic.Unit.NOW -> c.getString(R.string.w_seen_now)
            WidgetLogic.Unit.MIN -> c.resources.getQuantityString(R.plurals.w_seen_min, a.n, a.n)
            WidgetLogic.Unit.HOUR -> c.resources.getQuantityString(R.plurals.w_seen_hour, a.n, a.n)
            WidgetLogic.Unit.DAY -> c.resources.getQuantityString(R.plurals.w_seen_day, a.n, a.n)
        }

        /** Explicit, immutable, one request code per (action, kid): only this app can fire it. */
        private fun button(c: Context, action: String, kidHash: String): PendingIntent = PendingIntent.getBroadcast(c, (action + kidHash).hashCode(),
            Intent(c, KidWidget::class.java).setAction(action).putExtra(Notif.EXTRA_KID, kidHash), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
