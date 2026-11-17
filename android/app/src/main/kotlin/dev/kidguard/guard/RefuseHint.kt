package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * An app refuses to run and says why (owner 2026-11-17: "user thấy app bank báo lỗi, không biết KidGuard có chỗ cấu hình
 * => tìm cách gỡ, bị kẹt"): a bank / wallet / carrier app not on any pause list shows a dialog or a toast like "turn off
 * accessibility / USB debugging / VPN". Our accessibility service still reads that text (FLAG_SECURE only stops
 * screenshots), so: [onEvent] spots it in the first seconds after the app came to the front, the phone shows a toast with
 * the fix and logs it, and state `refused` carries it to the page, which offers "add to the list" in one tap.
 * Never pauses anything by itself: an app that only *shows* such words must not switch our protection off (a kid could
 * make a note app say it), the parent decides.
 */
object RefuseLogic {
    /** Words compared without accents / case ([norm]). */
    private val ACC = listOf("tro nang", "accessibility", "ho tro tiep can", "dieu khien tu xa", "remote control", "remote access", "kidguard", "doc man hinh", "screen reader")
    private val DEV = listOf("go loi", "debugging", "debug mode", "che do nha phat trien", "tuy chon nha phat trien", "tuy chon cho nha phat trien", "developer option", "developer mode", "adb")
    private val VPN = listOf("vpn")
    /** The text must also say something is wrong (a screen that merely mentions "VPN" is not a refusal). */
    private val BAD = listOf("tat ", "hay tat", "vui long tat", "go bo", "go cai dat", "vo hieu", "phat hien", "khong the", "khong duoc", "khong cho phep",
        "khong an toan", "rui ro", "nguy hiem", "bao mat", "an toan", "turn off", "disable", "uninstall", "remove", "detected", "unable", "cannot",
        "can't", "not allowed", "not supported", "unsafe", "risk", "security", "secure", "dangerous", "harmful")
    /** Longer than this = a normal screen (an article, a chat), not an error dialog / toast. */
    const val MAX_TEXT = 600
    /** Only in the first seconds after the app came up: refusals come at start. */
    const val WINDOW_MS = 12_000L
    /** Same app + same reason again: not before this. */
    const val AGAIN_MS = 6 * 3_600_000L
    const val KEEP = 6
    const val KEEP_MS = 7 * 86_400_000L

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd').replace(Regex("\\s+"), " ")

    /** What the text says the app refuses: subset of acc / dev / vpn, empty = not a refusal. */
    fun kinds(text: String): Set<String> {
        if (text.isBlank() || text.length > MAX_TEXT) return emptySet()
        val t = " " + norm(text) + " "
        if (BAD.none { t.contains(it) }) return emptySet()
        return buildSet {
            if (ACC.any { t.contains(it) }) add("acc")
            if (DEV.any { w -> if (w == "adb") Regex("\\badb\\b").containsMatchIn(t) else t.contains(w) }) add("dev")
            if (VPN.any { Regex("\\b$it\\b").containsMatchIn(t) }) add("vpn")
        }
    }

    /** The kinds of [hit] the rules do not cover yet (acc -> accPause, dev -> devPause or tcpPause, vpn -> vpnPause). */
    fun missing(hit: Set<String>, pkg: String, r: Rules): Set<String> = hit.filterTo(LinkedHashSet()) {
        when (it) { "acc" -> pkg !in r.accPause; "dev" -> pkg !in r.devPause && pkg !in r.tcpPause; else -> pkg !in r.vpnPause }
    }

    /** Stored list after a new hit: one entry per app (kinds merged), newest first, at most [KEEP], none older than [KEEP_MS]. */
    fun add(list: JSONArray, pkg: String, label: String, kinds: Set<String>, now: Long): JSONArray {
        val out = JSONArray()
        val prev = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("pkg") == pkg }
        val all = LinkedHashSet<String>().apply { prev?.optJSONArray("kinds")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) }; addAll(kinds) }
        out.put(JSONObject().put("pkg", pkg).put("label", label).put("kinds", JSONArray(all.toList())).put("at", now))
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("pkg") == pkg || now - o.optLong("at") > KEEP_MS || out.length() >= KEEP) continue
            out.put(o)
        }
        return out
    }

    /** For the state: drop the kinds the rules cover now (the parent added the app) and entries left with none. */
    fun live(list: JSONArray, r: Rules, now: Long): JSONArray {
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (now - o.optLong("at") > KEEP_MS) continue
            val k = o.optJSONArray("kinds") ?: continue
            val left = missing((0 until k.length()).map { k.optString(it) }.toSet(), o.optString("pkg"), r)
            if (left.isNotEmpty()) out.put(JSONObject(o.toString()).put("kinds", JSONArray(left.toList())))
        }
        return out
    }
}

object RefuseHint {
    private var front = ""
    private var frontAt = 0L
    private var walks = 0
    private val shown = HashMap<String, Long>()   // "pkg|kind" -> when the toast was shown
    private val SYS = setOf("android", "com.android.systemui")

    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        if (Flavor.HOME) return
        val pkg = e.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        when (e.eventType) {
            // a toast (AppSealing-style "debugging detected" just before the app kills itself): only its own text
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (e.className?.toString()?.contains("Toast") != true) return
                check(svc, pkg, e.text.joinToString(" "), now)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in SYS || pkg == svc.packageName) return
                if (pkg != front) { front = pkg; frontAt = now; walks = 0 }
                if (now - frontAt > RefuseLogic.WINDOW_MS || walks >= 4) return
                walks++
                val texts = ArrayList<String>(); var n = 0
                fun walk(x: AccessibilityNodeInfo?, d: Int) {
                    if (x == null || d > 12 || n > 150) return
                    n++
                    x.text?.let { texts += it.toString() }; x.contentDescription?.let { if (x.text == null) texts += it.toString() }
                    for (i in 0 until x.childCount) walk(x.getChild(i), d + 1)
                }
                runCatching { walk(svc.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }, 0) }
                if (texts.isEmpty()) texts += e.text.map { it.toString() }
                check(svc, pkg, texts.joinToString(" "), now)
            }
        }
    }

    private fun check(svc: AccessibilityService, pkg: String, text: String, now: Long) {
        if (pkg == svc.packageName || pkg in SYS || BasicLogic.isSettingsPkg(pkg)) return
        var hit = RefuseLogic.kinds(text)
        if (hit.isEmpty()) return
        val s = Store.of(svc)
        hit = RefuseLogic.missing(hit, pkg, s.rules)
        if ("vpn" in hit && isVpnApp(svc, pkg)) hit = hit - "vpn"   // a VPN app talking about VPNs
        if (hit.isEmpty()) return
        if (runCatching { svc.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() == null) return   // services / system parts
        if (hit.all { now - (shown["$pkg|$it"] ?: 0L) < RefuseLogic.AGAIN_MS }) return
        hit.forEach { shown["$pkg|$it"] = now }
        val pm = svc.packageManager
        val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
        s.addRefused(pkg, label, hit, now)
        val what = hit.joinToString(", ") { svc.getString(when (it) { "acc" -> R.string.rf_acc; "dev" -> R.string.rf_dev; else -> R.string.rf_vpn }) }
        runCatching { s.log(R.string.log_refused, label, what) }
        runCatching { s.event("refused", R.string.ev_refused, label, what) }   // pushed to the parent app (relay pushNew: every event)
        notify(svc, label, what)
        Guard.running?.kick()
    }

    /** A notification, not a toast: the text is long and the bank app may close itself right away. */
    private fun notify(ctx: android.content.Context, label: String, what: String) = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CH, ctx.getString(R.string.ch_refuse), android.app.NotificationManager.IMPORTANCE_HIGH))
        val body = ctx.getString(R.string.rf_body, label, what)
        nm.notify(NID, android.app.Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.getString(R.string.rf_title, label, what)).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body)).setAutoCancel(true).build())
    }
    private const val CH = "refuse"
    private const val NID = 7307

    private fun isVpnApp(svc: AccessibilityService, pkg: String): Boolean = runCatching {
        svc.packageManager.queryIntentServices(Intent("android.net.VpnService").setPackage(pkg), 0).isNotEmpty()
    }.getOrDefault(false)
}
