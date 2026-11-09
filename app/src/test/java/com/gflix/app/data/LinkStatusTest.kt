package com.gflix.app.data

import com.gflix.app.admin.parseLinkStatus
import com.gflix.app.admin.quickCheckRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStatusTest {
    @Test
    fun parsesBodyWrittenByLinkIngest() {
        val body = javaClass.classLoader!!.getResource("link_status_body.txt").readText()
        val st = parseLinkStatus(body)
        assertNotNull(st)
        assertEquals("running", st!!.state)
        assertEquals("Başlangıç (2010)", st.title)
        assertEquals("https://github.com/x/y/actions/runs/7", st.runUrl)
        assertEquals(1, st.items.size)
        assertEquals(2000L, st.items[0].files[0].total)
        assertEquals("ready", st.trailer?.state)
        assertTrue(st.log.last().contains("ğüşıöç"))
    }

    @Test
    fun quickCheck() {
        assertTrue(quickCheckRecipe("tmdb: https://www.themoviedb.org/movie/1\nvideo: https://a/b.txt").isEmpty())
        val errs = quickCheckRecipe("tmdb: \nvideo: ftp://x\nses tr: ")
        assertTrue(errs.any { it.contains("tmdb") })
        assertTrue(errs.any { it.contains("http") })
        // Doldurulmamış şablon satırları sorun değil
        assertTrue(quickCheckRecipe("tmdb: https://www.themoviedb.org/movie/1\nvideo: https://a/b\nses en: \naltyazı en: ").isEmpty())
    }
}
