package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class WeatherLogicTest {
    private val json = """{"latitude":10.75,"current":{"time":"2026-11-15T10:00","temperature_2m":31.6,"weather_code":3,"is_day":1},
        "daily":{"time":["2026-11-15"],"temperature_2m_max":[32.4],"temperature_2m_min":[24.5]}}"""

    @Test fun parsesCurrentAndDaily() {
        val w = WeatherLogic.parse(json)!!
        assertEquals(32, w.temp); assertEquals(32, w.hi); assertEquals(25, w.lo); assertEquals(3, w.code); assertTrue(w.day)
        assertEquals("32° / 25°", WeatherLogic.hiLo(w))
    }
    @Test fun nightFlagAndNegativeTemp() {
        val w = WeatherLogic.parse(json.replace("31.6", "-2.4").replace("\"is_day\":1", "\"is_day\":0"))!!
        assertEquals(-2, w.temp); assertFalse(w.day)
    }
    @Test fun junkIsNull() {
        assertNull(WeatherLogic.parse(null)); assertNull(WeatherLogic.parse("")); assertNull(WeatherLogic.parse("{\"error\":true}"))
        assertNull(WeatherLogic.parse("""{"current":{"temperature_2m":1},"daily":{}}"""))
    }
    @Test fun wmoGroups() {
        assertEquals(WeatherLogic.Cond.CLEAR, WeatherLogic.cond(0))
        assertEquals(WeatherLogic.Cond.MAINLY_CLEAR, WeatherLogic.cond(1))
        assertEquals(WeatherLogic.Cond.PARTLY, WeatherLogic.cond(2))
        assertEquals(WeatherLogic.Cond.CLOUDY, WeatherLogic.cond(3))
        assertEquals(WeatherLogic.Cond.FOG, WeatherLogic.cond(45))
        assertEquals(WeatherLogic.Cond.DRIZZLE, WeatherLogic.cond(53))
        assertEquals(WeatherLogic.Cond.RAIN, WeatherLogic.cond(61))
        assertEquals(WeatherLogic.Cond.HEAVY_RAIN, WeatherLogic.cond(65))
        assertEquals(WeatherLogic.Cond.SNOW, WeatherLogic.cond(73))
        assertEquals(WeatherLogic.Cond.SHOWERS, WeatherLogic.cond(81))
        assertEquals(WeatherLogic.Cond.STORM, WeatherLogic.cond(95))
        assertEquals(WeatherLogic.Cond.CLOUDY, WeatherLogic.cond(999))
    }
    @Test fun iconByDayNight() {
        assertEquals(R.drawable.li_sun, WeatherLogic.Cond.CLEAR.icon(true))
        assertEquals(R.drawable.li_moon, WeatherLogic.Cond.CLEAR.icon(false))
    }
    @Test fun stalenessIs30MinAndClockBackIsStale() {
        val now = 10_000_000L
        assertFalse(WeatherLogic.stale(now - 29 * 60_000L, now))
        assertTrue(WeatherLogic.stale(now - 31 * 60_000L, now))
        assertTrue(WeatherLogic.stale(0, now)); assertTrue(WeatherLogic.stale(now + 5_000, now))
    }
    @Test fun urlRoundsCoordinates() {
        val u = WeatherLogic.url(10.738654, 106.721987)
        assertTrue(u.contains("latitude=10.74&longitude=106.72&")); assertTrue(u.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(u.contains("forecast_days=1"))
    }
    @Test fun placePrefersDistrict() {
        assertEquals("Quận 7", WeatherLogic.pickPlace("Quận 7", "Tân Phong", "Hồ Chí Minh"))
        assertEquals("Tân Phong", WeatherLogic.pickPlace(null, "Tân Phong", "Hồ Chí Minh"))
        assertEquals("Hà Nội", WeatherLogic.pickPlace(" ", null, "Hà Nội"))
        assertEquals("", WeatherLogic.pickPlace(null, null, null))
        assertEquals("Hồ Chí Minh", WeatherLogic.pickPlace(null, null, "Thành phố Hồ Chí Minh"))
    }
    @Test fun samePlaceWithin2Km() {
        assertTrue(WeatherLogic.samePlace(10.74, 106.72, 10.75, 106.73)); assertFalse(WeatherLogic.samePlace(10.74, 106.72, 10.80, 106.72))
    }
}
