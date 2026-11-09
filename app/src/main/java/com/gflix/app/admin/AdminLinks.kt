package com.gflix.app.admin

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.appContainer
import com.gflix.app.data.AppJson
import com.gflix.app.ui.components.ProgressLine
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.UUID

// ---- tools/link_ingest.py'nin Release gövdesine yazdığı canlı durum
@Serializable
data class LinkFile(val label: String = "", val state: String = "", val done: Long = 0, val total: Long = 0, val speed: Long = 0, val error: String = "")

@Serializable
data class LinkItem(val label: String = "", val state: String = "", val progress: Double = 0.0, val text: String = "", val error: String = "", val files: List<LinkFile> = emptyList())

@Serializable
data class LinkTrailer(val state: String = "", val text: String = "", val files: List<LinkFile> = emptyList())

@Serializable
data class LinkStatus(
    val state: String = "running", val stage: String = "", val title: String = "", val kind: String = "",
    val started: Long = 0, val updated: Long = 0, @SerialName("run_url") val runUrl: String = "",
    val items: List<LinkItem> = emptyList(), val trailer: LinkTrailer? = null,
    val log: List<String> = emptyList(), val warnings: List<String> = emptyList(),
    /** İş bitince Release'e ayrıntılı kayıt (log.txt) kondu mu */
    @SerialName("has_log") val hasLog: Boolean = false,
    /** İşi yapan: "github" ya da Linux bilgisayarın adı */
    val worker: String = "github",
    /** İndirmeden önce onay: toplam boyut, işlerken gereken en fazla boş alan, bilgisayardaki boş alan */
    val approval: Approval? = null,
    /** Aşama zamanları (süre satırı ve istatistik) */
    val timing: JobStat? = null,
)

@Serializable
data class Approval(val total: Long = 0, val peak: Long = 0, val free: Long = 0, val items: List<ApprovalItem> = emptyList(), val unknown: Int = 0)

@Serializable
data class ApprovalItem(val label: String = "", val bytes: Long = 0)

private const val MARK = "<!--GFLIX_STATUS"

fun parseLinkStatus(body: String): LinkStatus? {
    if (!body.contains(MARK)) return null
    val json = body.substringAfter(MARK).substringBeforeLast("-->").trim()
    return runCatching { AppJson.decodeFromString(LinkStatus.serializer(), json) }.getOrNull()
        ?: runCatching {     // süre kaydı bozuksa onsuz oku (kart yine görünsün)
            val obj = AppJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            AppJson.decodeFromJsonElement(LinkStatus.serializer(), kotlinx.serialization.json.JsonObject(obj - "timing"))
        }.getOrNull()
}

private fun human(n: Long): String = when {
    n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / 1073741824.0)
    n >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
    n >= 1024 -> String.format(Locale.US, "%.0f KB", n / 1024.0)
    else -> "$n B"
}

/** Dil başına video (Türkçe dublajlı + İngilizce) ve altyazılar; fragman yazılmaz (TMDB'den otomatik gelir). */
const val LANG_BLOCK = "video tr: \nvideo en: \naltyazı tr: \naltyazı en: \n"

const val TEMPLATE_MOVIE = "tmdb: \n" + LANG_BLOCK

const val TEMPLATE_SERIES = "tmdb: \nbölüm S01E01:\n" + LANG_BLOCK + "bölüm S01E02:\n" + LANG_BLOCK

/** Hızlı ön kontrol (asıl kontrol GitHub'da yapılır ve hata canlı durumda görünür). */
/** Tek başına duran linkler (sayfa ya da ana m3u8): bilgisayar kendisi çözer. */
fun bareLinks(text: String): List<String> = text.lines().map { it.trim() }
    .filter { (it.startsWith("http://") || it.startsWith("https://")) && ' ' !in it }

fun quickCheckRecipe(text: String): List<String> {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val errors = mutableListOf<String>()
    if (lines.none { (it.lowercase().startsWith("tmdb:") || it.lowercase().startsWith("ad:")) && it.substringAfter(':').isNotBlank() }) errors += "'tmdb:' satırına TMDB adresini yapıştır."
    // "video:", "video tr:", "video en:", "film:" … (dil başına video)
    val videos = lines.filter { l -> l.substringBefore(':').trim().lowercase().split(Regex("\\s+")).first() in setOf("video", "film") && l.substringAfter(':').isNotBlank() }
    if (videos.isEmpty()) errors += "En az bir video satırı gerekli (video tr: / video en:)."
    lines.forEachIndexed { i, l ->
        val key = l.substringBefore(':').lowercase().split(" ").first()
        if (key in setOf("video", "film", "ses", "audio", "dublaj", "altyazı", "altyazi", "sub", "subtitle")) {
            val v = l.substringAfter(':').substringBefore('|').trim()
            if (v.isNotEmpty() && !v.startsWith("http://") && !v.startsWith("https://")) errors += "${i + 1}. dolu satır: link http:// ya da https:// ile başlamalı (\"$l\")"
        }
    }
    return errors
}

private fun tmdbUrl(series: Boolean, id: Int) = "https://www.themoviedb.org/${if (series) "tv" else "movie"}/$id"

/** Tarif şablonu: TMDB (ya da ad), dizide tek bölüm başlığı. Fragman satırı yok (otomatik gelir). */
private fun recipeFor(series: Boolean, tmdbId: Int?, name: String, episode: Pair<Int, Int>?): String {
    val head = if (tmdbId != null) "tmdb: ${tmdbUrl(series, tmdbId)}\n" else "ad: $name\n"
    return if (!series) head + LANG_BLOCK
    else head + "bölüm S%02dE%02d:\n".format(episode?.first ?: 1, episode?.second ?: 1) + LANG_BLOCK
}

@Composable
fun AdminLinksScreen(onBack: () -> Unit, presetItem: String? = null) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    val state by repo.store.state.collectAsState(initial = null)
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var who by remember { mutableStateOf("pc") }   // pc | github | phone
    val phoneMode = who == "phone"
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var confirmCancelAll by remember { mutableStateOf(false) }
    var pc by remember { mutableStateOf<PcStatus?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(1000) } }     // geri sayım
    LaunchedEffect(Unit) { while (true) { pc = runCatching { PcJobs.status(repo.gh()) }.getOrNull(); delay(20_000) } }
    var detailLog by remember { mutableStateOf<String?>(null) }
    detailLog?.let { text -> DetailLogDialog(text) { detailLog = null } }

    // ---- kısayollar: TMDB'de ara, yeni bölüm, yakındakilere medya
    val raw by context.appContainer.catalogRepository.catalog.collectAsState()
    var tmdb by remember { mutableStateOf<Tmdb?>(null) }
    LaunchedEffect(Unit) { tmdb = repo.tmdb() }
    var picker by remember { mutableStateOf<String?>(null) }        // TMDB araması (sorgu)
    var pickAndSend by remember { mutableStateOf(false) }
    var listPicker by remember { mutableStateOf<String?>(null) }    // "series" | "soon"
    var info by remember { mutableStateOf<String?>(null) }
    var langFor by remember { mutableStateOf<String?>(null) }
    var expDialog by remember { mutableStateOf(false) }
    val approvalDismissed = remember { mutableStateMapOf<String, Boolean>() }   // 1DM Experimental: Video + Audio linki → tek satır   // "video" | "altyazı" | "ses": dil seçilince satır eklenir

    /** Kutudaki tarifte zaten yazılı bölümler (S01E06 …) */
    fun episodesInText(): List<Pair<Int, Int>> = Regex("(?i)b[öo]l[üu]m\\s*s(\\d+)\\s*e(\\d+)").findAll(text)
        .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()

    /**
     * Kütüphanedeki diziye yeni bölüm: yüklü bölümleri say, TMDB'den sezon sonlarını al, sıradakini aç.
     * Kutuda aynı dizinin tarifi varsa silinmez: sıradaki bölümün şablonu altına eklenir (istediğin kadar bölüm).
     */
    fun newEpisodeFor(t: com.gflix.app.data.Title) {
        info = "Bölümler kontrol ediliyor…"
        scope.launch {
            val uploaded = t.seasons.flatMap { s -> s.episodes.filter { it.stream != null }.map { s.number to it.number } }
            val seasons = t.tmdbId?.let { id -> tmdb?.let { runCatching { it.seasons(id) }.getOrNull() } }
            val inText = episodesInText()
            val same = inText.isNotEmpty() && (t.tmdbId?.let { text.contains("/tv/$it") } ?: text.contains("ad: ${t.title}"))
            val next = NextEpisode.next(uploaded + if (same) inText else emptyList(), seasons)
            if (same) {
                text = text.trimEnd() + "\nbölüm S%02dE%02d:\n".format(next.first, next.second) + LANG_BLOCK
                info = "📺 ${t.title} — %d. sezon %d. bölüm şablonu eklendi (kutuda ${inText.size + 1} bölüm). Tekrar bas, bir sonrakini ekle.".format(next.first, next.second)
                return@launch
            }
            text = recipeFor(true, t.tmdbId, t.title, next)
            info = "📺 ${t.title} — " + NextEpisode.summary(uploaded, seasons) + "\n→ Sıradaki: %d. sezon %d. bölüm (S%02dE%02d). Video linkini yapıştır.".format(next.first, next.second, next.first, next.second) +
                if (seasons == null) "\n(TMDB anahtarı yoksa sezon sonu bilinemez; gerekirse bölüm satırını düzelt.)" else ""
        }
    }

    fun mediaFor(t: com.gflix.app.data.Title) {
        if (t.isSeries) newEpisodeFor(t) else {
            text = recipeFor(false, t.tmdbId, t.title, null)
            info = "🎬 ${t.title} — video linkini yapıştır" + if (t.releaseAt != null) " (yayın tarihine kadar gizli kalır)." else "."
        }
    }

    LaunchedEffect(presetItem, raw) {
        if (presetItem != null && text.isBlank()) raw?.titles?.firstOrNull { it.id == presetItem }?.let { mediaFor(com.gflix.app.data.Presentation.localize(it, null)) }
    }

    // İşlerin durumu: tek istekte (son 100 Release), 10 sn'de bir; bitmiş işlerin durumu telefona kaydedilir, bir daha
    // sorulmaz (eskiden her eski iş tek tek soruluyordu: liste yavaşlıyor, istek hakkı eriyordu). JobsTracker'a bak.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { JobsTracker.refresh(context, repo) }
            delay(10_000)
        }
    }

    fun asset(j: LinkJobRef, name: String, ok: String) {
        scope.launch {
            runCatching {
                val gh = repo.gh()
                gh.releaseById(j.releaseId)?.let { gh.uploadAsset(it, name, "1".toByteArray().toRequestBody("text/plain".toMediaType())) }
                    ?: throw IllegalStateException("İşin kaydı bulunamadı")
            }.onSuccess { message = ok; JobsTracker.refresh(context, repo, force = true) }.onFailure { message = it.message }
        }
    }

    fun jobHandlers(j: LinkJobRef) = JobHandlers(
        urgent = { asset(j, "urgent", "⚡ Hemen başlat: gecikme ve gece modu atlanır, sıranın başına geçti (bilgisayar 20 sn içinde görür; aynı anda yine tek iş).") },
        approve = { asset(j, "approve", "✓ Onaylandı; bilgisayar 10 saniye içinde indirmeye başlar.") },
        cancel = {
            if (j.phoneMode && j.localState == "downloading") {
                LinkPhoneWorker.cancel(context, j.tag)
                scope.launch { repo.store.updateLinkJob(j.tag) { it.copy(localState = "failed", localMessage = "Durduruldu") } }
            } else asset(j, "cancel", "İptal isteği gönderildi (15 sn içinde durur).")
        },
        remove = {
            LinkPhoneWorker.cancel(context, j.tag)
            scope.launch {
                runCatching { repo.gh().deleteReleaseById(j.releaseId); repo.gh().deleteRelease(j.tag) }
                // GitHub hâlâ işliyorsa yarım dosyalara dokunma (işlenenler kataloğa girecek)
                if (JobsTracker.viewOf(j)?.status?.state != "running") runCatching { repo.deleteOrphanMedia(j.tag) }
                repo.store.update { s -> s.copy(linkJobs = s.linkJobs.filterNot { it.tag == j.tag }) }
                JobsTracker.views.remove(j.tag)
            }
        },
        openLog = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        detailLog = {
            detailLog = "Kayıt indiriliyor…"
            scope.launch {
                detailLog = runCatching { repo.gh().assetText(j.releaseId, "log.txt") ?: "Bu iş için ayrıntılı kayıt yok." }
                    .getOrElse { "Kayıt okunamadı: ${it.message}" }
            }
        },
    )

    /** Sadece link(ler) yapıştırıldıysa: her link ayrı iş; tmdb/bölüm satırı da varsa tek iş olarak gider. */
    fun sendLinks(links: List<String>) {
        val others = text.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in links && it.substringAfter(':').isNotBlank() }
        val recipes = if (others.isEmpty()) links else listOf(text.lines().filter { l -> l.trim().let { it.isEmpty() || it in links || it.substringAfter(':').isNotBlank() } }.joinToString("\n"))
        if (others.isNotEmpty() && links.size > 1) { message = "Tarif satırlarıyla (tmdb/bölüm) birlikte tek link verilebilir. Birden çok bölüm için sadece linkleri alt alta yapıştır."; return }
        if (who == "phone") who = "pc"
        sending = true
        scope.launch {
            runCatching {
                val gh = repo.gh()
                recipes.forEach { recipe ->
                    val tag = if (who == "pc") PcJobs.newTag() else "linkjob-" + UUID.randomUUID().toString().take(12)
                    val label = recipe.lines().firstOrNull { it.trim().lowercase().startsWith("ad:") }?.substringAfter(':')?.trim()
                        ?: links.first { recipe.contains(it) }.let { java.net.URI(it).let { u -> (u.host ?: "link").removePrefix("www.") + (u.path ?: "").take(40) } }
                    val rel = gh.createDraftRelease(tag, "🔗 $label", "Hazırlanıyor…")
                    gh.uploadAsset(rel, "recipe.txt", recipe.toByteArray().toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    gh.updateRelease(rel.id, draft = false, body = if (who == "pc") "⏳ Sırada — bilgisayar alacak" else "⏳ Sırada — GitHub başlatıyor")
                    repo.store.update { it.copy(linkJobs = listOf(LinkJobRef(tag, rel.id, label, System.currentTimeMillis())) + it.linkJobs) }
                }
            }.onSuccess {
                text = ""
                message = "✓ ${recipes.size} link gönderildi. Bilgisayar sayfayı açıp yayını, sesleri, altyazıları ve yapımı kendisi bulacak; aşağıdan takip et."
            }.onFailure { message = "Gönderilemedi: ${it.message}" }
            sending = false
        }
    }

    fun send() {
        // "video tr:" satırının linki alt satıra düştüyse birleştir (aksi hâlde link tek başına "sayfa linki" sanılır)
        text = text.lines().fold(mutableListOf<String>()) { acc, l ->
            val prev = acc.lastOrNull()?.trim().orEmpty()
            if (l.trim().startsWith("http") && prev.endsWith(":") &&
                prev.substringBefore(':').trim().lowercase().split(Regex("\\s+")).first() in setOf("video", "film", "ses", "audio", "altyazı", "altyazi", "sub", "subtitle")) {
                acc[acc.size - 1] = prev + " " + l.trim()
            } else acc += l
            acc
        }.joinToString("\n")
        bareLinks(text).takeIf { it.isNotEmpty() }?.let { sendLinks(it); return }
        // HLS (m3u8) ve "| ses:" satırlarını telefon indiremez: bilgisayar indirsin
        if (who == "phone" && (text.contains(".m3u8", true) || text.contains("| ses:", true))) who = "pc"
        val tmdbLine = text.lines().firstOrNull { it.trim().lowercase().startsWith("tmdb:") }?.substringAfter(':')?.trim().orEmpty()
        if (tmdbLine.isNotEmpty() && !tmdbLine.startsWith("http") && tmdbLine.toIntOrNull() == null) {
            if (tmdb == null) { message = "'tmdb:' satırına kelimeyle aramak için Stüdyo Ayarları'na TMDB anahtarı gir (ya da TMDB linkini yapıştır)."; return }
            picker = tmdbLine; pickAndSend = true; return
        }
        val problems = quickCheckRecipe(text)
        if (problems.isNotEmpty()) { message = problems.joinToString("\n"); return }
        sending = true
        // Hızlı (birleştirmeden) mod seslerin kaymasına yol açtı: her zaman MKVToolNix ile birleştirilir
        val recipeText = text.lines().filterNot { it.trim().lowercase().startsWith("mod:") }.joinToString("\n")
        scope.launch {
            runCatching {
                val gh = repo.gh()
                val tag = if (who == "pc") PcJobs.newTag() else "linkjob-" + UUID.randomUUID().toString().take(12)
                val title = text.lines().firstOrNull { it.trim().lowercase().startsWith("ad:") }?.substringAfter(':')?.trim()
                    ?: text.lines().firstOrNull { it.trim().lowercase().startsWith("tmdb:") }?.substringAfter(':')?.trim()?.substringAfterLast('/')?.ifBlank { null }
                    ?: "Linkten ekleme"
                val rel = gh.createDraftRelease(tag, "🔗 $title", if (phoneMode) "📱 Telefon indiriyor…" else "Hazırlanıyor…")
                if (phoneMode) {
                    // Telefon indirip aktaracak; Release, aktarım bitince yayınlanır
                    repo.store.update { it.copy(linkJobs = listOf(LinkJobRef(tag, rel.id, title, System.currentTimeMillis(), phoneMode = true, recipe = recipeText, localState = "downloading")) + it.linkJobs) }
                    LinkPhoneWorker.enqueue(context, tag)
                } else {
                    gh.uploadAsset(rel, "recipe.txt", recipeText.toByteArray().toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    gh.updateRelease(rel.id, draft = false, body = if (who == "pc") "⏳ Sırada — bilgisayar alacak" else "⏳ Sırada — GitHub başlatıyor")
                    repo.store.update { it.copy(linkJobs = listOf(LinkJobRef(tag, rel.id, title, System.currentTimeMillis())) + it.linkJobs) }
                }
            }.onSuccess {
                text = ""
                message = when (who) {
                    "phone" -> "✓ Telefon indirmeye başladı (bildirimden de izleyebilirsin). Bitince GitHub birleştirip yükleyecek."
                    "pc" -> "✓ Gönderildi. Bilgisayarın 20 saniye içinde alır; aşağıdan takip et."
                    else -> "✓ Gönderildi. GitHub birkaç saniye içinde başlar; aşağıdan takip et."
                }
            }
                .onFailure { message = "Gönderilemedi: ${it.message}" }
            sending = false
        }
    }

    picker?.let { q ->
        tmdb?.let { t ->
            TmdbPickerDialog(t, q, tv = text.contains(Regex("(?i)bölüm s\\d")), year = null, onPick = { r ->
                val series = r.kind == "tv"
                val url = tmdbUrl(series, r.id)
                val lib = raw?.titles?.firstOrNull { it.tmdbId == r.id && it.isSeries == series }
                picker = null
                when {
                    text.lines().any { it.trim().lowercase().startsWith("tmdb:") } ->
                        text = text.lines().joinToString("\n") { if (it.trim().lowercase().startsWith("tmdb:")) "tmdb: $url" else it }
                    series && lib != null -> { newEpisodeFor(com.gflix.app.data.Presentation.localize(lib, null)); return@TmdbPickerDialog }
                    else -> text = recipeFor(series, r.id, r.title, if (series) 1 to 1 else null)
                }
                info = (if (series) "📺 " else "🎬 ") + r.title + (r.year?.let { " ($it)" } ?: "") + if (lib != null) " — kütüphanede var" else " — yeni"
                if (pickAndSend) { pickAndSend = false; send() }
            }, onDismiss = { picker = null; pickAndSend = false })
        }
    }
    listPicker?.let { kind ->
        val all = raw?.titles.orEmpty()
        val list = if (kind == "series") all.filter { it.isSeries } else com.gflix.app.data.Library.comingSoon(raw, null)
        TitlePickerDialog(
            title = if (kind == "series") "Hangi dizinin yeni bölümü?" else "Yakındakilerden hangisi?",
            items = list.map { com.gflix.app.data.Presentation.localize(it, null) },
            subtitle = { t ->
                if (t.isSeries) {
                    val n = t.seasons.sumOf { s -> s.episodes.count { it.stream != null } }
                    "Dizi · " + (if (n > 0) "$n bölüm yüklü" else "henüz bölüm yok")
                } else "Film" + (t.releaseAt?.let { " · " + com.gflix.app.data.Library.releaseText(it, false, false) } ?: "")
            },
            empty = if (kind == "series") "Kütüphanede dizi yok. 🔎 ile ara." else "Yakında'da bir şey yok.",
            onPick = { listPicker = null; mediaFor(it) },
            onDismiss = { listPicker = null },
        )
    }

    // Onay bekleyen iş varsa: ekranın ortasında pencere (iş kartı listenin en altında kalıp gözden kaçmasın)
    state?.linkJobs.orEmpty().firstOrNull { j ->
        val st = JobsTracker.viewOf(j)?.status
        !j.hidden && st?.approval != null && st.state == "running" && approvalDismissed[j.tag] != true
    }?.let { j ->
        val st = JobsTracker.viewOf(j)!!.status!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { approvalDismissed[j.tag] = true }, containerColor = Color(0xFF1F1F1F), confirmButton = {},
            title = { Text(st.title.ifBlank { j.title }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                ApprovalBox(st.approval!!,
                    onApprove = {
                        approvalDismissed[j.tag] = true
                        scope.launch {
                            runCatching {
                                val gh = repo.gh()
                                gh.releaseById(j.releaseId)?.let { gh.uploadAsset(it, "approve", "1".toByteArray().toRequestBody("text/plain".toMediaType())) }
                            }.onSuccess { message = "✓ Onaylandı; bilgisayar 10 saniye içinde indirmeye başlar." }.onFailure { message = it.message }
                        }
                    },
                    onCancel = {
                        approvalDismissed[j.tag] = true
                        scope.launch {
                            runCatching {
                                val gh = repo.gh()
                                gh.releaseById(j.releaseId)?.let { gh.uploadAsset(it, "cancel", "1".toByteArray().toRequestBody("text/plain".toMediaType())) }
                            }.onSuccess { message = "İptal edildi." }.onFailure { message = it.message }
                        }
                    })
            },
        )
    }
    if (expDialog) ExperimentalDialog(onDismiss = { expDialog = false }) { lang, video, audio ->
        // Kutuda aynı dilin boş satırı varsa (şablon) onu doldur, yoksa sona ekle
        val line = "video $lang: $video | ses: $audio"
        val lines = text.lines().toMutableList()
        val lastHeader = lines.indexOfLast { Regex("(?i)^b[öo]l[üu]m\\s*s\\d+e\\d+").containsMatchIn(it.trim()) }
        val empty = lines.indices.firstOrNull { i -> i > lastHeader && lines[i].trim().lowercase().replace(" ", "") == "video$lang:" }
        if (empty != null) lines[empty] = line else lines += line
        text = lines.joinToString("\n")
        expDialog = false
    }
    langFor?.let { role ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { langFor = null }, containerColor = Color(0xFF1F1F1F), confirmButton = {},
            title = { Text(when (role) { "video" -> "Videonun dili"; "ses" -> "Sesin dili"; else -> "Altyazının dili" }, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Langs.choices.filter { it.first != "und" }.forEach { (code, label) ->
                        Text("$label ($code)", color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable {
                            text = text.trimEnd() + "\n$role $code: "
                            langFor = null
                        }.padding(vertical = 10.dp))
                    }
                    if (role == "altyazı") Text("Türkçe zorunlu (sadece yabancı konuşmalar)", color = GColors.TextSecondary, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { text = text.trimEnd() + "\naltyazı tr zorunlu: "; langFor = null }.padding(vertical = 10.dp))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AdminTopBar("Linkten Ekle", onBack)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val allJobs = state?.linkJobs.orEmpty().filterNot { it.hidden }
        // Süren / sıradaki işler: en üstte özet (kartlar listenin en altında kalıyordu). Durumu bilinmeyen eski işler sayılmaz.
        val active = allJobs.filter { j -> JobsTracker.viewOf(j)?.let { !it.terminal } ?: (System.currentTimeMillis() - j.createdAt < 6 * 3600_000L) }
            .sortedBy { JobsTracker.queue.indexOf(it.tag).let { i -> if (i < 0) Int.MAX_VALUE else i } }.take(5)
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp)) {
            if (active.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1B2A1B))
                    .clickable { scope.launch { listState.animateScrollToItem(3) } }.padding(12.dp)) {
                    Text("Süren işler", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    active.forEach { j ->
                        val v = JobsTracker.viewOf(j)
                        val st = v?.status
                        val pct = st?.items?.takeIf { it.isNotEmpty() }?.let { its -> (its.sumOf { it.progress } / its.size * 100).toInt() }
                        Text("⚙️ " + (st?.title?.ifBlank { null } ?: j.title).take(60), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(st?.stage ?: v?.let { waitText(j.tag, it, pc, now) } ?: "Durumu okunuyor…", pct?.let { "%$it" }).joinToString(" · "),
                            color = GColors.Match, fontSize = 12.sp)
                        st?.log?.lastOrNull()?.let { Text(it.trim(), color = GColors.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    Hint("Ayrıntı için dokun (iş kartına kayar).", Modifier.padding(top = 4.dp))
                }
            }
            item {
                Text("Ne ekliyorsun?", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "🔎 Film/Dizi ara" to { if (tmdb == null) message = "Aramak için Stüdyo Ayarları'na TMDB anahtarı gir." else picker = "" },
                        "📺 Yeni bölüm" to { listPicker = "series" },
                        "🗓️ Yakındakilere" to { listPicker = "soon" },
                    ).forEach { (l, a) ->
                        Text(l, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Color.White).clickable { a() }.padding(vertical = 10.dp, horizontal = 6.dp))
                    }
                }
                info?.let { Text(it, color = GColors.Match, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(10.dp))
                Hint("⚡ En kolayı: izleme sayfasının linkini, YouTube linkini (Türk dizileri/filmleri) ya da 1DM'de kopyaladığın ana m3u8 linkini yapıştır, başka bir şey yazma. " +
                    "Bilgisayar yapımı, bölümü (\"150. Bölüm\" de olur), sesleri ve altyazıları kendisi bulur; en iyi kalitede indirir. İndirmeden önce toplam boyutu sorar, bölümleri sırayla işler. Birden çok bölüm için linkleri alt alta yapıştır.")
                Spacer(Modifier.height(6.dp))
                Hint("Ya da tarifle: GitHub hepsini aynı anda indirir, .txt uzantılarını düzeltir, MKVToolNix ile birleştirir, yükler ve fragmanı ekler. " +
                    "Her satır: video tr / video en / altyazı tr / altyazı en (zorunlu için: altyazı tr zorunlu). Fragman yazma, TMDB'den kendisi gelir. Dizide her bölüm 'bölüm S01E01:' satırıyla başlar.\n" +
                    "🎬 1DM'deki Experimental gibi: 'video tr:' ve 'video en:' satırlarına aynı ana m3u8 linkini ver; Türkçe ve orijinal sesli tek parça videolar olur. " +
                    "1DM görüntü ve sesi ayrı link verirse: video tr: <görüntü linki> | ses: <ses linki>")
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val chips = listOf(
                        "Film şablonu" to { text = TEMPLATE_MOVIE },
                        "Dizi şablonu" to { text = TEMPLATE_SERIES },
                        "🎬 1DM Experimental (video + ses)" to { expDialog = true },
                        "+ video (dil seç)" to { langFor = "video" },
                        "+ altyazı (dil seç)" to { langFor = "altyazı" },
                        "+ ayrı ses (dil seç)" to { langFor = "ses" },
                        "+ sonraki bölüm" to plus@{
                            // Kutudaki dizi kütüphanede varsa: yüklü + kutudaki bölümlerden sonrakini (sezon sonunu bilerek) ekle
                            val id = Regex("/tv/(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                            raw?.titles?.firstOrNull { it.isSeries && it.tmdbId == id && id != null }?.let { newEpisodeFor(com.gflix.app.data.Presentation.localize(it, null)); return@plus }
                            // Kütüphanede yoksa: kutudaki bölümlerden sonrakini TMDB'deki sezon sonlarına göre ekle
                            scope.launch {
                                val seasons = id?.let { x -> tmdb?.let { runCatching { it.seasons(x) }.getOrNull() } }
                                val (s, e) = NextEpisode.next(episodesInText(), seasons)
                                text = text.trimEnd() + "\nbölüm S%02dE%02d:\n".format(s, e) + LANG_BLOCK
                            }
                        },
                    )
                    items(chips) { (label, action) ->
                        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF333333)).clickable { action() }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it; message = null },
                    placeholder = { Text("tmdb: https://www.themoviedb.org/movie/…  (ya da sadece adı: tmdb: arabalar 3)\nvideo: https://…") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White),
                    colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                )
                message?.let { Text(it, color = if (it.startsWith("✓")) GColors.Match else Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(10.dp))
                Text("Kim indirsin?", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("pc" to "🖥️ Bilgisayarım", "github" to "☁️ GitHub", "phone" to "📱 Telefon").forEach { (v, l) ->
                        val sel = who == v
                        Text(l, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(if (sel) Color.White else Color(0xFF333333))
                                .clickable { who = v }.padding(vertical = 10.dp, horizontal = 6.dp))
                    }
                }
                Hint(when (who) {
                    "pc" -> "Linux bilgisayarın indirir (aria2, yt-dlp), MKVToolNix ile birleştirir, MKV'yi GitHub'a yükler ve her parçayı doğrular; " +
                        "ancak ondan sonra kaynakları ve MKV'yi siler. GitHub sadece kataloğu (TMDB bilgileri) günceller. Fragmanlar 4K VP9 varsa 4K iner."
                    "phone" -> "Linkler sadece telefonundan iniyorsa (GitHub 403 alıyorsa) bunu seç. Telefon her dosyayı birden fazla bağlantıyla indirip GitHub'a aktarır; birleştirme ve yükleme yine GitHub'da yapılır. Telefonun interneti iki kez kullanılır (indirme + aktarma)."
                    else -> "GitHub kendi sunucusunda, hız sınırı olmadan indirir. Telefonun internetini kullanmaz."
                }, Modifier.padding(top = 4.dp))
                if (who == "pc") PcStatusLine(repo)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = ::send, enabled = !sending && text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
                ) {
                    if (sending) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                    else Text("GitHub'a Gönder", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            // İşler: aynı dizinin bölümleri tek kartta, her film/dizi ayrı kart. Bitmiş işler "Temizle" ile gizlenir (dosyalara dokunulmaz).
            val jobs = allJobs
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { AdminSection("İşler") }
                    if (jobs.any { j -> JobsTracker.viewOf(j)?.terminal == true || j.finalState != null || (j.phoneMode && j.localState == "failed") })
                        TextButton(onClick = { scope.launch { JobsTracker.hideFinished(repo) } }) { Text("🧹 Bitmişleri temizle", color = GColors.TextSecondary, fontSize = 13.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint(if (jobs.isEmpty()) "Süren ya da bekleyen iş yok." else "Aynı anda tek iş çalışır; bitenler 10 dk sonra listeden kendiliğinden kalkar.", Modifier.weight(1f))
                    TextButton(onClick = { confirmCancelAll = true }) { Text("🛑 Tüm işleri iptal et", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            val groups = JobGroups.group(jobs) { j -> JobsTracker.viewOf(j)?.status.let { st -> JobGroups.keyOf(j.title, st?.title, st?.kind).key } }
            items(groups, key = { it.first }) { (_, members) ->
                val handlers: (LinkJobRef) -> JobHandlers = { j -> jobHandlers(j) }
                if (members.size == 1) {
                    val j = members.first()
                    val v = JobsTracker.viewOf(j)
                    LinkJobCard(j, v, waitText = v?.let { waitText(j.tag, it, pc, now) }, open = expanded[j.tag] == true, h = handlers(j), onToggle = { expanded[j.tag] = expanded[j.tag] != true })
                } else {
                    JobGroupCard(members, pc, now, expanded, handlers)
                }
            }
        }
    }
    if (confirmCancelAll) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmCancelAll = false }, containerColor = Color(0xFF1F1F1F),
        title = { Text("🛑 Tüm işler iptal edilsin mi?", color = Color.White, fontWeight = FontWeight.Bold) },
        text = { Hint("Bilgisayar süren işi hemen durdurur, sıradakileri \"İptal edildi\" diye kapatır ve yeni iş beklemeye geçer. Telefondaki arka plan yakalaması da durur. Yüklenmiş dosyalara dokunulmaz.") },
        confirmButton = {
            TextButton(onClick = {
                confirmCancelAll = false
                message = "🛑 İptal ediliyor…"
                scope.launch {
                    runCatching { JobsTracker.cancelAll(context, repo) }.onSuccess { message = it }.onFailure { message = "İptal gönderilemedi: ${it.message}" }
                    runCatching { JobsTracker.refresh(context, repo, force = true) }
                }
            }) { Text("Hepsini iptal et", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { confirmCancelAll = false }) { Text("Vazgeç", color = GColors.TextSecondary) } },
    )
}

/** Bir işin düğmeleri (tek kartta da grup kartında da aynı). */
private class JobHandlers(
    val urgent: () -> Unit, val approve: () -> Unit, val cancel: () -> Unit, val remove: () -> Unit,
    val openLog: (String) -> Unit, val detailLog: () -> Unit,
)

/** Bekleyen işin satırı: başlama gecikmesi (geri sayım), gece modu, istek hakkı koruması, sıradaki yeri. */
internal fun waitText(tag: String, v: JobView, pc: PcStatus?, now: Long): String? {
    if (!v.waiting) return null
    if (v.urgent) return "⚡ Acil: bilgisayar sıradaki iş olarak alacak (aynı anda yine tek iş)"
    val delaySec = (pc?.startDelayMin ?: 0) * 60L
    if (delaySec > 0 && v.publishedAt > 0) {
        val left = v.publishedAt + delaySec - now
        if (left > 0) return "⏱ %d:%02d sonra başlar (başlama gecikmesi) — acilse ⚡ Hemen başlat".format(left / 60, left % 60)
    }
    if (pc != null && pc.night?.on == true && !pc.inWindow) return "🌙 Gece modu: ${pc.night.start}'te başlar — acilse ⚡ Hemen başlat"
    if (pc?.quotaGuard?.paused == true) return "🛡️ GitHub istek hakkı az: yenilenince kendiliğinden başlar"
    if (pc != null && !pc.online) return "🖥️ Bilgisayar kapalı; açılınca sırayla başlar"
    val ahead = JobsTracker.ahead(tag)
    return if (ahead > 0) "⏳ Sırada — önünde $ahead iş var" else "⏳ Sırada — bilgisayar 20 sn içinde alır"
}

/** Aynı dizinin bölümleri tek kartta: başlıkta dizi adı ve özet, altında her bölümün satırı (dokununca ayrıntı). */
@Composable
private fun JobGroupCard(
    members: List<LinkJobRef>, pc: PcStatus?, now: Long, expanded: MutableMap<String, Boolean>, handlers: (LinkJobRef) -> JobHandlers,
) {
    val parsed = members.map { j -> j to JobsTracker.viewOf(j).let { v -> JobGroups.keyOf(j.title, v?.status?.title, v?.status?.kind) } }
    val name = parsed.firstNotNullOfOrNull { (j, _) -> JobsTracker.viewOf(j)?.status?.title?.takeIf { it.isNotBlank() } } ?: parsed.first().second.name
    val views = members.map { JobsTracker.viewOf(it) }
    val doneN = views.count { it?.status?.state in setOf("done", "partial") }
    val runN = views.count { it?.running == true }
    val waitN = views.count { it?.waiting == true }
    val badN = views.count { it?.status?.state in setOf("error", "cancelled") || it?.gone == true }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(GColors.Card).padding(12.dp)) {
        Text("📺  $name", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull("${members.size} bölüm", runN.takeIf { it > 0 }?.let { "$it sürüyor" }, waitN.takeIf { it > 0 }?.let { "$it sırada" },
            doneN.takeIf { it > 0 }?.let { "$it bitti" }, badN.takeIf { it > 0 }?.let { "$it başarısız/iptal" }).joinToString(" · "),
            color = GColors.TextSecondary, fontSize = 12.sp)
        val overall = views.sumOf { v ->
            if (v?.status?.state in setOf("done", "partial")) 1.0
            else v?.status?.items?.takeIf { it.isNotEmpty() }?.let { its -> its.sumOf { it.progress } / its.size } ?: 0.0
        } / members.size
        ProgressLine(overall.toFloat(), Modifier.padding(vertical = 6.dp), height = 4.dp)
        parsed.sortedWith(compareBy({ it.second.season ?: 0 }, { it.second.episode ?: 0 }, { it.first.createdAt })).forEach { (j, p) ->
            val v = JobsTracker.viewOf(j)
            val ep = when {
                p.season != null && p.episode != null -> "S%02dE%02d".format(p.season, p.episode)
                p.episode != null -> "${p.episode}. bölüm"
                else -> v?.status?.items?.firstOrNull()?.label ?: j.title.take(30)
            }
            LinkJobCard(j, v, waitText = v?.let { waitText(j.tag, it, pc, now) }, open = expanded[j.tag] == true, h = handlers(j),
                onToggle = { expanded[j.tag] = expanded[j.tag] != true }, titleOverride = ep, embedded = true)
        }
    }
}

@Composable
private fun LinkJobCard(
    j: LinkJobRef, v: JobView?, waitText: String?, open: Boolean, h: JobHandlers, onToggle: () -> Unit,
    /** Grup kartının içinde: başlık yerine bölüm kodu, dış kutu yok */
    titleOverride: String? = null, embedded: Boolean = false,
) {
    if (j.phoneMode && j.localState != "published") {
        PhoneJobCard(j, h.cancel, h.remove)
        return
    }
    val st = v?.status
    val head = v?.head.orEmpty()
    val stale = st != null && st.state == "running" && st.updated > 0 && System.currentTimeMillis() / 1000 - st.updated > 600
    val (icon, color) = when {
        v == null -> "…" to GColors.TextSecondary
        v.gone -> "❔" to GColors.TextSecondary
        st == null && v.waiting -> "⏳" to GColors.TextSecondary
        st == null -> "⚙️" to Color.White
        stale -> "⚠️" to Color(0xFFE8A33D)
        st.state == "done" -> "✅" to GColors.Match
        st.state == "partial" -> "⚠️" to Color(0xFFE8A33D)
        st.state == "error" -> "❌" to Color(0xFFFF6B6B)
        st.state == "cancelled" -> "🛑" to GColors.TextSecondary
        else -> "⚙️" to Color.White
    }
    val overall = st?.items?.takeIf { it.isNotEmpty() }?.let { items -> items.sumOf { it.progress } / items.size } ?: 0.0
    val box = if (embedded) Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF262626)).clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 6.dp)
              else Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(GColors.Card).clickable(onClick = onToggle).padding(12.dp)
    Column(box) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$icon  ${titleOverride ?: st?.title?.ifBlank { null } ?: j.finalTitle ?: j.title}", color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = if (embedded) 13.sp else 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (embedded && st?.state == "running" && st.items.isNotEmpty()) Text("%${(overall * 100).toInt()}", color = GColors.TextSecondary, fontSize = 12.sp)
            if ((st?.state == "running" && !stale) || v?.waiting == true || v?.running == true) TextButton(onClick = h.cancel) { Text("İptal", color = GColors.TextSecondary, fontSize = 13.sp) }
            else TextButton(onClick = h.remove) { Text("Kaldır", color = GColors.TextSecondary, fontSize = 13.sp) }
        }
        st?.approval?.takeIf { st.state == "running" }?.let { a -> ApprovalBox(a, h.approve, h.cancel) }
        Text(
            when {
                v == null -> "Durumu okunuyor…"
                v.gone -> "Bulunamadı (kaldırılmış ya da çok eski). Listeden temizleyebilirsin."
                stale -> "Bilgisayardan 10 dakikadır haber yok; iş durmuş olabilir. Canlı kayda bak."
                st == null -> waitText ?: head.ifBlank { "Başlıyor" }
                else -> st.stage
            }, color = color, fontSize = if (embedded) 12.sp else 13.sp,
        )
        // Bekleyen iş (başlama gecikmesi / gece modu): acilse hemen başlat
        if (v?.waiting == true && !v.urgent) TextButton(onClick = h.urgent) {
            Text("⚡ Hemen başlat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        if (embedded && !open) return@Column
        st?.let(::durationLine)?.let { Text(it, color = GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
        if (st != null && st.items.isNotEmpty()) ProgressLine(overall.toFloat(), Modifier.padding(vertical = 6.dp), height = 4.dp)
        st?.items?.forEach { it ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                Text(it.text, color = if (it.state == "error") Color(0xFFFF6B6B) else GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%${(it.progress * 100).toInt()}", color = GColors.TextSecondary, fontSize = 12.sp)
            }
            if (open) {
                if (it.error.isNotBlank()) Text(it.error, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                it.files.forEach { f -> FileRow(f) }
            }
        }
        st?.trailer?.let { t ->
            Text("🎞️ Fragman: ${t.text}", color = if (t.state == "error") Color(0xFFE8A33D) else GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (open) t.files.forEach { FileRow(it) }
        }
        if (open && st != null) {
            st.warnings.forEach { w -> Text("⚠ $w", color = Color(0xFFE8A33D), fontSize = 11.sp) }
            if (st.log.isNotEmpty()) {
                Text(st.log.takeLast(8).joinToString("\n"), color = GColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
            }
            if (st.worker != "github") Text("🖥️ ${st.worker} üzerinde çalışıyor", color = GColors.TextSecondary, fontSize = 12.sp)
            if (st.hasLog) TextButton(onClick = h.detailLog) { Text("📄 Ayrıntılı kayıt (birleştirme, senkron, yükleme)", color = Color.White, fontSize = 13.sp) }
            if (st.runUrl.isNotBlank()) TextButton(onClick = { h.openLog(st.runUrl) }) { Text("GitHub'da canlı kaydı aç", color = Color.White, fontSize = 13.sp) }
        }
        if (!open && st != null && st.log.isNotEmpty() && !embedded) Text("Ayrıntılar için dokun", color = GColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FileRow(f: LinkFile) {
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp)) {
        Row {
            Text(f.label, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                when (f.state) {
                    "done" -> "✓ ${human(f.total)}"
                    "error" -> "Hata"
                    else -> "${human(f.done)}${if (f.total > 0) " / ${human(f.total)}" else ""} · ${human(f.speed)}/sn"
                }, color = if (f.state == "error") Color(0xFFFF6B6B) else GColors.TextSecondary, fontSize = 11.sp,
            )
        }
        if (f.state != "done" && f.state != "error") ProgressLine(if (f.total > 0) f.done.toFloat() / f.total else 0f, Modifier.padding(top = 2.dp), height = 2.dp)
        if (f.error.isNotBlank()) Text(f.error, color = Color(0xFFFF6B6B), fontSize = 10.sp)
    }
}

@Composable
private fun PhoneJobCard(j: LinkJobRef, onCancel: () -> Unit, onRemove: () -> Unit) {
    val failed = j.localState == "failed"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(GColors.Card).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (failed) "❌  " else "📱  ") + j.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!failed) TextButton(onClick = onCancel) { Text("Durdur", color = GColors.TextSecondary, fontSize = 13.sp) }
            TextButton(onClick = onRemove) { Text("Kaldır", color = GColors.TextSecondary, fontSize = 13.sp) }
        }
        if (failed) {
            Text("Telefondaki indirme başarısız: ${j.localMessage ?: ""}", color = Color(0xFFFF6B6B), fontSize = 13.sp)
        } else {
            val frac = if (j.localTotal > 0) j.localDone.toFloat() / j.localTotal else 0f
            Text("Telefon indiriyor ve GitHub'a aktarıyor", color = Color.White, fontSize = 13.sp)
            ProgressLine(frac, Modifier.padding(vertical = 6.dp), height = 4.dp)
            Text("%${(frac * 100).toInt()} · ${human(j.localDone)}${if (j.localTotal > 0) " / ${human(j.localTotal)}" else ""} · ${human(j.localSpeed)}/sn",
                color = GColors.TextSecondary, fontSize = 12.sp)
            j.localMessage?.let { Text(it, color = Color(0xFFE8A33D), fontSize = 12.sp) }
            Hint("Bitince GitHub birleştirip yükleyecek; durum burada görünecek.", Modifier.padding(top = 4.dp))
        }
    }
}


/** Ayrıntılı iş kaydı (log.txt): kaydırılabilir, eş aralıklı yazı. */
@Composable
private fun DetailLogDialog(text: String, onClose: () -> Unit) {
    val bars = hostBars()
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0E0E0E)).padding(bars).imePadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📄 Ayrıntılı kayıt", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Kapat", color = Color.White) }
            }
            val scroll = androidx.compose.foundation.rememberScrollState()
            val hscroll = androidx.compose.foundation.rememberScrollState()
            Text(
                text, color = Color(0xFFD0D0D0), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp).verticalScroll(scroll).horizontalScroll(hscroll),
            )
        }
    }
}


/**
 * 1DM'de Experimental → "Show download link": Video ve Audio linki ayrı gelir (1DM ikisini kendisi birleştirir).
 * Burada ikisi tek satır olur: "video tr: <video> | ses: <audio>" → bilgisayar görüntü + o dilin sesini tek parça video yapar.
 */
@Composable
private fun ExperimentalDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var lang by remember { mutableStateOf("tr") }
    var video by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1F1F1F),
        title = { Text("1DM Experimental", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Hint("1DM'de Experimental'a bas → Show download link. Video ve Audio linkini kopyalayıp buraya yapıştır; tek parça video olur.")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("tr" to "Türkçe", "en" to "İngilizce / Orijinal").forEach { (c, l) ->
                        Text(l, color = if (lang == c) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (lang == c) Color.White else Color(0xFF333333))
                                .clickable { lang = c }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(video, { video = it.trim(); err = null }, label = { Text("Video linki") }, singleLine = true, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(audio, { audio = it.trim(); err = null }, label = { Text("Audio linki") }, singleLine = true, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                when {
                    !video.startsWith("http") -> err = "Video linkini yapıştır."
                    !audio.startsWith("http") -> err = "Audio linkini yapıştır (yoksa video sessiz kalır)."
                    video == audio -> err = "Video ve Audio linki aynı olamaz."
                    else -> onAdd(lang, video, audio)
                }
            }) { Text("Ekle", color = Color.White, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Vazgeç", color = GColors.TextSecondary) } },
    )
}


/** Bilgisayar indirmeden önce soruyor: toplam kaç GB, işlerken en fazla ne kadar alan, bilgisayarda ne kadar boş. */
@Composable
private fun ApprovalBox(a: Approval, onApprove: () -> Unit, onCancel: () -> Unit) {
    val short = a.free < a.peak
    var sent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF26231A)).padding(12.dp)) {
        Text("⏸ Onay bekleniyor", color = Color(0xFFE8A33D), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("📦 Toplam ~${human(a.total)} inecek (${a.items.size} öğe, sırayla: biri bitmeden öbürü başlamaz)", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text("💾 İşlerken en fazla ~${human(a.peak)} boş alan gerekir · bilgisayarda ${human(a.free)} boş",
            color = if (short) Color(0xFFFF6B6B) else GColors.TextSecondary, fontSize = 13.sp)
        if (short) Text("⚠ Boş alan yetmeyebilir: ${human(a.peak - a.free)} daha gerekli.", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (a.unknown > 0) Hint("${a.unknown} öğenin boyutu bilinemedi (toplama eklenmedi).")
        if (a.items.size > 1) a.items.take(30).forEach { Hint("• ${it.label}: ${if (it.bytes > 0) "~" + human(it.bytes) else "?"}") }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sent = true; onApprove() }, enabled = !sent, shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text(if (sent) "Onaylandı" else "✓ Devam et", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onCancel) { Text("İptal", color = GColors.TextSecondary) }
        }
    }
}
