package dev.kidguard

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * "Bố cục đã lưu": up to [MAX] named copies of the home (pages, dock, look, photo wallpaper) in filesDir/layouts/, so the
 * kid / parent switches between them in one tap (replaced the Bé / Teen / Gọn presets, owner 2026-11-11). Gestures are
 * not part of a layout. Applying goes through [HomeBackup.apply], so the one "Hoàn tác" step is the same as a restore's.
 * Files: <id>.json = {name, saved, pages, apps, sig, layout} and <id>.jpg when the layout had a photo wallpaper.
 */
object HomeSaved {
    const val MAX = 5
    data class Entry(val id: String, val name: String, val saved: Long, val pages: Int, val apps: Int, val sig: Int)

    private fun dir(ctx: Context) = File(ctx.filesDir, "layouts").apply { mkdirs() }

    /** What makes two homes "the same layout" for the "Đang dùng" mark: the grid, the pages and the look. */
    fun sig(m: HomeModel): Int = listOf(m.cols, m.pages, m.dock, m.style).hashCode()

    fun list(ctx: Context): List<Entry> = (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            Entry(f.name.removeSuffix(".json"), o.optString("name"), o.optLong("saved"), o.optInt("pages"), o.optInt("apps"), o.optInt("sig"))
        }.getOrNull()
    }.sortedBy { it.saved }

    /** Saves the home on screen now as [name]; null when it can't (no layout, full, write failed). */
    fun save(ctx: Context, name: String): Entry? {
        val m = HomeFile.load(ctx) ?: return null
        if (list(ctx).size >= MAX) return null
        val id = "l" + System.currentTimeMillis()
        val e = Entry(id, name.trim().take(30), System.currentTimeMillis(), m.pages.size, m.onHome().size, sig(m))
        return runCatching {
            val wall = File(ctx.filesDir, Wallpaper.PHOTO)
            if (m.style.wallKind == "photo" && wall.isFile && wall.length() <= HomeBackup.PHOTO_MAX) wall.copyTo(File(dir(ctx), "$id.jpg"), true)
            write(ctx, e, m.toJson())
            e
        }.getOrNull()
    }

    private fun write(ctx: Context, e: Entry, layout: JSONObject) {
        val o = JSONObject().put("name", e.name).put("saved", e.saved).put("pages", e.pages).put("apps", e.apps).put("sig", e.sig).put("layout", layout)
        val tmp = File(dir(ctx), "${e.id}.json.tmp"); tmp.writeText(o.toString())
        if (!tmp.renameTo(File(dir(ctx), "${e.id}.json"))) error("rename")
    }

    /** Switches the home to [e] (the current one becomes the undo step). The entry's sig is re-taken from the home as it
     *  now reads back, so "Đang dùng" marks it even when parsing normalised something. */
    fun apply(ctx: Context, e: Entry): Boolean {
        val f = File(dir(ctx), "${e.id}.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return false
        val m = HomeLayout.parse(o.optJSONObject("layout")?.toString()) ?: return false
        val photo = File(dir(ctx), "${e.id}.jpg").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
        if (!HomeBackup.apply(ctx, HomeBackup.Parsed(m, null, photo, 1))) return false
        HomeFile.load(ctx)?.let { now -> runCatching { write(ctx, e.copy(sig = sig(now)), o.getJSONObject("layout")) } }
        return true
    }

    fun rename(ctx: Context, e: Entry, name: String): Boolean = runCatching {
        val o = JSONObject(File(dir(ctx), "${e.id}.json").readText())
        write(ctx, e.copy(name = name.trim().take(30)), o.getJSONObject("layout")); true
    }.getOrDefault(false)

    fun delete(ctx: Context, e: Entry) { File(dir(ctx), "${e.id}.json").delete(); File(dir(ctx), "${e.id}.jpg").delete() }
}
