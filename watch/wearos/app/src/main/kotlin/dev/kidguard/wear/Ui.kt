package dev.kidguard.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Blue = Color(0xFF1565C0)

// ---------- small helpers ----------

private fun hhmm(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

private fun leftText(c: Context, ms: Long): String {
    val m = (ms / 60_000).toInt().coerceAtLeast(0)
    return if (m < 60) c.getString(R.string.m_min, m) else c.getString(R.string.m_hour, m / 60, m % 60)
}

private fun ago(c: Context, t: Long): String {
    if (t <= 0) return "?"
    val m = ((System.currentTimeMillis() - t) / 60_000).toInt()
    return when {
        m < 1 -> c.getString(R.string.now)
        m < 60 -> c.getString(R.string.m_min, m)
        m < 24 * 60 -> c.getString(R.string.m_hour, m / 60, m % 60)
        else -> "${m / 1440} d"
    }
}

/** now.why -> words (Zepp page/index.js nowLine); "" = the phone is simply in use. */
private fun whyText(c: Context, s: Summary): String {
    if (s.lost) return c.getString(R.string.why_lost)
    val until = if (s.until > 0) "\n" + c.getString(R.string.until, hhmm(s.until)) else ""
    return when (s.why) {
        "" -> ""
        "daily" -> c.getString(R.string.why_daily) + until
        "paused" -> c.getString(R.string.why_paused) + until
        "schedule" -> c.getString(R.string.why_schedule) + (if (s.whyName.isNotEmpty()) ": " + s.whyName else "") + until
        "bedtime" -> c.getString(R.string.why_bedtime) + until
        "pass" -> c.getString(R.string.why_pass) + until
        "code" -> c.getString(R.string.why_code) + until
        else -> s.why + until
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colors.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.caption2, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable
private fun Btn(text: String, color: Color? = null, onClick: () -> Unit) = Chip(
    onClick = onClick, modifier = Modifier.fillMaxWidth(),
    label = { Text(text, maxLines = 2) },
    colors = if (color != null) ChipDefaults.chipColors(backgroundColor = color) else ChipDefaults.secondaryChipColors(),
)

@Composable
private fun List(content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    val st = rememberScalingLazyListState()
    ScalingLazyColumn(state = st, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 34.dp, bottom = 34.dp, start = 10.dp, end = 10.dp), content = content)
}

/** Sends one command: "Sending…" -> "Sent" -> result from state.done[] (polled every 3 s for ~60 s). */
class Runner(private val scope: CoroutineScope, private val ctx: Context, private val kid: Kid) {
    var status by mutableStateOf<String?>(null)
        private set
    private var job: kotlinx.coroutines.Job? = null

    fun run(blk: () -> String, after: () -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            status = ctx.getString(R.string.sending)
            try {
                val id = withContext(Dispatchers.IO) { blk() }
                status = ctx.getString(R.string.sent)
                after()
                if (kid.demo) { delay(600); status = ctx.getString(R.string.done); return@launch }
                repeat(20) {
                    delay(3000)
                    val r = withContext(Dispatchers.IO) { try { Net.receipt(kid, id) } catch (e: Exception) { "wait" } }
                    when {
                        r == "ok" -> { status = ctx.getString(R.string.done); after(); return@launch }
                        r == "expired" -> { status = ctx.getString(R.string.expired); return@launch }
                        r.startsWith("fail:") -> { status = ctx.getString(R.string.failed, r.substring(5)); return@launch }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { status = ctx.getString(R.string.failed, e.message ?: ctx.getString(R.string.no_phone)) }
        }
    }
}

// ---------- connect ----------

@Composable
fun ConnectScreen(onDemo: () -> Unit, onDone: (Int) -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableLongStateOf(0L) }
    var err by remember { mutableStateOf(false) }
    // one loop: get a code, poll every 3 s, renew when it expires or the relay forgot it
    LaunchedEffect(Unit) {
        val relay = Store.relay(ctx)
        while (true) {
            val p = try { withContext(Dispatchers.IO) { Net.pairNew(relay) } } catch (e: Exception) { err = true; delay(5000); continue }
            err = false; code = p.code
            var renew = false
            while (!renew) {
                left = p.exp - System.currentTimeMillis()
                if (left <= 0) { renew = true; break }
                try {
                    val k = withContext(Dispatchers.IO) { Net.pairPoll(relay, p) }
                    if (k != null) { onDone(Store.addKid(ctx, k)); return@LaunchedEffect }
                    err = false
                } catch (e: ApiError) { if (e.message == "expired") renew = true else err = true
                } catch (e: Exception) { err = true }
                // count down every second between polls (3 s)
                repeat(3) { if (!renew) { delay(1000); left = p.exp - System.currentTimeMillis() } }
            }
            code = null
        }
    }
    List {
        item { Title(stringResource(R.string.connect_title)) }
        item {
            val c = code
            if (c == null) Note(stringResource(R.string.connect_new))
            else Text(c.substring(0, 3) + " " + c.substring(3), fontSize = 34.sp, textAlign = TextAlign.Center, color = MaterialTheme.colors.primary, modifier = Modifier.fillMaxWidth())
        }
        if (code != null) item { Note(stringResource(R.string.connect_left, "%d:%02d".format((left / 60000).coerceAtLeast(0), (left / 1000 % 60).coerceAtLeast(0)))) }
        item { Note(stringResource(R.string.connect_hint)) }
        if (err) item { Note(stringResource(R.string.connect_err), Red) }
        item { Btn(stringResource(R.string.demo)) { onDemo() } }
    }
}

// ---------- kids list ----------

@Composable
fun KidsScreen(ver: Int, onKid: (Int) -> Unit, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val kids = remember(ver) { Store.kids(ctx) }
    List {
        item { Title(stringResource(R.string.kids)) }
        kids.forEachIndexed { i, k -> item { Btn(k.name, Blue) { onKid(i) } } }
        item { Btn(stringResource(R.string.add_kid)) { onAdd() } }
        item { Btn(stringResource(R.string.demo)) { onKid(-1) } }
    }
}

// ---------- kid home ----------

@Composable
fun KidScreen(kid: Kid, onWhere: () -> Unit, onMessage: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sum by remember { mutableStateOf<Summary?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val runner = remember(kid) { Runner(scope, ctx, kid) }
    LaunchedEffect(kid, reload) {
        while (true) {
            try { sum = withContext(Dispatchers.IO) { Net.state(kid).sum }; err = null } catch (e: Exception) { err = ctx.getString(R.string.no_phone) }
            delay(15_000)
        }
    }
    val s = sum
    List {
        item { Title(kid.name) }
        if (s == null) item { Note(err ?: stringResource(R.string.loading), if (err != null) Red else MaterialTheme.colors.onSurfaceVariant) }
        else {
            item {
                val why = whyText(ctx, s)
                if (why.isNotEmpty()) Text(why, style = MaterialTheme.typography.title2, color = Red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                else if (s.leftMs >= 0) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(leftText(ctx, s.leftMs), fontSize = 30.sp, color = Green)
                    Note(stringResource(R.string.left_today))
                } else Note(stringResource(R.string.unknown_left))
            }
            if (s.current.isNotEmpty()) item { Note(s.current) }
            item {
                val bat = if (s.battery >= 0) stringResource(R.string.battery, s.battery) + (if (s.charging) " " + stringResource(R.string.charging) else "") else ""
                Note(listOf(bat, stringResource(R.string.seen, ago(ctx, s.seen))).filter { it.isNotEmpty() }.joinToString("\n"))
            }
            if (err != null) item { Note(err!!, Red) }
            runner.status?.let { st -> item { Text(st, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } }
            if (s.req.isNotEmpty()) {
                item { Title(stringResource(R.string.requests)) }
                s.req.forEach { r ->
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (r.kind == "app") stringResource(R.string.req_app, r.app) else stringResource(R.string.req_time, r.min), textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
                            if (r.note.isNotEmpty()) Note(r.note)
                        }
                    }
                    val after = { reload++; Unit }
                    if (r.kind == "app") item { Btn(stringResource(R.string.allow), Green) { runner.run({ Net.answer(kid, r, true, 0) }, after) } }
                    else listOf(15, 30).forEach { m -> item { Btn(stringResource(R.string.approve_min, m), Green) { runner.run({ Net.answer(kid, r, true, m) }, after) } } }
                    item { Btn(stringResource(R.string.deny), Red) { runner.run({ Net.answer(kid, r, false, 0) }, after) } }
                }
            }
            item { Title(stringResource(R.string.actions)) }
            item { Btn(stringResource(R.string.where)) { onWhere() } }
            item { Btn(stringResource(R.string.lock)) { runner.run({ Net.cmd(kid, "lock") }) } }
            item { Btn(stringResource(R.string.ring)) { runner.run({ Net.cmd(kid, "ring") }) } }
            item { Btn(stringResource(R.string.ring_stop)) { runner.run({ Net.cmd(kid, "ringStop") }) } }
            item { Btn(stringResource(R.string.locate)) { runner.run({ Net.cmd(kid, "locate") }, { reload++; Unit }) } }
            item { Btn(stringResource(R.string.message)) { onMessage() } }
            if (kid.demo) item { Note(stringResource(R.string.demo_note)) }
            else item { Btn(stringResource(R.string.remove), Color(0xFF444444)) { onRemove() } }
        }
    }
}

// ---------- message presets ----------

@Composable
fun MessageScreen(kid: Kid, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<kotlin.collections.List<String>?>(null) }
    val runner = remember(kid) { Runner(scope, ctx, kid) }
    val builtin = listOf(R.string.msg1, R.string.msg2, R.string.msg3, R.string.msg4, R.string.msg5).map { ctx.getString(it) }
    LaunchedEffect(kid) {
        // presets = rules.msgs; built-in list when the parent has none (or the relay is unreachable)
        msgs = try { withContext(Dispatchers.IO) { Net.state(kid).sum.msgs } } catch (e: Exception) { emptyList() }
    }
    List {
        item { Title(stringResource(R.string.message)) }
        runner.status?.let { st -> item { Note(st, MaterialTheme.colors.primary) } }
        val l = msgs
        if (l == null) item { Note(stringResource(R.string.loading)) }
        else (l.ifEmpty { builtin }).forEach { m -> item { Btn(m) { runner.run({ Net.cmd(kid, "message") { put("text", m) } }) } } }
    }
}

// ---------- where: coordinates + 2x2 tile map ----------

@Composable
fun WhereScreen(kid: Kid) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sum by remember { mutableStateOf<Summary?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var z by remember { mutableIntStateOf(Store.zoom(ctx)) }
    var mosaic by remember { mutableStateOf<Mosaic?>(null) }
    var mapLoading by remember { mutableStateOf(false) }
    val runner = remember(kid) { Runner(scope, ctx, kid) }
    LaunchedEffect(kid, reload) {
        while (true) {
            try { sum = withContext(Dispatchers.IO) { Net.state(kid).sum }; err = null } catch (e: Exception) { err = ctx.getString(R.string.no_phone) }
            delay(20_000)
        }
    }
    val loc = sum?.loc
    LaunchedEffect(loc?.lat, loc?.lng, z) {
        val l = loc ?: return@LaunchedEffect
        mapLoading = true
        mosaic = withContext(Dispatchers.IO) { Tiles.mosaic(ctx, kid, l.lat, l.lng, z) }
        mapLoading = false
    }
    val side = (LocalConfiguration.current.screenWidthDp * 0.7f).dp
    List {
        item { Title(stringResource(R.string.where)) }
        val s = sum
        if (s == null) item { Note(err ?: stringResource(R.string.loading), if (err != null) Red else MaterialTheme.colors.onSurfaceVariant) }
        else if (loc == null) item { Note(stringResource(R.string.no_loc)) }
        else {
            item {
                val m = mosaic
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(side).clip(RoundedCornerShape(10.dp))) {
                        if (m == null) { drawRect(Color(0xFF2A2A2A)); return@Canvas }
                        // window = 256 px of the 512 px mosaic around the kid, scaled to the canvas
                        val wx = (m.px - 128).coerceIn(0f, 256f); val wy = (m.py - 128).coerceIn(0f, 256f)
                        val sc = size.width / 256f
                        drawImage(m.bmp.asImageBitmap(), IntOffset(wx.toInt(), wy.toInt()), IntSize(256, 256), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                        val c = Offset((m.px - wx) * sc, (m.py - wy) * sc)
                        val mp = MapMath.mpp(loc.lat, z).toFloat()
                        s.zones.forEach { zn ->
                            val dx = Math.toRadians(zn.lng - loc.lng) * 6378137 * cos(Math.toRadians(loc.lat))
                            val dy = Math.toRadians(zn.lat - loc.lat) * 6378137
                            val r = (zn.r / mp * sc).toFloat()
                            val zc = Offset(c.x + (dx / mp * sc).toFloat(), c.y - (dy / mp * sc).toFloat())
                            if (r in 4f..size.width / 2) drawCircle(Green.copy(alpha = 0.35f), r, zc)
                        }
                        val ar = (loc.acc / mp * sc).coerceIn(8f, size.width / 2)
                        drawCircle(Blue.copy(alpha = 0.3f), ar, c)
                        drawCircle(Color.White, 10f, c)
                        drawCircle(Color(0xFFD32F2F), 7f, c)
                    }
                }
            }
            // zoom right under the map, plain - / +
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (z > MapMath.ZMIN) { z--; Store.setZoom(ctx, z) } }, modifier = Modifier.size(40.dp), colors = ButtonDefaults.secondaryButtonColors()) { Text(stringResource(R.string.zoom_out), fontSize = 20.sp) }
                    Spacer(Modifier.width(14.dp))
                    Button(onClick = { if (z < MapMath.ZMAX) { z++; Store.setZoom(ctx, z) } }, modifier = Modifier.size(40.dp), colors = ButtonDefaults.secondaryButtonColors()) { Text(stringResource(R.string.zoom_in), fontSize = 20.sp) }
                }
            }
            item {
                val m = mosaic
                if (mapLoading && m == null) Note(stringResource(R.string.map_loading))
                else if (m != null && m.missing > 0) Note(stringResource(R.string.map_fail), MaterialTheme.colors.error)
            }
            item { Text(stringResource(R.string.fix_line, ago(ctx, loc.t), loc.acc), style = MaterialTheme.typography.body2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            item { Note("%.5f, %.5f".format(loc.lat, loc.lng)) }
            item { Btn(stringResource(R.string.directions), Blue) { openMaps(ctx, loc.lat, loc.lng) } }
        }
        runner.status?.let { st -> item { Note(st, MaterialTheme.colors.primary) } }
        item { Btn(stringResource(R.string.locate)) { runner.run({ Net.cmd(kid, "locate") }, { reload++; Unit }) } }
    }
}

/** Google Maps walking/driving directions to the kid; clear message when the watch has no Maps app. */
private fun openMaps(c: Context, lat: Double, lng: Double) {
    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
    try {
        c.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try { c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e2: Exception) { Toast.makeText(c, R.string.no_maps, Toast.LENGTH_SHORT).show() }
    }
}
