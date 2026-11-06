package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Accessibility side: live screen, remote input, instant app-switch events. See docs/protocol.md.
 * STUB: the signatures in the companion are the contract other files compile against.
 */
class Acc : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var running: Acc? = null

        /** Enabled in Settings and connected right now. */
        fun isOn(ctx: Context): Boolean = running != null

        /** Turns our service back on if the child turned it off (needs WRITE_SECURE_SETTINGS from setup). True if on. */
        fun ensureEnabled(ctx: Context): Boolean = isOn(ctx)

        /** One JPEG of the screen, longest side <= [maxSide]. Blocks up to 3 s. Null + [lastError] when it cannot. */
        fun capture(maxSide: Int = 720, quality: Int = 50): Frame? = null

        /** Runs one input object (docs/protocol.md "Input"). Returns "ok" or a short error. */
        fun input(o: JSONObject): String = "acc off"

        @Volatile var lastError: String = "acc off"
    }

    /** [w], [h]: the real screen size in px, so the page can map clicks. */
    class Frame(val jpeg: ByteArray, val w: Int, val h: Int)
}
