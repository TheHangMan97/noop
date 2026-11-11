package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.analytics.LabBookProjection
import com.noop.analytics.LabMarkerCategory
import com.noop.analytics.MarkerCatalog
import com.noop.analytics.WindowedPair
import com.noop.data.ImportSummary
import com.noop.data.LabMarkerRow
import com.noop.data.WhoopDao
import com.noop.ingest.LabMarkerCsvImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

// MARK: - Lab Book (Health Records pillar — v5) — Compose twin of LabBookView.swift
//
// "Your own logbook." A private place to KEEP the numbers you already get from your
// doctor or pharmacy — bloods, BP, body measurements — and SEE them next to your
// wearable signals, entirely on this phone. NOOP never tests you, never reads a result,
// and never tells you what a number means medically.
// (Spec: docs/superpowers/specs/2026-06-19-v5-health-records-design.md.)
//
// SELF-CONTAINED: reads/writes markers through `vm.repo` (the Lab Book DAO methods +
// metricSeries projection). Raw readings store under the strap device id ("my-whoop");
// every write also projects a daily series under LAB_BOOK_SOURCE_ID so Compare/Explore/
// Coach see markers unchanged. The "Compare with a signal" surface reuses the same
// Pearson idiom + restrained copy as the Compare screen.
//
// NON-CLINICAL (load-bearing): no word here asserts a clinical judgement; any reference
// range shown is exactly what the user typed from their own report; correlation copy says
// "association, not a medical finding".

/** The strap device id the markers are stored under (matches the rest of the app + Swift test). */
private const val LAB_STRAP_DEVICE_ID = "my-whoop"

/** Reading-count floor below which NO conclusion sentence renders (spec default 4). */
private const val LAB_FLOOR = 4

@Composable
fun LabBookScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var markers by remember { mutableStateOf<List<LabMarkerRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadSeq by remember { mutableStateOf(0) }

    // Sheets.
    var showEditor by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var detailKey by remember { mutableStateOf<String?>(null) }

    // Markers CSV import (LabMarkerCsvImport, Phase 2).
    var csvImporting by remember { mutableStateOf(false) }
    var csvSummary by remember { mutableStateOf<String?>(null) }
    var csvFailed by remember { mutableStateOf(false) }

    suspend fun reload() {
        val all = mutableListOf<LabMarkerRow>()
        for (category in LabMarkerCategory.entries) {
            all += vm.repo.labMarkersByCategory(LAB_STRAP_DEVICE_ID, category.raw)
        }
        markers = all.sortedBy { it.takenAt }
        loaded = true
    }

    LaunchedEffect(reloadSeq) { reload() }

    // SAF picker for the markers CSV — the same OpenDocument + "*/*" idiom as the Data
    // Sources importers (csv mime filtering through SAF is unreliable across providers).
    val csvImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        csvImporting = true
        csvSummary = null
        csvFailed = false
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { LabMarkerCsvImport.importCsv(context, uri, vm.repo) }
                    .getOrElse { ImportSummary.failure(uiStringOr(R.string.de_lab_book_csv_11e8ca, "Lab Book CSV"), it.message ?: "failed") }
            }
            // Mirror into the exported strap log (issue #421 parity): counts only on
            // success, the human reason on failure — never a file name, path or value.
            if (summary.totalRows > 0) {
                vm.ble.externalLog(uiStringOr(R.string.de_import_1_s_labmarker_2_s_e04bec, "Import %1\$s: labMarker=%2\$s", (summary.source).toString(), (summary.totalRows).toString()))
            } else {
                vm.ble.externalLog(uiStringOr(R.string.de_import_1_s_failed_2_s_8b9fc5, "Import %1\$s failed: %2\$s", (summary.source).toString(), (summary.message).toString()))
            }
            csvSummary = summary.message
            csvFailed = summary.totalRows == 0
            csvImporting = false
            reloadSeq++
        }
    }

    // PERF (#707): lazy scaffold — each top-level section is one `item { }` so only on-screen cards
    // compose + are accessibility-walked on scroll. Order/spacing unchanged (no standalone Spacers; the
    // LazyColumn reproduces the eager `spacedBy(20.dp)`). The category/marker list stays inside the single
    // `when {}` item (it's a user-entered, bounded set, not unbounded history), so its appearance is
    // byte-identical; the sheets below the scaffold are untouched.
    LazyScreenScaffold(
        title = uiString(R.string.l10n_lab_book_screen_lab_book_f966c140),
        subtitle = uiStringOr(R.string.de_your_bloods_bp_and_body_numbers_kept_pri_ae4dd5, "Your bloods, BP and body numbers. Kept private, on this phone."),
    ) {
        // Header card: count + scope + add action.
        item {
        NoopCard(padding = 18.dp, tint = Palette.metricCyan) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Palette.metricCyan.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Palette.metricCyan, modifier = Modifier.size(16.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(countLine(markers), style = NoopType.headline, color = Palette.textPrimary)
                        Text(
                            uiString(R.string.l10n_lab_book_screen_all_stays_on_this_phone_nothing_9a966c1c),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showDisclaimer = true }
                            .semantics { contentDescription = uiString(R.string.l10n_lab_book_screen_what_lab_book_is_and_isn_f28686df) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    uiString(R.string.l10n_lab_book_screen_it_s_a_notebook_not_a_45835e98),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                PrimaryActionButton(uiStringOr(R.string.de_add_a_reading_ed7708, "Add a reading"), Icons.Filled.Add) { showEditor = true }
            }
        }
        }

        // Import entry — the Phase-2 markers CSV importer (LabMarkerCsvImport): (date, marker,
        // value, unit) rows with tolerant headers, catalog + custom marker mapping, and
        // skip-and-count on anything unreadable. Twin of LabBookView's importCard.
        item {
        NoopCard(padding = 18.dp, tint = Palette.metricAmber) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Palette.metricAmber.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null, tint = Palette.metricAmber, modifier = Modifier.size(16.dp))
                    }
                    Text(uiString(R.string.l10n_lab_book_screen_import_readings_7ad0d28f), style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                }
                Text(
                    uiString(R.string.l10n_lab_book_screen_bring_in_a_markers_csv_date_1090bec2),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                PrimaryActionButton(
                    if (csvImporting) uiStringOr(R.string.de_importing_820599, "Importing…") else uiStringOr(R.string.de_choose_csv_4890ca, "Choose CSV…"),
                    Icons.Filled.FileUpload,
                    enabled = !csvImporting,
                ) { csvImportLauncher.launch(arrayOf("*/*")) }
                csvSummary?.let { s ->
                    Text(
                        s,
                        style = NoopType.subhead,
                        color = if (csvFailed) Palette.statusWarning else Palette.statusPositive,
                    )
                }
            }
        }
        }

        item {
        when {
            !loaded -> {
                Text(uiString(R.string.l10n_lab_book_screen_reading_your_logbook_e31992b0), style = NoopType.subhead, color = Palette.textTertiary)
            }
            markers.isEmpty() -> {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(uiString(R.string.l10n_lab_book_screen_keep_your_own_numbers_here_1ca6c8ed), style = NoopType.headline, color = Palette.textPrimary)
                        Text(
                            uiString(R.string.l10n_lab_book_screen_type_in_a_blood_pressure_reading_c276d418),
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    }
                }
            }
            else -> {
                for (category in orderedCategories(markers)) {
                    val keys = markerKeys(markers, category)
                    SectionHeader(
                        title = category.displayName,
                        overline = if (keys.size == 1) uiStringOr(R.string.de_1_marker_25aeef, "1 marker") else "${keys.size} markers",
                    )
                    for (key in keys) {
                        MarkerRow(key = key, readings = readingsFor(markers, key)) { detailKey = key }
                    }
                }
            }
        }
        }

        // Always-visible disclaimer footnote + link.
        item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                uiString(R.string.l10n_lab_book_screen_lab_book_is_a_private_notebook_d07380ca),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
            Text(
                uiString(R.string.l10n_lab_book_screen_read_the_full_note_3fd0e4d1),
                style = NoopType.footnote,
                color = Palette.accent,
                modifier = Modifier
                    .clickable { showDisclaimer = true }
                    .semantics { contentDescription = uiString(R.string.l10n_lab_book_screen_read_the_full_lab_book_note_3a250750) },
            )
        }
        }
    }

    // --- Sheets ---
    if (showEditor) {
        MarkerEditorScreen(
            onDismiss = { showEditor = false },
            onSave = { drafts ->
                scope.launch {
                    vm.repo.upsertLabMarkers(drafts)
                    reloadSeq++
                }
                showEditor = false
            },
        )
    }

    if (showDisclaimer) {
        LabBookDisclaimerSheet(onDismiss = { showDisclaimer = false })
    }

    detailKey?.let { key ->
        MarkerDetailSheet(
            vm = vm,
            markerKey = key,
            readings = readingsFor(markers, key),
            onDelete = { id ->
                scope.launch {
                    vm.repo.deleteLabMarker(id)
                    reloadSeq++
                }
            },
            onDismiss = { detailKey = null },
        )
    }
}

// MARK: - Marker list row

@Composable
private fun MarkerRow(key: String, readings: List<LabMarkerRow>, onClick: () -> Unit) {
    val numeric = readings.mapNotNull { it.value }
    val latest = readings.lastOrNull()
    NoopCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName(key), style = NoopType.headline, color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lastTakenCaption(latest), style = NoopType.footnote, color = Palette.textTertiary)
            }
            if (numeric.size > 1) {
                MiniSpark(values = numeric, color = Palette.metricCyan, modifier = Modifier.width(64.dp).height(28.dp))
            }
            Text(latestLabel(latest, key), style = NoopType.number(18f), color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

// MARK: - Marker detail sheet (history + trend + compare with a signal)

@Composable
private fun MarkerDetailSheet(
    vm: AppViewModel,
    markerKey: String,
    readings: List<LabMarkerRow>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = displayName(markerKey)
    val unit = readings.lastOrNull()?.unit ?: MarkerCatalog.definition(markerKey)?.canonicalUnit ?: ""
    val numeric = readings.filter { it.value != null }

    var signal by remember { mutableStateOf<LabSignal?>(null) }
    var window by remember { mutableStateOf(LabWindow.FORTNIGHT) }
    var pairs by remember { mutableStateOf<List<WindowedPair>>(emptyList()) }
    var correlation by remember { mutableStateOf<LabCorrelation?>(null) }
    var computing by remember { mutableStateOf(false) }

    LaunchedEffect(signal, window) {
        val s = signal
        if (s == null) {
            pairs = emptyList(); correlation = null
            return@LaunchedEffect
        }
        computing = true
        val to = labDay(1)
        val from = labDay(-4000)
        val markerSeries = vm.repo.metricSeries(WhoopDao.LAB_BOOK_SOURCE_ID, markerKey, from, to).map { it.day to it.value }
        val wearable = vm.repo.resolvedSeries(s.key, s.source, from, to, strapDeviceId = vm.activeStrapId).values
        val built = LabBookProjection.pairMarkerToWearable(markerSeries, wearable, window.days)
        pairs = built
        correlation = if (built.size >= LAB_FLOOR) {
            pearson(LabBookProjection.correlationInput(built))
        } else {
            null
        }
        computing = false
    }

    NoopBottomSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            Text(name, style = NoopType.title2, color = Palette.textPrimary)
            Text(
                uiPlural(R.plurals.lab_book_readings_count, readings.size, readings.size),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )

            // Trend (descriptive arithmetic, never interpretation).
            SectionHeader(uiStringOr(R.string.de_trend_dae07c, "Trend"), overline = uiStringOr(R.string.de_your_readings_over_time_40e2de, "your readings over time"))
            NoopCard(tint = Palette.metricCyan) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val nums = numeric.mapNotNull { it.value }
                    if (nums.size > 1) {
                        MiniSpark(values = nums, color = Palette.metricCyan, modifier = Modifier.fillMaxWidth().height(64.dp))
                    }
                    Text(trendSentence(markerKey, numeric, unit), style = NoopType.subhead, color = Palette.textSecondary)
                    latestReferenceText(readings)?.let { ref ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceBadge(uiStringOr(R.string.de_from_your_report_450bb2, "from your report"), tint = Palette.textTertiary)
                            Text(ref, style = NoopType.footnote, color = Palette.textSecondary)
                        }
                    }
                }
            }

            // Compare with a signal (reuses the Pearson idiom + restrained copy).
            if (numeric.isNotEmpty()) {
                SectionHeader(uiStringOr(R.string.de_compare_with_a_signal_436791, "Compare with a signal"), overline = uiStringOr(R.string.de_side_by_side_1_s_before_each_reading_f592ff, "side by side · %1\$s before each reading", (window.phrase).toString()))
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SignalPicker(selected = signal) { signal = it }
                            Spacer(Modifier.weight(1f))
                            SegmentedPillControl(
                                items = LabWindow.entries.toList(),
                                selection = window,
                                label = { it.label },
                                onSelect = { window = it },
                            )
                        }
                        CorrelationResult(
                            markerName = name,
                            signal = signal,
                            window = window,
                            pairs = pairs,
                            correlation = correlation,
                            computing = computing,
                        )
                    }
                }
            }

            // History table.
            SectionHeader(uiStringOr(R.string.de_history_90ccd6, "History"), overline = uiStringOr(R.string.de_every_reading_you_ve_entered_839fa2, "every reading you've entered"))
            NoopCard {
                Column {
                    val reversed = readings.reversed()
                    reversed.forEachIndexed { idx, row ->
                        HistoryRow(markerKey, row, onDelete)
                        if (idx < reversed.size - 1) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
                        }
                    }
                }
            }

            Text(
                uiString(R.string.l10n_lab_book_screen_these_are_your_own_numbers_shown_7667508d),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun CorrelationResult(
    markerName: String,
    signal: LabSignal?,
    window: LabWindow,
    pairs: List<WindowedPair>,
    correlation: LabCorrelation?,
    computing: Boolean,
) {
    val n = pairs.size
    when {
        signal == null -> Text(
            uiString(R.string.l10n_lab_book_screen_pick_a_wearable_signal_resting_hr_8d8b01a2, window.phrase),
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )
        computing -> Text(uiString(R.string.l10n_lab_book_screen_lining_them_up_c59ee297), style = NoopType.subhead, color = Palette.textTertiary)
        n < LAB_FLOOR -> Text(
            if (n == 0) {
                uiString(R.string.lab_no_overlap_yet, signal.title)
            } else {
                uiPlural(R.plurals.lab_readings_line_up, n, n, LAB_FLOOR)
            },
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )
        correlation != null -> {
            val r = correlation.r
            val tint = correlationColor(r)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiString(R.string.l10n_lab_book_screen_markername_signal_title_e9c07909, markerName, signal.title), style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f), maxLines = 2)
                    TrendChip(text = signedR(r), color = tint)
                    Spacer(Modifier.width(8.dp))
                    Text(uiString(R.string.l10n_lab_book_screen_r_signedr_r_fb21be90, signedR(r)), style = NoopType.number(18f), color = tint)
                }
                Text(insightSentence(markerName, signal.title, r), style = NoopType.subhead, color = Palette.textSecondary)
                Text(
                    uiString(R.string.l10n_lab_book_screen_n_readings_used_strengthword_r_directionword_298baf9e, n, strengthWord(r), directionWord(r)),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        else -> Text(
            uiString(R.string.l10n_lab_book_screen_n_readings_line_up_but_there_27bfb506, n),
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun SignalPicker(selected: LabSignal?, onSelect: (LabSignal?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .semantics { contentDescription = uiString(R.string.l10n_lab_book_screen_choose_a_wearable_signal_to_compare_05a4b2bc) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
            Text(selected?.title ?: uiString(R.string.lab_choose_signal), style = NoopType.subhead, color = Palette.accent)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LAB_SIGNALS.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(s.title, style = NoopType.body, color = Palette.textPrimary) },
                    onClick = { onSelect(s); expanded = false },
                )
            }
            if (selected != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(uiString(R.string.l10n_lab_book_screen_clear_719ea396), style = NoopType.body, color = Palette.textSecondary) },
                    onClick = { onSelect(null); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(markerKey: String, row: LabMarkerRow, onDelete: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(valueLabel(markerKey, row), style = NoopType.number(16f), color = Palette.textPrimary)
            Text(labDayFromKey(row.day), style = NoopType.footnote, color = Palette.textTertiary)
            row.note?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = NoopType.footnote, color = Palette.textSecondary)
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onDelete(row.id) }
                .semantics { contentDescription = uiString(R.string.l10n_lab_book_screen_delete_this_reading_b5c92bda) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = Palette.statusCritical, modifier = Modifier.size(15.dp))
        }
    }
}

// MARK: - Disclaimer sheet

@Composable
private fun LabBookDisclaimerSheet(onDismiss: () -> Unit) {
    NoopBottomSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiString(R.string.l10n_lab_book_screen_about_lab_book_37bf2691), style = NoopType.title2, color = Palette.textPrimary)
            Text(uiString(R.string.l10n_lab_book_screen_a_private_notebook_not_a_medical_f63242cb), style = NoopType.subhead, color = Palette.textSecondary)
            DisclaimerBullet(uiStringOr(R.string.de_noop_stores_and_lines_up_the_numbers_you_a48239, "NOOP stores and lines up the numbers you enter yourself. It does not test you, read your results, give medical advice, or diagnose anything."))
            DisclaimerBullet(uiStringOr(R.string.de_anything_you_see_here_including_any_side_8561f0, "Anything you see here (including any side-by-side trend) is your own information shown back to you. It's an association, never a cause, and never a medical finding."))
            DisclaimerBullet(uiStringOr(R.string.de_noop_never_decides_whether_a_value_is_no_f44259, "NOOP never decides whether a value is \"normal,\" \"high,\" or \"low.\" Any reference range shown is exactly what you typed from your own report."))
            DisclaimerBullet(uiStringOr(R.string.de_your_records_never_leave_this_phone_ther_c9301c, "Your records never leave this phone. There's no account, no cloud, no NOOP server. Because NOOP is an independent app you run yourself (not a healthcare provider), it isn't \"HIPAA-covered,\" and that protection doesn't apply here; the safety comes from the data being local-only and yours."))
            DisclaimerBullet(uiStringOr(R.string.de_always_rely_on_your_doctor_pharmacist_or_953b0e, "Always rely on your doctor, pharmacist, or a qualified professional to interpret results and make decisions. If a number worries you, talk to them, not to an app."))
            PrimaryActionButton(uiStringOr(R.string.de_got_it_5b8027, "Got it"), Icons.Filled.Check, onClick = onDismiss)
        }
    }
}

@Composable
private fun DisclaimerBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.metricCyan),
        )
        Text(text, style = NoopType.subhead, color = Palette.textSecondary)
    }
}

// MARK: - Trailing window control (7 / 14 / 30 days)

enum class LabWindow(val label: String, val days: Int, val phrase: String) {
    WEEK("7d", 7, uiStringOr(R.string.de_7_days_0a8dd6, "7 days")),
    FORTNIGHT("14d", 14, uiStringOr(R.string.de_14_days_176e59, "14 days")),
    MONTH("30d", 30, uiStringOr(R.string.de_30_days_7d4278, "30 days")),
}

// MARK: - Wearable signals offered for correlation

data class LabSignal(val key: String, val title: String, val source: String)

/** The pickable wearable metrics, mirroring the Swift LabBookSignals.options list. */
private val LAB_SIGNALS = listOf(
    LabSignal("rhr", uiStringOr(R.string.de_resting_heart_rate_9700f4, "Resting Heart Rate"), "my-whoop"),
    LabSignal("hrv", uiStringOr(R.string.de_heart_rate_variability_20f006, "Heart Rate Variability"), "my-whoop"),
    LabSignal("recovery", uiStringOr(R.string.lab_signal_charge, "Charge"), "my-whoop"),
    LabSignal("sleep_performance", uiStringOr(R.string.lab_signal_rest, "Rest"), "my-whoop"),
    LabSignal("sleep_total_min", uiStringOr(R.string.de_asleep_time_ccde95, "Asleep Time"), "my-whoop"),
    LabSignal("strain", uiStringOr(R.string.lab_signal_effort, "Effort"), "my-whoop"),
    LabSignal("skin_temp", uiStringOr(R.string.de_skin_temperature_f59127, "Skin Temperature"), "my-whoop"),
    LabSignal("steps", uiStringOr(R.string.de_steps_cdde4f, "Steps"), "apple-health"),
    LabSignal("weight", uiStringOr(R.string.de_weight_69c0b8, "Weight"), "apple-health"),
)

// MARK: - Helpers (display, formatting, trend, correlation)

private fun countLine(markers: List<LabMarkerRow>): String {
    val keys = markers.map { it.markerKey }.toSet().size
    val markerWord = if (keys == 1) "marker" else "markers"
    val readingWord = if (markers.size == 1) "reading" else "readings"
    return "$keys $markerWord tracked · ${markers.size} $readingWord"
}

private fun displayName(key: String): String =
    MarkerCatalog.definition(key)?.displayName ?: key.replace("_", " ").replaceFirstChar { it.uppercase() }

private fun readingsFor(markers: List<LabMarkerRow>, key: String): List<LabMarkerRow> =
    markers.filter { it.markerKey == key }

private fun orderedCategories(markers: List<LabMarkerRow>): List<LabMarkerCategory> {
    val present = markers.map { LabMarkerCategory.fromRaw(it.category) }.toSet()
    val order = listOf(
        LabMarkerCategory.BLOOD_PANEL, LabMarkerCategory.BLOOD_PRESSURE, LabMarkerCategory.BODY_MEASUREMENT,
        LabMarkerCategory.IMAGING, LabMarkerCategory.APPOINTMENT_NOTE, LabMarkerCategory.OTHER,
    )
    return order.filter { present.contains(it) }
}

private fun markerKeys(markers: List<LabMarkerRow>, category: LabMarkerCategory): List<String> =
    markers.filter { it.category == category.raw }.map { it.markerKey }.toSet().sortedBy { displayName(it) }

private fun valueLabel(key: String, row: LabMarkerRow): String =
    row.value?.let { "${formatValue(it, key)} ${row.unit}" } ?: (row.valueText ?: "—")

private fun latestLabel(row: LabMarkerRow?, key: String): String {
    if (row == null) return "—"
    return row.value?.let { "${formatValue(it, key)} ${row.unit}" } ?: (row.valueText ?: "—")
}

private fun lastTakenCaption(row: LabMarkerRow?): String =
    if (row == null) uiStringOr(R.string.de_no_readings_yet_7e0fe2, "no readings yet") else uiStringOr(R.string.de_last_taken_1_s_93cf26, "last taken %1\$s", (labDayLabel(row.takenAt)).toString())

private fun formatValue(v: Double, key: String): String = LabValueFormat.value(v, key)

private fun latestReferenceText(readings: List<LabMarkerRow>): String? =
    readings.lastOrNull { !it.referenceText.isNullOrEmpty() }?.referenceText

/** "Your last 3 readings: 3.4 → 3.1 → 2.9 mmol/L, trending down." — descriptive only. */
private fun trendSentence(key: String, numeric: List<LabMarkerRow>, unit: String): String {
    val last = numeric.lastOrNull()?.value
        ?: return numeric.lastOrNull()?.valueText?.let { uiStringOr(R.string.de_latest_entry_1_s_db2c4d, "Latest entry: %1\$s.", it.toString()) } ?: uiStringOr(R.string.de_no_numeric_readings_yet_b8eea3, "No numeric readings yet.")
    if (numeric.size < 2) {
        return uiStringOr(R.string.de_one_reading_so_far_1_s_2_s_log_a_few_mor_efeac6, "One reading so far: %1\$s %2\$s. Log a few more to see a trend.", (formatValue(last, key)).toString(), unit.toString())
    }
    val shown = numeric.takeLast(3).mapNotNull { it.value }
    val arrowed = shown.joinToString(" → ") { formatValue(it, key) }
    val first = shown.first()
    val direction = when {
        last > first -> uiStringOr(R.string.de_trending_up_34094b, "trending up")
        last < first -> uiStringOr(R.string.de_trending_down_fc2716, "trending down")
        else -> uiStringOr(R.string.de_holding_steady_d34c82, "holding steady")
    }
    return uiStringOr(R.string.de_your_last_1_s_readings_2_s_3_s_4_s_049864, "Your last %1\$s readings: %2\$s %3\$s, %4\$s.", (shown.size).toString(), arrowed.toString(), unit.toString(), direction.toString())
}

private fun signedR(r: Double): String = (if (r >= 0) "+" else "−") + java.lang.String.format(Locale.US, "%.2f", abs(r))

private fun strengthWord(r: Double): String = when {
    abs(r) < 0.1 -> "negligible"
    abs(r) < 0.3 -> "weak"
    abs(r) < 0.5 -> "moderate"
    abs(r) < 0.7 -> "strong"
    else -> uiStringOr(R.string.de_very_strong_3e7b4c, "very strong")
}

private fun directionWord(r: Double): String = if (abs(r) < 0.1) "" else if (r >= 0) "positive" else "negative"

private fun insightSentence(markerName: String, signalName: String, r: Double): String {
    if (abs(r) < 0.3) {
        return uiStringOr(R.string.de_over_your_readings_1_s_and_2_s_move_larg_20a70c, "Over your readings, %1\$s and %2\$s move largely independently. No clear relationship.", markerName.toString(), (signalName).toString())
    }
    val verb = if (r < 0) uiStringOr(R.string.de_tends_to_be_lower_e5cda4, "tends to be lower") else uiStringOr(R.string.de_tends_to_be_higher_2e97e9, "tends to be higher")
    return uiStringOr(R.string.de_when_1_s_is_higher_2_s_3_s_42ed57, "When %1\$s is higher, %2\$s %3\$s.", markerName.toString(), (signalName).toString(), verb.toString())
}

@Composable
private fun correlationColor(r: Double): Color {
    val base = if (r >= 0) Palette.statusPositive else Palette.statusCritical
    return base.copy(alpha = (0.55f + 0.45f * min(abs(r), 1.0)).toFloat())
}

/** Pearson r over the pairs — value-for-value with CompareScreen's engine (null when <3 or zero variance). */
private data class LabCorrelation(val r: Double, val n: Int)

private fun pearson(xy: List<Pair<Double, Double>>): LabCorrelation? {
    val n = xy.size
    if (n < 3) return null
    val nD = n.toDouble()
    var sumX = 0.0
    var sumY = 0.0
    for (p in xy) { sumX += p.first; sumY += p.second }
    val meanX = sumX / nD
    val meanY = sumY / nD
    var sxx = 0.0
    var syy = 0.0
    var sxy = 0.0
    for (p in xy) {
        val dx = p.first - meanX
        val dy = p.second - meanY
        sxx += dx * dx
        syy += dy * dy
        sxy += dx * dy
    }
    if (sxx <= 0.0 || syy <= 0.0) return null
    var r = sxy / (sqrt(sxx) * sqrt(syy))
    if (r > 1.0) r = 1.0
    if (r < -1.0) r = -1.0
    return LabCorrelation(r, n)
}

private val labDayFmt = SimpleDateFormat("d MMM yyyy", Locale.US)
private fun labDayLabel(epochSeconds: Long): String = labDayFmt.format(Date(epochSeconds * 1000L))

/** "yyyy-MM-dd" parser + "d MMM yyyy" render, both pinned to UTC, so a stored day key renders the same
 *  calendar date regardless of the device zone (the takenAt is UTC noon). Falls back to the raw key if it
 *  doesn't parse. Mirrors Swift LabBookFormat.dayFromKey. */
private val labDayKeyParser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}
private val labDayKeyFmt = SimpleDateFormat("d MMM yyyy", Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}
private fun labDayFromKey(day: String): String =
    runCatching { labDayKeyParser.parse(day)?.let { labDayKeyFmt.format(it) } }.getOrNull() ?: day

/** "yyyy-MM-dd" for today offset by [deltaDays], fixed UTC (matches CompareScreen.todayDay). */
internal fun labDay(deltaDays: Int): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.add(java.util.Calendar.DAY_OF_YEAR, deltaDays)
    val y = cal.get(java.util.Calendar.YEAR)
    val m = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return java.lang.String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
}

// MARK: - Shared Lab Book primitives (sheet wrapper, primary button, mini sparkline)

/** A scrollable frosted bottom sheet in the house style — used by the marker detail, the
 *  marker editor and the disclaimer. Mirrors the WorkoutDetailSheet idiom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoopBottomSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.surfaceOverlay,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            content()
        }
    }
}

/** The accent primary CTA, matching the .noopPrimary button style on Apple. */
@Composable
internal fun PrimaryActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    val container = if (enabled) Palette.accent else Palette.accent.copy(alpha = Palette.disabledOpacity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            // A crisp, subtle NEUTRAL elevation (soft dark lift, no bloom) — matching the iOS
            // .noopPrimary refresh, which trades any cast-glow for a clean neutral shadow.
            .let { if (enabled) it.shadow(elevation = 4.dp, shape = shape, clip = false) else it }
            .clip(shape)
            .background(container)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.surfaceBase, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = NoopType.body, color = Palette.surfaceBase)
    }
}

/** A tiny inline sparkline for a short numeric series, sized by the modifier (width × height). */
@Composable
internal fun MiniSpark(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val pad = 3f
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val pts = values.mapIndexed { i, v ->
            val x = pad + (i.toFloat() / (values.size - 1)) * w
            val y = pad + (1f - ((v - lo) / span).toFloat()) * h
            Offset(x, y)
        }
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
