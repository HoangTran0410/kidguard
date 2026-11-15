package dev.kidguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Weather data for the home widget: the last known location -> Open-Meteo (no key, coordinates rounded to 2 decimals) ->
 * cache in the launcher's prefs (json + time + coords + place). Everything slow (network, geocoder) runs on one background
 * thread; the main thread only reads the cache and gets [Weather.onChange] callbacks. Network errors stay silent: the card
 * keeps the old numbers. Pure parsing / staleness: [WeatherLogic].
 */
internal object Weather {
    class Cached(val wx: WeatherLogic.Wx, val at: Long, val place: String)

    private const val PREFS = "kg_weather"
    const val REQ = 51
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    /** Widgets on screen redraw from the cache when it changes (main thread). */
    val listeners = ArrayList<() -> Unit>()

    private fun prefs(ctx: Context) = ctx.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(ctx: Context): Cached? {
        val p = prefs(ctx)
        val wx = WeatherLogic.parse(p.getString("json", null)) ?: return null
        return Cached(wx, p.getLong("at", 0L), p.getString("place", "").orEmpty())
    }

    fun hasPerm(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Newest fix any provider remembers (no new fix is requested: this widget never wakes the GPS). */
    private fun lastFix(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    /** Fetch when the cache is older than 30 min (or [force]); call from the main thread. Never blocks it. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (busy || !hasPerm(app)) return
        if (!force && !WeatherLogic.stale(prefs(app).getLong("at", 0L), System.currentTimeMillis())) return
        busy = true
        Thread {
            try { fetch(app) } catch (_: Throwable) { } finally { busy = false }
            main.post { for (l in ArrayList(listeners)) l() }
        }.apply { name = "kg-weather"; isDaemon = true }.start()
    }

    private fun fetch(ctx: Context) {
        val loc = lastFix(ctx) ?: return
        val lat = WeatherLogic.round2(loc.latitude); val lon = WeatherLogic.round2(loc.longitude)
        val c = URL(WeatherLogic.url(lat, lon)).openConnection() as HttpURLConnection
        val body = try {
            c.connectTimeout = 8000; c.readTimeout = 8000; c.requestMethod = "GET"
            if (c.responseCode != 200) return
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        if (WeatherLogic.parse(body) == null) return
        val p = prefs(ctx)
        var place = p.getString("place", "").orEmpty()
        // a new name only after moving ~2 km (or when there is none yet): the geocoder is the slow, flaky part
        if (place.isEmpty() || !WeatherLogic.samePlace(p.getFloat("plat", 0f).toDouble(), p.getFloat("plon", 0f).toDouble(), lat, lon))
            place = geocode(ctx, lat, lon) ?: place.takeIf { WeatherLogic.samePlace(p.getFloat("plat", 0f).toDouble(), p.getFloat("plon", 0f).toDouble(), lat, lon) }.orEmpty()
        p.edit().putString("json", body).putLong("at", System.currentTimeMillis()).putFloat("plat", lat.toFloat()).putFloat("plon", lon.toFloat()).putString("place", place).apply()
    }

    @Suppress("DEPRECATION")
    private fun geocode(ctx: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(ctx, ctx.resources.configuration.locales[0] ?: Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        WeatherLogic.pickPlace(a.subAdminArea, a.subLocality, a.locality).takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/** The weather app the phone has (Xiaomi, Google, Oppo / OnePlus, Samsung), or null: then a tap does nothing. */
private fun HomeActivity.weatherIntent(): Intent? {
    val have = launcherApps()
    val pkg = listOf("com.miui.weather2", "com.google.android.apps.weather", "com.coloros.weather2", "com.oneplus.weather", "com.heytap.weather", "com.sec.android.daemonservice")
        .firstOrNull { have.containsKey(it) } ?: return null
    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
}

/** Weather card. 2×1: icon + temperature + place. 2×2: place, big temperature + icon, condition, high / low. Redraws from the cache; a refresh runs in the background. */
internal fun HomeActivity.weatherWidget(w: Int, h: Int): View {
    val big = h >= 2
    val card = wCard(true)
    val icon = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val temp = wFit("", if (big) WType.HERO else WType.VALUE_L, pal.text, true)
    val place = wSub("")
    val cond = wMain("")
    val hilo = wSub("")
    if (!big) {
        card.orientation = LinearLayout.HORIZONTAL; card.gravity = Gravity.CENTER_VERTICAL
        card.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(temp, LinearLayout.LayoutParams(-2, -2)); col.addView(place, LinearLayout.LayoutParams(-2, -2))
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    } else {
        card.gravity = Gravity.CENTER_VERTICAL; card.minimumHeight = dp(150)
        place.setTextColor(pal.sub)
        card.addView(place, LinearLayout.LayoutParams(-1, -2))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mid.addView(temp, LinearLayout.LayoutParams(0, -2, 1f)); mid.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(cond, LinearLayout.LayoutParams(-1, -2)); card.addView(hilo, LinearLayout.LayoutParams(-1, -2))
    }
    fun set(tv: android.widget.TextView, t: String) { if (tv.text.toString() != t) tv.text = t; if (tv is FitText) tv.full = t }
    fun draw() {
        val c = Weather.cached(this)
        if (c != null) {
            val k = WeatherLogic.cond(c.wx.code)
            icon.setImageDrawable(Ic.get(this, k.icon(c.wx.day), pal.accentText, dp(if (big) 40 else 30)))
            set(temp, WeatherLogic.deg(c.wx.temp)); set(place, WeatherLogic.pickPlace(null, null, c.place).ifEmpty { getString(R.string.hm_w_weather) })
            set(cond, getString(k.text)); set(hilo, WeatherLogic.hiLo(c.wx))
            card.contentDescription = listOf(c.place, WeatherLogic.deg(c.wx.temp), getString(k.text), WeatherLogic.hiLo(c.wx)).filter { it.isNotEmpty() }.joinToString(". ")
        } else {
            // nothing to show yet: say why, in the card's own lines (no fake numbers)
            val noPerm = !Weather.hasPerm(this)
            val head = getString(if (noPerm) R.string.wx_noloc else R.string.wx_loading)
            val sub = getString(if (noPerm) (if (Flavor.HOME) R.string.wx_noloc_home else R.string.wx_tap_allow) else R.string.wx_loading_sub)
            icon.setImageDrawable(Ic.get(this, R.drawable.li_cloud, pal.sub, dp(if (big) 40 else 30)))
            set(temp, "--°"); set(place, if (big) head else sub); set(cond, head); set(hilo, sub)
            card.contentDescription = "$head. $sub"
        }
    }
    draw()
    // render never waits for the network: the cache is on screen now, a fresh answer redraws it when it lands
    val l: () -> Unit = { if (card.isAttachedToWindow || card.parent == null) draw() }
    Weather.listeners.add(l)
    card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { if (l !in Weather.listeners) Weather.listeners.add(l) }
        override fun onViewDetachedFromWindow(v: View) { Weather.listeners.remove(l) }
    })
    Weather.refresh(this)
    // the minute tick only runs while the launcher is in front: no polling with the screen off
    tickers += { Weather.refresh(this); draw() }
    return centred(card).also {
        kit.tap(card, 22f) {
            if (Weather.cached(this) == null && !Weather.hasPerm(this)) {
                if (!Flavor.HOME) runCatching { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), Weather.REQ) }
            } else {
                Weather.refresh(this, force = Weather.cached(this) == null)
                weatherIntent()?.let { openRelated(it, card) }
            }
        }
    }
}

internal fun HomeActivity.weatherPreview(c: LinearLayout) {
    c.gravity = Gravity.CENTER
    c.addView(ImageView(this).apply { setImageDrawable(Ic.get(this@weatherPreview, R.drawable.li_cloud_sun, pal.accentText, dp(26))) }, LinearLayout.LayoutParams(dp(26), dp(26)))
    c.addView(text("28°", WType.CAPTION, pal.text, true).apply { setPadding(0, dp(3), 0, 0); gravity = Gravity.CENTER })
}
