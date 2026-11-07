package dev.kidguard

import org.json.JSONArray
import org.json.JSONObject

/** Pure helpers of the "Thời khoá biểu" widget. Timetable = {"1":[subject…],…,"7":[…]} (ISO weekday, Mon=1). */
object TimetableLogic {
    const val MAX_DAY = 10
    const val MAX_LEN = 30
    const val EVENING = 17 * 60

    fun clean(l: List<String>): List<String> = l.map { it.trim().take(MAX_LEN).trim() }.filter { it.isNotEmpty() }.take(MAX_DAY)

    fun day(tt: JSONObject, dow: Int): List<String> {
        val a = tt.optJSONArray(dow.toString()) ?: return emptyList()
        return clean((0 until a.length()).map { a.optString(it, "") })
    }

    /** A copy of [tt] with [dow] replaced by the cleaned [l] (an empty day drops its key). */
    fun with(tt: JSONObject, dow: Int, l: List<String>): JSONObject {
        val o = JSONObject(tt.toString())
        val c = clean(l)
        if (c.isEmpty()) o.remove(dow.toString()) else o.put(dow.toString(), JSONArray(c))
        return o
    }

    fun isEmpty(tt: JSONObject) = (1..7).all { day(tt, it).isEmpty() }
    fun tomorrow(dow: Int) = dow % 7 + 1
    fun distinct(l: List<String>): List<String> = l.distinctBy { it.lowercase() }
    /** After 17:00 the widget leads with tomorrow's bag. */
    fun eveningFirst(minute: Int) = minute >= EVENING
}
