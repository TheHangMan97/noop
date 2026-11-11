package com.noop.ui

import com.noop.data.EcgWaveformSampleEntity
import com.noop.protocol.Whoop5Ecg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MG ECG recordings viewer's pure helpers. Fixtures are SYNTHETIC stored records built from the R16
 * layout in `docs/PROTOCOL_ECG.md`; nothing here is a hardware measurement.
 */
class EcgRecordingsTest {

    /** One stored row: the inner R16 record, as `ecgWaveformSample.record` holds it. */
    private fun row(
        ts: Long,
        sequence: Long,
        samples: List<Int> = listOf(1, -2, 3),
        presence: Boolean = true,
        flag6At: Set<Int> = emptySet(),
        flag7At: Set<Int> = emptySet(),
        quality: Int = 2,
    ): EcgWaveformSampleEntity {
        val inner = ByteArray(Whoop5Ecg.R16_INNER_LENGTH)
        inner[0] = Whoop5Ecg.STORED_RECORD_TYPE.toByte()
        inner[1] = Whoop5Ecg.R16_REVISION.toByte()
        for (k in 0 until 4) inner[3 + k] = (sequence shr (8 * k)).toByte()
        inner[13] = quality.toByte()
        inner[14] = if (presence) 0x08 else 0x00
        inner[21] = 0xff.toByte(); inner[22] = 0xff.toByte()   // variability unavailable
        inner[24] = (samples.size and 0xff).toByte()
        inner[25] = (samples.size shr 8).toByte()
        samples.forEachIndexed { i, v ->
            val raw18 = v and 0x3ffff
            var tag = raw18 shr 16
            if (i in flag6At) tag = tag or 0x40
            if (i in flag7At) tag = tag or 0x80
            val o = Whoop5Ecg.R16_SAMPLE_START + i * Whoop5Ecg.R16_SAMPLE_SLOT_BYTES
            inner[o] = tag.toByte()
            inner[o + 1] = (raw18 shr 8).toByte()
            inner[o + 2] = raw18.toByte()
        }
        return EcgWaveformSampleEntity("dev", ts, sequence, inner)
    }

    private fun timed(r: EcgWaveformSampleEntity) = EcgRecordings.TimedRecord(r.ts, EcgRecordings.decode(r)!!)

    private fun summary(ts: Long, sequence: Long, samples: Int = 500, presence: Boolean = true) =
        EcgRecordings.RecordSummary(ts, sequence, samples, presence, decodable = true)

    @Test
    fun `a stored record summarises from its own bytes`() {
        val s = EcgRecordings.summarize(row(ts = 1_000, sequence = 7, samples = listOf(5, -131072, 131071)))
        assertEquals(1_000L, s.ts)
        assertEquals(7L, s.sequence)
        assertEquals(3, s.sampleCount)
        assertTrue(s.presence)
        assertTrue(s.decodable)
        assertEquals(listOf(5, -131072, 131071), EcgRecordings.decode(row(1, 1, listOf(5, -131072, 131071)))!!.samples)
    }

    @Test
    fun `a row that does not decode is counted, not dropped`() {
        val bad = EcgWaveformSampleEntity("dev", 50, 1, ByteArray(10))
        val s = EcgRecordings.summarize(bad)
        assertFalse(s.decodable)
        assertEquals(0, s.sampleCount)
        val reading = EcgRecordings.groupReadings(listOf(s, summary(51, 2))).single()
        assertEquals(1, reading.undecodableRecords)
        assertEquals(2, reading.records)
    }

    @Test
    fun `readings split on a gap above ten seconds and come back newest first`() {
        val records = (0L..4L).map { summary(100 + it, 1 + it) } +
            listOf(summary(114, 6), summary(125, 7), summary(126, 8))
        val readings = EcgRecordings.groupReadings(records.shuffled(java.util.Random(3)))
        assertEquals(2, readings.size)
        // 104 -> 114 is exactly ten seconds: same reading. 114 -> 125 is eleven: a new one.
        assertEquals(125L, readings[0].startTs)
        assertEquals(126L, readings[0].endTs)
        assertEquals(2L, readings[0].spanSeconds)
        assertEquals(100L, readings[1].startTs)
        assertEquals(114L, readings[1].endTs)
        assertEquals(6, readings[1].records)
        assertEquals(3_000L, readings[1].samples)
        assertEquals(0L, readings[1].missingRecords)
    }

    @Test
    fun `a sequence reset splits and a sequence jump counts missing records`() {
        val readings = EcgRecordings.groupReadings(
            listOf(summary(100, 10), summary(101, 11), summary(102, 15), summary(103, 2), summary(104, 3)),
        )
        assertEquals(2, readings.size)
        assertEquals(103L, readings[0].startTs)
        assertEquals(0L, readings[0].missingRecords)
        assertEquals(3L, readings[1].missingRecords)   // 12, 13, 14
    }

    @Test
    fun `two records in one second stay one reading`() {
        val readings = EcgRecordings.groupReadings(
            listOf(summary(100, 1, samples = 0), summary(100, 2, samples = 245), summary(101, 3, presence = false)),
        )
        val r = readings.single()
        assertEquals(3, r.records)
        assertEquals(745L, r.samples)
        assertEquals(2, r.presenceRecords)
        assertEquals(2L, r.spanSeconds)   // seconds 100 and 101, inclusive
    }

    @Test
    fun `no records means no readings`() {
        assertTrue(EcgRecordings.groupReadings(emptyList()).isEmpty())
    }

    @Test
    fun `the trace lays records end to end in time and sequence order`() {
        val trace = EcgRecordings.trace(
            listOf(
                timed(row(101, 3, listOf(30, 31), presence = false)),
                timed(row(100, 2, listOf(20))),
                timed(row(100, 1, emptyList())),
            ),
        )
        assertArrayEquals(intArrayOf(20, 30, 31), trace.samples)
        assertArrayEquals(intArrayOf(0, 0, 1), trace.recordStarts)
        assertArrayEquals(longArrayOf(100, 100, 101), trace.recordTs)
        assertEquals(listOf(true, true, false), trace.recordPresence.toList())
        assertEquals(3, trace.recordCount)
        assertEquals(1, trace.windowEnd(0, 2))
        assertEquals(3, trace.windowEnd(1, 2))
        assertEquals(3, trace.recordStart(9))
    }

    @Test
    fun `the envelope keeps each bucket's extremes`() {
        val samples = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val (mins, maxs) = EcgRecordings.envelope(samples, 0, 10, 5)
        assertArrayEquals(intArrayOf(0, 2, 4, 6, 8), mins)
        assertArrayEquals(intArrayOf(1, 3, 5, 7, 9), maxs)
        val (subMins, subMaxs) = EcgRecordings.envelope(samples, 4, 8, 2)
        assertArrayEquals(intArrayOf(4, 6), subMins)
        assertArrayEquals(intArrayOf(5, 7), subMaxs)
    }

    @Test
    fun `an envelope with more buckets than samples never drops to zero`() {
        val (mins, maxs) = EcgRecordings.envelope(intArrayOf(-5, 7), 0, 2, 4)
        assertArrayEquals(intArrayOf(-5, -5, 7, 7), mins)
        assertArrayEquals(intArrayOf(-5, -5, 7, 7), maxs)
        assertEquals(0, EcgRecordings.envelope(intArrayOf(1, 2), 1, 1, 4).first.size)
    }

    @Test
    fun `the display range ignores a lone settling spike`() {
        val samples = IntArray(1_000) { it % 100 } + intArrayOf(120_000)
        val range = EcgRecordings.displayRange(samples, 0, samples.size)
        assertTrue(range.last < 120_000)
        assertTrue(range.first <= 1 && range.last >= 98)
    }

    @Test
    fun `a flat or empty window still gets a drawable range`() {
        assertEquals(-1..1, EcgRecordings.displayRange(IntArray(20), 0, 20))
        assertEquals(-1..1, EcgRecordings.displayRange(IntArray(0), 0, 0))
        assertEquals(41..43, EcgRecordings.displayRange(intArrayOf(42, 42), 0, 2))
    }

    /** A ts-ascending table with the DAO's `ts >= from ORDER BY ts LIMIT` shape. */
    private fun source(rows: List<Pair<Long, Long>>): suspend (Long, Int) -> List<Pair<Long, Long>> {
        val sorted = rows.sortedWith(compareBy({ it.first }, { it.second }))
        return { from, limit -> sorted.filter { it.first >= from }.take(limit) }
    }

    @Test
    fun `paging returns every row once when one second straddles a page edge`() = runBlocking {
        val rows = listOf(100L to 1L, 101L to 2L, 101L to 3L, 101L to 4L, 102L to 5L, 105L to 6L)
        val got = EcgRecordings.readAll(0, Long.MAX_VALUE, pageSize = 2, ts = { it.first }, sequence = { it.second },
            fetch = source(rows))
        assertEquals(rows, got)
    }

    @Test
    fun `paging honours the upper bound and the row cap`() = runBlocking {
        val rows = (0L until 50L).map { (1_000 + it) to it }
        val bounded = EcgRecordings.readAll(1_010, 1_019, pageSize = 3, ts = { it.first }, sequence = { it.second },
            fetch = source(rows))
        assertEquals((10L until 20L).map { (1_000 + it) to it }, bounded)
        val capped = EcgRecordings.readAll(0, Long.MAX_VALUE, pageSize = 4, maxRows = 7, ts = { it.first },
            sequence = { it.second }, fetch = source(rows))
        assertEquals(7, capped.size)
    }

    @Test
    fun `paging widens the page when one second holds more rows than a page`() = runBlocking {
        val rows = (1L..5L).map { 100L to it } + listOf(101L to 6L)
        var calls = 0
        val fetch = source(rows)
        val got = EcgRecordings.readAll(0, Long.MAX_VALUE, pageSize = 2, ts = { it.first }, sequence = { it.second }) { f, l ->
            calls++
            fetch(f, l)
        }
        assertEquals(rows, got)
        assertTrue(calls < 10)
    }

    @Test
    fun `paging stops when one second exceeds the whole cap`() = runBlocking {
        val rows = (1L..9L).map { 100L to it }
        val got = EcgRecordings.readAll(0, Long.MAX_VALUE, pageSize = 2, maxRows = 4, ts = { it.first },
            sequence = { it.second }, fetch = source(rows))
        assertEquals(rows.take(4), got)
    }

    @Test
    fun `the CSV states its units and writes one line per sample`() {
        val out = StringBuilder()
        EcgRecordings.writeCsv(
            out,
            listOf(
                timed(row(203, 9, listOf(-7), presence = false, quality = 0)),
                timed(row(200, 8, listOf(5, 6), flag6At = setOf(1), flag7At = setOf(0))),
            ),
            appVersion = "1.2.3",
        )
        val lines = out.lines().filter { it.isNotEmpty() }
        val comments = lines.takeWhile { it.startsWith("#") }
        assertTrue(comments.first().contains("1.2.3"))
        assertTrue(comments.any { it.contains("no volts-per-count") })
        assertTrue(comments.any { it.contains("Not a medical ECG") })
        assertTrue(comments.contains("# records: 2, samples: 3, records declaring 0 samples: 0"))
        assertEquals(EcgRecordings.CSV_COLUMNS, lines[comments.size])
        assertEquals(
            listOf(
                "0,200,8,0,5,0,1,1,2",
                "0,200,8,1,6,1,0,1,2",
                "3,203,9,0,-7,0,0,0,0",
            ),
            lines.drop(comments.size + 1),
        )
        assertEquals(EcgRecordings.CSV_COLUMNS.split(',').size, lines.last().split(',').size)
    }

    @Test
    fun `records that declare no samples are counted in the CSV header`() {
        val out = StringBuilder()
        EcgRecordings.writeCsv(out, listOf(timed(row(10, 1, emptyList())), timed(row(11, 2, emptyList()))), "x")
        assertTrue(out.lines().contains("# records: 2, samples: 0, records declaring 0 samples: 2"))
        assertEquals(EcgRecordings.CSV_COLUMNS, out.lines().last { it.isNotEmpty() })
    }

    @Test
    fun `an empty CSV still carries its header`() {
        val out = StringBuilder()
        EcgRecordings.writeCsv(out, emptyList(), appVersion = "x")
        assertNotNull(out.lines().firstOrNull { it == EcgRecordings.CSV_COLUMNS })
    }
}
