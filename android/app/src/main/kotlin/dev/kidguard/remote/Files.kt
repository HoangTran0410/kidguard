package dev.kidguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import dev.kidguard.FilesLogic.Entry
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The parent <-> child shared folder (docs/protocol.md "Shared files"). Only what was sent is listed and served:
 * parent -> kid files land in the public Download/KidGuard (MediaStore on API 29+, WRITE_EXTERNAL_STORAGE granted
 * by the Device Owner on 26-28), kid -> parent files are copied into our own outbox (no URI grants kept).
 * The index (prefs "kgfiles") is the only list: nothing else on the phone is reachable through it.
 */
object Files {
    private const val TAG = "KidGuard"
    private const val SUB = "KidGuard"
    private val lock = Any()
    private var cache: List<Entry>? = null

    // ---------------- index ----------------
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("kgfiles", 0)
    fun all(c: Context): List<Entry> = synchronized(lock) { cache ?: FilesLogic.parseIndex(prefs(c).getString("idx", "[]")).also { cache = it } }
    private fun save(c: Context, l: List<Entry>) = synchronized(lock) { cache = l; prefs(c).edit().putString("idx", FilesLogic.indexJson(l)).commit() }
    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }
    private fun put(c: Context, e: Entry) = synchronized(lock) {
        val (list, drop) = FilesLogic.add(all(c), e)
        save(c, list)
        drop.filter { it.dir == FilesLogic.OUT }.forEach { runCatching { File(it.loc).delete() } }
    }
    private fun setSt(c: Context, id: String, st: String) { synchronized(lock) {
        val l = all(c); if (l.none { it.id == id && it.st != st }) return
        save(c, l.map { if (it.id == id) it.copy(st = st) else it })
    } }

    /** State JSON "files": newest [FilesLogic.STATE_N], no phone paths. */
    fun state(c: Context) = FilesLogic.listJson(all(c), FilesLogic.STATE_N)

    private val rnd = SecureRandom()
    private fun newId(): String = ByteArray(8).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
    private fun mimeOf(name: String, hint: String?): String {
        val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FilesLogic.ext(name))
        return m ?: hint?.substringBefore(';')?.trim()?.takeIf { Regex("^[\\w.+-]+/[\\w.+-]+$").matches(it) && it != "text/html" } ?: FilesLogic.OCTET
    }
    private fun outbox(c: Context) = File(c.filesDir, "outbox").apply { mkdirs() }
    private fun kick() { Guard.running?.kick() }

    // ---------------- LAN API: /api/files... (Api.kt hands it over after the token / cookie check) ----------------
    fun api(c: Context, r: Req, rest: String): Res {
        val seg = rest.removePrefix("/")
        return when {
            seg.isEmpty() && r.method == "GET" -> Res.json(JSONObject().put("files", FilesLogic.listJson(all(c)))
                .put("maxLan", FilesLogic.MAX_LAN).put("maxRelay", FilesLogic.MAX_RELAY).toString())
            r.method == "PUT" -> {
                val raw = runCatching { URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return err("bad name", 400)
                val ins = r.input ?: return err("no body", 400)
                val (e, code) = receive(c, raw, r.length, r.header("content-type"), ins, r.query["id"])
                if (e == null) err(code, when (code) { "too big" -> 413; "no space" -> 507; "failed" -> 500; else -> 400 })
                else Res.json(JSONObject().put("file", e.toJson()).toString())
            }
            !FilesLogic.isId(seg) -> err("not found", 404)
            r.method == "GET" -> download(c, seg)
            r.method == "DELETE" -> if (delete(c, seg)) Res.json("""{"result":"ok"}""") else err("no such file", 404)
            else -> err("not found", 404)
        }
    }
    private fun err(code: String, http: Int) = Res.json(JSONObject().put("error", code).toString(), http)

    private fun download(c: Context, id: String): Res {
        val e = get(c, id) ?: return err("no such file", 404)
        val ins = openIn(c, e) ?: run { if (e.dir == FilesLogic.IN) setSt(c, id, FilesLogic.GONE); return err("gone", 404) }
        // always octet-stream + attachment: a kid's .html must never render on the phone's origin
        return Res(200, FilesLogic.OCTET, ByteArray(0), mapOf("Content-Length" to e.size.toString(),
            "Content-Disposition" to FilesLogic.disposition(e.name)), stream = { out ->
            try {
                val n = ins.use { copy(it, out, e.size) }
                out.flush()
                if (n == e.size && e.dir == FilesLogic.OUT) { setSt(c, id, FilesLogic.FETCHED); kick() }
            } catch (x: Exception) { Log.i(TAG, "file download cut: ${x.message}") }   // never throw on the pool thread
        })
    }

    fun openIn(c: Context, e: Entry): InputStream? = runCatching {
        if (e.loc.startsWith("content://")) c.contentResolver.openInputStream(Uri.parse(e.loc)) else File(e.loc).inputStream()
    }.getOrNull()

    /** Copies at most [max] bytes; returns how many. */
    private fun copy(i: InputStream, o: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024); var got = 0L
        while (got < max) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), max - got).toInt()); if (n < 0) break
            o.write(buf, 0, n); got += n
        }
        return got
    }

    // ---------------- parent -> kid ----------------
    /** Saves [len] bytes of [ins] as [raw] in Download/KidGuard. (entry, "") or (null, error code). [wantId]: the relay's id. */
    fun receive(c: Context, raw: String, len: Long, typeHint: String?, ins: InputStream, wantId: String? = null): Pair<Entry?, String> {
        val name0 = FilesLogic.cleanName(raw) ?: return null to "bad name"
        FilesLogic.sizeError(len, FilesLogic.MAX_LAN)?.let { return null to it }
        val id = wantId?.takeIf { FilesLogic.isId(it) && get(c, it) == null } ?: newId()
        val mime = mimeOf(name0, typeHint)
        val takenIdx = all(c).filter { it.dir == FilesLogic.IN }.map { it.name.lowercase() }.toSet()
        return try {
            val (loc, name) = if (Build.VERSION.SDK_INT >= 29) writeMediaStore(c, FilesLogic.unique(name0) { it.lowercase() in takenIdx }, mime, len, ins)
                else writeLegacy(c, name0, takenIdx, len, ins)
            val e = Entry(id, FilesLogic.IN, name, len, System.currentTimeMillis(), mime, FilesLogic.DELIVERED, loc)
            put(c, e); notifyKid(c, e); kick()
            e to ""
        } catch (x: NoSpace) { null to "no space" } catch (x: Cut) { null to "incomplete" } catch (x: Exception) {
            Log.w(TAG, "file receive", x); null to "failed"
        }
    }
    private class NoSpace : IOException(); private class Cut : IOException()

    private fun checkSpace(dir: File, len: Long) {
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (len + 50L * 1024 * 1024 > free) throw NoSpace()
    }

    private fun writeMediaStore(c: Context, name: String, mime: String, len: Long, ins: InputStream): Pair<String, String> {
        @Suppress("DEPRECATION") checkSpace(Environment.getExternalStorageDirectory(), len)
        val cr = c.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("insert failed")
        try {
            val n = (cr.openOutputStream(uri) ?: throw IOException("no stream")).use { copy(ins, it, len) }
            if (n != len) throw Cut()
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (x: Exception) { runCatching { cr.delete(uri, null, null) }; throw x }
        // MediaStore renames on a clash ("a (1).pdf"): list what it really is
        val real = runCatching { cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        return uri.toString() to (real ?: name)
    }

    private fun writeLegacy(c: Context, name0: String, takenIdx: Set<String>, len: Long, ins: InputStream): Pair<String, String> {
        // basic mode has no Device Owner to grant the permission: fall back to our own (still user-visible) app folder
        val granted = c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val dir = (if (granted) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB)
            else File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SUB)).apply { mkdirs() }
        checkSpace(dir, len)
        val name = FilesLogic.unique(name0) { it.lowercase() in takenIdx || File(dir, it).exists() }
        val f = File(dir, name)
        try {
            val n = f.outputStream().use { copy(ins, it, len) }
            if (n != len) throw Cut()
        } catch (x: Exception) { f.delete(); throw x }
        runCatching { MediaScannerConnection.scanFile(c, arrayOf(f.path), null, null) }
        return f.path to name
    }

    private fun notifyKid(c: Context, e: Entry) = runCatching {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("files", c.getString(R.string.ch_files), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, e.id.hashCode(), Intent(c, ShareInActivity::class.java).setAction(ShareInActivity.OPEN)
            .putExtra("id", e.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE_BASE + (e.id.hashCode() and 0xfff), Notification.Builder(c, "files").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(c.getString(R.string.files_got, e.name)).setContentText(c.getString(R.string.files_got_sub))
            .setAutoCancel(true).setContentIntent(open).build())
    }
    private const val NOTE_BASE = 0x4b460000

    /** The kid tapped the file (notification): mark it opened and hand it to a viewer through our provider. */
    fun viewIntent(c: Context, id: String): Intent? {
        val e = get(c, id)?.takeIf { it.dir == FilesLogic.IN } ?: return null
        if (e.st != FilesLogic.OPENED) { setSt(c, id, FilesLogic.OPENED); kick() }
        return Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uri(c, id), e.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun delete(c: Context, id: String): Boolean {
        val e = get(c, id) ?: return false
        runCatching {
            if (e.loc.startsWith("content://")) c.contentResolver.delete(Uri.parse(e.loc), null, null) else File(e.loc).delete()
        }.onFailure { Log.w(TAG, "file delete", it) }
        synchronized(lock) { save(c, all(c).filter { it.id != id }) }
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(NOTE_BASE + (id.hashCode() and 0xfff)) }
        kick(); return true
    }

    // ---------------- kid -> parent ----------------
    /** Copies one shared stream into the outbox. Returns the entry, or null + error code. */
    fun addOut(c: Context, raw: String, typeHint: String?, ins: InputStream): Pair<Entry?, String> {
        val name = FilesLogic.cleanName(raw) ?: FilesLogic.cleanName("file-" + System.currentTimeMillis())!!
        val id = newId(); val f = File(outbox(c), id)
        return try {
            checkSpace(outbox(c), 0)
            val n = f.outputStream().use { o -> ins.use { copy(it, o, FilesLogic.MAX_LAN + 1) } }
            if (n > FilesLogic.MAX_LAN) { f.delete(); return null to "too big" }
            val e = Entry(id, FilesLogic.OUT, name, n, System.currentTimeMillis(), mimeOf(name, typeHint), FilesLogic.NEW, f.path)
            put(c, e); kick(); e to ""
        } catch (x: NoSpace) { f.delete(); null to "no space" } catch (x: Exception) { f.delete(); Log.w(TAG, "outbox", x); null to "failed" }
    }

    /** "release": the outbox goes, the files the kid received stay in Download (they are the kid's now). */
    fun wipeOutbox(c: Context) {
        runCatching { File(c.filesDir, "outbox").deleteRecursively() }
        synchronized(lock) { save(c, emptyList()) }
    }

    // ---------------- relay (worker /c/<id>/file, 1 MiB chunks, <= 10 MB, 1 h TTL) ----------------
    private val pulling = AtomicBoolean(false)

    /** Relay commands: fileUp {file} = put that outbox file on the relay for the parent; fileDel {file}. */
    fun command(c: Context, t: String, o: JSONObject): String {
        val id = o.optString("file")
        val e = get(c, id) ?: return "no such file"
        return when (t) {
            "fileDel" -> if (delete(c, id)) "ok" else "no such file"
            "fileUp" -> if (e.size > FilesLogic.MAX_RELAY) "too big" else {
                Thread({ runCatching { push(c, e) }.onFailure { Log.w(TAG, "file push", it) } }, "file-push").start(); "ok"
            }
            else -> "unknown"
        }
    }

    private fun relay(c: Context): Triple<String, String, String>? {
        val s = Store.of(c)
        val url = s.relayUrl.trim().trimEnd('/')
        if (!url.startsWith("https://", true) || s.relayId.isBlank() || s.relayToken.isBlank()) return null
        return Triple("$url/c/${s.relayId}/file", s.relayToken, s.relayId)
    }

    private fun conn(url: String, method: String, token: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = false
        setRequestProperty("X-KG-Token", token)
    }

    private fun push(c: Context, e: Entry) {
        val (base, token) = relay(c) ?: return
        val n = FilesLogic.chunks(e.size)
        openIn(c, e)?.use { ins ->
            for (i in 0 until n) {
                val want = if (i < n - 1) FilesLogic.CHUNK else (e.size - FilesLogic.CHUNK.toLong() * (n - 1)).toInt()
                val buf = ByteArray(want); var got = 0
                while (got < want) { val r = ins.read(buf, got, want - got); if (r < 0) throw IOException("short"); got += r }
                val q = "?fid=${e.id}&i=$i&n=$n&to=parent&size=${e.size}&name=${URLEncoder.encode(e.name, "UTF-8")}"
                val h = conn(base + q, "POST", token).apply { doOutput = true; setRequestProperty("Content-Type", FilesLogic.OCTET); setFixedLengthStreamingMode(want) }
                try { h.outputStream.use { it.write(buf) }; if (h.responseCode != 200) throw IOException("relay HTTP ${h.responseCode}") } finally { h.disconnect() }
            }
        }
    }

    /** Sync said files wait for us: fetch each, save it like a LAN upload (same id), then delete it from the relay. */
    fun pull(c: Context) {
        if (!pulling.compareAndSet(false, true)) return
        Thread({
            try {
                val (base, token) = relay(c) ?: return@Thread
                val h = conn("$base?to=kid", "GET", token)
                val list = try { if (h.responseCode != 200) return@Thread; JSONObject(h.inputStream.use { String(it.readBytes()) }).optJSONArray("files") } finally { h.disconnect() }
                for (k in 0 until (list?.length() ?: 0)) {
                    val f = list!!.optJSONObject(k) ?: continue
                    val fid = f.optString("fid"); if (!f.optBoolean("done") || !FilesLogic.isId(fid)) continue
                    val size = f.optLong("size"); val n = f.optInt("n")
                    val ins = object : InputStream() {
                        var i = 0; var cur: InputStream = java.io.ByteArrayInputStream(ByteArray(0))
                        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            while (true) {
                                val r = cur.read(b, off, len); if (r >= 0) return r
                                if (i >= n) return -1
                                val g = conn("$base?fid=$fid&i=${i++}", "GET", token)
                                cur = try { if (g.responseCode != 200) throw IOException("chunk HTTP ${g.responseCode}"); java.io.ByteArrayInputStream(g.inputStream.use { it.readBytes() }) } finally { g.disconnect() }
                            }
                        }
                    }
                    val (e, code) = if (get(c, fid) != null) null to "dup" else receive(c, f.optString("name"), if (size <= FilesLogic.MAX_RELAY) size else -1, null, ins, fid)
                    // network trouble: keep it on the relay for the next sync; anything else will not get better
                    if (e != null || code != "failed") { val d = conn("$base?fid=$fid", "DELETE", token); runCatching { d.responseCode }; d.disconnect() }
                }
            } catch (x: Exception) { Log.w(TAG, "file pull", x) } finally { pulling.set(false) }
        }, "file-pull").start()
    }
}

/** content://<pkg>.files/<id>: read-only access to one shared file, for the viewer the kid opens it with. */
class FilesProvider : ContentProvider() {
    companion object { fun uri(c: Context, id: String): Uri = Uri.parse("content://${c.packageName}.files/$id") }
    override fun onCreate() = true
    private fun entry(u: Uri) = u.lastPathSegment?.takeIf { FilesLogic.isId(it) }?.let { Files.get(context!!, it) }
    override fun getType(u: Uri) = entry(u)?.mime
    override fun openFile(u: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val e = entry(u) ?: throw java.io.FileNotFoundException()
        return if (e.loc.startsWith("content://")) context!!.contentResolver.openFileDescriptor(Uri.parse(e.loc), "r")
            else ParcelFileDescriptor.open(File(e.loc), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val e = entry(u) ?: return null
        val cols = p ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply { addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> e.name; OpenableColumns.SIZE -> e.size; else -> null } }) }
    }
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
