package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTest {

    private val title = Title(
        id = "movie-baslangic", title = "Başlangıç", originalLanguage = "en",
        names = mapOf("tr" to "Başlangıç", "en" to "Inception", "original" to "Inception"),
        posters = (0 until 4).map { Art("tr$it", "tr") } + (0 until 4).map { Art("en$it", "en") } + (0 until 4).map { Art("none$it", null) },
        backdrops = (0 until 6).map { Art("bd$it", null) },
        logos = listOf(Art("logo-en", "en")),
        stream = Stream("m"),
    )

    private fun profile(lang: String, art: String = ArtMode.FIXED) = Profile("p1", "Ali", titleLang = lang, artMode = art)

    @Test
    fun turkishProfileShowsTurkishNameAndPoster() {
        val t = Presentation.localize(title, profile(TitleLang.TR), day = 1)
        assertEquals("Başlangıç", t.title)
        assertEquals("kapakta yazısız görsel (logo ayrıca konur)", "none0", t.poster)
        assertEquals("Türkçe logo yoksa İngilizce logo gösterilir", "logo-en", t.logo)
    }

    @Test
    fun englishProfileShowsEnglishNameLogoPoster() {
        val t = Presentation.localize(title, profile(TitleLang.EN), day = 1)
        assertEquals("Inception", t.title)
        assertEquals("kapakta yazısız görsel (logo ayrıca konur)", "none0", t.poster)
        assertEquals("logo-en", t.logo)
    }

    @Test
    fun rotatingArtChangesAcrossDaysButStableWithinDay() {
        val p = profile(TitleLang.TR, ArtMode.ROTATE)
        val sameDay = (1..5).map { Presentation.localize(title, p, day = 100).poster }.toSet()
        assertEquals(1, sameDay.size)
        val days = (100L..130L).map { Presentation.localize(title, p, day = it).poster }.toSet()
        assertTrue("Farklı günlerde farklı kapaklar beklenir: $days", days.size > 1)
        assertTrue(days.all { it!!.startsWith("tr") || it.startsWith("none") })
    }

    @Test
    fun mixedModeUsesBothLanguages() {
        val p = profile(TitleLang.MIXED)
        val names = (1L..30L).map { Presentation.localize(title, p, day = it).title }.toSet()
        assertEquals(setOf("Başlangıç", "Inception"), names)
    }

    @Test
    fun pinnedPosterAlwaysWins() {
        val pinned = title.copy(posters = title.posters + Art("pinned", "en", pinned = true))
        val p = profile(TitleLang.TR, ArtMode.ROTATE)
        assertEquals(setOf("pinned"), (1L..10L).map { Presentation.localize(pinned, p, day = it).poster }.toSet())
    }

    @Test
    fun searchFindsBothNames() {
        val shown = Presentation.localize(title, profile(TitleLang.TR), day = 1)
        assertEquals(listOf(shown), Library.search(listOf(shown), "incep"))
        assertEquals(listOf(shown), Library.search(listOf(shown), "baslangic"))
        assertNotEquals(emptyList<Title>(), Library.search(listOf(shown), "BAŞLA"))
    }
}
