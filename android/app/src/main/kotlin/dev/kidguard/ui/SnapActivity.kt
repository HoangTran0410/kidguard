package dev.kidguard

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * The window a remote photo (Snap.kt) is taken from: Android only gives the camera to an app that is on screen. Translucent, and it NEVER
 * takes touches (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE), so the child keeps using whatever is below. For the front camera with flash on it
 * turns white at full brightness for about a second (a selfie flash); the back camera uses the torch instead (no window change).
 *
 * Stuck-overlay rules (docs/decisions: overlay incident 2026-11-15): the white goes away in [SnapLogic.FLASH_HARD_MS] at the latest
 * (a posted removal that nothing can cancel but destroy), the whole Activity ends in [SnapLogic.TOTAL_MS] at the latest, it ends when
 * it loses the screen (onStop), and onDestroy removes the white again. Brightness is a window property: it is gone with the window.
 */
class SnapActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var fid = ""
    private var reported = false
    private var white = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fid = intent.getStringExtra("fid").orEmpty()
        val o = RemoteSnap.attach(fid)
        if (o == null) { finish(); return }   // a stale intent: no job is waiting for it
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        h.postDelayed({ report("", "timeout"); finish() }, SnapLogic.TOTAL_MS)   // hard end
        h.postDelayed({ step(o) }, o.delay * 1000L)
    }

    private fun step(o: SnapOpts) {
        if (isFinishing) return
        if (o.flash == "off") return shoot(o, false)
        if (o.flash == "on") return flash(o, true)
        RemoteSnap.lux(this) { lux -> if (!isFinishing) flash(o, SnapLogic.flashWanted("auto", lux)) }
    }

    private fun flash(o: SnapOpts, want: Boolean) {
        if (!SnapLogic.screenFlash(o, want)) return shoot(o, want)   // back camera: torch (or none)
        showWhite()
        h.postDelayed({ shoot(o, true) }, SnapLogic.FLASH_WAIT_MS)
    }

    private fun showWhite() {
        white = true
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        h.postDelayed({ hideWhite() }, SnapLogic.FLASH_HARD_MS)   // hard removal, whatever the camera does
    }

    private fun hideWhite() {
        if (!white) return
        white = false
        runCatching {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    private fun shoot(o: SnapOpts, torch: Boolean) {
        if (isFinishing) return
        val dir = java.io.File(filesDir, "snaps").apply { mkdirs() }
        val started = Shot.take(this, o, dir, { id, ok ->
            h.post { hideWhite(); report(if (ok) id else "", if (ok) "" else "camera"); finish() }
        }, torch)
        if (!started) { hideWhite(); report("", "busy"); finish() }
    }

    private fun report(photoId: String, err: String) {
        if (reported) return
        reported = true
        RemoteSnap.finishFid(applicationContext, fid, photoId, err)
    }

    override fun onStop() { super.onStop(); if (!isFinishing) { hideWhite(); report("", "left"); finish() } }

    override fun onDestroy() {
        hideWhite()
        h.removeCallbacksAndMessages(null)
        report("", "closed")   // no-op when a photo was already reported
        super.onDestroy()
    }
}
