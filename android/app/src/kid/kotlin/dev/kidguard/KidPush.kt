package dev.kidguard

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Push wake (kid flavor): the relay worker sends a data-only, high-priority FCM {kind:"wake"} when this phone has been
 * quiet too long (or a parent opens the page while it is quiet). Google Play services delivers it even in Doze and to a
 * killed (not force-stopped) app; it starts Guard / makes the relay sync now. The token goes to the worker in the next
 * sync (Relay.sync `fcm`). Off when app/google-services.json was missing at build time. Home flavor: src/home stub.
 */
object KidPush {
    @Volatile var token: String = ""

    fun init(c: Context) {
        if (BuildConfig.FCM_APP_ID.isEmpty()) return
        runCatching {
            FirebaseApp.getApps(c).firstOrNull() ?: FirebaseApp.initializeApp(c) ?: return
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { t -> if (!t.isNullOrEmpty() && t != token) { token = t; Guard.running?.relayWake() } }
                .addOnFailureListener { Log.w("KidGuard", "fcm token", it) }
        }.onFailure { Log.w("KidGuard", "firebase", it) }
    }

    fun wake(c: Context) { Guard.running?.relayWake() ?: Guard.start(c) }
}

class KidPushService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        if (m.data["kind"] == "wake") { Log.i("KidGuard", "push wake"); KidPush.wake(applicationContext) }
    }
    override fun onNewToken(t: String) { KidPush.token = t; Guard.running?.relayWake() }
}
