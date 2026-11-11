package com.noop.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.BuildConfig
import com.noop.R
import com.noop.ble.EcgLiveSession
import com.noop.data.WhoopDao
import com.noop.data.WhoopDatabase
import com.noop.protocol.LabradorR16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The stored WHOOP MG ECG recordings, drawn as they were banked (#2847 rows, #752 limits).
 *
 * The ECG screen (More > Body, and the Test Centre's ECG section). On top, a timed 30 s measurement with
 * the strap's live filtered trace ([EcgMeasurementCard]); below, the active strap's stored readings, one
 * drawn as a scrolling window of its samples and shareable as CSV. Only the Start and Stop buttons send
 * anything to the strap. Nothing on this screen is derived from the waveform: see [EcgRecordings] for
 * what the numbers are.
 */
@Composable
fun EcgRecordingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val dao = remember { WhoopDatabase.get(context.applicationContext).whoopDao() }
    val deviceId = vm.activeStrapId
    var readings by remember { mutableStateOf<List<EcgRecordings.Reading>?>(null) }
    var selected by remember { mutableStateOf<EcgRecordings.Reading?>(null) }
    val live by vm.live.collectAsStateWithLifecycle()
    val variant by vm.ble.whoop5VariantFlow.collectAsStateWithLifecycle()
    val session by vm.ble.ecgLiveFlow.collectAsStateWithLifecycle()

    // Re-read after every sync, so a measurement's banked records appear once the strap has handed them over.
    LaunchedEffect(deviceId, live.lastSyncAt) {
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
        title = stringResource(R.string.nav_ecg),
        subtitle = stringResource(R.string.ecg_screen_subtitle),
    ) {
        val current = selected
        if (current == null) {
            EcgMeasurementCard(
                vm = vm,
                canMeasure = live.connected && live.encryptedBond && variant.isMG,
                session = session,
            )
        }
        NoopCard {
            Text(stringResource(R.string.ecg_rec_disclaimer), style = NoopType.caption, color = Palette.textSecondary)
        }
        if (current == null) {
            Text(stringResource(R.string.ecg_rec_list_header), style = NoopType.headline, color = Palette.textPrimary)
        }
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

/** Set once the wearer has read the first-measurement note; the Start button then starts directly. */
private const val KEY_ECG_MEASUREMENT_CONSENT = "noop.ecg.measurementConsent"

/** Packets shown in the live window: the most recent few seconds. */
private const val LIVE_WINDOW_PACKETS = 5

/**
 * The timed measurement: Start (30 s, stopping on its own), a countdown, and the strap's live filtered
 * trace while it streams. The commands go out only from the Start and Stop buttons; the first Start shows
 * a one-time note that this is not a medical ECG. When a measurement ends here, a history sync is asked
 * for, so the banked recording reaches the list below without waiting for the next scheduled sync.
 */
@Composable
private fun EcgMeasurementCard(vm: AppViewModel, canMeasure: Boolean, session: EcgLiveSession) {
    val context = LocalContext.current
    val prefs = remember { NoopPrefs.of(context) }
    var showConsent by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var mayBeRunning by remember { mutableStateOf(vm.ble.ecgMayBeRunning) }
    val openedAtMs = remember { System.currentTimeMillis() }

    LaunchedEffect(session.running) {
        while (session.running) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    LaunchedEffect(session) { mayBeRunning = vm.ble.ecgMayBeRunning }
    // Only for a measurement that ended while this screen was open, so revisiting does not re-sync.
    LaunchedEffect(session.endedAtMs) {
        val ended = session.endedAtMs ?: return@LaunchedEffect
        if (ended >= openedAtMs && session.endReason != EcgLiveSession.EndReason.DISCONNECTED) {
            delay(2_000)   // let the stop burst go out ahead of the offload request
            vm.syncNow()
        }
    }

    val start = { vm.ble.ecgStartMeasurement(); mayBeRunning = vm.ble.ecgMayBeRunning }

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            when {
                session.running -> Text(
                    stringResource(R.string.ecg_measure_remaining, session.remainingSeconds(nowMs)),
                    style = NoopType.headline, color = Palette.textPrimary,
                )
                !canMeasure -> Text(
                    stringResource(R.string.ecg_measure_not_mg),
                    style = NoopType.body, color = Palette.textSecondary,
                )
                else -> Text(
                    stringResource(R.string.ecg_measure_how),
                    style = NoopType.body, color = Palette.textSecondary,
                )
            }

            val window = minOf(LIVE_WINDOW_PACKETS, session.packets)
            val firstPacket = session.packets - window
            val liveStrip = EcgRecordings.strip(
                (firstPacket until session.packets).map { p ->
                    val start = session.packetStarts[p]
                    val end = if (p + 1 < session.packets) session.packetStarts[p + 1] else session.samples.size
                    (p - firstPacket) to session.samples.copyOfRange(start, end).toList()
                },
                EcgRecordings.STRIP_SLOTS_LIVE,
            )
            if (liveStrip.values.any { !it.isNaN() }) {
                EcgPaperStrip(
                    values = liveStrip.values,
                    slotsPerSecond = liveStrip.slotsPerSecond,
                    fromSecond = 0,
                    secondsPerRow = LIVE_WINDOW_PACKETS,
                    range = EcgRecordings.stripRange(liveStrip.values),
                    labelOffsetSeconds = firstPacket,
                )
                Text(stringResource(R.string.ecg_live_note), style = NoopType.caption, color = Palette.textTertiary)
            } else if (session.running) {
                Text(stringResource(R.string.ecg_measure_waiting), style = NoopType.caption, color = Palette.textSecondary)
            }

            val endedHere = !session.running && (session.endedAtMs ?: 0L) >= openedAtMs
            if (endedHere) {
                val dropped = session.endReason == EcgLiveSession.EndReason.DISCONNECTED
                Text(
                    stringResource(if (dropped) R.string.ecg_measure_disconnected else R.string.ecg_measure_done),
                    style = NoopType.caption,
                    color = if (dropped) Palette.statusWarning else Palette.textSecondary,
                )
            }

            if (session.running) {
                NoopButton(
                    text = stringResource(R.string.ecg_measure_stop),
                    kind = NoopButtonKind.Destructive,
                    fullWidth = true,
                    onClick = { vm.ble.ecgStopMeasurement(); mayBeRunning = vm.ble.ecgMayBeRunning },
                )
            } else if (mayBeRunning) {
                Text(stringResource(R.string.ecg_measure_may_run), style = NoopType.caption, color = Palette.statusWarning)
                NoopButton(
                    text = stringResource(R.string.ecg_measure_stop_leftover),
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    enabled = canMeasure,
                    onClick = { vm.ble.ecgStopMeasurement(); mayBeRunning = vm.ble.ecgMayBeRunning },
                )
            } else {
                NoopButton(
                    text = stringResource(R.string.ecg_measure_start, EcgLiveSession.DEFAULT_DURATION_SECONDS),
                    fullWidth = true,
                    enabled = canMeasure,
                    onClick = {
                        if (prefs.getBoolean(KEY_ECG_MEASUREMENT_CONSENT, false)) start() else showConsent = true
                    },
                )
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            containerColor = Palette.surfaceOverlay,
            title = { Text(stringResource(R.string.ecg_consent_title), style = NoopType.title2, color = Palette.textPrimary) },
            text = { Text(stringResource(R.string.ecg_consent_body), style = NoopType.subhead, color = Palette.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean(KEY_ECG_MEASUREMENT_CONSENT, true).apply()
                    showConsent = false
                    start()
                }) { Text(stringResource(R.string.ecg_consent_accept), style = NoopType.body, color = Palette.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) {
                    Text(stringResource(R.string.ecg_consent_cancel), style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
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

@Composable
private fun ReadingViewer(dao: WhoopDao, deviceId: String, reading: EcgRecordings.Reading, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember(reading) { mutableStateOf<List<EcgRecordings.TimedRecord>?>(null) }
    var raw by remember(reading) { mutableStateOf<EcgRecordings.Strip?>(null) }
    var filtered by remember(reading) { mutableStateOf<FloatArray?>(null) }
    var showFiltered by remember { mutableStateOf(true) }
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
        val strip = withContext(Dispatchers.Default) { EcgRecordings.strip(loaded) }
        filtered = withContext(Dispatchers.Default) { EcgRecordings.displayFiltered(strip.values) }
        raw = strip
    }

    NoopButton(text = stringResource(R.string.ecg_rec_back), kind = NoopButtonKind.Tertiary, onClick = onBack)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) { ReadingFacts(reading) }
    }

    val strip = raw
    val loaded = records
    if (strip == null || loaded == null) {
        EmptyNote(stringResource(R.string.ecg_rec_loading))
        return
    }
    if (loaded.isEmpty()) {
        EmptyNote(stringResource(R.string.ecg_rec_empty))
        return
    }
    if (strip.values.none { !it.isNaN() }) {
        // Records exist but every one declares a zero sample count: there is no waveform to draw. Said
        // outright, because an empty strip reads as a drawing fault rather than as what the strap stored.
        EmptyNote(stringResource(R.string.ecg_rec_no_samples, loaded.size))
    } else {
        val values = if (showFiltered) filtered ?: strip.values else strip.values
        val range = remember(values) { EcgRecordings.stripRange(values) }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    NoopButton(
                        text = stringResource(R.string.ecg_view_filtered),
                        kind = if (showFiltered) NoopButtonKind.Primary else NoopButtonKind.Secondary,
                        onClick = { showFiltered = true },
                    )
                    NoopButton(
                        text = stringResource(R.string.ecg_view_raw),
                        kind = if (showFiltered) NoopButtonKind.Secondary else NoopButtonKind.Primary,
                        onClick = { showFiltered = false },
                    )
                }
                val perRow = EcgRecordings.STRIP_SECONDS_PER_ROW
                for (rowStart in 0 until strip.seconds step perRow) {
                    EcgPaperStrip(
                        values = values,
                        slotsPerSecond = strip.slotsPerSecond,
                        fromSecond = rowStart,
                        secondsPerRow = perRow,
                        range = range,
                    )
                }
                Text(stringResource(R.string.ecg_strip_note), style = NoopType.caption, color = Palette.textTertiary)
            }
        }
    }

    NoopButton(
        text = stringResource(R.string.ecg_rec_share),
        leadingIcon = Icons.Filled.Upload,
        kind = NoopButtonKind.Secondary,
        fullWidth = true,
        enabled = !exporting,
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

/** Minor grid divisions per second: 25, a 0.04 s square, as on ECG paper. */
private const val GRID_MINOR_PER_SECOND = 25

/** Minor squares per major square: five, a 0.2 s square. */
private const val GRID_MINOR_PER_MAJOR = 5

/** Row height in seconds of width, so grid squares stay square: 2.5 s, half a row of 5 s, a quarter of 10 s. */
private const val STRIP_HEIGHT_SECONDS = 2.5f

/**
 * One row of ECG paper: a square grid (a minor square is 1/25 s, a major one 1/5 s), the trace across
 * [secondsPerRow] seconds from [fromSecond], and the second marks under it. Every row of a reading uses
 * the same [range], so rows compare as they would on paper. The vertical grid carries no unit: there is no
 * volts-per-count calibration, so there is no calibration pulse either. Gaps (NaN) break the line.
 */
@Composable
private fun EcgPaperStrip(
    values: FloatArray,
    slotsPerSecond: Int,
    fromSecond: Int,
    secondsPerRow: Int,
    range: ClosedFloatingPointRange<Float>,
    labelOffsetSeconds: Int = fromSecond,
) {
    val traceColor = Palette.metricRose
    val minorColor = Palette.metricRose.copy(alpha = 0.10f)
    val majorColor = Palette.metricRose.copy(alpha = 0.28f)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(secondsPerRow / STRIP_HEIGHT_SECONDS),
        ) {
            val perSecond = size.width / secondsPerRow
            val minor = perSecond / GRID_MINOR_PER_SECOND
            val hair = Metrics.divider.toPx()
            // Vertical lines left to right, horizontal ones bottom up, so both share the same square.
            var k = 0
            while (k * minor <= size.width + 0.5f) {
                val x = k * minor
                val major = k % GRID_MINOR_PER_MAJOR == 0
                drawLine(if (major) majorColor else minorColor, Offset(x, 0f), Offset(x, size.height),
                    strokeWidth = if (major) hair else hair * 0.6f)
                k++
            }
            k = 0
            while (k * minor <= size.height + 0.5f) {
                val y = size.height - k * minor
                val major = k % GRID_MINOR_PER_MAJOR == 0
                drawLine(if (major) majorColor else minorColor, Offset(0f, y), Offset(size.width, y),
                    strokeWidth = if (major) hair else hair * 0.6f)
                k++
            }

            val from = fromSecond * slotsPerSecond
            val to = minOf(values.size, (fromSecond + secondsPerRow) * slotsPerSecond)
            if (to <= from) return@Canvas
            val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
            fun y(v: Float): Float =
                size.height - (v.coerceIn(range.start, range.endInclusive) - range.start) / span * size.height
            // Columns cover the row's full time width, so a short last row ends where its data ends.
            val rowSlots = secondsPerRow * slotsPerSecond
            val columns = size.width.toInt().coerceAtLeast(1)
            val (mins, maxs) = EcgRecordings.columnEnvelope(values, from, from + rowSlots, columns)
            val path = Path()
            var penDown = false
            for (c in 0 until columns) {
                val lo = mins[c]
                val hi = maxs[c]
                if (lo.isNaN() || hi.isNaN()) { penDown = false; continue }
                val x = c.toFloat() / columns * size.width
                if (penDown) path.lineTo(x, y(lo)) else path.moveTo(x, y(lo))
                path.lineTo(x, y(hi))
                penDown = true
            }
            drawPath(
                path = path,
                color = traceColor,
                style = Stroke(width = hair * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (sec in 0 until secondsPerRow) {
                Text(
                    stringResource(R.string.ecg_strip_second, labelOffsetSeconds + sec),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier.weight(1f),
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
