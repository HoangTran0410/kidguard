package dev.kidguard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/**
 * Family chat, child side: the thread with the parents (Store.chat, last 100), bubbles, one input line.
 * Reached from the "Nhắn bố mẹ" tile and the "Reply" button of a parent's message. A sent message is stored, becomes the
 * event "chat" (parent app notification) and kicks the sync. Re-reads the thread every 3 s while open (a parent may answer).
 * Not in the Home flavor (manifest removes it).
 */
class ChatActivity : Activity() {
    private lateinit var u: Ui
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val h = Handler(Looper.getMainLooper())
    private var shown = ""   // last drawn thread (as text): redraw only on change
    private val poll = object : Runnable { override fun run() { draw(false); h.postDelayed(this, 3_000) } }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        u = Ui(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(u.bg); fitsSystemWindows = true }
        root.addView(u.label(getString(R.string.chat_title), 20f, u.text, bold = true).apply { setPadding(u.dp(20), u.dp(16), u.dp(20), u.dp(8)) })
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u.dp(14), u.dp(4), u.dp(14), u.dp(8)) }
        scroll = ScrollView(this).apply { addView(col) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(10)) }
        input = EditText(this).apply {
            hint = getString(R.string.chat_hint); setTextColor(u.text); setHintTextColor(u.sub); textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4; background = u.shape(u.card, 22, u.stroke, 1); setPadding(u.dp(16), u.dp(10), u.dp(16), u.dp(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(ChatLogic.MAX_TEXT))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(u.button(getString(R.string.chat_send), R.drawable.li_send, true) { send() }, LinearLayout.LayoutParams(-2, u.dp(48)).apply { leftMargin = u.dp(8) })
        root.addView(bar)
        setContentView(root)
        window.statusBarColor = u.bg; window.navigationBarColor = u.bg
        @Suppress("DEPRECATION") if (!u.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    override fun onResume() {
        super.onResume()
        runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(3) }   // the parent's message notice (MessageActivity.NID)
        draw(true); h.postDelayed(poll, 3_000)
    }
    override fun onPause() { h.removeCallbacksAndMessages(null); super.onPause() }

    private fun send() {
        val s = Store.of(this)
        if (!s.everPaired) { Toasts.show(this, getString(R.string.chat_unpaired), true); return }
        val t = ChatLogic.clean(input.text.toString())
        if (t.isEmpty() || !s.addChat("k", "", t)) return
        input.setText("")
        Guard.running?.kick()
        draw(true)
    }

    private fun draw(toEnd: Boolean) {
        val st = Store.of(this)
        st.chatMarkRead()   // the launcher's unread dot (control tile / "From mum and dad" widget) goes off while the thread is on screen
        val a = st.chat()
        val sig = a.toString()
        if (sig == shown) return
        shown = sig
        col.removeAllViews()
        if (a.length() == 0) col.addView(u.label(getString(R.string.chat_empty), 15f).apply { gravity = Gravity.CENTER; setPadding(u.dp(20), u.dp(60), u.dp(20), 0) })
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            val kid = m.optString("from") == "k"
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(u.dp(14), u.dp(8), u.dp(14), u.dp(8))
                background = if (kid) u.shape(u.primary, 18) else u.shape(u.card, 18, u.stroke, 1)
            }
            val who = if (kid) "" else m.optString("who").ifEmpty { getString(R.string.chat_parent) }
            if (who.isNotEmpty()) bubble.addView(u.label(who, 12f, u.accent, bold = true))
            bubble.addView(TextView(this).apply { text = m.optString("text"); textSize = 16f; setTextColor(if (kid) u.onPrimary else u.text); setTextIsSelectable(false) })
            bubble.addView(u.label(android.text.format.DateFormat.getTimeFormat(this@ChatActivity).format(Date(m.optLong("t"))), 11f, if (kid) u.onPrimary else u.sub).apply { gravity = Gravity.END })
            col.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = if (kid) Gravity.END else Gravity.START; topMargin = u.dp(6)
                if (kid) leftMargin = u.dp(48) else rightMargin = u.dp(48)
            })
        }
        if (toEnd) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
