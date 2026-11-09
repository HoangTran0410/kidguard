package dev.kidguard

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.kidguard.ControlLogic.Mode
import dev.kidguard.ControlLogic.Tile

// The kid's control center: swipe down on the right half of the home (where the system quick settings opened before).
// Wi-Fi, Bluetooth, airplane, mobile data, torch, auto-rotate, Do Not Disturb, brightness and media volume, in the
// launcher's own window: what a tile may do is ControlLogic.mode (Device Owner / setup permissions / parent's rules).

/** Reads and changes the phone settings behind the tiles. Every call is guarded: an OEM refusal is a false, not a crash. */
internal object CcSys {
    fun wifi(ctx: Context) = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()
    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean) = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java)!!.setWifiEnabled(on) }.getOrDefault(false)

    private fun bt(ctx: Context) = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
    fun hasBt(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) && bt(ctx) != null
    fun btOn(ctx: Context) = runCatching { bt(ctx)?.isEnabled }.getOrNull()
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setBt(ctx: Context, on: Boolean): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31 && Policy.isOwner(ctx)) runCatching {   // a Device Owner grants itself the runtime permission
            Policy.dpm(ctx).setPermissionGrantState(Policy.admin(ctx), ctx.packageName, android.Manifest.permission.BLUETOOTH_CONNECT,
                android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        val a = bt(ctx)!!
        if (on) a.enable() else a.disable()
    }.getOrDefault(false)

    fun airplane(ctx: Context) = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1 }.getOrNull()
    fun hasPhone(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    fun data(ctx: Context) = runCatching { Settings.Global.getInt(ctx.contentResolver, "mobile_data") == 1 }.getOrNull()

    fun torchId(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)!!
        cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
    fun setTorch(ctx: Context, id: String, on: Boolean) = runCatching { ctx.getSystemService(CameraManager::class.java)!!.setTorchMode(id, on); true }.getOrDefault(false)

    fun rotate(ctx: Context) = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrNull()
    /** Plain Settings.System write (WRITE_SECURE_SETTINGS from setup or the WRITE_SETTINGS app-op); the Device Owner API
     *  (9+) only for the keys it accepts (brightness, its mode). True when the value reads back. */
    fun writeSys(ctx: Context, key: String, v: Int): Boolean {
        val cr = ctx.contentResolver
        if (Sys.wss(ctx) || Settings.System.canWrite(ctx)) runCatching { Settings.System.putInt(cr, key, v) }
        else if (Build.VERSION.SDK_INT >= 28 && Policy.isOwner(ctx) && key != Settings.System.ACCELEROMETER_ROTATION)
            runCatching { Policy.dpm(ctx).setSystemSetting(Policy.admin(ctx), key, v.toString()) }
        return runCatching { Settings.System.getInt(cr, key) }.getOrNull() == v
    }
    fun canRotate(ctx: Context) = Sys.wss(ctx) || Settings.System.canWrite(ctx)

    fun dnd(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        ?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL }
    fun setDnd(ctx: Context, on: Boolean) = runCatching {
        ctx.getSystemService(NotificationManager::class.java)!!.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL); true
    }.getOrDefault(false)

    fun bright(ctx: Context) = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
    fun brightMax(ctx: Context) = maxOf(Sys.brightMax(), bright(ctx) ?: 0)

    fun phone(ctx: Context, settingsOk: Boolean, rules: Rules?) = ControlLogic.Phone(
        sdk = Build.VERSION.SDK_INT,
        owner = !Flavor.HOME && Policy.isOwner(ctx),
        settingsOk = settingsOk,
        noAirplane = rules?.noAirplane == true,
        writeSys = Sys.canWriteSystem(ctx),
        dnd = Sys.dndAccess(ctx),
        flash = torchId(ctx) != null,
        hasBt = hasBt(ctx),
        secure = Sys.wss(ctx),
        night = sysBool("config_nightDisplayAvailable") == true,
        darkLocked = sysBool("config_lockDayNightMode") != false,
    )

    private fun sysBool(name: String) = runCatching { android.content.res.Resources.getSystem().let { r -> r.getIdentifier(name, "bool", "android").takeIf { it != 0 }?.let { r.getBoolean(it) } } }.getOrNull()
    fun autoBright(ctx: Context) = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrNull()
    private const val NIGHT = "night_display_activated"
    /** Night light (AOSP; OEM "reading mode" / "eye comfort" is usually built on it). Watched live by ColorDisplayService. */
    fun night(ctx: Context) = runCatching { Settings.Secure.getInt(ctx.contentResolver, NIGHT, 0) == 1 }.getOrNull()
    fun setNight(ctx: Context, on: Boolean) = Sys.wss(ctx) && runCatching { Settings.Secure.putInt(ctx.contentResolver, NIGHT, if (on) 1 else 0) }.getOrDefault(false) && night(ctx) == on
    fun dark(ctx: Context) = runCatching { ctx.getSystemService(android.app.UiModeManager::class.java)!!.nightMode == android.app.UiModeManager.MODE_NIGHT_YES }.getOrNull()
    fun setDark(ctx: Context, on: Boolean) = runCatching {
        val um = ctx.getSystemService(android.app.UiModeManager::class.java)!!
        um.nightMode = if (on) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO
        dark(ctx) == on
    }.getOrDefault(false)
    /** Ringer: normal / vibrate / silent. Silent (and leaving it) is refused without Do Not Disturb access on 7+. */
    fun setRinger(ctx: Context, mode: Int) = runCatching { ctx.getSystemService(AudioManager::class.java)!!.let { it.ringerMode = mode; it.ringerMode == mode } }.getOrDefault(false)

    /** The system page that changes [t] (a bottom-sheet panel on Android 10+ where one exists). */
    fun page(ctx: Context, t: Tile): Intent = when (t) {
        Tile.WIFI -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
        Tile.BT -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        Tile.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        Tile.DATA -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)
        Tile.DND, Tile.RINGER -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        Tile.NIGHT -> Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
        Tile.DARK -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        else -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** One round toggle with its label under it (the 2×2 connectivity card and the row of tiles). */
internal class CcToggle(val act: HomeActivity, val tile: Tile, private val icon: Int, label: String, size: Int, lines: Int = 1) : LinearLayout(act) {
    private val btn = ImageView(act)
    private val lock = ImageView(act)
    private val sz = size
    var mode = Mode.DIRECT
    var on: Boolean? = null
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val box = FrameLayout(act)
        box.addView(btn, FrameLayout.LayoutParams(act.dp(sz), act.dp(sz)))
        lock.setImageDrawable(Ic.get(act, R.drawable.li_lock, Color.WHITE, act.dp(11)))
        lock.background = act.kit.circle(0xCC0F172A.toInt()); lock.setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4))
        box.addView(lock, FrameLayout.LayoutParams(act.dp(19), act.dp(19), Gravity.TOP or Gravity.END))
        addView(box, LayoutParams(act.dp(sz), act.dp(sz)))
        addView(act.kit.text(label, 12f, Color.WHITE).apply {
            maxLines = lines; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setShadowLayer(act.dpf(3f), 0f, 0f, 0x66000000)
        }, LayoutParams(-1, -2).apply { topMargin = act.dp(5) })
        contentDescription = label
        act.kit.tap(btn, -1f, true)
        btn.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = true
        draw()
    }
    fun onTap(f: () -> Unit) { btn.setOnClickListener { f() }; setOnClickListener { f() } }
    fun set(state: Boolean?, m: Mode) { on = state; mode = m; draw() }
    private fun draw() {
        val lit = on == true
        val p = act.pal
        btn.setImageDrawable(Ic.get(act, icon, if (lit) p.onSolid else Color.WHITE, act.dp(sz * 11 / 24)))
        btn.background = act.kit.circle(if (lit) p.solid else 0x40FFFFFF)
        val pad = act.dp(sz * 13 / 48); btn.setPadding(pad, pad, pad, pad)
        lock.visibility = if (mode == Mode.LOCKED) VISIBLE else GONE
        alpha = if (mode == Mode.LOCKED) 0.7f else 1f
        if (Build.VERSION.SDK_INT >= 30) stateDescription = act.getString(if (lit) R.string.cc_on else R.string.cc_off)
    }
}

/** iOS vertical slider: the fill rises from the bottom; drag anywhere on it (relative, so a tap does not jump it). */
internal class CcSlider(val act: HomeActivity, private val icon: Int, label: String) : View(act) {
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var mode = Mode.DIRECT
    var onChange: (Float, Boolean) -> Unit = { _, _ -> }
    var onBlocked: () -> Unit = {}
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val clip = Path(); private val r = RectF()
    private val light = Ic.get(act, icon, Color.WHITE, act.dp(24))
    private val darkIc = Ic.get(act, icon, 0xFF0F172A.toInt(), act.dp(24))
    private var y0 = 0f; private var v0 = 0f; private var moved = false
    /** Long press (finger still) = more settings, like the system control centers. Null: no long press. */
    var onLong: (() -> Unit)? = null
    private var longDone = false
    private val slop = android.view.ViewConfiguration.get(act).scaledTouchSlop
    private val holdMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    // hold cue: shrinks over the long-press wait (iOS control center), springs back when it fires, the finger drags or lifts
    private fun squeeze(on: Boolean) {
        if (onLong == null) return
        animate().cancel()
        if (on) animate().scaleX(0.95f).scaleY(0.95f).setDuration(holdMs).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        else if (scaleX != 1f) animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
    }
    private val longRun = Runnable { if (!moved) { squeeze(false); longDone = true; isPressed = false; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLong?.invoke() } }
    init { contentDescription = label; isFocusable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        r.set(0f, 0f, w.toFloat(), h.toFloat()); clip.reset(); val rad = minOf(w / 2.4f, act.dpf(26f)); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
    }
    override fun onDraw(c: Canvas) {
        c.save(); c.clipPath(clip)
        c.drawRect(r, bg)
        val top = height * (1 - value)
        c.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
        c.restore()
        val s = act.dp(24); val l = (width - s) / 2; val t = height - act.dp(18) - s
        val d = if (top <= t + s / 2) darkIc else light
        d.setBounds(l, t, l + s, t + s); d.alpha = if (mode == Mode.LOCKED) 140 else 255; d.draw(c)
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // a locked slider still opens its detail on a long press (the toggles there have their own modes)
        if (mode != Mode.DIRECT) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { longDone = false; moved = false; if (onLong != null) { postDelayed(longRun, holdMs); squeeze(true) } }
                MotionEvent.ACTION_UP -> { removeCallbacks(longRun); squeeze(false); if (!longDone) onBlocked() }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(longRun); squeeze(false) }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                y0 = e.y; v0 = value; moved = false; longDone = false; isPressed = true; parent?.requestDisallowInterceptTouchEvent(true)
                if (onLong != null) { postDelayed(longRun, holdMs); squeeze(true) }
            }
            MotionEvent.ACTION_MOVE -> if (!longDone && (moved || kotlin.math.abs(y0 - e.y) > slop)) {
                if (!moved) { moved = true; removeCallbacks(longRun); squeeze(false); y0 = e.y }   // no jump by the slop distance
                value = v0 + (y0 - e.y) / height; onChange(value, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { removeCallbacks(longRun); squeeze(false); isPressed = false; if (moved) onChange(value, true) }
        }
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, value * 100)
        if (mode == Mode.DIRECT) { info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD) }
        if (onLong != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, act.getString(R.string.cc_more_of)))
    }
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && onLong != null) { onLong?.invoke(); return true }
        val d = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 0.1f; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -0.1f; else -> return super.performAccessibilityAction(action, args) }
        value += d; onChange(value, true); return true
    }
}

internal fun HomeActivity.openControl() {
    if (overlay != null) return
    val ctx = applicationContext
    val rules = if (Flavor.HOME) null else Store.of(this).rules
    val ok = rules?.let { settingsOk(it) } ?: true
    val ph = CcSys.phone(ctx, ok, rules)
    val torchId = if (ph.flash) CcSys.torchId(ctx) else null

    val scrim = FrameLayout(this).apply { setBackgroundColor(0xEB0B1220.toInt()); isClickable = true; contentDescription = getString(R.string.cc_title) }
    val w = minOf(root.width - insL - insR - dp(40), dp(400))
    val gap = dp(14); val half = (w - gap) / 2
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true }

    fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    val toggles = mutableListOf<CcToggle>()
    lateinit var refresh: () -> Unit
    fun later() { main.postDelayed({ if (overlay === scrim) refresh() }, 500); main.postDelayed({ if (overlay === scrim) refresh() }, 1600) }
    fun page(t: Tile) { runCatching { startActivity(CcSys.page(ctx, t)) }.onFailure { toast(R.string.cc_failed) }; closeOverlay() }
    fun act(t: Tile, mode: Mode, direct: () -> Boolean) = when (mode) {
        Mode.DIRECT -> { if (!direct()) { if (ok) page(t) else toast(R.string.cc_failed) } else later(); refresh() }
        Mode.PAGE -> page(t)
        Mode.LOCKED -> toast(R.string.cc_locked)
        Mode.HIDDEN -> {}
    }
    fun toggle(t: Tile, icon: Int, label: Int, size: Int, lines: Int = 1, direct: (Boolean) -> Boolean): CcToggle? {
        val m = ControlLogic.mode(t, ph)
        if (m == Mode.HIDDEN) return null
        return CcToggle(this, t, icon, getString(label), size, lines).also { tg -> tg.mode = m; toggles += tg; tg.onTap { act(t, tg.mode) { direct(tg.on != true) } } }
    }

    // row 1: connectivity card (2×2) | brightness + volume sliders
    val row1 = LinearLayout(this)
    val conn = android.widget.GridLayout(this).apply {
        columnCount = 2; background = GlassCard(0x33FFFFFF, dpf(30f), dpf(1f), true)
        setPadding(dp(6), dp(12), dp(6), dp(8)); minimumHeight = half
    }
    val cell = (half - dp(12)) / 2
    val bsz = minOf(56, (cell / resources.displayMetrics.density).toInt() - 22).coerceAtLeast(40)
    listOfNotNull(
        toggle(Tile.WIFI, R.drawable.li_wifi, R.string.cc_wifi, bsz) { CcSys.setWifi(ctx, it) },
        toggle(Tile.BT, R.drawable.li_bluetooth, R.string.cc_bt, bsz) { CcSys.setBt(ctx, it) },
        toggle(Tile.AIRPLANE, R.drawable.li_plane, R.string.cc_air, bsz) { false },
        if (CcSys.hasPhone(ctx)) toggle(Tile.DATA, R.drawable.li_signal, R.string.cc_data, bsz) { false } else null,
    ).forEach { conn.addView(it, android.widget.GridLayout.LayoutParams().apply { width = cell; height = -2; setGravity(Gravity.CENTER); bottomMargin = dp(8) }) }
    row1.addView(conn, LinearLayout.LayoutParams(half, -2))
    val bMax = CcSys.brightMax(ctx)
    val am = getSystemService(AudioManager::class.java)
    val vMax = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
    fun mkBright() = CcSlider(this, R.drawable.li_sun, getString(R.string.cc_bright)).apply {
        mode = ControlLogic.mode(Tile.BRIGHT, ph)
        var last = 0L
        onBlocked = { if (mode == Mode.PAGE) page(Tile.BRIGHT) else toast(R.string.cc_locked) }
        onChange = { f, done ->
            val now = android.os.SystemClock.uptimeMillis()
            if (done || now - last > 40) {
                last = now
                Thread {   // a binder write per step; off the UI thread so the drag stays smooth
                    if (done) CcSys.writeSys(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    CcSys.writeSys(ctx, Settings.System.SCREEN_BRIGHTNESS, ControlLogic.brightValue(f, bMax))
                }.start()
                // the change shows at once in our own window, whatever the system takes to apply it
                window.attributes = window.attributes.apply { screenBrightness = (ControlLogic.brightValue(f, bMax).toFloat() / bMax).coerceIn(0.01f, 1f) }
            }
        }
    }
    fun mkVol(stream: Int, icon: Int, label: Int) = CcSlider(this, icon, getString(label)).apply {
        val max = am?.getStreamMaxVolume(stream)?.coerceAtLeast(1) ?: 1
        mode = if (am == null || am.isVolumeFixed) Mode.LOCKED else Mode.DIRECT
        onBlocked = { toast(R.string.cc_failed) }
        // ring at 0 switches the phone to vibrate / silent: refused without Do Not Disturb access, so guarded
        onChange = { f, _ -> runCatching { am?.setStreamVolume(stream, Math.round(f * max), 0) } }
    }
    val bright = mkBright()
    val vol = mkVol(AudioManager.STREAM_MUSIC, R.drawable.li_volume_2, R.string.cc_volume)
    val sliders = LinearLayout(this)
    sliders.addView(bright, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = gap / 2 })
    sliders.addView(vol, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = gap / 2 })
    row1.addView(sliders, LinearLayout.LayoutParams(half, -1).apply { marginStart = gap })
    panel.addView(row1, LinearLayout.LayoutParams(w, -2))

    // what plays now (HomeMedia.kt), only while something does, like the system control centers
    // no notification access: the card asks for it, but only where the kid may open Settings (KidGuard Home, or allowed)
    if (NotifDots.granted(this) || ok) {
        val media = MediaCard(this, true).apply {
            background = GlassCard(0x33FFFFFF, dpf(26f), dpf(1f), true); setPadding(dp(12), dp(12), dp(8), dp(12)); visibility = View.GONE
            onState = { has -> visibility = if (has) View.VISIBLE else View.GONE }
        }
        panel.addView(media, LinearLayout.LayoutParams(w, -2).apply { topMargin = gap })
    }

    // row 2: torch, auto-rotate, Do Not Disturb, notifications
    val tsz = minOf(64, ((w - gap * 3) / 4 / resources.displayMetrics.density).toInt() - 8).coerceAtLeast(44)
    val row2 = LinearLayout(this).apply { gravity = Gravity.TOP }
    val notif = CcToggle(this, Tile.VOLUME, R.drawable.li_bell, getString(R.string.cc_notifs), tsz, 3).apply { onTap { closeOverlay(); main.postDelayed({ expandShade(false) }, 120) } }
    listOfNotNull(
        toggle(Tile.TORCH, R.drawable.li_flashlight, R.string.cc_torch, tsz, 3) { on -> torchId != null && CcSys.setTorch(ctx, torchId, on) },
        toggle(Tile.ROTATE, R.drawable.li_rotate_ccw, R.string.cc_rotate, tsz, 3) { on -> CcSys.canRotate(ctx) && CcSys.writeSys(ctx, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0) },
        toggle(Tile.DND, R.drawable.li_moon, R.string.cc_dnd, tsz, 3) { on -> CcSys.setDnd(ctx, on) },
        notif,
    ).forEach { row2.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    panel.addView(row2, LinearLayout.LayoutParams(w, -2).apply { topMargin = gap + dp(4) })

    // the phone's own quick settings, for what the kid's panel does not have (hotspot, cast, …)
    val more = kit.text(getString(R.string.cc_more), 14f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; background = kit.fill(0x33FFFFFF, 16f); setPadding(dp(16), dp(12), dp(16), dp(12)); minHeight = dp(48)
        Ic.lead(this, R.drawable.li_settings_2, Color.WHITE); compoundDrawablePadding = dp(8)
        kit.tap(this, 16f, true) { closeOverlay(); main.postDelayed({ expandShade(true) }, 120) }
    }
    // rule launcher.shade: the system quick settings close as soon as they open, so no way there
    if (!shadeOff()) panel.addView(more, LinearLayout.LayoutParams(w, -2).apply { topMargin = gap + dp(6) })

    // ---- long press on a slider: its detail (HyperOS / iOS pattern), in place of the main panel ----
    val detail = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true; visibility = View.GONE
        background = GlassCard(0x33FFFFFF, dpf(30f), dpf(1f), true); setPadding(dp(16), dp(10), dp(16), dp(18))
    }
    var back: (() -> Unit)? = null
    // read off the UI thread with the rest of refresh; each returns the UI update to run
    val extras = mutableListOf<() -> (() -> Unit)>()
    fun showDetail(title: Int, body: View, own: List<CcToggle>) {
        detail.removeAllViews()
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageDrawable(Ic.get(this@openControl, R.drawable.li_arrow_left, Color.WHITE, dp(22))); scaleType = ImageView.ScaleType.CENTER
            contentDescription = getString(R.string.hm_c_back); kit.tap(this, -1f) { back?.invoke() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(kit.text(getString(title), 17f, Color.WHITE, true).apply { setPadding(dp(4), 0, 0, 0) })
        detail.addView(head)
        detail.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.visibility = View.GONE; detail.visibility = View.VISIBLE
        detail.alpha = 0f; detail.scaleX = 0.96f; detail.scaleY = 0.96f
        detail.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator(2f)).start()
        back = {
            back = null; extras.clear(); toggles.removeAll(own)
            detail.visibility = View.GONE; detail.removeAllViews(); panel.visibility = View.VISIBLE
            panel.alpha = 0f; panel.animate().alpha(1f).setDuration(150).start()
            refresh()
        }
        announce(getString(title))
        refresh()
    }
    fun label(v: View, t: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(v, LinearLayout.LayoutParams(dp(76), dp(230)))
        addView(kit.text(getString(t), 13f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(8), 0, 0) })
    }
    bright.onLong = {
        val body = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val bs = mkBright()
        extras += { val b = CcSys.bright(ctx); { if (b != null && !bs.isPressed) bs.value = ControlLogic.brightPos(b, bMax) } }
        body.addView(label(bs, R.string.cc_bright), LinearLayout.LayoutParams(0, -2, 1f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val own = listOfNotNull(
            toggle(Tile.AUTO_BRIGHT, R.drawable.li_sun_medium, R.string.cc_auto_bright, 52, 2) { on -> CcSys.writeSys(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0) },
            toggle(Tile.NIGHT, R.drawable.li_eye, R.string.cc_night, 52, 2) { on -> CcSys.setNight(ctx, on) },
            toggle(Tile.DARK, R.drawable.li_sun_moon, R.string.cc_dark, 52, 2) { on -> CcSys.setDark(ctx, on) },
        )
        own.forEach { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        body.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        showDetail(R.string.cc_bright, body, own)
    }
    vol.onLong = {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bars = LinearLayout(this).apply { gravity = Gravity.TOP }
        for ((st, ic, lb) in listOf(Triple(AudioManager.STREAM_MUSIC, R.drawable.li_music, R.string.cc_media),
                Triple(AudioManager.STREAM_RING, R.drawable.li_bell_ring, R.string.cc_ring), Triple(AudioManager.STREAM_ALARM, R.drawable.li_alarm_clock, R.string.cc_alarm))) {
            val s = mkVol(st, ic, lb)
            val max = am?.getStreamMaxVolume(st)?.coerceAtLeast(1) ?: 1
            extras += { val v = runCatching { am?.getStreamVolume(st) }.getOrNull(); { if (v != null && !s.isPressed) s.value = v.toFloat() / max } }
            bars.addView(label(s, lb), LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(bars)
        // sound / vibrate / silent: one of three, like the system's ringer chooser
        val rm = ControlLogic.mode(Tile.RINGER, ph)
        val modes = listOf(AudioManager.RINGER_MODE_NORMAL to (R.drawable.li_bell to R.string.cc_sound),
            AudioManager.RINGER_MODE_VIBRATE to (R.drawable.li_vibrate to R.string.cc_vibrate), AudioManager.RINGER_MODE_SILENT to (R.drawable.li_bell_off to R.string.cc_silent))
        val row = LinearLayout(this).apply { gravity = Gravity.TOP }
        val rt = modes.map { (m, il) ->
            CcToggle(this, Tile.RINGER, il.first, getString(il.second), 52, 2).also { tg ->
                tg.mode = rm; tg.onTap { act(Tile.RINGER, tg.mode) { CcSys.setRinger(ctx, m) } }
                row.addView(tg, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        extras += { val cur = runCatching { am?.ringerMode }.getOrNull(); { rt.forEachIndexed { i, tg -> tg.set(cur?.let { it == modes[i].first }, rm) } } }
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        showDetail(R.string.cc_volume, body, emptyList())
    }
    // torch has no getter: its callback reports the state (at once when registered)
    var torchOn: Boolean? = null
    val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == torchId) { torchOn = enabled; main.post { if (overlay === scrim) refresh() } } }
        override fun onTorchModeUnavailable(id: String) { if (id == torchId) { torchOn = null; main.post { if (overlay === scrim) refresh() } } }
    }
    if (torchId != null) runCatching { getSystemService(CameraManager::class.java)?.registerTorchCallback(torchCb, main) }

    refresh = {
        Thread {
            val st = mapOf(Tile.WIFI to CcSys.wifi(ctx), Tile.BT to CcSys.btOn(ctx), Tile.AIRPLANE to CcSys.airplane(ctx), Tile.DATA to CcSys.data(ctx),
                Tile.ROTATE to CcSys.rotate(ctx), Tile.DND to CcSys.dnd(ctx),
                Tile.AUTO_BRIGHT to CcSys.autoBright(ctx), Tile.NIGHT to CcSys.night(ctx), Tile.DARK to CcSys.dark(ctx))
            val more = extras.toList().map { it() }
            val b = CcSys.bright(ctx); val v = runCatching { am?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            main.post {
                if (overlay !== scrim) return@post
                for (t in toggles) t.set(if (t.tile == Tile.TORCH) torchOn else st[t.tile], t.mode)
                if (b != null && !bright.isPressed) bright.value = ControlLogic.brightPos(b, bMax)
                if (v != null) vol.value = v.toFloat() / vMax
                for (f in more) f()
            }
        }.start()
    }

    scrim.addView(panel, FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = insT + dp(36) })
    scrim.addView(detail, FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = insT + dp(36) })
    // tap outside or swipe up anywhere closes it
    var sy = 0f
    scrim.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> sy = e.y
            // outside a detail: back to the main panel first (one tap), a swipe up still closes all
            MotionEvent.ACTION_UP -> if (sy - e.y > dp(60)) closeOverlay() else if (kotlin.math.abs(sy - e.y) < dp(12)) back?.invoke() ?: closeOverlay()
        }
        true
    }
    showOverlay(scrim)
    overlayExit = { done ->
        if (torchId != null) runCatching { getSystemService(CameraManager::class.java)?.unregisterTorchCallback(torchCb) }
        window.attributes = window.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        panel.animate().alpha(0f).translationY(-dpf(24f)).setDuration(150).start()
        scrim.animate().alpha(0f).setDuration(170).withEndAction(done).start()
    }
    scrim.alpha = 0f; panel.alpha = 0f; panel.translationY = -dpf(32f); panel.scaleX = 0.96f; panel.scaleY = 0.96f
    scrim.animate().alpha(1f).setDuration(160).start()
    panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
    refresh()
    announce(getString(R.string.cc_title))
}

/** Rule launcher.shade is on and the accessibility service closes the system quick settings. */
internal fun HomeActivity.shadeOff() = !Flavor.HOME && LauncherSafe.shadeBlocked && Acc.running != null
