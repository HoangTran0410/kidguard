package dev.kidguard.parent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject

/** Background check (~15 min via JobScheduler): SOS, new "more time" requests, events (zones, battery, ...), lost connection. Relay kids also get FCM ([Push]). */
class CheckJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread({
            try { run(applicationContext) } catch (e: Exception) { android.util.Log.w("KGParent", "check", e) }
            jobFinished(p, false)
        }, "kg-check").start()
        return true
    }
    override fun onStopJob(p: JobParameters) = false

    companion object {
        const val CHANNEL = "kg_events"
        const val SOS_CHANNEL = "kg_sos"
        private const val PERIODIC = 1
        private const val ONCE = 2

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            val cn = ComponentName(c, CheckJob::class.java)
            if (js.getPendingJob(PERIODIC) == null) {
                js.schedule(JobInfo.Builder(PERIODIC, cn).setPeriodic(15 * 60_000L).setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            }
            js.schedule(JobInfo.Builder(ONCE, cn).setOverrideDeadline(2_000).build())   // also once on every app start
        }

        fun channel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Thông báo từ máy của con", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(SOS_CHANNEL, "SOS từ con", NotificationManager.IMPORTANCE_HIGH))
        }

        fun run(c: Context) {
            if (!Store.notify(c)) return
            for (k in Logic.parseKids(Store.kids(c))) runKid(c, k)
        }

        /** The check for one kid (also called by an FCM "ev" push, [Push.handle]). */
        fun runKid(c: Context, k: Logic.KidCfg) {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            if (cm.activeNetwork == null) return   // our own phone is offline: nothing to conclude about the kid
            val now = System.currentTimeMillis()
            val seen = fetch(k)
            val canPost = canNotify(c)
            val onWifi = onWifi(cm)
            val out = synchronized(Store) {   // a push may mark the same event seen meanwhile ([Push.handle])
                val old = Logic.NotifyState.fromJson(Store.notifyState(c, k.key))
                val o = Logic.evaluate(k.name, seen, old, now, Logic.failCounts(k.via, onWifi))
                // can't show notifications: keep the events unseen so they fire once the permission is back
                if (o.events.isEmpty() || canPost) Store.setNotifyState(c, k.key, o.state.toJson())
                o
            }
            if (canPost) for (e in out.events) post(c, k, e)
        }

        private fun fetch(k: Logic.KidCfg): Logic.Seen {
            val url = k.target + (if (k.via == "lan") "/api/state" else "/c/${k.id}/state")
            val r = Net.call("GET", url, mapOf("X-KG-Token" to k.token), null, 15_000)
            if (r.status != 200) return Logic.Seen(false, emptyList(), 0)
            return try {
                val o = JSONObject(String(r.body, Charsets.UTF_8))
                val st = if (k.via == "lan") o else o.optJSONObject("state") ?: JSONObject()
                val rq = st.optJSONArray("requests")
                val list = (0 until (rq?.length() ?: 0)).mapNotNull { i ->
                    val x = rq!!.optJSONObject(i) ?: return@mapNotNull null
                    x.optString("id").takeIf { it.isNotEmpty() }?.let { it to x.optInt("min") }
                }
                val sa = st.optJSONArray("sos")
                val sos = (0 until (sa?.length() ?: 0)).mapNotNull { i ->
                    val x = sa!!.optJSONObject(i) ?: return@mapNotNull null
                    x.optString("id").takeIf { it.isNotEmpty() }?.let { it to x.optLong("t") }
                }
                val ea = st.optJSONArray("events")
                val evs = (0 until (ea?.length() ?: 0)).mapNotNull { i ->
                    val x = ea!!.optJSONObject(i) ?: return@mapNotNull null
                    val id = x.optString("id"); val text = x.optString("text")
                    if (id.isEmpty() || text.isEmpty()) null else Logic.Ev(id, text.take(120), x.optLong("t"))
                }
                Logic.Seen(true, list, if (k.via == "lan") st.optLong("t") else o.optLong("seen"), sos, evs)
            } catch (e: Exception) { Logic.Seen(false, emptyList(), 0) }
        }

        /** Wi-Fi or Ethernet is the active network (a LAN kid is only reachable from there). */
        fun onWifi(cm: ConnectivityManager): Boolean {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        /** Permission granted and notifications not turned off in system settings. */
        fun canNotify(c: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            return c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }

        fun post(c: Context, k: Logic.KidCfg, e: Logic.Event) {
            if (!canNotify(c)) return
            channel(c)
            val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(c, if (e.key.startsWith("sos:")) SOS_CHANNEL else CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle("KidGuard")
                .setContentText(e.text).setContentIntent(pi).setAutoCancel(true).build()
            c.getSystemService(NotificationManager::class.java).notify((k.key + e.key).hashCode(), n)
        }
    }
}
