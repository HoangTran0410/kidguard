package dev.kidguard

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Reads the system's record of how KidGuard's process ended (Android 11+). Pure rules in [ExitLogic]. Off the main thread. */
object ExitInfo {
    data class Full(val e: ExitLogic.Exit, val sub: String, val imp: Int, val pssKb: Long, val trace: String?)

    /** The last [n] exits, newest first; empty before Android 11. ANR / native traces are read only when [traces]. */
    fun recent(ctx: Context, n: Int = 8, traces: Boolean = false): List<Full> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, n) }.getOrDefault(emptyList()).map { x ->
            val tr = if (traces && (x.reason == 6 || x.reason == 5)) runCatching {
                x.traceInputStream?.use { s -> String(s.readNBytesCompat(512 * 1024)) }
            }.getOrNull() else null
            Full(ExitLogic.Exit(x.timestamp, x.reason, x.description ?: ""), x.processName ?: "", x.importance, x.pss, tr)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var left = max
        while (left > 0) { val r = read(buf, 0, minOf(buf.size, left)); if (r < 0) break; out.write(buf, 0, r); left -= r }
        return out.toByteArray()
    }

    /** Support report section: one line per exit, plus the main thread of the newest ANR. */
    fun report(ctx: Context): String {
        val all = recent(ctx, 10, traces = true)
        if (all.isEmpty()) return if (Build.VERSION.SDK_INT < 30) "(Android < 11)" else "(none)"
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
        val sb = StringBuilder()
        for (f in all) sb.append(fmt.format(java.util.Date(f.e.t))).append(' ').append(ExitLogic.name(f.e.reason))
            .append(" imp=").append(f.imp).append(" pss=").append(f.pssKb / 1024).append("MB ").append(f.sub.removePrefix(ctx.packageName))
            .append(if (f.e.desc.isNotBlank()) " | " + f.e.desc.take(200) else "").append('\n')
        all.firstOrNull { it.e.reason == 6 && it.trace != null }?.let { sb.append("\nnewest ANR, main thread:\n").append(ExitLogic.mainThread(it.trace!!, 4000)).append('\n') }
        return sb.toString()
    }
}
