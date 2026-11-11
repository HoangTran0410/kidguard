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
        val b = Notification.Builder(c, if (sos) CheckJob.SOS_CHANNEL else CheckJob.CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle("KidGuard")
            .setContentText(e.text).setStyle(Notification.BigTextStyle().bigText(e.text)).setContentIntent(open).setAutoCancel(true)
            .setGroup(group(k)).setOnlyAlertOnce(quiet)
        if (sos) {
            b.setCategory(Notification.CATEGORY_ALARM)
            if (canFullScreen(c) && !quiet) b.setFullScreenIntent(open, true)
        }
        for (a in Act.actionsFor(e.key, e.min)) action(c, k, e, a)?.let { b.addAction(it) }
        nm(c).notify(nid(k, e.key), b.build())
        summary(c, k)
    }

    private fun action(c: Context, k: Logic.KidCfg, e: Logic.Event, a: Act.A): Notification.Action? {
        val icon = Icon.createWithResource(c, R.drawable.ic_notif)
        val rq = (k.key + e.key + a.type + a.min).hashCode()
        return when (a.type) {
            Act.GRANT, Act.DECLINE -> {
                val req = Act.reqId(e.key) ?: return null
                val i = Intent(c, AnswerReceiver::class.java).setAction(ACT_ANSWER)
                    .putExtra(EXTRA_KID, kidHash(k)).putExtra("req", req).putExtra("ok", a.type == Act.GRANT).putExtra("min", a.min)
                val pi = PendingIntent.getBroadcast(c, rq, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Notification.Action.Builder(icon, if (a.type == Act.GRANT) c.getString(R.string.act_grant, a.min) else c.getString(R.string.act_decline), pi).build()
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
        val n = Notification.Builder(c, CheckJob.CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(k.name).setSubText(k.name)
            .setGroup(group(k)).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentIntent(openKid(c, k)).setAutoCancel(true).build()
        nm(c).notify(nid(k, "summary"), n)
    }

    /** Replaces a request notification with a plain line (sending / done / failed). [fail]: the tap opens the page. */
    fun result(c: Context, k: Logic.KidCfg, req: String, text: String) {
        if (!CheckJob.canNotify(c)) return
        val n = Notification.Builder(c, CheckJob.CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(k.name).setContentText(text)
            .setContentIntent(openKid(c, k)).setAutoCancel(true).setOnlyAlertOnce(true).setGroup(group(k)).build()
        nm(c).notify(nid(k, "req:$req"), n)
    }

    /** True while this notification is still in the shade (a late location must not bring back a dismissed SOS). */
    fun showing(c: Context, k: Logic.KidCfg, key: String) = runCatching { nm(c).activeNotifications.any { it.id == nid(k, key) } }.getOrDefault(false)

    /**
     * Answers a request: (bonus rules PUT +) `answer` cmd over the kid's own transport, same as the page's request card.
     * [bonusDone]: an earlier run already saved the bonus, so only the `answer` is sent (never the minutes twice).
     */
    fun answer(k: Logic.KidCfg, req: String, ok: Boolean, min: Int, bonusDone: Boolean = false, onBonus: () -> Unit = {}): Act.Res {
        val base = k.target + (if (k.via == "lan") "/api" else "/c/${k.id}")
        val h = mapOf("X-KG-Token" to k.token, "Content-Type" to "application/json")
        val s = Net.call("GET", "$base/state", h, null, 15_000)
        if (s.status != 200) return Act.Res.FAIL
        val body = runCatching { JSONObject(String(s.body, Charsets.UTF_8)) }.getOrNull() ?: return Act.Res.FAIL
        if (!Act.hasRequest(body, k.via, req)) return Act.Res.ALREADY   // answered on the page / another parent phone
        if (ok && !bonusDone) {
            val day = Act.stateOf(body, k.via)?.optString("day").orEmpty()
            val rules = Act.pickRules(body, k.via)
            if (day.isEmpty() || rules == null) return Act.Res.FAIL
            val p = Net.call("PUT", "$base/rules", h, Act.bonusRules(rules, day, min).toString().toByteArray(), 15_000)
            if (p.status != 200) return Act.Res.FAIL
            onBonus()
        }
        val r = Net.call("POST", "$base/cmd", h, Act.answerCmd(req, ok, min).toString().toByteArray(), 15_000)
        val res = Act.cmdResult(r.status, String(r.body, Charsets.UTF_8))
        return if (ok && res == Act.Res.FAIL) Act.Res.HALF else res
    }

    fun resultText(c: Context, res: Act.Res, ok: Boolean, min: Int): String = when (res) {
        Act.Res.OK -> if (ok) c.resources.getQuantityString(R.plurals.ans_granted, min, min) else c.getString(R.string.ans_declined)
        Act.Res.QUEUED -> if (ok) c.resources.getQuantityString(R.plurals.ans_queued, min, min) else c.getString(R.string.ans_declined)
        Act.Res.HALF -> c.resources.getQuantityString(R.plurals.ans_half, min, min)
        Act.Res.ALREADY -> c.getString(R.string.ans_already)
        Act.Res.FAIL -> c.getString(R.string.ans_failed)
    }
}

/** Not exported; reached only through our own immutable PendingIntents. Hands the network work to [AnswerJob]. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = ctx.applicationContext   // App: notification text in the chosen language
        val k = Notif.kidOf(c, i.getStringExtra(Notif.EXTRA_KID)) ?: return
        val req = i.getStringExtra("req")?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return
        val ok = i.getBooleanExtra("ok", false)
        val min = i.getIntExtra("min", 0).takeIf { !ok || it in 1..600 } ?: return
        if (!Store.takeAnswer(c, Notif.kidHash(k), req)) return   // a second tap / an old copy: the first one is answering it
        Notif.result(c, k, req, c.getString(R.string.ans_sending))   // drops the buttons at once
        val x = PersistableBundle().apply { putString("kid", Notif.kidHash(k)); putString("req", req); putBoolean("ok", ok); putInt("min", min) }
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

/** One answer: state check, bonus PUT, `answer` command, then the result line in place of the request notification. */
class AnswerJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        val c = applicationContext
        val x = p.extras
        Thread({
            var again = false
            val k = Notif.kidOf(c, x.getString("kid"))
            val req = x.getString("req").orEmpty()
            if (k != null && req.isNotEmpty()) {
                val ok = x.getBoolean("ok"); val min = x.getInt("min")
                val kh = Notif.kidHash(k); val bonus = "bonus:$req"
                val had = ok && Store.hasAnswer(c, kh, bonus)
                val res = runCatching { Notif.answer(k, req, ok, min, had) { Store.takeAnswer(c, kh, bonus) } }
                    .getOrElse { if (ok && Store.hasAnswer(c, kh, bonus)) Act.Res.HALF else Act.Res.FAIL }
                    .let { if (it == Act.Res.FAIL && had) Act.Res.HALF else it }
                if (res == Act.Res.FAIL) Store.dropAnswer(c, kh, req)   // the page can still answer it; a new tap too
                Notif.result(c, k, req, Notif.resultText(c, res, ok, min))
                again = res == Act.Res.HALF   // minutes are saved: only the `answer` is sent again (JobScheduler backoff)
            }
            jobFinished(p, again)
        }, "kg-answer").start()
        return true
    }
    override fun onStopJob(p: JobParameters) = false
}
