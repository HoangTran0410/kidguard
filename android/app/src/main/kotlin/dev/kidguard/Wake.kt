package dev.kidguard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

/** Command `wake`: the parent turns the child's screen on (a failing power button). See docs/protocol.md. */
object Wake {
    /** Screen on, plus the keyguard dropped when it has no secure lock. Blocks up to 2 s (HTTP / relay thread). "ok" | "failed". */
    fun now(ctx: Context): String {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return "failed"
        // API 26: the wakelock does it; API 27+ the activity's setTurnScreenOn does (both are tried everywhere)
        runCatching {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "kidguard:wake")
                .acquire(3_000)
        }.onFailure { Log.w("KidGuard", "wake lock", it) }
        runCatching {
            ctx.startActivity(Intent(ctx, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY))
        }.onFailure { Log.w("KidGuard", "wake activity", it) }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var n = 20
            while (n-- > 0 && !pm.isInteractive) Thread.sleep(100)
        }
        return if (pm.isInteractive) "ok" else "failed"
    }
}

/** Invisible: turns the screen on over the lock screen, asks to dismiss a non-secure keyguard, then goes away. */
class WakeActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && WakeLogic.dismiss(km.isKeyguardLocked, km.isKeyguardSecure)) runCatching {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
            })
        }
        h.postDelayed({ if (!isFinishing) finish() }, 1_500)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
