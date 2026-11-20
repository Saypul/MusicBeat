package com.musicbeat.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicbeat.app.R
import com.musicbeat.app.data.settings.AppSettings
import com.musicbeat.app.data.settings.USER_EQ_PRESET_NAME_MAX
import com.musicbeat.app.playback.AutoEqFormat
import com.musicbeat.app.playback.EqCurve
import com.musicbeat.app.playback.EqLayout
import com.musicbeat.app.playback.FilterKind
import com.musicbeat.app.playback.ParametricBand
import com.musicbeat.app.playback.ParametricLimits
import com.musicbeat.app.playback.parametricCurve
import com.musicbeat.app.ui.components.IosSwitch
import com.musicbeat.app.ui.haptics.Haptic
import com.musicbeat.app.ui.haptics.rememberHaptics
import com.musicbeat.app.ui.icons.SfIcons
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// ---- Response graph --------------------------------------------------------

/**
 * The curve as it will be heard: the summed response of every running band,
 * across 20 Hz to 20 kHz on a log axis.
 *
 * With [bands] given it is also the parametric tab's main control. Each band is
 * a point at its centre and gain, and dragging one moves both at once — the way
 * a parametric equaliser is read, so it is the way it is set. A touch that
 * lands on no point is left alone, so the page still scrolls through it.
 */
@Composable
internal fun EqResponseGraph(
    curve: EqCurve,
    enabled: Boolean,
    bands: List<ParametricBand>? = null,
    selected: Int = -1,
    onSelect: (Int) -> Unit = {},
    onMove: (index: Int, frequencyHz: Float, gainDb: Float) -> Unit = { _, _, _ -> },
) {
    val haptics = rememberHaptics()
    val gridColor = MaterialTheme.colorScheme.outline
    val curveColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pointColor = MaterialTheme.colorScheme.background
    // See [TonePad]: the gesture block outlives the composition that made it.
    val latestBands by rememberUpdatedState(bands)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnMove by rememberUpdatedState(onMove)

    Column(Modifier.padding(horizontal = ROW_INSET, vertical = 12.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT)
                .pointerInput(enabled, bands != null) {
                    if (!enabled || latestBands == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val current = latestBands ?: return@awaitEachGesture
                        val reach = POINT_REACH.toPx()
                        val hit = current.indices
                            .map { it to hypot(xOf(current[it].frequencyHz, size.width.toFloat()) - down.position.x, yOf(current[it].gainDb, size.height.toFloat()) - down.position.y) }
                            .filter { it.second <= reach }
                            .minByOrNull { it.second }
                            ?.first
                            ?: return@awaitEachGesture
                        down.consume()
                        haptics.play(Haptic.Select)
                        currentOnSelect(hit)
                        var lastGain = current[hit].gainDb
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                val hz = roundFrequency(frequencyAt(change.position.x, size.width.toFloat()))
                                val gain = snapParametricGain(gainAt(change.position.y, size.height.toFloat()))
                                if (gain != lastGain) {
                                    haptics.play(Haptic.Tick)
                                    lastGain = gain
                                }
                                currentOnMove(hit, hz, gain)
                            }
                        }
                    }
                },
        ) {
            val width = size.width
            val height = size.height
            val thin = 1.dp.toPx()

            for (hz in GRID_HZ) {
                val x = xOf(hz, width)
                drawLine(gridColor.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, height), thin)
            }
            for (db in GRID_DB) {
                val y = yOf(db, height)
                drawLine(
                    color = gridColor.copy(alpha = if (db == 0f) 0.9f else 0.45f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = thin,
                    pathEffect = if (db == 0f) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            val line = Path()
            val fill = Path()
            val zero = yOf(0f, height)
            fill.moveTo(0f, zero)
            for (step in 0..CURVE_POINTS) {
                val x = width * step / CURVE_POINTS
                val y = yOf(curve.responseDb(frequencyAt(x, width)), height)
                if (step == 0) line.moveTo(x, y) else line.lineTo(x, y)
                fill.lineTo(x, y)
            }
            fill.lineTo(width, zero)
            fill.close()
            drawPath(fill, curveColor.copy(alpha = 0.16f))
            drawPath(line, curveColor, style = Stroke(width = 2.5.dp.toPx()))

            bands?.forEachIndexed { index, band ->
                val centre = Offset(xOf(band.frequencyHz, width), yOf(band.gainDb, height))
                val radius = if (index == selected) 9.dp.toPx() else 7.dp.toPx()
                if (band.enabled) {
                    drawCircle(curveColor, radius, centre)
                    drawCircle(pointColor, radius - 2.5.dp.toPx(), centre)
                    if (index == selected) drawCircle(curveColor, radius - 4.5.dp.toPx(), centre)
                } else {
                    drawCircle(labelColor, radius, centre, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            for ((hz, label) in GRID_LABELS) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.align(
                        androidx.compose.ui.BiasAlignment(horizontalBias = labelBias(hz), verticalBias = 0f),
                    ),
                )
            }
        }
    }
}

// ---- Parametric tab --------------------------------------------------------

/** The band picker and the editor for whichever band is picked. */
@Composable
internal fun ParametricEditor(
    bands: List<ParametricBand>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val haptics = rememberHaptics()
    val band = bands.getOrNull(selected) ?: return
    fun update(change: (ParametricBand) -> ParametricBand) {
        AppSettings.setEqualizerParametric(bands.toMutableList().also { it[selected] = change(it[selected]) })
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bands.forEachIndexed { index, item ->
            BandChip(
                label = (index + 1).toString(),
                selected = index == selected,
                dimmed = !item.enabled,
                enabled = enabled,
            ) {
                haptics.play(Haptic.Select)
                onSelect(index)
            }
        }
        if (bands.size < EqLayout.PARAMETRIC_COUNT) {
            BandChip(label = "+", selected = false, dimmed = false, enabled = enabled) {
                haptics.play(Haptic.Select)
                AppSettings.setEqualizerParametric(bands + newBandFor(bands))
                onSelect(bands.size)
            }
        }
    }
    RowDivider()
    SettingsRow(
        icon = SfIcons.GraphicEq,
        title = stringResource(R.string.equalizer_band_n, selected + 1),
        subtitle = describe(band),
        enabled = enabled,
        trailing = {
            IosSwitch(
                checked = band.enabled,
                onCheckedChange = {
                    haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                    update { b -> b.copy(enabled = it) }
                },
            )
        },
    )
    RowDivider()
    SegmentedControl(
        options = KINDS.map { stringResource(it.label()) },
        selectedIndex = KINDS.indexOf(band.kind),
        onSelect = { index ->
            haptics.play(Haptic.Select)
            update { it.copy(kind = KINDS[index]) }
        },
        enabled = enabled,
        modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 12.dp),
    )
    SliderRow(
        icon = SfIcons.Waves,
        title = stringResource(R.string.equalizer_frequency),
        value = formatHz(band.frequencyHz),
        sliderValue = logFraction(band.frequencyHz, ParametricLimits.MIN_HZ, ParametricLimits.MAX_HZ),
        onSliderValue = { fraction ->
            if (enabled) {
                update { it.copy(frequencyHz = roundFrequency(fromLogFraction(fraction, ParametricLimits.MIN_HZ, ParametricLimits.MAX_HZ))) }
            }
        },
        valueRange = 0f..1f,
        steps = 0,
    )
    SliderRow(
        icon = SfIcons.Sliders,
        title = stringResource(R.string.equalizer_gain),
        value = formatDb(band.gainDb),
        sliderValue = band.gainDb,
        onSliderValue = { gain -> if (enabled) update { it.copy(gainDb = snapParametricGain(gain)) } },
        valueRange = -ParametricLimits.RANGE_DB..ParametricLimits.RANGE_DB,
        steps = 0,
    )
    SliderRow(
        icon = SfIcons.Tune,
        title = stringResource(R.string.equalizer_q),
        value = String.format(Locale.getDefault(), "%.2f", band.q),
        sliderValue = logFraction(band.q, ParametricLimits.MIN_Q, ParametricLimits.MAX_Q),
        onSliderValue = { fraction ->
            if (enabled) {
                val q = fromLogFraction(fraction, ParametricLimits.MIN_Q, ParametricLimits.MAX_Q)
                update { it.copy(q = (q * 100f).roundToInt() / 100f) }
            }
        },
        valueRange = 0f..1f,
        steps = 0,
    )
    Spacer(Modifier.height(8.dp))
    if (bands.size > 1) {
        RowDivider()
        DestructiveRow(label = stringResource(R.string.equalizer_remove_band), enabled = enabled) {
            haptics.play(Haptic.Select)
            AppSettings.setEqualizerParametric(bands.filterIndexed { index, _ -> index != selected })
            onSelect((selected - 1).coerceAtLeast(0))
        }
    }
}

@Composable
private fun BandChip(
    label: String,
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background
    val foreground = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onBackground
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

/**
 * A new band goes in the widest gap between the ones already there, so it
 * lands somewhere it can be seen and grabbed rather than on top of another.
 */
private fun newBandFor(bands: List<ParametricBand>): ParametricBand {
    val edges = (listOf(ParametricLimits.MIN_HZ, ParametricLimits.MAX_HZ) + bands.map { it.frequencyHz }).sorted()
    var bestHz = 1_000f
    var widest = 0f
    for (i in 0 until edges.lastIndex) {
        val gap = ln(edges[i + 1] / edges[i])
        if (gap > widest) {
            widest = gap
            bestHz = kotlin.math.sqrt(edges[i] * edges[i + 1])
        }
    }
    return ParametricBand(FilterKind.BELL, roundFrequency(bestHz), 0f, 1f)
}

@Composable
private fun describe(band: ParametricBand): String =
    "${stringResource(band.kind.label())} · ${formatHz(band.frequencyHz)} · ${formatDb(band.gainDb)}"

private val KINDS = listOf(FilterKind.LOW_SHELF, FilterKind.BELL, FilterKind.HIGH_SHELF)

private fun FilterKind.label(): Int = when (this) {
    FilterKind.BELL -> R.string.equalizer_band_bell
    FilterKind.LOW_SHELF -> R.string.equalizer_band_low_shelf
    FilterKind.HIGH_SHELF -> R.string.equalizer_band_high_shelf
}

// ---- Pre-amp ---------------------------------------------------------------

/**
 * The pre-amp, and what it leaves of the headroom.
 *
 * The readout is the curve's loudest point after the pre-amp. Above zero the
 * limiter is what keeps it from clipping, and that is worth saying, because a
 * limiter working hard is heard as loud passages going flat.
 */
@Composable
internal fun PreampSection(
    curve: EqCurve,
    autoPreamp: Boolean,
    preampDb: Float,
    enabled: Boolean,
) {
    val haptics = rememberHaptics()
    SettingsGroup(
        header = stringResource(R.string.equalizer_preamp),
        footer = stringResource(R.string.equalizer_preamp_footer),
    ) {
        SettingsRow(
            icon = SfIcons.VolumeUp,
            title = stringResource(R.string.equalizer_auto_preamp),
            subtitle = stringResource(R.string.equalizer_auto_preamp_subtitle),
            enabled = enabled,
            trailing = {
                IosSwitch(
                    checked = autoPreamp,
                    onCheckedChange = {
                        haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                        AppSettings.setEqualizerAutoPreamp(it)
                    },
                )
            },
            onClick = {
                haptics.play(if (!autoPreamp) Haptic.ToggleOn else Haptic.ToggleOff)
                AppSettings.setEqualizerAutoPreamp(!autoPreamp)
            },
        )
        RowDivider()
        SliderRow(
            icon = SfIcons.Sliders,
            title = stringResource(R.string.equalizer_preamp),
            value = formatDb(preampDb),
            sliderValue = preampDb,
            onSliderValue = { if (enabled) AppSettings.setEqualizerPreamp(snapParametricGain(it)) },
            valueRange = -ParametricLimits.PREAMP_RANGE_DB..ParametricLimits.PREAMP_RANGE_DB,
            steps = 0,
        )
        Spacer(Modifier.height(8.dp))
        RowDivider()
        val headroom = curve.peakDb + curve.preampDb
        if (headroom > 0.05f) {
            SettingsRow(
                icon = SfIcons.Warning,
                title = stringResource(R.string.equalizer_headroom_over, formatDb(headroom)),
                subtitle = stringResource(R.string.equalizer_headroom_over_subtitle),
                enabled = enabled,
            )
        } else {
            SettingsRow(
                icon = SfIcons.Check,
                title = stringResource(R.string.equalizer_headroom_ok),
                subtitle = stringResource(R.string.equalizer_headroom_ok_subtitle, formatDb(headroom)),
                enabled = enabled,
            )
        }
    }
}

// ---- Scales ----------------------------------------------------------------

private val OCTAVES = log10(ParametricLimits.MAX_HZ / ParametricLimits.MIN_HZ)

private fun xOf(hz: Float, width: Float): Float =
    width * log10(hz.coerceIn(ParametricLimits.MIN_HZ, ParametricLimits.MAX_HZ) / ParametricLimits.MIN_HZ) / OCTAVES

private fun frequencyAt(x: Float, width: Float): Float =
    ParametricLimits.MIN_HZ * 10f.pow((x / width).coerceIn(0f, 1f) * OCTAVES)

private fun yOf(db: Float, height: Float): Float {
    val inset = 10f
    val fraction = (GRAPH_RANGE_DB - db.coerceIn(-GRAPH_RANGE_DB, GRAPH_RANGE_DB)) / (2 * GRAPH_RANGE_DB)
    return inset + (height - inset * 2) * fraction
}

private fun gainAt(y: Float, height: Float): Float {
    val inset = 10f
    val fraction = ((y - inset) / (height - inset * 2)).coerceIn(0f, 1f)
    return GRAPH_RANGE_DB - fraction * 2 * GRAPH_RANGE_DB
}

private fun labelBias(hz: Float): Float =
    (log10(hz / ParametricLimits.MIN_HZ) / OCTAVES) * 2f - 1f

private fun logFraction(value: Float, min: Float, max: Float): Float =
    (ln(value.coerceIn(min, max) / min) / ln(max / min))

private fun fromLogFraction(fraction: Float, min: Float, max: Float): Float =
    min * (max / min).pow(fraction.coerceIn(0f, 1f))

/** Three significant figures: 1.23 kHz, 456 Hz, 78.9 Hz. Finer than that is not heard. */
private fun roundFrequency(hz: Float): Float {
    val magnitude = 10f.pow(kotlin.math.floor(log10(hz)) - 2f)
    return ((hz / magnitude).roundToInt() * magnitude)
        .coerceIn(ParametricLimits.MIN_HZ, ParametricLimits.MAX_HZ)
}

/** Tenth-of-a-decibel steps with a detent at zero, the same idea as the manual faders. */
private fun snapParametricGain(raw: Float): Float {
    if (abs(raw) < 0.3f) return 0f
    return (raw * 10f).roundToInt() / 10f
}

internal fun formatHz(hz: Float): String = when {
    hz < 1_000f -> String.format(Locale.getDefault(), if (hz < 100f) "%.1f Hz" else "%.0f Hz", hz)
    else -> String.format(Locale.getDefault(), if (hz < 10_000f) "%.2f kHz" else "%.1f kHz", hz / 1_000f)
}

internal fun formatDb(db: Float): String = when {
    abs(db) < 0.05f -> "0 dB"
    db > 0 -> String.format(Locale.getDefault(), "+%.1f dB", db)
    else -> String.format(Locale.getDefault(), "%.1f dB", db)
}

private val GRAPH_HEIGHT = 190.dp
private val POINT_REACH = 28.dp
private const val GRAPH_RANGE_DB = 15f
private const val CURVE_POINTS = 140
private val GRID_HZ = floatArrayOf(50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f)
private val GRID_DB = floatArrayOf(-12f, -6f, 0f, 6f, 12f)
private val GRID_LABELS = listOf(30f to "30", 100f to "100", 300f to "300", 1_000f to "1k", 3_000f to "3k", 10_000f to "10k")

// ---- Profiles and AutoEQ ---------------------------------------------------

/**
 * Where a parametric curve comes from and goes to: AutoEQ's headphone
 * corrections, a correction file or pasted text in the same format, the
 * listener's saved profiles, and the current curve shared out as text.
 */
@Composable
internal fun ParametricProfiles(
    bands: List<ParametricBand>,
    autoPreamp: Boolean,
    preampDb: Float,
    enabled: Boolean,
    onOpenAutoEq: () -> Unit,
    onApplied: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val presets by AppSettings.equalizerParametricPresets.collectAsStateWithLifecycle()
    val current = presets.firstOrNull { it.matches(bands, autoPreamp, preampDb) }
    var pasting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun apply(name: String, text: String) {
        val parsed = AutoEqFormat.parse(text)
        if (parsed == null) {
            Toast.makeText(context, context.getString(R.string.equalizer_import_empty), Toast.LENGTH_SHORT).show()
            return
        }
        AppSettings.applyEqualizerImport(name, parsed)
        onApplied()
        Toast.makeText(context, importMessage(context, name, parsed.skipped), Toast.LENGTH_SHORT).show()
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_IMPORT_BYTES).toByteArray().decodeToString() }
        }.getOrNull()
        if (text == null) {
            Toast.makeText(context, context.getString(R.string.equalizer_import_empty), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        apply(fileName(context, uri), text)
    }

    SettingsGroup(
        header = stringResource(R.string.equalizer_profiles),
        footer = stringResource(R.string.equalizer_profiles_footer),
    ) {
        SettingsRow(
            icon = SfIcons.Bluetooth,
            title = stringResource(R.string.equalizer_autoeq),
            subtitle = stringResource(R.string.equalizer_autoeq_subtitle),
            enabled = enabled,
            onClick = onOpenAutoEq,
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.Download,
            title = stringResource(R.string.equalizer_import_file),
            enabled = enabled,
            onClick = { openFile.launch(arrayOf("text/*", "application/octet-stream")) },
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.Add,
            title = stringResource(R.string.equalizer_import_paste),
            enabled = enabled,
            onClick = { pasting = true },
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.Share,
            title = stringResource(R.string.equalizer_export),
            enabled = enabled,
            onClick = {
                val text = AutoEqFormat.format(
                    if (autoPreamp) parametricCurve(bands).withPreamp(true, preampDb).preampDb else preampDb,
                    bands,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ParametricEQ.txt")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, null))
            },
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.Add,
            title = stringResource(R.string.equalizer_save_preset),
            enabled = enabled,
            onClick = { saving = true },
        )
    }

    if (presets.isNotEmpty()) {
        SettingsGroup(header = stringResource(R.string.equalizer_my_presets)) {
            presets.forEachIndexed { index, preset ->
                if (index > 0) RowDivider()
                UserPresetRow(
                    name = preset.name,
                    selected = preset == current,
                    onClick = {
                        if (enabled) {
                            AppSettings.applyEqualizerParametricPreset(preset)
                            onApplied()
                        }
                    },
                    onDelete = { AppSettings.deleteEqualizerParametricPreset(preset) },
                )
            }
        }
    }

    if (pasting) {
        PasteCorrectionDialog(
            onApply = { name, text ->
                pasting = false
                apply(name.ifBlank { context.getString(R.string.equalizer_import_pasted) }, text)
            },
            onDismiss = { pasting = false },
        )
    }
    if (saving) {
        SavePresetDialog(
            initialName = current?.name.orEmpty(),
            message = stringResource(R.string.equalizer_save_parametric_message),
            onSave = { name ->
                haptics.play(Haptic.Select)
                AppSettings.saveEqualizerParametricPreset(name)
                saving = false
            },
            onDismiss = { saving = false },
        )
    }
}

@Composable
private fun PasteCorrectionDialog(
    onApply: (name: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.equalizer_import_paste)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.equalizer_import_paste_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(USER_EQ_PRESET_NAME_MAX) },
                    label = { Text(stringResource(R.string.equalizer_preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_IMPORT_BYTES) },
                    placeholder = { Text("Preamp: -6.1 dB\nFilter 1: ON PK Fc 105 Hz Gain 6.4 dB Q 0.70") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(name, text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.equalizer_import_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The file's own name, less its extension and the " ParametricEQ" AutoEQ appends. */
private fun fileName(context: Context, uri: Uri): String {
    val display = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    return display.substringBeforeLast('.')
        .removeSuffix(" ParametricEQ")
        .trim()
        .ifEmpty { context.getString(R.string.equalizer_import_pasted) }
        .take(USER_EQ_PRESET_NAME_MAX)
}

/** Far more than any correction needs; it keeps a wrong pick from reading a whole video into memory. */
private const val MAX_IMPORT_BYTES = 64 * 1024
