package com.gflix.app.archive

import com.gflix.app.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** archive.org "yavaşla" dedi (503 SlowDown) ya da hesabın sırası doldu: [retryAfterSec] sonra tekrar denenir. */
class ArchiveBusy(val retryAfterSec: Long, message: String) : IOException(message)

/** Kalıcı hata (anahtar yanlış, item başkasının vb.): tekrar denemek işe yaramaz. */
class ArchiveRejected(message: String) : Exception(message)

/**
 * archive.org'un S3 uyumlu yükleme arayüzü (IAS3). Commit yok: her dosya tek PUT ile gider.
 * İlk PUT item'ı açar (x-archive-auto-make-bucket); item bilgileri (başlık, tür, etiket) o anda yazılır.
 * Yüklenen dosya archive.org'un iş sırasına girer; indirme adresinde görünmesi birkaç dakika sürebilir.
 */
class ArchiveClient(private val access: String, private val secret: String) {

    private val auth get() = "LOW $access:$secret"

    /** Anahtarlar doğru mu (archive.org'un kendi denetimi: ?check_auth=1 → {"authorized": true, …}). */
    suspend fun checkKeys() = withContext(Dispatchers.IO) {
        val r = Request.Builder().url("$S3/?check_auth=1").header("authorization", auth).build()
        quick.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("archive.org'a ulaşılamadı (HTTP ${resp.code}).")
            val o = runCatching { AppJson.parseToJsonElement(resp.body!!.string()) as JsonObject }.getOrNull()
                ?: throw IOException("archive.org'dan beklenmeyen yanıt.")
            if (o["authorized"]?.jsonPrimitive?.content != "true") {
                throw ArchiveRejected("archive.org anahtarları geçersiz (archive.org/account/s3.php). " + (o["error"]?.jsonPrimitive?.content ?: ""))
            }
        }
    }

    /** Hesabın ya da item'ın yükleme sırası dolu mu (doluysa beklenir). */
    suspend fun overLimit(item: String): Boolean = withContext(Dispatchers.IO) {
        val r = Request.Builder().url("$S3/?check_limit=1&accesskey=$access&bucket=$item").build()
        runCatching {
            quick.newCall(r).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val o = AppJson.parseToJsonElement(resp.body!!.string()) as JsonObject
                o["over_limit"]?.jsonPrimitive?.content == "1"
            }
        }.getOrDefault(false)
    }

    /** Item'daki dosyalar: ad → boyut (item yoksa boş). Daha önce yüklenmiş dosyayı tekrar göndermemek için. */
    suspend fun files(item: String): Map<String, Long> = filesInfo(item).mapValues { it.value.first }

    /** Item'daki dosyalar: ad → (boyut, md5). archive.org dosyayı işleyip item'a koyunca burada görünür. */
    suspend fun filesInfo(item: String): Map<String, Pair<Long, String?>> = withContext(Dispatchers.IO) {
        val r = Request.Builder().url("https://archive.org/metadata/$item/files").build()
        runCatching {
            quick.newCall(r).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyMap()
                val o = AppJson.parseToJsonElement(resp.body!!.string()) as? JsonObject ?: return@use emptyMap()
                (o["result"] as? JsonArray).orEmpty().mapNotNull { f ->
                    val fo = f as? JsonObject ?: return@mapNotNull null
                    val name = fo["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    name to ((fo["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L) to fo["md5"]?.jsonPrimitive?.content)
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * Dosyayı item'a yükler. [meta] sadece item ilk açılırken yazılır (title, mediatype, collection, subject…).
     * [replace]: aynı adlı eski dosyanın kopyası tutulmasın (katalog gibi sık değişen dosyalar).
     */
    suspend fun put(
        item: String, name: String, body: RequestBody, meta: List<Pair<String, String>> = emptyList(),
        replace: Boolean = true, interactive: Boolean = false, sizeHint: Long? = null,
    ) = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$S3/$item/${ArchiveSource.encodePath(name)}").put(body)
            .header("authorization", auth)
            .header("x-archive-auto-make-bucket", "1")
            .header("x-archive-queue-derive", "0")              // archive.org kendi kopyalarını üretmesin; orijinal dosya oynatılır
            .header("x-archive-keep-old-version", if (replace) "0" else "1")
        if (interactive) b.header("x-archive-interactive-priority", "1")
        sizeHint?.let { b.header("x-archive-size-hint", it.toString()) }
        meta.groupBy { it.first }.forEach { (key, values) ->
            if (values.size == 1) b.header("x-archive-meta-$key", encodeMeta(values[0].second))
            else values.forEachIndexed { i, v -> b.header("x-archive-meta%02d-%s".format(i + 1, key), encodeMeta(v.second)) }
        }
        upload.newCall(b.build()).execute().use { check(it, item) }
    }

    /** Dosyayı item'dan siler. */
    suspend fun delete(item: String, name: String) = withContext(Dispatchers.IO) {
        val r = Request.Builder().url("$S3/$item/${ArchiveSource.encodePath(name)}").delete()
            .header("authorization", auth).header("x-archive-cascade-delete", "1").build()
        quick.newCall(r).execute().use { if (it.code != 404) check(it, item) }
    }

    private fun check(resp: Response, item: String) {
        if (resp.isSuccessful) return
        val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        val code = Regex("<Code>(.*?)</Code>").find(text)?.groupValues?.get(1).orEmpty()
        val msg = Regex("<Message>(.*?)</Message>").find(text)?.groupValues?.get(1).orEmpty()
        when {
            resp.code == 503 || code == "SlowDown" ->
                throw ArchiveBusy(resp.header("Retry-After")?.toLongOrNull() ?: 60, "archive.org şu an yoğun, biraz sonra devam edilecek.")
            resp.code == 403 && code == "InvalidAccessKeyId" || code == "SignatureDoesNotMatch" ->
                throw ArchiveRejected("archive.org anahtarları geçersiz.")
            resp.code == 403 ->
                throw ArchiveRejected("\"$item\" item'ına yazılamadı. Ad başka bir hesaba ait olabilir; Stüdyo Ayarları'ndan farklı bir önek seç. ($code $msg)".trim())
            resp.code in 400..499 -> throw ArchiveRejected("archive.org isteği reddetti (HTTP ${resp.code} $code $msg)".trim())
            else -> throw IOException("archive.org hatası (HTTP ${resp.code} $code)".trim())
        }
    }

    companion object {
        private const val S3 = "https://s3.us.archive.org"

        private val quick = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

        /** Büyük dosyalar: archive.org dosyanın tamamını aldıktan sonra yanıt verir, okuma süresi uzun tutulur. */
        private val upload = OkHttpClient.Builder()
            // Her dosya kendi bağlantısından gitsin (HTTP/2'de aynı anda yüklenen dosyalar tek bağlantıyı paylaşır)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(20, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .build()

        /** Başlıklarda ASCII dışı karakterler: uri(%C3%A7…) biçimi (archive.org kuralı). */
        fun encodeMeta(value: String): String {
            val clean = value.replace(Regex("\\s+"), " ").trim()
            val plain = clean.all { it.code in 0x20..0x7E }
            return if (plain) clean else "uri(" + java.net.URLEncoder.encode(clean, "UTF-8").replace("+", "%20") + ")"
        }
    }
}
