package dev.kidguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import java.io.IOException
import java.io.OutputStream

/**
 * "Listen around": the parent hears the child's microphone, one way, LAN / Tailscale only (`GET /api/listen`).
 * The child is ALWAYS told: a beep, an ongoing notification and a log line. This is not tied to `showWatching`
 * (recording someone without telling them is not something this app does), and Android also shows its mic dot.
 */
object Listen {
    const val MAX_MS = 10 * 60_000L
    private const val CH = "kg_listen"
    private const val NID = 4

    @Volatile var since = 0L; private set
    val active get() = since > 0
    /** Guard's state is rebuilt per tick: a changed error kicks a tick so the page sees it at once, not on the next one. */
    @Volatile var lastError = ""
        private set(v) { val changed = field != v; field = v; if (changed) Guard.running?.kick() }

    fun state(): org.json.JSONObject = org.json.JSONObject().put("on", active).put("since", since).put("err", lastError)

    class Session(private val g: Guard, private val rec: MediaRecorder, private val read: ParcelFileDescriptor, private val write: ParcelFileDescriptor) {
        @Volatile private var closed = false
        @Volatile private var lastData = System.currentTimeMillis()
        private val started = System.currentTimeMillis()
        // screen off: keep the CPU up while streaming (the Wi-Fi lock in Guard keeps the radio up)
        private val wl = runCatching {
            g.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidguard:listen").apply { setReferenceCounted(false); acquire(MAX_MS + 30_000) }
        }.getOrNull()

        init {
            // watchdog: a blocked mic (ColorOS privacy layer: amplitude stays 0) or a stuck encoder / reader ends the session,
            // so `listen.on` never sticks and the next request is not refused
            Thread({
                val amps = ArrayList<Int>()
                runCatching { rec.maxAmplitude }   // the first call is always 0
                while (!closed) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                    val now = System.currentTimeMillis()
                    if (now - started <= ListenLogic.SILENT_MS + 250) {
                        amps += runCatching { rec.maxAmplitude }.getOrDefault(0)
                        if (ListenLogic.blocked(amps, now - started)) { close("mic silenced"); break }
                    }
                    if (ListenLogic.stalled(lastData, now)) { close("mic stalled"); break }
                    if (now - started > MAX_MS) { close(""); break }
                }
            }, "listen-watch").apply { isDaemon = true; start() }
        }

        /** Copies the AAC (ADTS) stream to [out] until the parent goes away, [MAX_MS] passes or [stopAll] is called. */
        fun pump(out: OutputStream) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(read).use { ins ->
                    val buf = ByteArray(4096)
                    while (!closed && System.currentTimeMillis() - started < MAX_MS) {
                        val n = ins.read(buf); if (n < 0) break
                        lastData = System.currentTimeMillis()
                        out.write(buf, 0, n); out.flush()
                    }
                }
            } catch (e: IOException) { /* the parent closed the player, or close() closed the pipe */ } finally { close("") }
        }

        /** [why] non-empty = an error the page shows (`listen.err`). Only the current session touches the shared state. */
        fun close(why: String) {
            synchronized(this) { if (closed) return; closed = true }
            runCatching { rec.stop() }; runCatching { rec.release() }
            runCatching { write.close() }; runCatching { read.close() }
            runCatching { wl?.release() }
            val ms = System.currentTimeMillis() - started
            val min = (ms + 30_000) / 60_000
            val took = if (min < 1) g.getString(R.string.under_1_min) else g.resources.getQuantityString(R.plurals.listen_about, min.toInt(), min.toInt())
            val mine = synchronized(Listen) { (current === this).also { if (it) { since = 0L; current = null; if (why.isNotEmpty()) lastError = why } } }
            runCatching { Store.of(g).lastListen = longArrayOf(started, ms) }
            if (mine) {
                g.micForeground(false)
                g.getSystemService(NotificationManager::class.java)?.cancel(NID)
            }
            if (why.isNotEmpty()) runCatching { Store.of(g).log(R.string.log_listen_err, why) }
            runCatching { Store.of(g).log(R.string.log_listen_done, java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(started)), took) }
            g.kick()
        }
    }

    @Volatile private var current: Session? = null

    /**
     * Starts the mic, or returns null with [lastError] = "no mic permission" | "notice blocked" | "mic blocked" | "mic failed".
     * A session already running is replaced (a parent whose Wi-Fi dropped must be able to start again at once).
     */
    @Synchronized fun open(g: Guard): Session? {
        ActionLogic.conflict("listen", ringing = Ring.active, listening = active)?.let { lastError = it; return null }
        current?.close("")
        if (g.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { lastError = "no mic permission"; return null }
        // the child must be able to see the notice: refuse when notifications or this channel are off / silent
        if (!noticeVisible(g)) { lastError = "notice blocked"; return null }
        // Android 14: the mic may only be used from a foreground service that declares it
        if (!g.micForeground(true)) { lastError = "mic blocked"; return null }
        tell(g)
        val pipe = ParcelFileDescriptor.createPipe()
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(g) else MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)   // a plain stream: no seeking needed, browsers play it as audio/aac
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1); rec.setAudioSamplingRate(16_000); rec.setAudioEncodingBitRate(32_000)
            rec.setOutputFile(pipe[1].fileDescriptor)
            rec.prepare(); rec.start()
            since = System.currentTimeMillis(); lastError = ""
            Store.of(g).log(R.string.log_listen_start)
            g.kick()
            Session(g, rec, pipe[0], pipe[1]).also { current = it }
        } catch (e: Exception) {
            Log.w("KidGuard", "listen", e)
            runCatching { rec.release() }; runCatching { pipe[0].close() }; runCatching { pipe[1].close() }
            g.micForeground(false)
            g.getSystemService(NotificationManager::class.java)?.cancel(NID)
            lastError = "mic failed"; null
        }
    }

    /** True when the "listening" notice can show: notifications on and the channel at least DEFAULT importance. */
    private fun noticeVisible(g: Guard): Boolean {
        val nm = g.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(CH, g.getString(R.string.ch_listen), NotificationManager.IMPORTANCE_HIGH))
        if (!nm.areNotificationsEnabled()) return false
        val imp = nm.getNotificationChannel(CH)?.importance ?: return false
        return imp >= NotificationManager.IMPORTANCE_DEFAULT
    }

    /** Ringing stopped: the "ringing" refusal is no longer true, so the page must not keep showing it as `listen.err`. */
    fun clearRinging() { if (lastError == "ringing") lastError = "" }

    /** Release / service stop / command `listenStop`. True when a session was running. */
    fun stopAll(): Boolean { val c = current ?: return false; c.close(""); return true }

    /** Beep + ongoing notification. Always, whatever the rules say. */
    private fun tell(g: Guard) {
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            android.os.Handler(g.mainLooper).postDelayed({ t.release() }, 1_000)
        }
        val nm = g.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, g.getString(R.string.ch_listen), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(g, 0, Intent(g, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NID, Notification.Builder(g, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(g.getString(R.string.notif_listen_title))
            .setContentText(g.getString(R.string.notif_listen_text))
            .setOngoing(true).setContentIntent(open).build())
    }
}
