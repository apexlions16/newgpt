package com.gflix.app.data

import com.gflix.app.admin.RecipeLinks
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeLinksTest {
    private val recipe = """tmdb: https://www.themoviedb.org/movie/2002-ornek-film
fragman: https://www.youtube.com/watch?v=abc
video: https://cdn.site.com/v.txt
ses tr varsayılan: https://cdn.site.com/tr.txt | ad: Türkçe Dublaj
ses en: https://cdn.site.com/en.txt
# altyazı tr: https://yorum.com/x
altyazı tr: https://subs.site.com/tr.txt | referer: https://site.com/ | cookie: a=1
altyazı en: https://subs.site.com/en.txt"""

    @Test
    fun findsOnlyFileLinksWithHeaders() {
        val links = RecipeLinks.find(recipe)
        assertEquals(5, links.size)
        assertEquals("https://cdn.site.com/v.txt", links[0].url)
        assertEquals(mapOf("Referer" to "https://site.com/", "Cookie" to "a=1"), links[3].headers)
    }

    @Test
    fun rewriteKeepsOptionsAndTrailer() {
        val out = RecipeLinks.rewrite(recipe, RecipeLinks.find(recipe)).lines()
        assertEquals("fragman: https://www.youtube.com/watch?v=abc", out[1])
        assertEquals("video: asset:f0", out[2])
        assertEquals("ses tr varsayılan: asset:f1 | ad: Türkçe Dublaj", out[3])
        assertEquals("altyazı tr: asset:f3 | referer: https://site.com/ | cookie: a=1", out[6])
        assertEquals("altyazı en: asset:f4", out[7])
    }

    @Test
    fun fastSeriesKnowsItemOfEachLinkAndRewritesToMediaReleases() {
        val r = """mod: hızlı
tmdb: https://www.themoviedb.org/tv/1001
fragman: content://x/trailer
bölüm S01E01:
video: content://x/v1
ses tr varsayılan: content://x/a1
bölüm S01E02:
video: content://x/v2
altyazı tr: content://x/s2"""
        assert(RecipeLinks.isFast(r))
        assert(!RecipeLinks.isFast("tmdb: x\nvideo: https://a"))
        val links = RecipeLinks.find(r)
        assertEquals(listOf("trailer", "video", "ses", "video", "altyazı"), links.map { it.role })
        assertEquals(listOf(0, 0, 0, 1, 1), links.map { it.item })
        assertEquals("media-abc-1", RecipeLinks.mediaTag("linkjob-abc", 1))
        val refs = links.mapIndexed { i, l -> if (l.role == "trailer") "asset:f$i" else "asset:${RecipeLinks.mediaTag("linkjob-abc", l.item)}/f$i" }
        val out = RecipeLinks.rewrite(r, links, refs).lines()
        assertEquals("fragman: asset:f0", out[2])
        assertEquals("video: asset:media-abc-0/f1", out[4])
        assertEquals("altyazı tr: asset:media-abc-1/f4", out[8])
    }
}
