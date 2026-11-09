package com.gflix.app.data

import com.gflix.app.archive.ArchiveCatalog
import com.gflix.app.archive.ArchiveClient
import com.gflix.app.archive.ArchiveSource
import com.gflix.app.archive.ArchiveUploadWorker
import com.gflix.app.archive.TmdbMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTest {

    @Test fun prefixNormalized() {
        assertEquals("gflix-ahmet", ArchiveSource.normalize("  GFlix Ahmet "))
        assertEquals("gflix-ahmet", ArchiveSource.normalize("gflix/ahmet!"))
    }

    @Test fun itemIdentifiers() {
        assertEquals("gflix-a-movie-yaris", ArchiveSource.itemFor("gflix-a", "movie-yaris", "movie", null))
        assertEquals("gflix-a-series-dizi-s02", ArchiveSource.itemFor("gflix-a", "series-dizi", "episode", 2))
        assertEquals("gflix-a-movie-yaris-fragman", ArchiveSource.itemFor("gflix-a", "movie-yaris", "trailer", null))
        assertEquals("gflix-a-series-dizi-tema", ArchiveSource.itemFor("gflix-a", "series-dizi", "theme", null))
        assertEquals("gflix-a-katalog", ArchiveSource.catalogItem("gflix-a"))
        assertTrue(ArchiveSource.itemFor("gflix-a", "movie-" + "x".repeat(200), "episode", 1).length <= 100)
    }

    @Test fun remoteNames() {
        assertEquals("s01e05", ArchiveUploadWorker.remoteBase("episode", "series-x", 1, 5, "tr"))
        assertEquals("fragman-en", ArchiveUploadWorker.remoteBase("trailer", "movie-x", null, null, "en"))
        assertEquals("tema", ArchiveUploadWorker.remoteBase("theme", "movie-x", null, null, null))
        assertEquals("yaris-2", ArchiveUploadWorker.remoteBase("movie", "movie-yaris-2", null, null, null))
    }

    @Test fun urlsEncoded() {
        assertEquals("https://archive.org/download/it/s01e01.tr.srt", ArchiveSource.fileUrl("it", "s01e01.tr.srt"))
        assertEquals("https://archive.org/download/it/a%20b%C3%A7.mkv", ArchiveSource.fileUrl("it", "a bç.mkv"))
    }

    @Test fun metaHeadersAsciiSafe() {
        assertEquals("Yaris (2020)", ArchiveClient.encodeMeta("Yaris (2020)"))
        assertEquals("uri(Yar%C4%B1%C5%9F%20-%201.%20Sezon)", ArchiveClient.encodeMeta("Yarış - 1. Sezon"))
        assertEquals("a b", ArchiveClient.encodeMeta("a\n b"))
    }

    @Test fun maturity() {
        assertEquals(13, TmdbMeta.maturityOf("PG-13"))
        assertEquals(18, TmdbMeta.maturityOf("18+"))
        assertEquals(7, TmdbMeta.maturityOf("7+"))
        assertEquals(18, TmdbMeta.maturityOf("TV-MA"))
        assertEquals(0, TmdbMeta.maturityOf("G"))
        assertNull(TmdbMeta.maturityOf("NR"))
    }

    private fun stream(url: String, size: Long) = Stream(direct = DirectMedia(video = PartedFile(listOf(url), listOf(size))))

    @Test fun episodeMergeKeepsScheduleAndOrder() {
        var t = Title(id = "series-x", type = "series", title = "X")
        t = ArchiveCatalog.putEpisode(t, 1, Episode(2, releaseAt = 500))                    // önce zamanlandı, video yok
        t = ArchiveCatalog.putEpisode(t, 1, Episode(1, title = "Bir", stream = stream("u1", 10)))
        t = ArchiveCatalog.putEpisode(t, 1, Episode(2, title = "İki", stream = stream("u2", 20)))
        val eps = t.seasons.single().episodes
        assertEquals(listOf(1, 2), eps.map { it.number })
        assertEquals(500L, eps[1].releaseAt)
        assertEquals("İki", eps[1].title)
        assertEquals("u2", eps[1].stream?.direct?.video?.parts?.single())
    }

    @Test fun statsCountContentAndTrailers() {
        val movie = Title(id = "m", title = "M", stream = stream("v", 1000), trailers = listOf(stream("t", 50).copy(lang = "tr")),
            theme = PartedFile(listOf("th"), listOf(5)))
        var series = Title(id = "s", type = "series", title = "S")
        series = ArchiveCatalog.putEpisode(series, 1, Episode(1, stream = stream("e1", 300)))
        series = ArchiveCatalog.putEpisode(series, 2, Episode(1, stream = stream("e2", 300)))
        val st = ArchiveCatalog.stats(listOf(movie, series))
        assertEquals(1, st.movies); assertEquals(1, st.series); assertEquals(2, st.seasons); assertEquals(2, st.episodes)
        assertEquals(1, st.trailers)
        assertEquals(1600L, st.contentBytes); assertEquals(55L, st.trailerBytes)
    }

    @Test fun openWhenCompleteProgress() {
        var t = Title(id = "s", type = "series", title = "S", tmdbSeasons = listOf(TmdbSeason(1, 3)))
        val plan = PlanEntry(season = 1, from = 1, whenComplete = true)
        t = ArchiveCatalog.putEpisode(t, 1, Episode(1, stream = stream("a", 1)))
        t = ArchiveCatalog.putEpisode(t, 1, Episode(2, stream = stream("b", 1)))
        val half = ArchiveCatalog.planProgress(t, plan)
        assertEquals(2, half.have); assertEquals(3, half.total); assertFalse(half.complete)
        t = ArchiveCatalog.putEpisode(t, 1, Episode(3, stream = stream("c", 1)))
        assertTrue(ArchiveCatalog.planProgress(t, plan).complete)
    }

    @Test fun catalogRoundTrip() {
        val c = Catalog(generatedAt = "2026-11-07T12:00:00Z", titles = listOf(Title(id = "m", title = "M", stream = stream("v", 1))))
        val back = AppJson.decodeFromString<Catalog>(AppJson.encodeToString(Catalog.serializer(), c))
        assertEquals("v", back.titles.single().stream?.direct?.video?.parts?.single())
    }

    @Test fun dubVersionsMergeAndShareSubtitles() {
        val tr = stream("tr.mkv", 100).copy(subtitles = listOf(Subtitle("tr", null, "tr.srt")))
        val en = stream("en.mkv", 120).copy(subtitles = listOf(Subtitle("en", null, "en.srt")))
        val first = ArchiveCatalog.mergeStream(null, tr, "tr")
        assertEquals(listOf("tr"), first.versions.map { it.lang })
        val both = ArchiveCatalog.mergeStream(first, en, "en")
        assertEquals(listOf("tr", "en"), both.versions.map { it.lang })
        assertEquals(setOf("tr", "en"), both.subtitles.map { it.lang }.toSet())
        assertNull(both.direct)
        // aynı dil yeniden yüklenince o sürüm değişir, diğeri kalır
        val again = ArchiveCatalog.mergeStream(both, stream("tr2.mkv", 90), "tr")
        assertEquals("tr2.mkv", again.versions.first { it.lang == "tr" }.video.parts.single())
        assertEquals("en.mkv", again.versions.first { it.lang == "en" }.video.parts.single())
        assertEquals(2, again.subtitles.size)
    }

    @Test fun singleVideoBecomesVersionWhenOtherLanguageArrives() {
        val old = Stream(direct = DirectMedia(video = PartedFile(listOf("old.mkv"), listOf(5)), videoAudio = listOf("tr", "en")))
        assertEquals(listOf("tr"), ArchiveCatalog.streamLangs(old))
        val merged = ArchiveCatalog.mergeStream(old, stream("en.mkv", 6), "en")
        assertEquals(listOf("tr", "en"), merged.versions.map { it.lang })
        assertEquals("old.mkv", merged.versions[0].video.parts.single())
        // sürümsüz yükleme eskisinin yerine geçer
        assertEquals("x", ArchiveCatalog.mergeStream(old, stream("x", 1), null).direct?.video?.parts?.single())
    }
}
