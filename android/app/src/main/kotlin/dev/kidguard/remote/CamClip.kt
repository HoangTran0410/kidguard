package dev.kidguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short video clip with sound for the Telegram bot (`/clip`, docs/protocol.md "Telegram bot"): same consent as the photo on request
 * (rule `watch.cam`). The camera (+ mic) from the background needs a foreground service started "while-in-use", so this reuses
 * [CamService] / [CamActivity] exactly like [CamCall] (Device Owner starts the service at once; basic mode = the translucent visible
 * moment, a dark screen is refused). The child is told: the service's ongoing notification for the whole recording + log lines.
 * Recording = Camera2 into MediaRecorder (H.264 + AAC MP4, ~480p, [TgLogic.CLIP_VBPS]); the file lives in cacheDir/tgclip and is
 * deleted by the caller after sending (and on release / rule off).
 */
object CamClip {
    private const val TAG = "KidGuard"
    const val NID = 15
    private const val VISIBLE_WAIT_MS = 4000L
    private const val SERVICE_WAIT_MS = 7000L

    private val busy = AtomicBoolean()
    val active get() = busy.get()
    @Volatile var back = false; private set
    @Volatile var snd = false; private set
    @Volatile private var secs = 0
    @Volatile private var wanted = false      // waiting for CamService to come up
    @Volatile private var started = false
    @Volatile private var up: CountDownLatch? = null
    private val main = Handler(Looper.getMainLooper())

    fun dir(c: Context) = File(c.cacheDir, "tgclip").apply { mkdirs() }
    /** Release / rule off: no clip stays behind. */
    fun clear(c: Context) { runCatching { File(c.cacheDir, "tgclip").deleteRecursively() } }

    /** A clip that was recorded: [snd] false = no mic permission, the video has no sound. */
    class Result(val file: File?, val err: String, val snd: Boolean)

    private fun owner(c: Context) = c.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(c.packageName) == true

    /**
     * Blocking (a Telegram worker thread): checks, tells the child, records [n] seconds, ends the service.
     * err: off | unsupported | busy | cam live | no camera permission | no such camera | notice blocked | screen off | cam blocked | camera.
     */
    fun record(g: Guard, n: Int, back0: Boolean, onStart: () -> Unit = {}): Result {
        if (Flavor.HOME) return Result(null, "unsupported", false)
        if (!RemoteSnap.on(g)) return Result(null, "off", false)
        if (CamCall.active) return Result(null, "cam live", false)
        if (Call.cur != null || ListenCall.active || Listen.active || RemoteSnap.busy || Ring.active) return Result(null, "busy", false)
        if (!busy.compareAndSet(false, true)) return Result(null, "busy", false)
        back = back0; secs = n
        var t0 = 0L; var done = false
        var wl: PowerManager.WakeLock? = null
        try {
            grant(g)
            if (g.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return Result(null, "no camera permission", false)
            // no mic: the clip is still useful as a picture; the parent's caption says it has no sound (docs/decisions/remote.md)
            snd = g.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!Shot.canUse(g, back)) return Result(null, "no such camera", false)
            if (!CamCall.noticeVisible(g)) return Result(null, "notice blocked", false)
            if (!owner(g) && g.getSystemService(PowerManager::class.java)?.isInteractive != true) return Result(null, "screen off", false)
            wl = runCatching {
                g.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidguard:clip").apply { setReferenceCounted(false); acquire(n * 1000L + 60_000) }
            }.getOrNull()
            t0 = System.currentTimeMillis()
            runCatching { Store.of(g).log(R.string.log_clip_start, n) }
            if (!startFg(g)) return Result(null, "cam blocked", snd).also { log(g, "cam blocked") }
            runCatching(onStart)
            val f = File(dir(g), "clip-${Store.randomHex(4)}.mp4")
            val err = capture(g, f, n)
            if (err.isNotEmpty()) { f.delete(); log(g, err); return Result(null, err, snd) }
            done = true; return Result(f, "", snd)
        } finally {
            wanted = false; started = false; up = null
            runCatching { g.stopService(Intent(g, CamService::class.java)) }
            g.getSystemService(NotificationManager::class.java)?.cancel(NID)
            runCatching { wl?.release() }
            if (done) runCatching { Store.of(g).log(R.string.log_clip_done, java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t0))) }
            busy.set(false)
            Guard.running?.kick()
        }
    }

    private fun log(g: Context, why: String) { runCatching { Store.of(g).log(R.string.log_clip_err, why) } }

    /** CAMERA + RECORD_AUDIO: Device Owner grants them itself, basic mode through our own adb (AutoGrant), like the live camera. */
    private fun grant(c: Context) {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) return
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(c.packageName))
            for (p in need) runCatching { dpm.setPermissionGrantState(Policy.admin(c), c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        else { runCatching { AutoGrant.camNow(c) }; if (Manifest.permission.RECORD_AUDIO in need) runCatching { AutoGrant.micNow(c) } }
    }

    /** CamService in the foreground with the camera (+ mic) type; false = Android refused or it never came up. */
    private fun startFg(g: Guard): Boolean {
        val l = CountDownLatch(1); up = l; wanted = true; started = false
        if (owner(g)) startService(g)   // Device Owner: exempt from the while-in-use rule
        else {
            val shown = runCatching {
                g.startActivity(Intent(g, CamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("clip", true))
            }.isSuccess
            if (!shown) startService(g) else main.postDelayed({ if (wanted && !started) startService(g) }, VISIBLE_WAIT_MS)
        }
        return l.await(SERVICE_WAIT_MS, TimeUnit.MILLISECONDS) && svcOk
    }
    @Volatile private var svcOk = false

    /** From CamActivity (on screen) or [startFg]. */
    fun startService(c: Context) {
        if (!wanted || started) return
        started = true; svcOk = false
        runCatching { c.applicationContext.startForegroundService(Intent(c.applicationContext, CamService::class.java).putExtra("clip", true)) }
            .onFailure { Log.w(TAG, "clip service", it); up?.countDown() }
    }
    fun wants() = wanted && !started
    fun waiting() = wanted
    /** CamService answered: [ok] = in the foreground with the camera type. */
    fun serviceUp(ok: Boolean) { svcOk = ok; up?.countDown() }

    /** The child's notice (also the foreground service's notification). */
    fun notice(c: Context): Notification {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(android.app.NotificationChannel(CamCall.CH, c.getString(R.string.ch_watch), NotificationManager.IMPORTANCE_HIGH))
        val text = c.getString(if (back) R.string.clip_notice_back else R.string.clip_notice_front, secs) + (if (snd) " " + c.getString(R.string.cam_notice_snd) else "")
        return Notification.Builder(c, CamCall.CH).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(c.getString(R.string.clip_notice_title)).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(Monitoring.openIntent(c)).build()
    }

    /** Camera2 -> MediaRecorder for [n] s into [f]. "" = ok, else the reason. Stops early when the rule goes off. */
    private fun capture(g: Context, f: File, n: Int): String {
        var stopped = true
        val r = capture0(g, f, n) { stopped = it }
        return if (r.isEmpty() && (!stopped || !f.isFile || f.length() == 0L)) "camera" else r   // stop() threw = no frame arrived: unusable file
    }

    @SuppressLint("MissingPermission")
    private fun capture0(g: Context, f: File, n: Int, stoppedOk: (Boolean) -> Unit): String {
        val th = HandlerThread("clip").apply { start() }
        val h = Handler(th.looper)
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(g) else MediaRecorder()
        var cam: CameraDevice? = null; var ses: CameraCaptureSession? = null
        var recording = false
        try {
            val m = g.getSystemService(CameraManager::class.java)
            val lens = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val id = m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens } ?: return "no such camera"
            val ch = m.getCameraCharacteristics(id)
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(MediaRecorder::class.java) ?: return "camera"
            val size = TgLogic.clipSize(sizes.map { it.width to it.height }) ?: return "camera"
            val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: if (back) 90 else 270
            if (snd) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(size.first, size.second)
            rec.setVideoFrameRate(24)
            rec.setVideoEncodingBitRate(TgLogic.CLIP_VBPS)
            if (snd) { rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); rec.setAudioChannels(1); rec.setAudioSamplingRate(44_100); rec.setAudioEncodingBitRate(TgLogic.CLIP_ABPS) }
            rec.setOrientationHint(orient)   // the phone held upright (no window to read the rotation from)
            rec.setMaxFileSize(TgLogic.CLIP_MAX_BYTES)
            rec.setOutputFile(f.path)
            rec.prepare()
            val surface = rec.surface
            val ready = CountDownLatch(1); var fail = ""
            m.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    cam = d
                    try {
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                ses = s
                                runCatching {
                                    val q = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                        addTarget(surface); set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }
                                    s.setRepeatingRequest(q.build(), null, h)
                                }.onFailure { Log.w(TAG, "clip request", it); fail = "camera" }
                                ready.countDown()
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail = "camera"; ready.countDown() }
                        }, h)
                    } catch (e: Exception) { Log.w(TAG, "clip session", e); fail = "camera"; ready.countDown() }
                }
                override fun onDisconnected(d: CameraDevice) { cam = d; fail = "busy"; ready.countDown() }
                override fun onError(d: CameraDevice, e: Int) { Log.w(TAG, "clip camera error $e"); cam = d; fail = if (e == ERROR_CAMERA_IN_USE || e == ERROR_MAX_CAMERAS_IN_USE) "busy" else "camera"; ready.countDown() }
            }, h)
            if (!ready.await(8, TimeUnit.SECONDS)) return "camera"
            if (fail.isNotEmpty()) return fail
            rec.start(); recording = true
            val end = System.currentTimeMillis() + n * 1000L
            while (System.currentTimeMillis() < end) {
                Thread.sleep(250)
                if (!RemoteSnap.on(g)) return "off"   // the parent turned the consent off meanwhile: nothing is sent
            }
            return ""
        } catch (e: Exception) {
            Log.w(TAG, "clip", e); return "camera"
        } finally {
            if (recording) stoppedOk(runCatching { rec.stop() }.isSuccess)
            runCatching { rec.release() }
            runCatching { ses?.close() }; runCatching { cam?.close() }
            th.quitSafely()
        }
    }
}
