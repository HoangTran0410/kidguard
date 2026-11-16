package dev.kidguard

import android.content.Context

/** KidGuard Home has no relay and no Firebase: the kid flavor's push wake (src/kid/.../KidPush.kt) is a no-op here. */
object KidPush {
    const val token: String = ""
    fun init(c: Context) {}
}
