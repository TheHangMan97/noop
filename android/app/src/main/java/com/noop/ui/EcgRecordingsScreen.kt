package com.noop.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.noop.BuildConfig
import com.noop.R
import com.noop.data.WhoopDao
import com.noop.data.WhoopDatabase
import com.noop.protocol.LabradorR16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * The stored WHOOP MG ECG recordings, drawn as they were banked (#2847 rows, #752 limits).
 *
 * Reached from the Test Centre's ECG section. Lists the active strap's readings, draws one as a scrolling
 * window of its samples, and shares it as CSV. It reads the existing `ecgWaveformSample` rows through the
 * existing DAO query and sends nothing to the strap. Nothing on this screen is derived from the waveform:
 * see [EcgRecordings] for what the numbers are.
 */
@Composable
fun EcgRecordingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val dao = remember { WhoopDatabase.get(context.applicationContext).whoopDao() }
    val deviceId = vm.activeStrapId
    var readings by remember { mutableStateOf<List<EcgRecordings.Reading>?>(null) }
    var selected by remember { mutableStateOf<EcgRecordings.Reading?>(null) }

    LaunchedEffect(deviceId) {
        selected = null
        readings = null
        readings = if (deviceId.isBlank()) emptyList() else withContext(Dispatchers.IO) {
            val summaries = EcgRecordings.readAll(
                from = 0L,
                to = Long.MAX_VALUE,
                ts = EcgRecordings.RecordSummary::ts,
                sequence = EcgRecordings.RecordSummary::sequence,
            ) { from, limit ->
                dao.ecgWaveformSamples(deviceId, from, Long.MAX_VALUE, limit).map(EcgRecordings::summarize)
            }
            EcgRecordings.groupReadings(summaries)
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.ecg_rec_title),
        subtitle = stringResource(R.string.ecg_rec_subtitle),
    ) {
        NoopCard {
            Text(stringResource(R.string.ecg_rec_disclaimer), style = NoopType.caption, color = Palette.textSecondary)
        }
        val current = selected
        when {
            deviceId.isBlank() -> EmptyNote(stringResource(R.string.ecg_rec_no_strap))
            readings == null -> EmptyNote(stringResource(R.string.ecg_rec_loading))
            current != null -> ReadingViewer(dao, deviceId, current, onBack = { selected = null })
            readings!!.isEmpty() -> EmptyNote(stringResource(R.string.ecg_rec_empty))
            else -> readings!!.forEach { reading ->
                ReadingRow(reading, onOpen = { selected = reading })
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    NoopCard {
        Text(text, style = NoopType.body, color = Palette.textSecondary)
    }
}

private fun startLabel(ts: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts * 1000))

@Composable
private fun ReadingFacts(reading: EcgRecordings.Reading) {
    Text(
        stringResource(R.string.ecg_rec_reading_meta, startLabel(reading.startTs), reading.spanSeconds, reading.records),
        style = NoopType.headline, color = Palette.textPrimary,
    )
    Text(
        stringResource(R.string.ecg_rec_stats, reading.samples, reading.presenceRecords, reading.records),
        style = NoopType.caption, color = Palette.textSecondary,
    )
    if (reading.missingRecords > 0 || reading.undecodableRecords > 0) {
        Text(
            stringResource(R.string.ecg_rec_missing, reading.missingRecords, reading.undecodableRecords),
            style = NoopType.caption, color = Palette.statusWarning,
        )
    }
}

@Composable
private fun ReadingRow(reading: EcgRecordings.Reading, onOpen: () -> Unit) {
    NoopCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            ReadingFacts(reading)
        }
    }
}

/** One stored row as the viewer loads it; [record] is null when the bytes did not decode. */
private class LoadedRow(val ts: Long, val sequence: Long, val record: LabradorR16?)

private val WINDOW_RECORDS = listOf(2, 5, 10, 30)

@Composable
private fun ReadingViewer(dao: WhoopDao, deviceId: String, reading: EcgRecordings.Reading, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember(reading) { mutableStateOf<List<EcgRecordings.TimedRecord>?>(null) }
    var trace by remember(reading) { mutableStateOf<EcgRecordings.Trace?>(null) }
    var windowRecords by remember { mutableIntStateOf(5) }
    var firstRecord by remember(reading) { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    val chooserTitle = stringResource(R.string.ecg_rec_share_chooser)

    LaunchedEffect(reading) {
        val loaded = withContext(Dispatchers.IO) {
            EcgRecordings.readAll(
                from = reading.startTs,
                to = reading.endTs,
                ts = LoadedRow::ts,
                sequence = LoadedRow::sequence,
            ) { from, limit ->
                dao.ecgWaveformSamples(deviceId, from, reading.endTs, limit).map {
                    LoadedRow(it.ts, it.sequence, EcgRecordings.decode(it))
                }
            }.mapNotNull { row -> row.record?.let { EcgRecordings.TimedRecord(row.ts, it) } }
        }
        records = loaded
        trace = withContext(Dispatchers.Default) { EcgRecordings.trace(loaded) }
    }

    NoopButton(text = stringResource(R.string.ecg_rec_back), kind = NoopButtonKind.Tertiary, onClick = onBack)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) { ReadingFacts(reading) }
    }

    val t = trace
    if (t == null) {
        EmptyNote(stringResource(R.string.ecg_rec_loading))
        return
    }
    if (t.recordCount == 0) {
        EmptyNote(stringResource(R.string.ecg_rec_empty))
        return
    }
    val window = windowRecords.coerceAtMost(t.recordCount)
    val maxFirst = (t.recordCount - window).coerceAtLeast(0)
    val first = firstRecord.coerceIn(0, maxFirst)
    val from = t.recordStart(first)
    val to = t.windowEnd(first, window)
    val startOffset = t.recordTs[first] - t.recordTs[0]
    val endOffset = t.recordTs[first + window - 1] - t.recordTs[0]

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text(stringResource(R.string.ecg_rec_window), style = NoopType.subhead, color = Palette.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                WINDOW_RECORDS.forEach { n ->
                    NoopButton(
                        text = stringResource(R.string.ecg_rec_window_seconds, n),
                        kind = if (n == windowRecords) NoopButtonKind.Primary else NoopButtonKind.Secondary,
                        onClick = { windowRecords = n },
                    )
                }
            }
            EcgTraceCanvas(t, from, to, first, window)
            Text(
                stringResource(R.string.ecg_rec_position, startOffset, endOffset, t.recordTs.last() - t.recordTs[0]),
                style = NoopType.captionNumber, color = Palette.textSecondary,
            )
            if (maxFirst > 0) {
                Slider(
                    value = first.toFloat(),
                    onValueChange = { firstRecord = it.roundToInt() },
                    valueRange = 0f..maxFirst.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.accent,
                        activeTrackColor = Palette.accent,
                        inactiveTrackColor = Palette.surfaceInset,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    NoopButton(
                        text = stringResource(R.string.ecg_rec_earlier),
                        kind = NoopButtonKind.Secondary,
                        enabled = first > 0,
                        onClick = { firstRecord = (first - window).coerceAtLeast(0) },
                    )
                    NoopButton(
                        text = stringResource(R.string.ecg_rec_later),
                        kind = NoopButtonKind.Secondary,
                        enabled = first < maxFirst,
                        onClick = { firstRecord = (first + window).coerceAtMost(maxFirst) },
                    )
                }
            }
            if (to > from && (from until to).all { t.samples[it] == 0 }) {
                Text(stringResource(R.string.ecg_rec_flat), style = NoopType.caption, color = Palette.textSecondary)
            }
            Text(stringResource(R.string.ecg_rec_axis_note), style = NoopType.caption, color = Palette.textTertiary)
        }
    }

    NoopButton(
        text = stringResource(R.string.ecg_rec_share),
        leadingIcon = Icons.Filled.Upload,
        kind = NoopButtonKind.Secondary,
        fullWidth = true,
        enabled = !exporting && records != null,
        onClick = {
            val rows = records ?: return@NoopButton
            exporting = true
            scope.launch {
                runCatching {
                    val file = withContext(Dispatchers.IO) { writeCsvFile(context, rows) }
                    shareCsv(context, file, chooserTitle)
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.ecg_rec_share_failed, it.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                exporting = false
            }
        },
    )
}

/**
 * The visible window: one line through the samples (min/max per pixel column once samples outnumber
 * pixels), hairlines at record boundaries, and a strip under the trace marking records whose presence
 * flag is set. The vertical scale fits the window ([EcgRecordings.displayRange]); there is no unit.
 */
@Composable
private fun EcgTraceCanvas(trace: EcgRecordings.Trace, from: Int, to: Int, firstRecord: Int, windowRecords: Int) {
    val lineColor = Palette.accent
    val gridColor = Palette.hairline
    val presenceOn = Palette.statusPositive
    val presenceOff = Palette.surfaceInset
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight)) {
            val n = to - from
            if (n <= 0) return@Canvas
            val range = EcgRecordings.displayRange(trace.samples, from, to)
            val span = (range.last - range.first).toFloat()
            fun y(v: Int): Float {
                val clamped = v.coerceIn(range.first, range.last)
                return size.height - (clamped - range.first) / span * size.height
            }
            for (r in firstRecord until firstRecord + windowRecords) {
                val x = (trace.recordStart(r) - from).toFloat() / n * size.width
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = Metrics.divider.toPx())
            }
            val columns = size.width.toInt().coerceAtLeast(1)
            val path = Path()
            if (n <= columns) {
                for (i in 0 until n) {
                    val x = if (n == 1) 0f else i.toFloat() / (n - 1) * size.width
                    val yy = y(trace.samples[from + i])
                    if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
                }
            } else {
                val (mins, maxs) = EcgRecordings.envelope(trace.samples, from, to, columns)
                for (b in mins.indices) {
                    val x = b.toFloat() / (columns - 1).coerceAtLeast(1) * size.width
                    if (b == 0) path.moveTo(x, y(mins[b])) else path.lineTo(x, y(mins[b]))
                    path.lineTo(x, y(maxs[b]))
                }
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = Metrics.divider.toPx() * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.space6)) {
            val n = to - from
            if (n <= 0) return@Canvas
            for (r in firstRecord until firstRecord + windowRecords) {
                val x0 = (trace.recordStart(r) - from).toFloat() / n * size.width
                val x1 = (trace.recordStart(r + 1) - from).toFloat() / n * size.width
                drawRect(
                    color = if (trace.recordPresence[r]) presenceOn else presenceOff,
                    topLeft = Offset(x0, 0f),
                    size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), size.height),
                )
            }
        }
    }
}

private fun writeCsvFile(context: Context, records: List<EcgRecordings.TimedRecord>): File {
    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
    val out = File(dir, "noop-mg-ecg-${LogExport.timestamp()}.csv")
    out.bufferedWriter().use { w -> EcgRecordings.writeCsv(w, records, BuildConfig.VERSION_NAME) }
    return out
}

private fun shareCsv(context: Context, file: File, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
