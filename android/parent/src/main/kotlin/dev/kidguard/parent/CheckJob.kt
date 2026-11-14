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
        /** Channels (C7): sos = high, requests (more time + tamper / lost / released) = default, info = low, offline = low. */
        const val SOS_CHANNEL = "kg_sos"
        const val REQ_CHANNEL = "kg_requests"
        const val INFO_CHANNEL = "kg_info"
        const val OFFLINE_CHANNEL = "kg_offline"
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
            nm.deleteNotificationChannel("kg_events")   // before 0.9.16: one default-importance channel for everything
            nm.createNotificationChannel(NotificationChannel(REQ_CHANNEL, c.getString(R.string.ch_requests), NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(INFO_CHANNEL, c.getString(R.string.ch_info), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(OFFLINE_CHANNEL, c.getString(R.string.ch_offline), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(SOS_CHANNEL, c.getString(R.string.ch_sos), NotificationManager.IMPORTANCE_HIGH))
        }

        fun run(c: Context) {
            runCatching { checkUpdate(c) }.onFailure { android.util.Log.w("KGParent", "update check", it) }
            Store.setCheckAt(c, System.currentTimeMillis())
            // the worker drops push tokens after 30 quiet days and a failed registration was never retried: once a day, again
            if (Store.notify(c) && System.currentTimeMillis() - Store.pushAt(c) >= 24 * 3_600_000L) Push.sync(c, force = true)
            if (!Store.notify(c)) {   // no alerts wanted, but a widget still needs fresh numbers
                if (KidWidget.active(c)) KidWidget.refreshAll(c)
                return
            }
            for (k in Logic.parseKids(Store.kids(c))) runKid(c, k)
            KidWidget.update(c)   // redraw even when nothing could be fetched: "seen N min ago" ages
        }

        /** The check for one kid (also called by an FCM "ev" push, [Push.handle]). */
        fun runKid(c: Context, k: Logic.KidCfg) {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            if (cm.activeNetwork == null) return   // our own phone is offline: nothing to conclude about the kid
            val now = System.currentTimeMillis()
            val seen = fetch(k)
            if (seen == null) {   // 401/403: unpaired (not just offline): tell the parent once instead of going silent
                if (!Store.unpairedSeen(c, k.key) && canNotify(c)) { Store.setUnpairedSeen(c, k.key, true); postUnpaired(c, k) }
                return
            }
            if (Store.unpairedSeen(c, k.key)) Store.setUnpairedSeen(c, k.key, false)
            KidWidget.save(c, k, seen)
            val canPost = canNotify(c)
            val onWifi = onWifi(cm)
            val out = synchronized(Store) {   // a push may mark the same event seen meanwhile ([Push.handle])
                val old = Logic.NotifyState.fromJson(Store.notifyState(c, k.key))
                val o = Logic.evaluate(k.name, seen, old, now, Logic.failCounts(k.via, onWifi, k.hasRelay, Logic.onKidSubnet(k.target, ownV4(cm))), tr = ResTr(c))
                // can't show notifications: keep the events unseen so they fire once the permission is back
                if (o.events.isEmpty() || canPost) Store.setNotifyState(c, k.key, o.state.toJson())
                o
            }
            if (canPost) for (e in out.events) post(c, k, e)
            for (key in out.clear) Notif.cancel(c, k, key)
            runCatching { digest(c, k, seen, now) }.onFailure { android.util.Log.w("KGParent", "digest", it) }
        }

        /** Evening summary (setting in the app's settings): once a day after the chosen time, plus the weekly one on Sunday. Silent info channel. */
        private fun digest(c: Context, k: Logic.KidCfg, seen: Logic.Seen, now: Long) {
            val inp = seen.digest ?: return
            val day = dev.kidguard.Digest.dayKey(now, java.util.TimeZone.getDefault())
            if (!canNotify(c) || !dev.kidguard.Digest.due(Store.digestOn(c), Store.digestAt(c), Store.digestDay(c, k.key), now)) return
            Store.setDigestDay(c, k.key, day)
            val lang = if (c.resources.configuration.locales[0].language == "vi") "vi" else "en"
            val msgs = dev.kidguard.Digest.messages(dev.kidguard.Digest.Input(k.name, inp.usedMs, inp.limitMin, inp.top, inp.history, inp.days, inp.bedKept), lang, now)
            channel(c)
            msgs.forEachIndexed { i, m -> Notif.postDigest(c, k, m, i) }
        }

        private fun postUnpaired(c: Context, k: Logic.KidCfg) {
            channel(c)
            val pi = PendingIntent.getActivity(c, 10, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            // a relay kid is not paired by a code on the phone: it needs a new link
            val text = c.getString(if (k.via == "relay") R.string.ev_unpaired_relay else R.string.ev_unpaired, k.name)
            val n = android.app.Notification.Builder(c, REQ_CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle("KidGuard")
                .setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
            c.getSystemService(NotificationManager::class.java).notify(0x6b68 + (k.key.hashCode() and 0xfff), n)
        }

        /**
         * null = really unpaired. A LAN 401/403 counts only when /api/hello (no token) still names this kid: the saved IP may now
         * belong to another KidGuard phone, which rejects our token. A LAN kid that is unreachable falls back to its relay.
         */
        fun fetch(k: Logic.KidCfg): Logic.Seen? {
            val r = fetchOne(k.target, k.via == "lan", k.id, k.token)
            if (k.via != "lan" || (r != null && r.fetchOk)) return r
            if (r == null && sameKid(k)) return null
            if (k.hasRelay) fetchOne(k.relay!!, false, k.relayId, k.relayToken)?.let { if (it.fetchOk) return it }
            return Logic.Seen(false, emptyList(), 0)
        }

        private fun sameKid(k: Logic.KidCfg): Boolean {
            val h = Net.call("GET", k.target + "/api/hello", emptyMap(), null, 6_000)
            if (h.status != 200) return false
            return try { JSONObject(String(h.body, Charsets.UTF_8)).optString("id") == k.id } catch (e: Exception) { false }
        }

        private fun fetchOne(target: String, lan: Boolean, id: String, token: String): Logic.Seen? {
            val url = target + (if (lan) "/api/state" else "/c/$id/state")
            val r = Net.call("GET", url, mapOf("X-KG-Token" to token), null, 15_000)
            if (r.status == 401 || r.status == 403) return null
            if (r.status != 200) return Logic.Seen(false, emptyList(), 0)
            return try {
                val o = JSONObject(String(r.body, Charsets.UTF_8))
                val st = if (lan) o else o.optJSONObject("state") ?: JSONObject()
                val rq = st.optJSONArray("requests")
                val list = (0 until (rq?.length() ?: 0)).mapNotNull { i ->
                    val x = rq!!.optJSONObject(i) ?: return@mapNotNull null
                    x.optString("id").takeIf { it.isNotEmpty() }?.let { it to x.optInt("min") }
                }
                val appReqs = (0 until (rq?.length() ?: 0)).mapNotNull { i ->
                    val x = rq!!.optJSONObject(i) ?: return@mapNotNull null
                    if (x.optString("kind") != "app" || x.optString("id").isEmpty()) null
                    else x.optString("id") to x.optString("app").ifEmpty { x.optString("pkg") }.take(60)
                }.toMap()
                val sa = st.optJSONArray("sos")
                val sos = (0 until (sa?.length() ?: 0)).mapNotNull { i ->
                    val x = sa!!.optJSONObject(i) ?: return@mapNotNull null
                    x.optString("id").takeIf { it.isNotEmpty() }?.let { it to x.optLong("t") }
                }
                val ea = st.optJSONArray("events")
                val evs = (0 until (ea?.length() ?: 0)).mapNotNull { i ->
                    val x = ea!!.optJSONObject(i) ?: return@mapNotNull null
                    val id = x.optString("id"); val text = x.optString("text")
                    if (id.isEmpty() || text.isEmpty()) null else Logic.Ev(id, text.take(120), x.optLong("t"), x.optString("kind"), Act.locOf(JSONObject().put("location", x.optJSONObject("loc"))))
                }
                val accOff = st.optString("mode") == "basic" && st.optJSONObject("basic")?.optBoolean("acc", true) == false
                Logic.Seen(true, list, if (lan) st.optLong("t") else o.optLong("seen"), sos, evs, accOff, Act.locOf(st), appReqs, st.optJSONObject("now")?.optString("why") == "bedtime",
                    if (st.length() == 0) null   // relay kid that never synced: nothing to show
                    else WidgetLogic.Snap(st.optJSONObject("now")?.optString("why").orEmpty(), st.optInt("limitMin"), if (st.has("leftMs")) st.optLong("leftMs") else -1,
                        if (lan) st.optLong("t") else o.optLong("seen"), System.currentTimeMillis()),
                    runCatching { dev.kidguard.Digest.fromState(st, "") }.getOrNull())
            } catch (e: Exception) { Logic.Seen(false, emptyList(), 0) }
        }

        /** (address, prefix length) of this phone's IPv4 addresses on the active network. */
        private fun ownV4(cm: ConnectivityManager): List<Pair<String, Int>> {
            val net = cm.activeNetwork ?: return emptyList()
            return cm.getLinkProperties(net)?.linkAddresses
                ?.filter { it.address is java.net.Inet4Address }?.map { (it.address.hostAddress ?: "") to it.prefixLength } ?: emptyList()
        }

        /** Wi-Fi or Ethernet is the active network (a LAN kid is only reachable from there). */
        fun onWifi(cm: ConnectivityManager): Boolean {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        /** Once a day: is there a newer release? Notifies once per version; the tap opens the app on "App settings" (in-app download, [Updater]). */
        fun checkUpdate(c: Context) {
            val now = System.currentTimeMillis()
            if (now - Store.updateAt(c) < 24 * 3_600_000L) return
            val u = java.net.URL(Logic.RELEASES).openConnection() as java.net.HttpURLConnection
            u.instanceFollowRedirects = false; u.requestMethod = "HEAD"; u.connectTimeout = 15_000; u.readTimeout = 15_000
            val tag = try { Logic.tagFromLocation(u.getHeaderField("Location")) } finally { u.disconnect() } ?: return
            Store.setUpdateAt(c, now)
            if (!Logic.newer(tag, BuildConfig.VERSION_NAME) || Store.updateNotified(c) == tag || !canNotify(c)) return
            Store.setUpdateNotified(c, tag)
            channel(c)
            val pi = PendingIntent.getActivity(c, 9, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(c, INFO_CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(c.getString(R.string.upd_title, tag))
                .setContentText(c.getString(R.string.upd_text)).setContentIntent(pi).setAutoCancel(true).build()
            c.getSystemService(NotificationManager::class.java).notify(0x6b67, n)
        }

        /** Permission granted and notifications not turned off in system settings. */
        fun canNotify(c: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            return c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }

        fun post(c: Context, k: Logic.KidCfg, e: Logic.Event, quiet: Boolean = false) = Notif.post(c, k, e, quiet)
    }
}
