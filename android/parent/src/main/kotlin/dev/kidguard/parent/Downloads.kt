package dev.kidguard.parent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Saves a WebView download (blob / data / same-origin URL) into the public Downloads folder, chunk by chunk. */
object Downloads {
    private class Sess(val out: OutputStream, val name: String, val uri: Uri?, val file: File?, var size: Long = 0)
    private val sessions = ConcurrentHashMap<String, Sess>()
    private const val MAX = 260L * 1024 * 1024

    /** JS run in the page: reads the URL (blob etc.) in 768 KiB slices and passes base64 chunks to KGApp.dl*. */
    fun script(url: String, contentDisposition: String?, mime: String?): String {
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        fun q(s: String) = org.json.JSONObject.quote(s)
        return "(async()=>{let id='';try{const r=await fetch(${q(url)});const b=await r.blob();" +
            "id=KGApp.dlBegin(${q(name)},b.type||${q(mime ?: "")});if(!id)return;const S=786432;" +
            "for(let o=0;o<b.size;o+=S){const d=await new Promise((ok,no)=>{const f=new FileReader();f.onload=()=>ok(f.result);f.onerror=()=>no(f.error);f.readAsDataURL(b.slice(o,o+S))});" +
            "if(!KGApp.dlChunk(id,d.slice(d.indexOf(',')+1)))throw 0}KGApp.dlEnd(id,'true')}catch(e){if(id)KGApp.dlEnd(id,'false');else try{KGApp.dlEnd('','false')}catch(x){}}})()"
    }

    private fun toast(c: Context, r: Int, vararg a: Any) = Handler(Looper.getMainLooper()).post { Toast.makeText(c, c.getString(r, *a), Toast.LENGTH_LONG).show() }

    fun safeName(n: String): String {
        val x = n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").trim().trimStart('.')
        return (if (x.length > 120) x.take(100) + "." + x.substringAfterLast('.', "bin").take(15) else x).ifBlank { "file" }
    }

    fun begin(c: Context, name0: String, mime0: String): String {
        val name = safeName(name0)
        val mime = mime0.ifBlank { "application/octet-stream" }
        return try {
            val s = if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KidGuard"); put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                Sess(c.contentResolver.openOutputStream(u)!!, name, u, null)
            } else {
                // API 26-28: no storage permission in this app, so the app's own external Download folder
                val d = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KidGuard").apply { mkdirs() }
                var f = File(d, name); var i = 1
                while (f.exists()) { f = File(d, name.substringBeforeLast('.', name) + " ($i)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); i++ }
                Sess(f.outputStream(), name, null, f)
            }
            UUID.randomUUID().toString().also { sessions[it] = s }
        } catch (e: Exception) { toast(c, R.string.dl_fail, name); "" }
    }

    fun chunk(id: String, b64: String): Boolean {
        val s = sessions[id] ?: return false
        return try {
            val b = Base64.decode(b64, Base64.DEFAULT)
            s.size += b.size
            if (s.size > MAX) throw java.io.IOException("too big")
            s.out.write(b); true
        } catch (e: Exception) { false }
    }

    fun end(c: Context, id: String, ok: Boolean) {
        val s = sessions.remove(id)
        if (s == null) { if (!ok) toast(c, R.string.dl_fail, ""); return }
        runCatching { s.out.close() }
        if (ok) {
            if (s.uri != null) runCatching { c.contentResolver.update(s.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
            toast(c, R.string.dl_ok, s.name)
        } else {
            if (s.uri != null) runCatching { c.contentResolver.delete(s.uri, null, null) }
            s.file?.delete()
            toast(c, R.string.dl_fail, s.name)
        }
    }
}
