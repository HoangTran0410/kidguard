package dev.kidguard

import dev.kidguard.DeviceLogic.AppSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLogicTest {
    @Test fun topPicksBiggestAndDropsEmpty() {
        val all = (1..30).map { AppSize("p$it", "App $it", it * 10L, it.toLong(), 1) } + AppSize("zero", "Zero", 0, 0, 0)
        val top = DeviceLogic.topBySize(all)
        assertEquals(15, top.size)
        assertEquals("p30", top.first().pkg)
        assertEquals("p16", top.last().pkg)
        assertTrue(top.none { it.pkg == "zero" })
        // ties: by label, case-insensitive
        val tie = DeviceLogic.topBySize(listOf(AppSize("b", "beta", 5, 0, 0), AppSize("a", "Alpha", 5, 0, 0)), 2)
        assertEquals(listOf("a", "b"), tie.map { it.pkg })
        assertEquals(3, DeviceLogic.topBySize(all.take(3)).size)
    }

    @Test fun batteryFormatting() {
        assertEquals(34.5, DeviceLogic.tempC(345)!!, 0.0)
        assertEquals(-2.0, DeviceLogic.tempC(-20)!!, 0.0)
        assertNull(DeviceLogic.tempC(Int.MIN_VALUE))
        assertEquals(81, DeviceLogic.pct(81, 100))
        assertEquals(50, DeviceLogic.pct(127, 255))
        assertEquals(-1, DeviceLogic.pct(-1, 100))
        assertEquals("good", DeviceLogic.healthName(2))
        assertEquals("overheat", DeviceLogic.healthName(3))
        assertEquals("unknown", DeviceLogic.healthName(1))
        assertEquals("usb", DeviceLogic.plugName(2))
        assertEquals("none", DeviceLogic.plugName(0))
    }

    @Test fun wifiBars() {
        assertEquals(4, DeviceLogic.wifiBars(-50))
        assertEquals(3, DeviceLogic.wifiBars(-60))
        assertEquals(0, DeviceLogic.wifiBars(-100))
        assertNull(DeviceLogic.wifiBars(-127 - 1))
        assertNull(DeviceLogic.wifiBars(null))
        assertNull(DeviceLogic.wifiBars(Int.MIN_VALUE))
    }

    @Test fun screenOnFromEvents() {
        val from = 0L; val now = 10_000L
        assertEquals(10_000, DeviceLogic.screenOnMs(emptyList(), from, now, true))
        assertEquals(0, DeviceLogic.screenOnMs(emptyList(), from, now, false))
        // on 1000-3000, on 5000-now
        assertEquals(7_000, DeviceLogic.screenOnMs(listOf(15 to 1000L, 16 to 3000L, 15 to 5000L), from, now, true))
        // first event "off": it was on since midnight
        assertEquals(2_000 + 1_000, DeviceLogic.screenOnMs(listOf(16 to 2000L, 15 to 8000L, 16 to 9000L), from, now, false))
        // repeated "on" does not double count, other event types ignored
        assertEquals(4_000, DeviceLogic.screenOnMs(listOf(15 to 1000L, 1 to 2000L, 15 to 3000L, 16 to 5000L), from, now, false))
    }

    @Test fun jsonShape() {
        val s = DeviceLogic.Snapshot(1L, 27, "8.1.0", "OPPO A3s", "0.9.5", 5000, 32L shl 30, 3L shl 30,
            listOf(AppSize("a", "A".repeat(60), 1, 2, 3)),
            DeviceLogic.Ram(3L shl 30, 1L shl 30, 200L shl 20, false, 0, false),
            DeviceLogic.Battery(81, true, "usb", 34.5, "good", 4100, null),
            DeviceLogic.Net("wifi", -60, false), false)
        val j = DeviceLogic.json(s)
        assertEquals(27, j.getInt("sdk"))
        val st = j.getJSONObject("storage")
        assertEquals(3L shl 30, st.getLong("free"))
        val a = st.getJSONArray("apps").getJSONObject(0)
        assertEquals(40, a.getString("label").length)
        assertEquals(3, a.getLong("cache"))
        assertFalse(j.getJSONObject("ram").getBoolean("perApp"))
        assertTrue(j.getJSONObject("battery").isNull("screenOnMs"))
        assertEquals(34.5, j.getJSONObject("battery").getDouble("tempC"), 0.0)
        assertEquals(3, j.getJSONObject("net").getInt("bars"))
        assertFalse(j.getBoolean("clearData"))
        // no usage access: apps null, not an empty list
        assertTrue(DeviceLogic.json(s.copy(apps = null)).getJSONObject("storage").isNull("apps"))
    }
}
