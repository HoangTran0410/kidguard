package dev.kidguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/** Android side of [Gray]: bedtime wind-down grayscale through Settings.Secure (needs WRITE_SECURE_SETTINGS). */
object GrayFx {
    @Volatile var lastErr = ""
    @Volatile var applied = false   // our grayscale is on right now

    fun perm(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** One Guard tick: switch on / keep on / put back. Writes nothing when the phone already matches. */
    fun tick(ctx: Context, s: Store, want: Boolean) = run(ctx, s, want)

    /** `release` and "wind-down off": put back what the phone had before (no-op when we never switched it on). */
    fun restore(ctx: Context, s: Store) = run(ctx, s, false)

    private fun run(ctx: Context, s: Store, want: Boolean) {
        val owned = s.grayOwned
        if (!want && !owned) { applied = false; lastErr = ""; return }
        if (!perm(ctx)) { lastErr = "no permission"; applied = false; return }
        val cr = ctx.contentResolver
        val step = Gray.step(want, owned, s.graySaved, Settings.Secure.getString(cr, Gray.KEY_ON), Settings.Secure.getString(cr, Gray.KEY_MODE))
        // remember the old values before the first write, so a crash halfway still restores them
        if (step.owned && !owned) s.setGray(true, step.saved)
        val ok = runCatching { step.write.forEach { (k, v) -> check(Settings.Secure.putString(cr, k, v)) { "put $k" } } }
            .onFailure { Log.w("KidGuard", "grayscale", it) }.isSuccess
        if (!ok) {
            lastErr = "write failed"
            if (step.owned && !owned) s.setGray(false, null)   // never switched on: nothing to put back
            applied = false; return
        }
        lastErr = ""
        if (!step.owned) s.setGray(false, null)
        applied = step.owned
    }

    /** State `windDown` (protocol v0.9.7). */
    fun state(ctx: Context, r: Rules, active: Boolean): JSONObject = JSONObject()
        .put("active", active).put("gray", applied).put("perm", perm(ctx)).put("err", if (r.windGray) lastErr else "")
}
