package dev.kidguard.parent

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal

/** Pure part of the app lock (unit-tested). */
object AppLockLogic {
    /** Away longer than this (app in the background) = ask again. */
    const val AWAY_MS = 5 * 60_000L
    /** Lock now? Only when the parent wants it and the phone has a screen lock to check against. */
    fun needed(on: Boolean, secure: Boolean, unlockedAt: Long, leftAt: Long, now: Long): Boolean =
        on && secure && (unlockedAt <= 0L || leftAt > 0L && now - leftAt >= AWAY_MS)
}

/**
 * Parent app lock (owner 2026-11-16: "ai lấy được điện thoại bố mẹ cũng điều khiển được máy con"; the likeliest one is the
 * kid). Uses the phone's own lock: fingerprint / face / screen PIN, like banking apps. On by default; turning it off asks
 * too. A phone with no screen lock is not locked (nothing to check against): the settings page says so.
 * Covers the page (MainActivity) and the "grant time" / "approve" buttons of notifications ([AuthActivity]).
 */
object AppLock {
    @Volatile var unlockedAt = 0L
    @Volatile var leftAt = 0L
    @Volatile var asking = false

    fun on(c: Context): Boolean = Store.appLock(c) && Logic.parseKids(Store.kids(c)).isNotEmpty()   // nothing to guard before the first kid
    fun secure(c: Context): Boolean = c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    fun needed(c: Context, now: Long = System.currentTimeMillis()) = AppLockLogic.needed(on(c), secure(c), unlockedAt, leftAt, now)
    /** Notification buttons that change the kid's phone go through [AuthActivity] while this is true. */
    fun guardsActions(c: Context) = on(c) && secure(c)

    fun ok() { unlockedAt = System.currentTimeMillis(); leftAt = 0L }

    const val REQ = 73

    /** Asks for the phone's lock. Android 10+: system prompt (biometric or screen lock); 8-9: the screen-lock screen, answered in onActivityResult. */
    fun ask(a: Activity, done: (Boolean) -> Unit) {
        if (asking) return
        asking = true
        val title = a.getString(R.string.lock_title)
        if (Build.VERSION.SDK_INT >= 29) {
            val b = BiometricPrompt.Builder(a).setTitle(title).setSubtitle(a.getString(R.string.lock_sub))
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            val r = runCatching {
                b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { asking = false; ok(); done(true) }
                    override fun onAuthenticationError(code: Int, msg: CharSequence?) { asking = false; done(false) }
                })
            }
            if (r.isFailure) { asking = false; done(false) }
        } else {
            pending = done
            @Suppress("DEPRECATION")
            val i = a.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, a.getString(R.string.lock_sub))
            if (i == null) { asking = false; pending = null; ok(); done(true); return }   // no lock to confirm
            runCatching { a.startActivityForResult(i, REQ) }.onFailure { asking = false; pending = null; done(false) }
        }
    }
    private var pending: ((Boolean) -> Unit)? = null
    /** Android 8-9: the result of [ask]'s screen-lock screen. */
    fun result(ok: Boolean) { asking = false; if (ok) ok(); pending?.invoke(ok); pending = null }
}

/**
 * Invisible step between a notification button and its receiver while the app lock is on: the phone's lock first,
 * then the original broadcast (extra [FWD]). Without it anyone holding the unlocked phone could grant time from the shade.
 */
class AuthActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION")
        val fwd = intent.getParcelableExtra<Intent>(FWD)
        if (fwd == null || fwd.component?.packageName != packageName) { finish(); return }
        if (!AppLock.needed(this)) { sendBroadcast(fwd); finish(); return }   // the parent unlocked the app moments ago
        AppLock.ask(this) { ok -> if (ok) sendBroadcast(fwd); finish() }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == AppLock.REQ) AppLock.result(res == RESULT_OK) else super.onActivityResult(req, res, data)
    }
    companion object { const val FWD = "fwd" }
}
