package dev.kidguard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File

/**
 * KidGuard Home (home flavor only, declared in src/home/AndroidManifest.xml) hands its layout to KidGuard once, when
 * KidGuard's own launcher is turned on: the kid keeps the home screen they set up. Only an app signed with the same key
 * may read it (checked per call; both APKs share signing.jks).
 * call("layout") -> {"json": home.json text}; openFile("content://dev.kidguard.home.export/wall") -> wall.jpg.
 */
class HomeExport : ContentProvider() {
    override fun onCreate() = true

    private fun sameSigner(): Boolean {
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        return uid == Process.myUid() || ctx.packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!sameSigner()) throw SecurityException("not the same signer")
        val ctx = context ?: return null
        if (method != "layout") return null
        val m = HomeFile.load(ctx) ?: return Bundle()
        return Bundle().apply { putString("json", m.toJson().toString()) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!sameSigner()) throw SecurityException("not the same signer")
        if (mode != "r" || uri.lastPathSegment != "wall") return null
        val f = File(context!!.filesDir, Wallpaper.PHOTO)
        return if (f.isFile) ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) else null
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        private const val WALL_MAX = 16L shl 20

        /**
         * KidGuard side: with no layout of its own yet, take KidGuard Home's (layout, notes, to-dos, timetable, own
         * wallpaper photo). Must run before the Home app is hidden. Returns true when a layout was taken.
         */
        fun pull(ctx: Context): Boolean = runCatching {
            if (Flavor.HOME || File(ctx.filesDir, "home.json").exists() || File(ctx.filesDir, "home.json.bad").exists()) return false
            val uri = Uri.parse("content://${ctx.packageName}.home.export")
            val json = ctx.contentResolver.call(uri, "layout", null, null)?.getString("json") ?: return false
            val m = HomeLayout.imported(HomeLayout.parse(json) ?: return false)
            runCatching {
                ctx.contentResolver.openInputStream(Uri.withAppendedPath(uri, "wall"))?.use { inp ->
                    val tmp = File(ctx.filesDir, Wallpaper.PHOTO + ".tmp")
                    tmp.outputStream().use { out ->
                        // stop at the cap instead of copying a huge file first
                        val buf = ByteArray(64 * 1024); var total = 0L
                        while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > WALL_MAX) error("wallpaper too big"); out.write(buf, 0, n) }
                    }
                    if (!tmp.renameTo(File(ctx.filesDir, Wallpaper.PHOTO))) tmp.delete()
                }
            }.onFailure { File(ctx.filesDir, Wallpaper.PHOTO + ".tmp").delete() }
            HomeFile.save(ctx, m)
            Log.i("KidGuard", "launcher layout taken from KidGuard Home")
            true
        }.onFailure { Log.i("KidGuard", "no KidGuard Home layout: $it") }.getOrDefault(false)
    }
}
