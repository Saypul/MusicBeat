package com.musicbeat.app

import com.musicbeat.app.playback.AutoEqEntry
import com.musicbeat.app.playback.AutoEqFormat
import com.musicbeat.app.playback.AutoEqIndex
import com.musicbeat.app.playback.FilterKind
import com.musicbeat.app.playback.ParametricBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEqFormatTest {

    private val hd650 = """
        Preamp: -6.1 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
        Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
        Filter 3: ON PK Fc 118 Hz Gain -3.1 dB Q 0.50
        Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
    """.trimIndent()

    @Test
    fun `an AutoEQ correction reads as its bands and preamp`() {
        val parsed = AutoEqFormat.parse(hd650)!!
        assertEquals(-6.1f, parsed.preampDb, 0.001f)
        assertEquals(0, parsed.skipped)
        assertEquals(ParametricBand(FilterKind.LOW_SHELF, 105f, 6.4f, 0.7f), parsed.bands[0])
        assertEquals(ParametricBand(FilterKind.BELL, 8_800f, 5.1f, 1.42f), parsed.bands[1])
        assertEquals(FilterKind.HIGH_SHELF, parsed.bands[3].kind)
    }

    @Test
    fun `filters it has no shape for are counted, not silently dropped`() {
        val parsed = AutoEqFormat.parse("Filter 1: ON PK Fc 100 Hz Gain 2 dB Q 1\nFilter 2: ON LP Fc 18000 Hz\nFilter 3: OFF PK Fc 50 Hz Gain 3 dB Q 1")!!
        assertEquals(1, parsed.bands.size)
        assertEquals(1, parsed.skipped)
    }

    @Test
    fun `more than ten filters keeps the ten doing the most`() {
        val text = (1..12).joinToString("\n") { "Filter $it: ON PK Fc ${it * 500} Hz Gain ${if (it <= 2) 0.1 else it.toDouble()} dB Q 1" }
        val parsed = AutoEqFormat.parse(text)!!
        assertEquals(10, parsed.bands.size)
        assertEquals(2, parsed.skipped)
        assertEquals(1_500f, parsed.bands.first().frequencyHz)
    }

    @Test
    fun `text with no filters is not a correction`() {
        assertNull(AutoEqFormat.parse("hello"))
        assertNull(AutoEqFormat.parse("Preamp: -3 dB"))
    }

    @Test
    fun `what is written out reads back the same`() {
        val parsed = AutoEqFormat.parse(hd650)!!
        val again = AutoEqFormat.parse(AutoEqFormat.format(parsed.preampDb, parsed.bands))!!
        assertEquals(parsed, again)
    }

    @Test
    fun `the index lists each headphone with its source and file`() {
        val entries = AutoEqIndex.parse(
            """
            # Index
            - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
            - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals(AutoEqEntry("Sennheiser HD 650", "oratory1990", "oratory1990/over-ear/Sennheiser%20HD%20650"), entries[0])
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/oratory1990/over-ear/" +
                "Sennheiser%20HD%20650/Sennheiser%20HD%20650%20ParametricEQ.txt",
            entries[0].parametricUrl,
        )
        assertEquals("1MORE Aero (ANC Off)", entries[1].name)
        assertEquals(listOf(entries[0]), AutoEqIndex.search(entries, "hd650"))
        assertEquals(listOf(entries[0]), AutoEqIndex.search(entries, "sennheiser 650"))
    }
}
