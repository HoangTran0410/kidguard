package dev.kidguard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * content://<pkg>.capture/<name>: the scratch file a camera app writes a chat photo / video into (EXTRA_OUTPUT), and where the voice recorder
 * puts its m4a. Lives in cacheDir/capture only (flat names, no path tricks); not exported, access by per-intent grant. ChatActivity copies the
 * result into the archive (Files.addOutChat) and deletes the scratch file.
 */
class CaptureProvider : ContentProvider() {
    companion object {
        private val NAME = Regex("^[A-Za-z0-9._-]{1,80}$")
        fun dir(c: Context) = File(c.cacheDir, "capture").apply { mkdirs() }
        fun newFile(c: Context, ext: String) = File(dir(c), "cap-${System.currentTimeMillis()}-${(0..99999).random()}.$ext")
        fun uri(c: Context, f: File): Uri = Uri.parse("content://${c.packageName}.capture/${f.name}")
    }

    override fun onCreate() = true
    private fun file(u: Uri): File {
        val n = u.lastPathSegment?.takeIf { NAME.matches(it) && !it.startsWith(".") } ?: throw java.io.FileNotFoundException()
        return File(dir(context!!), n)
    }
    override fun getType(u: Uri): String? = null
    override fun openFile(u: Uri, mode: String): ParcelFileDescriptor {
        val f = file(u)
        if (mode.contains('w') && !f.exists()) f.createNewFile()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = file(u)
        val cols = p ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply { addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }) }
    }
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
