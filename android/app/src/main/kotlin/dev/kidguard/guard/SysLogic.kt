package dev.kidguard

/**
 * Remote phone settings (command `setting {key, value}`), pure part: no Android classes, unit-tested in SysLogicTest.
 * [parse] checks the key and the value's type; [clamp] fits a number into what the phone allows.
 */
object SysLogic {
    const val VOL_RING = "vol.ring"; const val VOL_MEDIA = "vol.media"; const val VOL_ALARM = "vol.alarm"
    const val BRIGHT = "bright"; const val AUTO = "autoBright"; const val TIMEOUT = "timeoutMs"; const val DND = "dnd"
    val VOLS = listOf(VOL_RING, VOL_MEDIA, VOL_ALARM)
    val KEYS = VOLS + listOf(BRIGHT, AUTO, TIMEOUT, DND)
    /** Screen timeout bounds: below 15 s the phone is unusable, above 10 min it never sleeps (battery, burn-in). */
    const val TIMEOUT_MIN = 15_000; const val TIMEOUT_MAX = 600_000

    /** A checked request: [n] for numbers (volume step, brightness, ms), [on] for switches. */
    data class Setting(val key: String, val n: Int = 0, val on: Boolean = false)

    /** null = "bad key" / "bad value" (see [error]). Numbers must be JSON numbers (no strings), switches JSON booleans. */
    fun parse(key: String, v: Any?): Setting? = when (key) {
        AUTO, DND -> (v as? Boolean)?.let { Setting(key, on = it) }
        in VOLS, BRIGHT, TIMEOUT -> (v as? Number)?.toDouble()?.takeIf { it.isFinite() }
            ?.let { Setting(key, n = it.coerceIn(-1e9, 1e9).toInt()) }
        else -> null
    }

    fun error(key: String): String = if (key in KEYS) "bad value" else "bad key"

    /** Volume step in 0..[max]; brightness in 1..[max] (0 turns some panels fully dark); timeout in the bounds above. */
    fun clamp(s: Setting, max: Int): Int = when (s.key) {
        in VOLS -> s.n.coerceIn(0, maxOf(0, max))
        BRIGHT -> s.n.coerceIn(1, maxOf(1, max))
        TIMEOUT -> s.n.coerceIn(TIMEOUT_MIN, TIMEOUT_MAX)
        else -> s.n
    }
}
