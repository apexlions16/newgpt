package com.gflix.app.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeCodeTest {
    @Test fun formats() {
        assertEquals(1 to 2, episodeCode("Dizi.S01E02.1080p.mkv"))
        assertEquals(2 to 5, episodeCode("Dizi 2x05 TR.mp4"))
        assertEquals(1 to 3, episodeCode("Örnek Dizi 1. Sezon 3. Bölüm.mp4"))
        assertEquals(2 to 10, episodeCode("Dizi Sezon 2 Bölüm 10.mkv"))
        assertEquals(3 to 4, episodeCode("Show Season 3 Episode 4.mkv"))
        assertEquals(1 to 7, episodeCode("Dizi S1 B7.mp4"))
    }

    @Test fun episodeOnlyNeedsSeason() {
        assertNull(episodeCode("Dizi 5. Bölüm.mp4"))
        assertEquals(2 to 5, episodeCode("Dizi 5. Bölüm.mp4", 2))
        assertEquals(1 to 12, episodeCode("Dizi Bölüm 12 EN.mp4", 1))
        assertEquals(1 to 8, episodeCode("Show.E08.mkv", 1))
        assertNull(episodeCode("Yarış 3 izle EN.mp4", 1))
    }
}
