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
    @Volatile var lastError = ""; private set

    fun state(): org.json.JSONObject = org.json.JSONObject().put("on", active).put("since", since).put("err", lastError)

    class Session(private val g: Guard, private val rec: MediaRecorder, private val read: ParcelFileDescriptor, private val write: ParcelFileDescriptor) {
        @Volatile private var closed = false

        /** Copies the AAC (ADTS) stream to [out] until the parent goes away, [MAX_MS] passes or [stopAll] is called. */
        fun pump(out: OutputStream) {
            val start = System.currentTimeMillis()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(read).use { ins ->
                    val buf = ByteArray(4096)
                    while (!closed && System.currentTimeMillis() - start < MAX_MS) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); out.flush()
                    }
                }
            } catch (e: IOException) { /* the parent closed the player */ } finally { close() }
        }

        @Synchronized fun close() {
            if (closed) return
            closed = true
            runCatching { rec.stop() }; runCatching { rec.release() }
            runCatching { write.close() }; runCatching { read.close() }
            val start = since
            val ms = System.currentTimeMillis() - start
            val min = (ms + 30_000) / 60_000
            val took = if (min < 1) "dưới 1 phút" else "khoảng $min phút"
            since = 0L; current = null
            runCatching { Store.of(g).lastListen = longArrayOf(start, ms) }
            g.micForeground(false)
            g.getSystemService(NotificationManager::class.java)?.cancel(NID)
            runCatching { Store.of(g).log("Bố mẹ đã nghe âm thanh xung quanh từ ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(start))} ($took)") }
            g.kick()
        }
    }

    @Volatile private var current: Session? = null

    /** Starts the mic, or returns null with [lastError] = "busy" | "no mic permission" | "notice blocked" | "mic blocked" | "mic failed". */
    @Synchronized fun open(g: Guard): Session? {
        if (current != null) { lastError = "busy"; return null }
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
            Store.of(g).log("Bố mẹ bắt đầu nghe âm thanh xung quanh")
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
        nm.createNotificationChannel(NotificationChannel(CH, "Bố mẹ đang nghe", NotificationManager.IMPORTANCE_HIGH))
        if (!nm.areNotificationsEnabled()) return false
        val imp = nm.getNotificationChannel(CH)?.importance ?: return false
        return imp >= NotificationManager.IMPORTANCE_DEFAULT
    }

    /** Release / service stop. */
    fun stopAll() { current?.close() }

    /** Beep + ongoing notification. Always, whatever the rules say. */
    private fun tell(g: Guard) {
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            android.os.Handler(g.mainLooper).postDelayed({ t.release() }, 1_000)
        }
        val nm = g.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, "Bố mẹ đang nghe", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(g, 0, Intent(g, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NID, Notification.Builder(g, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Bố mẹ đang nghe âm thanh xung quanh máy")
            .setContentText("Micro đang bật để bố mẹ nghe. Tự tắt sau tối đa 10 phút.")
            .setOngoing(true).setContentIntent(open).build())
    }
}
