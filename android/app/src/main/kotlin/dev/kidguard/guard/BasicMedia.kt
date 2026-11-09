package dev.kidguard

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager

/** Basic mode: a bounce only sends the child HOME, the app's audio would play on. Pause that package's session (decision: BasicLogic.shouldPause). */
object BasicMedia {
    /** Needs notification access (NotifDots); without it, or on any error, nothing happens. Best effort, never throws. */
    fun pauseOf(ctx: Context, pkg: String) {
        runCatching {
            if (!NotifDots.granted(ctx)) return
            val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
            for (c in msm.getActiveSessions(ComponentName(ctx, NotifDots::class.java))) {
                if (!BasicLogic.shouldPause(c.packageName, c.playbackState?.state ?: 0, pkg)) continue
                runCatching { c.transportControls.pause() }.onFailure { runCatching { c.transportControls.stop() } }
            }
        }
    }
}
