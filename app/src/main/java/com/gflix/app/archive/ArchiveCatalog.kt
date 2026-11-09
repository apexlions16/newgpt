package com.gflix.app.archive

import android.content.Context
import com.gflix.app.appContainer
import com.gflix.app.data.AppJson
import com.gflix.app.data.Catalog
import com.gflix.app.data.CatalogStats
import com.gflix.app.data.Episode
import com.gflix.app.data.PartedFile
import com.gflix.app.data.Season
import com.gflix.app.data.Stream
import com.gflix.app.data.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Yönetici telefonundaki asıl katalog. Her değişiklik önce burada yapılır, sonra archive.org'daki
 * <önek>-katalog/catalog.json'a tek dosya olarak yazılır. archive.org dosyayı birkaç dakika gecikmeyle
 * gösterebildiği için yönetici telefonu kendi kopyasını kullanır; izleyiciler archive.org'dan okur.
 */
class ArchiveCatalog(private val context: Context) {

    private val file = File(context.filesDir, FILE)

    /** Telefondaki kopya yoksa archive.org'daki katalogdan başlar (başka telefondan devralma). */
    private suspend fun load(): Catalog = withContext(Dispatchers.IO) {
        if (file.exists()) runCatching { return@withContext AppJson.decodeFromString<Catalog>(file.readText()) }
        val remote = runCatching {
            http.newCall(Request.Builder().url(ArchiveSource.catalogUrl() + "?t=" + System.currentTimeMillis()).build()).execute().use { r ->
                if (r.isSuccessful) AppJson.decodeFromString<Catalog>(r.body!!.string()) else null
            }
        }.getOrNull()
        if (remote != null) return@withContext remote
        // Boş katalogla başlayıp archive.org'dakinin üzerine yazmamak için: sadece katalog item'ı gerçekten yoksa boş başlanır
        when (ArchiveSource.itemExists(http, ArchiveSource.catalogItem())) {
            false -> Catalog()
            true -> throw java.io.IOException("archive.org'daki katalog okunamadı (dosya henüz işleniyor olabilir). Birazdan tekrar dene.")
            null -> throw java.io.IOException("archive.org'a ulaşılamadı; katalog okunamadı. Birazdan tekrar dene.")
        }
    }

    suspend fun current(): Catalog = lock.withLock { load() }

    /** Kataloğu değiştirir; telefondaki kopyayı ve uygulamanın gösterdiği kataloğu hemen günceller. */
    suspend fun edit(transform: (Catalog) -> Catalog): Catalog = lock.withLock {
        val next = transform(load()).let { c ->
            c.copy(
                version = 1,
                generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                titles = c.titles.map { t -> if (t.releasePlan.isEmpty()) t else t.copy(releasePlan = t.releasePlan.map { planProgress(t, it) }) }
                    .sortedByDescending { it.lastChange },
                stats = stats(c.titles),
            )
        }
        val text = AppJson.encodeToString(Catalog.serializer(), next)
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
        context.appContainer.catalogRepository.setLocal(next, text)
        next
    }

    /** catalog.json'u archive.org'a yazar (tek PUT; eski sürüm tutulmaz). */
    suspend fun publish(client: ArchiveClient) {
        val text = withContext(Dispatchers.IO) { if (file.exists()) file.readText() else null } ?: return
        client.put(
            ArchiveSource.catalogItem(), "catalog.json", text.toByteArray().toRequestBody("application/json".toMediaType()),
            meta = listOf(
                "mediatype" to "data", "collection" to "opensource",
                "title" to "G-Flix katalog (${ArchiveSource.prefix})", "subject" to "gflix",
            ),
            replace = true, interactive = true,
        )
    }

    /** Upload exactly these bytes and keep a receipt so cleanup waits for remote processing. */
    suspend fun publishVerifiedSnapshot(client: ArchiveClient): Pair<Long, String> = lock.withLock {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        client.put(ArchiveSource.catalogItem(), "catalog.json", bytes.toRequestBody("application/json".toMediaType()),
            meta = listOf("mediatype" to "data", "collection" to "opensource",
                "title" to "G-Flix katalog (${ArchiveSource.prefix})", "subject" to "gflix"),
            replace = true, interactive = true)
        bytes.size.toLong() to md5
    }

    // ------------------------------------------------------------ değişiklikler

    fun Catalog.upsert(id: String, create: () -> Title, change: (Title) -> Title): Catalog {
        val old = titles.firstOrNull { it.id == id }
        val updated = change(old ?: create())
        return copy(titles = if (old == null) titles + updated else titles.map { if (it.id == id) updated else it })
    }

    fun Catalog.mapTitle(id: String, change: (Title) -> Title?): Catalog =
        copy(titles = titles.mapNotNull { if (it.id == id) change(it) else it })

    companion object {
        const val FILE = "ia_catalog.json"
        private val lock = Mutex()
        private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

        fun file(context: Context) = File(context.filesDir, FILE)

        fun sizeOf(f: PartedFile?): Long = f?.sizes?.sum() ?: 0L
        fun sizeOf(s: Stream?): Long = if (s == null) 0L else
            sizeOf(s.direct?.video) + s.direct?.audio.orEmpty().sumOf { it.sizes.sum() } + s.versions.sumOf { sizeOf(it.video) } + sizeOf(s.uhd)

        fun stats(titles: List<Title>): CatalogStats {
            val content = titles.sumOf { t -> sizeOf(t.stream) + t.seasons.sumOf { s -> s.episodes.sumOf { sizeOf(it.stream) } } }
            val trailers = titles.sumOf { t -> (listOfNotNull(t.trailer) + t.trailers).distinctBy { TrailerKey.of(it) }.sumOf { sizeOf(it) } + sizeOf(t.theme) }
            val minutes = titles.sumOf { t ->
                val d = (t.stream?.duration ?: 0.0) + t.seasons.sumOf { s -> s.episodes.sumOf { it.stream?.duration ?: 0.0 } }
                (d / 60).toLong()
            }
            return CatalogStats(
                movies = titles.count { !it.isSeries && it.stream != null },
                series = titles.count { it.isSeries && it.seasons.any { s -> s.episodes.any { e -> e.stream != null } } },
                seasons = titles.sumOf { t -> t.seasons.count { s -> s.episodes.any { it.stream != null } } },
                episodes = titles.sumOf { t -> t.seasons.sumOf { s -> s.episodes.count { it.stream != null } } },
                trailers = titles.count { it.trailer != null || it.trailers.isNotEmpty() },
                comingSoon = titles.count { it.comingSoon },
                contentBytes = content, trailerBytes = trailers, totalBytes = content + trailers, minutes = minutes,
            )
        }

        /** "Hepsi yüklenince aç" satırının ilerlemesi: kaç bölüm yüklü / kaç gerekli, tamam mı */
        fun planProgress(t: Title, p: com.gflix.app.data.PlanEntry): com.gflix.app.data.PlanEntry {
            if (!p.whenComplete) return p
            val eps = t.seasons.firstOrNull { it.number == p.season }?.episodes.orEmpty().filter { it.stream != null }.map { it.number }.toSet()
            val to = p.to ?: t.tmdbSeasons.firstOrNull { it.n == p.season }?.c ?: eps.maxOrNull() ?: p.from
            val total = (to - p.from + 1).coerceAtLeast(0)
            val have = (p.from..to).count { it in eps }
            return p.copy(have = have, total = total, complete = total > 0 && have >= total)
        }

        /**
         * Yeni yüklenen videoyu yapımın (ya da bölümün) mevcut videosuyla birleştirir. [lang] null ise yeni video
         * eskisinin yerine geçer. Değilse dil sürümü olur: aynı dildeki eski sürüm değişir, diğer diller kalır; eskiden
         * tek video varsa (sürümsüz) o da kendi ses diliyle sürüme dönüşür. Altyazılar ortaktır (aynı dil + zorunluluk
         * yenisiyle değişir).
         */
        fun mergeStream(old: Stream?, add: Stream, lang: String?): Stream {
            if (lang == null) return add
            val video = add.direct?.video ?: add.versions.firstOrNull()?.video ?: return add
            val oldVersions = when {
                old == null -> emptyList()
                old.versions.isNotEmpty() -> old.versions
                old.direct != null -> listOf(com.gflix.app.data.StreamVersion(old.direct.videoAudio.firstOrNull() ?: "und", null, old.direct.video))
                else -> emptyList()
            }
            val order = { l: String -> when (l) { "tr" -> 0; "en" -> 1; else -> 2 } }
            val versions = (oldVersions.filterNot { it.lang == lang } + com.gflix.app.data.StreamVersion(lang, null, video)).sortedBy { order(it.lang) }
            val subs = old?.subtitles.orEmpty().filterNot { o -> add.subtitles.any { it.lang == o.lang && it.forced == o.forced } } + add.subtitles
            return Stream(versions = versions, subtitles = subs, duration = add.duration ?: old?.duration)
        }

        /** Mevcut videonun dil(ler)i: sürümlerin dilleri ya da tek videonun ilk ses dili (video yoksa boş) */
        fun streamLangs(s: Stream?): List<String> = when {
            s == null -> emptyList()
            s.versions.isNotEmpty() -> s.versions.map { it.lang }
            s.direct != null -> listOf(s.direct.videoAudio.firstOrNull() ?: "und")
            else -> emptyList()
        }

        /** Bölümü sezonuna yerleştirir (varsa yerine koyar; zamanlama ve elle yazılmış bilgiler korunur). */
        fun putEpisode(t: Title, season: Int, ep: Episode): Title {
            val s = t.seasons.firstOrNull { it.number == season } ?: Season(season)
            val old = s.episodes.firstOrNull { it.number == ep.number }
            val merged = if (old == null) ep else ep.copy(
                title = ep.title ?: old.title, overview = ep.overview ?: old.overview, still = ep.still ?: old.still,
                runtime = ep.runtime ?: old.runtime, releaseAt = old.releaseAt ?: ep.releaseAt, added = old.added ?: ep.added,
            )
            val eps = (s.episodes.filterNot { it.number == ep.number } + merged).sortedBy { it.number }
            val seasons = (t.seasons.filterNot { it.number == season } + s.copy(episodes = eps)).sortedBy { it.number }
            return t.copy(seasons = seasons)
        }
    }
}

object TrailerKey {
    fun of(s: Stream): String = (s.direct?.video?.parts?.firstOrNull() ?: s.master) + "|" + s.lang
}
