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
    const val HIST_KEEP = 100   // sessions kept on the phone
    const val HIST_STATE = 20   // sessions the state carries

    /** [a] (newest last) + one finished session {at, dur s, via}; sessions under 1 s are not worth a row; only the last [HIST_KEEP] stay. */
    fun addHist(a: org.json.JSONArray, at: Long, ms: Long, via: String): org.json.JSONArray {
        val out = org.json.JSONArray()
        if (ms < 1000) return a
        val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) } + org.json.JSONObject().put("at", at).put("dur", ms / 1000).put("via", if (via == "relay") "relay" else "lan")
        l.takeLast(HIST_KEEP).forEach { out.put(it) }
        return out
    }

    /** The newest [n] sessions, newest first (what state carries). */
    fun histTail(a: org.json.JSONArray, n: Int = HIST_STATE): org.json.JSONArray {
        val out = org.json.JSONArray(); for (i in a.length() - 1 downTo maxOf(0, a.length() - n)) out.put(a.get(i)); return out
    }

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

    /**
     * Real home apps among the CATEGORY_HOME activities ([pkg] to [cls]). Settings' FallbackHome (direct-boot placeholder,
     * priority -1000) also answers HOME: counting Settings as a launcher finished our task the moment a setup step opened
     * "About phone" inside it, and the child landed on the home screen (dead end in "full setup" from basic mode).
     */
    fun homes(acts: List<Pair<String, String>>, isSettings: (String) -> Boolean): Set<String> =
        acts.filter { (p, c) -> !c.endsWith("FallbackHome") && !isSettings(p) }.map { it.first }.toSet()
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

/** Screen frames over the relay socket (Relay.wsLiveLoop, worker Kid.phoneFrame). Pure, unit-tested. */
object LiveLogic {
    /** Unchanged picture: sent again after this long anyway (a viewer who just came gets one, the age stays fresh). */
    const val RESEND_MS = 5_000L
    /** No live:true from the worker for this long = nobody is watching (the page says it every 15 s). */
    const val LIVE_OK_MS = 45_000L

    fun send(changed: Boolean, sinceLast: Long): Boolean = changed || sinceLast >= RESEND_MS

    /** [2 bytes header length][header JSON {w,h[,err]}][JPEG or nothing]. */
    fun wsFrame(w: Int, h: Int, err: String, jpeg: ByteArray?): ByteArray {
        val hd = org.json.JSONObject().put("w", w).put("h", h).apply { if (err.isNotEmpty()) put("err", err) }.toString().toByteArray()
        val out = ByteArray(2 + hd.size + (jpeg?.size ?: 0))
        out[0] = (hd.size ushr 8).toByte(); out[1] = hd.size.toByte()
        System.arraycopy(hd, 0, out, 2, hd.size)
        jpeg?.let { System.arraycopy(it, 0, out, 2 + hd.size, it.size) }
        return out
    }
}
