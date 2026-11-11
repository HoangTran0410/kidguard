package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StateDiffTest {
    private fun st(t: Long, bat: Int, apps: List<Pair<String, Long>>, trk: List<Long>, extra: Map<String, Any> = emptyMap()) = JSONObject().apply {
        put("t", t); put("battery", bat)
        put("apps", JSONArray().apply { apps.forEach { (p, ms) -> put(JSONObject().put("pkg", p).put("ms", ms)) } })
        put("track", JSONArray().apply { trk.forEach { put(JSONArray().put(it).put(1.0).put(2.0).put(5)) } })
        extra.forEach { (k, v) -> put(k, v) }
    }

    @Test fun firstAndWholeAreTheState() {
        val d = StateDiff(); val s = st(1, 50, listOf("a" to 1L), listOf(10))
        val (out, snap) = d.build(s, whole = false); assertSame(s, out); d.commit(snap)
        assertSame(s, d.build(s, whole = true).first)
    }

    @Test fun onlyChangesGo() {
        val d = StateDiff()
        d.commit(d.build(st(1, 50, listOf("a" to 1L, "b" to 2L, "c" to 0L), listOf(10, 20), mapOf("x" to 1)), false).second)
        val out = d.build(st(2, 50, listOf("a" to 5L, "b" to 2L, "d" to 0L), listOf(10, 20, 30)), false).first
        assertEquals(1, out.getInt("_part")); assertEquals(2L, out.getLong("t"))
        assertFalse(out.has("battery")); assertFalse(out.has("track"))
        assertEquals("[\"x\"]", out.getJSONArray("_drop").toString())
        val a = out.getJSONObject("_apps")
        assertEquals(setOf("a", "d"), (0 until a.getJSONArray("set").length()).map { a.getJSONArray("set").getJSONObject(it).getString("pkg") }.toSet())
        assertEquals("[\"c\"]", a.getJSONArray("del").toString())
        val tk = out.getJSONObject("_trk"); assertEquals(10L, tk.getLong("from")); assertEquals(1, tk.getJSONArray("add").length())
        assertEquals(30L, tk.getJSONArray("add").getJSONArray(0).getLong(0))
    }

    @Test fun trackAgesOutAndClears() {
        val d = StateDiff(); d.commit(d.build(st(1, 50, emptyList(), listOf(10, 20)), false).second)
        val aged = d.build(st(1, 50, emptyList(), listOf(20)), false).first.getJSONObject("_trk")   // day rolled: same last, new from
        assertEquals(20L, aged.getLong("from")); assertEquals(0, aged.getJSONArray("add").length())
        val cleared = d.build(st(1, 50, emptyList(), emptyList()), false).first
        assertTrue(cleared.has("track")); assertEquals(0, cleared.getJSONArray("track").length())
        val unchanged = d.build(st(1, 50, emptyList(), listOf(10, 20)), false).first
        assertFalse(unchanged.has("_trk") || unchanged.has("track") || unchanged.has("_apps") || unchanged.has("t"))
    }

    @Test fun resetSendsWhole() {
        val d = StateDiff(); val s = st(1, 50, emptyList(), emptyList())
        d.commit(d.build(s, false).second); d.reset(); assertSame(s, d.build(s, false).first)
    }
}
