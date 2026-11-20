package com.musicbeat.app.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A headphone correction in the text format AutoEQ publishes and Equalizer APO
 * reads, as far as the parametric tab can hold it.
 *
 * ```
 * Preamp: -6.1 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
 * Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
 * ```
 *
 * [skipped] counts the filters that were read and left out: a type the
 * processor has no shape for (pass and notch filters), or more bands than
 * [EqLayout.PARAMETRIC_COUNT]. The caller says so rather than pretending the
 * correction arrived whole.
 */
data class EqImport(
    val preampDb: Float,
    val bands: List<ParametricBand>,
    val skipped: Int,
)

object AutoEqFormat {

    private val preampLine = Regex("""(?i)^\s*preamp\s*:\s*([-+]?\d+(?:\.\d+)?)\s*db""")
    private val filterLine = Regex(
        """(?i)^\s*filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)(?:\s+\d+\s*db)?\s+fc\s+([\d.]+)\s*hz""" +
            """(?:\s+gain\s+([-+]?[\d.]+)\s*db)?(?:\s+q\s+([\d.]+))?""",
    )

    /** Null when the text holds no filter this can use, so a wrong file is an error and not a flat curve. */
    fun parse(text: String): EqImport? {
        var preamp = 0f
        val read = mutableListOf<ParametricBand>()
        var skipped = 0
        for (line in text.lineSequence()) {
            val preampMatch = preampLine.find(line)
            if (preampMatch != null) {
                preampMatch.groupValues[1].toFloatOrNull()?.let { preamp += it }
                continue
            }
            val match = filterLine.find(line) ?: continue
            val on = match.groupValues[1].equals("on", ignoreCase = true)
            val kind = kindOf(match.groupValues[2])
            val hz = match.groupValues[3].toFloatOrNull()
            val gain = match.groupValues[4].toFloatOrNull()
            if (!on) continue
            if (kind == null || hz == null || gain == null) {
                skipped++
                continue
            }
            val q = match.groupValues[5].toFloatOrNull()
                ?: if (kind == FilterKind.BELL) 1f else SHELF_Q
            read += ParametricBand(kind, hz, gain, q).clamped()
        }
        if (read.isEmpty()) return null
        // Over the limit, the bands doing the least go: AutoEQ lists its
        // filters roughly by importance, but not strictly, and a 0.2 dB
        // correction is the one nobody will miss.
        val kept = if (read.size <= EqLayout.PARAMETRIC_COUNT) {
            read
        } else {
            val keep = read.indices.sortedByDescending { abs(read[it].gainDb) }
                .take(EqLayout.PARAMETRIC_COUNT)
                .toSet()
            read.filterIndexed { index, _ -> index in keep }
        }
        return EqImport(
            preampDb = preamp.coerceIn(-ParametricLimits.PREAMP_RANGE_DB, ParametricLimits.PREAMP_RANGE_DB),
            bands = kept,
            skipped = skipped + (read.size - kept.size),
        )
    }

    /** The bands and pre-amp in the same format, for another app or another phone to read. */
    fun format(preampDb: Float, bands: List<ParametricBand>): String = buildString {
        append("Preamp: ").append(number(preampDb)).append(" dB\n")
        bands.forEachIndexed { index, band ->
            append("Filter ").append(index + 1).append(": ")
            append(if (band.enabled) "ON " else "OFF ")
            append(
                when (band.kind) {
                    FilterKind.BELL -> "PK"
                    FilterKind.LOW_SHELF -> "LSC"
                    FilterKind.HIGH_SHELF -> "HSC"
                },
            )
            append(" Fc ").append(band.frequencyHz.roundToInt()).append(" Hz")
            append(" Gain ").append(number(band.gainDb)).append(" dB")
            append(" Q ").append(number(band.q, places = 2)).append('\n')
        }
    }

    private fun kindOf(type: String): FilterKind? = when (type.uppercase()) {
        "PK", "PEQ", "MODAL" -> FilterKind.BELL
        "LS", "LSC", "LSQ", "LOWSHELF" -> FilterKind.LOW_SHELF
        "HS", "HSC", "HSQ", "HIGHSHELF" -> FilterKind.HIGH_SHELF
        else -> null
    }

    /** Locale-free on purpose: the file is read by machines that expect a full stop. */
    private fun number(value: Float, places: Int = 1): String {
        val scale = if (places == 2) 100 else 10
        val scaled = (value * scale).roundToInt()
        val sign = if (scaled < 0) "-" else ""
        val whole = abs(scaled) / scale
        val fraction = (abs(scaled) % scale).toString().padStart(places, '0')
        return "$sign$whole.$fraction"
    }

    private const val SHELF_Q = 0.707f
}

/**
 * One headphone in AutoEQ's published results.
 *
 * [path] is as the index writes it, already escaped for a URL, and [source] is
 * who measured it — the same model is often listed more than once, by
 * different people, and the listener picks whose measurement they trust.
 */
data class AutoEqEntry(val name: String, val source: String, val path: String) {
    /** Where its parametric correction is, in the published results. */
    val parametricUrl: String
        get() = "${AutoEqIndex.RESULTS}/$path/${path.substringAfterLast('/')}%20ParametricEQ.txt"
}

object AutoEqIndex {
    const val RESULTS = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    const val INDEX_URL = "$RESULTS/INDEX.md"

    private val entryLine = Regex("""^- \[(.+)]\(\./(.+)\) by (.+)$""")

    /** Each `- [Name](./path) by Source` line of the index, in its order. */
    fun parse(markdown: String): List<AutoEqEntry> = markdown.lineSequence()
        .mapNotNull { line ->
            val match = entryLine.find(line.trimEnd()) ?: return@mapNotNull null
            AutoEqEntry(match.groupValues[1], match.groupValues[3], match.groupValues[2])
        }
        .toList()

    /**
     * Every word of [query] somewhere in the name, case and spacing ignored,
     * so "hd650", "HD 650" and "sennheiser 650" all find the HD 650.
     */
    fun search(entries: List<AutoEqEntry>, query: String, limit: Int = 200): List<AutoEqEntry> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { entry ->
                val name = entry.name.lowercase()
                val squashed = name.replace(" ", "")
                words.all { it in name || it in squashed }
            }
            .take(limit)
            .toList()
    }
}
