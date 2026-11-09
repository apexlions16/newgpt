package com.gflix.app.data

import com.gflix.app.admin.MergeFile
import com.gflix.app.admin.MergeItem
import com.gflix.app.admin.RecipeLinks
import com.gflix.app.admin.buildMergeRecipe
import com.gflix.app.admin.parseTmdbUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeRecipeTest {
    @Test
    fun tmdbUrl() {
        assertEquals("movie" to 2002, parseTmdbUrl("https://www.themoviedb.org/movie/2002-ornek-film?language=tr"))
        assertEquals("tv" to 1001, parseTmdbUrl("https://www.themoviedb.org/tv/1001-ornek-dizi/season/1"))
        assertEquals(null, parseTmdbUrl("batman"))
    }

    @Test
    fun seriesRecipeFromSeparateFiles() {
        val e1 = MergeItem(1, 1).apply {
            files += MergeFile("content://x/ep1", "Dizi.S01E01.mp4", "video", "und")
            files += MergeFile("content://x/ep1_en", "en.ac3", "audio", "en")
            files += MergeFile("content://x/ep1_tr", "tr.ac3", "audio", "tr")
            files += MergeFile("content://x/ep1_sub", "tr.srt", "subtitle", "tr").apply { forced = true }
        }
        val e2 = MergeItem(1, 2).apply { files += MergeFile("content://x/ep2", "Dizi.S01E02.mp4", "video", "und") }
        val recipe = buildMergeRecipe("https://www.themoviedb.org/tv/1001", "", "content://x/trailer", listOf(e1, e2))
        assertEquals(
            """tmdb: https://www.themoviedb.org/tv/1001
fragman: content://x/trailer
bölüm S01E01:
video: content://x/ep1
ses en: content://x/ep1_en
ses tr varsayılan: content://x/ep1_tr
altyazı tr zorunlu: content://x/ep1_sub
bölüm S01E02:
video: content://x/ep2
""", recipe)
        val links = RecipeLinks.find(recipe)
        assertEquals(6, links.size) // fragman dosyası + 5 dosya
        val out = RecipeLinks.rewrite(recipe, links)
        assertEquals(true, out.contains("fragman: asset:f0"))
        assertEquals(true, out.contains("ses tr varsayılan: asset:f3"))
        assertEquals(false, out.contains("content://"))
    }

    @Test
    fun manualAudioDelayGoesIntoRecipe() {
        val it = MergeItem(null, null).apply {
            files += MergeFile("content://x/v", "film.mp4", "video", "und")
            files += MergeFile("content://x/tr", "tr.m4a", "audio", "tr").apply { delayMs = -300 }
            files += MergeFile("content://x/en", "en.m4a", "audio", "en")
        }
        val r = buildMergeRecipe("https://www.themoviedb.org/movie/2002", "", "", listOf(it)).lines()
        assertEquals("ses tr varsayılan: content://x/tr | gecikme: -300", r[2])
        assertEquals("ses en: content://x/en", r[3])
        // telefon aktarınca link asset adıyla değişir, gecikme kalır
        val links = RecipeLinks.find(r.joinToString("\n"))
        assertEquals("ses tr varsayılan: asset:f1 | gecikme: -300", RecipeLinks.rewrite(r.joinToString("\n"), links).lines()[2])
    }

    @Test
    fun dubbedVersionsAreSeparateVideoLines() {
        val it = MergeItem(null, null).apply {
            files += MergeFile("content://x/tr", "Film.TR.mkv", "video", "tr")
            files += MergeFile("content://x/en", "Film.EN.mkv", "video", "en")
            files += MergeFile("content://x/sub", "tr.vtt", "subtitle", "tr")
        }
        val r = buildMergeRecipe("https://www.themoviedb.org/movie/2002", "", "", listOf(it)).lines()
        assertEquals(listOf("video tr: content://x/tr", "video en: content://x/en", "altyazı tr: content://x/sub"), r.subList(1, 4))
    }
}
