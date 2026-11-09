package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private val now = 1_800_000_000L
    private val s = Stream(master = "x")
    private val catalog = Catalog(titles = listOf(
        Title(id = "movie-later", title = "Sonra", stream = s, releaseAt = now + 3600),
        Title(id = "movie-past", title = "Geçti", stream = s, releaseAt = now - 10),
        Title(id = "movie-trailer", title = "Fragman", trailer = s),
        Title(id = "movie-announced", title = "Duyuru", comingSoon = true),
        Title(id = "movie-normal", title = "Normal", stream = s),
        Title(id = "series-x", type = "series", title = "Dizi", seasons = listOf(
            Season(1, episodes = listOf(Episode(1, stream = s), Episode(2, stream = s, releaseAt = now + 7200))),
            Season(2, episodes = listOf(Episode(1, stream = s, releaseAt = now + 99999))),
        )),
    ))

    @Test fun hidesUnreleased() {
        val c = Library.applySchedule(catalog, now)!!
        val visible = Library.visibleTitles(c, null).map { it.id }
        assertEquals(listOf("movie-past", "movie-normal", "series-x"), visible)
        val series = c.titles.first { it.id == "series-x" }
        assertEquals(1, series.seasons.size)
        assertEquals(listOf(1), series.seasons[0].episodes.map { it.number })
    }

    @Test fun comingSoonOrder() {
        val soon = Library.comingSoon(catalog, null, now).map { it.id }
        assertEquals(listOf("movie-later", "series-x", "movie-announced", "movie-trailer").toSet(), soon.toSet())
        assertEquals("movie-later", soon.first())
        assertEquals("series-x", soon[1])
        assertEquals(now + 7200, Library.nextRelease(catalog.titles.first { it.id == "series-x" }, now))
    }

    @Test fun releasesWhenTimeComes() {
        val later = Library.applySchedule(catalog, now + 4000)!!
        assertTrue(Library.visibleTitles(later, null).any { it.id == "movie-later" })
        assertFalse(Library.comingSoon(catalog, null, now + 4000).any { it.id == "movie-later" })
        assertNull(Library.nextRelease(catalog.titles.first { it.id == "movie-normal" }, now))
    }
}
