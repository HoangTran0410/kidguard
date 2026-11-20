package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class MediaBusyTest {
    @Test fun clipBlocksCallsAndListen() {
        for (me in listOf("call", "listencall", "lancall", "cam", "listen"))
            assertTrue(me, MediaBusy.busyOf(me, call = false, listenCall = false, cam = false, listen = false, clip = true))
    }
    @Test fun ownSessionIsNotBusy() {
        assertFalse(MediaBusy.busyOf("clip", call = false, listenCall = false, cam = false, listen = false, clip = true))
        assertFalse(MediaBusy.busyOf("listen", call = false, listenCall = false, cam = false, listen = true, clip = false))
    }
    @Test fun aCallBlocksTheOthers() {
        for (me in listOf("listencall", "lancall", "cam", "clip", "listen"))
            assertTrue(me, MediaBusy.busyOf(me, call = true, listenCall = false, cam = false, listen = false, clip = false))
    }
    @Test fun idleIsFree() {
        assertFalse(MediaBusy.busyOf("call", false, false, false, false, false))
    }
}
