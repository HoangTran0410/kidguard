package dev.kidguard

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * "Sao lưu / Khôi phục màn hình chính" (Tuỳ chỉnh): the whole layout (HomeModel JSON: pages, dock, folders, widgets, notes, to-dos,
 * timetable, style) + the three gestures + the photo wallpaper when it is small, as one JSON file the user keeps (Storage Access
 * Framework, no storage permission). Restore parses through [HomeLayout.parse] (repairs and caps everything), keeps unknown packages
 * (they are simply not shown) and first saves the current layout as home.undo.json so one restore can be undone.
 * Format: {"format":"kidguard-home-backup","version":1,"layout":{home.json},"gest":{..},"wall":"<base64 jpeg>"}; a bare home.json is accepted too.
 */
object HomeBackup {
    const val FORMAT = "kidguard-home-backup"
    const val VERSION = 1
    const val MAX_BYTES = 2L shl 20          // file cap (layout is ~20-60 KB; the photo is <= 300 KB from Tuỳ chỉnh)
    const val PHOTO_MAX = 400L shl 10        // a bigger wall.jpg is left out of the backup
    private const val UNDO = "home.undo.json"

    class Parsed(val model: HomeModel, val gest: JSONObject?, val photo: ByteArray?, val version: Int)

    /** The backup text. [photo] is null (or too big) -> the wallpaper is left out; the second value says whether it was included. */
    fun export(m: HomeModel, gest: JSONObject?, photo: ByteArray?): Pair<String, Boolean> {
        val inc = photo != null && photo.size <= PHOTO_MAX
        val o = JSONObject().put("format", FORMAT).put("version", VERSION).put("saved", System.currentTimeMillis())
            .put("layout", m.toJson()).put("gest", gest ?: JSONObject())
        if (inc) o.put("wall", Base64.encodeToString(photo, Base64.NO_WRAP))
        return o.toString() to inc
    }

    /** Null when it isn't a backup we can use (not JSON, too big, newer version, layout won't parse). Never throws. */
    fun parse(text: String?): Parsed? {
        if (text == null || text.length > MAX_BYTES) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.has("format")) {
            if (o.optString("format") != FORMAT) return null
            val v = o.optInt("version", 0)
            if (v < 1 || v > VERSION) return null
            val lay = o.optJSONObject("layout") ?: return null
            val m = HomeLayout.parse(lay.toString()) ?: return null
            val photo = o.optString("wall").takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                ?.takeIf { it.size in 4..PHOTO_MAX.toInt() && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }   // JPEG magic
            return Parsed(m, o.optJSONObject("gest"), photo, v)
        }
        if (!o.has("pages")) return null   // a plain home.json
        return Parsed(HomeLayout.parse(text) ?: return null, null, null, 1)
    }

    /** Reads at most [MAX_BYTES] from [uri]; null if bigger or unreadable. */
    fun read(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { inp ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0L
            while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) return@use null; out.write(buf, 0, n) }
            out.toString("UTF-8")
        }
    }.getOrNull()

    fun write(ctx: Context, uri: Uri, text: String): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
    }.getOrDefault(false)

    private fun wallFile(ctx: Context) = File(ctx.filesDir, Wallpaper.PHOTO)
    private fun photo(ctx: Context): ByteArray? = wallFile(ctx).takeIf { it.isFile && it.length() <= PHOTO_MAX }?.let { runCatching { it.readBytes() }.getOrNull() }

    /** Backup of the layout on screen now. Returns (text, photoIncluded) or null when there is no layout. */
    fun current(ctx: Context): Pair<String, Boolean>? {
        val m = HomeFile.load(ctx) ?: return null
        return export(m, Gest.toJson(ctx), photo(ctx))
    }

    /** Installed packages of the layout that this phone doesn't have (shown count in the confirm dialog). */
    fun missing(ctx: Context, m: HomeModel): Int = m.onHome().count { p -> runCatching { ctx.packageManager.getApplicationInfo(p, 0) }.isFailure }

    fun hasUndo(ctx: Context) = File(ctx.filesDir, UNDO).isFile

    /** Applies [p]: the current layout is kept as the one undo step first. False when nothing could be saved. */
    fun apply(ctx: Context, p: Parsed, keepUndo: Boolean = true): Boolean {
        val before = current(ctx)
        if (keepUndo && before != null) runCatching {
            val tmp = File(ctx.filesDir, "$UNDO.tmp"); tmp.writeText(before.first)
            if (!tmp.renameTo(File(ctx.filesDir, UNDO))) error("rename")
        }.onFailure { return false }   // no undo copy, no restore
        var m = p.model
        val noPhoto = m.copy(style = m.style.copy(wallKind = "builtin", wall = ""))
        // the photo goes to a temp file first: the layout is saved before wall.jpg or the gestures are replaced, so a failed write changes nothing
        val ptmp = File(ctx.filesDir, Wallpaper.PHOTO + ".tmp")
        val staged = p.photo != null && runCatching { ptmp.writeBytes(p.photo) }.isSuccess
        if (p.photo != null && !staged) m = noPhoto
        else if (p.photo == null && m.style.wallKind == "photo" && !wallFile(ctx).isFile) m = noPhoto   // photo not in the backup, none here
        if (!HomeFile.save(ctx, m)) { ptmp.delete(); return false }
        if (staged && !runCatching { ptmp.renameTo(wallFile(ctx)) }.getOrDefault(false)) { ptmp.delete(); HomeFile.save(ctx, noPhoto) }
        p.gest?.let { Gest.fromJson(ctx, it) }
        IconCache.clear()
        return true
    }

    /** Puts the layout from before the last restore back (once). Returns false when there is none. */
    fun undo(ctx: Context): Boolean {
        val f = File(ctx.filesDir, UNDO)
        val p = parse(runCatching { f.readText() }.getOrNull()) ?: return false
        if (!apply(ctx, p, keepUndo = false)) return false
        f.delete()
        return true
    }
}
