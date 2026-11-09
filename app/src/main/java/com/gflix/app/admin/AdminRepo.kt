package com.gflix.app.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gflix.app.appContainer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient

/** Telefondaki yönetim işlemlerinin tek giriş noktası. */
class AdminRepo(private val context: Context) {

    val store = AdminStore(context)

    suspend fun gh(): GitHubAdmin = GitHubAdmin(store.current().writeToken
        ?: if (com.gflix.app.data.PcServer.configured) "" else throw IllegalStateException("Önce yönetici anahtarını gir."))

    suspend fun tmdb(): Tmdb? = store.current().tmdbKey?.takeIf { it.isNotBlank() }?.let { Tmdb(it, sharedClient) }

    // ------------------------------------------------------------ yükleme görevleri

    suspend fun addTasks(tasks: List<UploadTask>) {
        store.update { it.copy(tasks = tasks + it.tasks) }
        tasks.forEach { enqueue(it) }
    }

    private fun enqueue(t: UploadTask) {
        when {
            t.tag == com.gflix.app.archive.PhoneArchiveWorker.TAG && t.files.isEmpty() ->
                com.gflix.app.archive.PhoneArchiveWorker.retry(context, t.id)
            t.archiveItem != null -> com.gflix.app.archive.ArchiveUploadWorker.enqueue(context, t.id)
            else -> UploadWorker.enqueue(context, t.id)
        }
    }

    /** Yeni düzen: içerikler archive.org'da (katalog telefonda tutulur, archive.org'a yazılır) */
    suspend fun archive(): Boolean = store.current().archive

    private val iaCatalog get() = com.gflix.app.archive.ArchiveCatalog(context)

    /** Katalog değişikliği: telefonda hemen, archive.org'a birazdan (art arda değişiklikler tek yazışta) */
    private suspend fun editArchive(transform: com.gflix.app.archive.ArchiveCatalog.(com.gflix.app.data.Catalog) -> com.gflix.app.data.Catalog) {
        val c = iaCatalog
        c.edit { c.transform(it) }
        com.gflix.app.archive.ArchivePublishWorker.enqueue(context, delaySec = 5)
    }

    /** GitHub'a teslim edilmiş işlerin durumunu staging Release'inden okur. */
    suspend fun refreshServerStatus() {
        val tasks = store.current().tasks.filter { it.status == TaskStatus.PROCESSING || it.status == TaskStatus.SERVER_FAILED }
        if (tasks.isEmpty()) return
        val gh = gh()
        var anyDone = false
        for (t in tasks) {
            val result = runCatching { gh.releaseByTag(t.tag) ?: t.releaseId?.let { gh.releaseById(it) } }
            if (result.isFailure) continue
            val rel = result.getOrNull()
            if (rel == null) {
                store.updateTask(t.id) { it.copy(status = TaskStatus.DONE, message = null) }
                releaseUris(t)
                anyDone = true
            } else if (rel.body.startsWith("❌")) {
                store.updateTask(t.id) { it.copy(status = TaskStatus.SERVER_FAILED, message = rel.body.removePrefix("❌").substringBefore("```").trim()) }
            } else {
                store.updateTask(t.id) { it.copy(status = TaskStatus.PROCESSING, message = rel.body.lineSequence().firstOrNull()) }
            }
        }
        if (anyDone) context.appContainer.catalogRepository.refresh()
    }

    suspend fun retry(task: UploadTask) {
        when (task.status) {
            TaskStatus.SERVER_FAILED -> {
                // Release'i taslağa çekip yeniden yayınlamak GitHub Actions'ı yeniden tetikler
                val gh = gh()
                val id = task.releaseId ?: gh.releaseByTag(task.tag)?.id ?: return
                gh.updateRelease(id, draft = true)
                gh.updateRelease(id, draft = false, body = "⏳ Sırada — yeniden deneniyor")
                store.updateTask(task.id) { it.copy(status = TaskStatus.PROCESSING, message = "⏳ Sırada — yeniden deneniyor") }
            }
            else -> {
                store.updateTask(task.id) { it.copy(status = TaskStatus.QUEUED, message = null) }
                enqueue(task)
            }
        }
    }

    suspend fun remove(task: UploadTask) {
        if (task.archiveItem != null) {
            // archive.org'a yarım kalmış dosyalar item'da kalır; yeniden yüklenirse üzerine yazılır
            com.gflix.app.archive.ArchiveUploadWorker.cancel(context, task.id)
            if (task.tag == com.gflix.app.archive.PhoneArchiveWorker.TAG)
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("phone-archive-${task.id}")
            releaseUris(task)
            store.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == task.id }) }
            return
        }
        UploadWorker.cancel(context, task.id)
        if (task.status != TaskStatus.DONE) {
            runCatching {
                val gh = gh()
                task.releaseId?.let { gh.deleteReleaseById(it) }
                gh.deleteRelease(task.tag)
            }
        }
        releaseUris(task)
        store.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == task.id }) }
    }

    private suspend fun releaseUris(task: UploadTask) {
        val stillUsed = store.current().tasks.filter { it.id != task.id && it.status != TaskStatus.DONE }.flatMap { t -> t.files.map { it.uri } }.toSet()
        task.files.map { it.uri }.distinct().filter { it !in stillUsed }.forEach { u ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** Fragmanı olmayan bütün yapımlara TMDB'deki YouTube fragmanlarını ekler; eklenen yapım sayısını döner. */
    suspend fun fillTrailersFromTmdb(): Int {
        val tmdb = tmdb() ?: throw IllegalStateException("Önce Stüdyo Ayarları'na TMDB anahtarını gir.")
        val meta = com.gflix.app.archive.TmdbMeta(tmdb)
        val missing = iaCatalog.current().titles.filter { it.tmdbId != null && it.trailers.isEmpty() && it.trailer == null }
        val found = missing.mapNotNull { t -> runCatching { meta.trailers(t.isSeries, t.tmdbId!!) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { t.id to it } }.toMap()
        if (found.isEmpty()) return 0
        editArchive { c -> c.copy(titles = c.titles.map { t ->
            val tr = found[t.id]
            if (tr != null && t.trailers.isEmpty() && t.trailer == null) t.copy(trailers = tr, trailer = tr.firstOrNull { it.lang == "tr" } ?: tr.first()) else t
        }) }
        return found.size
    }

    // ------------------------------------------------------------ yapım yönetimi (manage.py ile aynı)

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.releaseTag(): String? = (this["release"])?.jsonPrimitive?.content

    /**
     * Hızlı yükleme işi silinince: o işin kataloğa girmemiş (başarısız/iptal) "media-…" Release'lerini temizler.
     * Kataloğa girenler yapımın kendi dosyalarıdır, dokunulmaz.
     */
    suspend fun deleteOrphanMedia(jobTag: String) {
        val prefix = RecipeLinks.mediaTag(jobTag, 0).removeSuffix("0")
        val gh = gh()
        val candidates = gh.listReleases().filter { it.tag.startsWith(prefix) }
        if (candidates.isEmpty()) return
        val repoCatalog = context.appContainer.catalogRepository
        if (!repoCatalog.refresh()) return  // katalog okunamadıysa risk alma
        val used = repoCatalog.catalog.value?.titles.orEmpty().flatMap { t ->
            listOfNotNull(t.stream?.release, t.trailer?.release) + t.seasons.flatMap { s -> s.episodes.mapNotNull { it.stream?.release } }
        }.toSet()
        candidates.filter { it.tag !in used }.forEach { gh.deleteRelease(it.tag) }
    }

    suspend fun deleteTitle(itemId: String) {
        if (archive()) {   // kayıt katalogdan kalkar; archive.org'daki dosyalar durur (Arşiv Yönetimi'nden ayrıca silinebilir)
            editArchive { c -> c.mapTitle(itemId) { null } }
            return
        }
        if (com.gflix.app.data.PcServer.configured) {      // yeni düzen: kayıt bilgisayarda (geri alınabilir şekilde kaldırılır)
            com.gflix.app.data.PcServer.deleteItem(itemId)
            return
        }
        val gh = gh()
        val (item, sha) = gh.getItem(itemId)
        val tags = mutableSetOf<String>()
        item?.get("stream").obj()?.releaseTag()?.let(tags::add)
        item?.get("trailer").obj()?.releaseTag()?.let(tags::add)
        (item?.get("trailers") as? JsonArray)?.forEach { t -> t.obj()?.releaseTag()?.let(tags::add) }
        (item?.get("episodes") as? JsonArray)?.forEach { e -> e.jsonObject["stream"].obj()?.releaseTag()?.let(tags::add) }
        val prefix = "$itemId-"
        gh.listReleases().forEach { r ->
            val t = r.tag
            if (t == itemId || (t.startsWith(prefix) && (t.removePrefix(prefix).startsWith("s") || t.endsWith("-trailer")))) tags += t
        }
        tags.forEach { gh.deleteRelease(it) }
        if (sha != null) gh.deleteItem(itemId, sha, "Yapım silindi: ${item?.get("title")?.jsonPrimitive?.content ?: itemId}")
    }

    suspend fun deleteEpisode(itemId: String, season: Int, episode: Int) {
        if (archive()) {
            editArchive { c -> c.mapTitle(itemId) { t ->
                t.copy(seasons = t.seasons.map { s -> if (s.number == season) s.copy(episodes = s.episodes.filterNot { it.number == episode }) else s }
                    .filter { it.episodes.isNotEmpty() })
            } }
            return
        }
        val gh = gh()
        val (item, sha) = gh.getItem(itemId)
        item ?: throw IllegalStateException("Yapım bulunamadı")
        val eps = (item["episodes"] as? JsonArray).orEmpty()
        val (removed, keep) = eps.partition {
            val o = it.jsonObject
            o["season"]?.jsonPrimitive?.intOrNull == season && o["episode"]?.jsonPrimitive?.intOrNull == episode
        }
        removed.firstOrNull()?.jsonObject?.get("stream").obj()?.releaseTag()?.let { gh.deleteRelease(it) }
        val updated = JsonObject(item.toMutableMap().apply { put("episodes", JsonArray(keep)) })
        gh.putItem(updated, sha, "Bölüm silindi: ${item["title"]?.jsonPrimitive?.content} S%02dE%02d".format(season, episode))
    }

    suspend fun removeTrailer(itemId: String) {
        if (archive()) {
            editArchive { c -> c.mapTitle(itemId) { it.copy(trailer = null, trailers = emptyList()) } }
            return
        }
        val gh = gh()
        val (item, sha) = gh.getItem(itemId)
        item ?: return
        val all = listOfNotNull(item["trailer"].obj()) + (item["trailers"] as? JsonArray).orEmpty().mapNotNull { it.obj() }
        if (all.isEmpty()) return
        all.map { it.releaseTag() ?: "$itemId-trailer" }.toSet().forEach { gh.deleteRelease(it) }
        gh.putItem(JsonObject(item.toMutableMap().apply { remove("trailer"); remove("trailers") }), sha, "Fragman kaldırıldı: ${item["title"]?.jsonPrimitive?.content}")
    }

    // ---- Yakında / zamanlanmış yayın (catalog/items/<id>.json: releaseAt, episodeRelease, comingSoon)

    /** Yapımın yayın zamanını ayarlar (null: hemen yayında). Kayıt yoksa iskelet kayıt açılır. */
    suspend fun setRelease(itemId: String, releaseAt: Long?, skeleton: Skeleton? = null) {
        if (archive()) {
            editArchive { c ->
                if (c.titles.none { it.id == itemId } && skeleton == null) throw IllegalStateException("Yapım bulunamadı")
                c.upsert(itemId, create = { skeleton!!.toTitle(itemId) }) { t ->
                    val bump = releaseAt != null && (t.added ?: 0L) < releaseAt
                    t.copy(releaseAt = releaseAt, added = if (bump) releaseAt else t.added, updated = if (bump) releaseAt else t.updated)
                }
            }
            return
        }
        val gh = gh()
        val (item, sha) = gh.getItem(itemId)
        val base = item ?: skeleton?.toJson(itemId) ?: throw IllegalStateException("Yapım bulunamadı")
        val updated = JsonObject(base.toMutableMap().apply {
            if (releaseAt != null) put("releaseAt", JsonPrimitive(releaseAt)) else remove("releaseAt")
            if (releaseAt != null && (base["added"]?.jsonPrimitive?.longOrNull ?: 0L) < releaseAt) { put("added", JsonPrimitive(releaseAt)); put("updated", JsonPrimitive(releaseAt)) }
        })
        val name = updated["title"]?.jsonPrimitive?.content ?: itemId
        gh.putItem(updated, sha, if (releaseAt != null) "Yayın zamanı: $name" else "Hemen yayında: $name")
    }

    /** Dizinin bir bölümünü zamanlar (bölüm henüz yüklenmemiş olabilir). */
    suspend fun setEpisodeRelease(itemId: String, season: Int, episode: Int, releaseAt: Long?, skeleton: Skeleton? = null) {
        if (archive()) {
            editArchive { c ->
                if (c.titles.none { it.id == itemId } && skeleton == null) throw IllegalStateException("Yapım bulunamadı")
                c.upsert(itemId, create = { skeleton!!.toTitle(itemId) }) { t ->
                    val old = t.seasons.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == episode }
                    com.gflix.app.archive.ArchiveCatalog.putEpisode(t, season, (old ?: com.gflix.app.data.Episode(episode)).copy(releaseAt = releaseAt))
                        .let { u -> u.copy(seasons = u.seasons.map { s -> if (s.number == season) s.copy(episodes = s.episodes.map { e -> if (e.number == episode) e.copy(releaseAt = releaseAt) else e }) else s }) }
                }
            }
            return
        }
        val gh = gh()
        val (item, sha) = gh.getItem(itemId)
        val base = item ?: skeleton?.toJson(itemId) ?: throw IllegalStateException("Yapım bulunamadı")
        val map = (base["episodeRelease"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (releaseAt != null) map["$season:$episode"] = JsonPrimitive(releaseAt) else map.remove("$season:$episode")
        val updated = JsonObject(base.toMutableMap().apply { if (map.isEmpty()) remove("episodeRelease") else put("episodeRelease", JsonObject(map)) })
        gh.putItem(updated, sha, "Bölüm zamanlandı: ${updated["title"]?.jsonPrimitive?.content} S%02dE%02d".format(season, episode))
    }

    /** "Yakında" kaydı: dosyasız yapım (fragman sonra yüklenir). */
    suspend fun addComingSoon(skeleton: Skeleton, releaseAt: Long?): String {
        val itemId = FileNames.itemId(if (skeleton.series) "episode" else "movie", skeleton.title)
        if (archive()) {
            val meta = skeleton.tmdbId?.let { id -> runCatching { tmdb()?.let { com.gflix.app.archive.TmdbMeta(it).title(itemId, skeleton.series, id) } }.getOrNull() }
            editArchive { c ->
                c.upsert(itemId, create = { meta ?: skeleton.toTitle(itemId) }) { t ->
                    t.copy(comingSoon = true, releaseAt = releaseAt ?: t.releaseAt, added = releaseAt ?: t.added, updated = releaseAt ?: t.updated)
                }
            }
            return itemId
        }
        val gh = gh()
        val (item, sha) = gh.getItem(itemId)
        val base = item ?: skeleton.toJson(itemId)
        val updated = JsonObject(base.toMutableMap().apply {
            put("comingSoon", JsonPrimitive(true))
            if (releaseAt != null) { put("releaseAt", JsonPrimitive(releaseAt)); put("added", JsonPrimitive(releaseAt)); put("updated", JsonPrimitive(releaseAt)) }
        })
        gh.putItem(updated, sha, "Yakında: ${skeleton.title}")
        return itemId
    }

    /**
     * Yakında'daki dizi: [season]. sezonun ilk [count] bölümü TAMAMEN yüklenince birlikte açılsın ("Hepsi yüklenince
     * birlikte aç" planı: from 1, to count). Aynı sezonun eski "hepsi yüklenince" kaydı yenisiyle değişir; diğer plan
     * satırları korunur. count null → o sezonun bu kaydı kaldırılır.
     */
    suspend fun setOpenWhen(itemId: String, season: Int, count: Int?) {
        if (archive()) {
            editArchive { c -> c.mapTitle(itemId) { t ->
                val keep = t.releasePlan.filterNot { it.season == season && it.whenComplete && it.from == 1 }
                val add = count?.takeIf { it > 0 }?.let { com.gflix.app.data.PlanEntry(season = season, from = 1, to = it, at = 0, whenComplete = true) }
                val plan = (keep + listOfNotNull(add)).sortedWith(compareBy({ it.season }, { it.from }))
                t.copy(releasePlan = plan)
            } }
            return
        }
        val gh = gh()
        val (item, sha) = gh.getItem(itemId)
        val base = item ?: throw IllegalStateException("Kayıt bulunamadı")
        val plan = (base["releasePlan"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filterNot { e ->
                e["season"]?.jsonPrimitive?.content?.toIntOrNull() == season && e["whenComplete"]?.jsonPrimitive?.content == "true" &&
                    (e["from"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1) == 1
            }
        val add = count?.takeIf { it > 0 }?.let {
            JsonObject(mapOf("season" to JsonPrimitive(season), "from" to JsonPrimitive(1), "to" to JsonPrimitive(it),
                "at" to JsonPrimitive(0L), "whenComplete" to JsonPrimitive(true)))
        }
        val next = (plan + listOfNotNull(add)).sortedWith(compareBy({ it["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }, { it["from"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }))
        gh.putItem(JsonObject(base.toMutableMap().apply {
            if (next.isEmpty()) remove("releasePlan") else put("releasePlan", kotlinx.serialization.json.JsonArray(next))
        }), sha, "Açılış: ${base["title"]?.jsonPrimitive?.content ?: itemId} $season. sezon" + (count?.let { " $it bölüm yüklenince" } ?: " planı kaldırıldı"))
    }

    companion object {
        val sharedClient: OkHttpClient by lazy { OkHttpClient() }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** Henüz kaydı olmayan yapım için iskelet bilgiler. */
data class Skeleton(val title: String, val series: Boolean, val tmdbId: Int? = null, val year: Int? = null) {
    fun toTitle(itemId: String): com.gflix.app.data.Title {
        val now = System.currentTimeMillis() / 1000
        return com.gflix.app.data.Title(id = itemId, type = if (series) "series" else "movie", tmdbId = tmdbId, title = title, year = year, added = now, updated = now)
    }

    fun toJson(itemId: String): JsonObject {
        val now = System.currentTimeMillis() / 1000
        return JsonObject(buildMap {
            put("id", JsonPrimitive(itemId)); put("type", JsonPrimitive(if (series) "series" else "movie")); put("title", JsonPrimitive(title))
            put("added", JsonPrimitive(now)); put("updated", JsonPrimitive(now))
            tmdbId?.let { put("tmdbId", JsonPrimitive(it)) }; year?.let { put("year", JsonPrimitive(it)) }
        })
    }
}
