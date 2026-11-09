package com.gflix.app

import com.gflix.app.admin.NextEpisode
import org.junit.Assert.assertEquals
import org.junit.Test

class NextEpisodeTest {
    private val tmdb = listOf(1 to 8, 2 to 10)

    @Test fun middleOfSeason() = assertEquals(1 to 3, NextEpisode.next(listOf(1 to 1, 1 to 2), tmdb))
    @Test fun seasonEndGoesToNextSeason() = assertEquals(2 to 1, NextEpisode.next((1..8).map { 1 to it }, tmdb))
    @Test fun lastSeasonEndContinues() = assertEquals(2 to 11, NextEpisode.next((1..10).map { 2 to it }, tmdb))
    @Test fun noTmdb() = assertEquals(1 to 9, NextEpisode.next((1..8).map { 1 to it }, null))
    @Test fun empty() = assertEquals(1 to 1, NextEpisode.next(emptyList(), tmdb))
    @Test fun summary() = assertEquals("Yüklü: 1. sezon 1–8 · TMDB: 1. sezon 8 bölüm, 2. sezon 10 bölüm", NextEpisode.summary((1..8).map { 1 to it }, tmdb))
}

class EpisodeCodeTest {
    private fun code(n: String) = com.gflix.app.admin.FileNames.parse(n).let { it.season to it.episode }
    @Test fun multiplicationSign() = assertEquals(1 to 2, code("1×2en.mp4"))
    @Test fun multiplicationSignTr() = assertEquals(1 to 2, code("1×2tur.mp4"))
    @Test fun classic() = assertEquals(1 to 2, code("Dizi.S01E02.1080p.mkv"))
    @Test fun xForm() = assertEquals(2 to 10, code("Dizi 2x10.mkv"))
    @Test fun resolutionIsNotEpisode() = assertEquals(null to null, code("Yarış 2 1920x1080.mp4"))
    @Test fun langFromName() {
        assertEquals("en", com.gflix.app.admin.Langs.guessFromName("1×2en.mp4"))
        assertEquals("tr", com.gflix.app.admin.Langs.guessFromName("Yarış 2 TUR.mp4"))
    }
}
