package dev.kidguard

import android.content.Context
import android.util.Log
import java.io.File

/** filesDir/home.json (HomeLayout model), written atomically (tmp + rename). A file that can't be read is kept as home.json.bad. */
object HomeFile {
    private const val NAME = "home.json"
    @Volatile private var cache: HomeModel? = null

    private fun file(ctx: Context) = File(ctx.filesDir, NAME)

    @Synchronized fun load(ctx: Context): HomeModel? {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return null
        val m = HomeLayout.parse(runCatching { f.readText() }.getOrNull())
        if (m == null) { keepBad(ctx); Log.w("KidGuard", "home.json unreadable, kept as .bad") }
        cache = m
        return m
    }

    /** home.json -> home.json.bad. A .bad that still parses (an earlier layout) moves to .bad.old first, so a second reset can't destroy it. */
    private fun keepBad(ctx: Context) {
        val bad = File(ctx.filesDir, "$NAME.bad")
        runCatching {
            if (bad.exists() && HomeLayout.parse(runCatching { bad.readText() }.getOrNull()) != null) bad.renameTo(File(ctx.filesDir, "$NAME.bad.old"))
            file(ctx).renameTo(bad)
        }
    }

    @Synchronized fun save(ctx: Context, m: HomeModel) {
        if (m == cache) return
        // the cache is set only after the file is in place, so a failed write is tried again by the next save
        runCatching {
            val tmp = File(ctx.filesDir, "$NAME.tmp")
            tmp.writeText(m.toJson().toString())
            // rename replaces the target atomically (Android: rename(2)); no delete first, which could lose the file
            if (!tmp.renameTo(file(ctx))) error("rename failed")
            cache = m
        }.onFailure { Log.w("KidGuard", "home.json write", it) }
    }

    /** After the 2nd crash in 5 min: start over (style and layout). */
    @Synchronized fun reset(ctx: Context) {
        cache = null
        keepBad(ctx)
    }

    /** A new app version after a crash [reset]: put the kid's layout back if it still parses (the crash may have been the code's). */
    @Synchronized fun restoreBad(ctx: Context): Boolean {
        val bad = File(ctx.filesDir, "$NAME.bad")
        if (HomeLayout.parse(runCatching { bad.readText() }.getOrNull()) == null) return false
        cache = null
        file(ctx).delete()
        return bad.renameTo(file(ctx))
    }

    fun styleId(ctx: Context): String = load(ctx)?.style?.preset ?: ""
    fun pages(ctx: Context): Int = load(ctx)?.pages?.size ?: 0
}
