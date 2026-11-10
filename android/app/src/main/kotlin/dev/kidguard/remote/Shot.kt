package dev.kidguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrong-PIN photo (rule `pinShot`): one front-camera JPEG when the on-device admin PIN / code is wrong for the 3rd, 6th…
 * time in a row (ShotLogic.due). Never hidden: the PIN dialog says so beforehand and Android shows its camera dot.
 * Kept in filesDir/shots for 7 days (max 20), linked from the `tamper` event (`shot` id). The parent fetches it with
 * LAN `GET /api/shot/<id>` or, over the relay, command `shotUp {shot}` -> the relay file channel (Files.pushBytes).
 */
object Shot {
    private const val TAG = "KidGuard"
    private val busy = AtomicBoolean(false)

    private fun dir(c: Context) = File(c.filesDir, "shots").apply { mkdirs() }

    /** The camera can be used: permission held (Device Owner grants it; basic mode asks) and a front camera exists. */
    fun can(c: Context) = c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && front(c) != null

    private fun front(c: Context): String? = runCatching {
        val m = c.getSystemService(CameraManager::class.java)
        m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
    }.getOrNull()

    fun file(c: Context, id: String): File? = if (FilesLogic.isId(id)) File(dir(c), "$id.jpg").takeIf { it.isFile } else null

    private fun prune(c: Context, now: Long = System.currentTimeMillis()) {
        val all = dir(c).listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() } ?: return
        all.forEachIndexed { i, f -> if (i >= ShotLogic.MAX || now - f.lastModified() > ShotLogic.KEEP_MS) f.delete() }
    }

    /** Release: no photo stays behind. */
    fun clear(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }

    /** Relay command shotUp {shot}: put that photo on the relay for the parent (one chunk, < 1 MiB). */
    fun command(c: Context, o: JSONObject): String {
        val f = file(c, o.optString("shot")) ?: return "no such photo"
        Thread({ runCatching { Files.pushBytes(c, f.nameWithoutExtension, "kidguard-${f.nameWithoutExtension}.jpg", f.readBytes()) }
            .onFailure { Log.w(TAG, "shot push", it) } }, "shot-push").start()
        return "ok"
    }

    /**
     * Takes one photo in the background (camera open ~1-2 s, a few frames dropped so auto exposure settles), then [done]
     * with its id on the camera thread. Does nothing without permission / camera or while another shot runs.
     */
    @SuppressLint("MissingPermission")
    fun take(c: Context, done: (String) -> Unit) {
        val ctx = c.applicationContext
        if (!can(ctx) || !busy.compareAndSet(false, true)) return
        val th = HandlerThread("shot").apply { start() }
        val h = Handler(th.looper)
        var cam: CameraDevice? = null; var reader: ImageReader? = null; var frames = 0; var finished = false
        fun end() {
            if (finished) return; finished = true
            runCatching { cam?.close() }; runCatching { reader?.close() }
            th.quitSafely(); busy.set(false)
        }
        try {
            val m = ctx.getSystemService(CameraManager::class.java)
            val id = front(ctx) ?: return end()
            val ch = m.getCameraCharacteristics(id)
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG) ?: return end()
            val size = ShotLogic.pick(sizes.map { it.width to it.height }) ?: return end()
            val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            val r = ImageReader.newInstance(size.first, size.second, ImageFormat.JPEG, 2).also { reader = it }
            h.postDelayed({ if (!finished) { Log.w(TAG, "shot: timeout"); end() } }, 10_000)
            r.setOnImageAvailableListener({ rd ->
                val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (finished || ++frames < ShotLogic.SETTLE_FRAMES) return@setOnImageAvailableListener
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    val sid = Store.randomHex(8)
                    File(dir(ctx), "$sid.jpg").writeBytes(bytes)
                    prune(ctx)
                    end()
                    runCatching { done(sid) }.onFailure { Log.w(TAG, "shot done", it) }
                } catch (e: Exception) { Log.w(TAG, "shot save", e); end() } finally { img.close() }
            }, h)
            m.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    cam = d
                    if (finished) { d.close(); return }
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            runCatching {
                                val q = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(r.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.JPEG_ORIENTATION, orient)   // phone held upright while typing the PIN
                                    set(CaptureRequest.JPEG_QUALITY, 75.toByte())
                                }
                                s.setRepeatingRequest(q.build(), null, h)
                            }.onFailure { Log.w(TAG, "shot request", it); end() }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { end() }
                    }, h)
                }
                override fun onDisconnected(d: CameraDevice) { end() }
                override fun onError(d: CameraDevice, e: Int) { Log.w(TAG, "shot camera error $e"); end() }
            }, h)
        } catch (e: Exception) { Log.w(TAG, "shot", e); end() }
    }
}

/** Pure parts of [Shot] (unit-tested). */
object ShotLogic {
    const val KEEP_MS = 7 * 24 * 3600_000L
    const val MAX = 20
    const val SETTLE_FRAMES = 6
    const val GAP_MS = 2 * 60_000L

    /** A photo on the 3rd, 6th, 9th… wrong code in a row, at most one per [GAP_MS]. */
    fun due(on: Boolean, run: Int, lastShot: Long, now: Long) = on && run >= 3 && run % 3 == 0 && now - lastShot >= GAP_MS

    /** Biggest JPEG size with the long side <= 960 px (a face, ~60-150 KB), else the smallest one. */
    fun pick(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? =
        sizes.filter { maxOf(it.first, it.second) <= 960 }.maxByOrNull { it.first * it.second } ?: sizes.minByOrNull { it.first * it.second }
}
