package dev.kidguard

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

/** Basic mode (no Device Owner): mode detection and the child-screen checklist. Pure rules are in [BasicLogic]. docs/protocol.md "v0.7". */
object Basic {
    fun adminActive(ctx: Context): Boolean = runCatching { Policy.dpm(ctx).isAdminActive(Policy.admin(ctx)) }.getOrDefault(false)

    /** Blocking by bouncing is in force: not Device Owner, admin active, not released. (Accessibility is checked by the caller.) */
    fun enforcing(ctx: Context): Boolean = !Policy.isOwner(ctx) && adminActive(ctx) && !Store.of(ctx).released

    /** "owner" | "basic" | "setup" (docs/protocol.md). */
    fun mode(ctx: Context): String = when {
        Policy.isOwner(ctx) -> "owner"
        adminActive(ctx) && Acc.isOn(ctx) -> "basic"
        else -> "setup"
    }

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun locationOk(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    /** Device location switch. LocationManager.isLocationEnabled is API 28+; older phones use the secure setting. */
    fun locationOn(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 28) ctx.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
        else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(false)
    fun notifyOk(ctx: Context) = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    fun batteryOk(ctx: Context) = runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)

    /** What the checklist still misses: each key true = OK, as in state.basic (docs/protocol.md). */
    fun status(ctx: Context): JSONObject = JSONObject()
        .put("admin", adminActive(ctx)).put("acc", Acc.isOn(ctx)).put("usage", Usage.granted(ctx))
        .put("location", locationOk(ctx)).put("notify", notifyOk(ctx)).put("battery", batteryOk(ctx))
}

/** Child screen card "Cài nhanh không cần máy tính": one big button per missing item, rebuilt on every resume. */
class BasicChecklist(private val act: Activity, private val ui: Ui, private val autostart: () -> Unit) {
    val box: LinearLayout = ui.cardBox().apply {
        addView(ui.label("Cài nhanh không cần máy tính", 17f, ui.text, true))
        addView(ui.label("Làm lần lượt từng bước bên dưới. Mỗi nút mở đúng màn hình cần bật; bật xong bấm Quay lại.", 14f))
    }
    private val items = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    init { box.addView(items) }

    private fun open(vararg i: Intent) { for (x in i) if (runCatching { act.startActivity(x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return }

    /** True when something is still missing (the card stays visible). */
    private var lastKey = ""
    private var lastMissing = false

    fun update(): Boolean {
        val c = act
        // called every 3 s: rebuild only when a step changed, or a tap on a button being redrawn gets lost
        val key = listOf(Basic.adminActive(c), Acc.isOn(c), Usage.granted(c), Basic.locationOk(c),
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            Basic.notifyOk(c), Basic.batteryOk(c)).joinToString()
        if (key == lastKey) return lastMissing
        lastKey = key
        items.removeAllViews()
        fun add(t: String, why: String, go: () -> Unit) {
            items.addView(ui.button(t, null, true) { runCatching { go() } })
            items.addView(ui.label(why, 13f).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0) })
        }
        var missing = false
        if (!Basic.adminActive(c)) { missing = true
            add("1. Bật quyền quản trị thiết bị", "Để KidGuard khoá máy được và không bị gỡ khi chưa có bố mẹ.") {
                c.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(c))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "KidGuard cần quyền này để khoá màn hình và chống bị gỡ."))
            }
        }
        if (!Acc.isOn(c)) { missing = true
            add("2. Bật Trợ năng cho KidGuard", "Vào Trợ năng, chọn KidGuard, bật. Dùng để chặn app bị khoá và giữ KidGuard không bị tắt.") {
                c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!Usage.granted(c)) { missing = true
            add("3. Cho phép xem thời gian dùng app", "Chọn KidGuard rồi bật \"Cho phép\". Dùng để đếm giờ dùng từng app.") {
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        if (!Basic.locationOk(c)) { missing = true
            val fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine) add("4. Cho phép vị trí", "Để bố mẹ biết con đang ở đâu. Chọn \"Chính xác\" và \"Khi dùng ứng dụng\" ở bước này.") {
                c.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            } else add("4. Vị trí: cho phép \"Luôn cho phép\"", "Để bố mẹ xem vị trí cả khi con không mở KidGuard. Chọn \"Luôn cho phép\" (Always) ở màn hình tiếp theo.") {
                if (Build.VERSION.SDK_INT >= 30) {
                    // Android 11+ sends the user to the app's location page itself
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
                    c.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
                } else c.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
            }
        }
        if (!Basic.notifyOk(c)) { missing = true
            add("5. Cho phép thông báo", "Để con thấy lời nhắc và lý do khi app bị khoá.") {
                c.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
            }
        }
        if (!Basic.batteryOk(c)) { missing = true
            add("6. Không giới hạn pin cho KidGuard", "Nếu không, máy có thể tự tắt KidGuard khi tiết kiệm pin.") {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            items.addView(ui.button("Cho phép tự khởi động (Oppo, Xiaomi, Vivo…)", null, false) { runCatching { autostart() } })
        }
        if (missing) items.addView(ui.label("Bị Google Play Protect chặn (\"App blocked to protect your device\")? Mở CH Play → ảnh đại diện → Play Protect → ⚙ → tắt \"Quét ứng dụng bằng Play Protect\", làm xong các bước ở đây rồi bật lại.", 13f).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) })
        box.visibility = if (missing) View.VISIBLE else View.GONE
        lastMissing = missing
        return missing
    }
}
