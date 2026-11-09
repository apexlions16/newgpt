package com.gflix.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Evdeki bilgisayarın yayın sunucusu (gflix-yayin): katalog, linkten anlık yayın, "TV'de aç", Sayfadan Yakala kaynakları.
 * GitHub kullanılmaz. Wi-Fi'de ev ağı adresi, dışarıda Tailscale adresi denenir; çalışan adres hatırlanır.
 * Bağlanma: bilgisayarda "gflix-yayin anahtar" → QR / gflix://pc?h=192.168.1.20:8766&t=100.x.y.z:8766&k=ABCD-EFGH-JK23
 */
object PcServer {
    private const val PREFS = "gflix.pc"

    @Volatile private var lan: String = ""
    @Volatile private var tailscale: String = ""
    @Volatile var key: String = ""
        private set
    @Volatile private var working: String? = null

    private val json = "application/json; charset=utf-8".toMediaType()
    private val quick = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    /** Link çözme birkaç saniye ile bir dakika arası sürebilir; TV komutu uzun bekleme yapmaz */
    private val slow = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lan = p.getString("lan", "").orEmpty()
        tailscale = p.getString("ts", "").orEmpty()
        key = p.getString("key", "").orEmpty()
    }

    /** Bu adres evdeki bilgisayar mı (siteye özel başlıklar ona gönderilmez) */
    fun isPc(hostPort: String): Boolean = hostPort.isNotBlank() && (hostPort == lan || hostPort == tailscale || hostPort.endsWith(":8766"))

    /** Doğrudan indirme bilgisi: sitedeki ana liste, istek başlıkları, kaliteler, altyazılar (bilgisayardan, küçük) */
    suspend fun direct(id: String, season: Int?, episode: Int?): DirectInfo = withContext(Dispatchers.IO) {
        AppJson.decodeFromString<DirectInfo>(call(slow, "/api/direct", buildJsonObject {
            put("id", id); season?.let { put("season", it) }; episode?.let { put("episode", it) }
        }))
    }

    /** 🎞 Bilgisayarda küçültme (sitede istenen kalite yoksa): iş başlat / durum / indirildi (dosya silinsin) */
    suspend fun transcodeAdd(id: String, season: Int?, episode: Int?, height: Int): String = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/transcode/add", buildJsonObject {
            put("id", id); season?.let { put("season", it) }; episode?.let { put("episode", it) }; put("height", height)
        })).jsonObject["job"]!!.jsonPrimitive.content
    }
    suspend fun transcodeStatus(job: String): TcStatus = withContext(Dispatchers.IO) {
        AppJson.decodeFromString<TcStatus>(call(slow, "/api/transcode/status", buildJsonObject { put("job", job) }))
    }
    suspend fun transcodeDone(job: String) = withContext(Dispatchers.IO) { call(slow, "/api/transcode/done", buildJsonObject { put("job", job) }) }

    val configured: Boolean get() = key.isNotBlank() && (lan.isNotBlank() || tailscale.isNotBlank())
    val summary: String get() = listOf(lan, tailscale).filter { it.isNotBlank() }.joinToString(" · ")

    fun save(context: Context, lanHost: String, tsHost: String, newKey: String) {
        lan = norm(lanHost); tailscale = norm(tsHost); key = newKey.trim(); working = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lan", lan).putString("ts", tailscale).putString("key", key).apply()
    }

    /** gflix://pc?h=…&t=…&k=… (bilgisayarın gösterdiği QR) */
    fun savePairLink(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "gflix" || uri.host != "pc") return false
        val k = uri.getQueryParameter("k").orEmpty()
        val h = uri.getQueryParameter("h").orEmpty()
        if (k.isBlank() || h.isBlank()) return false
        save(context, h, uri.getQueryParameter("t").orEmpty(), k)
        return true
    }

    private fun norm(h: String): String {
        val x = h.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (x.isBlank()) return ""
        return if (x.contains(':')) x else "$x:8766"
    }

    private fun bases(): List<String> =
        listOfNotNull(working, lan.takeIf { it.isNotBlank() }?.let { "http://$it" }, tailscale.takeIf { it.isNotBlank() }?.let { "http://$it" }).distinct()

    /** Çalışan adres: önce son çalışan, sonra ev ağı, sonra Tailscale (her biri kısa ping). */
    private fun base(): String {
        if (!configured) throw IOException("Bilgisayar bağlantısı kurulmadı (Profil → Bilgisayar bağlantısı).")
        for (b in bases()) {
            val ok = runCatching {
                quick.newCall(Request.Builder().url("$b/api/ping").build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) { working = b; return b }
        }
        working = null
        throw IOException("Bilgisayara ulaşılamadı. Bilgisayar açık mı? Dışarıdaysan Tailscale açık mı?")
    }

    private fun call(client: OkHttpClient, path: String, body: JsonObject? = null): String {
        val b = base()
        val req = Request.Builder().url(b + path).header("X-GFlix-Key", key)
        if (body != null) req.post(body.toString().toRequestBody(json))
        client.newCall(req.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { AppJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw PcException(r.code, msg ?: "Bilgisayar hata verdi (HTTP ${r.code})",
                    runCatching { AppJson.parseToJsonElement(text).jsonObject["needsSource"]?.jsonPrimitive?.content == "true" }.getOrDefault(false))
            }
            return text
        }
    }

    /** Oynatıcının doğrudan açacağı adres (anahtar adreste): ev ağı ya da Tailscale, son çalışan önce. */
    fun liveUrl(path: String): String? {
        if (!configured) return null
        val b = working ?: bases().firstOrNull() ?: return null
        return b + path + (if ('?' in path) "&" else "?") + "k=" + java.net.URLEncoder.encode(key, "UTF-8")
    }

    /** Bilgisayardaki telefon güncellemesi: (sürüm kodu, ad, boyut, indirme adresi) ya da null */
    suspend fun appUpdate(): Triple<Int, String, Pair<Long, String>>? = withContext(Dispatchers.IO) {
        val o = AppJson.parseToJsonElement(call(quick, "/api/app")).jsonObject
        val code = o["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (code <= 0) return@withContext null
        val url = (working ?: return@withContext null) + (o["url"]?.jsonPrimitive?.content ?: "/app.apk")
        Triple(code, o["name"]?.jsonPrimitive?.content.orEmpty(), (o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) to url)
    }

    /** Stüdyo özeti: yapım sayıları, izleme ve geçen veri, bilgisayarda kaplanan yer */
    suspend fun stats(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/stats")).jsonObject }

    /** İstek listesi ile tetiklenen indirme → archive.org aktarım kuyruğu. */
    suspend fun archiveJobs(): JsonObject = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/archive/jobs")).jsonObject
    }

    /** Canlı kayıt satırları (after: son görülen sıra no) */
    suspend fun logText(after: Long): String = withContext(Dispatchers.IO) { call(quick, "/log?after=$after") }

    /** Fragman düzeltme: [bad] bu videoyu bir daha seçme; [url] bu YouTube linkini kullan ([lang] dilinde) */
    suspend fun fixTrailer(id: String, bad: String? = null, url: String? = null, lang: String = "tr") = withContext(Dispatchers.IO) {
        call(slow, "/api/trailer", buildJsonObject {
            put("id", id)
            bad?.let { put("bad", it) }
            url?.let { put("url", it); put("lang", lang) }
        })
    }

    /** Ölü linkler (bilgisayarın günlük kontrolü) */
    suspend fun alerts(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/alerts")).jsonObject }

    suspend fun alertAction(key: String, action: String): String = withContext(Dispatchers.IO) {
        call(slow, "/api/alerts/$action", buildJsonObject { put("key", key) })
    }

    // ---- onay kuyruğu
    suspend fun pending(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/pending")).jsonObject }

    suspend fun approve(pids: List<String>, tmdb: String?, season: Int?, episode: Int?, start: Int? = null): JsonObject = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/pending/approve", buildJsonObject {
            put("pids", kotlinx.serialization.json.JsonArray(pids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            tmdb?.takeIf { it.isNotBlank() }?.let { put("tmdb", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            start?.let { put("start", it) }
        })).jsonObject
    }

    suspend fun reject(pids: List<String>) = withContext(Dispatchers.IO) {
        call(slow, "/api/pending/reject", buildJsonObject { put("pids", kotlinx.serialization.json.JsonArray(pids.map { kotlinx.serialization.json.JsonPrimitive(it) })) })
    }

    suspend fun tmdbSearch(q: String): JsonObject = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/tmdb/search", buildJsonObject { put("q", q) })).jsonObject
    }

    /** İstek listesindeki (TMDB'si seçili) yapımların tahmini tarihleri → Yakında'da "Tahmini 7 Kasım" */
    suspend fun soonPlan(plan: List<Pair<String, Long>>) = withContext(Dispatchers.IO) {
        call(slow, "/api/soon/plan", buildJsonObject {
            put("items", kotlinx.serialization.json.JsonArray(plan.map { (t, eta) -> buildJsonObject { put("tmdb", t); put("eta", eta) } }))
        })
    }

    /** 🌙 Gece önceden yenileme ayarları ve bu geceki sayı */
    suspend fun prefetchGet(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/prefetch")).jsonObject }
    suspend fun prefetchSet(on: Boolean, from: String, to: String, max: Int) = withContext(Dispatchers.IO) {
        call(slow, "/api/prefetch", buildJsonObject { put("on", on); put("from", from); put("to", to); put("max", max) })
    }

    /** İstek listesi uzaktan: yakalama cihazı (TV kutusu) durumunu yazar → panelin komutları döner */
    suspend fun wishReport(device: String, state: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/wish/report", buildJsonObject { put("device", device); put("state", state) })).jsonObject
    }

    /** Yakalama cihazı: panelin komutlarını uzun sorguyla al (komut gelir gelmez döner) */
    suspend fun wishCmds(waitSec: Int): JsonObject = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/wish/cmds?wait=$waitSec")).jsonObject
    }

    /** Panel: yakalama cihazının son durumu */
    suspend fun wishView(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/wish")).jsonObject }

    /** Panel: yakalama cihazına komut */
    suspend fun wishCmd(cmd: JsonObject) = withContext(Dispatchers.IO) { call(slow, "/api/wish/cmd", buildJsonObject { put("cmd", cmd) }) }

    /** Yabancı dizilerin yayınlanmış ama katalogda olmayan yeni bölümleri (bilgisayar günde bir verir) */
    suspend fun newEps(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/neweps")).jsonObject }

    /** Süresi dolmuş yayınlar: telefon yeniden yakalasın (id, sezon, bölüm, sayfa, ad) */
    suspend fun refreshList(waitSec: Int = 0): JsonObject = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, if (waitSec > 0) "/api/refresh?wait=$waitSec" else "/api/refresh")).jsonObject
    }

    /** İstek listesi: bir sayfayı bilgisayarda yakalat (görünür tarayıcı penceresiyle) → iş kimliği */
    suspend fun pccapAdd(url: String, hint: JsonObject?): String = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/pccap/add", buildJsonObject { put("url", url); hint?.let { put("hint", it) } })).jsonObject["jid"]!!.jsonPrimitive.content
    }

    /** İşlerin durumu: kimlik → (wait|work|ok|black, not) */
    suspend fun pccapStatus(jids: List<String>): Map<String, Pair<String, String>> = withContext(Dispatchers.IO) {
        val o = AppJson.parseToJsonElement(call(slow, "/api/pccap/status", buildJsonObject {
            put("jids", kotlinx.serialization.json.JsonArray(jids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })).jsonObject
        (o["jobs"] as? JsonObject).orEmpty().mapValues { (_, v) ->
            val x = v as JsonObject
            (x["state"]?.jsonPrimitive?.content.orEmpty()) to (x["note"]?.jsonPrimitive?.content.orEmpty())
        }
    }

    /** YouTube takibi (dizi kanalları/listeleri) */
    suspend fun follows(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/follows")).jsonObject }

    suspend fun followAdd(url: String, tmdb: String, title: String, season: Int, first: Int, min: Int): JsonObject = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/follows/add", buildJsonObject {
            put("url", url); put("tmdb", tmdb); put("title", title); put("season", season); put("first", first); put("min", min)
        })).jsonObject
    }

    suspend fun followRemove(key: String) = withContext(Dispatchers.IO) { call(slow, "/api/follows/remove", buildJsonObject { put("key", key) }) }

    suspend fun followCheck(key: String): Int = withContext(Dispatchers.IO) {
        AppJson.parseToJsonElement(call(slow, "/api/follows/check", buildJsonObject { put("key", key) })).jsonObject["added"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    }

    /** TMDB duyuruları (yayındaki dizilere yeni sezon/bölüm) */
    suspend fun news(): JsonObject = withContext(Dispatchers.IO) { AppJson.parseToJsonElement(call(slow, "/api/news")).jsonObject }

    suspend fun newsDone(key: String) = withContext(Dispatchers.IO) { call(slow, "/api/news/done", buildJsonObject { put("key", key) }) }

    /** Tema müziği: YouTube video kimliği (yoksa null). bad: yanlış denen, url: elle verilen link. */
    suspend fun theme(id: String, bad: String? = null, url: String? = null): String? = withContext(Dispatchers.IO) {
        val o = AppJson.parseToJsonElement(call(slow, "/api/theme", buildJsonObject {
            put("id", id); bad?.let { put("bad", it) }; url?.let { put("url", it) }
        })).jsonObject
        o["youtube"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    /** İstek listesi: dizinin sezonları (sezon no → bölüm sayısı) */
    suspend fun tmdbSeasons(tmdb: String): List<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        val o = AppJson.parseToJsonElement(call(slow, "/api/tmdb/seasons", buildJsonObject { put("tmdb", tmdb) })).jsonObject
        (o["seasons"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val sn = x["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            sn to (x["episodes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
        }
    }

    /** Yapım kaydı (bilgisayardaki katalog/items): Stüdyo düzenleme ekranları için. Yoksa null. */
    suspend fun getItem(id: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            AppJson.parseToJsonElement(call(slow, "/api/item?id=" + java.net.URLEncoder.encode(id, "UTF-8"))).jsonObject
        } catch (e: PcException) {
            if (e.code == 404) null else throw e
        }
    }

    /** Yapımı kaldır: bilgisayarda silinenler/ klasörüne taşınır (geri alınabilir). */
    suspend fun deleteItem(id: String) = withContext(Dispatchers.IO) { call(slow, "/api/item/delete", buildJsonObject { put("id", id) }) }

    suspend fun putItem(item: JsonObject, message: String) = withContext(Dispatchers.IO) {
        call(slow, "/api/item", buildJsonObject { put("item", item); put("message", message) })
    }

    /** Telefonun tanı satırları (Sayfadan Yakala) bilgisayarın Canlı Kayıt'ına */
    suspend fun clientLog(lines: List<String>) = withContext(Dispatchers.IO) {
        call(quick, "/api/client-log", buildJsonObject {
            put("lines", kotlinx.serialization.json.JsonArray(lines.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
    }

    /** Sayfa linklerinin bilgisayardaki durumu: "live" (yayında) | "pending" (onayda); olmayan yok */
    suspend fun sourceStatus(urls: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val o = AppJson.parseToJsonElement(call(slow, "/api/sources/status", buildJsonObject {
            put("urls", kotlinx.serialization.json.JsonArray(urls.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })).jsonObject
        (o["status"] as? JsonObject).orEmpty().mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
    }

    suspend fun catalogText(): String = withContext(Dispatchers.IO) { call(slow, "/api/catalog") }

    suspend fun play(id: String, season: Int?, episode: Int?, fresh: Boolean = false, maxHeight: Int = 0): PcPlay = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("id", id)
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            if (fresh) put("fresh", true)
            if (maxHeight > 0) put("maxHeight", maxHeight)      // indirme: istenen kalite (bilgisayar o çözünürlüğü verir)
        }
        AppJson.decodeFromString<PcPlay>(call(slow, "/api/play", body))
    }

    /** İndirme kalitesi seçenekleri (p): yayının sunduğu çözünürlükler */
    suspend fun qualities(id: String, season: Int?, episode: Int?): List<Int> = withContext(Dispatchers.IO) {
        val o = AppJson.parseToJsonElement(call(slow, "/api/qualities", buildJsonObject {
            put("id", id); season?.let { put("season", it) }; episode?.let { put("episode", it) }
        })).jsonObject
        (o["heights"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
    }

    suspend fun openOnTv(id: String, season: Int?, episode: Int?, positionSec: Long, title: String) = withContext(Dispatchers.IO) {
        call(slow, "/api/tv/open", buildJsonObject {
            put("id", id)
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            put("position", positionSec)
            put("title", title)
        })
    }

    /** Sayfadan Yakala: sayfanın adresi ve başlığı bilgisayara gider; bilgisayar yapımı/bölümü bulup kaynak olarak kaydeder. */
    suspend fun sendSource(pageUrl: String, pageTitle: String, capture: JsonObject? = null, hint: JsonObject? = null): String = withContext(Dispatchers.IO) {
        val t = call(slow, "/api/source", buildJsonObject {
            put("url", pageUrl); put("title", pageTitle)
            capture?.let { put("capture", it) }
            hint?.let { put("hint", it) }
        })
        val o = AppJson.parseToJsonElement(t).jsonObject
        if (o["pending"]?.jsonPrimitive?.content == "true") {
            // Yeni düzen: önce onay (Stüdyo → Onay bekleyenler), eşleştirince yayına girer
            val g = o["title"]?.jsonPrimitive?.content.orEmpty()
            val ok = o["guessOk"]?.jsonPrimitive?.content == "true"
            return@withContext "📥 Onay bekliyor: $g" + if (!ok) " (⚠ ${o["reason"]?.jsonPrimitive?.content.orEmpty()})" else ""
        }
        val name = o["title"]?.jsonPrimitive?.content ?: o["id"]?.jsonPrimitive?.content.orEmpty()
        val s = o["season"]?.jsonPrimitive?.content?.toIntOrNull()
        val e = o["episode"]?.jsonPrimitive?.content?.toIntOrNull()
        if (s != null && e != null) "$name S${s.toString().padStart(2, '0')}E${e.toString().padStart(2, '0')}" else name
    }
}

class PcException(val code: Int, message: String, val needsSource: Boolean = false) : IOException(message)

@Serializable
data class PcPlay(
    val session: String = "",
    val hls: String = "",
    val subs: List<PcSub> = emptyList(),
    val audio: List<PcAudio> = emptyList(),
    val height: Int = 0,
    val duration: Long = 0,
)

@Serializable
data class TcStatus(val state: String = "", val pct: Int = 0, val size: Long = 0, val err: String = "", val url: String = "", val height: Int = 0)

@Serializable
data class DirectInfo(val master: String = "", val headers: Map<String, String> = emptyMap(), val heights: List<Int> = emptyList(),
                      val subs: List<PcSub> = emptyList())

@Serializable
data class PcSub(val lang: String = "", val label: String? = null, val forced: Boolean = false, val url: String = "")

@Serializable
data class PcAudio(val lang: String = "", val name: String? = null)
