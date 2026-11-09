package com.gflix.app.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JobGroupsTest {
    @Test fun sameSeriesSameCard() {
        val a = JobGroups.parse("Gece Bekçisi 1. Sezon 3. Bölüm izle | Site | Dizi izle")
        val b = JobGroups.parse("Gece Bekçisi 1. Sezon 1. Bölüm izle | Site")
        assertEquals(a.key, b.key)
        assertEquals("Gece Bekçisi", a.name)
        assertEquals(1, a.season); assertEquals(3, a.episode)
        assertEquals(1, b.episode)
    }

    @Test fun differentSeriesDifferentCards() {
        val flash = JobGroups.parse("Hızlı Adam 3. Sezon 4. Bölüm izle | Site")
        val hawk = JobGroups.parse("Gece Bekçisi 1. Sezon 2. Bölüm izle | Site")
        assertNotEquals(flash.key, hawk.key)
        assertEquals(3, flash.season); assertEquals(4, flash.episode)
    }

    @Test fun moviesSeparate() {
        val a = JobGroups.parse("Sessiz Kadın izle | Site | Film izle")
        val b = JobGroups.parse("Gölge izle | Site")
        val c = JobGroups.parse("Kahraman 2 Karanlık Dünya izle | Site")
        assertNotEquals(a.key, b.key); assertNotEquals(b.key, c.key)
        assertEquals(null, a.season)
        assertEquals("Sessiz Kadın", a.name)
    }

    @Test fun otherEpisodeFormats() {
        assertEquals(2 to 5, JobGroups.parse("Dizi Adı S02E05 izle").let { it.season to it.episode })
        assertEquals(1 to 12, JobGroups.parse("Dizi Adı 1x12").let { it.season to it.episode })
        val abs = JobGroups.parse("Örnek Dizi 150. Bölüm izle | Site")
        assertEquals(150, abs.episode)
        assertEquals(JobGroups.parse("Örnek Dizi 151. Bölüm izle").key, abs.key)
    }

    @Test fun resolvedTitleJoinsPageTitle() {
        // Bilgisayar çözünce durumdaki ad "Gece Bekçisi (2021)" ve tür "series": sayfa başlığıyla aynı kart
        val page = JobGroups.keyOf("Gece Bekçisi 1. Sezon 2. Bölüm izle | Site", null, null)
        val resolved = JobGroups.keyOf("🌐 garip başlık", "Gece Bekçisi (2021)", "series")
        assertEquals(page.key, resolved.key)
        // Film çözülünce de yılı atılır
        assertEquals(JobGroups.keyOf("Sessiz Kadın izle | Site", null, null).key, JobGroups.keyOf("x", "Sessiz Kadın (2021)", "movie").key)
    }

    @Test fun groupKeepsOrder() {
        val g = JobGroups.group(listOf("a1", "b1", "a2")) { it.take(1) }
        assertEquals(listOf("a", "b"), g.map { it.first })
        assertEquals(listOf("a1", "a2"), g.first().second)
    }
}
