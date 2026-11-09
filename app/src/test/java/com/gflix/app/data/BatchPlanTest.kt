package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hepsi yüklenince birlikte aç": eksikken bekler ve ibare gösterir; tamamlanınca planla ilgili ibare kalmaz. */
class BatchPlanTest {
    private val now = 1_800_000_000L
    private val st = Stream(master = "x")
    // katalog bekleyen bölümleri hiç koymaz: sadece 1-2 var, plan 5/15 yüklendi diyor
    private val bekliyor = Title(id = "d", type = "series", title = "D",
        seasons = listOf(Season(1, episodes = listOf(Episode(1, stream = st), Episode(2, stream = st)))),
        releasePlan = listOf(PlanEntry(1, 3, 17, 0, whenComplete = true, have = 5, total = 15, complete = false)))

    @Test fun waitingBatch() {
        assertEquals("🆕 Yeni bölümler: 3–17. bölümler hepsi hazır olunca birlikte açılacak", Library.seriesSoonText(bekliyor, now))
        assertNull(Library.nextRelease(bekliyor, now))
        assertEquals(listOf("d"), Library.comingSoonSeries(Catalog(titles = listOf(bekliyor)), null, now).map { it.id })
        val enErken = bekliyor.copy(releasePlan = listOf(bekliyor.releasePlan[0].copy(at = now + 86_400)))
        assertTrue(Library.seriesSoonText(enErken, now)!!.contains("hepsi hazır olunca birlikte açılacak (en erken "))
        assertNull("bekleyen planın tarihi 'sıradaki yayın' sayılmaz", Library.nextRelease(enErken, now))
    }

    @Test fun completedBatch() {
        val tamam = bekliyor.copy(seasons = listOf(Season(1, episodes = (1..17).map { Episode(it, stream = st) })),
            releasePlan = listOf(PlanEntry(1, 3, 17, 0, whenComplete = true, have = 15, total = 15, complete = true)))
        assertNull(Library.planText(tamam, now))
        assertTrue(Library.comingSoonSeries(Catalog(titles = listOf(tamam)), null, now).isEmpty())
    }

    @Test fun oldPlanWithoutNewFieldsStillParses() {
        val c = AppJson.decodeFromString(Catalog.serializer(), """{"titles":[{"id":"d","type":"series","title":"D","releasePlan":[{"season":2,"from":1,"at":1900000000}]}]}""")
        assertEquals(1_900_000_000L, c.titles[0].releasePlan[0].at)
    }
}
