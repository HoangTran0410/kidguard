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
    const val PAGE = 30              // rows per "history" page (max [PAGE_MAX])
    const val PAGE_MAX = 60
    const val THUMB_PX = 320
    const val FREE_LOW_MB = 1024     // the page warns under this (same as chat: state.freeMb)

    /** Flash is used: "on" always, "auto" only when the light sensor reads dark ([lux] < 0 = no reading: no flash). */
    fun flashWanted(mode: String, lux: Float) = mode == "on" || (mode == "auto" && lux in 0f..LOW_LUX)

    /** One kept photo / failed try of the history (`filesDir/snaps/<id>.json`): no count or age cap, wiped on release / rule off. */
    data class Meta(val id: String, val at: Long, val cam: String, val flash: String, val ok: Boolean, val err: String = "", val size: Long = 0) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("cam", cam).put("flash", flash).put("ok", ok)
            .also { if (!ok) it.put("err", err.ifEmpty { "failed" }) else it.put("size", size) }
        companion object {
            fun fromJson(o: JSONObject?): Meta? = o?.takeIf { FilesLogic.isId(it.optString("id")) }?.let {
                Meta(it.optString("id"), it.optLong("at"), if (it.optString("cam") == "back") "back" else "front",
                    it.optString("flash").let { f -> if (f == "on" || f == "auto") f else "off" }, it.optBoolean("ok"), it.optString("err"), it.optLong("size"))
            }
        }
    }

    fun count(n: Int) = if (n <= 0) PAGE else minOf(n, PAGE_MAX)

    /** One history page, newest first: entries with `at < before` (0 = from the newest), at most [n], `more` = still older ones. */
    fun page(all: List<Meta>, before: Long, n: Int): JSONObject {
        val old = all.filter { before <= 0 || it.at < before }.sortedByDescending { it.at }
        val take = old.take(count(n))
        return JSONObject().put("snaps", org.json.JSONArray().also { a -> take.forEach { a.put(it.toJson()) } }).put("more", old.size > take.size)
    }

    /** Front camera = a white screen at full brightness, back camera = the torch. */
    fun screenFlash(o: SnapOpts, wanted: Boolean) = wanted && !o.back
}

/**
 * Remote camera snapshot (rule `watch.cam`, command `snap`). The camera needs a visible window since Android 11, so the job runs in
 * [SnapActivity] (translucent, never takes touches, ends itself within [SnapLogic.TOTAL_MS]); when that cannot start, a photo is tried
 * straight from the service (no flash). The child gets a notification + a log line for EVERY attempt. The photo is saved as
 * `snaps/<fid>.jpg` + `<fid>.json` (history, failed tries too, never pruned: docs/flows/enforcement.md 1.18), fetched by LAN
 * `GET /api/snap/<fid>` or pushed to the relay file channel (`relay: true`); the parent's history = `snapList` / `GET /snaps`.
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
    fun clear(c: Context) { runCatching { File(c.filesDir, "snaps").deleteRecursively() }; runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NID) } }

    // ---------------- history ----------------
    private fun metaFile(c: Context, id: String) = File(dir(c), "$id.json")
    private fun record(c: Context, fid: String, o: SnapOpts, ok: Boolean, err: String) {
        if (!FilesLogic.isId(fid)) return
        val size = if (ok) file(c, fid)?.length() ?: 0L else 0L
        runCatching { metaFile(c, fid).writeText(SnapLogic.Meta(fid, System.currentTimeMillis(), o.cam, o.flash, ok, err, size).toJson().toString()) }
    }
    fun metas(c: Context): List<SnapLogic.Meta> =
        dir(c).listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { f -> runCatching { SnapLogic.Meta.fromJson(JSONObject(f.readText())) }.getOrNull() } ?: emptyList()
    /** `GET /api/snaps?before=&n=` and relay `snapList`: one page of the history (newest first). */
    fun api(c: Context, q: Map<String, String>): JSONObject = SnapLogic.page(metas(c), q["before"]?.toLongOrNull() ?: 0L, q["n"]?.toIntOrNull() ?: 0)
    /** State: how many entries / MB the history holds (the page shows it and warns on low space with state.freeMb). */
    fun stateJson(c: Context): JSONObject {
        val m = metas(c)
        return m.sumOf { it.size }.let { b -> JSONObject().put("n", m.size).put("mb", b / 1048576).put("b", b) }   // "b" bytes: "mb" rounds a few photos down to 0
    }
    /** Bulk delete (`snapDel {ids}`): the photos, their metas and thumbnails go. Returns how many entries were removed. */
    fun delete(c: Context, ids: List<String>): Int = ids.count { id ->
        val had = metaFile(c, id).isFile || File(dir(c), "$id.jpg").isFile
        File(dir(c), "$id.jpg").delete(); metaFile(c, id).delete(); File(File(dir(c), "th"), "$id.jpg").delete(); File(dir(c), "$id.err").delete(); had
    }
    /** JPEG <= 320 px of a kept photo (cached in snaps/th), null = no such photo. */
    fun thumb(c: Context, id: String): ByteArray? {
        val f = file(c, id) ?: return null
        val t = File(File(dir(c), "th").apply { mkdirs() }, "$id.jpg")
        if (t.isFile && t.length() > 0) return runCatching { t.readBytes() }.getOrNull()
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= SnapLogic.THUMB_PX) s *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray().also { b -> runCatching { t.writeBytes(b) } }
    }
    /** Relay `snapUp {id, thumb?}`: put the photo (or its thumbnail under `FilesLogic.thumbFid(id)`) on the file channel. */
    fun up(g: Context, c: JSONObject): String {
        val id = c.optString("id"); if (!FilesLogic.isId(id)) return "bad id"
        val f = file(g, id) ?: return "no such photo"
        val th = c.optInt("thumb") == 1
        Thread({
            runCatching {
                if (th) thumb(g, id)?.let { Files.pushBytes(g, FilesLogic.thumbFid(id), "$id.thumb.jpg", it, "&thumb=1") }
                else Files.pushBytes(g, id, "kidguard-snap.jpg", f.readBytes())
            }.onFailure { Log.w(TAG, "snap up", it) }
        }, "snap-up").start()
        return "ok"
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
        // a refused try is part of the history too (the parent sees why nothing came); a malformed / unsupported / rule-off request is not
        if (res != "ok" && res !in setOf("bad fid", "off", "unsupported")) record(g, c.optString("fid"), SnapOpts.fromJson(c), false, res)
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
            record(g, job0.fid, job0.o, ok, err)
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
