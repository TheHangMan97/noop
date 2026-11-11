package com.noop.ble

/**
 * The state of one timed MG ECG measurement, as the ECG screen draws it while the strap streams.
 *
 * Fed by the live revision-17 (filtered) packets the strap sends during a capture, one packet per strap
 * second. Holds the samples exactly as decoded, signed counts with no scaling, plus where each packet
 * begins, so the screen can mark packet boundaries without inventing a sample clock. Nothing here is
 * derived from the waveform: no rate, no rhythm, no classification (#752).
 *
 * Immutable and pure; [WhoopBleClient] swaps in a new value per packet and publishes it.
 */
data class EcgLiveSession(
    /** True from the start burst until the auto-stop, a manual stop, or the link dropping. */
    val running: Boolean = false,
    val startedAtMs: Long? = null,
    val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    val endedAtMs: Long? = null,
    val endReason: EndReason? = null,
    /** Every decoded sample of this measurement, in arrival order. */
    val samples: IntArray = IntArray(0),
    /** Index into [samples] where each received packet begins (a packet can carry zero samples). */
    val packetStarts: IntArray = IntArray(0),
    /** The strap's presence flag per packet. Not an electrode-contact or quality verdict. */
    val packetPresence: BooleanArray = BooleanArray(0),
) {
    enum class EndReason { COMPLETED, STOPPED, DISCONNECTED }

    val packets: Int get() = packetStarts.size

    /** Whole seconds left on the timer at [nowMs], never negative; 0 when not running. */
    fun remainingSeconds(nowMs: Long): Int {
        val start = startedAtMs ?: return 0
        if (!running) return 0
        val left = durationSeconds * 1000L - (nowMs - start)
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** A new measurement, empty and running from [nowMs]. */
    fun started(nowMs: Long, durationSeconds: Int): EcgLiveSession =
        EcgLiveSession(running = true, startedAtMs = nowMs, durationSeconds = durationSeconds)

    /** Append one packet's samples. Ignored once the measurement has ended, so a late packet adds nothing. */
    fun withPacket(packetSamples: List<Int>, presence: Boolean): EcgLiveSession {
        if (!running) return this
        val merged = samples.copyOf(samples.size + packetSamples.size)
        packetSamples.forEachIndexed { i, v -> merged[samples.size + i] = v }
        return copy(
            samples = merged,
            packetStarts = packetStarts + samples.size,
            packetPresence = packetPresence + presence,
        )
    }

    /** End the measurement at [nowMs] for [reason]; a second end keeps the first one's reason. */
    fun ended(nowMs: Long, reason: EndReason): EcgLiveSession =
        if (!running) this else copy(running = false, endedAtMs = nowMs, endReason = reason)

    // IntArray/BooleanArray members need structural equality, or every StateFlow emission compares unequal
    // by identity and two snapshots of the same state would never be recognised as the same.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcgLiveSession) return false
        return running == other.running && startedAtMs == other.startedAtMs &&
            durationSeconds == other.durationSeconds && endedAtMs == other.endedAtMs &&
            endReason == other.endReason && samples.contentEquals(other.samples) &&
            packetStarts.contentEquals(other.packetStarts) && packetPresence.contentEquals(other.packetPresence)
    }

    override fun hashCode(): Int {
        var h = running.hashCode()
        h = 31 * h + (startedAtMs?.hashCode() ?: 0)
        h = 31 * h + durationSeconds
        h = 31 * h + (endedAtMs?.hashCode() ?: 0)
        h = 31 * h + (endReason?.hashCode() ?: 0)
        h = 31 * h + samples.contentHashCode()
        h = 31 * h + packetStarts.contentHashCode()
        h = 31 * h + packetPresence.contentHashCode()
        return h
    }

    companion object {
        /** The measurement length the ECG screen offers. */
        const val DEFAULT_DURATION_SECONDS = 30
    }
}
