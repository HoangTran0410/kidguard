package dev.kidguard

/** Pure parts of wake / MediaProjection screen view / listen checks. Android-free, unit-tested (LiveLogicTest). */
object WakeLogic {
    /** Ask the system to drop the keyguard only when it is up and has no PIN / pattern / password (else the bouncer would show). */
    fun dismiss(locked: Boolean, secure: Boolean): Boolean = locked && !secure
}

object ProjLogic {
    /** Below this API level frames come from MediaProjection (Acc.takeScreenshot is API 30+). */
    const val ACC_SHOT_API = 30
    /** Min ms between two real frames from the projection (~1 fps: old phones, every few seconds is enough). */
    const val GAP_MS = 1_000L
    /** Projection stops this long after the last capture (the parent stopped viewing). */
    const val IDLE_MS = 20_000L
    /** After "Cancel" (or a dialog that never answered) wait this long before asking again. */
    const val RETRY_MS = 60_000L

    fun useProjection(sdk: Int) = sdk < ACC_SHOT_API

    /** Size of the virtual display: the screen scaled so its longest side is <= [maxSide], even numbers, never upscaled. */
    fun fit(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 2 to 2
        val k = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
        fun even(x: Double) = (x.toInt() and 1.inv()).coerceAtLeast(2)
        return even(w * k) to even(h * k)
    }

    /** Width in px of a row as the ImageReader lays it out (rows can be padded past the image width). */
    fun rowPx(rowStride: Int, pixelStride: Int, width: Int): Int = if (pixelStride <= 0) width else maxOf(width, rowStride / pixelStride)

    /** What to do when a frame is wanted and there is no projection: "ask" = show the consent dialog, "wait" = keep waiting. */
    fun next(state: String, now: Long, askedAt: Long, deniedAt: Long): String = when (state) {
        "asking" -> if (now - askedAt > RETRY_MS) "ask" else "wait"
        "denied" -> if (now - deniedAt > RETRY_MS) "ask" else "wait"
        else -> "ask"
    }

    /** The lastError the page sees while there is no projection. */
    fun waitError(state: String): String = if (state == "denied") "denied" else "need consent"
}

object ListenLogic {
    /** How long the mic may give nothing but silence (amplitude 0) before we call it blocked. */
    const val SILENT_MS = 2_500L
    /** No bytes from the encoder for this long = the mic is stuck: end the session. */
    const val STALL_MS = 4_000L

    /** [amps]: MediaRecorder.getMaxAmplitude() samples from the start. A real mic is never exactly 0 for 2.5 s; a blocked one is. */
    fun blocked(amps: List<Int>, elapsedMs: Long): Boolean = elapsedMs >= SILENT_MS && amps.isNotEmpty() && amps.all { it <= 0 }

    fun stalled(lastDataAt: Long, now: Long): Boolean = now - lastDataAt > STALL_MS
}

object TaskLogic {
    /**
     * Finish our tasks when the front window is the launcher or a Recents screen (AOSP: systemui RecentsActivity;
     * ColorOS / Android 10+: inside the launcher or com.coloros.recents). Settings pages opened from setup keep the task.
     */
    fun dropTasks(pkg: String, cls: String?, self: String, homes: Set<String>): Boolean =
        pkg != self && (pkg in homes || pkg.contains("recents", true) || (cls?.contains("recents", true) == true))
}

/** Which phone actions refuse each other (same table in docs/protocol.md "Action states" and on the page). */
object ActionLogic {
    /** Null = go ahead, else the result code the phone answers. Ringing drowns out listening; listening would only hear the alarm. */
    fun conflict(action: String, ringing: Boolean, listening: Boolean): String? = when (action) {
        "ring" -> if (listening) "listening" else null
        "listen" -> if (ringing) "ringing" else null
        else -> null
    }
}
