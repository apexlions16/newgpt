package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesMissingTest {
    private val now = 1_800_000_000L
    private val st = Stream(master = "x")

    @Test fun notesExpireAndHide() {
        val t = Title(id = "m", type = "movie", title = "F", stream = st, notes = listOf(
            TitleNote("📢 Duyuru"), TitleNote("eski", until = now - 1), TitleNote("⏳ Kalkıyor", until = now + 100, hide = true)))
        assertEquals(listOf("📢 Duyuru", "⏳ Kalkıyor"), Library.activeNotes(t, now).map { it.text })
        assertFalse(Library.isHidden(t, now))
        assertTrue(Library.isHidden(t, now + 101))
        val cat = Catalog(titles = listOf(t))
        assertEquals(0, Library.applySchedule(cat, now + 101)!!.titles.size)
    }

    @Test fun tmdbGuessDoesNotPutSeriesInSoon() {
        // Eksik bölüm ya da TMDB'nin duyurduğu sezon Yakında'ya kendiliğinden sokmaz (yalnızca yöneticinin tarihi/planı)
        val t = Title(id = "s", type = "series", title = "D",
            seasons = listOf(Season(1, episodes = listOf(Episode(1, stream = st), Episode(2, stream = st)))),
            tmdbSeasons = listOf(TmdbSeason(1, 5), TmdbSeason(2, 3)), nextEpisode = NextEpisodeInfo(3, 1, now + 86400))
        assertEquals(6, Library.missingEpisodes(t))
        assertNull(Library.newEpisodesText(t, now))
        assertTrue(Library.comingSoon(Catalog(titles = listOf(t)), null, now).isEmpty())
    }
}
