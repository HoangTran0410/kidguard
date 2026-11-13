package dev.kidguard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar

/**
 * An app we suspended (DevicePolicyManager.setPackagesSuspended) opened from outside our launcher shows Android's stock
 * "App paused / blocked by your admin" dialog, which a DPC cannot give a text or a time (SuspendDialogInfo is
 * PackageManager API behind SUSPEND_APPS, system only). So: [update] keeps the reasons Guard just enforced, [onEvent]
 * (from Acc.onAccessibilityEvent) spots that dialog and shows our own line: app, reason, when it opens again.
 */
object SuspendHint {
    @Volatile private var reasons: Map<String, String> = emptyMap()
    private var lastShown = 0L

    /** Guard tick: [want] = package -> reason code for the apps suspended now. */
    fun update(want: Map<String, String>) { reasons = want }

    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || reasons.isEmpty()) return
        // stock dialog: package "android", SuspendedAppActivity (AOSP / Pixel; OEM class names unverified)
        if (e.packageName?.toString() != "android" || e.className?.toString()?.contains("SuspendedApp") != true) return
        val now = System.currentTimeMillis()
        if (now - lastShown < 3000) return
        val texts = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) { if (n == null || d > 8) return; n.text?.let { texts += it.toString() }; for (i in 0 until n.childCount) walk(n.getChild(i), d + 1) }
        runCatching { walk(svc.rootInActiveWindow, 0) }
        val pm = svc.packageManager
        val hit = reasons.entries.firstOrNull { (p, _) ->
            val label = runCatching { pm.getApplicationInfo(p, 0).loadLabel(pm).toString() }.getOrNull() ?: return@firstOrNull false
            texts.any { it.contains(label, true) }
        } ?: return
        lastShown = now
        val store = Store.of(svc); val r = store.rules
        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val label = runCatching { pm.getApplicationInfo(hit.key, 0).loadLabel(pm).toString() }.getOrDefault(hit.key)
        val whenTxt = when (val o = LauncherLogic.reopen(r, hit.value, hit.key, minute, Rules.isoDow(Guard.day(now)), now)) {
            is LauncherLogic.Reopen.AtMinute -> svc.getString(if (o.minute <= minute) R.string.hm_tomorrow_at else R.string.hm_today_at, HomeLogic.clock(o.minute))
            is LauncherLogic.Reopen.AtMs -> svc.getString(R.string.hm_today_at, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(o.ms))
            LauncherLogic.Reopen.Tomorrow -> svc.getString(R.string.hm_tomorrow)
            else -> null
        }
        val line = svc.getString(R.string.sus_hint, label, BasicLogic.reasonText(hit.value, store.tr)) + (whenTxt?.let { " " + svc.getString(R.string.hm_reopen, it) } ?: "")
        Toasts.show(svc, line, true)
    }
}
