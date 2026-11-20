package com.musicbeat.app

import com.musicbeat.app.data.settings.EqSnapshot
import com.musicbeat.app.data.settings.EqualizerCodec
import com.musicbeat.app.data.settings.UserEqPreset
import com.musicbeat.app.playback.FilterKind
import com.musicbeat.app.playback.ParametricBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerProfilesTest {

    private val bands = listOf(4f, 2.5f, 0f, -1f, 0f, 1.5f, 3f)

    @Test
    fun `saved presets read back as they were written`() {
        val presets = listOf(UserEqPreset("Kereta", bands), UserEqPreset("Night", List(7) { 0f }))
        assertEquals(presets, EqualizerCodec.presets(EqualizerCodec.presets(presets)))
    }

    @Test
    fun `per-device profiles read back by output`() {
        val profiles = mapOf(
            "PHONE" to EqSnapshot(false, "DYNAMIC", 0, 0, false, 0f, List(7) { 0f }),
            "BLUETOOTH:Galaxy Buds2" to EqSnapshot(true, "MANUAL", 2, -1, true, -0.2f, bands),
        )
        assertEquals(profiles, EqualizerCodec.profiles(EqualizerCodec.profiles(profiles)))
    }

    @Test
    fun `a profile saved before the parametric tab still reads`() {
        val old = """{"PHONE":{"enabled":true,"mode":"MANUAL","toneX":0,"toneY":0,"focused":false,"balance":0.0,"bands":[1,2,3,4,5,6,7]}}"""
        val profile = EqualizerCodec.profiles(old).getValue("PHONE")
        assertEquals(null, profile.parametric)
        assertTrue(profile.autoPreamp)
        assertEquals(0f, profile.preampDb)
    }

    @Test
    fun `parametric bands read back as they were written`() {
        val bands = listOf(
            ParametricBand(FilterKind.LOW_SHELF, 105f, -2.3f, 0.7f),
            ParametricBand(FilterKind.BELL, 3_400f, 4f, 2.5f, enabled = false),
        )
        assertEquals(bands, EqualizerCodec.parametric(EqualizerCodec.parametric(bands)))
        assertEquals(null, EqualizerCodec.parametric("nonsense"))
    }

    @Test
    fun `nothing stored or something unreadable is no presets rather than a crash`() {
        assertTrue(EqualizerCodec.presets(null).isEmpty())
        assertTrue(EqualizerCodec.presets("not json").isEmpty())
        assertTrue(EqualizerCodec.profiles("{\"PHONE\":42}").isEmpty())
    }

    @Test
    fun `a preset matches its own curve and not a different one`() {
        val preset = UserEqPreset("Kereta", bands)
        assertTrue(preset.matches(bands.map { it + 0.01f }))
        assertFalse(preset.matches(bands.map { it + 0.5f }))
        assertFalse(preset.matches(bands.dropLast(1)))
    }
}
