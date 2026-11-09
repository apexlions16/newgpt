package com.gflix.app.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCheckTest {
    @Test fun languageVideoLinesAccepted() {
        val r = "tmdb: https://www.themoviedb.org/tv/1001\nbölüm S01E06:\nvideo tr: https://a/x.m3u8\nvideo en: https://a/x.m3u8\naltyazı tr: https://a/tr.vtt\naltyazı en: \n"
        assertEquals(emptyList<String>(), quickCheckRecipe(r))
        assertEquals(emptyList<String>(), quickCheckRecipe("tmdb: 1\nvideo tr: https://a/v.mp4 | ses: https://a/a.m3u8\n"))
    }

    @Test fun emptyTemplateRejected() {
        assertTrue(quickCheckRecipe("tmdb: https://www.themoviedb.org/movie/1\nvideo tr: \nvideo en: \n").any { "video" in it })
    }
}
