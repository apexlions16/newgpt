package com.gflix.app.admin

import com.gflix.app.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class TmdbResult(val id: Int, val kind: String, val title: String, val originalTitle: String, val year: Int?, val poster: String?, val overview: String)

data class TmdbEpisode(val season: Int, val episode: Int, val name: String, val airAt: Long?)
data class TmdbImage(val path: String, val lang: String?, val vote: Double)

class Tmdb(private val key: String, private val client: OkHttpClient) {

    suspend fun get(path: String, params: Map<String, String>): JsonObject? = withContext(Dispatchers.IO) {
        val b = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val k = key.trim()
        val r = Request.Builder()
        if (k.startsWith("eyJ")) r.header("Authorization", "Bearer $k") else b.addQueryParameter("api_key", k)
        client.newCall(r.url(b.build()).build()).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (resp.code == 401) throw IOException("TMDB anahtarı geçersiz.")
            if (!resp.isSuccessful) throw IOException("TMDB hatası (HTTP ${resp.code})")
            AppJson.parseToJsonElement(resp.body!!.string()).jsonObject
        }
    }

    suspend fun search(kind: String, query: String, year: Int?): List<TmdbResult> {
        val params = mutableMapOf("query" to query, "language" to "tr-TR")
        if (year != null) params[if (kind == "tv") "first_air_date_year" else "year"] = year.toString()
        val res = get("/search/$kind", params)?.get("results")?.jsonArray.orEmpty()
        if (res.isEmpty() && year != null) return search(kind, query, null)
        return res.take(20).map { it.jsonObject }.map { o ->
            fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty().takeIf { it != "null" }.orEmpty()
            val date = s("release_date").ifBlank { s("first_air_date") }
            TmdbResult(
                id = o["id"]!!.jsonPrimitive.intOrNull ?: 0, kind = kind,
                title = s("title").ifBlank { s("name") }, originalTitle = s("original_title").ifBlank { s("original_name") },
                year = date.take(4).toIntOrNull(), poster = s("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w185$it" },
                overview = s("overview"),
            )
        }
    }

    /** Dizinin sezonları: (sezon no, bölüm sayısı); "Özel" bölümler (sezon 0) hariç. */
    suspend fun seasons(id: Int): List<Pair<Int, Int>> {
        val o = get("/tv/$id", mapOf("language" to "tr-TR")) ?: return emptyList()
        return o["seasons"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { s ->
            val n = s["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val c = s["episode_count"]?.jsonPrimitive?.intOrNull ?: 0
            if (n >= 1 && c > 0) n to c else null
        }.sortedBy { it.first }
    }

    /** Sezon: (bölüm sayısı, ilk yayın tarihi epoch sn — Türkiye saatiyle 20:00, bilinmiyorsa null) */
    suspend fun season(id: Int, number: Int): Pair<Int, Long?>? {
        val o = get("/tv/$id/season/$number", mapOf("language" to "tr-TR")) ?: return null
        val count = o["episodes"]?.jsonArray?.size ?: 0
        val date = o["air_date"]?.jsonPrimitive?.content?.takeIf { it.length == 10 && it != "null" }
        val at = date?.let {
            runCatching {
                java.time.LocalDate.parse(it).atTime(20, 0).atZone(java.time.ZoneId.of("Europe/Istanbul")).toEpochSecond()
            }.getOrNull()
        }
        return count to at
    }

    /** Dizinin tüm bölümleri (Özel sezon hariç): sezon, bölüm, ad, yayın tarihi (20:00 TR). */
    suspend fun episodes(id: Int): List<TmdbEpisode> = seasons(id).flatMap { (n, _) ->
        val o = get("/tv/$id/season/$n", mapOf("language" to "tr-TR")) ?: return@flatMap emptyList()
        o["episodes"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { e ->
            val num = e["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val date = e["air_date"]?.jsonPrimitive?.content?.takeIf { it.length == 10 && it != "null" }
            TmdbEpisode(n, num, e["name"]?.jsonPrimitive?.content?.takeIf { it != "null" }.orEmpty(), date?.let {
                runCatching { java.time.LocalDate.parse(it).atTime(20, 0).atZone(java.time.ZoneId.of("Europe/Istanbul")).toEpochSecond() }.getOrNull()
            })
        }
    }

    suspend fun images(kind: String, id: Int): Map<String, List<TmdbImage>> {
        val o = get("/$kind/$id/images", emptyMap()) ?: return emptyMap()
        return listOf("posters", "backdrops", "logos").associateWith { k ->
            o[k]?.jsonArray.orEmpty().map { it.jsonObject }.map { i ->
                TmdbImage(
                    i["file_path"]!!.jsonPrimitive.content,
                    i["iso_639_1"]?.jsonPrimitive?.content?.takeIf { it != "null" },
                    i["vote_average"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            }.sortedWith(compareBy<TmdbImage>({ mapOf("tr" to 0, "en" to 1, null to 2)[it.lang] ?: 3 }, { -it.vote }))
        }
    }

    companion object {
        fun img(path: String, size: String = "w342") = "https://image.tmdb.org/t/p/$size$path"
    }
}
