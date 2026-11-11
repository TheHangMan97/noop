package com.noop.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timed ECG measurement's live state: countdown, packet bookkeeping, and how it ends. */
class EcgLiveSessionTest {

    private val t0 = 1_000_000L

    @Test
    fun `a new measurement runs empty for its duration`() {
        val s = EcgLiveSession().started(t0, 30)
        assertTrue(s.running)
        assertEquals(0, s.packets)
        assertEquals(30, s.remainingSeconds(t0))
        assertEquals(30, s.remainingSeconds(t0 + 1))        // rounds up, so it never shows 29 at the start
        assertEquals(1, s.remainingSeconds(t0 + 29_500))
        assertEquals(0, s.remainingSeconds(t0 + 30_000))
        assertEquals(0, s.remainingSeconds(t0 + 90_000))     // never negative
    }

    @Test
    fun `packets append in order and mark where each begins`() {
        val s = EcgLiveSession().started(t0, 30)
            .withPacket(emptyList(), presence = false)        // the strap's opening packet carries no samples
            .withPacket(listOf(1, -2, 3), presence = true)
            .withPacket(listOf(4), presence = true)
        assertArrayEquals(intArrayOf(1, -2, 3, 4), s.samples)
        assertArrayEquals(intArrayOf(0, 0, 3), s.packetStarts)
        assertEquals(listOf(false, true, true), s.packetPresence.toList())
        assertEquals(3, s.packets)
    }

    @Test
    fun `a packet after the end adds nothing`() {
        val ended = EcgLiveSession().started(t0, 30).withPacket(listOf(7), true)
            .ended(t0 + 30_000, EcgLiveSession.EndReason.COMPLETED)
        assertSame(ended, ended.withPacket(listOf(8, 9), true))
        assertEquals(0, ended.remainingSeconds(t0 + 1_000))
    }

    @Test
    fun `the first end wins`() {
        val stopped = EcgLiveSession().started(t0, 30).ended(t0 + 5_000, EcgLiveSession.EndReason.STOPPED)
        val again = stopped.ended(t0 + 6_000, EcgLiveSession.EndReason.DISCONNECTED)
        assertFalse(again.running)
        assertEquals(EcgLiveSession.EndReason.STOPPED, again.endReason)
        assertEquals(t0 + 5_000, again.endedAtMs)
    }

    @Test
    fun `starting again clears the previous measurement`() {
        val old = EcgLiveSession().started(t0, 30).withPacket(listOf(1, 2), true)
            .ended(t0 + 30_000, EcgLiveSession.EndReason.COMPLETED)
        val fresh = old.started(t0 + 60_000, 30)
        assertTrue(fresh.running)
        assertEquals(0, fresh.samples.size)
        assertEquals(null, fresh.endReason)
    }

    @Test
    fun `equality is by content, so identical snapshots compare equal`() {
        val a = EcgLiveSession().started(t0, 30).withPacket(listOf(1, 2), true)
        val b = EcgLiveSession().started(t0, 30).withPacket(listOf(1, 2), true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.withPacket(listOf(3), false))
    }
}
