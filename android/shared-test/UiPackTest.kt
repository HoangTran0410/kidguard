package dev.kidguard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** UI pack verify / seq / minKid / rollback / download (android/shared/UiPack.kt). Compiled into both the kid app's and the parent app's unit tests. */
class UiPackTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val keys = listOf(Base64.getEncoder().encodeToString(kp.public.encoded))
    private val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private lateinit var dir: File

    @Before fun setUp() { dir = tmp() }
    private fun tmp() = File.createTempFile("uipack", "").apply { delete(); mkdirs() }

    private fun pack(seq: Long, minKid: Int = 1, minParent: Int = 1, page: String = "<html>$seq</html>", key: java.security.KeyPair = kp,
                     tamper: Boolean = false, fix: (MutableMap<String, ByteArray>) -> Unit = {}): ByteArray {
        val files = linkedMapOf<String, ByteArray>("index.html" to page.toByteArray(), "assets/core.js" to "var a=$seq;".toByteArray())
        val hashes = files.mapValues { UiPack.sha256(it.value) }
        val ver = UiPack.verOf(hashes)
        val mf = """{"v":1,"ver":"$ver","seq":$seq,"minKid":$minKid,"minParent":$minParent,"files":{${hashes.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}""".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(mf) }.sign()
        if (tamper) files["assets/core.js"] = "var evil=1;".toByteArray()
        fix(files)
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            fun add(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            add("manifest.json", mf); add("manifest.sig", Base64.getEncoder().encodeToString(sig).toByteArray())
            files.forEach { (n, b) -> add(n, b) }
        }
        return o.toByteArray()
    }

    private fun store(role: UiRole = UiRole.KID, code: Int = 50, d: File = dir) = UiPackStore(d, role, code, keys)

    @Test fun goodPackInstallsAndServes() {
        val s = store()
        assertNull(s.load())
        assertNull(s.install(pack(100)))
        assertEquals(100L, s.load()!!.manifest.seq)
        assertEquals("<html>100</html>", String(s.load()!!.files["index.html"]!!))
        // a new process (fresh store over the same dir) re-verifies and serves the same pack
        assertEquals(100L, store().load()!!.manifest.seq)
    }

    @Test fun wrongKeyOrTamperedFileIsRefused() {
        val s = store()
        assertEquals("sig", s.install(pack(100, key = other)))
        assertEquals("hash", s.install(pack(100, tamper = true)))
        assertNull(s.load())
        // a file smuggled in that the manifest does not name, a path outside assets/, garbage
        assertEquals("files", s.install(pack(100, fix = { it["assets/extra.js"] = "x".toByteArray() })))
        assertEquals("name", s.install(pack(100, fix = { it["../evil.js"] = "x".toByteArray() })))
        assertEquals("manifest", s.install("not a zip".toByteArray()))
    }

    @Test fun manifestEditedAfterSigningIsRefused() {
        // the signature covers the manifest bytes: a manifest that claims another seq does not verify
        val good = pack(100)
        val v = UiPack.verifyZip(good, keys)
        val edited = String(v.manifestRaw).replace("\"seq\":100", "\"seq\":900").toByteArray()
        assertFalse(UiPack.sigOk(edited, v.sig, keys))
        assertTrue(UiPack.sigOk(v.manifestRaw, v.sig, keys))
    }

    @Test fun seqMustGrow() {
        val s = store()
        assertNull(s.install(pack(100)))
        assertEquals("seq", s.install(pack(100)))
        assertEquals("seq", s.install(pack(99)))
        assertNull(s.install(pack(101)))
        assertEquals(101L, s.load()!!.manifest.seq)
    }

    @Test fun replayFloorSurvivesDroppedCache() {
        val s = store()
        assertNull(s.install(pack(100))); assertNull(s.install(pack(200)))
        File(dir, "cur").deleteRecursively(); File(dir, "prev").deleteRecursively()
        val t = store()
        assertNull(t.load())
        assertEquals("seq", t.install(pack(150)))   // older than the highest ever installed: replay
        assertNull(t.install(pack(200)))            // the same one again is fine
    }

    @Test fun minKidAndMinParent() {
        assertEquals("min", store(UiRole.KID, 36).install(pack(100, minKid = 37)))
        assertNull(store(UiRole.KID, 37, tmp()).install(pack(100, minKid = 37)))
        // the parent app ignores minKid and obeys minParent
        assertNull(store(UiRole.PARENT, 10, tmp()).install(pack(100, minKid = 99, minParent = 5)))
        assertEquals("min", store(UiRole.PARENT, 10, tmp()).install(pack(100, minKid = 1, minParent = 11)))
    }

    @Test fun corruptedCacheFallsBackToPreviousThenBundled() {
        val s = store()
        assertNull(s.install(pack(100))); assertNull(s.install(pack(200)))
        File(dir, "cur/assets/core.js").writeText("var hacked=1;")
        val t = store()
        assertEquals(100L, t.load()!!.manifest.seq)   // cur dropped, prev serves
        File(dir, "cur/index.html").writeText("x")
        val u = store()
        assertNull(u.load())                            // nothing valid left: bundled page
        assertFalse(File(dir, "cur").exists())
    }

    @Test fun appUpdateDropsCache() {
        assertNull(store(code = 50).install(pack(100)))
        assertNull(store(code = 51).load())
    }

    @Test fun pageThatNeverBootsIsRevertedAndBlacklisted() {
        val s = store()
        assertNull(s.install(pack(100))); s.confirm(s.load()!!.manifest.ver)
        assertNull(s.install(pack(200)))
        val bad = s.load()!!.manifest.ver
        assertFalse(s.indexServed()); assertFalse(s.indexServed())
        assertTrue(s.indexServed())                      // 3rd load without "it booted": back to the previous pack
        assertEquals(100L, s.load()!!.manifest.seq)
        assertEquals("bad", s.install(pack(200)))        // not offered again
        assertNull(s.install(pack(201)))                 // a fixed one is
        val v = s.load()!!.manifest.ver; assertNotEquals(bad, v)
        s.indexServed(); s.confirm(v); s.indexServed(); s.indexServed(); s.indexServed()
        assertEquals(201L, s.load()!!.manifest.seq)      // confirmed: stays
    }

    /** A tiny HTTP/1.1 host (one request per connection): manifest.json (+ETag/304), manifest.sig, pack-<ver>.zip. */
    private class Host(val keys: List<String>) {
        @Volatile var serve: ByteArray = ByteArray(0)
        @Volatile var manifestFrom: ByteArray = ByteArray(0)   // the host may serve a manifest of one pack and the zip of another
        val srv = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        init {
            Thread {
                while (!srv.isClosed) {
                    val c = try { srv.accept() } catch (e: Exception) { break }
                    try {
                        val r = c.getInputStream().bufferedReader()
                        val path = r.readLine().split(" ")[1].removePrefix("/ui/")
                        var inm = ""
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("If-None-Match:", true)) inm = l.substringAfter(":").trim() }
                        val v = UiPack.verifyZip(manifestFrom, keys); val tag = "\"${v.manifest.ver}\""
                        val body = when (path) { "manifest.json" -> v.manifestRaw; "manifest.sig" -> v.sig.toByteArray(); "pack-${v.manifest.ver}.zip" -> serve; else -> null }
                        val o = c.getOutputStream()
                        if (body == null) o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        else if (path == "manifest.json" && inm == tag) o.write("HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n".toByteArray())
                        else { o.write("HTTP/1.1 200 OK\r\n${if (path == "manifest.json") "ETag: $tag\r\n" else ""}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); o.write(body) }
                        o.flush()
                    } catch (e: Exception) { } finally { runCatching { c.close() } }
                }
            }.apply { isDaemon = true }.start()
        }
        val base get() = "http://127.0.0.1:${srv.localPort}/ui/"
    }

    @Test fun downloadVerifyAndTamper() {
        val h = Host(keys + Base64.getEncoder().encodeToString(other.public.encoded))
        h.serve = pack(300); h.manifestFrom = h.serve
        try {
            val s = store()
            assertEquals("new", s.check(h.base, allowHttp = true)); assertEquals(300L, s.load()!!.manifest.seq)
            assertEquals("same", s.check(h.base, allowHttp = true))   // ETag -> 304
            assertEquals("net", s.check("http://127.0.0.1:1/ui/", allowHttp = true))
            assertEquals("net", s.check(h.base, allowHttp = false))   // https only in production
            // the host serves the signed manifest of pack 301 but a zip whose core.js was swapped: hash mismatch, nothing installed
            h.manifestFrom = pack(301); h.serve = pack(301, tamper = true)
            val s2 = store(d = tmp())
            assertEquals("hash", s2.check(h.base, allowHttp = true))
            assertNull(s2.load())
            // the same host, an untampered zip: installs
            h.serve = h.manifestFrom
            assertEquals("new", s2.check(h.base, allowHttp = true)); assertEquals(301L, s2.load()!!.manifest.seq)
            // a manifest signed with a foreign key never gets as far as the zip
            h.manifestFrom = pack(302, key = other); h.serve = h.manifestFrom
            assertEquals("sig", store(d = tmp()).check(h.base, allowHttp = true))
        } finally { h.srv.close() }
    }

    /** The pack scripts/ui-pack.mjs really built (website/ui/, git-ignored; skipped when not built) verifies with the keys baked into the app, and a flipped byte does not. */
    @Test fun packBuiltByTheScriptVerifiesWithBakedKeys() {
        val d = listOf(File("../../website/ui"), File("../website/ui"), File("website/ui")).firstOrNull { File(it, "manifest.json").exists() }
        org.junit.Assume.assumeTrue("website/ui not built (node scripts/ui-pack.mjs)", d != null)
        val zip = d!!.listFiles()!!.first { it.name.startsWith("pack-") && it.name.endsWith(".zip") }.readBytes()
        val v = UiPack.verifyZip(zip)   // UiPackCfg.KEYS
        assertTrue(v.files.containsKey("index.html"))
        assertEquals(v.manifest.ver, UiPack.verOf(v.manifest.files))
        // the same pack under a foreign key list is refused, and so is a copy with one byte of a script changed
        assertEquals("sig", try { UiPack.verifyZip(zip, keys); "ok" } catch (e: UiPackException) { e.why })
        val bad = zip.copyOf()
        val i = String(zip, Charsets.ISO_8859_1).indexOf("manifest.json") + 20
        bad[i] = (bad[i].toInt() xor 1).toByte()
        assertTrue(try { UiPack.verifyZip(bad); false } catch (e: UiPackException) { true })
    }
}
