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

    /**
     * A reading's samples laid end to end, in record order.
     *
     * [recordStarts] holds the index into [samples] where each record begins, so `recordStarts.size` is
     * the record count and record `i` covers `recordStarts[i] until next start (or samples.size)`.
     */
    class Trace(
        val samples: IntArray,
        val recordStarts: IntArray,
        val recordTs: LongArray,
        val recordPresence: BooleanArray,
    ) {
        val recordCount: Int get() = recordStarts.size

        /** First sample index of record [index] (clamped), or [samples].size past the end. */
        fun recordStart(index: Int): Int =
            if (index >= recordStarts.size) samples.size else recordStarts[index.coerceAtLeast(0)]

        /** Sample index one past the last sample of the window of [count] records starting at [first]. */
        fun windowEnd(first: Int, count: Int): Int = recordStart(first + count)
    }

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

    /** Lay a reading's records end to end, ordered by (ts, sequence). Records with no samples still mark a start. */
    fun trace(records: List<TimedRecord>): Trace {
        val sorted = records.sortedWith(compareBy<TimedRecord> { it.ts }.thenBy { it.record.sequence })
        val total = sorted.sumOf { it.record.samples.size }
        val samples = IntArray(total)
        val starts = IntArray(sorted.size)
        val ts = LongArray(sorted.size)
        val presence = BooleanArray(sorted.size)
        var at = 0
        sorted.forEachIndexed { i, t ->
            starts[i] = at
            ts[i] = t.ts
            presence[i] = t.record.presence
            for (v in t.record.samples) samples[at++] = v
        }
        return Trace(samples, starts, ts, presence)
    }

    /**
     * Min/max of each of [buckets] equal slices of `samples[from, to)`, for drawing more samples than
     * there are pixels. A bucket that receives no sample (only when the range is shorter than [buckets])
     * repeats its neighbour, so the drawn line never drops to zero. Empty range gives empty arrays.
     */
    fun envelope(samples: IntArray, from: Int, to: Int, buckets: Int): Pair<IntArray, IntArray> {
        val lo = from.coerceIn(0, samples.size)
        val hi = to.coerceIn(lo, samples.size)
        val n = hi - lo
        if (n == 0 || buckets <= 0) return IntArray(0) to IntArray(0)
        val mins = IntArray(buckets) { Int.MAX_VALUE }
        val maxs = IntArray(buckets) { Int.MIN_VALUE }
        for (i in 0 until n) {
            val b = ((i.toLong() * buckets) / n).toInt()
            val v = samples[lo + i]
            if (v < mins[b]) mins[b] = v
            if (v > maxs[b]) maxs[b] = v
        }
        for (b in 0 until buckets) {
            if (mins[b] == Int.MAX_VALUE) {
                val prev = if (b > 0) b - 1 else (0 until buckets).first { mins[it] != Int.MAX_VALUE }
                mins[b] = mins[prev]
                maxs[b] = maxs[prev]
            }
        }
        return mins to maxs
    }

    /**
     * The vertical range to draw `samples[from, to)` in: the [tail] and `1 - tail` quantiles, so a single
     * settling spike does not flatten the rest of the window. Never returns an empty range (a flat window
     * is widened by one count each way); an empty slice gives `-1..1`.
     */
    fun displayRange(samples: IntArray, from: Int, to: Int, tail: Double = 0.005): IntRange {
        val lo = from.coerceIn(0, samples.size)
        val hi = to.coerceIn(lo, samples.size)
        if (hi == lo) return -1..1
        val slice = samples.copyOfRange(lo, hi)
        slice.sort()
        val last = slice.size - 1
        val t = tail.coerceIn(0.0, 0.49)
        val a = slice[(last * t).toInt()]
        val b = slice[last - (last * t).toInt()]
        return if (a == b) (a - 1)..(b + 1) else a..b
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
