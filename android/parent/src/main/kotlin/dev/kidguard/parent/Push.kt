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
    fun sync(c: Context, removed: List<Logic.KidCfg> = emptyList()) {
        app(c) ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> Thread({ send(c, t, removed) }, "kg-push").start() }
            .addOnFailureListener { Log.w("KGParent", "fcm token", it) }
    }

    private fun send(c: Context, token: String, removed: List<Logic.KidCfg>) {
        fun post(k: Logic.KidCfg, off: Boolean) = Net.call("POST", "${k.target}/c/${k.id}/push",
            mapOf("X-KG-Token" to k.token, "Content-Type" to "application/json"),
            JSONObject().put("fcm", token).apply { if (off) put("off", true) }.toString().toByteArray(), 15_000).status
        for (k in removed.filter { it.via == "relay" }) post(k, true)
        for (k in Logic.parseKids(Store.kids(c)).filter { it.via == "relay" }) {
            val st = post(k, !Store.notify(c))
            if (st != 200) Log.w("KGParent", "push register ${k.id}: $st")
        }
    }

    /** Called for every incoming data message. Marks the event seen so the background check does not repeat it. */
    fun handle(c: Context, data: Map<String, String>) {
        if (!Store.notify(c) || !CheckJob.canNotify(c)) return   // not shown = not marked seen: the background check retries
        // "ev" carries no text (privacy): run the check for that kid now, it notifies each new event once
        Logic.pushCheckKid(Logic.parseKids(Store.kids(c)), data)?.let { k -> runCatching { CheckJob.runKid(c, k) }.onFailure { Log.w("KGParent", "ev check", it) }; return }
        val hit = synchronized(Store) {
            val kids = Logic.parseKids(Store.kids(c))
            val ev = Logic.pushEvent(kids, data, { Logic.NotifyState.fromJson(Store.notifyState(c, it.key)) }) ?: return
            val st = Logic.NotifyState.fromJson(Store.notifyState(c, ev.first.key))
            Store.setNotifyState(c, ev.first.key, Logic.withSeen(st, Logic.seenIdOf(data["eid"]!!)!!).toJson())
            ev
        }
        CheckJob.post(c, hit.first, hit.second)
    }
}

class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) = Push.handle(applicationContext, m.data)
    override fun onNewToken(token: String) = Push.sync(applicationContext)
}
