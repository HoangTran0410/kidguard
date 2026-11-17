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
        if (!file(ctx).exists()) return null
        val m = read(ctx.filesDir) { runCatching { Log.w("KidGuard", it) } }
        cache = m
        if (m != null) AppLook.set(m.custom)
        return m
    }

    /**
     * home.json, or (unreadable: kept as .bad) the last layout that was saved fine, home.json.prev, put back in place.
     * Stuck audit 2026-11-17: a broken home.json reset the kid's whole layout and look with no way back. No Android here (tests).
     */
    internal fun read(dir: File, log: (String) -> Unit = {}): HomeModel? {
        val f = File(dir, NAME)
        HomeLayout.parse(runCatching { f.readText() }.getOrNull())?.let { return it }
        keepBad(dir); log("home.json unreadable, kept as .bad")
        val prev = File(dir, "$NAME.prev")
        val m = HomeLayout.parse(runCatching { prev.readText() }.getOrNull()) ?: return null
        runCatching { prev.copyTo(f, overwrite = true) }
        log("home.json restored from .prev")
        return m
    }

    /** home.json -> home.json.bad. A .bad that still parses (an earlier layout) moves to .bad.old first, so a second reset can't destroy it. */
    private fun keepBad(dir: File) {
        val bad = File(dir, "$NAME.bad")
        runCatching {
            if (bad.exists() && HomeLayout.parse(runCatching { bad.readText() }.getOrNull()) != null) bad.renameTo(File(dir, "$NAME.bad.old"))
            File(dir, NAME).renameTo(bad)
        }
    }

    /** True when [m] is on disk (or already was); callers that don't care can ignore it. */
    @Synchronized fun save(ctx: Context, m: HomeModel): Boolean {
        AppLook.set(m.custom)
        if (m == cache) return true
        // the cache is set only after the file is in place, so a failed write is tried again by the next save
        return runCatching { write(ctx.filesDir, m); cache = m }.onFailure { Log.w("KidGuard", "home.json write", it) }.isSuccess
    }

    /** home.json, then the same text as home.json.prev (the copy [read] falls back to), each tmp + rename. */
    internal fun write(dir: File, m: HomeModel) {
        val txt = m.toJson().toString()
        for (n in listOf(NAME, "$NAME.prev")) {
            val tmp = File(dir, "$n.tmp")
            tmp.writeText(txt)
            // rename replaces the target atomically (Android: rename(2)); no delete first, which could lose the file
            if (!tmp.renameTo(File(dir, n))) error("rename failed")
        }
    }

    /** After the 2nd crash in 5 min: start over (style and layout). The .prev copy goes too, or [read] would bring the crash back. */
    @Synchronized fun reset(ctx: Context) = reset(ctx.filesDir).also { AppLook.set(emptyMap()) }
    internal fun reset(dir: File) {
        cache = null
        keepBad(dir)
        File(dir, "$NAME.prev").delete()
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
