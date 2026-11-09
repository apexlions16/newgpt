package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 15 bölüm aynı saatte: "Yeni bölümler: 3–17. bölümler <tarih>"; açılınca plan yoksa Yakında'dan çıkar. */
class SeriesPlanTest {
    private val now = 1_800_000_000L
    private val at = now + 3 * 86_400
    private val st = Stream(master = "x")
    private fun ep(n: Int, r: Long? = null) = Episode(n, stream = st, releaseAt = r)
    private val dizi = Title(id = "d", type = "series", title = "D", tmdbSeasons = listOf(TmdbSeason(1, 20)),
        seasons = listOf(Season(1, episodes = listOf(ep(1), ep(2)) + (3..17).map { ep(it, at) })))

    @Test fun sameTimeRangeIsOneLine() {
        val text = Library.seriesSoonText(dizi, now)!!
        assertTrue(text, text.startsWith("🆕 Yeni bölümler: 3–17. bölümler "))
        assertEquals(listOf(PlanEntry(1, 3, 17, at)), Library.episodePlan(dizi, now))
        assertEquals(listOf("d"), Library.comingSoonSeries(Catalog(titles = listOf(dizi)), null, now).map { it.id })
    }

    @Test fun afterReleaseNoDeadline() {
        // TMDB'de bölüm eksik görünse de tahminle "yakında" yazılmaz (yanlış algılıyordu)
        assertNull(Library.seriesSoonText(dizi, at + 60))
        assertTrue(Library.comingSoonSeries(Catalog(titles = listOf(dizi)), null, at + 60).isEmpty())
        val tam = dizi.copy(tmdbSeasons = listOf(TmdbSeason(1, 17)))
        assertNull(Library.seriesSoonText(tam, at + 60))
        assertTrue(Library.comingSoonSeries(Catalog(titles = listOf(tam)), null, at + 60).isEmpty())
    }

    @Test fun twoBatchesAndAdminPlanWins() {
        val iki = dizi.copy(seasons = listOf(Season(1, episodes = listOf(ep(1), ep(2)) + (3..7).map { ep(it, at) } + (8..12).map { ep(it, at + 7 * 86_400) })))
        assertEquals(listOf(PlanEntry(1, 3, 7, at), PlanEntry(1, 8, 12, at + 7 * 86_400)), Library.episodePlan(iki, now))
        val plan = iki.copy(releasePlan = listOf(PlanEntry(2, 1, null, at)))
        assertTrue(Library.planText(plan, now)!!.startsWith("2. sezon "))
    }
}
