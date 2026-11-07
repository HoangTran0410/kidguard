package dev.kidguard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast

/**
 * No UI of its own (translucent). Three jobs around the shared folder (Files.kt):
 *  - share target "Gửi cho bố mẹ" (ACTION_SEND / SEND_MULTIPLE, any type): copies the streams into the outbox;
 *  - [PICK] from the child screen's sheet: system file picker, then the same copy;
 *  - [OPEN] from the "Bố mẹ gửi: ..." notification: marks the file opened and starts a viewer.
 * The copy runs before finish(), while the sender's URI grant is still ours; nothing keeps the grant afterwards.
 */
class ShareInActivity : Activity() {
    companion object {
        const val OPEN = "dev.kidguard.OPEN_SHARED"
        const val PICK = "dev.kidguard.PICK_SHARED"
        private const val REQ = 41
        fun pick(c: Context) = c.startActivity(Intent(c, ShareInActivity::class.java).setAction(PICK))
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b != null) return   // recreated while the picker is up: onActivityResult follows
        val i = intent
        when (i?.action) {
            OPEN -> { open(i.getStringExtra("id") ?: ""); finish() }
            PICK -> runCatching {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ)
            }.onFailure { toast(getString(R.string.files_no_picker)); finish() }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> take(i)
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req != REQ || res != RESULT_OK || data == null) return finish()
        val uris = mutableListOf<Uri>()
        data.data?.let { uris += it }
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { if (it !in uris) uris += it } }
        copy(uris, null, null)
    }

    private fun open(id: String) {
        val v = Files.viewIntent(this, id) ?: return toast(getString(R.string.files_gone))
        try { startActivity(v) } catch (e: ActivityNotFoundException) { toast(getString(R.string.files_no_app)) }
        catch (e: Exception) { Log.w("KidGuard", "open shared", e); toast(getString(R.string.files_gone)) }
    }

    private fun take(i: Intent) {
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        else (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
        // plain text shared (a link, a note): kept as a small .txt
        val text = if (uris.isEmpty()) i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(100_000) else null
        if (uris.isEmpty() && text.isNullOrBlank()) { toast(getString(R.string.files_none)); return finish() }
        copy(uris, text, i.type)
    }

    private fun copy(uris: List<Uri>, text: String?, type: String?) {
        toast(getString(R.string.files_sending))
        Thread({
            var ok = 0; var err = ""
            for (u in uris.take(20)) {
                val (e, code) = runCatching {
                    val name = nameOf(u) ?: u.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val ins = contentResolver.openInputStream(u) ?: return@runCatching null to "failed"
                    Files.addOut(this, name, contentResolver.getType(u) ?: type, ins)
                }.getOrElse { Log.w("KidGuard", "share in", it); null to "failed" }
                if (e != null) ok++ else err = code
            }
            if (text != null) {
                val name = "note-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".txt"
                if (Files.addOut(this, name, "text/plain", text.toByteArray().inputStream()).first != null) ok++
            }
            runOnUiThread {
                toast(if (ok > 0) resources.getQuantityString(R.plurals.files_sent, ok, ok)
                    else getString(if (err == "too big") R.string.files_too_big else R.string.files_failed))
                finish()
            }
        }, "share-in").start()
    }

    private fun nameOf(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()
}
