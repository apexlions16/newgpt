package com.gflix.app.admin

import com.gflix.app.data.AppJson
import com.gflix.app.data.GitHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class GitHubApiException(val code: Int, message: String) : IOException(message)

data class ReleaseAsset(val id: Long, val name: String, val size: Long, val state: String)

data class Release(
    val id: Long, val tag: String, val draft: Boolean, val body: String, val uploadUrl: String,
    /** Oluşturulma / yayınlanma zamanı (epoch sn; bilinmiyorsa 0) ve varlık adları (urgent, cancel …) */
    val createdAt: Long = 0, val publishedAt: Long = 0, val assetNames: List<String> = emptyList(),
)

private fun isoEpoch(s: String): Long = runCatching { java.time.Instant.parse(s).epochSecond }.getOrDefault(0L)

/** Yeni düzen: GitHub'a yapım adı taşıyan hiçbir şey yazılmaz (iş Release'leri, catalog/items). Her şey bilgisayarda. */
private fun writeClosed(): Nothing =
    throw IllegalStateException("GitHub'a yazma kapalı. Yeni düzende kayıtlar ve işler evdeki bilgisayarda (Profilim → Bilgisayar bağlantısı).")

/** GitHub Release'lerine yüklenmesine izin verilen tek dosyalar (küçük komutlar). */
val UPLOAD_ALLOWED = setOf("recipe.txt", "cancel", "approve")

/** Yazma yetkili token ile GitHub işlemleri (yükleme, düzenleme, silme). */
class GitHubAdmin(private val token: String) {

    private val owner = GitHub.owner
    private val repo = GitHub.repo
    private val api = "https://api.github.com/repos/$owner/$repo"
    private val jsonType = "application/json".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    private fun req(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${token.trim()}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private suspend fun call(request: Request, ok: Set<Int> = setOf(200, 201, 204)): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code !in ok) {
                val msg = when (r.code) {
                    401 -> "GitHub token geçersiz."
                    403 -> "Token'ın bu işlem için yetkisi yok (Contents: Read and write gerekli)."
                    404 -> "Bulunamadı."
                    422 -> "GitHub isteği reddetti."
                    else -> "GitHub hatası"
                }
                throw GitHubApiException(r.code, "$msg (HTTP ${r.code}) ${body.take(200)}")
            }
            r.code to body
        }
    }

    private fun parse(body: String): JsonElement = AppJson.parseToJsonElement(body)

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull ?: 0L

    private fun release(o: JsonObject) = Release(
        o.long("id"), o.str("tag_name"), o["draft"]?.jsonPrimitive?.content == "true",
        o["body"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content.orEmpty(), o.str("upload_url").substringBefore("{"),
        createdAt = isoEpoch(o.str("created_at")),
        publishedAt = isoEpoch(o["published_at"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content.orEmpty()),
        assetNames = (o["assets"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty(),
    )

    // ---- depo
    suspend fun canPush(): Boolean {
        val (_, body) = call(req("https://api.github.com/repos/$owner/$repo").build())
        val perms = parse(body).jsonObject["permissions"]?.jsonObject
        return perms?.get("push")?.jsonPrimitive?.content == "true"
    }

    // ---- release'ler
    suspend fun createDraftRelease(tag: String, name: String, body: String): Release {
        writeClosed()
        val payload = buildJsonObject {
            put("tag_name", tag); put("name", name); put("body", body); put("draft", true); put("prerelease", true)
        }
        val (_, res) = call(req("$api/releases").post(payload.toString().toRequestBody(jsonType)).build())
        return release(parse(res).jsonObject)
    }

    /** Yayınlanmış (uygulamanın okuma anahtarıyla görülebilen) Release; varsa olduğu gibi döner. */
    suspend fun ensurePublishedRelease(tag: String, name: String, body: String): Release {
        releaseByTag(tag)?.let { return it }
        val payload = buildJsonObject {
            put("tag_name", tag); put("name", name); put("body", body); put("prerelease", true); put("make_latest", "false")
        }
        val (_, res) = call(req("$api/releases").post(payload.toString().toRequestBody(jsonType)).build())
        return release(parse(res).jsonObject)
    }

    suspend fun releaseByTag(tag: String): Release? {
        if (com.gflix.app.data.PcServer.configured) return null
        val (code, res) = call(req("$api/releases/tags/$tag").build(), setOf(200, 404))
        return if (code == 200) release(parse(res).jsonObject) else null
    }

    suspend fun releaseById(id: Long): Release? {
        if (com.gflix.app.data.PcServer.configured) return null
        val (code, res) = call(req("$api/releases/$id").build(), setOf(200, 404))
        return if (code == 200) release(parse(res).jsonObject) else null
    }

    /**
     * Hesabın saatlik istek hakkı: (kalan, toplam, yenilenme epoch sn). GitHub'ın /rate_limit adresi hakkın kendisinden
     * düşmez; hak hesap başına olduğu için TV, bilgisayar ve telefonların harcadıkları da bu sayıda görünür.
     */
    suspend fun rateLimit(): Triple<Int, Int, Long> {
        val (_, res) = call(req("https://api.github.com/rate_limit").build())
        val core = parse(res).jsonObject["resources"]!!.jsonObject["core"]!!.jsonObject
        fun n(k: String) = core[k]!!.jsonPrimitive.content.toLong()
        return Triple(n("remaining").toInt(), n("limit").toInt(), n("reset"))
    }

    /**
     * En yeni 100 Release, OLUŞTURULMA zamanına göre (tek istek). REST'in /releases listesi tarihe göre değil etiket adına
     * göre sıralı geliyor: rastgele adlı yeni iş (pcjob-0…) ilk 100'ün dışında kalıp görünmüyordu. GraphQL tarihe göre
     * sıralar ve REST istek hakkından düşmez. Olmazsa bütün liste okunup tarihe göre sıralanır.
     */
    suspend fun recentReleases(): List<Release> = runCatching { recentReleasesGraphQl() }.getOrElse {
        listReleases().sortedByDescending { it.createdAt }.take(100)
    }

    private suspend fun recentReleasesGraphQl(): List<Release> {
        val q = "query(\$o:String!,\$n:String!){repository(owner:\$o,name:\$n){releases(first:100,orderBy:{field:CREATED_AT,direction:DESC})" +
            "{nodes{databaseId tagName isDraft description createdAt publishedAt releaseAssets(first:30){nodes{name}}}}}}"
        val payload = buildJsonObject {
            put("query", q)
            put("variables", buildJsonObject { put("o", owner); put("n", repo) })
        }
        val (_, res) = call(req("https://api.github.com/graphql").post(payload.toString().toRequestBody(jsonType)).build())
        val nodes = parse(res).jsonObject["data"]?.jsonObject?.get("repository")?.jsonObject?.get("releases")?.jsonObject?.get("nodes")?.jsonArray
            ?: throw IllegalStateException("GraphQL: ${res.take(200)}")
        return nodes.map { it.jsonObject }.map { o ->
            Release(
                id = o.long("databaseId"), tag = o.str("tagName"), draft = o["isDraft"]?.jsonPrimitive?.content == "true",
                body = o["description"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content.orEmpty(),
                uploadUrl = "https://uploads.github.com/repos/$owner/$repo/releases/${o.long("databaseId")}/assets",
                createdAt = isoEpoch(o.str("createdAt")),
                publishedAt = isoEpoch(o["publishedAt"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content.orEmpty()),
                assetNames = o["releaseAssets"]?.jsonObject?.get("nodes")?.jsonArray?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty(),
            )
        }
    }

    suspend fun listReleases(): List<Release> {
        if (com.gflix.app.data.PcServer.configured) return emptyList()   // yeni düzen: GitHub Release yok
        val out = mutableListOf<Release>()
        var page = 1
        while (true) {
            val (_, res) = call(req("$api/releases?per_page=100&page=$page").build())
            val arr = parse(res).jsonArray.map { release(it.jsonObject) }
            out += arr
            if (arr.size < 100) return out
            page++
        }
    }

    suspend fun updateRelease(id: Long, draft: Boolean? = null, body: String? = null, name: String? = null) {
        writeClosed()
        val payload = buildJsonObject {
            draft?.let { put("draft", it) }
            body?.let { put("body", it) }
            name?.let { put("name", it) }
        }
        call(req("$api/releases/$id").patch(payload.toString().toRequestBody(jsonType)).build())
    }

    suspend fun listAssets(releaseId: Long): List<ReleaseAsset> {
        val out = mutableListOf<ReleaseAsset>()
        var page = 1
        while (true) {
            val (_, res) = call(req("$api/releases/$releaseId/assets?per_page=100&page=$page").build())
            val arr = parse(res).jsonArray.map { it.jsonObject }.map { ReleaseAsset(it.long("id"), it.str("name"), it.long("size"), it.str("state")) }
            out += arr
            if (arr.size < 100) return out
            page++
        }
    }

    /** Release'teki küçük bir metin dosyasını (ör. log.txt) okur; yoksa null. */
    suspend fun assetText(releaseId: Long, name: String): String? {
        val a = listAssets(releaseId).firstOrNull { it.name == name } ?: return null
        val (_, body) = call(req("$api/releases/assets/${a.id}").header("Accept", "application/octet-stream").build())
        return body
    }

    suspend fun deleteAsset(id: Long) {
        call(req("$api/releases/assets/$id").delete().build(), setOf(204, 404))
    }

    /** uploads.github.com'a gönderir. Gövdenin boyutu bilinmeli (GitHub "chunked" kabul etmez). */
    suspend fun uploadAsset(rel: Release, name: String, body: RequestBody) {
        // GitHub'a medya yükleme KAPALI: sadece küçük komut dosyaları (tarif, iptal, onay). Video, ses, altyazı ve
        // yakalama dosyası (altyazı metinleri içerir) GitHub'a gitmez; yeni düzende her şey ev ağından bilgisayara gider.
        val len = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (name !in UPLOAD_ALLOWED || len < 0 || len > 64 * 1024) {
            throw IllegalStateException("GitHub'a dosya yükleme kapalı ($name). Yeni düzende her şey bilgisayara gider.")
        }
        val url = "${rel.uploadUrl}?name=" + java.net.URLEncoder.encode(name, "UTF-8")
        call(req(url).header("Content-Type", "application/octet-stream").post(body).build(), setOf(201))
    }

    suspend fun deleteRelease(tag: String) {
        if (com.gflix.app.data.PcServer.configured) return
        releaseByTag(tag)?.let { call(req("$api/releases/${it.id}").delete().build(), setOf(204, 404)) }
        call(req("$api/git/refs/tags/$tag").delete().build(), setOf(204, 404, 422))
    }

    suspend fun deleteReleaseById(id: Long) {
        if (com.gflix.app.data.PcServer.configured) return
        call(req("$api/releases/$id").delete().build(), setOf(204, 404))
    }

    // ---- içerik dosyaları (catalog/items/*.json)
    suspend fun getItem(itemId: String): Pair<JsonObject?, String?> {
        // Yeni düzen: kayıtlar evdeki bilgisayarda (GitHub'da değil)
        if (com.gflix.app.data.PcServer.configured) return com.gflix.app.data.PcServer.getItem(itemId) to "pc"
        val path = "$api/contents/catalog/items/$itemId.json"
        val (code, meta) = call(req(path).build(), setOf(200, 404))
        if (code == 404) return null to null
        val sha = parse(meta).jsonObject.str("sha")
        val (_, raw) = call(req(path).header("Accept", "application/vnd.github.raw+json").build())
        return parse(raw).jsonObject to sha
    }

    suspend fun putItem(item: JsonObject, sha: String?, message: String): String {
        if (com.gflix.app.data.PcServer.configured) {
            com.gflix.app.data.PcServer.putItem(item, message)
            return "pc"
        }
        writeClosed()
        val id = item["id"]!!.jsonPrimitive.content
        val pretty = kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), item) + "\n"
        val payload = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(pretty.toByteArray()))
            sha?.let { put("sha", it) }
        }
        val (_, res) = call(req("$api/contents/catalog/items/$id.json").put(payload.toString().toRequestBody(jsonType)).build())
        return parse(res).jsonObject["content"]!!.jsonObject.str("sha")
    }

    suspend fun deleteItem(itemId: String, sha: String, message: String) {
        if (com.gflix.app.data.PcServer.configured) { com.gflix.app.data.PcServer.deleteItem(itemId); return }
        val payload = buildJsonObject { put("message", message); put("sha", sha) }
        call(req("$api/contents/catalog/items/$itemId.json").delete(payload.toString().toRequestBody(jsonType)).build())
    }

    suspend fun dispatchCatalog() {
        val (_, info) = call(req("https://api.github.com/repos/$owner/$repo").build())
        val branch = parse(info).jsonObject.str("default_branch")
        val payload = buildJsonObject { put("ref", branch) }
        call(req("$api/actions/workflows/catalog.yml/dispatches").post(payload.toString().toRequestBody(jsonType)).build())
    }
}
