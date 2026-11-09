package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Yakında" ile "Yakında Gelen Diziler" ayrı listeler: devamı gelen diziler sadece ikincisinde. */
class SoonSplitTest {
    private val now = 1_800_000_000L
    private val st = Stream(master = "x")

    @Test fun continuingSeriesAreSeparate() {
        val titles = listOf(
            Title(id = "film-yakinda", type = "movie", title = "F", stream = st, releaseAt = now + 86400),
            Title(id = "dizi-duyuru", type = "series", title = "D", comingSoon = true),
            Title(id = "dizi-ilk-bolum-ileride", type = "series", title = "D",
                seasons = listOf(Season(1, episodes = listOf(Episode(1, stream = st, releaseAt = now + 3600))))),
            Title(id = "dizi-devam-eksik", type = "series", title = "D",
                seasons = listOf(Season(1, episodes = listOf(Episode(1, stream = st), Episode(2, stream = st)))),
                tmdbSeasons = listOf(TmdbSeason(1, 5))),
            Title(id = "dizi-devam-zamanli", type = "series", title = "D",
                seasons = listOf(Season(1, episodes = listOf(Episode(1, stream = st), Episode(2, stream = st, releaseAt = now + 7200))))),
            Title(id = "dizi-tam", type = "series", title = "D",
                seasons = listOf(Season(1, episodes = listOf(Episode(1, stream = st)))), tmdbSeasons = listOf(TmdbSeason(1, 1))),
        )
        val cat = Catalog(titles = titles)
        assertEquals(setOf("film-yakinda", "dizi-duyuru", "dizi-ilk-bolum-ileride"),
            Library.comingSoonTitles(cat, null, now).map { it.id }.toSet())
        // "dizi-devam-eksik": TMDB'de bölüm eksik diye Yakında'ya girmez (sadece tarihi/planı olan)
        assertEquals(setOf("dizi-devam-zamanli"),
            Library.comingSoonSeries(cat, null, now).map { it.id }.toSet())
        // ikisinin birleşimi eski tek "Yakında" listesiyle aynı; kesişimleri yok
        assertEquals(Library.comingSoon(cat, null, now).map { it.id }.toSet(),
            (Library.comingSoonTitles(cat, null, now) + Library.comingSoonSeries(cat, null, now)).map { it.id }.toSet())
    }
}
