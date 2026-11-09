package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgesTest {
    private val profile = Profile("p", "Ali", artMode = ArtMode.FIXED)

    @Test fun textlessPosterPreferred() {
        val t = Title(id = "m", title = "Yarış 2", posters = listOf(Art("tr-text", "tr"), Art("clean", null)), logos = listOf(Art("logo-en", "en")))
        val l = Presentation.localize(t, profile)
        assertEquals("clean", l.poster)
        assertFalse(l.posterHasText)
    }

    @Test fun textedPosterWhenNoTextless() {
        val t = Title(id = "m", title = "X", posters = listOf(Art("tr-text", "tr")), backdrops = listOf(Art("bd-text", "en")), backdrop = "default")
        val l = Presentation.localize(t, profile)
        assertEquals("tr-text", l.poster)
        assertTrue(l.posterHasText)
        assertEquals("yazılı arka plan kullanılmaz", "default", l.backdrop)
    }

    @Test fun languageBadges() {
        val st = Stream(versions = listOf(StreamVersion("tr", video = PartedFile(listOf("u"), listOf(1))), StreamVersion("en", video = PartedFile(listOf("u"), listOf(1)))),
            subtitles = listOf(Subtitle("tr", url = "s")))
        assertEquals(listOf("Türkçe Dublaj", "Türkçe Altyazı"), Presentation.languageBadges(Title(id = "m", title = "X", originalLanguage = "en", stream = st)))
        assertEquals(listOf("Türkçe Altyazı"), Presentation.languageBadges(Title(id = "m", title = "X", originalLanguage = "tr", stream = st)))
    }
}
