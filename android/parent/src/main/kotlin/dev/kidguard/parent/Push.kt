package dev.kidguard.parent

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * Instant SOS / "more time" notifications through Firebase Cloud Messaging, for kids reached via the relay.
 * Optional: on only when parent/google-services.json existed at build time (its values become the resources the
 * Firebase SDK reads, no google-services plugin needed). Without it, or for
 * LAN-only kids, the ~15 min background check ([CheckJob]) is the only source. Both post the same notification id.
 */
object Push {
    val enabled get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    private fun app(c: Context): FirebaseApp? {
        if (!enabled) return null
        // FirebaseInitProvider normally did it already, from the google_app_id / gcm_defaultSenderId... resources
        return FirebaseApp.getApps(c).firstOrNull() ?: runCatching { FirebaseApp.initializeApp(c) }
            .onFailure { Log.w("KGParent", "firebase", it) }.getOrNull()
    }

    /** Registers this phone's FCM token with every relay kid; [removed] relay kids get it unregistered. */
    fun sync(c: Context, removed: List<Logic.KidCfg> = emptyList(), force: Boolean = false) {
        app(c) ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> Thread({ send(c, t, removed, force) }, "kg-push").start() }
            .addOnFailureListener { Log.w("KGParent", "fcm token", it) }
    }

    /** [force]: register again even when nothing changed (the worker forgets tokens after 30 quiet days; a failed run is retried). */
    private fun send(c: Context, token: String, removed: List<Logic.KidCfg>, force: Boolean) {
        fun post(k: Logic.KidCfg, off: Boolean) = Net.call("POST", "${k.target}/c/${k.id}/push",
            mapOf("X-KG-Token" to k.token, "Content-Type" to "application/json"),
            JSONObject().put("fcm", token).apply { if (off) put("off", true) }.toString().toByteArray(), 15_000).status
        val relayKids = Logic.parseKids(Store.kids(c)).filter { it.via == "relay" }
        // saves that touch nothing relevant (rules edits, tab state) must not re-POST to every relay kid
        val sig = (listOf(token, Store.notify(c).toString()) + relayKids.map { "${it.id}|${it.target}|${it.token}" }.sorted()).joinToString("\n")
        val rm = removed.filter { it.via == "relay" }
        if (!force && rm.isEmpty() && sig == Store.pushSig(c)) return
        for (k in rm) post(k, true)
        var ok = true
        for (k in relayKids) {
            val st = post(k, !Store.notify(c))
            if (st != 200) { ok = false; Log.w("KGParent", "push register ${k.id}: $st") }
        }
        if (ok) Store.setPushSig(c, sig)
        Store.setPushRes(c, if (ok) "ok" else "fail", System.currentTimeMillis())
    }

    /** One word for the page's health card: "off" (no FCM in this build), "none" (no relay kid), "ok", "fail" / "wait" (never run). */
    fun status(c: Context): String = when {
        !enabled -> "off"
        Logic.parseKids(Store.kids(c)).none { it.via == "relay" } -> "none"
        Store.pushRes(c).isEmpty() -> "wait"
        else -> Store.pushRes(c)
    }

    /** Called for every incoming data message. Marks the event seen so the background check does not repeat it. */
    fun handle(c: Context, data: Map<String, String>) {
        if (!Store.notify(c) || !CheckJob.canNotify(c)) return   // not shown = not marked seen: the background check retries
        // "ev" carries no text (privacy): run the check for that kid now, it notifies each new event once
        Logic.pushCheckKid(Logic.parseKids(Store.kids(c)), data)?.let { k -> runCatching { CheckJob.runKid(c, k) }.onFailure { Log.w("KGParent", "ev check", it) }; return }
        val hit = synchronized(Store) {
            val kids = Logic.parseKids(Store.kids(c))
            val ev = Logic.pushEvent(kids, data, { Logic.NotifyState.fromJson(Store.notifyState(c, it.key)) }, tr = ResTr(c)) ?: return
            val st = Logic.NotifyState.fromJson(Store.notifyState(c, ev.first.key))
            Store.setNotifyState(c, ev.first.key, Logic.withSeen(st, Logic.seenIdOf(data["eid"]!!)!!).toJson())
            ev
        }
        CheckJob.post(c, hit.first, hit.second)
        // the push carries no location (privacy): fetch the state once so "Xem vị trí" opens the map at the last fix
        if (Act.isSos(hit.second.key)) Thread({
            val loc = runCatching { CheckJob.fetch(hit.first)?.loc }.getOrNull() ?: return@Thread
            if (Notif.showing(c, hit.first, hit.second.key)) CheckJob.post(c, hit.first, Logic.Event(hit.second.key, hit.second.text, loc = loc), quiet = true)
        }, "kg-sos-loc").start()
    }
}

class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) = Push.handle(applicationContext, m.data)
    override fun onNewToken(token: String) = Push.sync(applicationContext)
}
