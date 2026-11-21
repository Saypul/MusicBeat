package com.musicbeat.app.playback

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The shape of one filter in [EqLayout].
 *
 * Shelves rather than bells at the two ends of the manual tab: a bell centred
 * at 60 Hz leaves 40 Hz almost untouched, so "turn the bass up" doesn't turn up
 * the part of the bass people mean. A shelf lifts everything below its corner,
 * which is what the bottom slider is asked for, and the same argument upside
 * down applies to 14 kHz.
 */
enum class FilterKind { BELL, LOW_SHELF, HIGH_SHELF }

/** A slot's fixed identity: its shape and where on the spectrum it sits. */
class FilterSlot(val kind: FilterKind, val frequencyHz: Float)

/**
 * Every filter either tab can ask for, in one fixed list.
 *
 * Every tab drives the *same* slots, and a slot the current tab doesn't use
 * is held at 0 dB rather than removed. That costs nothing — a section at 0 dB
 * is skipped outright by [EqualizerProcessor] — and it buys the thing that
 * matters: the set of running filters never changes shape, so switching tab or
 * preset is a gain change like any other and glides silently. Rebuilding the
 * cascade instead would drop filter state on the floor mid-signal, which is a
 * click every time.
 */
object EqLayout {

    /** The seven centres the manual tab puts a slider under by default. */
    val MANUAL_BANDS_HZ = floatArrayOf(60f, 150f, 400f, 1_000f, 2_500f, 6_000f, 14_000f)

    /**
     * Wide enough to overlap its neighbours — the centres are about 1.3 octaves
     * apart, so a Q near 1 means pushing every slider up lifts the whole band
     * evenly instead of leaving scallops between them.
     */
    const val MANUAL_Q = 1.0f

    /** Slots 0..31: the manual tab, however many sliders it is showing. */
    const val MANUAL_FIRST = 0

    /** The default slider count, and the one every built-in preset is written for. */
    const val MANUAL_COUNT = 7

    /** The slider counts the manual tab offers. */
    val MANUAL_BAND_COUNTS = listOf(MANUAL_COUNT, 10, 16, 32)

    const val MANUAL_MAX = 32

    /** Slots 32..34: the tone pad's tilt pair and its mid contour. */
    const val TONE_LOW = MANUAL_FIRST + MANUAL_MAX
    const val TONE_MID = TONE_LOW + 1
    const val TONE_HIGH = TONE_LOW + 2

    /**
     * Slots 35..44: the parametric tab. Unlike the others these have no fixed
     * identity — each band brings its own shape and centre — so the entries in
     * [slots] for them are only what a curve that doesn't set them falls back to.
     */
    const val PARAMETRIC_FIRST = TONE_HIGH + 1
    const val PARAMETRIC_COUNT = 10

    const val SLOTS = PARAMETRIC_FIRST + PARAMETRIC_COUNT

    val slots: List<FilterSlot> = buildList {
        MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val kind = when (index) {
                0 -> FilterKind.LOW_SHELF
                MANUAL_BANDS_HZ.lastIndex -> FilterKind.HIGH_SHELF
                else -> FilterKind.BELL
            }
            add(FilterSlot(kind, hz))
        }
        repeat(MANUAL_MAX - MANUAL_COUNT) { add(FilterSlot(FilterKind.BELL, 1_000f)) }
        add(FilterSlot(FilterKind.LOW_SHELF, 250f))
        add(FilterSlot(FilterKind.BELL, 1_000f))
        add(FilterSlot(FilterKind.HIGH_SHELF, 4_000f))
        repeat(PARAMETRIC_COUNT) { add(FilterSlot(FilterKind.BELL, 1_000f)) }
    }

    /**
     * Where [count] sliders sit. Seven keeps the original centres; the others
     * are evenly spaced on a log scale up to 16 kHz — ten is the octave layout
     * a hardware graphic EQ uses (31 Hz, 62 Hz, 125 Hz…), and 16 and 32 split
     * 25 Hz–16 kHz into two-thirds and one-third octave steps, near enough.
     */
    fun manualBandsHz(count: Int): FloatArray {
        if (count == MANUAL_COUNT || count !in MANUAL_BAND_COUNTS) return MANUAL_BANDS_HZ
        val low = if (count == 10) 31.25 else 25.0
        val ratio = (16_000.0 / low).pow(1.0 / (count - 1))
        return FloatArray(count) { (low * ratio.pow(it)).toFloat() }
    }

    /**
     * The bandwidth that lets [count] neighbouring bells meet where they
     * overlap, from their spacing in octaves. Seven lands on [MANUAL_Q] itself;
     * 32 bands a third of an octave apart need a Q near 4.3.
     */
    fun manualQ(count: Int): Float {
        if (count == MANUAL_COUNT || count !in MANUAL_BAND_COUNTS) return MANUAL_Q
        val hz = manualBandsHz(count)
        val ratio = (hz.last() / hz.first()).toDouble().pow(1.0 / (count - 1))
        return (sqrt(ratio) / (ratio - 1)).toFloat()
    }

    /**
     * [bandsDb] moved onto [count] sliders by reading the old sliders as a line
     * across a log-frequency axis and sampling it at the new centres. Lets a
     * seven-band preset or a saved curve land on whichever layout is showing,
     * and keeps the shape when the listener changes how many sliders there are.
     */
    fun resampleBands(bandsDb: List<Float>, count: Int): List<Float> {
        val target = if (count in MANUAL_BAND_COUNTS) count else MANUAL_COUNT
        val source = if (bandsDb.size in MANUAL_BAND_COUNTS) {
            bandsDb
        } else {
            List(MANUAL_COUNT) { bandsDb.getOrElse(it) { 0f } }
        }
        if (source.size == target) return source
        val fromHz = manualBandsHz(source.size)
        return manualBandsHz(target).map { hz ->
            when {
                hz <= fromHz.first() -> source.first()
                hz >= fromHz.last() -> source.last()
                else -> {
                    val upper = fromHz.indexOfFirst { it >= hz }
                    val lower = upper - 1
                    val fraction = log10(hz / fromHz[lower]) / log10(fromHz[upper] / fromHz[lower])
                    source[lower] + (source[upper] - source[lower]) * fraction
                }
            }
        }
    }

    /** How far a manual slider travels either way. */
    const val MANUAL_RANGE_DB = 12f

    /** How far the tone pad travels on each axis, in whole steps. */
    const val TONE_STEPS = 5

    /** Decibels per step of the pad, so a corner is ±6 dB. */
    const val TONE_DB_PER_STEP = 1.2f

    /** Frequency of the tone pad's tilt pair, for the UI to describe. */
    val TONE_TILT_HZ = floatArrayOf(250f, 4_000f)
}

/**
 * A tuning, rendered: what every slot in [EqLayout] is set to right now.
 *
 * Gains and Qs both, because Broad/Focused moves Q while the pad stays put —
 * see [toneCurve]. Shapes and centres too, for the parametric slots, whose
 * bands bring their own; every other slot keeps the one [EqLayout] gave it.
 */
class EqCurve(
    val gainsDb: FloatArray,
    val qs: FloatArray,
    val kinds: Array<FilterKind>,
    val frequenciesHz: FloatArray,
    /** The loudest point of the summed response, in decibels; zero for a curve that only cuts. */
    val peakDb: Float,
    /**
     * Make-up attenuation, always at or below zero: half of however far the
     * curve's loudest point was pushed up.
     *
     * Half rather than all of it. Taking the whole boost back assumes the
     * music has full-scale content sitting exactly on the boosted frequency,
     * which almost nothing does, and the price of that assumption was every
     * boost reading as "the track got quieter" — a +6 dB bass lift left the
     * bass where it was and turned everything else down 6 dB. The overs the
     * remaining half lets through are rare and brief, and [PeakLimiter] takes
     * them instead.
     */
    val preampDb: Float,
) {
    /** A curve on [EqLayout]'s own shapes and centres with a pre-amp of the caller's choosing. */
    constructor(gainsDb: FloatArray, qs: FloatArray, preampDb: Float) : this(
        gainsDb,
        qs,
        Array(EqLayout.SLOTS) { EqLayout.slots[it].kind },
        FloatArray(EqLayout.SLOTS) { EqLayout.slots[it].frequencyHz },
        0f,
        preampDb,
    )

    fun slot(index: Int): FilterSlot = FilterSlot(kinds[index], frequenciesHz[index])

    /**
     * The same curve behind a different pre-amp: the automatic make-up when
     * [auto], or none, and [trimDb] on top of either. The listener's own
     * pre-amp slider, and what an imported correction asks for.
     */
    fun withPreamp(auto: Boolean, trimDb: Float): EqCurve {
        val base = if (auto) -peakDb * MAKEUP_FRACTION else 0f
        return EqCurve(gainsDb, qs, kinds, frequenciesHz, peakDb, base + trimDb)
    }

    companion object {
        val FLAT = of(FloatArray(EqLayout.SLOTS), FloatArray(EqLayout.SLOTS) { 0.707f })

        /** Builds a curve and works out the attenuation it needs. */
        fun of(
            gainsDb: FloatArray,
            qs: FloatArray,
            kinds: Array<FilterKind> = Array(EqLayout.SLOTS) { EqLayout.slots[it].kind },
            frequenciesHz: FloatArray = FloatArray(EqLayout.SLOTS) { EqLayout.slots[it].frequencyHz },
        ): EqCurve {
            val peak = peakOf(gainsDb, qs, kinds, frequenciesHz)
            return EqCurve(gainsDb, qs, kinds, frequenciesHz, peak, -peak * MAKEUP_FRACTION)
        }
    }

    /** The summed response at [hz], in decibels, before the pre-amp. */
    fun responseDb(hz: Float): Float {
        var sum = 0f
        for (slot in 0 until EqLayout.SLOTS) sum += sectionGainDb(slot(slot), gainsDb[slot], qs[slot], hz)
        return sum
    }
}

/**
 * The sliders, as a curve: as many as [bandsDb] has, if that is one of
 * [EqLayout.MANUAL_BAND_COUNTS], else the first seven. Slots the tone pad owns
 * stay flat.
 */
fun manualCurve(bandsDb: List<Float>): EqCurve {
    val count = if (bandsDb.size in EqLayout.MANUAL_BAND_COUNTS) bandsDb.size else EqLayout.MANUAL_COUNT
    val gains = FloatArray(EqLayout.SLOTS)
    val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
    val kinds = Array(EqLayout.SLOTS) { EqLayout.slots[it].kind }
    val frequencies = FloatArray(EqLayout.SLOTS) { EqLayout.slots[it].frequencyHz }
    val centres = EqLayout.manualBandsHz(count)
    val q = EqLayout.manualQ(count)
    for (band in 0 until count) {
        val slot = EqLayout.MANUAL_FIRST + band
        gains[slot] = bandsDb.getOrElse(band) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        kinds[slot] = when (band) {
            0 -> FilterKind.LOW_SHELF
            count - 1 -> FilterKind.HIGH_SHELF
            else -> FilterKind.BELL
        }
        // A shelf as narrow as a third-octave bell would ring at its corner.
        qs[slot] = if (kinds[slot] == FilterKind.BELL) q else minOf(q, EqLayout.MANUAL_Q)
        frequencies[slot] = centres[band]
    }
    return EqCurve.of(gains, qs, kinds, frequencies)
}

/**
 * The tone pad, as a curve. Slots the manual tab owns stay flat.
 *
 * [x] tilts: left is warm (low shelf up, high shelf down), right is bright, and
 * the two move by the same amount in opposite directions so the middle of the
 * spectrum — where most of a mix lives — keeps its level whichever way the puck
 * goes. [y] is the contour: down scoops the mids into a V, up pushes them
 * forward.
 *
 * [focused] narrows all three. Apple describes the two settings as wider ranges
 * with a richer sound against specific ranges with less distraction, which is a
 * description of bandwidth and nothing else, so that is the only thing it
 * changes.
 */
fun toneCurve(x: Int, y: Int, focused: Boolean): EqCurve {
    val gains = FloatArray(EqLayout.SLOTS)
    val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
    val steps = EqLayout.TONE_STEPS
    val tilt = x.coerceIn(-steps, steps) * EqLayout.TONE_DB_PER_STEP
    val contour = y.coerceIn(-steps, steps) * EqLayout.TONE_DB_PER_STEP

    gains[EqLayout.TONE_LOW] = -tilt
    gains[EqLayout.TONE_HIGH] = tilt
    gains[EqLayout.TONE_MID] = contour

    val shelfQ = if (focused) 0.9f else 0.5f
    val bellQ = if (focused) 2.2f else 0.7f
    qs[EqLayout.TONE_LOW] = shelfQ
    qs[EqLayout.TONE_HIGH] = shelfQ
    qs[EqLayout.TONE_MID] = bellQ
    return EqCurve.of(gains, qs)
}

/**
 * One band of the parametric tab: a filter the listener places anywhere.
 *
 * [enabled] off keeps the band's settings but runs it at 0 dB, so switching
 * one out to compare glides like any other change.
 */
@Serializable
data class ParametricBand(
    val kind: FilterKind = FilterKind.BELL,
    val frequencyHz: Float = 1_000f,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
) {
    /** The same band with every value pulled inside what the processor accepts. */
    fun clamped(): ParametricBand = copy(
        frequencyHz = frequencyHz.coerceIn(ParametricLimits.MIN_HZ, ParametricLimits.MAX_HZ),
        gainDb = gainDb.coerceIn(-ParametricLimits.RANGE_DB, ParametricLimits.RANGE_DB),
        q = q.coerceIn(ParametricLimits.MIN_Q, ParametricLimits.MAX_Q),
    )
}

object ParametricLimits {
    const val MIN_HZ = 20f
    const val MAX_HZ = 20_000f
    const val RANGE_DB = 15f
    const val MIN_Q = 0.1f
    const val MAX_Q = 10f

    /** How far the pre-amp slider travels either way. */
    const val PREAMP_RANGE_DB = 15f

    /** Where a new parametric tab starts: five flat bands spread across the spectrum. */
    val DEFAULT_BANDS = listOf(
        ParametricBand(FilterKind.LOW_SHELF, 80f, 0f, 0.707f),
        ParametricBand(FilterKind.BELL, 250f, 0f, 1f),
        ParametricBand(FilterKind.BELL, 1_000f, 0f, 1f),
        ParametricBand(FilterKind.BELL, 4_000f, 0f, 1f),
        ParametricBand(FilterKind.HIGH_SHELF, 10_000f, 0f, 0.707f),
    )
}

/** The parametric bands, as a curve. At most [EqLayout.PARAMETRIC_COUNT] are used. */
fun parametricCurve(bands: List<ParametricBand>): EqCurve {
    val gains = FloatArray(EqLayout.SLOTS)
    val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
    val kinds = Array(EqLayout.SLOTS) { EqLayout.slots[it].kind }
    val frequencies = FloatArray(EqLayout.SLOTS) { EqLayout.slots[it].frequencyHz }
    bands.take(EqLayout.PARAMETRIC_COUNT).forEachIndexed { index, raw ->
        val band = raw.clamped()
        val slot = EqLayout.PARAMETRIC_FIRST + index
        gains[slot] = if (band.enabled) band.gainDb else 0f
        qs[slot] = band.q
        kinds[slot] = band.kind
        frequencies[slot] = band.frequencyHz
    }
    return EqCurve.of(gains, qs, kinds, frequencies)
}

/**
 * The curve's loudest point, at or above zero.
 *
 * Taking the largest single band gain would under-read it: the bands overlap,
 * so two neighbours at +6 dB are worth more than +6 dB between them. This walks
 * the summed response instead — magnitudes multiply through a cascade, so
 * decibels add — and takes its actual peak. Runs on whoever changed the
 * setting, never on the audio thread.
 */
private fun peakOf(
    gainsDb: FloatArray,
    qs: FloatArray,
    kinds: Array<FilterKind>,
    frequenciesHz: FloatArray,
): Float {
    var peak = 0f
    for (point in 0 until RESPONSE_POINTS) {
        val hz = responseFrequency(point)
        var sum = 0f
        for (slot in 0 until EqLayout.SLOTS) {
            sum += sectionGainDb(FilterSlot(kinds[slot], frequenciesHz[slot]), gainsDb[slot], qs[slot], hz)
        }
        if (sum > peak) peak = sum
    }
    return peak
}

/** Log-spaced across the audible band: 20 Hz to 20 kHz. */
private fun responseFrequency(point: Int): Float {
    val fraction = point.toDouble() / (RESPONSE_POINTS - 1)
    return (20.0 * (1_000.0).pow(fraction)).toFloat()
}

/**
 * One section's contribution at [hz], in decibels.
 *
 * The analog prototypes these are the magnitudes of are exactly what
 * [EqualizerProcessor]'s state-variable mixes realise — the bilinear transform
 * only bends the frequency axis near Nyquist, which is nowhere near where a
 * peak worth attenuating for lands.
 */
internal fun sectionGainDb(slot: FilterSlot, gainDb: Float, q: Float, hz: Float): Float {
    if (abs(gainDb) < 0.01f) return 0f
    val a = 10.0.pow(gainDb / 40.0)
    val a2 = a * a
    val x = (hz / slot.frequencyHz).toDouble()
    val x2 = x * x
    val qq = (q * q).toDouble()
    val magnitude = when (slot.kind) {
        FilterKind.BELL -> {
            val flat = (1 - x2) * (1 - x2)
            sqrt((flat + x2 * a2 / qq) / (flat + x2 / (a2 * qq)))
        }
        FilterKind.LOW_SHELF -> {
            val common = x2 * a / qq
            a * sqrt(((a - x2) * (a - x2) + common) / ((1 - a * x2) * (1 - a * x2) + common))
        }
        FilterKind.HIGH_SHELF -> {
            val common = x2 * a / qq
            a * sqrt(((1 - a * x2) * (1 - a * x2) + common) / ((a - x2) * (a - x2) + common))
        }
    }
    return (20.0 * log10(magnitude)).toFloat()
}

/** How much of a curve's peak boost the automatic make-up takes back; [PeakLimiter] covers the rest. */
private const val MAKEUP_FRACTION = 0.5f

/** Enough to find the peak of a curve this smooth without wasting time on it. */
private const val RESPONSE_POINTS = 240
