package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** What the parent asked for in one remote photo. [size] "pin" is only the wrong-PIN photo (960 px, quality 75). */
data class SnapOpts(val cam: String = "front", val flash: String = "off", val size: String = "small", val delay: Int = 0) {
    val back get() = cam == "back"
    fun maxSide() = when (size) { "pin" -> 960; "medium" -> 1280; else -> 640 }
    fun quality() = when (size) { "pin" -> 75; "medium" -> 80; else -> 70 }

    companion object {
        val PIN = SnapOpts(size = "pin")
        /** Whatever the parent sent, clamped to the known values (the worker checks them too). */
        fun fromJson(o: JSONObject) = SnapOpts(
            cam = if (o.optString("cam") == "back") "back" else "front",
            flash = o.optString("flash").let { if (it == "on" || it == "auto") it else "off" },
            size = if (o.optString("size") == "medium") "medium" else "small",
            delay = o.optInt("delay", 0).coerceIn(0, SnapLogic.MAX_DELAY),
        )
    }
}

/** Pure part of [RemoteSnap] (unit-tested). */
object SnapLogic {
    const val MAX_DELAY = 5
    const val LOW_LUX = 10f          // "auto" flash below this
    const val FLASH_WAIT_MS = 450L   // screen is white this long before the shutter (auto exposure settles)
    const val FLASH_HARD_MS = 2000L  // the white screen is removed after this at the latest, whatever happens
    const val TOTAL_MS = 25_000L     // whole job, delay included: then the Activity ends itself and the parent hears "timeout"
    const val KEEP_MS = 30 * 60_000L // a photo waits this long for the parent, then it is deleted
    const val MAX_KEEP = 10

    /** Flash is used: "on" always, "auto" only when the light sensor reads dark ([lux] < 0 = no reading: no flash). */
    fun flashWanted(mode: String, lux: Float) = mode == "on" || (mode == "auto" && lux in 0f..LOW_LUX)

    /** Front camera = a white screen at full brightness, back camera = the torch. */
    fun screenFlash(o: SnapOpts, wanted: Boolean) = wanted && !o.back
}

/**
 * Remote camera snapshot (rule `watch.cam`, command `snap`). The camera needs a visible window since Android 11, so the job runs in
 * [SnapActivity] (translucent, never takes touches, ends itself within [SnapLogic.TOTAL_MS]); when that cannot start, a photo is tried
 * straight from the service (no flash). The child gets a notification + a log line for EVERY attempt. The photo is saved as
 * `snaps/<fid>.jpg` (30 min), fetched by LAN `GET /api/snap/<fid>` or pushed to the relay file channel (`relay: true`).
 */
object RemoteSnap {
    private const val TAG = "KidGuard"
    private const val CH = "kg_watch"
    private const val NID = 13
    private val main = Handler(Looper.getMainLooper())

    private class Job(val fid: String, val o: SnapOpts, val relay: Boolean, var started: Boolean = false, var done: Boolean = false)
    private val job = AtomicReference<Job?>(null)

    fun on(g: Context) = Store.of(g).baseRules.watch.cam
    private fun dir(c: Context) = File(c.filesDir, "snaps").apply { mkdirs() }
    fun file(c: Context, fid: String): File? = if (FilesLogic.isId(fid)) File(dir(c), "$fid.jpg").takeIf { it.isFile } else null
    /** Why the last job with [fid] failed (LAN poll), or null. */
    fun error(c: Context, fid: String): String? = if (FilesLogic.isId(fid)) File(dir(c), "$fid.err").takeIf { it.isFile }?.readText() else null

    /** Release / rule off: no photo stays behind. */
    fun clear(c: Context) { runCatching { dir(c).listFiles()?.forEach { it.delete() } }; runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NID) } }

    private fun prune(c: Context, now: Long = System.currentTimeMillis()) {
        val all = dir(c).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        all.forEachIndexed { i, f -> if (i >= SnapLogic.MAX_KEEP || now - f.lastModified() > SnapLogic.KEEP_MS) f.delete() }
    }

    /** CAMERA held: Device Owner grants it itself, basic mode through our own adb (AutoGrant), like the video call. */
    private fun grant(c: Context) {
        if (Shot.canUse(c, false) || Shot.canUse(c, true)) return
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(c.packageName))
            runCatching { dpm.setPermissionGrantState(Policy.admin(c), c.packageName, android.Manifest.permission.CAMERA, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        else runCatching { AutoGrant.camNow(c) }
    }

    /** Answers: ok (started, the parent now polls) | off | bad fid | busy | screen off | no camera permission | no such camera | unsupported. */
    fun command(g: Context, c: JSONObject): String {
        val res = run(g, c)
        // relay: the parent waits on the file channel, so a refusal is pushed there too (LAN gets the answer directly)
        if (res != "ok" && c.optBoolean("relay", false) && FilesLogic.isId(c.optString("fid"))) Thread({
            runCatching { Files.pushBytes(g, c.optString("fid"), "kidguard-snap.json", JSONObject().put("error", res).toString().toByteArray()) }
        }, "snap-push").start()
        return res
    }

    private fun run(g: Context, c: JSONObject): String {
        if (Flavor.HOME) return "unsupported"
        val fid = c.optString("fid")
        if (!FilesLogic.isId(fid)) return "bad fid"
        if (!on(g)) return "off"
        val o = SnapOpts.fromJson(c)
        grant(g)
        if (g.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "no camera permission"
        if (!Shot.canUse(g, o.back)) return "no such camera"
        if (g.getSystemService(PowerManager::class.java)?.isInteractive != true) return "screen off"   // nobody looks at a dark phone; the parent can wake it first
        val j = Job(fid, o, c.optBoolean("relay", false))
        if (!job.compareAndSet(null, j)) return "busy"
        prune(g)
        File(dir(g), "$fid.err").delete()
        tell(g, o)
        runCatching { Store.of(g).log(R.string.log_watch_snap) }
        main.postDelayed({ if (job.get() === j && !j.started) fallback(g, j) }, 4000)   // the window did not come up: photo from the service
        main.postDelayed({ if (job.get() === j) finish(g, j, "", "timeout") }, SnapLogic.TOTAL_MS + 3000)
        val started = runCatching {
            g.startActivity(Intent(g, SnapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("fid", fid).putExtra("cam", o.cam).putExtra("flash", o.flash).putExtra("size", o.size).putExtra("delay", o.delay))
        }.isSuccess
        if (!started) fallback(g, j)
        return "ok"
    }

    /** SnapActivity is on screen and takes over (cancels the fallback). The Activity asks which job it serves. */
    fun attach(fid: String): SnapOpts? = job.get()?.takeIf { it.fid == fid && !it.done }?.also { it.started = true }?.o

    /** Job result (the Activity or the fallback): saves the photo as <fid>.jpg or the reason, answers the parent, ends the job. */
    private fun finish(g: Context, j: Job?, photoId: String, err: String) {
        val job0 = j ?: job.get() ?: return
        if (job0.done) return
        job0.done = true
        val ok = photoId.isNotEmpty() && err.isEmpty()
        try {
            if (ok) File(dir(g), "$photoId.jpg").renameTo(File(dir(g), "${job0.fid}.jpg"))
            else File(dir(g), "${job0.fid}.err").writeText(err.ifEmpty { "failed" })
            if (job0.relay) Thread({
                runCatching {
                    val f = file(g, job0.fid)
                    if (ok && f != null) Files.pushBytes(g, job0.fid, "kidguard-snap.jpg", f.readBytes())
                    else Files.pushBytes(g, job0.fid, "kidguard-snap.json", JSONObject().put("error", err.ifEmpty { "failed" }).toString().toByteArray())
                }.onFailure { Log.w(TAG, "snap push", it) }
            }, "snap-push").start()
        } finally { job.compareAndSet(job0, null) }
    }

    /** Job result by finish() from the Activity (which does not know the Job type). */
    fun finishFid(g: Context, fid: String, photoId: String, err: String) { job.get()?.takeIf { it.fid == fid }?.let { finish(g, it, photoId, err) } }

    private fun fallback(g: Context, j: Job) {
        j.started = true
        val ok = runCatching { Shot.take(g, j.o, dir(g), { id, good -> finish(g, j, if (good) id else "", if (good) "" else "camera") }) }.getOrDefault(false)
        if (!ok) finish(g, j, "", "busy")
    }

    /** The child is told every time: notification (own id, replaced by the next one) + the log line above. */
    private fun tell(c: Context, o: SnapOpts) {
        runCatching {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH, c.getString(R.string.ch_watch), NotificationManager.IMPORTANCE_HIGH))
            nm.notify(NID, Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(c.getString(R.string.snap_notice_title))
                .setContentText(c.getString(if (o.back) R.string.snap_notice_back else R.string.snap_notice_front))
                .setContentIntent(Monitoring.openIntent(c)).setAutoCancel(true).build())
        }
    }

    /** One light reading (lux) or -1 when there is no sensor / no answer in 400 ms. Calls [then] on the main thread. */
    fun lux(c: Context, then: (Float) -> Unit) {
        val sm = c.getSystemService(SensorManager::class.java)
        val s = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sm == null || s == null) return then(-1f)
        var got = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { if (got) return; got = true; sm.unregisterListener(this); main.post { then(e.values[0]) } }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, main)
        main.postDelayed({ if (!got) { got = true; sm.unregisterListener(l); then(-1f) } }, 400)
    }
}
