package com.gflix.app.archive

import android.content.Context
import com.gflix.app.BuildConfig
import java.net.URLEncoder

/**
 * archive.org'daki G-Flix arşivi: bütün item'lar aynı önekle başlar (ör. "gflix-ahmet").
 *
 *  - <önek>-katalog              → catalog.json (uygulamaların okuduğu katalog)
 *  - <önek>-movie-<ad>           → film videosu, altyazıları
 *  - <önek>-series-<ad>-s01      → dizinin 1. sezonu (bölüm videoları, altyazılar)
 *  - <önek>-<yapım>-fragman      → fragmanlar
 *  - <önek>-<yapım>-tema         → tema müziği
 *
 * İzleyici telefonlarda sadece önek gerekir (anahtar yok); dosyaların hepsi archive.org'da herkese açık.
 */
object ArchiveSource {
    private const val PREFS = "gflix.ia"
    const val DOWNLOAD = "https://archive.org/download"

    @Volatile var prefix: String = ""
        private set

    fun init(context: Context) {
        prefix = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("prefix", null)
            ?: BuildConfig.IA_PREFIX
    }

    val configured: Boolean get() = prefix.isNotBlank()

    fun save(context: Context, value: String) {
        prefix = normalize(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("prefix", prefix).apply()
    }

    /** archive.org kimlik kuralları: harf, rakam, - _ . (en fazla 100 karakter) */
    fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.', '_').take(40)

    fun catalogItem(p: String = prefix) = "$p-katalog"
    fun catalogUrl(p: String = prefix) = "$DOWNLOAD/${catalogItem(p)}/catalog.json"

    /** Yapımın item kimliği; sezonlu diziler sezona göre bölünür (item'lar küçük kalsın). */
    fun itemFor(p: String, itemId: String, kind: String, season: Int?): String {
        val base = "$p-${itemId.take(70)}".trimEnd('-')
        return when (kind) {
            "episode" -> "$base-s%02d".format(season ?: 1)
            "trailer" -> "$base-fragman"
            "theme" -> "$base-tema"
            else -> base
        }
    }

    /**
     * Item archive.org'da var mı: true / false, ulaşılamadıysa null. (Olmayan item'ın indirme adresi 404 değil 503 döner;
     * ayırt etmek için metadata sorulur: olmayan item için "{}".)
     */
    fun itemExists(client: okhttp3.OkHttpClient, item: String): Boolean? = runCatching {
        client.newCall(okhttp3.Request.Builder().url("https://archive.org/metadata/$item").build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val o = com.gflix.app.data.AppJson.parseToJsonElement(r.body!!.string()) as? kotlinx.serialization.json.JsonObject ?: return@use null
            when {
                o.containsKey("metadata") || o.containsKey("files") -> true
                o.isEmpty() || o.containsKey("error") -> false
                else -> null
            }
        }
    }.getOrNull()

    fun fileUrl(item: String, name: String) = "$DOWNLOAD/$item/${encodePath(name)}"

    fun encodePath(name: String): String =
        name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
