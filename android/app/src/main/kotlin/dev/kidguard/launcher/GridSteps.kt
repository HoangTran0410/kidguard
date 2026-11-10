package dev.kidguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * filesDir/home.grid.json: the undo stack of automatic row changes ([HomeLayout.GridStep]), so a shrink (bigger icons /
 * font: 7 -> 6 rows) followed by the way back restores the kid's page exactly, also across a process restart.
 */
object GridSteps {
    private const val NAME = "home.grid.json"
    @Volatile private var cache: List<HomeLayout.GridStep>? = null

    @Synchronized fun load(ctx: Context): List<HomeLayout.GridStep> {
        cache?.let { return it }
        val out = runCatching {
            val a = JSONArray(File(ctx.filesDir, NAME).readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val b = HomeLayout.parse(o.getString("before")) ?: return@mapNotNull null
                val n = HomeLayout.parse(o.getString("after")) ?: return@mapNotNull null
                HomeLayout.GridStep(b.cols, b.rows, b.pages, n.pages)
            }
        }.getOrDefault(emptyList())
        cache = out
        return out
    }

    @Synchronized fun save(ctx: Context, steps: List<HomeLayout.GridStep>, m: HomeModel) {
        if (steps == cache) return
        cache = steps
        runCatching {
            val f = File(ctx.filesDir, NAME)
            if (steps.isEmpty()) { f.delete(); return }
            // pages only (style/notes are not needed): stored as a minimal model so HomeLayout.parse reads them back
            fun pages(rows: Int, p: List<List<HItem>>) =
                HomeModel(m.cols, rows, p, emptyList(), HStyle()).toJson().toString()
            val a = JSONArray()
            steps.forEach { s -> a.put(JSONObject().put("before", pages(s.rows, s.before)).put("after", pages(s.rows, s.after))) }
            val tmp = File(ctx.filesDir, "$NAME.tmp"); tmp.writeText(a.toString()); tmp.renameTo(f)
        }
    }
}
