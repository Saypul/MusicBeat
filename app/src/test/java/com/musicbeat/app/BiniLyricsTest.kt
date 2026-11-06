package com.musicbeat.app

import com.musicbeat.app.data.lyrics.BiniLyrics
import com.musicbeat.app.data.lyrics.lyricsJson
import com.musicbeat.app.data.lyrics.withoutArtistPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BiniLyrics' recording pick, against what its host actually returned on
 * 2026-11-06 — results trimmed to the fields the match reads.
 */
class BiniLyricsTest {

    /** `GET /?track=Blinding Lights&artist=The Weeknd&duration=200` */
    private val blindingLights = """
        {"total":20,"source":"HIT-LRC-RED","results":[
        {"track_name":"Blinding Lights (Mixed)","artist_name":"The Weeknd","duration":200,"isrc":"US23A1521198"},
        {"track_name":"Blinding Lights","artist_name":"The Weeknd","duration":200,"isrc":"USUG11904206"},
        {"track_name":"Blinding Lights (feat. Major Lazer) [Major Lazer Remix]","artist_name":"The Weeknd",
         "duration":198,"isrc":"USUG12001402"}
        ]}
    """.trimIndent()

    /**
     * `GET /?track=Separuh Aku&artist=NOAH&duration=266` — names, artists and
     * lengths as returned, the cover moved first; the ISRCs are stand-ins.
     */
    private val separuhAku = """
        {"total":8,"source":"HIT-LRC-RED","results":[
        {"track_name":"Separuh Aku","artist_name":"Tami Aulia","duration":264,"isrc":"IDA012300001"},
        {"track_name":"Separuh Aku","artist_name":"Noah","duration":266,"isrc":"IDA011200123"}
        ]}
    """.trimIndent()

    /** `GET /?track=Hati-Hati di Jalan&artist=Tulus&duration=242` */
    private val hatiHati = """
        {"total":2,"source":"HIT-LRC-RED","results":[
        {"track_name":"Hati-Hati di Jalan (Acoustic Version)","artist_name":"Thea Lestari","duration":253,
         "isrc":"QZFZ42612333"}
        ]}
    """.trimIndent()

    private fun results(json: String) = lyricsJson.decodeFromString<BiniLyrics.Response>(json).results!!

    @Test
    fun `skips a mix the search ranks first`() {
        val hit = BiniLyrics.best(results(blindingLights), "Blinding Lights", "The Weeknd", 200_000)
        assertEquals("USUG11904206", hit?.isrc)
    }

    @Test
    fun `a cover by somebody else is not the recording`() {
        val hit = BiniLyrics.best(results(separuhAku), "Separuh Aku", "NOAH", 266_000)
        assertEquals("IDA011200123", hit?.isrc)
    }

    @Test
    fun `only another artist's acoustic take is no match at all`() {
        assertNull(BiniLyrics.best(results(hatiHati), "Hati-Hati di Jalan", "Tulus", 242_000))
    }

    @Test
    fun `a remix asked for is found`() {
        val hit = BiniLyrics.best(
            results(blindingLights),
            "Blinding Lights (feat. Major Lazer) [Major Lazer Remix]",
            "The Weeknd",
            198_000,
        )
        assertEquals("USUG12001402", hit?.isrc)
    }

    @Test
    fun `a video upload's artist prefix comes off the title`() {
        assertEquals("Separuh Aku", "NOAH - Separuh Aku".withoutArtistPrefix("NOAH"))
        assertEquals("Sial", "Mahalini – Sial".withoutArtistPrefix("mahalini"))
    }

    @Test
    fun `a dash that is part of the title stays`() {
        assertEquals("Hati-Hati di Jalan", "Hati-Hati di Jalan".withoutArtistPrefix("Tulus"))
        assertEquals("Intro - Reprise", "Intro - Reprise".withoutArtistPrefix("Tulus"))
    }
}
