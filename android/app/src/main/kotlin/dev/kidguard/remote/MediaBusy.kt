package dev.kidguard

/**
 * One "is the camera / mic taken" check for every media feature (video call, listen call, LAN call, live camera,
 * Telegram clip, listen). [me] = the caller's own name, never counted against itself ("call", "listencall", "lancall",
 * "cam", "clip", "listen"). It used to be copied in 7 places and Call / ListenCall / LanCall missed [CamClip].
 */
object MediaBusy {
    fun busy(me: String): Boolean =
        (me != "call" && Call.cur != null) || (me != "listencall" && ListenCall.active) || (me != "cam" && CamCall.active) ||
            (me != "listen" && Listen.active) || (me != "clip" && CamClip.active)
}
