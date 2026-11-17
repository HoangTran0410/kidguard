package dev.kidguard

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/**
 * Family chat, child side: the thread with the parents (Store.chat, last 100), bubbles, one input line.
 * Reached from the "Nhắn bố mẹ" tile and the "Reply" button of a parent's message. A sent message is stored, becomes the
 * event "chat" (parent app notification) and kicks the sync. Re-reads the thread every 3 s while open (a parent may answer).
 * Attachments (2026-11-17): "+" menu = photo / video from the system picker, take a photo, record a video, any file; hold the mic button
 * to record a voice note (release = send, slide left = cancel). Each one goes into the archive (Files.addOutChat) and the thread.
 * Bubbles show an image / video thumbnail, an audio player row or a file row; a tap opens it. "Ảnh & file" = gallery of everything kept
 * (also the files of the old shared folder). Not in the Home flavor (manifest removes it).
 */
class ChatActivity : Activity() {
    private lateinit var u: Ui
    private lateinit var chatRoot: LinearLayout
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var recLabel: TextView
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<String, Bitmap>(80) {}
    private var shown = ""   // last drawn thread (as text): redraw only on change
    private val poll = object : Runnable { override fun run() { draw(false); h.postDelayed(this, 3_000) } }

    // gallery
    private var gallery: LinearLayout? = null
    private var filter = "all"
    private var selMode = false          // gallery select mode (bulk delete of the child's files; messages are never deleted from here)
    private val sel = LinkedHashSet<String>()

    // playback of an audio bubble
    private var player: MediaPlayer? = null
    private var playingId = ""
    private val audioViews = HashMap<String, Pair<ImageView, TextView>>()
    private val tick = object : Runnable { override fun run() { updateAudio(); if (playingId.isNotEmpty()) h.postDelayed(this, 500) } }

    // capture / recording
    private var capFile: File? = null
    private var rec: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var recCancel = false
    private var pendingPerm: (() -> Unit)? = null
    private val recTick = object : Runnable { override fun run() { if (rec != null) { showRec(); h.postDelayed(this, 250) } } }

    companion object {
        const val EXTRA_GALLERY = "gallery"
        private const val R_PICK = 51; private const val R_FILE = 52; private const val R_PHOTO = 53; private const val R_VIDEO = 54; private const val R_PERM = 55
        private const val MAX_REC_MS = 120_000
        fun open(c: android.content.Context, gallery: Boolean = false) =
            c.startActivity(Intent(c, ChatActivity::class.java).also { if (gallery) it.putExtra(EXTRA_GALLERY, true) })
    }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        u = Ui(this)
        capFile = b?.getString("cap")?.let { File(CaptureProvider.dir(this), it) }
        chatRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(u.bg); fitsSystemWindows = true }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(u.dp(20), u.dp(16), u.dp(12), u.dp(8)) }
        head.addView(u.label(getString(R.string.chat_title), 20f, u.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill(getString(R.string.chat_gallery), R.drawable.li_image) { showGallery() })
        chatRoot.addView(head)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u.dp(14), u.dp(4), u.dp(14), u.dp(8)) }
        scroll = ScrollView(this).apply { addView(col) }
        chatRoot.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(u.dp(8), u.dp(8), u.dp(12), u.dp(10)) }
        val plus = iconBtn(R.drawable.li_plus, getString(R.string.chat_attach)) { attachMenu(it) }
        bar.addView(plus, LinearLayout.LayoutParams(u.dp(44), u.dp(44)))
        input = EditText(this).apply {
            hint = getString(R.string.chat_hint); setTextColor(u.text); setHintTextColor(u.sub); textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4; background = u.shape(u.card, 22, u.stroke, 1); setPadding(u.dp(16), u.dp(10), u.dp(16), u.dp(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(ChatLogic.MAX_TEXT))
        }
        recLabel = u.label("", 15f, u.accent, bold = true).apply { visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; setPadding(u.dp(16), u.dp(10), u.dp(16), u.dp(10)) }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(recLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val mic = iconBtn(R.drawable.li_mic, getString(R.string.chat_mic_hint)) { Toasts.show(this, getString(R.string.chat_mic_hint), false) }
        mic.setOnTouchListener { v, ev -> micTouch(v, ev) }
        bar.addView(mic, LinearLayout.LayoutParams(u.dp(44), u.dp(44)).apply { leftMargin = u.dp(4) })
        bar.addView(u.button(getString(R.string.chat_send), R.drawable.li_send, true) { send() }, LinearLayout.LayoutParams(-2, u.dp(48)).apply { leftMargin = u.dp(4) })
        chatRoot.addView(bar)
        setContentView(chatRoot)
        window.statusBarColor = u.bg; window.navigationBarColor = u.bg
        @Suppress("DEPRECATION") if (!u.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        if (b == null && intent?.getBooleanExtra(EXTRA_GALLERY, false) == true) showGallery()
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); capFile?.let { o.putString("cap", it.name) } }

    override fun onResume() {
        super.onResume()
        runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(3) }   // the parent's message notice (MessageActivity.NID)
        draw(true); h.postDelayed(poll, 3_000)
    }
    override fun onPause() {
        h.removeCallbacksAndMessages(null)
        stopPlay(); if (rec != null) stopRec(false)
        super.onPause()
    }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (gallery != null) hideGallery() else @Suppress("DEPRECATION") super.onBackPressed() }

    // ---------------- small views ----------------
    private fun pill(t: String, icon: Int, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(u.accent); gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = u.dp(6)
        setCompoundDrawablesRelativeWithIntrinsicBounds(Ic.get(this@ChatActivity, icon, u.accent, u.dp(18)), null, null, null)
        background = u.shape(u.card, 18, u.stroke, 1); setPadding(u.dp(12), u.dp(8), u.dp(14), u.dp(8)); setOnClickListener { f() }
    }
    private fun iconBtn(icon: Int, desc: String, f: (View) -> Unit) = ImageView(this).apply {
        setImageDrawable(Ic.get(this@ChatActivity, icon, u.accent, u.dp(24))); val p = u.dp(10); setPadding(p, p, p, p)
        contentDescription = desc; setOnClickListener { f(it) }
    }
    private fun round(v: View, dp: Int) { v.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, u.dpf(dp)) }; v.clipToOutline = true }
    private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)

    // ---------------- send text ----------------
    private fun paired(): Boolean { if (Store.of(this).everPaired) return true; Toasts.show(this, getString(R.string.chat_unpaired), true); return false }

    private fun send() {
        val s = Store.of(this)
        if (!paired()) return
        val t = ChatLogic.clean(input.text.toString())
        if (t.isEmpty() || !s.addChat("k", "", t)) return
        input.setText("")
        Guard.running?.kick()
        draw(true)
    }

    // ---------------- attach menu ----------------
    private fun attachMenu(anchor: View) {
        if (!paired()) return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, getString(R.string.chat_att_pick)); menu.add(0, 2, 1, getString(R.string.chat_att_cam))
            menu.add(0, 3, 2, getString(R.string.chat_att_vid)); menu.add(0, 4, 3, getString(R.string.chat_att_file))
            setOnMenuItemClickListener { m -> when (m.itemId) { 1 -> pickMedia(); 2 -> capture(false); 3 -> capture(true); else -> pickFile() }; true }
        }.show()
    }

    @Suppress("DEPRECATION")
    private fun pickMedia() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
            else Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, R_PICK) } catch (x: Exception) { Toasts.show(this, getString(R.string.files_no_picker), true) }
    }
    @Suppress("DEPRECATION")
    private fun pickFile() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), R_FILE) }
        catch (x: Exception) { Toasts.show(this, getString(R.string.files_no_picker), true) }
    }

    /** Camera app writes into our scratch file (CaptureProvider). The app declares CAMERA, so the camera intent needs the grant first. */
    @Suppress("DEPRECATION")
    private fun capture(video: Boolean) = withPerm(Manifest.permission.CAMERA) {
        val f = CaptureProvider.newFile(this, if (video) "mp4" else "jpg"); capFile = f
        val uri = CaptureProvider.uri(this, f)
        val i = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        try { startActivityForResult(i, if (video) R_VIDEO else R_PHOTO) } catch (x: ActivityNotFoundException) { Toasts.show(this, getString(R.string.chat_no_cam), true) }
        catch (x: SecurityException) { Toasts.show(this, getString(R.string.chat_no_cam), true) }
    }

    private fun withPerm(perm: String, then: () -> Unit) {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return then()
        pendingPerm = then; requestPermissions(arrayOf(perm), R_PERM)
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val p = pendingPerm; pendingPerm = null
        if (code == R_PERM && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (perms.firstOrNull() == Manifest.permission.RECORD_AUDIO) Toasts.show(this, getString(R.string.chat_mic_again), false) else p?.invoke() }
        else if (code == R_PERM) Toasts.show(this, getString(if (perms.firstOrNull() == Manifest.permission.RECORD_AUDIO) R.string.chat_mic_perm else R.string.chat_no_cam), true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        when (req) {
            R_PICK, R_FILE -> {
                if (res != RESULT_OK || data == null) return
                val uris = mutableListOf<Uri>()
                data.data?.let { uris += it }
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { if (it !in uris) uris += it } }
                sendUris(uris)
            }
            R_PHOTO, R_VIDEO -> {
                val f = capFile; capFile = null
                if (f == null) return
                if (res != RESULT_OK || !f.isFile || f.length() == 0L) { f.delete(); return }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(Date())
                val video = req == R_VIDEO
                sendFile(f, (if (video) "video-$stamp.mp4" else "photo-$stamp.jpg"), if (video) "video/mp4" else "image/jpeg", if (video) durOf(f.path, null) else 0)
            }
        }
    }

    private fun nameOf(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Seconds of an audio / video file, 0 = unknown. */
    private fun durOf(path: String?, uri: Uri?): Int {
        val m = MediaMetadataRetriever()
        return try {
            if (uri != null) m.setDataSource(this, uri) else m.setDataSource(path)
            ((m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        } catch (x: Exception) { 0 } finally { runCatching { m.release() } }
    }

    private fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toasts.show(applicationContext, getString(R.string.files_sending), false)
        val app = applicationContext
        Thread({
            var err = ""
            for (uri in uris.take(10)) {
                val name = nameOf(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val type = contentResolver.getType(uri)
                val kind = ChatLogic.kind(type, name)
                val dur = if (kind == "video" || kind == "audio") durOf(null, uri) else 0
                val (e, code) = runCatching {
                    val ins = contentResolver.openInputStream(uri) ?: return@runCatching null to "failed"
                    Files.addOutChat(app, name, type, ins, dur)
                }.getOrElse { null to "failed" }
                if (e == null) err = code
            }
            done(err)
        }, "chat-send").start()
    }

    private fun sendFile(f: File, name: String, type: String, dur: Int) {
        Toasts.show(applicationContext, getString(R.string.files_sending), false)
        val app = applicationContext
        Thread({
            val (e, code) = runCatching { Files.addOutChat(app, name, type, f.inputStream(), dur) }.getOrElse { null to "failed" }
            f.delete()
            done(if (e == null) code else "")
        }, "chat-send").start()
    }

    private fun done(err: String) = runOnUiThread {
        if (err.isNotEmpty()) Toasts.show(applicationContext, getString(when (err) { "too big" -> R.string.files_too_big; "no space" -> R.string.chat_nospace; else -> R.string.files_failed }), true)
        Guard.running?.kick()
        if (!isDestroyed) { shown = ""; draw(true); if (gallery != null) buildGallery() }
    }

    // ---------------- voice note: hold the mic button ----------------
    private fun micTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.parent.requestDisallowInterceptTouchEvent(true); startRec() }
            MotionEvent.ACTION_MOVE -> if (rec != null) { val c = ev.x < -u.dp(60) || ev.y < -u.dp(90); if (c != recCancel) { recCancel = c; showRec() } }
            MotionEvent.ACTION_UP -> if (rec != null) stopRec(!recCancel)
            MotionEvent.ACTION_CANCEL -> if (rec != null) stopRec(false)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun startRec() {
        if (!paired()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { pendingPerm = null; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), R_PERM); return }
        stopPlay()
        val f = CaptureProvider.newFile(this, "m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1); r.setAudioEncodingBitRate(32_000); r.setAudioSamplingRate(16_000); r.setMaxDuration(MAX_REC_MS); r.setOutputFile(f.path)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) h.post { if (rec != null) stopRec(!recCancel) } }
            r.prepare(); r.start()
        } catch (x: Exception) { runCatching { r.release() }; f.delete(); Toasts.show(this, getString(R.string.chat_mic_busy), true); return }
        rec = r; recFile = f; recStart = System.currentTimeMillis(); recCancel = false
        input.visibility = View.GONE; recLabel.visibility = View.VISIBLE; showRec(); h.postDelayed(recTick, 250)
    }

    private fun showRec() {
        val s = ((System.currentTimeMillis() - recStart) / 1000).toInt()
        recLabel.text = if (recCancel) getString(R.string.chat_rec_cancel) else getString(R.string.chat_rec, mmss(s))
        recLabel.setTextColor(if (recCancel) 0xFFD14343.toInt() else u.accent)
    }

    private fun stopRec(send: Boolean) {
        val r = rec ?: return; val f = recFile
        rec = null; recFile = null; h.removeCallbacks(recTick)
        val ms = System.currentTimeMillis() - recStart
        val ok = try { r.stop(); true } catch (x: Exception) { false } finally { runCatching { r.release() } }
        recLabel.visibility = View.GONE; input.visibility = View.VISIBLE
        if (f == null) return
        if (!send || !ok || ms < 800) { f.delete(); if (send && ms < 800) Toasts.show(this, getString(R.string.chat_mic_hint), false); return }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(Date())
        sendFile(f, "voice-$stamp.m4a", "audio/mp4", ((ms + 999) / 1000).toInt().coerceAtLeast(1))
    }

    // ---------------- playback ----------------
    private fun togglePlay(id: String) {
        if (playingId == id) return stopPlay()
        stopPlay()
        try {
            val p = MediaPlayer(); player = p; playingId = id
            p.setDataSource(this, FilesProvider.uri(this, id)); p.setOnCompletionListener { stopPlay() }; p.setOnErrorListener { _, _, _ -> stopPlay(); true }
            p.prepare(); p.start(); h.post(tick)
        } catch (x: Exception) { stopPlay(); Toasts.show(this, getString(R.string.files_gone), true) }
        updateAudio()
    }
    private fun stopPlay() { player?.let { runCatching { it.stop() }; runCatching { it.release() } }; player = null; playingId = ""; updateAudio() }
    private fun updateAudio() {
        for ((id, v) in audioViews) {
            val on = id == playingId
            v.first.setImageDrawable(Ic.get(this, if (on) R.drawable.li_pause else R.drawable.li_play, v.first.tag as? Int ?: u.accent, u.dp(24)))
            if (on) player?.let { p -> v.second.text = mmss(p.currentPosition / 1000) + " / " + mmss(p.duration / 1000) }
            else v.second.text = v.second.tag as? String ?: ""
        }
    }

    // ---------------- open / thumbnails ----------------
    private fun openFile(id: String) {
        val v = Files.viewIntent(this, id) ?: return Toasts.show(applicationContext, getString(R.string.files_gone), true)
        try { startActivity(v) } catch (x: ActivityNotFoundException) { Toasts.show(applicationContext, getString(R.string.files_no_app), true) }
        catch (x: Exception) { Toasts.show(applicationContext, getString(R.string.files_gone), true) }
    }

    private fun loadThumb(id: String, iv: ImageView) {
        thumbs.get(id)?.let { iv.setImageBitmap(it); return }
        val e = Files.get(this, id) ?: return
        runCatching { io.execute {
            val b = Files.thumb(this, e)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (b != null) { thumbs.put(id, b); iv.post { iv.setImageBitmap(b) } }
        } }
    }

    // ---------------- thread ----------------
    private fun attView(m: JSONObject, at: JSONObject, kid: Boolean): View {
        val id = at.optString("id"); val kind = at.optString("kind"); val st = at.optString("st")
        val fg = if (kid) u.onPrimary else u.text
        if (st == "gone") return u.label(getString(R.string.chat_att_gone, at.optString("name")), 14f, if (kid) u.onPrimary else u.sub)
        return when (kind) {
            "image", "video" -> FrameLayout(this).apply {
                val iv = ImageView(this@ChatActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(u.stroke); round(this, 12) }
                addView(iv, FrameLayout.LayoutParams(u.dp(220), u.dp(180)))
                if (kind == "video") {
                    addView(ImageView(this@ChatActivity).apply { setImageDrawable(Ic.get(this@ChatActivity, R.drawable.li_play, 0xFFFFFFFF.toInt(), u.dp(26))); background = u.shape(0x99000000.toInt(), 28); val p = u.dp(11); setPadding(p, p, p, p) },
                        FrameLayout.LayoutParams(u.dp(48), u.dp(48), Gravity.CENTER))
                    val d = at.optInt("dur")
                    if (d > 0) addView(u.label(mmss(d), 12f, 0xFFFFFFFF.toInt(), true).apply { background = u.shape(0x99000000.toInt(), 8); setPadding(u.dp(6), u.dp(2), u.dp(6), u.dp(2)) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, u.dp(6), u.dp(6)) })
                }
                setOnClickListener { openFile(id) }
                loadThumb(id, iv)
            }
            "audio" -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val ic = ImageView(this@ChatActivity).apply { tag = fg; setImageDrawable(Ic.get(this@ChatActivity, if (playingId == id) R.drawable.li_pause else R.drawable.li_play, fg, u.dp(24))) }
                val d = at.optInt("dur")
                val t = u.label(if (d > 0) mmss(d) else getString(R.string.chat_voice), 15f, fg).apply { tag = if (d > 0) mmss(d) else getString(R.string.chat_voice); minWidth = u.dp(110); setPadding(u.dp(8), 0, 0, 0) }
                addView(ic, LinearLayout.LayoutParams(u.dp(32), u.dp(32))); addView(t)
                audioViews[id] = ic to t
                setOnClickListener { togglePlay(id) }
            }
            else -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@ChatActivity).apply { setImageDrawable(Ic.get(this@ChatActivity, R.drawable.li_file, fg, u.dp(24))) }, LinearLayout.LayoutParams(u.dp(32), u.dp(32)))
                val c = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(u.dp(8), 0, 0, 0) }
                c.addView(u.label(at.optString("name"), 15f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; maxWidth = u.dp(200) })
                c.addView(u.label(Formatter.formatShortFileSize(this@ChatActivity, at.optLong("size")), 12f, if (kid) u.onPrimary else u.sub))
                addView(c)
                setOnClickListener { openFile(id) }
            }
        }
    }

    private fun draw(toEnd: Boolean) {
        val st = Store.of(this)
        st.chatMarkRead()   // the launcher's unread dot (control tile / "From mum and dad" widget) goes off while the thread is on screen
        val a = Files.chatView(this, st.chat())
        val sig = a.toString()
        if (sig == shown) return
        shown = sig
        col.removeAllViews(); audioViews.clear()
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
            m.optJSONObject("att")?.let { bubble.addView(attView(m, it, kid), LinearLayout.LayoutParams(-2, -2).apply { topMargin = u.dp(2); bottomMargin = u.dp(2) }) }
            val tx = m.optString("text")
            if (tx.isNotEmpty()) bubble.addView(TextView(this).apply { text = tx; textSize = 16f; setTextColor(if (kid) u.onPrimary else u.text); setTextIsSelectable(false) })
            val wait = kid && m.optJSONObject("att")?.optString("st") == "wait"
            val time = android.text.format.DateFormat.getTimeFormat(this@ChatActivity).format(Date(m.optLong("t")))
            bubble.addView(u.label(if (wait) getString(R.string.chat_att_wait) + " · " + time else time, 11f, if (kid) u.onPrimary else u.sub).apply { gravity = Gravity.END })
            col.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = if (kid) Gravity.END else Gravity.START; topMargin = u.dp(6)
                if (kid) leftMargin = u.dp(48) else rightMargin = u.dp(48)
            })
        }
        if (toEnd) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------- gallery: everything kept (also the old shared-folder files) ----------------
    /** Check circle in the corner of a tile / row in select mode; a selected one is dimmed a little. */
    private fun tick(v: View, on: Boolean) {
        v.alpha = if (on) 0.72f else 1f
        val fl = v as? FrameLayout ?: return
        fl.addView(ImageView(this).apply {
            background = u.shape(if (on) u.primary else 0x66000000, 24); val p = u.dp(4); setPadding(p, p, p, p)
            setImageDrawable(Ic.get(this@ChatActivity, if (on) R.drawable.li_check else R.drawable.li_circle, 0xFFFFFFFF.toInt(), u.dp(16)))
        }, FrameLayout.LayoutParams(u.dp(26), u.dp(26), Gravity.TOP or Gravity.START).apply { setMargins(u.dp(6), u.dp(6), 0, 0) })
    }
    /** Day header with its own check ("select this day") shown in select mode. */
    private fun dayHead(day: List<FilesLogic.Entry>): View {
        val on = day.all { it.id in sel }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(u.dp(2), u.dp(10), u.dp(2), u.dp(4))
            addView(ImageView(this@ChatActivity).apply { setImageDrawable(Ic.get(this@ChatActivity, if (on) R.drawable.li_check else R.drawable.li_circle, if (on) u.primary else u.sub, u.dp(22))) }, LinearLayout.LayoutParams(u.dp(28), u.dp(28)))
            addView(u.label(android.text.format.DateUtils.formatDateTime(this@ChatActivity, day.first().at, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY), 14f, u.text, true).apply { setPadding(u.dp(8), 0, 0, 0) })
            setOnClickListener { selDay(day) }
        }
    }

    private fun showGallery() { stopPlay(); gallery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(u.bg); fitsSystemWindows = true }; buildGallery(); setContentView(gallery) }
    private fun hideGallery() { gallery = null; selMode = false; sel.clear(); setContentView(chatRoot); shown = ""; draw(true) }

    // ---- select mode (Google Photos style): long press = start, tap = toggle, "select by date" menu, per-day check, bar "N selected · X MB -> Delete" ----
    private fun galShown(items: List<FilesLogic.Entry>): List<FilesLogic.Entry> =
        items.filter { filter == "all" || ChatLogic.kind(it.mime, it.name).let { k -> if (filter == "media") k == "image" || k == "video" else k == filter } }
    private fun toggle(id: String) { if (!sel.remove(id)) sel.add(id); if (sel.isEmpty()) selMode = false; buildGallery() }
    private fun selDay(day: List<FilesLogic.Entry>) { val all = day.all { it.id in sel }; day.forEach { if (all) sel.remove(it.id) else sel.add(it.id) }; if (sel.isEmpty()) selMode = false; buildGallery() }
    private fun selRange(items: List<FilesLogic.Entry>, r: SelLogic.Range) {
        val now = System.currentTimeMillis()
        sel.clear(); sel.addAll(SelLogic.pick(items.map { it.id to it.at }, r, now)); selMode = sel.isNotEmpty()
        if (!selMode) Toasts.show(this, getString(R.string.gal_sel_none), false)
        buildGallery()
    }
    private fun rangeMenu(anchor: View, items: List<FilesLogic.Entry>) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, getString(R.string.gal_r_all)); menu.add(0, 1, 1, getString(R.string.gal_r_today)); menu.add(0, 2, 2, getString(R.string.gal_r_7))
            menu.add(0, 3, 3, getString(R.string.gal_r_30)); menu.add(0, 4, 4, getString(R.string.gal_r_old))
            setOnMenuItemClickListener { m ->
                when (m.itemId) {
                    0 -> { sel.clear(); sel.addAll(items.map { it.id }); selMode = sel.isNotEmpty(); buildGallery() }
                    1 -> selRange(items, SelLogic.Range.TODAY); 2 -> selRange(items, SelLogic.Range.DAYS7)
                    3 -> selRange(items, SelLogic.Range.DAYS30); else -> selRange(items, SelLogic.Range.OLDER30)
                }; true
            }
        }.show()
    }
    private fun delSelected(all: List<FilesLogic.Entry>) {
        val ids = sel.toList(); val bytes = all.filter { it.id in sel }.sumOf { it.size }
        if (ids.isEmpty()) return
        Modal(this, u).show(R.drawable.li_trash_2, Modal.Tone.DANGER, getString(R.string.gal_del_title, ids.size), getString(R.string.gal_del_msg, Formatter.formatShortFileSize(this, bytes)),
            ok = getString(R.string.gal_del), cancel = getString(R.string.cancel)) {
            io.execute {
                ids.chunked(FilesLogic.MAX_IDS).forEach { Files.command(this, "chatFileDel", JSONObject().put("ids", org.json.JSONArray(it))) }
                runOnUiThread { sel.clear(); selMode = false; Toasts.show(this, getString(R.string.gal_deleted, ids.size), false); if (!isDestroyed) { shown = ""; buildGallery() } }
            }
            true
        }
    }

    private fun buildGallery() {
        val g = gallery ?: return
        g.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(u.dp(8), u.dp(12), u.dp(12), u.dp(4)) }
        val allItems = Files.all(this).filter { it.st != FilesLogic.GONE }.sortedByDescending { it.at }
        sel.retainAll(allItems.map { it.id }.toSet()); if (sel.isEmpty()) selMode = false
        if (selMode) {
            val bytes = allItems.filter { it.id in sel }.sumOf { it.size }
            head.addView(iconBtn(R.drawable.li_x, getString(R.string.cancel)) { sel.clear(); selMode = false; buildGallery() }, LinearLayout.LayoutParams(u.dp(44), u.dp(44)))
            head.addView(u.label(getString(R.string.gal_sel_n, sel.size, Formatter.formatShortFileSize(this, bytes)), 17f, u.text, bold = true).apply { setPadding(u.dp(6), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(iconBtn(R.drawable.li_calendar, getString(R.string.gal_r_title)) { rangeMenu(it, galShown(allItems)) }, LinearLayout.LayoutParams(u.dp(44), u.dp(44)))
            head.addView(iconBtn(R.drawable.li_trash_2, getString(R.string.gal_del)) { delSelected(allItems) }, LinearLayout.LayoutParams(u.dp(44), u.dp(44)))
        } else {
            head.addView(iconBtn(R.drawable.li_arrow_left, getString(R.string.hm_c_back)) { hideGallery() }, LinearLayout.LayoutParams(u.dp(44), u.dp(44)))
            head.addView(u.label(getString(R.string.chat_gallery), 20f, u.text, bold = true).apply { setPadding(u.dp(6), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(iconBtn(R.drawable.li_check, getString(R.string.gal_select)) { if (galShown(allItems).isNotEmpty()) { selMode = true; buildGallery() } }, LinearLayout.LayoutParams(u.dp(44), u.dp(44)))
        }
        g.addView(head)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(u.dp(14), u.dp(4), u.dp(14), u.dp(8)) }
        for ((k, r) in listOf("all" to R.string.chat_g_all, "media" to R.string.chat_g_media, "audio" to R.string.chat_g_audio, "file" to R.string.chat_g_files)) {
            val on = filter == k
            chips.addView(TextView(this).apply {
                text = getString(r); textSize = 13.5f; setTextColor(if (on) u.onPrimary else u.text); background = if (on) u.shape(u.primary, 16) else u.shape(u.card, 16, u.stroke, 1)
                setPadding(u.dp(12), u.dp(6), u.dp(12), u.dp(6)); setOnClickListener { filter = k; buildGallery() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = u.dp(6) })
        }
        g.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val items = allItems
        val media = items.filter { ChatLogic.kind(it.mime, it.name).let { k -> k == "image" || k == "video" } }
        val other = items - media.toSet()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u.dp(14), u.dp(4), u.dp(14), u.dp(24)) }
        val showMedia = filter == "all" || filter == "media"
        val list = other.filter { filter == "all" || ChatLogic.kind(it.mime, it.name) == filter }
        if (!showMedia && list.isEmpty() || showMedia && media.isEmpty() && list.isEmpty()) body.addView(u.label(getString(R.string.chat_g_empty), 15f).apply { gravity = Gravity.CENTER; setPadding(0, u.dp(60), 0, 0) })
        if (showMedia && media.isNotEmpty()) {
            val cell = (resources.displayMetrics.widthPixels - u.dp(28) - u.dp(8)) / 3
            val groups = if (selMode) media.groupBy { SelLogic.dayKey(it.at) }.values.toList() else listOf(media)
            groups.forEach { grp ->
              if (selMode) body.addView(dayHead(grp))
              grp.chunked(3).forEach { rowItems ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowItems.forEach { e ->
                    val fl = FrameLayout(this)
                    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(u.stroke); round(this, 8) }
                    fl.addView(iv, FrameLayout.LayoutParams(-1, -1))
                    if (ChatLogic.kind(e.mime, e.name) == "video") fl.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@ChatActivity, R.drawable.li_play, 0xFFFFFFFF.toInt(), u.dp(22))); background = u.shape(0x99000000.toInt(), 24); val p = u.dp(9); setPadding(p, p, p, p) }, FrameLayout.LayoutParams(u.dp(40), u.dp(40), Gravity.CENTER))
                    fl.setOnClickListener { if (selMode) toggle(e.id) else openFile(e.id) }
                    fl.setOnLongClickListener { if (!selMode) { selMode = true; toggle(e.id) } ; true }
                    if (selMode) tick(fl, e.id in sel)
                    loadThumb(e.id, iv)
                    row.addView(fl, LinearLayout.LayoutParams(cell, cell).apply { setMargins(u.dp(2), u.dp(2), u.dp(2), u.dp(2)) })
                }
                body.addView(row)
              }
            }
        }
        list.forEach { e ->
            val kind = ChatLogic.kind(e.mime, e.name)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = u.shape(u.card, 14, u.stroke, 1); setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10)) }
            r.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@ChatActivity, if (kind == "audio") R.drawable.li_mic else R.drawable.li_file, u.accent, u.dp(24))) }, LinearLayout.LayoutParams(u.dp(32), u.dp(32)))
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u.dp(10), 0, 0, 0) }
            c.addView(u.label(e.name, 15f, u.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            c.addView(u.label(Formatter.formatShortFileSize(this, e.size) + " · " + android.text.format.DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS), 12f))
            r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
            r.setOnClickListener { if (selMode) toggle(e.id) else openFile(e.id) }
            r.setOnLongClickListener { if (!selMode) { selMode = true; toggle(e.id) }; true }
            if (selMode) { tick(r, e.id in sel); if (e.id in sel) r.background = u.shape(u.card, 14, u.primary, 2) }
            body.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u.dp(6) })
        }
        g.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
