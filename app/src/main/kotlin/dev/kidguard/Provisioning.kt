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

    /** Builds the box; call [update] from onResume. Hidden when both permissions are on. */
    fun build(act: Activity): LinearLayout {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; tag = "checklist" }
        box.addView(TextView(act).apply { text = "Bước còn lại"; textSize = 18f; setTextColor(Color.parseColor("#111827")); setPadding(0, 16, 0, 8) })
        for (r in rows) box.addView(Button(act).apply { text = r.label; setOnClickListener { runCatching { r.open(act) } } })
        box.addView(TextView(act).apply { tag = "secure"; textSize = 14f; setTextColor(Color.parseColor("#6b7280")) })
        return box
    }

    fun update(act: Activity, box: LinearLayout) {
        val buttons = (0 until box.childCount).map { box.getChildAt(it) }.filterIsInstance<Button>()
        rows.forEachIndexed { i, r -> buttons[i].text = (if (r.done(act)) "Đã xong: " else "") + r.label }
        val needed = !rows.all { r -> r.done(act) }
        box.visibility = if (needed) View.VISIBLE else View.GONE
        box.findViewWithTag<TextView>("secure").text =
            if (hasSecure(act)) "Tự bật lại Trợ năng: có" else "Tuỳ chọn: tự bật lại Trợ năng cần máy tính hoặc Gỡ lỗi không dây"
    }
}
