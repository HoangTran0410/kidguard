package dev.kidguard

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NotifHoldTest {
    private fun at(d: Int, h: Int, m: Int = 0) = Calendar.getInstance().apply { clear(); set(2026, 10, d, h, m) }.timeInMillis

    @Test fun nextSlotSameDayNextDayAndEdge() {
        assertEquals(at(12, 8), NotifHold.nextSlot(at(12, 1)))
        assertEquals(at(12, 12), NotifHold.nextSlot(at(12, 9, 30)))
        assertEquals(at(12, 18), NotifHold.nextSlot(at(12, 11, 59)))      // under a minute before 12:00 waits for 18:00
        assertEquals(at(13, 8), NotifHold.nextSlot(at(12, 18, 5)))
        assertEquals(at(13, 8), NotifHold.nextSlot(at(12, 23, 59)))
    }

    @Test fun nextAtTodayOrTomorrow() {
        assertEquals(at(13, 7), NotifHold.nextAt(at(12, 21), 7))
        assertEquals(at(12, 7), NotifHold.nextAt(at(12, 2), 7))
    }

    @Test fun deliverAtByMode() {
        val now = at(12, 10)
        assertEquals(0L, NotifHold.deliverAt(null, now))
        assertEquals(at(12, 12), NotifHold.deliverAt(NotifHold.Hold(NotifHold.BUNDLE), now))
        assertEquals(at(12, 11), NotifHold.deliverAt(NotifHold.Hold(NotifHold.MUTE, at(12, 11)), now))
        assertEquals(0L, NotifHold.deliverAt(NotifHold.Hold(NotifHold.MUTE, at(12, 9)), now))   // mute over
    }

    @Test fun repostAtItsTimePassesEarlierIsHeldAgain() {
        val h = NotifHold.Hold(NotifHold.BUNDLE)
        val sn = mapOf("k" to at(12, 12))
        assertEquals(0L, NotifHold.decide("k", at(12, 12), h, sn))                 // the system's delivery
        assertEquals(0L, NotifHold.decide("k", at(12, 12) - 30_000, h, sn))        // a little early still counts
        assertEquals(at(12, 12), NotifHold.decide("k", at(12, 10), h, sn))         // the app re-posted it before: hold again
        assertEquals(at(12, 18), NotifHold.decide("k2", at(12, 12, 5), h, sn))     // a new one after delivery waits for the next slot
        assertEquals(0L, NotifHold.decide("k3", at(12, 10), null, sn))
    }

    @Test fun neverHoldsCallsAlarmsOngoingMediaOrOurs() {
        assertTrue(NotifHold.holdable("com.zing.zalo", Notification.CATEGORY_MESSAGE, 0, false, false))
        assertTrue(NotifHold.holdable("com.google.android.gm", null, 0, false, false))
        assertFalse(NotifHold.holdable("com.zing.zalo", Notification.CATEGORY_CALL, 0, false, false))
        assertFalse(NotifHold.holdable("x", Notification.CATEGORY_ALARM, 0, false, false))
        assertFalse(NotifHold.holdable("x", null, Notification.FLAG_ONGOING_EVENT, false, false))
        assertFalse(NotifHold.holdable("x", null, Notification.FLAG_FOREGROUND_SERVICE, false, false))
        assertFalse(NotifHold.holdable("x", null, 0, true, false))     // full-screen intent
        assertFalse(NotifHold.holdable("x", null, 0, false, true))     // media
        assertFalse(NotifHold.holdable("dev.kidguard", null, 0, false, false))
        assertFalse(NotifHold.holdable("dev.kidguard.parent", null, 0, false, false))
    }

    @Test fun jsonRoundTripAndEndedMutesDropped() {
        val m = mapOf("a" to NotifHold.Hold(NotifHold.BUNDLE), "b" to NotifHold.Hold(NotifHold.MUTE, 500))
        assertEquals(m, NotifHold.fromJson(NotifHold.toJson(m)))
        assertEquals(emptyMap<String, NotifHold.Hold>(), NotifHold.fromJson("""{"x":{"m":"other"}}"""))
        assertEquals(emptyMap<String, NotifHold.Hold>(), NotifHold.fromJson("not json"))
        assertEquals(setOf("a"), NotifHold.live(m, 600).keys)
    }

    @Test fun snoozedReasonMapsToHold() {
        assertEquals(NotifLogic.R_HOLD, NotifLogic.reason(18))
    }
}
