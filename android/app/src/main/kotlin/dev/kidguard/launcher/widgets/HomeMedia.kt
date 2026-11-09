package dev.kidguard

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Outline
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * "Đang phát" card: the media session that plays now (else the latest one), with its art, title, artist and
 * previous / play-pause / next. Used in the control center (hidden while nothing plays) and as the home widget "media".
 * Sessions come from MediaSessionManager.getActiveSessions, which needs notification access: KidGuard already has it
 * for the badges (NotifDots), so no new permission. Without it (a phone set up before 0.9.8, or KidGuard Home that never
 * asked) the card says so and a tap opens the system switch (NotifDots.ask); it picks the sessions up when the home
 * gets focus back. The control center shows that card only where the kid may open Settings.
 * Tapping the title opens the playing app through [launch], the same path as its icon (rules apply as usual).
 */
internal class MediaCard(private val act: HomeActivity, private val cc: Boolean, private val square: Boolean = false) : LinearLayout(act) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = act.getSystemService(MediaSessionManager::class.java)
    private val cn = ComponentName(act, NotifDots::class.java)
    private var ctl: MediaController? = null
    private val fg = if (cc) android.graphics.Color.WHITE else act.pal.text
    private val dim = if (cc) 0xCCFFFFFF.toInt() else act.pal.sub
    private val art = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, act.dpf(12f)) } }
        clipToOutline = true
    }
    private val title = act.text("", if (cc) 15f else 14f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val artist = act.text("", 12f, dim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val prev = btn(R.drawable.li_skip_back, R.string.md_prev) { ctl?.transportControls?.skipToPrevious() }
    private val play = btn(R.drawable.li_play, R.string.md_play) { toggle() }
    private val next = btn(R.drawable.li_skip_forward, R.string.md_next) { ctl?.transportControls?.skipToNext() }
    /** Something to show changed: the control center hides the card when nothing plays. */
    var onState: (has: Boolean) -> Unit = {}
    private var playIcon = 0
    private var shownKey: Any? = null

    private val cb = object : MediaController.Callback() {
        // re-pick, not just redraw: a session that drops to ERROR / STOPPED must give way (or empty the card)
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick(null)
        override fun onMetadataChanged(metadata: MediaMetadata?) = show()
        override fun onSessionDestroyed() { main.post { pick(null) } }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    init {
        val col = LinearLayout(act).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(title); col.addView(artist)
        // the whole card opens the playing app (the buttons keep their own taps); the home widget puts the ripple on its wCard
        if (cc) act.kit.tap(this, 26f, dark = true) { tapped() }
        val b = act.dp(40)
        if (square) {
            // 2x2 (music widgets on Pixel / HyperOS): art on top, the names, the three buttons along the bottom
            orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(art, LayoutParams(act.dp(52), act.dp(52)))
            addView(col, LayoutParams(-1, -2).apply { topMargin = act.dp(8) })
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            for (v in listOf(prev, play, next)) row.addView(v, LayoutParams(0, b, 1f))
            addView(row, LayoutParams(-1, -2).apply { topMargin = act.dp(4) })
        } else {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val a = act.dp(if (cc) 52 else 44)
            addView(art, LayoutParams(a, a))
            addView(col, LayoutParams(0, -2, 1f).apply { leftMargin = act.dp(12); rightMargin = act.dp(4) })
            addView(prev, LayoutParams(b, b)); addView(play, LayoutParams(b, b)); addView(next, LayoutParams(b, b))
        }
        show()
    }

    private fun btn(icon: Int, desc: Int, f: () -> Unit) = ImageView(act).apply {
        setImageDrawable(Ic.get(act, icon, fg, act.dp(22))); scaleType = ImageView.ScaleType.CENTER
        contentDescription = act.getString(desc); act.kit.tap(this, -1f) { runCatching(f) }
    }

    private fun toggle() {
        val c = ctl ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun tapped() { if (listening) open() else { act.closeOverlay(); NotifDots.ask(act) } }

    private fun open() {
        val pkg = ctl?.packageName ?: return
        val a = act.launcherApps()[pkg] ?: return
        act.closeOverlay(); act.launch(a, this)
    }

    private var listening = false
    private fun listen() {
        if (listening) return
        if (!NotifDots.granted(act)) { show(); return }
        listening = runCatching { msm.addOnActiveSessionsChangedListener(sessions, cn, main) }.isSuccess
        if (listening) pick(runCatching { msm.getActiveSessions(cn) }.getOrNull()) else show()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); listen() }

    // back from the system switch: access may be on now (asked fresh, the 5 s cache would still say no)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !listening && NotifDots.granted(act, fresh = true)) { listen(); NotifDots.setOn(act, true) }
        else if (hasFocus && listening) pick(null)   // back on home: catch a change the callbacks missed
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (listening) runCatching { msm.removeOnActiveSessionsChangedListener(sessions) }; listening = false
        ctl?.unregisterCallback(cb); ctl = null
    }

    /**
     * Playing first, then paused (a song stopped a minute ago is still the one to resume). Only live states count: an app
     * closed from Recents can leave its session active in ERROR with the old title and no actions (YouTube / ReVanced on
     * the owner's Poco: "Required premium account to perform action in background"), which looked stuck and dead.
     */
    private fun pick(list: List<MediaController>?) {
        val live = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)
        val l = (list ?: runCatching { msm.getActiveSessions(cn) }.getOrNull().orEmpty())
            .filter { it.metadata != null && it.playbackState?.state in live }
        val best = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
        if (best?.sessionToken != ctl?.sessionToken) {
            ctl?.unregisterCallback(cb); ctl = best
            runCatching { best?.registerCallback(cb, main) }
        }
        show()
    }

    private fun show() {
        val c = ctl; val md = c?.metadata
        onState(md != null || !listening)
        if (c == null || md == null) {
            shownKey = null
            title.text = act.getString(if (listening) R.string.md_none else R.string.md_noaccess)
            artist.text = act.getString(if (listening) R.string.md_none_sub else R.string.md_noaccess_sub)
            art.setImageDrawable(Ic.get(act, R.drawable.li_music, fg, act.dp(22))); art.scaleType = ImageView.ScaleType.CENTER
            art.background = act.kit.fill(if (cc) 0x33FFFFFF else act.pal.tint, 12f)
            // no access: the whole row is the "allow" button, so no dead controls taking its width
            for (v in listOf(prev, play, next)) { v.alpha = 0.35f; v.isEnabled = false; v.visibility = if (listening) VISIBLE else GONE }
            contentDescription = title.text
            return
        }
        val t = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val label = act.launcherApps()[c.packageName]?.label ?: ""
        val by = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty()
        if (title.text.toString() != t.ifEmpty { label }) title.text = t.ifEmpty { label }
        val sub = listOf(by, label).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        if (artist.text.toString() != sub) artist.text = sub
        // the bitmap only when the song changed: decoding / scaling art on every playback tick is wasted work
        val key = c.sessionToken to t
        if (key != shownKey) {
            shownKey = key
            val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null) { art.background = null; art.scaleType = ImageView.ScaleType.CENTER_CROP; art.setImageBitmap(bmp) }
            else {
                art.scaleType = ImageView.ScaleType.FIT_CENTER; art.background = null
                art.setImageDrawable(runCatching { act.packageManager.getApplicationIcon(c.packageName) }.getOrNull())
            }
        }
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val ic = if (playing) R.drawable.li_pause else R.drawable.li_play
        if (ic != playIcon) { playIcon = ic; play.setImageDrawable(Ic.get(act, ic, fg, act.dp(24))); play.contentDescription = act.getString(if (playing) R.string.md_pause else R.string.md_play) }
        val acts = c.playbackState?.actions ?: 0L
        prev.alpha = if (acts and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) 1f else 0.35f
        next.alpha = if (acts and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) 1f else 0.35f
        play.alpha = 1f // the empty state dimmed all three; play/pause always works on a live session
        for (v in listOf(prev, play, next)) { v.isEnabled = true; v.visibility = VISIBLE }
        contentDescription = "${title.text}. ${artist.text}"
    }
}

/** Home widget "media": the same card in the widget style (pal colours, wCard). Full row x 1, or 2x2 (stacked). */
internal fun HomeActivity.mediaWidget(w: Int, h: Int): View {
    val card = wCard(false)
    val mc = MediaCard(this, false, square = h >= 2 && w <= 3)
    card.addView(mc, LinearLayout.LayoutParams(-1, -2))
    kit.tap(card, 22f) { mc.tapped() }
    return card
}

internal fun HomeActivity.mediaPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER_VERTICAL; c.orientation = LinearLayout.HORIZONTAL
    c.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@mediaPreview, R.drawable.li_music, pal.accentText, dp(16))); background = kit.fill(pal.tint, 8f); scaleType = ImageView.ScaleType.CENTER },
        LinearLayout.LayoutParams(dp(26), dp(26)))
    c.addView(text(getString(R.string.md_preview), 10f, pal.text, true).apply { maxLines = 1; setPadding(dp(6), 0, dp(4), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
    c.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@mediaPreview, R.drawable.li_play, pal.text, dp(14))) }, LinearLayout.LayoutParams(dp(16), dp(16)))
}

