package com.musicbeat.app

import com.musicbeat.app.data.LocalMediaRepository.localQualityOf
import com.musicbeat.app.data.model.LocalQuality
import com.musicbeat.app.data.model.Song
import com.musicbeat.app.ui.screens.LocalQualityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalQualityTest {

    private fun of(
        name: String,
        mime: String? = null,
        bitrate: Long? = null,
        bits: Int? = null,
        rate: Int? = null,
    ) = localQualityOf(name, mime, bitrate, bits, rate)

    @Test
    fun lossyFilesGetNoBadge() {
        assertNull(of("song.mp3", "audio/mpeg", 320_000))
        assertNull(of("song.opus", "audio/ogg", 160_000))
        // AAC in an .m4a, at the top of what AAC encoders go to.
        assertNull(of("song.m4a", "audio/mp4", 512_000))
    }

    @Test
    fun cdResolutionIsLossless() {
        assertEquals(LocalQuality.LOSSLESS, of("song.flac", "audio/flac", 900_000, 16, 44_100))
        assertEquals(LocalQuality.LOSSLESS, of("song.flac", "audio/flac", bits = 16, rate = 48_000))
    }

    @Test
    fun deeperOrFasterThanCdIsHiRes() {
        assertEquals(LocalQuality.HI_RES, of("song.flac", "audio/flac", 2_800_000, 24, 96_000))
        assertEquals(LocalQuality.HI_RES, of("song.flac", "audio/flac", bits = 24, rate = 44_100))
        assertEquals(LocalQuality.HI_RES, of("song.flac", "audio/flac", bits = 16, rate = 88_200))
    }

    @Test
    fun withoutTheSampleFormatTheBitrateDecides() {
        assertEquals(LocalQuality.LOSSLESS, of("song.flac", "audio/flac", 1_000_000))
        assertEquals(LocalQuality.HI_RES, of("song.flac", "audio/flac", 3_000_000))
        // Nothing reported at all: lossless is all that can be said.
        assertEquals(LocalQuality.LOSSLESS, of("song.flac"))
    }

    @Test
    fun alacIsToldFromAacByBitrate() {
        assertEquals(LocalQuality.LOSSLESS, of("song.m4a", "audio/mp4", 850_000))
        assertNull(of("song.m4a", "audio/mp4"))
    }

    @Test
    fun dsdIsAlwaysHiRes() {
        assertEquals(LocalQuality.HI_RES, of("song.dsf"))
        assertEquals(LocalQuality.HI_RES, of("SONG.DFF"))
    }

    @Test
    fun filterAdmitsByTier() {
        fun song(q: LocalQuality?) = Song("id", "t", "a", null, localQuality = q)
        val mp3 = song(null)
        val flac = song(LocalQuality.LOSSLESS)
        val hiRes = song(LocalQuality.HI_RES)
        assertTrue(listOf(mp3, flac, hiRes).all(LocalQualityFilter.ALL::admits))
        assertFalse(LocalQualityFilter.LOSSLESS.admits(mp3))
        assertTrue(LocalQualityFilter.LOSSLESS.admits(flac) && LocalQualityFilter.LOSSLESS.admits(hiRes))
        assertFalse(LocalQualityFilter.HI_RES.admits(flac))
        assertTrue(LocalQualityFilter.HI_RES.admits(hiRes))
    }
}
