package com.gflix.app.admin

import com.gflix.app.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Ev ağındaki Linux bilgisayar (tools/gflix/lan.py). */
class LanTarget(val address: String, val key: String) {
    val base: String get() = "http://$address"
}

/**
 * Telefon aynı Wi-Fi'deyse dosyaları GitHub'a uğratmadan doğrudan bilgisayara gönderir. Bilgisayar adresini
 * "pcworker" kalp atışında yayınlar; anahtar sha256("gflix-lan:" + GitHub token) — token ağda hiç dolaşmaz.
 */
object LanClient {
    private val quick = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build()

    fun key(githubToken: String): String =
        MessageDigest.getInstance("SHA-256").digest("gflix-lan:$githubToken".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Bilgisayar ev ağında ulaşılabilir mi? Ulaşılamazsa null (dosyalar GitHub üzerinden gider). */
    suspend fun find(gh: GitHubAdmin, githubToken: String): LanTarget? = withContext(Dispatchers.IO) {
        val st = PcJobs.status(gh) ?: return@withContext null
        val k = key(githubToken)
        st.lan.firstNotNullOfOrNull { addr ->
            runCatching {
                quick.newCall(Request.Builder().url("http://$addr/ping").header("X-GFlix-Key", k).build()).execute().use { r ->
                    if (r.isSuccessful) LanTarget(addr, k) else null
                }
            }.getOrNull()
        }
    }

    private fun url(t: LanTarget, job: String, name: String, suffix: String = "") =
        "${t.base}/files/${enc(job)}/${enc(name)}$suffix"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Bilgisayarda zaten olan bayt aralıkları (kaldığı yerden devam için). */
    suspend fun ranges(t: LanTarget, job: String, name: String): List<LongRange> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url(t, job, name)).header("X-GFlix-Key", t.key).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Bilgisayar yanıt vermedi (HTTP ${r.code})")
            val o = AppJson.parseToJsonElement(r.body!!.string()).jsonObject
            o["ranges"]?.jsonArray?.map { a ->
                val p = a.jsonArray
                p[0].jsonPrimitive.longOrNull!! until p[1].jsonPrimitive.longOrNull!!
            }.orEmpty()
        }
    }

    suspend fun put(t: LanTarget, job: String, name: String, offset: Long, total: Long, body: RequestBody) = withContext(Dispatchers.IO) {
        val u = url(t, job, name, "?offset=$offset" + if (total > 0) "&total=$total" else "")
        http.newCall(Request.Builder().url(u).header("X-GFlix-Key", t.key).put(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Bilgisayara gönderilemedi (HTTP ${r.code}): ${r.body?.string()?.take(200)}")
        }
    }

    /** Bütün baytlar geldiyse bilgisayardaki dosyanın file:// adresini döner. */
    suspend fun done(t: LanTarget, job: String, name: String, size: Long): String = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url(url(t, job, name, "/done?size=$size")).header("X-GFlix-Key", t.key)
                .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType())).build(),
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Bilgisayar dosyayı tamamlayamadı (HTTP ${r.code}): ${text.take(200)}")
            AppJson.parseToJsonElement(text).jsonObject["uri"]!!.jsonPrimitive.content
        }
    }
}
