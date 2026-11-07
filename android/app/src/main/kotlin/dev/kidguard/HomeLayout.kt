package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The kid launcher's local layout (filesDir/home.json, protocol "v0.9.9: kid launcher"). Pure: parse, repair,
 * first-run placement, append / pin / remove. Visibility is never stored: every render filters through the rules.
 */
data class HItem(
    val id: String, val kind: String, val x: Int, val y: Int, val w: Int = 1, val h: Int = 1,
    val pkg: String = "", val cls: String = "", val type: String = "", val name: String = "", val apps: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("x", x).put("y", y).also { o ->
        if (w != 1 || kind == "widget") o.put("w", w)
        if (h != 1 || kind == "widget") o.put("h", h)
        if (pkg.isNotEmpty()) o.put("pkg", pkg)
        if (cls.isNotEmpty()) o.put("cls", cls)
        if (type.isNotEmpty()) o.put("type", type)
        if (kind == "folder") o.put("name", name).put("apps", JSONArray(apps))
    }
    fun covers(cx: Int, cy: Int) = cx in x until x + w && cy in y until y + h
}

data class HStyle(
    val preset: String = "teen", val icon: Int = 3, val labels: Boolean = true, val shape: String = "round", val dockBg: Boolean = true,
    val accent: String = "", val theme: String = "auto", val font: Double = 1.0, val wallKind: String = "builtin", val wall: String = "", val dim: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("preset", preset).put("icon", icon).put("labels", labels).put("shape", shape).put("dockBg", dockBg)
        .put("accent", accent).put("theme", theme).put("font", font)
        .put("wall", JSONObject().put("kind", wallKind).put("id", wall).put("dim", dim))

    companion object {
        fun fromJson(o: JSONObject?): HStyle {
            if (o == null) return HStyle()
            val w = o.optJSONObject("wall") ?: JSONObject()
            return HStyle(
                preset = o.optString("preset", "teen").takeIf { p -> HomeStyle.PRESETS.any { it.id == p } } ?: "teen",
                icon = o.optInt("icon", 3).coerceIn(1, 5), labels = o.optBoolean("labels", true),
                shape = o.optString("shape", "round").takeIf { it == "round" || it == "square" } ?: "round",
                dockBg = o.optBoolean("dockBg", true),
                accent = o.optString("accent", "").takeIf { a -> a.isEmpty() || HomeStyle.ACCENTS.any { it.id == a } } ?: "",
                theme = o.optString("theme", "auto").takeIf { it in setOf("auto", "light", "dark") } ?: "auto",
                font = o.optDouble("font", 1.0).takeIf { !it.isNaN() }?.coerceIn(0.85, 1.3) ?: 1.0,
                wallKind = w.optString("kind", "builtin").takeIf { it in setOf("builtin", "photo", "system") } ?: "builtin",
                wall = w.optString("id", "").takeIf { it.isEmpty() || it in HomeStyle.WALLS } ?: "",
                dim = w.optInt("dim", 0).coerceIn(0, 60),
            )
        }
    }
}

data class HomeModel(
    val cols: Int, val rows: Int, val pages: List<List<HItem>>, val dock: List<String>, val style: HStyle,
    val seen: Set<String> = emptySet(),                      // apps placed once or removed by the kid: never auto-added again
    val notes: JSONArray = JSONArray(), val todo: JSONArray = JSONArray(),   // the -1 page's (part 2), kept as they are
) {
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("grid", JSONObject().put("cols", cols).put("rows", rows))
        .put("pages", JSONArray().apply { pages.forEach { p -> put(JSONObject().put("items", JSONArray().apply { p.forEach { put(it.toJson()) } })) } })
        .put("dock", JSONArray().apply { dock.forEach { put(JSONObject().put("pkg", it)) } })
        .put("style", style.toJson()).put("seen", JSONArray(seen.sorted())).put("notes", notes).put("todo", todo)

    /** Packages on the grid (folders included) or in the dock. */
    fun onHome(): Set<String> = pages.flatten().flatMap { if (it.kind == "folder") it.apps else listOf(it.pkg) }.filter { it.isNotEmpty() }.toSet() +
        dock.filter { it.isNotEmpty() && !it.startsWith("@") }
}

object HomeLayout {
    const val PHONE = "@phone"
    const val DOCK = 4
    val WIDGETS = setOf("clock", "time", "quick")             // part 2 adds next / chores / notes / files
    val KINDS = setOf("app", "widget", "folder")

    /** Widget ids are "w" + n, apps "a" + n: unique inside one model. */
    private fun nextId(m: List<HItem>, prefix: String): String {
        val used = m.map { it.id }.toSet()
        var n = 1
        while ("$prefix$n" in used) n++
        return "$prefix$n"
    }

    fun free(items: List<HItem>, cols: Int, rows: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        x >= 0 && y >= 0 && x + w <= cols && y + h <= rows && (x until x + w).all { cx -> (y until y + h).all { cy -> items.none { it.covers(cx, cy) } } }

    /** First free cell of [items] in reading order, or null. */
    fun firstFree(items: List<HItem>, cols: Int, rows: Int, w: Int = 1, h: Int = 1): Pair<Int, Int>? {
        for (y in 0..rows - h) for (x in 0..cols - w) if (free(items, cols, rows, x, y, w, h)) return x to y
        return null
    }

    /** Page 1 widgets for a preset on a [cols]-wide grid: clock, time left, quick actions. */
    fun defaultWidgets(p: HomeStyle.Preset, cols: Int): List<HItem> {
        val tw = if (p.timeW in 1 until cols) p.timeW else 0
        return if (tw > 0) listOf(
            HItem("w1", "widget", 0, 0, cols - tw, 1, type = "clock"),
            HItem("w2", "widget", cols - tw, 0, tw, 1, type = "time"),
            HItem("w3", "widget", 0, 1, cols, 1, type = "quick"))
        else listOf(
            HItem("w1", "widget", 0, 0, cols, 1, type = "clock"),
            HItem("w2", "widget", 0, 1, cols, 1, type = "time"),
            HItem("w3", "widget", 0, 2, cols, 1, type = "quick"))
    }

    /** Places [pkg] at the first free cell from page 0 on (a new page when all are full). */
    fun place(pages: List<List<HItem>>, cols: Int, rows: Int, pkg: String, cls: String = ""): List<List<HItem>> {
        val all = pages.flatten()
        val id = nextId(all, "a")
        pages.forEachIndexed { i, p ->
            firstFree(p, cols, rows)?.let { (x, y) -> return pages.toMutableList().also { it[i] = p + HItem(id, "app", x, y, pkg = pkg, cls = cls) } }
        }
        return pages + listOf(listOf(HItem(id, "app", 0, 0, pkg = pkg, cls = cls)))
    }

    /**
     * First run: page 1 = the preset's widgets then [apps] (visible now, in the caller's order); more pages as needed.
     * [dockApps]: up to 3 apps for slots 2-4 (slot 1 is always the phone). Apps in the dock are not on the grid too.
     */
    fun fresh(preset: String, apps: List<String>, dockApps: List<String>): HomeModel {
        val p = HomeStyle.preset(preset)
        val dock = (listOf(PHONE) + dockApps.filter { it.isNotEmpty() && it != PHONE }.distinct().take(DOCK - 1)).let { it + List(DOCK - it.size) { "" } }
        var pages: List<List<HItem>> = listOf(defaultWidgets(p, p.cols))
        val onGrid = apps.distinct().filter { it !in dock }
        for (a in onGrid) pages = place(pages, p.cols, p.rows, a)
        return HomeModel(p.cols, p.rows, pages, dock, HStyle(preset = p.id, dockBg = p.dockBg, labels = p.labels), (onGrid + dock.filter { !it.startsWith("@") && it.isNotEmpty() }).toSet())
    }

    /** Newly visible apps (never seen before) go to the first free cell. Returns the same model when nothing is new. */
    fun appendNew(m: HomeModel, visible: List<String>): HomeModel {
        val on = m.onHome()
        val new = visible.distinct().filter { it !in m.seen && it !in on }
        if (new.isEmpty()) return m
        var pages = m.pages
        for (a in new) pages = place(pages, m.cols, m.rows, a)
        return m.copy(pages = pages, seen = m.seen + new)
    }

    /** "Ghim vào màn chính": first free cell (no-op when already on the grid or in the dock). */
    fun pin(m: HomeModel, pkg: String): HomeModel = if (pkg in m.onHome()) m else m.copy(pages = place(m.pages, m.cols, m.rows, pkg), seen = m.seen + pkg)

    /** "Bỏ khỏi màn chính": takes the shortcut off the grid / dock (the app stays in the drawer). The phone slot stays. */
    fun remove(m: HomeModel, pkg: String): HomeModel {
        val pages = m.pages.map { p -> p.filter { !(it.kind == "app" && it.pkg == pkg) } }
        val dock = m.dock.mapIndexed { i, d -> if (i > 0 && d == pkg) "" else d }
        return trimPages(m.copy(pages = pages, dock = dock, seen = m.seen + pkg))
    }

    /** Empty pages after the first are dropped. */
    fun trimPages(m: HomeModel): HomeModel {
        val keep = m.pages.filterIndexed { i, p -> i == 0 || p.isNotEmpty() }
        return if (keep.size == m.pages.size) m else m.copy(pages = keep)
    }

    /** Apps that are gone for good (uninstalled; [gone] says so) leave the grid, folders and dock. */
    fun prune(m: HomeModel, gone: (String) -> Boolean): HomeModel {
        val pages = m.pages.map { p -> p.mapNotNull { i ->
            when (i.kind) {
                "app" -> i.takeIf { !gone(i.pkg) }
                "folder" -> i.copy(apps = i.apps.filter { !gone(it) }).takeIf { it.apps.isNotEmpty() }
                else -> i
            }
        } }
        val dock = m.dock.mapIndexed { i, d -> if (i > 0 && d.isNotEmpty() && gone(d)) "" else d }
        val out = m.copy(pages = pages, dock = dock, seen = m.seen.filter { !gone(it) }.toSet())
        return if (out == m) m else trimPages(out)
    }

    /**
     * Reads home.json. Never throws: null when it can't be parsed at all (the caller keeps the file as .bad and builds
     * a fresh one). Repairs: grid size, unknown kinds / widget types, items outside the grid (widgets shrink to fit,
     * apps move to a free cell), overlaps, duplicate apps, dock size and the fixed phone slot.
     */
    fun parse(s: String?): HomeModel? {
        val o = runCatching { JSONObject(s ?: return null) }.getOrNull() ?: return null
        val g = o.optJSONObject("grid") ?: JSONObject()
        val style = HStyle.fromJson(o.optJSONObject("style"))
        val cols = g.optInt("cols", HomeStyle.preset(style.preset).cols).coerceIn(3, 5)
        val rows = g.optInt("rows", HomeStyle.preset(style.preset).rows).coerceIn(4, 6)
        val pa = o.optJSONArray("pages") ?: JSONArray()
        val seenPkgs = HashSet<String>()
        val misplaced = ArrayList<HItem>()
        var pages = (0 until minOf(pa.length(), 20)).map { pi ->
            val items = pa.optJSONObject(pi)?.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<HItem>()
            for (k in 0 until minOf(items.length(), 60)) {
                val j = items.optJSONObject(k) ?: continue
                val kind = j.optString("kind")
                if (kind !in KINDS) continue
                var it = HItem(j.optString("id").take(16).ifEmpty { "i$pi-$k" }, kind, j.optInt("x", -1), j.optInt("y", -1),
                    j.optInt("w", 1).coerceIn(1, 5), j.optInt("h", 1).coerceIn(1, 6),
                    j.optString("pkg").take(200), j.optString("cls").take(300), j.optString("type"), j.optString("name").take(40),
                    j.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList())
                when (kind) {
                    "widget" -> { if (it.type !in WIDGETS) continue; it = it.copy(w = minOf(it.w, cols), h = minOf(it.h, rows), x = minOf(it.x, cols - minOf(it.w, cols))) }
                    "app" -> { if (it.pkg.isEmpty() || !seenPkgs.add(it.pkg)) continue; it = it.copy(w = 1, h = 1) }
                    "folder" -> { val apps = it.apps.filter { seenPkgs.add(it) }.distinct(); if (apps.isEmpty()) continue; it = it.copy(w = 1, h = 1, apps = apps) }
                }
                if (free(out, cols, rows, it.x, it.y, it.w, it.h)) out += it
                else if (it.kind != "widget") misplaced += it      // a widget that doesn't fit is dropped (the picker brings it back)
            }
            out.toList()
        }.ifEmpty { listOf(emptyList()) }
        // unique ids across pages
        val ids = HashSet<String>()
        var n = 0
        fun fresh(prefix: String): String { var id: String; do { id = "$prefix${++n}" } while (id in ids || pages.flatten().any { it.id == id } || misplaced.any { it.id == id }); return id }
        pages = pages.map { p -> p.map { i -> if (ids.add(i.id)) i else i.copy(id = fresh(if (i.kind == "widget") "w" else "a")).also { ids.add(it.id) } } }
        for (m0 in misplaced) {
            val it = if (ids.add(m0.id)) m0 else m0.copy(id = fresh("a")).also { ids.add(it.id) }
            val all = pages
            val idx = all.indexOfFirst { p -> firstFree(p, cols, rows) != null }
            pages = if (idx >= 0) all.toMutableList().also { l -> val (x, y) = firstFree(all[idx], cols, rows)!!; l[idx] = all[idx] + it.copy(x = x, y = y) }
                else all + listOf(listOf(it.copy(x = 0, y = 0)))
        }
        val da = o.optJSONArray("dock") ?: JSONArray()
        val dockSeen = HashSet<String>()
        val dock = (0 until DOCK).map { i ->
            val p = if (i < da.length()) (da.optJSONObject(i)?.optString("pkg") ?: da.optString(i, "")) else ""
            when {
                i == 0 -> PHONE
                p.isEmpty() || p.startsWith("@") || p in seenPkgs || !dockSeen.add(p) -> ""
                else -> p
            }
        }
        val seen = o.optJSONArray("seen")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }?.toSet() ?: emptySet()
        return trimPages(HomeModel(cols, rows, pages, dock, style, seen + seenPkgs + dock.filter { it.isNotEmpty() && !it.startsWith("@") },
            o.optJSONArray("notes") ?: JSONArray(), o.optJSONArray("todo") ?: JSONArray()))
    }

    /** SOS invariant: the phone is always in dock slot 1. */
    fun sosOk(m: HomeModel) = m.dock.size == DOCK && m.dock[0] == PHONE
}
