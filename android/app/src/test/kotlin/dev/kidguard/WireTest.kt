package dev.kidguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WireTest {
    @Test fun decodesCodesKeepsFields() {
        val c = Wire.decode(JSONObject("""{"type":"lk","id":"x1","by":"Mom"}"""))
        assertEquals("lock", c.getString("type")); assertEquals("x1", c.getString("id")); assertEquals("Mom", c.getString("by"))
        assertEquals("smsPull", Wire.decode(JSONObject("""{"type":"sp","fid":"0123456789abcdef"}""")).getString("type"))
    }
    @Test fun namesAndUnknownPassThrough() {
        // an older worker still sends names; an unknown code reaches Guard, which answers "unknown"
        assertEquals("lock", Wire.decode(JSONObject("""{"type":"lock"}""")).getString("type"))
        assertEquals("zz", Wire.decode(JSONObject("""{"type":"zz"}""")).getString("type"))
    }
    @Test fun codesUnique() { assertEquals(Wire.CODES.size, Wire.CODES.values.toSet().size) }
}
