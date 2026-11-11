package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A DIS read Android did not accept is retried on the link, boundedly and quietly, never dropped. */
class DisReadRetryTest {

    @Test
    fun `an unaccepted read is retried until the attempts are spent`() {
        for (made in 1 until DIS_READ_ISSUE_ATTEMPTS) {
            assertEquals(DIS_READ_RETRY_DELAY_MS, disReadRetryDelayMs(made))
        }
        assertNull(disReadRetryDelayMs(DIS_READ_ISSUE_ATTEMPTS))
        assertNull(disReadRetryDelayMs(DIS_READ_ISSUE_ATTEMPTS + 1))
    }

    @Test
    fun `the retry window outlasts an ordinary offload burst but is bounded`() {
        // The field capture: the serial read was due ~3 s after the handshake, inside an offload that ran
        // for several more seconds. Half a minute is the floor; it must still end.
        val windowMs = DIS_READ_RETRY_DELAY_MS * (DIS_READ_ISSUE_ATTEMPTS - 1)
        assertTrue(windowMs >= 30_000)
        assertTrue(windowMs <= 120_000)
    }

    @Test
    fun `only the first and the final unaccepted attempt are logged`() {
        assertTrue(shouldLogDisReadNotAccepted(1))
        for (made in 2 until DIS_READ_ISSUE_ATTEMPTS) assertFalse(shouldLogDisReadNotAccepted(made))
        assertTrue(shouldLogDisReadNotAccepted(DIS_READ_ISSUE_ATTEMPTS))
    }

    @Test
    fun `the lines say what was observed and nothing more`() {
        val first = disReadNotAcceptedLine("serial", 1)
        assertTrue(first.startsWith("DIS: serial read not accepted"))
        assertTrue(first.contains("readCharacteristic returned false"))
        assertTrue(first.contains("retrying"))
        // Not a strap refusal, and no cause the stack never reported.
        assertFalse(first.contains("declined"))
        assertFalse(first.contains("busy"))

        val last = disReadNotAcceptedLine("serial", DIS_READ_ISSUE_ATTEMPTS)
        assertTrue(last.contains("giving up on this link"))
        assertTrue(last.contains("$DIS_READ_ISSUE_ATTEMPTS attempts"))
        assertFalse(last.contains("retrying"))
    }
}
