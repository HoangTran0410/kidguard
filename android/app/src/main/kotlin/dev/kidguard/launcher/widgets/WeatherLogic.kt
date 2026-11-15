package dev.kidguard

import org.json.JSONObject
import java.util.Locale

/** Pure part of the weather widget: Open-Meteo request / parse, WMO code -> text + icon, cache age. No Android, unit-tested. */
internal object WeatherLogic {
    /** What the card shows: whole degrees (C), today's high / low, WMO code, day or night. */
    data class Wx(val temp: Int, val hi: Int, val lo: Int, val code: Int, val day: Boolean)

    /** Condition group of a WMO weather_code: its text and the day / night icon. */
    enum class Cond(val text: Int, val iconDay: Int, val iconNight: Int) {
        CLEAR(R.string.wx_clear, R.drawable.li_sun, R.drawable.li_moon),
        MAINLY_CLEAR(R.string.wx_mainly_clear, R.drawable.li_cloud_sun, R.drawable.li_cloud_moon),
        PARTLY(R.string.wx_partly, R.drawable.li_cloud_sun, R.drawable.li_cloud_moon),
        CLOUDY(R.string.wx_cloudy, R.drawable.li_cloud, R.drawable.li_cloud),
        FOG(R.string.wx_fog, R.drawable.li_cloud_fog, R.drawable.li_cloud_fog),
        DRIZZLE(R.string.wx_drizzle, R.drawable.li_cloud_drizzle, R.drawable.li_cloud_drizzle),
        RAIN(R.string.wx_rain, R.drawable.li_cloud_rain, R.drawable.li_cloud_rain),
        HEAVY_RAIN(R.string.wx_heavy_rain, R.drawable.li_cloud_rain, R.drawable.li_cloud_rain),
        SHOWERS(R.string.wx_showers, R.drawable.li_cloud_rain, R.drawable.li_cloud_rain),
        SNOW(R.string.wx_snow, R.drawable.li_cloud_snow, R.drawable.li_cloud_snow),
        STORM(R.string.wx_storm, R.drawable.li_cloud_lightning, R.drawable.li_cloud_lightning);
        fun icon(day: Boolean) = if (day) iconDay else iconNight
    }

    const val MAX_AGE_MS = 30 * 60_000L

    fun cond(code: Int): Cond = when (code) {
        0 -> Cond.CLEAR
        1 -> Cond.MAINLY_CLEAR
        2 -> Cond.PARTLY
        3 -> Cond.CLOUDY
        45, 48 -> Cond.FOG
        in 51..57 -> Cond.DRIZZLE
        65, 67 -> Cond.HEAVY_RAIN
        in 61..66 -> Cond.RAIN
        in 71..77, 85, 86 -> Cond.SNOW
        in 80..82 -> Cond.SHOWERS
        95, 96, 99 -> Cond.STORM
        else -> Cond.CLOUDY
    }

    /** Coordinates go out rounded to 2 decimals (about 1 km): the weather needs no more, the server learns no street. */
    fun round2(x: Double) = Math.round(x * 100.0) / 100.0

    fun url(lat: Double, lon: Double) = String.format(Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", round2(lat), round2(lon))

    /** Null for anything that is not a full answer (missing fields, error body, junk). */
    fun parse(json: String?): Wx? = runCatching {
        val o = JSONObject(json ?: return null)
        val c = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val t = c.getDouble("temperature_2m")
        Wx(Math.round(t).toInt(), Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(), c.getInt("weather_code"), c.optInt("is_day", 1) != 0)
    }.getOrNull()

    /** Older than 30 min, or stamped in the future (clock set back): fetch again. */
    fun stale(fetchedAt: Long, now: Long) = fetchedAt <= 0 || fetchedAt > now || now - fetchedAt > MAX_AGE_MS

    fun deg(t: Int) = "$t°"
    fun hiLo(w: Wx) = "${deg(w.hi)} / ${deg(w.lo)}"

    /** "Quận 7" before a ward before the city; blank values are skipped. */
    fun pickPlace(subAdmin: String?, subLocality: String?, locality: String?) =
        listOf(subAdmin, subLocality, locality).firstOrNull { !it.isNullOrBlank() }?.trim()?.replace(Regex("^(Thành phố|Tỉnh) "), "").orEmpty()

    /** True when the place name of [a] still fits [b] (moved less than ~2 km): no new geocoding. */
    fun samePlace(aLat: Double, aLon: Double, bLat: Double, bLon: Double) =
        Math.abs(aLat - bLat) < 0.02 && Math.abs(aLon - bLon) < 0.02
}
