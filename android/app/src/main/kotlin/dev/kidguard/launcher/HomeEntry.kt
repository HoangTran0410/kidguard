package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * "KidGuard Home": the kid launcher's own icon in the phone's app list (Nova / Niagara style), no detour through
 * KidGuard. No UI of its own: opens the kid home. Before any parent and without Device Owner it is the user's own
 * opt-in, the same as the Features sheet's "Dùng màn hình chính KidGuard"; once paired the parent's rule decides,
 * so a home the parent turned off opens KidGuard with a note instead.
 */
class HomeEntry : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Flavor.HOME) {   // KidGuard Home: the launcher is the app (a 3rd crash in 5 min turned it off: tapping the icon is the way back)
            runCatching { packageManager.setComponentEnabledSetting(LauncherSafe.comp(this), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP) }
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        val s = Store.of(this)
        if (!LauncherSafe.wanted(this, s.rules) && !Policy.isOwner(this) && !s.everPaired && !s.released && !LauncherSafe.inSafeMode(this))
            LauncherSafe.setLocal(this, true)
        if (LauncherSafe.wanted(this, s.rules) && LauncherSafe.enabled(this))
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else {
            Toast.makeText(this, R.string.home_app_off, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
