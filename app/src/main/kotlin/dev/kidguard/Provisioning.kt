package dev.kidguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * QR / zero-touch provisioning (Android 10+). The setup wizard asks which mode we support: always "fully managed device".
 * Needs android:permission=BIND_DEVICE_ADMIN in the manifest so only the system can call it.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK, Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        finish()
    }
}

/** The wizard asks whether the device complies with our policy: nothing to check, say yes. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}

/** "Bước còn lại" checklist on the main screen: what a Device Owner (no adb) cannot grant by itself. */
object Checklist {
    private class Row(val label: String, val done: (Context) -> Boolean, val open: (Activity) -> Unit)

    private val rows = listOf(
        Row("Bật quyền theo dõi sử dụng", { Usage.granted(it) }, {
            runCatching { it.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${it.packageName}"))) }
                .onFailure { _ -> it.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }),
        Row("Bật Trợ năng KidGuard", { Acc.isOn(it) }, { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
    )

    private fun hasSecure(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Builds the card; call [update] from onResume. Hidden when both permissions are on. */
    fun build(act: Activity, ui: Ui): LinearLayout {
        val box = ui.cardBox().apply { tag = "checklist" }
        box.addView(ui.label("Bước còn lại", 17f, ui.text, true))
        box.addView(ui.label("Bật 2 quyền này để KidGuard chạy đầy đủ", 14f))
        for (r in rows) box.addView(ui.button(r.label, null, false) { runCatching { r.open(act) } })
        box.addView(ui.label("", 13f).apply { tag = "secure"; setPadding(0, ui.dp(12), 0, 0) })
        return box
    }

    fun update(act: Activity, box: LinearLayout) {
        val buttons = (0 until box.childCount).map { box.getChildAt(it) }.filterIsInstance<Button>()
        rows.forEachIndexed { i, r -> buttons[i].text = (if (r.done(act)) "Đã xong: " else "") + r.label; buttons[i].alpha = if (r.done(act)) 0.6f else 1f }
        val needed = !rows.all { r -> r.done(act) }
        box.visibility = if (needed) View.VISIBLE else View.GONE
        box.findViewWithTag<TextView>("secure").text =
            if (hasSecure(act)) "Tự bật lại Trợ năng: có" else "Tuỳ chọn: tự bật lại Trợ năng cần máy tính hoặc Gỡ lỗi không dây"
    }
}
