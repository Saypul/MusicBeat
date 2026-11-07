package com.musicbeat.app

import com.musicbeat.app.data.settings.AudioQuality
import com.musicbeat.app.data.settings.permits
import com.musicbeat.app.data.sources.InternetArchiveTracks
import com.musicbeat.app.data.sources.InternetArchiveTracks.File
import com.musicbeat.app.data.sources.SourceConfig
import com.musicbeat.app.data.sources.SourceKind
import com.musicbeat.app.data.sources.SourceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File lists below are trimmed from what archive.org's metadata API returned for real items. */
class InternetArchiveSourceTest {

    private val concert = listOf(
        File("gd76-06-09d1t02.flac", "Flac", length = "312.5", track = "2"),
        File("gd76-06-09d1t01.flac", "Flac", length = "451.95", track = "1"),
        File("gd76-06-09d1t01.mp3", "VBR MP3", original = "gd76-06-09d1t01.flac"),
        File("gd76-06-09d1t01.ffp", "Flac FingerPrint"),
        File("gd76-06-09d1t01.png", "PNG"),
    )

    @Test
    fun onlyFlacFilesAreTracksInRunningOrder() {
        val tracks = InternetArchiveTracks.tracks("gd76-06-09d1t01", "Live at Boston", "Grateful Dead", concert)
        assertEquals(listOf("gd76-06-09d1t01.flac", "gd76-06-09d1t02.flac"), tracks.map { it.file.name })
        // No title tag: the file name stands in.
        assertEquals("gd76-06-09d1t01", tracks.first().title)
        assertEquals("Grateful Dead", tracks.first().artist)
    }

    @Test
    fun aSixteenBitCopyOfAHiResUploadIsNotASecondTrack() {
        val files = listOf(
            File("01 My Own Street.flac", "24bit Flac", title = "My Own Street"),
            File("01 My Own Street_16.flac", "Flac", original = "01 My Own Street.flac"),
            File("02 Hope.flac", "Flac", title = "Hope"),
        )
        val tracks = InternetArchiveTracks.tracks("vysota", null, null, files)
        assertEquals(listOf("My Own Street", "Hope"), tracks.map { it.title })
        assertTrue(InternetArchiveTracks.isHiResFormat(tracks.first().file.format))
        assertFalse(InternetArchiveTracks.isHiResFormat(tracks.last().file.format))
    }

    @Test
    fun lengthReadsSecondsOrClock() {
        assertEquals(451, File("a.flac", "Flac", length = "451.95").lengthSec)
        assertEquals(381, File("a.flac", "Flac", length = "06:21").lengthSec)
        assertEquals(3_723, File("a.flac", "Flac", length = "1:02:03").lengthSec)
        assertNull(File("a.flac", "Flac").lengthSec)
    }

    @Test
    fun aQueryNamingASongKeepsThatSong() {
        val files = listOf(
            File("t01.flac", "Flac", title = "Scarlet Begonias", track = "1"),
            File("t02.flac", "Flac", title = "Fire on the Mountain", track = "2"),
        )
        val tracks = InternetArchiveTracks.tracks("gd", "Grateful Dead Live", "Grateful Dead", files)
        assertEquals(
            listOf("Scarlet Begonias"),
            InternetArchiveTracks.narrow(tracks, "grateful dead scarlet begonias").map { it.title },
        )
        // Naming only the band leaves the whole show.
        assertEquals(2, InternetArchiveTracks.narrow(tracks, "grateful dead").size)
        // A song the show doesn't have leaves it whole rather than empty.
        assertEquals(2, InternetArchiveTracks.narrow(tracks, "grateful dead ripple").size)
    }

    @Test
    fun theArchiveIsOptInAndNeverStandsInForOtherTracks() {
        assertFalse(SourceConfig(kind = SourceKind.INTERNET_ARCHIVE).enabled)
        assertFalse(SourceKind.INTERNET_ARCHIVE.matchesOtherCatalogues)
        assertTrue(SourceKind.YOUTUBE.matchesOtherCatalogues)
        val seeded = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = false)
        assertFalse(seeded.single { it.kind == SourceKind.INTERNET_ARCHIVE }.enabled)
    }

    @Test
    fun ceilingsThatAllowTheArchive() {
        assertTrue(AudioQuality.LOSSLESS.permits(SourceKind.INTERNET_ARCHIVE))
        // Served as its own MP3 under the High ceiling.
        assertTrue(AudioQuality.HIGH.permits(SourceKind.INTERNET_ARCHIVE))
        assertFalse(AudioQuality.MEDIUM.permits(SourceKind.INTERNET_ARCHIVE))
        // The rule for the other lossless sources is unchanged.
        assertFalse(AudioQuality.HIGH.permits(SourceKind.ADDON))
    }
}
