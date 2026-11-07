package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Command `ring` (find my phone): alarm sound at full alarm volume for [MAX_MS], a heads-up notification with
 * "Tắt chuông" that anyone holding the phone may press, a volume key also stops it (Acc key filter).
 * `ringStop` / release stop it early. State `ring: {on, since}` is the real player state. See docs/protocol.md.
 */
object Ring {
    const val MAX_MS = 20_000L
    private const val CH = "kg_ring"
    private const val NID = 7303
    private val main = Handler(Looper.getMainLooper())
    private var rt: Ringtone? = null
    private var oldVol = -1
    @Volatile var since = 0L; private set
    val active get() = since > 0
    private var app: Context? = null
    private val end = Runnable { app?.let { stop(it) } }

    fun state(): org.json.JSONObject = org.json.JSONObject().put("on", active).put("since", since)

    /** "ok" | "listening" (ringing would drown out the parent listening) | "failed". Again while ringing = start the 20 s over. */
    fun start(ctx: Context): String {
        ActionLogic.conflict("ring", ringing = active, listening = Listen.active)?.let { return it }
        val c = ctx.applicationContext
        var res = "ok"
        runOnMain {
            app = c
            main.removeCallbacks(end)
            if (rt?.isPlaying != true) runCatching {
                val am = c.getSystemService(AudioManager::class.java)
                if (oldVol < 0) oldVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val r = RingtoneManager.getRingtone(c, uri) ?: error("no ringtone")
                r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                r.play(); rt = r
                since = System.currentTimeMillis()
                notice(c)
            }.onFailure { Log.w("KidGuard", "ring", it); res = "failed"; stopNow(c) }
            if (res == "ok") main.postDelayed(end, MAX_MS)
        }
        Guard.running?.kick()
        return res
    }

    /** Idempotent: "ok" whether or not it was ringing. Any thread. */
    fun stop(ctx: Context): String {
        val c = ctx.applicationContext
        runOnMain { stopNow(c) }
        Guard.running?.kick()
        return "ok"
    }

    private fun stopNow(c: Context) {
        main.removeCallbacks(end)
        runCatching { rt?.stop() }; rt = null
        if (oldVol >= 0) runCatching { c.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0) }
        oldVol = -1
        since = 0L
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(NID) }
    }

    /** Runs [f] on the main thread and waits for it (max 3 s). */
    private fun runOnMain(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { f(); return }
        val l = java.util.concurrent.CountDownLatch(1)
        main.post { try { f() } finally { l.countDown() } }
        l.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun notice(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val res = Lang.res(c) ?: c.resources
        nm.createNotificationChannel(NotificationChannel(CH, res.getString(R.string.ch_ring), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val stop = PendingIntent.getBroadcast(c, 0, Intent(c, RingStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NID, Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(res.getString(R.string.notif_ring_title)).setContentText(res.getString(R.string.notif_ring_text))
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setContentIntent(stop).setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, res.getString(R.string.ring_stop), stop).build()).build())
    }
}

/** "Tắt chuông" in the ring notification (or the notification tapped / swiped). */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Ring.active) runCatching { Store.of(context).log(R.string.log_ring_stopped) }
        Ring.stop(context)
    }
}
