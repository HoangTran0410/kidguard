package dev.kidguard

/**
 * The kid's control center (swipe down on the right half of the home): what each tile can do on this phone. Pure, so
 * the rules are unit-tested (ControlLogicTest).
 *  - DIRECT: the app changes it itself (Device Owner, WRITE_SECURE_SETTINGS from setup, or a plain API).
 *  - PAGE: only the system can: the tile opens the system panel / page, and only where the kid may open Settings.
 *  - LOCKED: shown with its state and a lock; a tap says a parent decides it.
 *  - HIDDEN: the phone has no such thing (no flash).
 */
object ControlLogic {
    enum class Mode { DIRECT, PAGE, LOCKED, HIDDEN }
    enum class Tile { WIFI, BT, AIRPLANE, DATA, TORCH, ROTATE, DND, BRIGHT, VOLUME, AUTO_BRIGHT, NIGHT, DARK, RINGER }

    data class Phone(
        val sdk: Int,
        val owner: Boolean,           // KidGuard is Device Owner
        val settingsOk: Boolean,      // the kid may open Settings (rule, unmanaged phone, or KidGuard Home)
        val noAirplane: Boolean,      // rule: airplane mode blocked (Device Owner restriction)
        val writeSys: Boolean,        // may write Settings.System (Device Owner 9+, WRITE_SECURE_SETTINGS, or the app-op)
        val dnd: Boolean,             // Do Not Disturb policy access
        val flash: Boolean,
        val hasBt: Boolean,
        val secure: Boolean = false,  // WRITE_SECURE_SETTINGS (setup grants it): night light
        val night: Boolean = false,   // the phone has a night light (config_nightDisplayAvailable)
        val darkLocked: Boolean = true, // config_lockDayNightMode: setNightMode needs a system permission (AOSP default)
    )

    fun mode(t: Tile, p: Phone): Mode {
        val page = if (p.settingsOk) Mode.PAGE else Mode.LOCKED
        return when (t) {
            // targetSdk 29+: setWifiEnabled is refused for normal apps on Android 10+, a Device Owner is exempt
            Tile.WIFI -> if (p.owner || p.sdk < 29) Mode.DIRECT else page
            // Android 13+: enable()/disable() refused unless Device Owner; 12: needs BLUETOOTH_CONNECT (owner grants it)
            Tile.BT -> if (!p.hasBt) Mode.HIDDEN else if (p.sdk <= 30 || p.owner) Mode.DIRECT else page
            Tile.AIRPLANE -> if (p.noAirplane && p.owner) Mode.LOCKED else page
            Tile.DATA -> page
            Tile.TORCH -> if (p.flash) Mode.DIRECT else Mode.HIDDEN
            Tile.ROTATE, Tile.BRIGHT -> if (p.writeSys) Mode.DIRECT else page
            Tile.DND -> if (p.dnd) Mode.DIRECT else page
            Tile.VOLUME -> Mode.DIRECT
            Tile.AUTO_BRIGHT -> if (p.writeSys) Mode.DIRECT else page
            Tile.NIGHT -> if (!p.night) Mode.HIDDEN else if (p.secure) Mode.DIRECT else page
            // UiModeManager.setNightMode works for apps only where the build does not lock it (AOSP locks it: "Night mode
            // locked, requires MODIFY_DAY_NIGHT_MODE"); writing Secure ui_night_mode applies only after a reboot. Else the page
            Tile.DARK -> if (p.sdk < 29) Mode.HIDDEN else if (!p.darkLocked) Mode.DIRECT else page
            // silent needs Do Not Disturb access (Android 7+), sound / vibrate do not
            Tile.RINGER -> Mode.DIRECT
        }
    }

    /** Brightness slider position 0..1 to a setting value: squared, so the dim end gets most of the travel (as Android does). */
    fun brightValue(f: Float, max: Int): Int = (f.coerceIn(0f, 1f).let { it * it } * (max - 1)).toInt() + 1
    fun brightPos(v: Int, max: Int): Float = if (max <= 1) 1f else kotlin.math.sqrt(((v - 1).toFloat() / (max - 1)).coerceIn(0f, 1f))
}
