package dev.kidguard

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * UI pack = the parent page (ui/index.html + ui/assets/ *.js and *.css) as a signed zip that the kid phone and the parent app
 * download and serve instead of their bundled copy (docs/flows/remote.md 2.9b, docs/decisions/page.md).
 * Shared by both apps (android/shared). Pure Kotlin + java.security + org.json: unit-tested on the JVM.
 */
object UiPackCfg {
    /** Where the signed manifest + pack live (website/ui/, built by scripts/ui-pack.mjs, deployed by scripts/deploy.sh). */
    const val BASE = "https://kidguard.asia/ui/"
    /** Mirrors tried in order when [BASE] can't be reached (the pack is signed: any host is safe). 2026-11-19 own domain, Pages kept. */
    val BASES = listOf(BASE, "https://kid-guard.pages.dev/ui/")
    /** ECDSA P-256 public keys (X.509 DER, base64). [0] = the one scripts/ui-pack.mjs signs with, [1] = an offline backup (key rotation without an APK update is NOT possible: add keys with a release). Rewritten by `node scripts/ui-pack.mjs --init`. */
    val KEYS: List<String> = listOf(/*KEYS*/"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKtbl/OeNbQ+B8Cx3IkSWtyGebZ32BRqNX4bLxAbfyVQmvCZ3sRdtvPoaX8x98sjTrxDoTFzOBBVt3/0qszARlQ==", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETXhNbMTjy8pEV0qrBMQPe0AjaOm+usW4n7IKXbRcDiaS+O/whtDxrgI0lIC/WQrELDptzq9juZLhMRPON9q+/Q=="/*KEYS*/)
}

/** Who verifies: the kid app compares `minKid` with its versionCode, the parent app `minParent`. */
enum class UiRole { KID, PARENT }

class UiManifest(val ver: String, val seq: Long, val minKid: Int, val minParent: Int, val files: Map<String, String>)

class UiPackException(val why: String) : Exception(why)

object UiPack {
    const val MAX_FILE = 1_500_000
    const val MAX_TOTAL = 6_000_000
    const val MAX_FILES = 60
    const val MAX_ZIP = 4_000_000
    private val NAME = Regex("""index\.html|assets/[a-z0-9][a-z0-9-]{0,40}\.(js|css)""")
    private val HEX64 = Regex("[0-9a-f]{64}")
    val VER = Regex("[0-9a-f]{12}")

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Content hash of a file set (names + file hashes, sorted): the pack's `ver`. Same rule as scripts/ui-pack.mjs. */
    fun verOf(files: Map<String, String>): String =
        sha256(files.toSortedMap().entries.joinToString("") { "${it.key}:${it.value}\n" }.toByteArray()).take(12)

    fun parseManifest(raw: ByteArray): UiManifest {
        val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) { throw UiPackException("manifest") }
        if (o.optInt("v") != 1) throw UiPackException("manifest")
        val f = o.optJSONObject("files") ?: throw UiPackException("manifest")
        if (f.length() == 0 || f.length() > MAX_FILES) throw UiPackException("manifest")
        val files = HashMap<String, String>()
        for (k in f.keys()) {
            val h = f.optString(k)
            if (!NAME.matches(k) || !HEX64.matches(h)) throw UiPackException("name")
            files[k] = h
        }
        if (!files.containsKey("index.html")) throw UiPackException("manifest")
        val ver = o.optString("ver"); val seq = o.optLong("seq", -1)
        if (!VER.matches(ver) || seq <= 0 || ver != verOf(files)) throw UiPackException("manifest")
        return UiManifest(ver, seq, o.optInt("minKid", Int.MAX_VALUE), o.optInt("minParent", Int.MAX_VALUE), files)
    }

    fun sigOk(manifest: ByteArray, sigB64: String, keys: List<String>): Boolean {
        val sig = try { Base64.getDecoder().decode(sigB64.trim()) } catch (e: Exception) { return false }
        for (k in keys) {
            try {
                val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(k)))
                val s = Signature.getInstance("SHA256withECDSA"); s.initVerify(pub); s.update(manifest)
                if (s.verify(sig)) return true
            } catch (e: Exception) { /* next key */ }
        }
        return false
    }

    /** Can this app serve the pack at all? (`minKid` / `minParent` = the lowest app versionCode whose native side the page needs.) */
    fun fits(m: UiManifest, role: UiRole, ownCode: Int) = ownCode >= (if (role == UiRole.KID) m.minKid else m.minParent)

    /** Is [m] worth downloading: newer than [curSeq], not older than the replay floor, not on the bad list, fits this app? null = yes, else the reason. */
    fun wanted(m: UiManifest, role: UiRole, ownCode: Int, curSeq: Long, floor: Long, bad: Set<String>): String? = when {
        !fits(m, role, ownCode) -> "min"
        m.seq <= curSeq -> "seq"
        m.seq < floor -> "seq"
        m.ver in bad -> "bad"
        else -> null
    }

    class Verified(val manifest: UiManifest, val manifestRaw: ByteArray, val sig: String, val files: Map<String, ByteArray>)

    /** Unzip + check a pack: signature over manifest.json, every file named in it present with its sha256, nothing else inside. Throws [UiPackException]. */
    fun verifyZip(zip: ByteArray, keys: List<String> = UiPackCfg.KEYS): Verified {
        if (zip.size > MAX_ZIP) throw UiPackException("size")
        val got = LinkedHashMap<String, ByteArray>()
        var total = 0
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) throw UiPackException("name")
                    val n = e.name
                    if (n != "manifest.json" && n != "manifest.sig" && !NAME.matches(n)) throw UiPackException("name")
                    if (got.containsKey(n) || got.size > MAX_FILES + 2) throw UiPackException("name")
                    val o = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = z.read(buf); if (r < 0) break
                        o.write(buf, 0, r)
                        if (o.size() > MAX_FILE) throw UiPackException("size")
                    }
                    total += o.size(); if (total > MAX_TOTAL) throw UiPackException("size")
                    got[n] = o.toByteArray()
                }
            }
        } catch (e: UiPackException) { throw e } catch (e: Exception) { throw UiPackException("zip") }
        val mf = got.remove("manifest.json") ?: throw UiPackException("manifest")
        val sg = String(got.remove("manifest.sig") ?: throw UiPackException("sig"), Charsets.UTF_8)
        if (!sigOk(mf, sg, keys)) throw UiPackException("sig")
        val m = parseManifest(mf)
        if (got.keys != m.files.keys) throw UiPackException("files")
        for ((n, h) in m.files) if (sha256(got[n]!!) != h) throw UiPackException("hash")
        return Verified(m, mf, sg, got)
    }
}

/** What [UiPackStore.load] hands the web server: where the files come from. */
class UiLoaded(val manifest: UiManifest, val files: Map<String, ByteArray>)

/**
 * The cached packs on disk: `cur/` (served) and `prev/` (rollback), plus `meta.json` {app, floor, bad[], pend, tries, etag}.
 * - A pack is installed only after [UiPack.verifyZip] and [UiPack.wanted]; written to a temp dir and renamed (atomic).
 * - Every [load] after a restart re-verifies (signature + hashes): a corrupted or tampered cache is dropped, never served.
 * - `floor` = highest seq ever installed: an older pack (replay) is refused even after the cache was dropped.
 * - A new pack is "pending" until the page it served reports it booted ([confirm]); 3 index loads without it = bad: blacklisted, previous pack back.
 * - The app's own versionCode is kept: after an app update the cache is dropped (the new APK's bundled page is at least as new).
 */
class UiPackStore(private val dir: File, val role: UiRole, private val ownCode: Int, private val keys: List<String> = UiPackCfg.KEYS) {
    private val lock = Any()
    private var cache: UiLoaded? = null
    private var cacheOk = false

    private fun meta(): JSONObject = try { JSONObject(File(dir, "meta.json").readText()) } catch (e: Exception) { JSONObject() }
    private fun saveMeta(o: JSONObject) { dir.mkdirs(); val t = File(dir, "meta.json.tmp"); t.writeText(o.toString()); if (!t.renameTo(File(dir, "meta.json"))) { File(dir, "meta.json").delete(); t.renameTo(File(dir, "meta.json")) } }
    private fun bad(m: JSONObject): Set<String> = m.optJSONArray("bad")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

    private fun readDir(d: File): UiLoaded? = try {
        val mf = File(d, "manifest.json").readBytes(); val sg = File(d, "manifest.sig").readText()
        if (!UiPack.sigOk(mf, sg, keys)) null else {
            val m = UiPack.parseManifest(mf)
            if (!UiPack.fits(m, role, ownCode)) null else {
                val files = HashMap<String, ByteArray>()
                for ((n, h) in m.files) { val b = File(d, n).readBytes(); if (UiPack.sha256(b) != h) throw UiPackException("hash"); files[n] = b }
                UiLoaded(m, files)
            }
        }
    } catch (e: Exception) { null }

    private fun dropAll() { File(dir, "cur").deleteRecursively(); File(dir, "prev").deleteRecursively(); cache = null; cacheOk = false }

    /** The pack to serve, or null = serve the bundled page. */
    fun load(): UiLoaded? = synchronized(lock) {
        if (cacheOk) return cache
        val m = meta()
        if (m.optInt("app", -1) != ownCode) { dropAll(); m.put("app", ownCode).put("pend", "").put("tries", 0).remove("etag"); saveMeta(m) }
        var l = readDir(File(dir, "cur"))
        if (l == null && File(dir, "cur").exists()) {   // corrupted / no longer fits: drop it, the previous one takes over
            File(dir, "cur").deleteRecursively()
            val p = File(dir, "prev")
            if (p.exists() && readDir(p) != null) p.renameTo(File(dir, "cur")) else p.deleteRecursively()
            l = readDir(File(dir, "cur"))
            m.put("pend", "").remove("etag"); saveMeta(m)
        }
        cache = l; cacheOk = true
        l
    }

    fun seq(): Long = load()?.manifest?.seq ?: 0L
    fun etagSaved(): String = meta().optString("etag")
    fun saveEtag(e: String) = synchronized(lock) { val m = meta(); m.put("etag", e); saveMeta(m) }
    fun floor(): Long = meta().optLong("floor", 0)
    fun badSet(): Set<String> = bad(meta())

    /** Decide from a parsed manifest whether to download (null = yes). */
    fun wanted(m: UiManifest): String? = UiPack.wanted(m, role, ownCode, seq(), floor(), badSet())

    /** Verify + install a zip. null = installed, else the reason ("sig", "hash", "seq", "min", "bad", ...). */
    fun install(zip: ByteArray): String? = synchronized(lock) {
        try {
            val v = UiPack.verifyZip(zip, keys)
            load()
            UiPack.wanted(v.manifest, role, ownCode, seq(), floor(), badSet())?.let { return it }
            dir.mkdirs()
            val tmp = File(dir, "new").also { it.deleteRecursively(); it.mkdirs() }
            for ((n, b) in v.files) { val f = File(tmp, n); f.parentFile?.mkdirs(); f.writeBytes(b) }
            File(tmp, "manifest.json").writeBytes(v.manifestRaw); File(tmp, "manifest.sig").writeText(v.sig)
            File(dir, "prev").deleteRecursively()
            val cur = File(dir, "cur")
            if (cur.exists() && !cur.renameTo(File(dir, "prev"))) { tmp.deleteRecursively(); return "io" }
            if (!tmp.renameTo(cur)) { File(dir, "prev").renameTo(cur); return "io" }
            val m = meta(); m.put("app", ownCode).put("floor", maxOf(m.optLong("floor", 0), v.manifest.seq)).put("pend", v.manifest.ver).put("tries", 0)
            saveMeta(m)
            cache = null; cacheOk = false
            null
        } catch (e: UiPackException) { e.why } catch (e: java.io.IOException) { "io" }
    }

    /** Called when the index page is served: counts loads of a pack that never confirmed; returns true when the pack just went bad (reverted). */
    fun indexServed(): Boolean = synchronized(lock) {
        val l = load() ?: return false
        val m = meta()
        if (m.optString("pend") != l.manifest.ver) return false
        val n = m.optInt("tries", 0) + 1
        if (n < 3) { m.put("tries", n); saveMeta(m); return false }
        // booted never, 3 loads in a row: blacklist, go back to the previous pack (or the bundled page)
        val b = org.json.JSONArray(bad(m).toList() + l.manifest.ver)
        File(dir, "cur").deleteRecursively()
        val p = File(dir, "prev")
        if (p.exists() && readDir(p) != null) p.renameTo(File(dir, "cur")) else p.deleteRecursively()
        m.put("bad", b).put("pend", "").put("tries", 0).remove("etag"); saveMeta(m)
        cache = null; cacheOk = false
        true
    }

    /** The page reports it booted (`/ui/ok?v=`). */
    fun confirm(ver: String) = synchronized(lock) {
        val m = meta()
        if (m.optString("pend") == ver) { m.put("pend", "").put("tries", 0); saveMeta(m) }
    }

    /** Ask the host for a newer pack. Result: "same", "new" (installed), "min"/"seq"/"bad" (refused), "net", or a verify error ("sig", "hash", ...). */
    fun check(base: String = UiPackCfg.BASE, allowHttp: Boolean = false): String {
        try {
            if (!(base.startsWith("https://") || (allowHttp && base.startsWith("http://")))) return "net"
            val et = etagSaved()
            val mc = open(base + "manifest.json", allowHttp).apply { if (et.isNotEmpty()) setRequestProperty("If-None-Match", et) }
            val mf: ByteArray; val newEtag: String
            try {
                if (mc.responseCode == 304) return "same"
                if (mc.responseCode != 200) return "net"
                mf = read(mc, 64 * 1024); newEtag = mc.getHeaderField("ETag") ?: ""
            } finally { mc.disconnect() }
            val sc = open(base + "manifest.sig", allowHttp)
            val sg = try { if (sc.responseCode != 200) return "net"; String(read(sc, 4096), Charsets.UTF_8) } finally { sc.disconnect() }
            if (!UiPack.sigOk(mf, sg, keys)) return "sig"
            val m = UiPack.parseManifest(mf)
            when (val w = wanted(m)) { null -> {} else -> { if (w == "seq" && m.seq == seq()) saveEtag(newEtag); return if (w == "seq") "same" else w } }
            val zc = open(base + "pack-" + m.ver + ".zip", allowHttp)
            val zip = try { if (zc.responseCode != 200) return "net"; read(zc, UiPack.MAX_ZIP) } finally { zc.disconnect() }
            val r = install(zip)
            if (r == null) { saveEtag(newEtag); return "new" }
            return r
        } catch (e: UiPackException) { return e.why } catch (e: java.io.IOException) { return "net" } catch (e: Exception) { return "net" }
    }

    private fun open(url: String, allowHttp: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = false; useCaches = false }

    private fun read(c: HttpURLConnection, max: Int): ByteArray {
        val o = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        c.inputStream.use { i -> while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); if (o.size() > max) throw java.io.IOException("too big") } }
        return o.toByteArray()
    }
}

/**
 * One per app process: the store + "what does the server answer for a page file" + the check state, so both apps share the same rules.
 * [bundled] reads a file from the APK assets ("index.html", "assets/core.js").
 */
class UiPackHub(val store: UiPackStore, private val bundled: (String) -> ByteArray, private val bundledTag: String) {
    @Volatile var checking = false; private set
    @Volatile var lastCheck = 0L; private set
    @Volatile var lastResult = ""; private set
    @Volatile private var started = 0L

    /** ETag of what is served right now (changes with the pack). */
    fun tag(): String = store.load()?.let { "\"kg-ui-${it.manifest.ver}\"" } ?: bundledTag

    fun file(name: String): ByteArray = store.load()?.files?.get(name) ?: bundled(name)

    /** JSON for `/ui/pack.json` (public, no secrets). */
    fun info(app: String): JSONObject {
        val l = store.load()
        return JSONObject().put("src", if (l != null) "pack" else "bundled").put("ver", l?.manifest?.ver ?: "").put("seq", l?.manifest?.seq ?: 0L)
            .put("app", app).put("checking", checking).put("last", lastCheck).put("result", lastResult)
    }

    /** Background check; [gap] ms between two (page opens: 60 s). Returns false when skipped. [done] gets the result on the worker thread. */
    fun checkAsync(gap: Long, now: Long = System.currentTimeMillis(), base: String = UiPackCfg.BASE, done: (String) -> Unit = {}): Boolean {
        synchronized(this) {
            if (checking || (gap > 0 && now - started < gap)) return false
            checking = true; started = now
        }
        Thread({
            val r = (if (base == UiPackCfg.BASE) UiPackCfg.BASES else listOf(base)).fold("net") { r, b -> if (r != "net") r else try { store.check(b) } catch (e: Throwable) { "net" } }
            lastResult = r; lastCheck = System.currentTimeMillis(); checking = false
            try { done(r) } catch (_: Throwable) {}
        }, "ui-pack").apply { isDaemon = true }.start()
        return true
    }

    /** Dev push (`scripts/ui-push.sh`): a signed pack body. null = installed, else the reason. */
    fun push(zip: ByteArray): String? = store.install(zip).also { lastResult = it ?: "new"; lastCheck = System.currentTimeMillis() }
}
