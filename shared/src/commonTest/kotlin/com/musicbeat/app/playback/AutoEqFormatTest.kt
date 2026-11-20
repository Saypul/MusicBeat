package com.musicbeat.app.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoEqFormatTest {

    private val hd650 = """
        Preamp: -6.1 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
        Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
        Filter 3: ON PK Fc 118 Hz Gain -3.1 dB Q 0.50
        Filter 4: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
    """.trimIndent()

    @Test
    fun readsAnAutoEqCorrection() {
        val parsed = AutoEqFormat.parse(hd650)!!
        assertEquals(-6.1f, parsed.preampDb)
        assertEquals(0, parsed.skipped)
        assertEquals(
            listOf(
                ParametricBand(FilterKind.LOW_SHELF, 105f, 6.4f, 0.70f),
                ParametricBand(FilterKind.BELL, 8_800f, 5.1f, 1.42f),
                ParametricBand(FilterKind.BELL, 118f, -3.1f, 0.50f),
                ParametricBand(FilterKind.HIGH_SHELF, 10_000f, -2.1f, 0.70f),
            ),
            parsed.bands,
        )
    }

    @Test
    fun skipsWhatItCannotPlayAndSaysSo() {
        val text = """
            Filter 1: ON PK Fc 1000 Hz Gain 3 dB Q 1
            Filter 2: ON LP Fc 18000 Hz
            Filter 3: OFF PK Fc 200 Hz Gain 4 dB Q 1
        """.trimIndent()
        val parsed = AutoEqFormat.parse(text)!!
        assertEquals(1, parsed.bands.size)
        assertEquals(1, parsed.skipped)
        assertEquals(0f, parsed.preampDb)
    }

    @Test
    fun keepsTheTenBandsDoingTheMost() {
        val text = (1..12).joinToString("\n") { "Filter $it: ON PK Fc ${it * 100} Hz Gain ${it * 0.5} dB Q 1" }
        val parsed = AutoEqFormat.parse(text)!!
        assertEquals(EqLayout.PARAMETRIC_COUNT, parsed.bands.size)
        assertEquals(2, parsed.skipped)
        assertEquals(300f, parsed.bands.first().frequencyHz)
    }

    @Test
    fun anythingElseIsNothing() {
        assertNull(AutoEqFormat.parse("hello"))
        assertNull(AutoEqFormat.parse(""))
    }

    @Test
    fun writesWhatItReads() {
        val bands = AutoEqFormat.parse(hd650)!!.bands
        val again = AutoEqFormat.parse(AutoEqFormat.format(-6.1f, bands))!!
        assertEquals(bands, again.bands)
        assertEquals(-6.1f, again.preampDb)
    }

    @Test
    fun readsTheIndexAndFindsAHeadphoneHoweverItIsTyped() {
        val index = """
            # Index
            - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
            - [Sony WH-1000XM4](./rtings/over-ear/Sony%20WH-1000XM4) by rtings
        """.trimIndent()
        val entries = AutoEqIndex.parse(index)
        assertEquals(2, entries.size)
        assertEquals("oratory1990", entries[0].source)
        assertEquals(
            "${AutoEqIndex.RESULTS}/oratory1990/over-ear/Sennheiser%20HD%20650/Sennheiser%20HD%20650%20ParametricEQ.txt",
            entries[0].parametricUrl,
        )
        for (query in listOf("hd650", "HD 650", "sennheiser 650")) {
            assertEquals(listOf(entries[0]), AutoEqIndex.search(entries, query), query)
        }
        assertTrue(AutoEqIndex.search(entries, " ").isEmpty())
    }

    @Test
    fun readsTheWholePublishedIndex() {
        // A real line from the index, with brackets inside the name.
        val line = "- [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045"
        val entry = AutoEqIndex.parse(line).single()
        assertEquals("1MORE Aero (ANC Off)", entry.name)
        assertEquals("HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)", entry.path)
    }
}
