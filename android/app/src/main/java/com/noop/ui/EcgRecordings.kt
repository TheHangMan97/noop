package com.noop.ui

import com.noop.data.EcgWaveformSampleEntity
import com.noop.protocol.LabradorR16
import com.noop.protocol.Whoop5Ecg

/**
 * Pure helpers behind the MG ECG recordings viewer ([EcgRecordingsScreen]).
 *
 * The viewer reads the flash-banked R16 records `ecgWaveformSample` already keeps (#2847) and draws them.
 * It shows the strap's samples as they are and derives nothing from them: no rate, no rhythm, no
 * interval, no classification (#752). Every number this file produces is a COUNT or an INDEX of what was
 * stored (records, samples, sequence gaps, flags set), never a physiological value.
 *
 * Units stay native on purpose. `docs/PROTOCOL_ECG.md` establishes the sample coding (signed 18-bit) but
 * not volts per count and not the sample rate, so amplitudes are "counts" and the time axis is the
 * records' own wall-clock second (`ts`, one record per strap-second) plus a sample index inside each
 * record, never a sample clock.
 *
 * Android-only for now: the Apple app has no viewer yet. Nothing here is analytics or stored data, so the
 * byte-identical parity contract does not bind it; an Apple twin should mirror the reading boundary
 * ([READING_GAP_SECONDS]) and the CSV columns ([CSV_COLUMNS]) so exports from both platforms line up.
 */
object EcgRecordings {

    /** A new reading starts when two consecutive records are more than this many seconds apart. */
    const val READING_GAP_SECONDS = 10L

    /** Rows fetched per DAO page. A row is 1,572 bytes, so a page stays well inside a CursorWindow. */
    const val PAGE_ROWS = 200

    /** Upper bound on rows read in one pass: the table's own per-device retention cap plus headroom. */
    const val MAX_ROWS = 40_000

    /** The CSV header row, in column order. */
    const val CSV_COLUMNS =
        "reading_offset_s,record_ts_unix,record_sequence,sample_index,value_counts,flag6,flag7,presence_flag,quality_code"

    /** What the list needs from one stored record, without keeping its 1,572 bytes. */
    data class RecordSummary(
        val ts: Long,
        val sequence: Long,
        /** Declared sample count, or 0 when the record did not decode. */
        val sampleCount: Int,
        /** The strap's presence flag (status byte bit 3). Not an electrode-contact or quality verdict. */
        val presence: Boolean,
        /** False when [Whoop5Ecg.parseR16] refused the stored bytes. */
        val decodable: Boolean,
    )

    /** One reading: a run of records with no gap above [READING_GAP_SECONDS] and no sequence reset. */
    data class Reading(
        val startTs: Long,
        val endTs: Long,
        val records: Int,
        val samples: Long,
        /** Records whose presence flag was set. */
        val presenceRecords: Int,
        /** Sequence numbers skipped inside the reading (records the strap numbered but we never stored). */
        val missingRecords: Long,
        /** Stored rows that did not decode as R16. */
        val undecodableRecords: Int,
    ) {
        /** Wall-clock span from the first record's second to the last one's, inclusive. */
        val spanSeconds: Long get() = endTs - startTs + 1
    }

    /** One decoded record with the wall-clock second it was stored under. */
    data class TimedRecord(val ts: Long, val record: LabradorR16)

    /** Stored bytes to the decoder's integer view. */
    fun decode(row: EcgWaveformSampleEntity): LabradorR16? =
        Whoop5Ecg.parseR16(row.record.map { it.toInt() and 0xFF })

    fun summarize(row: EcgWaveformSampleEntity): RecordSummary {
        val r = decode(row)
        return RecordSummary(
            ts = row.ts,
            sequence = row.sequence,
            sampleCount = r?.sampleCount ?: 0,
            presence = r?.presence ?: false,
            decodable = r != null,
        )
    }

    /**
     * Split records into readings, newest reading first.
     *
     * A boundary falls where the next record's `ts` is more than [maxGapSeconds] after the previous one,
     * or where its sequence does not move forward (the strap restarted its numbering). Inside a reading a
     * forward jump of more than one counts the skipped numbers as missing records.
     */
    fun groupReadings(
        records: List<RecordSummary>,
        maxGapSeconds: Long = READING_GAP_SECONDS,
    ): List<Reading> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedWith(compareBy<RecordSummary> { it.ts }.thenBy { it.sequence })
        val out = ArrayList<Reading>()
        var start = 0
        for (i in 1..sorted.size) {
            val boundary = i == sorted.size ||
                sorted[i].ts - sorted[i - 1].ts > maxGapSeconds ||
                sorted[i].sequence <= sorted[i - 1].sequence
            if (boundary) {
                out.add(readingOf(sorted.subList(start, i)))
                start = i
            }
        }
        return out.sortedByDescending { it.startTs }
    }

    private fun readingOf(run: List<RecordSummary>): Reading {
        var missing = 0L
        for (i in 1 until run.size) {
            val step = run[i].sequence - run[i - 1].sequence
            if (step > 1) missing += step - 1
        }
        return Reading(
            startTs = run.first().ts,
            endTs = run.last().ts,
            records = run.size,
            samples = run.sumOf { it.sampleCount.toLong() },
            presenceRecords = run.count { it.presence },
            missingRecords = missing,
            undecodableRecords = run.count { !it.decodable },
        )
    }

    /**
     * Read every row in `[from, to]` through a `(from, limit)`-paged, ts-ascending source.
     *
     * The DAO pages by `ts >= from`, and several records can share one second at the start of a reading
     * (the reason the table's key includes `sequence`), so each next page starts AT the last second seen
     * and skips the (ts, sequence) pairs already returned. When a page holds nothing new, that one second
     * has at least a page of rows, so the page is widened (doubling, up to [maxRows]) rather than stepping
     * past rows it never returned. Stops at [maxRows].
     */
    suspend fun <T> readAll(
        from: Long,
        to: Long,
        pageSize: Int = PAGE_ROWS,
        maxRows: Int = MAX_ROWS,
        ts: (T) -> Long,
        sequence: (T) -> Long,
        fetch: suspend (from: Long, limit: Int) -> List<T>,
    ): List<T> {
        val out = ArrayList<T>()
        var cursor = from
        var limit = pageSize.coerceAtLeast(1)
        var lastTs = Long.MIN_VALUE
        val seenAtLast = HashSet<Long>()
        while (out.size < maxRows && cursor <= to) {
            val page = fetch(cursor, limit)
            var added = 0
            for (row in page) {
                val t = ts(row)
                if (t < cursor || t > to) continue
                if (t == lastTs && sequence(row) in seenAtLast) continue
                if (t != lastTs) { lastTs = t; seenAtLast.clear() }
                seenAtLast.add(sequence(row))
                out.add(row)
                added++
                if (out.size >= maxRows) break
            }
            if (page.size < limit) break
            if (added > 0) {
                cursor = lastTs
                limit = pageSize.coerceAtLeast(1)
            } else if (limit < maxRows) {
                limit = (limit.toLong() * 2).coerceAtMost(maxRows.toLong()).toInt()
            } else {
                // A single second holding more rows than the whole cap: nothing further can be reached.
                break
            }
        }
        return out
    }

    // ---- Paper-strip layout -------------------------------------------------------------------------

    /** Slots per second in a stored reading's strip: one R16 record's capacity, one record per second. */
    const val STRIP_SLOTS_STORED = Whoop5Ecg.R16_MAX_SAMPLES

    /** Slots per second in the live strip: one R17 packet's capacity, one packet per second. */
    const val STRIP_SLOTS_LIVE = Whoop5Ecg.R17_MAX_SAMPLES

    /** Seconds per printed row, as on a 30 s ECG print-out. */
    const val STRIP_SECONDS_PER_ROW = 10

    /** Display filter: baseline window, in slots (about one second of a stored reading). */
    const val DISPLAY_BASELINE_WINDOW = 501

    /** Display filter: smoothing window, in slots (a few milliseconds of a stored reading). */
    const val DISPLAY_SMOOTH_WINDOW = 5

    /**
     * A reading laid out on a time axis of whole seconds, [slotsPerSecond] slots each. NaN marks a slot
     * with no sample: a second the strap numbered but we never stored, or the unused tail of a short
     * record. The axis comes from the records' own second stamps; inside a second, samples are placed in
     * order from the second's start, one per slot.
     */
    class Strip(val values: FloatArray, val slotsPerSecond: Int) {
        val seconds: Int get() = if (slotsPerSecond == 0) 0 else values.size / slotsPerSecond
    }

    /**
     * Lay out [segments] (second index, samples) on a [Strip]. Segments sharing a second continue where the
     * previous one stopped, as the opening records of a reading can; samples past the end are dropped.
     */
    fun strip(segments: List<Pair<Int, List<Int>>>, slotsPerSecond: Int): Strip {
        if (segments.isEmpty() || slotsPerSecond <= 0) return Strip(FloatArray(0), slotsPerSecond)
        val seconds = segments.maxOf { it.first } + 1
        val values = FloatArray(seconds * slotsPerSecond) { Float.NaN }
        val cursor = HashMap<Int, Int>()
        for ((second, samples) in segments) {
            if (second < 0) continue
            var at = second * slotsPerSecond + (cursor[second] ?: 0)
            for (v in samples) {
                if (at >= values.size) break
                values[at++] = v.toFloat()
            }
            cursor[second] = at - second * slotsPerSecond
        }
        return Strip(values, slotsPerSecond)
    }

    /**
     * A stored reading's strip: each record at its second offset from the strip's first record.
     *
     * Leading records shorter than a full second are left out. They are the front end's opening (a reading
     * starts with records declaring 0, then a few hundred samples, before the first full 500), where it is
     * not known which part of the second they cover and the signal is still settling. The CSV keeps them.
     */
    fun strip(records: List<TimedRecord>): Strip {
        val sorted = records.sortedWith(compareBy<TimedRecord> { it.ts }.thenBy { it.record.sequence })
            .dropWhile { it.record.samples.size < STRIP_SLOTS_STORED }
        val first = sorted.firstOrNull()?.ts ?: return Strip(FloatArray(0), STRIP_SLOTS_STORED)
        return strip(sorted.map { (it.ts - first).toInt() to it.record.samples }, STRIP_SLOTS_STORED)
    }

    /**
     * A DISPLAY filter, for drawing only: subtract a centred moving average of [baselineWindow] slots (the
     * slow wander of the raw signal), then smooth with a centred moving average of [smoothWindow] slots,
     * applied twice (a triangular kernel, which suppresses the high-frequency hiss better than one pass).
     * Each run of samples between gaps is filtered on its own, and gaps stay NaN. Nothing is measured from
     * the result and the export stays raw.
     */
    fun displayFiltered(
        values: FloatArray,
        baselineWindow: Int = DISPLAY_BASELINE_WINDOW,
        smoothWindow: Int = DISPLAY_SMOOTH_WINDOW,
    ): FloatArray {
        val out = FloatArray(values.size) { Float.NaN }
        var i = 0
        while (i < values.size) {
            if (values[i].isNaN()) { i++; continue }
            var j = i
            while (j < values.size && !values[j].isNaN()) j++
            val run = values.copyOfRange(i, j)
            val base = movingAverage(run, baselineWindow)
            val detrended = FloatArray(run.size) { run[it] - base[it] }
            movingAverage(movingAverage(detrended, smoothWindow), smoothWindow).copyInto(out, i)
            i = j
        }
        return out
    }

    /** Centred moving average, the window shrinking at the edges; a window of 1 or less returns a copy. */
    fun movingAverage(x: FloatArray, window: Int): FloatArray {
        if (window <= 1 || x.isEmpty()) return x.copyOf()
        val half = window / 2
        val prefix = DoubleArray(x.size + 1)
        for (k in x.indices) prefix[k + 1] = prefix[k] + x[k]
        return FloatArray(x.size) { k ->
            val lo = maxOf(0, k - half)
            val hi = minOf(x.size, k + half + 1)
            ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
        }
    }

    /**
     * The vertical range for a whole strip: the [tail] and `1 - tail` quantiles of its samples, widened by
     * [pad] of the span each way. One range for every row, as on paper, so rows compare. Ignores NaN; an
     * empty or flat strip gets a small range around its value.
     */
    fun stripRange(values: FloatArray, tail: Double = 0.001, pad: Float = 0.1f): ClosedFloatingPointRange<Float> {
        val finite = values.filter { !it.isNaN() }.sorted()
        if (finite.isEmpty()) return -1f..1f
        val last = finite.size - 1
        val lo = finite[(last * tail).toInt()]
        val hi = finite[last - (last * tail).toInt()]
        if (hi <= lo) return (lo - 1f)..(hi + 1f)
        val p = (hi - lo) * pad
        return (lo - p)..(hi + p)
    }

    /**
     * Min and max of `values[from, to)` per drawing column, NaN where a column holds no sample. A column
     * narrower than a slot repeats the slot it falls in, so a short span still draws as a line.
     */
    fun columnEnvelope(values: FloatArray, from: Int, to: Int, columns: Int): Pair<FloatArray, FloatArray> {
        val lo = from.coerceIn(0, values.size)
        val hi = to.coerceIn(lo, values.size)
        val n = hi - lo
        val mins = FloatArray(maxOf(columns, 0)) { Float.NaN }
        val maxs = FloatArray(maxOf(columns, 0)) { Float.NaN }
        if (n == 0 || columns <= 0) return mins to maxs
        for (c in 0 until columns) {
            val a = lo + ((c.toLong() * n) / columns).toInt()
            val b = maxOf(a + 1, lo + (((c + 1).toLong() * n) / columns).toInt())
            var mn = Float.NaN
            var mx = Float.NaN
            for (k in a until minOf(b, hi)) {
                val v = values[k]
                if (v.isNaN()) continue
                if (mn.isNaN() || v < mn) mn = v
                if (mx.isNaN() || v > mx) mx = v
            }
            mins[c] = mn
            maxs[c] = mx
        }
        return mins to maxs
    }

    /**
     * Write a reading as CSV, one line per sample, after a commented header that states what the numbers
     * are and are not. Columns are [CSV_COLUMNS]. `reading_offset_s` is the record's second minus the
     * reading's first second; `sample_index` counts from 0 inside each record.
     */
    fun writeCsv(out: Appendable, records: List<TimedRecord>, appVersion: String) {
        val sorted = records.sortedWith(compareBy<TimedRecord> { it.ts }.thenBy { it.record.sequence })
        out.append("# NOOP WHOOP MG ECG export (app ").append(appVersion).append(")\n")
        out.append("# Flash-banked R16 records as stored. value_counts is the signed 18-bit sample in native counts:\n")
        out.append("# no volts-per-count calibration and no sample rate are established (docs/PROTOCOL_ECG.md).\n")
        out.append("# flag6/flag7/presence_flag/quality_code are wire fields with no established clinical meaning.\n")
        out.append("# Not a medical ECG. Not for diagnosis.\n")
        // Counted here so an export whose records all declare zero samples still says what it holds,
        // rather than arriving as a header with nothing under it.
        val emptyRecords = sorted.count { it.record.samples.isEmpty() }
        out.append("# records: ").append(sorted.size.toString())
            .append(", samples: ").append(sorted.sumOf { it.record.samples.size }.toString())
            .append(", records declaring 0 samples: ").append(emptyRecords.toString()).append('\n')
        out.append(CSV_COLUMNS).append('\n')
        val first = sorted.firstOrNull()?.ts ?: return
        for (t in sorted) {
            val r = t.record
            val prefix = "${t.ts - first},${t.ts},${r.sequence},"
            val suffix = ",${if (r.presence) 1 else 0},${r.signalQualityRaw}\n"
            for (i in r.samples.indices) {
                out.append(prefix)
                    .append(i.toString()).append(',')
                    .append(r.samples[i].toString()).append(',')
                    .append(if (r.flag6(i) == true) "1" else "0").append(',')
                    .append(if (r.flag7(i) == true) "1" else "0")
                    .append(suffix)
            }
        }
    }
}
