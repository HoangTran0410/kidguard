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
    val awid: Int = 0,                                       // type "app": the AppWidgetHost id of another app's widget
    val opt: String = "",                                    // a widget's own setting from its long-press menu (clock: "center")
    val names: Map<String, String> = emptyMap(),             // folder: label of each shortcut entry in [apps] (entry -> label); packages need none
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("x", x).put("y", y).also { o ->
        if (w != 1 || kind == "widget") o.put("w", w)
        if (h != 1 || kind == "widget") o.put("h", h)
        if (pkg.isNotEmpty()) o.put("pkg", pkg)
        if (cls.isNotEmpty()) o.put("cls", cls)
        if (type.isNotEmpty()) o.put("type", type)
        if (kind == "folder") {
            o.put("name", name).put("apps", JSONArray(apps))
            val n = names.filterKeys { it in apps }
            if (n.isNotEmpty()) o.put("names", JSONObject().also { j -> n.forEach { (k, v) -> j.put(k, v) } })
        }
        if (kind == HomeLayout.SHORTCUT) o.put("name", name)
        if (awid != 0) o.put("awid", awid)
        if (opt.isNotEmpty()) o.put("opt", opt)
    }
    fun covers(cx: Int, cy: Int) = cx in x until x + w && cy in y until y + h
}

data class HStyle(
    val preset: String = "teen", val icon: Int = 2, val labels: Boolean = true, val shape: String = "soft", val dockBg: Boolean = true, val dockLabels: Boolean = true, val badge: String = "num", val face: String = "sys",
    val accent: String = "", val theme: String = "auto", val font: Double = 1.0, val wallKind: String = "builtin", val wall: String = "", val dim: Int = 0, val blur: Int = 0,
    val lib: Boolean = true, val fx: String = "auto", val today: Boolean = true,
    val lbl: String = "m",   // app-name size: "auto" = follows the icon size (the old rule), "s" / "m" / "l" = fixed
    val rim: String = "top", val fill: String = "sheen",   // icon edge (top = lit from above, line, glass = the old diagonal, none) / face (app = as the app draws it, sheen = iOS-like light)
    val speak: Boolean = false, val calm: Boolean = false, val solid: Boolean = false,   // reading aids: say the name on tap / launcher animations off / solid label backing
) {
    fun toJson(): JSONObject = JSONObject().put("preset", preset).put("icon", icon).put("labels", labels).put("shape", shape).put("dockBg", dockBg).put("dockLabels", dockLabels).put("badge", badge).put("face", face).put("lib", lib).put("fx", fx).put("today", today).put("speak", speak).put("calm", calm).put("solid", solid)
        .put("rim", rim).put("fill", fill).put("accent", accent).put("theme", theme).put("font", font).put("lbl", lbl)
        .put("wall", JSONObject().put("kind", wallKind).put("id", wall).put("dim", dim).put("blur", blur))

    companion object {
        val RIMS = listOf("top", "line", "glass", "none"); val FILLS = listOf("app", "sheen")
        fun fromJson(o: JSONObject?): HStyle {
            if (o == null) return HStyle()
            val w = o.optJSONObject("wall") ?: JSONObject()
            return HStyle(
                preset = o.optString("preset", "teen").takeIf { p -> HomeStyle.PRESETS.any { it.id == p } } ?: "teen",
                icon = o.optInt("icon", 2).coerceIn(1, 5), labels = o.optBoolean("labels", true),
                shape = o.optString("shape", "soft").takeIf { it == "round" || it == "soft" || it == "square" } ?: "soft",
                dockBg = o.optBoolean("dockBg", true), dockLabels = o.optBoolean("dockLabels", true), badge = o.optString("badge", "num").takeIf { it in setOf("num", "dot", "off") } ?: "num",
                face = o.optString("face", "sys").takeIf { it in HomeActivity.FACES } ?: "sys",
                accent = o.optString("accent", "").takeIf { a -> a.isEmpty() || HomeStyle.ACCENTS.any { it.id == a } } ?: "",
                theme = o.optString("theme", "auto").takeIf { it in setOf("auto", "light", "dark") } ?: "auto",
                font = o.optDouble("font", 1.0).takeIf { !it.isNaN() }?.coerceIn(0.85, 1.3) ?: 1.0,
                wallKind = w.optString("kind", "builtin").takeIf { it in setOf("builtin", "photo", "system") } ?: "builtin",
                wall = w.optString("id", "").takeIf { it.isEmpty() || it in HomeStyle.WALLS } ?: "",
                dim = w.optInt("dim", 0).coerceIn(0, 60), blur = w.optInt("blur", 0).coerceIn(0, 100),
                lib = o.optBoolean("lib", true), today = o.optBoolean("today", true),
                speak = o.optBoolean("speak", false), calm = o.optBoolean("calm", false), solid = o.optBoolean("solid", false),
                fx = o.optString("fx", "auto").takeIf { it in setOf("auto", "full", "lite") } ?: "auto",
                lbl = o.optString("lbl", "m").takeIf { it in setOf("auto", "s", "m", "l") } ?: "m",
                rim = o.optString("rim", "top").takeIf { it in RIMS } ?: "top",
                fill = o.optString("fill", "sheen").takeIf { it in FILLS } ?: "sheen",
            )
        }
    }
}

data class HomeModel(
    val cols: Int, val rows: Int, val pages: List<List<HItem>>, val dock: List<String>, val style: HStyle,
    val seen: Set<String> = emptySet(),                      // apps placed once or removed by the kid: never auto-added again
    val notes: JSONArray = JSONArray(), val todo: JSONArray = JSONArray(),   // the -1 page's (part 2), kept as they are
    val timetable: JSONObject = JSONObject(),                // "Thời khoá biểu" widget: {"1":[subject…],…,"7":[…]} (ISO weekday), kept as is
    val autoRows: Boolean = true,                            // rows follow the screen so the cells are ~square (POCO / One UI); off once the kid picks a number
    val todayHide: Set<String> = emptySet(),                 // "Hôm nay" cards the kid turned off (TodayPage ids: now time sched chores tomorrow notes files)
    val custom: Map<String, AppCustom> = emptyMap(),         // per-app rename / icon (AppLook), keyed by package
) {
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("grid", JSONObject().put("cols", cols).put("rows", rows).put("auto", autoRows))
        .put("pages", JSONArray().apply { pages.forEach { p -> put(JSONObject().put("items", JSONArray().apply { p.forEach { put(it.toJson()) } })) } })
        .put("dock", JSONArray().apply { dock.forEach { put(JSONObject().put("pkg", it)) } })
        .put("style", style.toJson()).put("seen", JSONArray(seen.sorted())).put("notes", notes).put("todo", todo).put("timetable", timetable)
        .put("todayHide", JSONArray(todayHide.sorted())).also { o -> if (custom.isNotEmpty()) o.put("custom", AppLook.toJson(custom)) }

    /** Packages on the grid (folders included) or in the dock. */
    fun onHome(): Set<String> = pages.flatten().filter { it.kind != HomeLayout.SHORTCUT }.flatMap { if (it.kind == "folder") it.apps.filter { e -> !HomeLayout.isShortcutEntry(e) } else listOf(it.pkg) }.filter { it.isNotEmpty() }.toSet() +
        dock.filter { it.isNotEmpty() && !it.startsWith("@") }
}

object HomeLayout {
    const val PHONE = "@phone"
    const val DOCK = 4
    /** Widget types and the sizes the picker offers (w x h; w 4 = the full row on any grid, see [span]). */
    val SIZES: Map<String, List<Pair<Int, Int>>> = linkedMapOf(
        "clock" to listOf(4 to 1, 4 to 2), "analog" to listOf(2 to 2), "month" to listOf(4 to 2, 2 to 2),
        "time" to listOf(2 to 1, 4 to 1), "next" to listOf(4 to 1), "quick" to listOf(4 to 1),
        "todo" to listOf(2 to 2, 4 to 2), "note" to listOf(2 to 2, 4 to 2), "counts" to listOf(2 to 2),
        "usage" to listOf(4 to 1, 4 to 2), "battery" to listOf(2 to 1), "lock" to listOf(1 to 1, 2 to 1),
        "when" to listOf(4 to 1, 4 to 2), "earn" to listOf(2 to 2, 4 to 2), "msg" to listOf(4 to 1, 2 to 2), "class" to listOf(4 to 2, 2 to 2, 4 to 4),
        "timer" to listOf(2 to 2, 4 to 2), "streak" to listOf(2 to 2, 4 to 2), "media" to listOf(4 to 1, 2 to 2))
    /** Another app's widget (AppWidgetHost): kind "widget", type "app", pkg / cls = its provider, awid = the host id. Any size. */
    const val APP = "app"
    /** A [SIZES] width on a [cols]-wide grid: 4 means the whole row (3, 4 or 5 cells), narrower ones stay as they are. */
    fun span(w: Int, cols: Int) = if (w >= 4) cols else minOf(w, cols)
    val WIDGETS = SIZES.keys + APP
    const val FOLDER_MAX = 20
    const val MAX_ROWS = 7

    /**
     * Rows for "auto": the count that makes a cell about as tall as it is wide ([availH] / [cellW], 4..7, like POCO's 4×6 /
     * 4×7 on a tall phone), fewer while a cell can't hold an icon + one label line ([minH]: big font scale). The current
     * [cur] stays while its cells are within 0.85..1.2 of square and tall enough: a banner or a small inset change never
     * moves the kid's icons.
     */
    fun fitRows(availH: Int, cellW: Int, minH: Int, cur: Int): Int {
        if (availH <= 0 || cellW <= 0) return cur
        var r = Math.round(availH.toFloat() / cellW).coerceIn(4, MAX_ROWS)
        while (r > 4 && availH / r < minH) r--
        val ratio = availH.toFloat() / cur / cellW
        // growing never moves an icon (only a shrink re-flows), so the square count wins over a smaller near-square one
        return if (cur in 4..MAX_ROWS && r <= cur && ratio in 0.85f..1.2f && availH / cur >= minH) cur else r
    }
    /** An app's pinned shortcut (ShortcutManager.requestPinShortcut): pkg = the app, cls = the shortcut id, name = its label. */
    const val SHORTCUT = "shortcut"
    /**
     * A shortcut label fit to show: not blank, not its id or package (MIUI's Security "Clean" shortcut hands
     * "com.miui.securitycenter:shortcut…" to a third-party home: a label from a resource only its own home resolves).
     */
    fun shortcutLabelOk(s: String?, pkg: String, id: String): Boolean {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || t == id || t == pkg || t.startsWith("$pkg:") || t.startsWith("$pkg/")) return false
        return !(' ' !in t && Regex("""^[A-Za-z]\w*(\.[A-Za-z]\w*)+([:/#][\w.:/#-]*)?$""").matches(t))
    }

    // A folder's [HItem.apps] entry is a package, or "sc:" + package + "/" + shortcut id (a pinned shortcut, e.g. a web app).
    fun isShortcutEntry(e: String) = e.startsWith(Shortcuts.PREFIX)
    fun scEntry(pkg: String, id: String) = Shortcuts.PREFIX + pkg + "/" + id
    /** The app behind a folder entry (a shortcut's own app). */
    fun pkgOf(e: String) = if (isShortcutEntry(e)) e.removePrefix(Shortcuts.PREFIX).substringBefore("/") else e
    fun scIdOf(e: String) = if (isShortcutEntry(e)) e.removePrefix(Shortcuts.PREFIX).substringAfter("/", "") else ""
    /** The folder entry of a grid item (an app or a shortcut). */
    fun entryOf(i: HItem) = if (i.kind == SHORTCUT) scEntry(i.pkg, i.cls) else i.pkg
    /** The grid item a folder entry becomes when it leaves its folder or is the only one left. */
    fun itemOfEntry(f: HItem, e: String, id: String = f.id, x: Int = f.x, y: Int = f.y) =
        if (isShortcutEntry(e)) HItem(id, SHORTCUT, x, y, pkg = pkgOf(e), cls = scIdOf(e), name = f.names[e] ?: "")
        else HItem(id, "app", x, y, pkg = e)
    /** [f] with [apps] as its entries (labels of the others dropped); one entry left = that app / shortcut again; none = null. */
    private fun folderWith(f: HItem, apps: List<String>): HItem? = when (apps.size) {
        0 -> null
        1 -> itemOfEntry(f, apps[0])
        else -> f.copy(apps = apps, names = f.names.filterKeys { it in apps })
    }
    private fun namesOf(items: List<HItem>): Map<String, String> =
        items.flatMap { i -> if (i.kind == SHORTCUT) listOf(entryOf(i) to i.name) else i.names.toList() }.filter { it.second.isNotEmpty() }.toMap()
    val KINDS = setOf("app", "widget", "folder", SHORTCUT)

    /** Widget ids are "w" + n, apps "a" + n: unique inside one model. */
    private fun nextId(m: List<HItem>, prefix: String): String {
        val used = m.map { it.id }.toSet()
        var n = 1
        while ("$prefix$n" in used) n++
        return "$prefix$n"
    }

    fun free(items: List<HItem>, cols: Int, rows: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        x >= 0 && y >= 0 && x + w <= cols && y + h <= rows && (x until x + w).all { cx -> (y until y + h).all { cy -> items.none { it.covers(cx, cy) } } }

    /** Every cell of the page is taken. */
    fun full(items: List<HItem>, cols: Int, rows: Int) = firstFree(items, cols, rows) == null

    /** Places [items] in order, each at the first free spot of the current page, a new page when it does not fit. */
    fun flow(items: List<HItem>, cols: Int, rows: Int): List<List<HItem>> {
        val out = arrayListOf(ArrayList<HItem>())
        for (it in items) {
            val f = firstFree(out.last(), cols, rows, it.w, it.h) ?: run { out += ArrayList<HItem>(); firstFree(out.last(), cols, rows, it.w, it.h) }
            if (f != null) out.last() += it.copy(x = f.first, y = f.second) else out += arrayListOf(it.copy(x = 0, y = 0))
        }
        return out
    }

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
     * [parent] false (KidGuard Home): no time-left / quick-actions widgets, a 2-row clock instead.
     */
    fun fresh(preset: String, apps: List<String>, dockApps: List<String>, parent: Boolean = true, known: List<String> = emptyList()): HomeModel {
        val p = HomeStyle.preset(preset)
        val dock = (listOf(PHONE) + dockApps.filter { it.isNotEmpty() && it != PHONE }.distinct().take(DOCK - 1)).let { it + List(DOCK - it.size) { "" } }
        var pages: List<List<HItem>> = listOf(if (parent) defaultWidgets(p, p.cols) else listOf(HItem("w1", "widget", 0, 0, p.cols, 2, type = "clock")))
        val onGrid = apps.distinct().filter { it !in dock }
        for (a in onGrid) pages = place(pages, p.cols, p.rows, a)
        return HomeModel(p.cols, p.rows, pages, dock, HStyle(preset = p.id, dockBg = p.dockBg, labels = p.labels), (onGrid + dock.filter { !it.startsWith("@") && it.isNotEmpty() } + known).toSet())
    }

    /** Everyday apps a first home screen shows, in this order (package, or a prefix ending in "."). */
    private val FEATURED = listOf(
        "com.google.android.apps.youtube.kids", "com.google.android.youtube", "com.zing.zalo", "com.facebook.orca",
        "com.google.android.apps.photos", "com.miui.gallery", "com.coloros.gallery3d", "com.oppo.gallery3d", "com.sec.android.gallery3d", "com.google.android.gallery3d",
        "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage", "com.coloros.alarmclock",
        "com.google.android.calculator", "com.miui.calculator", "com.coloros.calculator", "com.sec.android.app.popupcalculator", "com.android.calculator2",
        "com.google.android.calendar", "com.android.calendar", "com.google.android.apps.maps", "com.google.android.apps.classroom",
        "com.duolingo", "com.google.android.apps.docs", "com.spotify.music", "com.android.vending")

    /**
     * First run: up to [max] everyday apps for the grid instead of every app (bro, 11-10: "xóa mệt lắm"); the rest stay
     * in the drawer (pass them to [fresh] as known so they are not placed later). [used]: most used first, fills the
     * remaining slots when the phone has few of the known ones.
     */
    fun featured(all: List<String>, used: List<String> = emptyList(), max: Int = 8): List<String> {
        val set = all.toSet()
        val known = FEATURED.filter { it in set }.distinctBy { appKind(it) }
        return (known + used.filter { it in set }).distinct().take(max)
    }
    private fun appKind(p: String) = when {   // one gallery / clock / calculator even when the phone has two
        "gallery" in p || p.endsWith("photos") -> "gallery"; "clock" in p -> "clock"; "calculator" in p -> "calc"; else -> p }

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

    /** "Bỏ khỏi màn chính": takes the shortcut off the grid / folders / dock (the app stays in the drawer). The phone slot stays. A folder left with 1 app turns back into it. */
    fun remove(m: HomeModel, pkg: String): HomeModel {
        val pages = m.pages.map { p -> p.mapNotNull { i -> when {
            i.kind == "app" && i.pkg == pkg -> null
            i.kind == SHORTCUT && entryOf(i) == pkg -> null
            i.kind == "folder" && pkg in i.apps -> folderWith(i, i.apps - pkg)
            else -> i
        } } }
        val dock = m.dock.mapIndexed { i, d -> if (i > 0 && d == pkg) "" else d }
        return trimPages(m.copy(pages = pages, dock = dock, seen = if (isShortcutEntry(pkg)) m.seen else m.seen + pkg))
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
                "app", SHORTCUT -> i.takeIf { !gone(i.pkg) }
                "folder" -> folderWith(i, i.apps.filter { !gone(pkgOf(it)) })
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
        val rows = g.optInt("rows", HomeStyle.preset(style.preset).rows).coerceIn(4, MAX_ROWS)
        val pa = o.optJSONArray("pages") ?: JSONArray()
        val seenPkgs = HashSet<String>()
        val seenShortcuts = HashSet<String>()
        val seenAw = HashSet<Int>()
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
                    j.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList(), j.optInt("awid", 0), j.optString("opt").take(20),
                    j.optJSONObject("names")?.let { n -> n.keys().asSequence().associateWith { k -> n.optString(k).take(40) } } ?: emptyMap())
                when (kind) {
                    "widget" -> { if (it.type !in WIDGETS || (it.type == APP && (it.awid == 0 || it.pkg.isEmpty() || !seenAw.add(it.awid)))) continue; it = it.copy(w = minOf(it.w, cols), h = minOf(it.h, rows), x = minOf(it.x, cols - minOf(it.w, cols))) }
                    "app" -> { if (it.pkg.isEmpty() || !seenPkgs.add(it.pkg)) continue; it = it.copy(w = 1, h = 1) }
                    SHORTCUT -> { if (it.pkg.isEmpty() || it.cls.isEmpty() || !seenShortcuts.add(it.pkg + "/" + it.cls)) continue; it = it.copy(w = 1, h = 1, type = "", apps = emptyList(), names = emptyMap()) }
                    "folder" -> {
                        val apps = it.apps.filter { e -> if (isShortcutEntry(e)) (pkgOf(e).isNotEmpty() && scIdOf(e).isNotEmpty() && seenShortcuts.add(e.removePrefix(Shortcuts.PREFIX))) else seenPkgs.add(e) }.distinct().take(FOLDER_MAX)
                        if (apps.isEmpty()) continue
                        // a folder of one is that app (or shortcut) again
                        it = if (apps.size == 1) itemOfEntry(it, apps[0]).copy(id = it.id) else it.copy(w = 1, h = 1, apps = apps, names = it.names.filterKeys { k -> k in apps })
                    }
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
        // before 0.9.12 "4 wide" was 4 cells on a 5-column grid too: such a card at the left edge with the 5th column free
        // beside it now spans the row (what the picker offers since then); one placed next to an icon stays as it is
        // (3 wide on 4-5 columns too: the picker never offered 3, so it can only come from a column change)
        pages = pages.map { p -> p.map { i ->
            if (i.kind == "widget" && i.type != APP && i.x == 0 && i.w in 3 until cols && free(p, cols, rows, i.w, i.y, cols - i.w, i.h)) i.copy(w = cols) else i } }
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
            o.optJSONArray("notes") ?: JSONArray(), o.optJSONArray("todo") ?: JSONArray(), o.optJSONObject("timetable") ?: JSONObject(), g.optBoolean("auto", true),
            o.optJSONArray("todayHide")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet(),
            AppLook.parse(o.optJSONObject("custom"))))
    }

    /** SOS invariant: the phone is always in dock slot 1. */
    fun sosOk(m: HomeModel) = m.dock.size == DOCK && m.dock[0] == PHONE

    // ---------------- edit: drag and drop, folders, widgets (part 2) ----------------

    /** Where a drag starts: a grid item, a dock slot (1-3), an app inside a folder, or a new widget from the picker. */
    sealed class Src {
        data class Item(val id: String) : Src()
        data class Dock(val slot: Int) : Src()
        data class InFolder(val folder: String, val pkg: String) : Src()
        data class NewWidget(val type: String, val w: Int, val h: Int) : Src()
        /** Another app's widget dragged from the picker: not bound yet (awid 0), so a drop only picks the cell ([AwDrop]). */
        data class NewAppWidget(val pkg: String, val cls: String, val w: Int, val h: Int) : Src()
    }

    /** [m] with the dragged [item] taken out; where it came from ([page] -1 = not the grid, [dock] -1 = not the dock). */
    data class Taken(val m: HomeModel, val item: HItem, val page: Int = -1, val x: Int = 0, val y: Int = 0, val dock: Int = -1)

    fun find(m: HomeModel, id: String): Pair<Int, HItem>? {
        m.pages.forEachIndexed { i, p -> p.firstOrNull { it.id == id }?.let { return i to it } }
        return null
    }

    /**
     * The open folder re-arranged: [vis] = the shown apps in their new order. Apps not shown (hidden by a rule) keep
     * their slots in the stored list; the shown ones fill the other slots in the new order. Null if nothing matches.
     */
    fun reorderFolder(m: HomeModel, id: String, vis: List<String>): HomeModel? {
        val (pi, f) = find(m, id)?.takeIf { it.second.kind == "folder" } ?: return null
        val set = vis.toSet()
        if (set.size != vis.size || !f.apps.containsAll(set)) return null
        val it = vis.iterator()
        val apps = f.apps.map { p -> if (p in set) it.next() else p }
        if (apps == f.apps) return null
        return setPage(m, pi, m.pages[pi].map { o -> if (o.id == id) o.copy(apps = apps) else o })
    }

    private fun idFor(m: HomeModel, prefix: String) = nextId(m.pages.flatten(), prefix)

    private fun setPage(m: HomeModel, i: Int, items: List<HItem>): HomeModel {
        val pages = m.pages.toMutableList()
        while (pages.size <= i) pages += emptyList<HItem>()
        pages[i] = items
        return m.copy(pages = pages)
    }

    /** Picks up [src]. A folder left with one app turns back into that app (same cell); an empty one goes. */
    fun take(m: HomeModel, src: Src): Taken? = when (src) {
        is Src.Item -> find(m, src.id)?.let { (pi, it) -> Taken(setPage(m, pi, m.pages[pi].filter { o -> o.id != it.id }), it, pi, it.x, it.y) }
        is Src.Dock -> m.dock.getOrNull(src.slot)?.takeIf { src.slot > 0 && it.isNotEmpty() }?.let { pkg ->
            Taken(m.copy(dock = m.dock.mapIndexed { i, d -> if (i == src.slot) "" else d }), HItem(idFor(m, "a"), "app", 0, 0, pkg = pkg), dock = src.slot)
        }
        is Src.InFolder -> find(m, src.folder)?.takeIf { it.second.kind == "folder" && src.pkg in it.second.apps }?.let { (pi, f) ->
            val repl = folderWith(f, f.apps - src.pkg)
            val m2 = setPage(m, pi, m.pages[pi].mapNotNull { if (it.id == f.id) repl else it })
            Taken(m2, itemOfEntry(f, src.pkg, id = idFor(m2, if (isShortcutEntry(src.pkg)) "s" else "a"), x = 0, y = 0))
        }
        is Src.NewWidget -> if (src.type !in WIDGETS) null
            else Taken(m, HItem(idFor(m, "w"), "widget", 0, 0, minOf(src.w, m.cols), minOf(src.h, m.rows), type = src.type))
        is Src.NewAppWidget -> Taken(m, HItem(idFor(m, "w"), "widget", 0, 0, minOf(src.w, m.cols), minOf(src.h, m.rows), pkg = src.pkg, cls = src.cls, type = APP))
    }

    /** Drops on the empty cells at ([x], [y]) of [page] ([page] == pages.size: a new page). Null = no room there. */
    fun dropCell(t: Taken, page: Int, x: Int, y: Int): HomeModel? {
        val m = t.m
        if (page < 0 || page > m.pages.size) return null
        val items = m.pages.getOrNull(page) ?: emptyList()
        if (!free(items, m.cols, m.rows, x, y, t.item.w, t.item.h)) return null
        return trimPages(setPage(m, page, items + t.item.copy(x = x, y = y)))
    }

    /**
     * Re-arrange: dropping on cells taken by exactly one item of the same size moves that item out of the way, to the
     * spot the dragged one left (same page, if it fits there) or else to the nearest free spot on the page. Returns the
     * displaced item and its new cell, or null (free cells: plain [dropCell]; several / other-size items: no room).
     */
    fun swapSpot(t: Taken, page: Int, x: Int, y: Int): Triple<HItem, Int, Int>? {
        val m = t.m
        val items = m.pages.getOrNull(page) ?: return null
        val w = t.item.w; val h = t.item.h
        val hit = items.filter { it.x < x + w && x < it.x + it.w && it.y < y + h && y < it.y + it.h }
        val u = hit.singleOrNull()?.takeIf { it.w == w && it.h == h } ?: return null
        val rest = items - u + t.item.copy(x = x, y = y)
        if (t.page == page && free(rest, m.cols, m.rows, t.x, t.y, u.w, u.h)) return Triple(u, t.x, t.y)
        var best: Pair<Int, Int>? = null; var bd = Int.MAX_VALUE
        for (cy in 0..m.rows - u.h) for (cx in 0..m.cols - u.w) if (free(rest, m.cols, m.rows, cx, cy, u.w, u.h)) {
            val d = (cx - u.x) * (cx - u.x) + (cy - u.y) * (cy - u.y)
            if (d < bd) { bd = d; best = cx to cy }
        }
        return best?.let { Triple(u, it.first, it.second) }
    }

    /** [dropCell], or on a taken spot the [swapSpot] move. Null = no room. */
    fun dropMove(t: Taken, page: Int, x: Int, y: Int): HomeModel? {
        dropCell(t, page, x, y)?.let { return it }
        val (u, nx, ny) = swapSpot(t, page, x, y) ?: return null
        val items = t.m.pages[page].map { if (it.id == u.id) it.copy(x = nx, y = ny) else it } + t.item.copy(x = x, y = y)
        return trimPages(setPage(t.m, page, items))
    }

    /**
     * Drops an app on the app or folder [targetId]: app + app = a new folder named [name] in the target's cell;
     * app + folder = one more app in it. Null when it can't (a widget or a folder dragged, a full folder).
     */
    fun dropOnto(t: Taken, targetId: String, name: String): HomeModel? {
        if (t.item.kind != "app" && t.item.kind != SHORTCUT) return null
        val m = t.m
        val (pi, target) = find(m, targetId) ?: return null
        val e = entryOf(t.item)
        val repl = when (target.kind) {
            "app", SHORTCUT -> if (entryOf(target) == e) return null else HItem(idFor(m, "f"), "folder", target.x, target.y, name = name.take(40), apps = listOf(entryOf(target), e), names = namesOf(listOf(target, t.item)))
            "folder" -> if (target.apps.size >= FOLDER_MAX || e in target.apps) return null else target.copy(apps = target.apps + e, names = target.names + namesOf(listOf(t.item)))
            else -> return null
        }
        return trimPages(setPage(m, pi, m.pages[pi].map { if (it.id == targetId) repl else it }))
    }

    /**
     * Drops an app on dock [slot] (1-3; slot 0 is the phone). An occupied slot swaps: its app goes where the dragged one
     * came from (that grid cell, that dock slot), or to the first free cell.
     */
    fun dropDock(t: Taken, slot: Int): HomeModel? {
        if (t.item.kind != "app" || slot !in 1 until DOCK) return null
        val m = t.m
        val old = m.dock[slot]
        var out = m.copy(dock = m.dock.mapIndexed { i, d -> if (i == slot) t.item.pkg else d })
        if (old.isNotEmpty() && old != t.item.pkg) out = when {
            t.dock > 0 -> out.copy(dock = out.dock.mapIndexed { i, d -> if (i == t.dock) old else d })
            t.page >= 0 && free(out.pages.getOrNull(t.page) ?: emptyList(), m.cols, m.rows, t.x, t.y, 1, 1) ->
                setPage(out, t.page, (out.pages.getOrNull(t.page) ?: emptyList()) + HItem(idFor(out, "a"), "app", t.x, t.y, pkg = old))
            else -> out.copy(pages = place(out.pages, m.cols, m.rows, old))
        }
        return trimPages(out)
    }

    /**
     * Drop between the dock's icons (they sit packed and centred, empty slots hidden): the app goes in after the first [k]
     * shown icons (the phone is always the first), the rest move up. Null when the dock is full (then it replaces one: [dropDock]).
     */
    fun insertDock(t: Taken, k: Int): HomeModel? {
        if (t.item.kind != "app") return null
        val m = t.m
        val rest = m.dock.drop(1).filter { it.isNotEmpty() && it != t.item.pkg }.toMutableList()
        if (rest.size >= DOCK - 1) return null
        rest.add((k - 1).coerceIn(0, rest.size), t.item.pkg)
        return trimPages(m.copy(dock = listOf(m.dock[0]) + rest + List(DOCK - 1 - rest.size) { "" }))
    }

    /** "Bỏ khỏi màn chính" target: what was taken stays off the home (apps stay in the drawer and are not re-added). */
    fun dropRemove(t: Taken): HomeModel = trimPages(t.m.copy(seen = t.m.seen + listOf(t.item.pkg).filter { it.isNotEmpty() && t.item.kind != SHORTCUT } + t.item.apps.filter { !isShortcutEntry(it) }))

    fun rename(m: HomeModel, id: String, name: String): HomeModel {
        val n = name.trim().take(40)
        val (pi, f) = find(m, id)?.takeIf { it.second.kind == "folder" } ?: return m
        return if (n.isEmpty() || n == f.name) m else setPage(m, pi, m.pages[pi].map { if (it.id == id) it.copy(name = n) else it })
    }

    /** Takes [pkg] off the grid / dock / other folders, wherever it is (the menu's "Thêm vào thư mục" starts here). */
    fun detach(m: HomeModel, pkg: String): Taken {
        m.pages.flatten().firstOrNull { (it.kind == "app" && it.pkg == pkg) || (it.kind == SHORTCUT && entryOf(it) == pkg) }?.let { return take(m, Src.Item(it.id))!! }
        m.pages.flatten().firstOrNull { it.kind == "folder" && pkg in it.apps }?.let { return take(m, Src.InFolder(it.id, pkg))!! }
        val slot = m.dock.indexOf(pkg)
        if (slot > 0) return take(m, Src.Dock(slot))!!
        if (isShortcutEntry(pkg)) return Taken(m, HItem(idFor(m, "s"), SHORTCUT, 0, 0, pkg = pkgOf(pkg), cls = scIdOf(pkg)))
        return Taken(m, HItem(idFor(m, "a"), "app", 0, 0, pkg = pkg))
    }

    /** "Thêm vào thư mục": [pkg] into the folder or onto the app [targetId] (then a new folder [name]). */
    fun addTo(m: HomeModel, pkg: String, targetId: String, name: String): HomeModel? {
        val t = detach(m, pkg)
        return dropOnto(t.copy(m = t.m.copy(seen = t.m.seen + pkgOf(pkg))), targetId, name)
    }

    // ---- edit mode (several items at once, POCO / One UI) ----

    /** The picked grid items in reading order: page, then row, then column. */
    private fun picked(m: HomeModel, ids: Collection<String>): List<Pair<Int, HItem>> =
        m.pages.flatMapIndexed { pi, p -> p.filter { it.id in ids }.map { pi to it } }.sortedWith(compareBy({ it.first }, { it.second.y }, { it.second.x }))

    /** Edit-mode actions leave no empty page anywhere (the first one included); one page always stays. */
    private fun compact(m: HomeModel): HomeModel {
        val keep = m.pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
        return if (keep.size == m.pages.size) m else m.copy(pages = keep)
    }

    /** Apps behind grid items (a folder's apps; widgets have none): they stay in the drawer and are never re-added. */
    private fun pkgsOf(items: List<HItem>) = items.flatMap { when (it.kind) { "app" -> listOf(it.pkg); SHORTCUT -> emptyList(); "folder" -> it.apps.filter { e -> !isShortcutEntry(e) }; else -> emptyList() } }.filter { it.isNotEmpty() }

    /** "Bỏ khỏi màn chính" on the picked items (apps, folders, widgets). */
    fun removeIds(m: HomeModel, ids: Set<String>): HomeModel {
        val gone = m.pages.flatten().filter { it.id in ids }
        if (gone.isEmpty()) return m
        return compact(m.copy(pages = m.pages.map { p -> p.filter { it.id !in ids } }, seen = m.seen + pkgsOf(gone)))
    }

    /** "Xoá trang": the whole page goes, its apps stay in the drawer. The only page left is emptied, never removed. */
    fun removePage(m: HomeModel, page: Int): HomeModel {
        if (page !in m.pages.indices) return m
        val pages = m.pages.filterIndexed { i, _ -> i != page }.ifEmpty { listOf(emptyList()) }
        return compact(m.copy(pages = pages, seen = m.seen + pkgsOf(m.pages[page])))
    }

    /**
     * "Gom nhóm": the picked apps and folders become one folder in the first one's cell (a folder keeps its name),
     * in reading order. Picked widgets stay where they are. Null: fewer than 2 apps or more than [FOLDER_MAX].
     */
    fun group(m: HomeModel, ids: Collection<String>, name: String): HomeModel? {
        val items = picked(m, ids).filter { it.second.kind == "app" || it.second.kind == "folder" || it.second.kind == SHORTCUT }
        val apps = items.flatMap { (_, i) -> if (i.kind == "folder") i.apps else listOf(entryOf(i)) }.filter { it.isNotEmpty() }.distinct()
        if (apps.size < 2 || apps.size > FOLDER_MAX) return null
        val (pi, first) = items.first()
        val nm = namesOf(items.map { it.second })
        val folder = if (first.kind == "folder") first.copy(apps = apps, names = nm) else HItem(idFor(m, "f"), "folder", first.x, first.y, name = name.take(40), apps = apps, names = nm)
        val merged = items.map { it.second.id }.toSet() - first.id
        val pages = m.pages.mapIndexed { i, p -> p.filter { it.id !in merged }.map { if (i == pi && it.id == first.id) folder else it } }
        return compact(m.copy(pages = pages))
    }

    /**
     * "Chuyển trang": the picked items to the first free cells of [page] ([page] == pages.size: a new page), in reading
     * order. What does not fit stays put. Returns the model and how many items moved.
     */
    fun moveTo(m: HomeModel, ids: Collection<String>, page: Int): Pair<HomeModel, Int> {
        if (page < 0 || page > m.pages.size) return m to 0
        val pages = m.pages.map { it.toMutableList() }.toMutableList()
        if (page == pages.size) pages.add(mutableListOf())
        var n = 0
        for ((pi, it) in picked(m, ids)) {
            if (pi == page) continue
            val (x, y) = firstFree(pages[page], m.cols, m.rows, it.w, it.h) ?: continue
            pages[pi].removeAll { o -> o.id == it.id }
            pages[page].add(it.copy(x = x, y = y)); n++
        }
        if (n == 0) return m to 0
        return compact(m.copy(pages = pages.map { it.toList() })) to n
    }

    fun hasWidget(m: HomeModel, type: String) = m.pages.flatten().any { it.kind == "widget" && it.type == type }

    /** A picker tap: the first free cells for a [w] x [h] widget, from [page] on, then the pages before, then a new page. */
    fun placeWidget(m: HomeModel, type: String, w: Int, h: Int, page: Int): HomeModel? {
        val t = take(m, Src.NewWidget(type, w, h)) ?: return null
        val order = (page.coerceIn(0, m.pages.size - 1) until m.pages.size) + (0 until page.coerceIn(0, m.pages.size - 1))
        for (pi in order) firstFree(m.pages[pi], m.cols, m.rows, t.item.w, t.item.h)?.let { (x, y) -> return dropCell(t, pi, x, y) }
        return dropCell(t, m.pages.size, 0, 0)
    }

    /**
     * Another app's widget dropped from the picker at ([x], [y]) of [page], once bound as [awid]: there if it still fits,
     * else the first free cells like a tap ([placeAppWidget]). Null = no room anywhere.
     */
    fun placeAppWidgetAt(m: HomeModel, pkg: String, cls: String, awid: Int, w: Int, h: Int, page: Int, x: Int, y: Int): HomeModel? {
        val t = take(m, Src.NewAppWidget(pkg, cls, w, h)) ?: return null
        return dropMove(t.copy(item = t.item.copy(awid = awid)), page, x, y) ?: placeAppWidget(m, pkg, cls, awid, w, h, page)
    }

    /** Widgets whose type [ok] refuses leave the layout (KidGuard Home has no parent widgets: they would be invisible but keep their cells). */
    fun dropWidgets(m: HomeModel, ok: (String) -> Boolean): HomeModel =
        if (m.pages.none { p -> p.any { it.kind == "widget" && !ok(it.type) } }) m
        else trimPages(m.copy(pages = m.pages.map { p -> p.filter { !(it.kind == "widget" && !ok(it.type)) } }))

    /** A layout taken from KidGuard Home: other apps' widgets are bound to that app's widget host, so they cannot come along. */
    fun imported(m: HomeModel): HomeModel =
        trimPages(m.copy(pages = m.pages.map { p -> p.filter { !(it.kind == "widget" && it.type == APP) && it.kind != SHORTCUT }.mapNotNull { i -> if (i.kind == "folder") folderWith(i, i.apps.filter { e -> !isShortcutEntry(e) }) else i } }))   // shortcuts are pinned to that app too

    /**
     * A pinned shortcut accepted from an app: the first free cell from the first page on, else a new page. The same
     * shortcut again only refreshes its label.
     */
    fun addShortcut(m: HomeModel, pkg: String, sid: String, label: String): HomeModel {
        val name = label.take(40)
        find(m, m.pages.flatten().firstOrNull { it.kind == SHORTCUT && it.pkg == pkg && it.cls == sid }?.id ?: "")?.let { (pi, it) ->
            return if (it.name == name) m else m.copy(pages = m.pages.mapIndexed { i, p -> if (i != pi) p else p.map { o -> if (o.id == it.id) o.copy(name = name) else o } })
        }
        val id = idFor(m, "s")
        for ((i, p) in m.pages.withIndex()) firstFree(p, m.cols, m.rows)?.let { (x, y) ->
            return m.copy(pages = m.pages.toMutableList().also { it[i] = p + HItem(id, SHORTCUT, x, y, pkg = pkg, cls = sid, name = name) })
        }
        return m.copy(pages = m.pages + listOf(listOf(HItem(id, SHORTCUT, 0, 0, pkg = pkg, cls = sid, name = name))))
    }

    /** Ids of the other apps' widgets (type app) in the model: the host deletes the ones that leave. */
    fun appWidgetIds(m: HomeModel): Set<Int> = m.pages.flatten().filter { it.kind == "widget" && it.type == APP && it.awid != 0 }.map { it.awid }.toSet()

    /** A picker tap for another app's widget: like [placeWidget], but the item carries the provider and the host id. */
    fun placeAppWidget(m: HomeModel, pkg: String, cls: String, awid: Int, w: Int, h: Int, page: Int): HomeModel? {
        val t = Taken(m, HItem(idFor(m, "w"), "widget", 0, 0, minOf(w, m.cols), minOf(h, m.rows), pkg = pkg, cls = cls, type = APP, awid = awid))
        val order = (page.coerceIn(0, m.pages.size - 1) until m.pages.size) + (0 until page.coerceIn(0, m.pages.size - 1))
        for (pi in order) firstFree(m.pages[pi], m.cols, m.rows, t.item.w, t.item.h)?.let { (x, y) -> return dropCell(t, pi, x, y) }
        return dropCell(t, m.pages.size, 0, 0)
    }

    /** Another size for the widget [id]: in place when the new cells are free, else the first place it fits. */
    /** A widget's own option (its long-press menu), e.g. the clock's alignment. */
    fun setOpt(m: HomeModel, id: String, opt: String): HomeModel? {
        val (pi, it) = find(m, id)?.takeIf { it.second.kind == "widget" } ?: return null
        if (it.opt == opt) return m
        return setPage(m, pi, m.pages[pi].map { o -> if (o.id == id) o.copy(opt = opt) else o })
    }

    fun resizeWidget(m: HomeModel, id: String, w: Int, h: Int): HomeModel? {
        val (pi, it) = find(m, id)?.takeIf { it.second.kind == "widget" } ?: return null
        val nw = minOf(w, m.cols); val nh = minOf(h, m.rows)
        if (nw == it.w && nh == it.h) return m
        val rest = m.pages[pi].filter { o -> o.id != id }
        val x = minOf(it.x, m.cols - nw); val y = minOf(it.y, m.rows - nh)
        val grown = it.copy(x = x, y = y, w = nw, h = nh)
        if (free(rest, m.cols, m.rows, x, y, nw, nh)) return setPage(m, pi, rest + grown)
        // grows in place (One UI / POCO): what it now covers moves down by the rows it grew, else to the first free cell of
        // the page, else onto a new page right after it. Moving the widget to another free page instead looked like it vanished.
        val dy = (nh - it.h).coerceAtLeast(1)
        val placed = arrayListOf(grown); val over = ArrayList<HItem>()
        for (o in rest.sortedWith(compareBy({ it.y }, { it.x }))) {
            val spot = listOf(o.x to o.y, o.x to o.y + dy).firstOrNull { (cx, cy) -> free(placed, m.cols, m.rows, cx, cy, o.w, o.h) }
                ?: firstFree(placed, m.cols, m.rows, o.w, o.h)
            if (spot != null) placed += o.copy(x = spot.first, y = spot.second) else over += o
        }
        val pages = m.pages.toMutableList(); pages[pi] = placed
        if (over.isNotEmpty()) {
            val np = ArrayList<HItem>()
            for (o in over) firstFree(np, m.cols, m.rows, o.w, o.h)?.let { (cx, cy) -> np += o.copy(x = cx, y = cy) }
            pages.add(pi + 1, np)
        }
        return m.copy(pages = pages)
    }

    /**
     * New grid size (protocol "Changing rows / columns"). Bigger: nothing moves. Smaller: widgets shrink to fit, every item
     * keeps its cell when it still fits, else takes the first free cell of its page, else moves on to the next page;
     * the order (page, row, col) is kept. Nothing is ever dropped.
     */
    /**
     * One automatic row change: the pages before it and right after it. A shrink pushes the misfits to the first free cells
     * (lossy); when the rows come back and nobody touched the layout in between, [rows] puts every item back where it was
     * (a bigger icon size took 7 -> 6 rows on the Poco and going back left the page shuffled).
     */
    data class GridStep(val cols: Int, val rows: Int, val before: List<List<HItem>>, val after: List<List<HItem>>)

    /** The rows the last automatic shrink came from, while the layout is still exactly what it left; else null. */
    fun undoRows(m: HomeModel, steps: List<GridStep>): Int? =
        steps.lastOrNull()?.takeIf { it.after == m.pages && it.cols == m.cols }?.rows

    /** [m] re-gridded to [r] rows with the undo stack [steps]; returns the model and the new stack. */
    fun rows(m: HomeModel, r: Int, steps: List<GridStep>): Pair<HomeModel, List<GridStep>> {
        // a stack only chains while the layout is exactly what its last step left
        var st = if (steps.lastOrNull()?.let { it.after == m.pages && it.cols == m.cols } == true) steps else emptyList()
        if (r < m.rows) {
            val n = regrid(m, m.cols, r)
            return n to (st + GridStep(m.cols, m.rows, m.pages, n.pages)).takeLast(4)
        }
        var cur = m
        while (true) {
            val top = st.lastOrNull() ?: break
            if (top.rows > r || top.after != cur.pages) break
            cur = cur.copy(rows = top.rows, pages = top.before); st = st.dropLast(1)
        }
        return (if (cur.rows == r) cur else regrid(cur, cur.cols, r)) to st
    }

    fun regrid(m: HomeModel, cols: Int, rows: Int): HomeModel {
        val c = cols.coerceIn(3, 5); val r = rows.coerceIn(4, MAX_ROWS)
        if (c >= m.cols && r >= m.rows) {
            // a widget that filled the old row fills the new one (the added columns are empty): a 4-wide card on a 5-wide
            // grid left an odd gap on the right
            val g = m.copy(cols = c, rows = r, pages = if (c == m.cols) m.pages else m.pages.map { p -> p.map { if (it.kind == "widget" && it.x == 0 && it.w == m.cols) it.copy(w = c) else it } })
            // Auto-filled pages (fresh() / appendNew: every page but the last is full) flow into the new rows; a hand-made
            // layout with gaps or short pages keeps its places. Without this, a fresh 5-row grid that grows to 6 left the
            // last row of every page empty.
            val auto = c == m.cols && r > m.rows && m.pages.size > 1 && m.pages.dropLast(1).all { full(it, m.cols, m.rows) }
            return if (!auto) g else trimPages(g.copy(pages = flow(g.pages.flatMap { p -> p.sortedWith(compareBy({ it.y }, { it.x })) }, c, r)))
        }
        val out = ArrayList<List<HItem>>()
        var carry = emptyList<HItem>()
        var pi = 0
        while (pi < m.pages.size || carry.isNotEmpty()) {
            val own = (m.pages.getOrNull(pi) ?: emptyList()).sortedWith(compareBy({ it.y }, { it.x }))
                .map { if (it.kind == "widget") it.copy(w = minOf(it.w, c), h = minOf(it.h, r)) else it }
            val placed = ArrayList<HItem>(); val next = ArrayList<HItem>()
            val misfit = ArrayList<HItem>()
            for (it in own) if (free(placed, c, r, it.x, it.y, it.w, it.h)) placed += it else misfit += it
            for (it in carry + misfit) {
                val f = firstFree(placed, c, r, it.w, it.h)
                if (f != null) placed += it.copy(x = f.first, y = f.second) else next += it
            }
            out += placed.toList()
            carry = next
            pi++
            if (pi > 40) break
        }
        return trimPages(m.copy(cols = c, rows = r, pages = out.ifEmpty { listOf(emptyList()) }))
    }

    /** Folder name from the two apps' ApplicationInfo.category (API 26+, -1 unknown): a shared kind, else "". */
    fun folderKind(a: Int, b: Int): String {
        fun k(c: Int) = when (c) { 0 -> "games"; 4 -> "social"; 7 -> "study"; 1, 2, 3 -> "fun"; else -> "" }
        return if (k(a) == k(b)) k(a) else ""
    }
}
