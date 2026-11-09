package com.gflix.app.data

import com.gflix.app.admin.FileNames
import com.gflix.app.admin.MediaProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class MediaProbeTest {

    private fun probe(name: String) = File(javaClass.classLoader!!.getResource(name).toURI()).let { f ->
        RandomAccessFile(f, "r").channel.use { MediaProbe.probeMatroska(it, f.name, f.length()) }.also { MediaProbe.autoSelect(it) }
    }

    @Test
    fun mkvToolnixWebVttIndicesMatchFfprobe() {
        // ffprobe: 0 video, 1 ses tur, 2 ses eng, 3 altyazı S_TEXT/WEBVTT tur, 4 altyazı subrip eng
        val r = probe("probe_webvtt.mkv")
        assertEquals(listOf(1 to "tr", 2 to "en"), r.audio.map { it.index to it.lang })
        assertEquals(listOf(3 to "tr", 4 to "en"), r.subs.map { it.index to it.lang })
        assertEquals("WebVTT", r.subs[0].codec)
        assertTrue(r.subs.all { it.include && !it.bitmap })
    }

    @Test
    fun attachmentSubtitleGetsIndexAfterTracks() {
        // ffprobe: 0 video, 1-2 ses, 3 altyazı webvtt eng, 4 ek dosya "Türkçe Altyazı.tr.vtt"
        val r = probe("probe_attach.mkv")
        assertEquals(listOf(3, 4), r.subs.map { it.index })
        assertEquals("Türkçe Altyazı.tr.vtt", r.subs[1].attachment)
        assertEquals("tr", r.subs[1].lang)
        assertFalse(r.subs[1].bitmap)
    }

    @Test
    fun fileNamesParseLikeDesktop() {
        assertEquals(FileNames.Parsed("episode", "Dark Road", null, 1, 2), FileNames.parse("Dark.Road.S01E02.1080p.WEB-DL.mkv"))
        assertEquals(FileNames.Parsed("movie", "Desert Part Two", 2024, null, null), FileNames.parse("Desert Part Two (2024) [1080p].mkv"))
        assertEquals(FileNames.Parsed("episode", "Kurt Tepesi", null, 1, 5), FileNames.parse("Kurt Tepesi - 1x05.mp4"))
        assertEquals("trailer", FileNames.parse("Desert.2021.Trailer.mp4").kind)
        assertEquals("movie-golge-adam-yepyeni-bir-gun", FileNames.itemId("movie", "Gölge-Adam: Yepyeni Bir Gün"))
        assertEquals("series-ask-i-yasak", FileNames.itemId("episode", "Aşk-ı Yasak"))
    }
}
