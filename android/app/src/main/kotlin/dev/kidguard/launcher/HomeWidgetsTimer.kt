package dev.kidguard

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

// "Hẹn giờ" widget: visual timer (coloured wedge that shrinks) + pomodoro. State machine: TimerLogic.kt. The end time is
// persisted (device-protected prefs) and an AlarmManager alarm posts the "time's up" notification even if the launcher is gone.
// The widget only ticks (1 s) while it is on screen. Design system: HomeWidgets.kt (wCard, wHead...).

internal object TimerStore {
    private const val CH = "timer"
    private const val REQ = 7301
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("kg_widgets2", Context.MODE_PRIVATE)

    fun load(c: Context): TimerLogic.S = runCatching { TimerLogic.fromJson(JSONObject(prefs(c).getString("timer", "") ?: "")) }.getOrDefault(TimerLogic.S())
    fun save(c: Context, s: TimerLogic.S) { prefs(c).edit().putString("timer", s.toJson().toString()).apply() }

    private fun pi(c: Context, endAt: Long, flags: Int) =
        PendingIntent.getBroadcast(c, REQ, Intent(c, TimerReceiver::class.java).putExtra("endAt", endAt), flags or PendingIntent.FLAG_IMMUTABLE)

    /** Alarm for a running state (elapsed-realtime based: a changed wall clock does not move it), none otherwise. */
    fun schedule(c: Context, s: TimerLogic.S, now: Long = System.currentTimeMillis()) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(c, 0, PendingIntent.FLAG_UPDATE_CURRENT))
        if (!s.running) return
        val at = SystemClock.elapsedRealtime() + (s.endAt - now).coerceIn(0, s.total)
        val p = pi(c, s.endAt, PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, p)
        }
    }

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, c.getString(R.string.ch_timer), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
        })
    }

    fun notifyDone(c: Context, before: TimerLogic.S) {
        runCatching {
            channel(c)
            val text = when { !before.pomo -> c.getString(R.string.tm_done); before.onBreak -> c.getString(R.string.tm_break_over); else -> c.getString(R.string.tm_work_over) }
            val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let { PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE) }
            c.getSystemService(NotificationManager::class.java).notify(CH.hashCode(), Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(c.getString(R.string.hm_w_timer)).setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).setContentIntent(open).build())
        }
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = TimerStore.load(c)
        // only the alarm of the state that is still running counts: a reset / pause / new start changed endAt
        if (!s.running || s.endAt != i.getLongExtra("endAt", -1)) return
        TimerStore.save(c, TimerLogic.finish(s))
        TimerStore.notifyDone(c, s)
    }
}

/** The disc: grey track, coloured wedge from 12 o'clock for the time left (full disc = all of it, empty = done). */
internal class TimerDisc(c: Context, private val track: Int, private val fill: Int) : View(c) {
    var frac = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(cv: Canvas) {
        val d = minOf(width, height).toFloat() - paddingLeft - paddingRight
        r.set((width - d) / 2, (height - d) / 2, (width + d) / 2, (height + d) / 2)
        p.style = Paint.Style.FILL; p.color = track; cv.drawOval(r, p)
        p.color = fill
        if (frac >= 0.999f) cv.drawOval(r, p) else if (frac > 0f) cv.drawArc(r, -90f, -360f * frac, true, p)
    }
}

private class TimerBox(val act: HomeActivity, val wide: Boolean) {
    val ctx: Context = act
    val card = act.wCard(!wide)
    private val disc = TimerDisc(act, (act.pal.sub and 0x00ffffff) or 0x38000000, act.pal.solid)
    private val time = act.text("", if (wide) 22f else 20f, act.pal.text, true)
    private val phase = act.wSub("")
    private val reset = act.kit.chip("", act.pal.onTint, act.pal.tint, R.drawable.li_rotate_ccw)
    private val chips = ArrayList<Pair<TextView, Int>>()       // minutes; 0 = pomodoro; -1 = custom
    private var tick: Runnable? = null
    private var s = TimerStore.load(ctx)

    init {
        val d = act.dp(if (wide) 84 else 62)
        disc.contentDescription = act.getString(R.string.hm_w_timer)
        act.kit.tap(disc, -1f) { toggle() }
        val head = act.wHead(R.drawable.li_timer, act.getString(R.string.hm_w_timer))
        reset.text = act.getString(R.string.tm_reset); act.kit.tap(reset, 999f, true) { set(TimerLogic.reset(s)) }
        if (wide) {
            val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            left.addView(disc, LinearLayout.LayoutParams(d, d)); left.addView(time, LinearLayout.LayoutParams(-2, -2).apply { topMargin = act.dp(4) })
            val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            right.addView(head)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in TimerLogic.PRESETS + listOf(-1)) row.addView(chip(m), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = act.dp(4) })
            right.addView(row)
            val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(6), 0, 0) }
            row2.addView(chip(0), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = act.dp(6) })
            row2.addView(reset)
            right.addView(row2); right.addView(phase, LinearLayout.LayoutParams(-2, -2).apply { topMargin = act.dp(6) })
            card.addView(left, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = act.dp(14) })
            card.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            card.gravity = Gravity.CENTER_HORIZONTAL
            card.addView(head)
            card.addView(disc, LinearLayout.LayoutParams(d, d))
            card.addView(time, LinearLayout.LayoutParams(-2, -2).apply { topMargin = act.dp(3) })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, act.dp(4), 0, 0) }
            row.addView(chip(-2).apply { textSize = 11f }); row.addView(reset)
            card.addView(row)
        }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { loop() }
            override fun onViewDetachedFromWindow(v: View) { stop() }
        })
        bind()
    }

    /** -2 = the single 2x2 chip: shows the choice, a tap steps through 5 / 10 / 15 / 25 / pomodoro / custom. */
    private fun chip(m: Int): TextView {
        val t = act.kit.chip("", act.pal.onTint, act.pal.tint).apply { textSize = if (wide) 11f else 12f; setPadding(act.dp(8), act.dp(5), act.dp(8), act.dp(5)) }
        act.kit.tap(t, 999f, true) { pick(m) }
        chips += t to m
        return t
    }

    private fun pick(m: Int) {
        val cur = if (s.pomo) 0 else (s.total / 60_000).toInt()
        val steps = TimerLogic.PRESETS + listOf(0, -1)
        when (val v = if (m == -2) steps[(steps.indexOf(cur) + 1) % steps.size] else m) {
            0 -> set(TimerLogic.pomodoro())
            -1 -> custom()
            else -> set(TimerLogic.choose(v))
        }
    }

    private fun custom() {
        val md = Modal(act, act.u)
        val f = md.field(act.getString(R.string.tm_custom_hint), 3).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        md.show(R.drawable.li_timer, Modal.Tone.INFO, act.getString(R.string.tm_custom), content = f, ok = act.getString(R.string.hm_t_add), cancel = act.getString(R.string.cancel)) {
            val n = f.text.toString().trim().toIntOrNull()
            if (n == null || n < 1 || n > TimerLogic.MAX_MIN) return@show f.text.isBlank()
            set(TimerLogic.choose(n)); true
        }
        f.requestFocus()
    }

    private fun toggle() {
        val now = System.currentTimeMillis()
        val cur = TimerStore.load(ctx)
        val st = cur.running && cur.endAt <= now
        set(when { st -> TimerLogic.start(TimerLogic.finish(cur), now); cur.running -> TimerLogic.pause(cur, now); else -> TimerLogic.start(cur, now) })
        if (Build.VERSION.SDK_INT >= 33 && TimerStore.load(ctx).running && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4710)
    }

    private fun set(n: TimerLogic.S) {
        TimerStore.save(ctx, n); TimerStore.schedule(ctx, n); TimerStore.channel(ctx)
        s = n; bind(); loop()
    }

    private fun bind() {
        val now = System.currentTimeMillis()
        s = TimerStore.load(ctx)
        // a running state long past its end (alarm lost: reboot, force-stop): settle it quietly; within 20 s the alarm still notifies
        if (s.running && now - s.endAt > 20_000) { s = TimerLogic.finish(s); TimerStore.save(ctx, s) }
        val left = TimerLogic.remaining(s, now)
        val over = s.running && left <= 0
        disc.frac = if (over) 0f else TimerLogic.fraction(s, now)
        disc.invalidate()
        time.text = if (over || s.done) act.getString(R.string.tm_done_short) else TimerLogic.mmss(left)
        time.setTextColor(if (over || s.done) act.pal.warnText else act.pal.text)
        phase.text = when {
            s.pomo -> act.getString(if (s.onBreak) R.string.tm_break else R.string.tm_work) + " · " + act.getString(R.string.tm_cycle, s.cycles + if (s.onBreak) 0 else 1)
            else -> if (s.running) act.getString(R.string.tm_running) else if (s.idle) act.getString(R.string.tm_tap_start) else act.getString(R.string.tm_paused)
        }
        phase.visibility = if (wide) View.VISIBLE else View.GONE
        reset.visibility = if (s.idle) View.GONE else View.VISIBLE
        val cur = if (s.pomo) 0 else (s.total / 60_000).toInt()
        val customCur = !s.pomo && cur !in TimerLogic.PRESETS
        for ((t, m) in chips) {
            val sel = when (m) { -2 -> false; -1 -> customCur; else -> m == cur }
            t.text = when (m) {
                -2 -> if (s.pomo) act.getString(R.string.tm_pomo) else "$cur ${act.getString(R.string.tm_min)}"
                -1 -> if (customCur && wide) "$cur" else "+"
                0 -> act.getString(R.string.tm_pomo)
                else -> "$m"
            }
            t.background = act.fill(if (sel) act.pal.solid else act.pal.tint, 999f)
            t.setTextColor(if (sel) act.pal.onSolid else act.pal.onTint)
            t.contentDescription = if (m > 0) "$m ${act.getString(R.string.tm_min)}" else null
        }
        card.contentDescription = act.getString(R.string.hm_w_timer) + " " + time.text
    }

    /** One tick per second while the card is on screen and a timer is running; nothing otherwise. */
    private fun loop() {
        stop()
        if (!card.isAttachedToWindow || !s.running) return
        val r = object : Runnable { override fun run() {
            if (!card.isAttachedToWindow) return
            if (card.isShown && act.lifecycleStarted) bind()
            if (s.running) card.postDelayed(this, 1000) else tick = null
        } }
        tick = r; card.postDelayed(r, 1000)
    }
    private fun stop() { tick?.let { card.removeCallbacks(it) }; tick = null }
}

/** [w] = widget width in cells: 4 = wide layout (disc left, presets right), else the compact 2x2. */
internal fun HomeActivity.timerWidget(w: Int, h: Int): View = centred(TimerBox(this, w >= 4).card)

internal fun HomeActivity.timerPreview(c: LinearLayout) {
    c.addView(TimerDisc(this, (pal.sub and 0x00ffffff) or 0x38000000, pal.solid).apply { frac = 0.62f }, LinearLayout.LayoutParams(dp(34), dp(34)))
    c.addView(text("15:00", 11f, pal.text, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
}
