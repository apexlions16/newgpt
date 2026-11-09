package com.gflix.app.archive

import com.gflix.app.admin.Tmdb
import com.gflix.app.data.Art
import com.gflix.app.data.Title
import com.gflix.app.data.TmdbSeason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** TMDB'deki bölüm bilgisi (ad, özet, kare, süre). */
data class EpisodeMeta(val title: String?, val titleEn: String?, val overview: String?, val still: String?, val runtime: Int?, val airAt: Long?)

/**
 * Telefonda yapım kaydı hazırlar: TMDB'den ad, özet, tür, oyuncular, kapak/arka plan/logo (TMDB adresleri, archive.org'a
 * resim yüklenmez), yaş sınırı ve dizinin sezonları. TMDB eşleşmesi yoksa sadece ad ve yıl yazılır.
 */
class TmdbMeta(private val tmdb: Tmdb) {

    private val seasonCache = HashMap<String, JsonObject?>()

    suspend fun title(itemId: String, series: Boolean, tmdbId: Int): Title? {
        val kind = if (series) "tv" else "movie"
        val extra = if (series) "credits,content_ratings,images,videos" else "credits,release_dates,images,videos"
        val o = tmdb.get("/$kind/$tmdbId", mapOf(
            "language" to "tr-TR", "append_to_response" to extra,
            "include_image_language" to "tr,en,null", "include_video_language" to "tr,en,null",
        )) ?: return null
        val en = runCatching { tmdb.get("/$kind/$tmdbId", mapOf("language" to "en-US")) }.getOrNull()
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        val name = o.s(if (series) "name" else "title") ?: return null
        val original = o.s(if (series) "original_name" else "original_title")
        val enName = en?.s(if (series) "name" else "title")
        val date = o.s(if (series) "first_air_date" else "release_date")
        val credits = o["credits"] as? JsonObject
        val cast = (credits?.get("cast") as? JsonArray).orEmpty().take(10).mapNotNull { (it as? JsonObject)?.s("name") }
        val creators = if (series) (o["created_by"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("name") }
        else (credits?.get("crew") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.s("job") == "Director" }.mapNotNull { it.s("name") }
        val images = o["images"] as? JsonObject
        fun arts(key: String, size: String) = (images?.get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .sortedWith(compareBy({ mapOf("tr" to 0, "en" to 1).getOrElse(it.s("iso_639_1") ?: "") { 2 } }, { -(it["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0) }))
            .take(8).mapNotNull { i -> i.s("file_path")?.let { Art(Tmdb.img(it, size), i.s("iso_639_1")) } }
        val posters = arts("posters", "w500")
        val backdrops = arts("backdrops", "w1280")
        val logos = arts("logos", "w500")
        val cert = certification(o, series)
        val runtime = if (series) (o["episode_run_time"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
        else o["runtime"]?.jsonPrimitive?.intOrNull
        val seasons = if (series) (o["seasons"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { s ->
            val n = s["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val c = s["episode_count"]?.jsonPrimitive?.intOrNull ?: 0
            if (n >= 1 && c > 0) TmdbSeason(n, c) else null
        } else emptyList()
        val now = System.currentTimeMillis() / 1000
        val trailers = pickTrailers(o["videos"] as? JsonObject)
        return Title(
            id = itemId, type = if (series) "series" else "movie", tmdbId = tmdbId,
            title = name, originalTitle = original, overview = o.s("overview").orEmpty(), tagline = o.s("tagline"),
            year = date?.take(4)?.toIntOrNull(), runtime = runtime,
            rating = o["vote_average"]?.jsonPrimitive?.doubleOrNull, popularity = o["popularity"]?.jsonPrimitive?.doubleOrNull,
            maturity = cert?.let(::maturityOf), certification = cert,
            genres = (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("name") },
            cast = cast, creators = creators,
            poster = posters.firstOrNull()?.url ?: o.s("poster_path")?.let { Tmdb.img(it, "w500") },
            backdrop = backdrops.firstOrNull { it.lang == null }?.url ?: backdrops.firstOrNull()?.url ?: o.s("backdrop_path")?.let { Tmdb.img(it, "w1280") },
            logo = logos.firstOrNull()?.url,
            originalLanguage = o.s("original_language"),
            added = now, updated = now,
            names = buildMap { put("tr", name); enName?.let { put("en", it) }; original?.let { put("original", it) } },
            posters = posters, backdrops = backdrops, logos = logos,
            tmdbSeasons = seasons,
            trailers = trailers, trailer = trailers.firstOrNull { it.lang == "tr" } ?: trailers.firstOrNull(),
        )
    }

    /** Yapımın TMDB'deki YouTube fragmanları (Türkçe ve İngilizce, birer tane) — dosya yüklenmez, YouTube'dan oynar. */
    suspend fun trailers(series: Boolean, tmdbId: Int): List<com.gflix.app.data.Stream> {
        val o = tmdb.get("/${if (series) "tv" else "movie"}/$tmdbId/videos", mapOf("include_video_language" to "tr,en,null")) ?: return emptyList()
        return pickTrailers(o)
    }

    suspend fun episode(tmdbId: Int, season: Int, episode: Int): EpisodeMeta? {
        val key = "$tmdbId/$season"
        val s = if (seasonCache.containsKey(key)) seasonCache[key] else
            runCatching { tmdb.get("/tv/$tmdbId/season/$season", mapOf("language" to "tr-TR")) }.getOrNull().also { seasonCache[key] = it }
        val e = (s?.get("episodes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it["episode_number"]?.jsonPrimitive?.intOrNull == episode } ?: return null
        fun str(k: String) = e[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        val air = str("air_date")?.let {
            runCatching { java.time.LocalDate.parse(it).atTime(20, 0).atZone(java.time.ZoneId.of("Europe/Istanbul")).toEpochSecond() }.getOrNull()
        }
        return EpisodeMeta(str("name"), null, str("overview"), str("still_path")?.let { Tmdb.img(it, "w300") }, e["runtime"]?.jsonPrimitive?.intOrNull, air)
    }

    private fun pickTrailers(videos: JsonObject?): List<com.gflix.app.data.Stream> {
        val list = (videos?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it["site"]?.jsonPrimitive?.content == "YouTube" && !it["key"]?.jsonPrimitive?.content.isNullOrBlank() }
        fun score(v: JsonObject): Int {
            val type = v["type"]?.jsonPrimitive?.content
            return (if (type == "Trailer") 0 else if (type == "Teaser") 10 else 20) +
                (if (v["official"]?.jsonPrimitive?.content == "true") 0 else 5)
        }
        return listOf("tr", "en").mapNotNull { lang ->
            list.filter { (it["iso_639_1"]?.jsonPrimitive?.content ?: "en") == lang }
                .filter { it["type"]?.jsonPrimitive?.content in setOf("Trailer", "Teaser") }
                .minByOrNull(::score)
                ?.let { com.gflix.app.data.Stream(youtube = it["key"]!!.jsonPrimitive.content, lang = lang) }
        }
    }

    private fun certification(o: JsonObject, series: Boolean): String? {
        fun pick(list: List<Pair<String, String>>) = listOf("TR", "US", "GB", "DE").firstNotNullOfOrNull { c -> list.firstOrNull { it.first == c && it.second.isNotBlank() }?.second }
        return if (series) {
            val r = ((o["content_ratings"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .map { (it["iso_3166_1"]?.jsonPrimitive?.content.orEmpty()) to (it["rating"]?.jsonPrimitive?.content.orEmpty()) }
            pick(r)
        } else {
            val r = ((o["release_dates"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .flatMap { c ->
                    val country = c["iso_3166_1"]?.jsonPrimitive?.content.orEmpty()
                    (c["release_dates"] as? JsonArray).orEmpty().mapNotNull { d -> d.jsonObject["certification"]?.jsonPrimitive?.content?.let { country to it } }
                }
            pick(r)
        }
    }

    companion object {
        /** Ülke sınıflandırmasından yaş (çocuk profili süzgeci için). */
        fun maturityOf(cert: String): Int? {
            val c = cert.trim().uppercase()
            Regex("(\\d{1,2})").find(c)?.let { if (!c.startsWith("TV-")) return it.value.toInt() }
            return when (c) {
                "G", "TV-Y", "TV-G", "U", "GENEL İZLEYİCİ", "GENEL IZLEYICI" -> 0
                "PG", "TV-Y7", "TV-Y7-FV" -> 7
                "TV-PG" -> 10
                "PG-13" -> 13
                "TV-14" -> 14
                "R" -> 17
                "NC-17", "TV-MA" -> 18
                else -> null
            }
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
