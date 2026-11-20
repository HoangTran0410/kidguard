package dev.kidguard

/**
 * One "is the camera / mic taken" check for every media feature (video call, listen call, LAN call, live camera,
 * Telegram clip, listen). [me] = the caller's own name, never counted against itself ("call", "listencall", "lancall",
 * "cam", "clip", "listen"). It used to be copied in 7 places and Call / ListenCall / LanCall missed [CamClip].
 * Skipping [me] is for a feature that replaces its own session; Call does NOT (a 2nd parent's ring is busy): it checks Call.cur itself.
 */
object MediaBusy {
    fun busy(me: String): Boolean =
        busyOf(me, Call.cur != null, ListenCall.active, CamCall.active, Listen.active, CamClip.active)

    /** Pure, unit-tested. */
    fun busyOf(me: String, call: Boolean, listenCall: Boolean, cam: Boolean, listen: Boolean, clip: Boolean): Boolean =
        (me != "call" && call) || (me != "listencall" && listenCall) || (me != "cam" && cam) ||
            (me != "listen" && listen) || (me != "clip" && clip)
}
