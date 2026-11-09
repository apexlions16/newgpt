package com.gflix.app.admin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gflix.app.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Bir işin telefonda gösterilen hâli (GitHub'daki Release'ten). */
data class JobView(
    val head: String,                 // Release gövdesinin ilk satırı ("⏳ Sırada …")
    val status: LinkStatus?,          // canlı durum (bilgisayar yazınca)
    val publishedAt: Long = 0,        // iş gönderildiği an (epoch sn; başlama gecikmesi buna göre)
    val urgent: Boolean = false,      // ⚡ Hemen başlat basıldı
    val gone: Boolean = false,        // Release silinmiş / bulunamadı
) {
    val waiting: Boolean get() = status == null && head.startsWith("⏳") && !gone
    val running: Boolean get() = !gone && (status?.state == "running" || (status == null && !head.startsWith("⏳") && head.isNotBlank()))
    val terminal: Boolean get() = gone || (status != null && status.state != "running")
}

/**
 * İşlerin durumu: bütün ekranlar (Linkten Ekle, Sayfadan Yakala, arka plan yakalama) tek kaynaktan okur.
 *
 * Neden: telefon eskiden gönderdiği her işi listede tutuyor, son 100 Release'te bulunamayan eski işleri 10 sn'de bir
 * TEK TEK soruyordu (140 iş → her turda ~100 istek): liste yavaşlıyor, kartlar "Yükleniyor…"da kalıyor, GitHub
 * istek hakkı eriyordu; durumu okunamayan işler de "sırada" sayılıyordu ("önünde 142 iş var").
 * Şimdi: tek istek (son 100 Release) + bitmiş işin durumu telefona kaydedilir ve bir daha sorulmaz; son 100'de olmayan
 * eski işler (bilgisayar da onlara bakmaz) "bulunamadı" sayılıp listeden kalkar. Sıra, GitHub'daki gerçek bekleyen
 * işlerden hesaplanır.
 */
object JobsTracker {
    val views = mutableStateMapOf<String, JobView>()
    /** Bilgisayarın sırası: önce süren, sonra ⚡ acil, sonra en eskiden bekleyen pcjob- etiketleri */
    var queue by mutableStateOf<List<String>>(emptyList())
        private set
    var lastRefresh by mutableLongStateOf(0L)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private val lock = Mutex()
    private val notFound = HashSet<String>()
    private val FAILED = Release(0, "", false, "", "")
    private const val MIN_INTERVAL_MS = 8_000L
    /** Başarılı iş bu kadar sonra listeden kendiliğinden gizlenir (sadece görünüm) */
    const val AUTO_HIDE_DONE_MS = 10 * 60_000L
    private const val NEW_JOB_GRACE_MS = 6 * 3600_000L

    /**
     * Durumları yenile (en fazla ~8 sn'de bir gerçekten sorar; birden çok ekran aynı anda çağırsa da tek istek).
     */
    suspend fun refresh(context: Context, repo: AdminRepo, force: Boolean = false) = lock.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < MIN_INTERVAL_MS) return@withLock
        val state = repo.store.current()
        val jobs = state.linkJobs
        val gh = runCatching { repo.gh() }.getOrNull() ?: return@withLock
        val rels = runCatching { gh.recentReleases() }.onFailure { lastError = it.message }.getOrNull() ?: return@withLock
        lastError = null
        lastRefresh = now
        val byId = rels.associateBy { it.id }
        // Bilgisayarın gerçek sırası (telefondaki listeden bağımsız)
        val pcJobs = rels.filter { it.tag.startsWith(PcJobs.PREFIX) && !it.draft }
        val running = pcJobs.filter { r -> !r.body.startsWith("⏳") && parseLinkStatus(r.body)?.state.let { it == null && r.body.startsWith("⚙️") || it == "running" } }
        val waiting = pcJobs.filter { it.body.startsWith("⏳") && "cancel" !in it.assetNames }
            .sortedWith(compareBy({ "urgent" !in it.assetNames }, { it.createdAt }))
        queue = running.sortedBy { it.createdAt }.map { it.tag } + waiting.map { it.tag }

        // Telefondaki işler: bitmişlerin durumu zaten kayıtlı, sadece açık olanlara bak
        val open = jobs.filter { j -> j.finalState == null && !(j.phoneMode && j.localState != "published") }
        val missing = open.filter { byId[it.releaseId] == null }
        // Son 100'de olmayan YENİ işler (6 saatten genç): tek tek sorulur (en fazla 10); eskiler (bilgisayar da onlara
        // bakmaz) ve silinmiş olanlar "bulunamadı" sayılır
        val fetched: Map<String, Release?> = coroutineScope {
            missing.filter { now - it.createdAt < NEW_JOB_GRACE_MS && it.tag !in notFound }.take(10)
                .map { j -> async(Dispatchers.IO) { j.tag to runCatching { gh.releaseById(j.releaseId) }.getOrElse { FAILED } } }
                .awaitAll().filter { it.second !== FAILED }.toMap()
        }
        fetched.forEach { (tag, r) -> if (r == null) notFound.add(tag) }
        val finals = HashMap<String, LinkJobRef.() -> LinkJobRef>()
        var refreshCatalog = false
        open.forEach { j ->
            val rel = byId[j.releaseId] ?: fetched[j.tag]
            if (rel == null) {
                if (now - j.createdAt >= NEW_JOB_GRACE_MS || j.tag in notFound) {
                    views[j.tag] = JobView("Bulunamadı", null, gone = true)
                    finals[j.tag] = { copy(finalState = "gone", finishedAt = now, hidden = true) }
                }
                return@forEach
            }
            val st = parseLinkStatus(rel.body)
            val v = JobView(rel.body.lineSequence().firstOrNull().orEmpty(), st, rel.publishedAt, "urgent" in rel.assetNames)
            val prev = views[j.tag]
            views[j.tag] = v
            if (st != null && st.state != "running") {
                if (st.state in setOf("done", "partial") && prev?.status?.state == "running") refreshCatalog = true
                finals[j.tag] = { copy(finalState = st.state, finalTitle = st.title.ifBlank { null }, finishedAt = now) }
            }
        }
        // Bitmiş işler: durumu kaydet (bir daha sorulmaz); başarılılar 10 dk sonra listeden gizlenir
        val hideDone = jobs.filter { it.finalState == "done" && !it.hidden && now - it.finishedAt > AUTO_HIDE_DONE_MS }.map { it.tag }.toSet()
        if (finals.isNotEmpty() || hideDone.isNotEmpty()) {
            repo.store.update { s ->
                s.copy(linkJobs = s.linkJobs.map { j ->
                    var x = finals[j.tag]?.invoke(j) ?: j
                    if (j.tag in hideDone) x = x.copy(hidden = true)
                    x
                }.let(::prune))
            }
        }
        if (refreshCatalog) runCatching { context.appContainer.catalogRepository.refresh() }
    }

    /** Gizlenmiş ve 30 günden eski kayıtlar telefondan atılır (GitHub'daki işe dokunulmaz). */
    private fun prune(list: List<LinkJobRef>): List<LinkJobRef> {
        val cut = System.currentTimeMillis() - 30L * 24 * 3600_000L
        return list.filterNot { it.hidden && it.createdAt < cut }
    }

    /** Kayıtlı son durumla birlikte görünüm (açılışta istek beklemeden kartlar dolu gelsin). */
    fun viewOf(j: LinkJobRef): JobView? = views[j.tag] ?: j.finalState?.let { fs ->
        if (fs == "gone") JobView("Bulunamadı", null, gone = true)
        else JobView("", LinkStatus(state = fs, stage = when (fs) {
            "done" -> "Tamamlandı"; "partial" -> "Kısmen tamamlandı"; "cancelled" -> "İptal edildi"; else -> "Başarısız"
        }, title = j.finalTitle.orEmpty()))
    }

    /** Bekleyen işin önünde kaç iş var (bilgisayarın gerçek sırasına göre). */
    fun ahead(tag: String): Int = queue.indexOf(tag).let { if (it < 0) 0 else it }

    /** "Bitmiş işleri temizle": bitmiş/iptal/hatalı işler listeden gizlenir (sadece görünüm; dosyalara dokunulmaz). */
    suspend fun hideFinished(repo: AdminRepo) {
        repo.store.update { s ->
            s.copy(linkJobs = s.linkJobs.map { j ->
                val v = viewOf(j)
                val localFailed = j.phoneMode && j.localState == "failed"
                if (j.finalState != null || v?.terminal == true || localFailed) j.copy(hidden = true) else j
            })
        }
    }

    /**
     * 🛑 Tüm işleri iptal et: aynı Wi-Fi'deyse bilgisayara doğrudan (anında); değilse GitHub'a komut (bilgisayar
     * 20 sn içinde görür). Bilgisayar süren işleri durdurur, bekleyenleri "İptal edildi" diye kapatır ve yeni iş bekler.
     * Dönen: kullanıcıya gösterilecek ileti.
     */
    suspend fun cancelAll(context: Context, repo: AdminRepo): String {
        val gh = repo.gh()
        val token = repo.store.current().writeToken.orEmpty()
        // Telefonda süren arka plan yakalaması ve telefon indirmeleri de dursun
        CaptureEngine.stopQueue("🛑 Tüm işler iptal edildi")
        repo.store.current().linkJobs.filter { it.phoneMode && it.localState == "downloading" }.forEach { LinkPhoneWorker.cancel(context, it.tag) }
        val lan = withContext(Dispatchers.IO) {
            runCatching {
                val st = PcJobs.status(gh, maxAgeMs = 0) ?: return@runCatching null
                val k = LanClient.key(token)
                st.lan.firstNotNullOfOrNull { addr ->
                    runCatching {
                        LanClient.http.newCall(Request.Builder().url("http://$addr/cancel-all").header("X-GFlix-Key", k)
                            .post(ByteArray(0).toRequestBody("text/plain".toMediaType())).build()).execute().use { r ->
                            if (r.isSuccessful) r.body?.string().orEmpty() else null
                        }
                    }.getOrNull()
                }
            }.getOrNull()
        }
        if (lan != null) {
            refresh(context, repo, force = true)
            val n = Regex("\"cancelled\":\\s*(\\d+)").find(lan)?.groupValues?.get(1)
            return "🛑 Bilgisayar (Wi-Fi) tüm işleri iptal etti" + (n?.let { ": $it iş" } ?: "") + ". Yeni iş beklemeye hazır."
        }
        val tag = "pccmd-" + System.currentTimeMillis()
        val rel = gh.createDraftRelease(tag, "🛑 Tüm işleri iptal et", "cancel_all")
        gh.updateRelease(rel.id, draft = false, body = "cancel_all")
        return "🛑 İptal komutu gönderildi: bilgisayar 20 saniye içinde bütün işleri durdurup yeni iş beklemeye geçer."
    }
}
