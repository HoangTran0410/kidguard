package dev.kidguard

import java.text.Normalizer
import java.util.Locale

/**
 * Basic mode (no Device Owner), pure part: no Android classes, unit-tested in BasicLogicTest.
 * [bounceReason]: should a window of [pkg] be sent HOME because the rules block it?
 * [selfProtect]: is this Settings-like window an attempt to uninstall / deactivate / stop KidGuard?
 */
object BasicLogic {
    /** Never bounced, whatever the rules say (our own screen, the phone's plumbing, calls). The caller adds Policy.protectedApps. */
    val NEVER = setOf("android", "com.android.systemui", "com.android.phone", "com.android.emergency", "com.android.server.telecom",
        "com.android.settings", "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui")

    /** The block reason for [pkg] when it must be bounced, else null. [blocked] is decide()'s result: package -> reason code. */
    fun bounceReason(pkg: String?, self: String, blocked: Map<String, String>, prot: Set<String>): String? {
        if (pkg == null || pkg == self || pkg in NEVER || pkg in prot) return null
        return blocked[pkg]
    }

    /** Child-facing text for decide()'s reason codes (unknown codes as they are). */
    fun reasonText(code: String, tr: Tr): String = when (code) {
        "blocked" -> tr.s(T.reason_blocked)
        "appLimit" -> tr.s(T.reason_app_limit)
        "groupHours" -> tr.s(T.reason_group_hours)
        "groupLimit" -> tr.s(T.reason_group_limit)
        "new" -> tr.s(T.reason_new)
        "paused" -> tr.s(T.reason_paused)
        "schedule" -> tr.s(T.reason_schedule)
        "bedtime" -> tr.s(T.reason_bedtime)
        "daily" -> tr.s(T.reason_daily)
        else -> code
    }

    /** Windows we watch for uninstall / deactivate attempts: AOSP Settings, package installers, OEM security centers. */
    private val SETTINGS_EXACT = setOf(
        "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.miui.securitycenter", "com.miui.packageinstaller", "com.huawei.systemmanager", "com.hihonor.systemmanager",
        "com.vivo.permissionmanager", "com.iqoo.secure", "com.vivo.abe", "com.android.vending.packageinstaller",
        "com.sec.android.app.safetymanager", "com.sec.android.app.sysscope",
    )
    private val SETTINGS_PREFIX = listOf("com.coloros.", "com.oppo.", "com.realme.", "com.heytap.", "com.oneplus.", "com.android.settings.",
        "com.samsung.android.settings", "com.samsung.android.app.settings", "com.samsung.android.sm", "com.samsung.android.lool")

    fun isSettingsPkg(pkg: String): Boolean =
        pkg in SETTINGS_EXACT || SETTINGS_PREFIX.any { pkg.startsWith(it) } ||
            (pkg.startsWith("com.samsung.android.") && pkg.contains("settings"))

    /** One text node of the window; [clickable] = the node or a close ancestor is a button / switch.
     * [checkable] = the node itself is a switch / checkbox: its "TẮT"/"OFF" text (Android 8 switches) is a state, not a button. */
    class Node(val text: String, val clickable: Boolean = false, val checkable: Boolean = false)

    /** Buttons whose text starts like this: uninstall, deactivate admin, force stop, turn off the accessibility service. */
    private val STRONG_PREFIX = listOf(
        "gỡ cài đặt", "gỡ ứng dụng", "huỷ kích hoạt", "hủy kích hoạt", "tắt kích hoạt", "buộc dừng", "dừng ngay", "xoá ứng dụng", "xóa ứng dụng",
        "uninstall", "deactivate", "force stop", "force-stop", "turn off kidguard", "disable kidguard", "stop kidguard", "dừng kidguard", "tắt kidguard",
        "dùng kidguard", "use kidguard", "sử dụng kidguard", "dùng dịch vụ", "use service", "sử dụng dịch vụ",
    )
    /** Whole-text matches that are common words: counted as controls only when the node is clickable. */
    private val WEAK_CLICK = setOf("gỡ", "tắt", "dừng", "stop", "turn off", "off", "disable", "xoá", "xóa", "remove", "delete")
    /** Our accessibility service description (res acc_desc, each language), shown on its detail page where the child can switch it off. */
    private val ACC_DESC = listOf("cho bố mẹ xem màn hình", "lets parents see the screen")

    private fun norm(s: String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * True when a window of [pkg] mentions KidGuard (one of [labels]) AND offers a destructive control.
     * An app list that merely shows "KidGuard" has no such control and passes. KidGuard's own windows are never passed in.
     */
    fun selfProtect(pkg: String, nodes: List<Node>, labels: Collection<String> = listOf("KidGuard")): Boolean {
        if (!isSettingsPkg(pkg)) return false
        val ls = labels.map { norm(it) }.filter { it.isNotEmpty() }
        var mentions = false; var control = false
        for (n in nodes) {
            val t = norm(n.text)
            if (t.isEmpty()) continue
            if (ls.any { t.contains(it) }) mentions = true
            if (STRONG_PREFIX.any { t.startsWith(it) } || ACC_DESC.any { t.contains(it) } || (n.clickable && !n.checkable && t in WEAK_CLICK)) control = true
            if (mentions && control) return true
        }
        return false
    }
}
