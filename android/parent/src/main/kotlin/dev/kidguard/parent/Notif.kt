package dev.kidguard.parent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import org.json.JSONObject

/**
 * Posting the event notifications (from [CheckJob] and [Push]) and their actions. Rules:
 * - every PendingIntent is FLAG_IMMUTABLE; ours name their component explicitly ([AnswerReceiver], [MainActivity]);
 * - intents carry only the kid's hash (sha256 of target|id) and the request id: tokens are looked up in [Store];
 * - one notification id per event key (same as before), grouped per kid with a quiet summary.
 */
object Notif {
    const val EXTRA_KID = "kg.kid"
    private const val ACT_ANSWER = "dev.kidguard.parent.ANSWER"

    fun kidHash(k: Logic.KidCfg) = Store.sha(k.key)
    fun kidOf(c: Context, hash: String?): Logic.KidCfg? = hash?.let { h -> Logic.parseKids(Store.kids(c)).firstOrNull { kidHash(it) == h } }
    fun nid(k: Logic.KidCfg, key: String) = (k.key + key).hashCode()
    private fun group(k: Logic.KidCfg) = "kg:" + kidHash(k).take(16)
    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    /** Opens the parent page on this kid (MainActivity hands the kid to the page through the bridge, `KGApp.takeOpen()`). */
    fun openKid(c: Context, k: Logic.KidCfg): PendingIntent = PendingIntent.getActivity(c, ("open|" + k.key).hashCode(),
        Intent(c, MainActivity::class.java).putExtra(EXTRA_KID, kidHash(k)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** SOS may take the screen (full-screen intent) only where allowed: Android 14 lets the user / Play revoke it. */
    private fun canFullScreen(c: Context) = Build.VERSION.SDK_INT < 34 || nm(c).canUseFullScreenIntent()

    /** [quiet]: an update of a notification already shown (no sound / heads-up again). */
    fun post(c: Context, k: Logic.KidCfg, e: Logic.Event, quiet: Boolean = false) {
        if (!CheckJob.canNotify(c)) return
        CheckJob.channel(c)
        val sos = Act.isSos(e.key)
        val open = openKid(c, k)
        val ch = when { sos -> CheckJob.SOS_CHANNEL; e.key == "lost" -> CheckJob.OFFLINE_CHANNEL; Act.reqId(e.key) != null || e.important || e.key == "acc" -> CheckJob.REQ_CHANNEL; else -> CheckJob.INFO_CHANNEL }
        val b = Notification.Builder(c, ch).setSmallIcon(R.drawable.ic_notif).setContentTitle("KidGuard")
            .setContentText(e.text).setStyle(Notification.BigTextStyle().bigText(e.text)).setContentIntent(open).setAutoCancel(true)
            .setGroup(group(k)).setOnlyAlertOnce(quiet)
        if (sos) {
            b.setCategory(Notification.CATEGORY_ALARM)
            if (canFullScreen(c) && !quiet) b.setFullScreenIntent(open, true)
        }
        for (a in Act.actionsFor(e.key, e.min, app = e.app)) action(c, k, e, a)?.let { b.addAction(it) }
        nm(c).notify(nid(k, e.key), b.build())
        summary(c, k)
    }

    private fun action(c: Context, k: Logic.KidCfg, e: Logic.Event, a: Act.A): Notification.Action? {
        val icon = Icon.createWithResource(c, R.drawable.ic_notif)
        val rq = (k.key + e.key + a.type + a.min).hashCode()
        return when (a.type) {
            Act.GRANT, Act.DECLINE, Act.APPROVE -> {
                val req = Act.reqId(e.key) ?: return null
                val i = Intent(c, AnswerReceiver::class.java).setAction(ACT_ANSWER)
                    .putExtra(EXTRA_KID, kidHash(k)).putExtra("req", req).putExtra("ok", a.type != Act.DECLINE).putExtra("min", a.min).putExtra("app", a.type == Act.APPROVE)
                val pi = PendingIntent.getBroadcast(c, rq, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val label = when (a.type) { Act.GRANT -> c.getString(R.string.act_grant, a.min); Act.APPROVE -> c.getString(R.string.act_approve); else -> c.getString(R.string.act_decline) }
                Notification.Action.Builder(icon, label, pi).build()
            }
            Act.MAP -> {
                // a known fix: the maps app at that point; else the page (its SOS banner has "Xem vị trí" and the map tab)
                val pi = e.loc?.let { l -> PendingIntent.getActivity(c, rq, Intent(Intent.ACTION_VIEW, Uri.parse(Act.geoUri(l, k.name))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) } ?: openKid(c, k)
                Notification.Action.Builder(icon, c.getString(R.string.act_map), pi).build()
            }
            else -> null   // CALL: no phone number is known anywhere yet (kid list / state carry none)
        }
    }

    /** One summary per kid so several kids' alerts stack per kid instead of one long list. Silent: the children alert. */
    private fun summary(c: Context, k: Logic.KidCfg) {
        val n = Notification.Builder(c, CheckJob.INFO_CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(k.name).setSubText(k.name)
            .setGroup(group(k)).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentIntent(openKid(c, k)).setAutoCancel(true).build()
        nm(c).notify(nid(k, "summary"), n)
    }

    /** Replaces a request notification with a plain line (sending / done / failed). [fail]: the tap opens the page. */
    fun result(c: Context, k: Logic.KidCfg, req: String, text: String) {
        if (!CheckJob.canNotify(c)) return
        val n = Notification.Builder(c, CheckJob.REQ_CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(k.name).setContentText(text)
            .setContentIntent(openKid(c, k)).setAutoCancel(true).setOnlyAlertOnce(true).setGroup(group(k)).build()
        nm(c).notify(nid(k, "req:$req"), n)
    }

    /** Dismisses one notification of this kid (e.g. "offline since" once the phone is back). */
    fun cancel(c: Context, k: Logic.KidCfg, key: String) { runCatching { nm(c).cancel(nid(k, key)) } }

    /** Settings test (page "do alerts arrive?" card): one notification on the request channel. False = the OS would not show it. */
    fun test(c: Context): Boolean {
        if (!CheckJob.canNotify(c)) return false
        CheckJob.channel(c)
        val pi = PendingIntent.getActivity(c, 11, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CheckJob.REQ_CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(c.getString(R.string.test_title))
            .setContentText(c.getString(R.string.test_text)).setContentIntent(pi).setAutoCancel(true).build()
        nm(c).notify(0x6b69, n)
        return true
    }

    /** True while this notification is still in the shade (a late location must not bring back a dismissed SOS). */
    fun showing(c: Context, k: Logic.KidCfg, key: String) = runCatching { nm(c).activeNotifications.any { it.id == nid(k, key) } }.getOrDefault(false)

    /**
     * Answers a request: rules PUT (the grant rule or the app approval) + `answer` cmd, same as the page's request card.
     * Transport order is the page's `call()`: the kid's own target, and when that fails (a LAN kid and the parent is away)
     * the relay. [bonusDone]: an earlier run already saved the rules, so only the `answer` is sent (never the minutes twice).
     */
    fun answer(k: Logic.KidCfg, req: String, ok: Boolean, min: Int, bonusDone: Boolean = false, onBonus: () -> Unit = {}): Act.Res {
        val lan = k.via == "lan"
        val routes = ArrayList<Triple<String, String, String>>()   // base, token, via
        routes.add(Triple(k.target + (if (lan) "/api" else "/c/${k.id}"), k.token, k.via))
        if (lan && k.hasRelay) routes.add(Triple(k.relay + "/c/${k.relayId}", k.relayToken, "relay"))
        var body: JSONObject? = null
        var route = routes[0]
        for (r in routes) {
            val s = Net.call("GET", r.first + "/state", mapOf("X-KG-Token" to r.second, "Content-Type" to "application/json"), null, 15_000)
            if (s.status != 200) continue
            body = runCatching { JSONObject(String(s.body, Charsets.UTF_8)) }.getOrNull() ?: continue
            route = r; break
        }
        if (body == null) return Act.Res.FAIL
        val (base, token, via) = route
        val h = mapOf("X-KG-Token" to token, "Content-Type" to "application/json")
        val rq = Act.requestOf(body, via, req) ?: return Act.Res.ALREADY   // answered on the page / another parent phone
        if (ok && !bonusDone) {
            val st = Act.stateOf(body, via)
            val day = st?.optString("day").orEmpty()
            val rules = Act.pickRules(body, via)
            if (rules == null) return Act.Res.FAIL
            val next = if (rq.optString("kind") == "app") Logic.approveAppRules(rules, rq.optString("pkg").takeIf { it.isNotEmpty() } ?: return Act.Res.FAIL)
            else { if (day.isEmpty()) return Act.Res.FAIL; Logic.grantRules(rules, Act.nowWhy(body, via), day, System.currentTimeMillis(), min) }
            val p = Net.call("PUT", "$base/rules", h, next.toString().toByteArray(), 15_000)
            if (p.status != 200) return Act.Res.FAIL
            onBonus()
        }
        val r = Net.call("POST", "$base/cmd", h, Act.answerCmd(req, ok, min).toString().toByteArray(), 15_000)
        val res = Act.cmdResult(r.status, String(r.body, Charsets.UTF_8))
        return if (ok && res == Act.Res.FAIL) Act.Res.HALF else res
    }

    fun resultText(c: Context, res: Act.Res, ok: Boolean, min: Int, app: Boolean = false): String = when {
        app && ok -> c.getString(when (res) { Act.Res.OK -> R.string.ans_approved; Act.Res.QUEUED -> R.string.ans_approved_queued; Act.Res.HALF -> R.string.ans_half_app; Act.Res.ALREADY -> R.string.ans_already; Act.Res.FAIL -> R.string.ans_failed })
        else -> when (res) {
            Act.Res.OK -> if (ok) c.resources.getQuantityString(R.plurals.ans_granted, min, min) else c.getString(R.string.ans_declined)
            Act.Res.QUEUED -> if (ok) c.resources.getQuantityString(R.plurals.ans_queued, min, min) else c.getString(R.string.ans_declined)
            Act.Res.HALF -> c.resources.getQuantityString(R.plurals.ans_half, min, min)
            Act.Res.ALREADY -> c.getString(R.string.ans_already)
            Act.Res.FAIL -> c.getString(R.string.ans_failed)
        }
    }
}

/** Not exported; reached only through our own immutable PendingIntents. Hands the network work to [AnswerJob]. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = ctx.applicationContext   // App: notification text in the chosen language
        val k = Notif.kidOf(c, i.getStringExtra(Notif.EXTRA_KID)) ?: return
        val req = i.getStringExtra("req")?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return
        val ok = i.getBooleanExtra("ok", false)
        val app = i.getBooleanExtra("app", false)
        val min = i.getIntExtra("min", 0).takeIf { !ok || app || it in 1..600 } ?: return
        if (!Store.takeAnswer(c, Notif.kidHash(k), req)) return   // a second tap / an old copy: the first one is answering it
        Notif.result(c, k, req, c.getString(R.string.ans_sending))   // drops the buttons at once
        val x = PersistableBundle().apply { putString("kid", Notif.kidHash(k)); putString("req", req); putBoolean("ok", ok); putInt("min", min); putBoolean("app", app) }
        val js = c.getSystemService(JobScheduler::class.java)
        val id = 0x4b000000 or (Notif.nid(k, "req:$req") and 0xffffff)
        val cn = ComponentName(c, AnswerJob::class.java)
        fun job(fast: Boolean) = JobInfo.Builder(id, cn).setExtras(x).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).apply {
            if (fast && Build.VERSION.SDK_INT >= 31) setExpedited(true) else setOverrideDeadline(0)
        }.build()
        val r = runCatching { js.schedule(job(true)) }.getOrDefault(JobScheduler.RESULT_FAILURE)
        if (r != JobScheduler.RESULT_SUCCESS && runCatching { js.schedule(job(false)) }.getOrDefault(JobScheduler.RESULT_FAILURE) != JobScheduler.RESULT_SUCCESS) {
            Store.dropAnswer(c, Notif.kidHash(k), req)
            Notif.result(c, k, req, c.getString(R.string.ans_failed))
        }
    }
}

/** One answer: state check, rules PUT (grant / app approval), `answer` command, then the result line in place of the request notification. */
class AnswerJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        val c = applicationContext
        val x = p.extras
        Thread({
            var again = false
            val k = Notif.kidOf(c, x.getString("kid"))
            val req = x.getString("req").orEmpty()
            if (k != null && req.isNotEmpty()) {
                val ok = x.getBoolean("ok"); val min = x.getInt("min"); val app = x.getBoolean("app")
                val kh = Notif.kidHash(k); val bonus = "bonus:$req"
                val had = ok && Store.hasAnswer(c, kh, bonus)
                val res = runCatching { Notif.answer(k, req, ok, min, had) { Store.takeAnswer(c, kh, bonus) } }
                    .getOrElse { if (ok && Store.hasAnswer(c, kh, bonus)) Act.Res.HALF else Act.Res.FAIL }
                    .let { if (it == Act.Res.FAIL && had) Act.Res.HALF else it }
                if (res == Act.Res.FAIL) Store.dropAnswer(c, kh, req)   // the page can still answer it; a new tap too
                Notif.result(c, k, req, Notif.resultText(c, res, ok, min, app))
                again = res == Act.Res.HALF   // minutes are saved: only the `answer` is sent again (JobScheduler backoff)
            }
            jobFinished(p, again)
        }, "kg-answer").start()
        return true
    }
    override fun onStopJob(p: JobParameters) = false
}
