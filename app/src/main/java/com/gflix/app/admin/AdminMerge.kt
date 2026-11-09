package com.gflix.app.admin

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import com.gflix.app.appContainer
import java.util.UUID

/** TMDB adresinden tür ve numara: https://www.themoviedb.org/tv/1001-ornek-dizi -> ("tv", 1396) */
fun parseTmdbUrl(text: String): Pair<String, Int>? {
    val m = Regex("(movie|tv)/(\\d+)", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
    return m.groupValues[1].lowercase() to m.groupValues[2].toInt()
}

class MergeFile(val uri: String, val name: String, role: String, lang: String) {
    var role by mutableStateOf(role)          // video | audio | subtitle
    var lang by mutableStateOf(lang)
    var forced by mutableStateOf(false)
    /** Elle ince ayar (ms; + = ses daha geç). Otomatik senkron düzeltmesinin üstüne eklenir. */
    var delayMs by mutableStateOf(0)
}

class MergeItem(season: Int?, episode: Int?) {
    var season by mutableStateOf(season)
    var episode by mutableStateOf(episode)
    /** TMDB bölüm listesinden geldiyse: bölüm adı ve yayın tarihi; dosyası eklenmezse gönderilmez */
    var name by mutableStateOf<String?>(null)
    var airAt: Long? = null
    var listed = false
    val files: SnapshotStateList<MergeFile> = mutableStateListOf()
    val label: String get() = if (season == null) "Film" else "S%02dE%02d".format(season ?: 0, episode ?: 0)
}

private val ROLE_NAMES = mapOf("video" to "Video", "audio" to "Ses", "subtitle" to "Altyazı")

private val TR_SEASON_EP = listOf(
    Regex("(\\d{1,2})\\s*\\.?\\s*sezon[\\s._-]*(\\d{1,3})\\s*\\.?\\s*b[öo]l[üu]m", RegexOption.IGNORE_CASE),
    Regex("sezon[\\s._-]*(\\d{1,2})[\\s._-]*b[öo]l[üu]m[\\s._-]*(\\d{1,3})", RegexOption.IGNORE_CASE),
    Regex("season[\\s._-]*(\\d{1,2})[\\s._-]*(?:episode|ep)[\\s._-]*(\\d{1,3})", RegexOption.IGNORE_CASE),
    Regex("(?<![a-z])s(\\d{1,2})[\\s._-]*b(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE),
)
private val EP_ONLY = listOf(
    Regex("(\\d{1,3})\\s*\\.\\s*b[öo]l[üu]m", RegexOption.IGNORE_CASE),
    Regex("b[öo]l[üu]m[\\s._-]*(\\d{1,3})", RegexOption.IGNORE_CASE),
    Regex("(?:episode|ep)[\\s._-]*(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE),
    Regex("(?<![a-z0-9])e(\\d{2,3})(?!\\d)", RegexOption.IGNORE_CASE),
)

/**
 * Bölüm kodu: S01E02 · 1x02 · "1. Sezon 2. Bölüm" · "Sezon 1 Bölüm 2" · "Season 1 Episode 2";
 * sadece bölüm yazıyorsa ("5. Bölüm", "E05") [defaultSeason] kullanılır.
 */
internal fun episodeCode(name: String, defaultSeason: Int? = null): Pair<Int, Int>? {
    val p = FileNames.parse(name)
    if (p.season != null && p.episode != null) return p.season to p.episode
    val stem = name.substringBeforeLast('.')
    TR_SEASON_EP.firstNotNullOfOrNull { it.find(stem) }?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    if (defaultSeason != null) EP_ONLY.firstNotNullOfOrNull { it.find(stem) }?.let { return defaultSeason to it.groupValues[1].toInt() }
    return null
}

/**
 * Ayrı video/ses/altyazı dosyalarından tarif üretir; content:// adresleri aktarım sırasında asset:… olur.
 * [fast]: "mod: hızlı" — birleştirilmeden, olduğu gibi oynatılır. [videoAudio]: videonun kendi seslerinin dilleri.
 */
fun buildMergeRecipe(
    tmdbUrl: String, title: String, trailer: String, items: List<MergeItem>,
    fast: Boolean = false, videoAudio: Map<MergeItem, List<String>> = emptyMap(),
): String {
    val sb = StringBuilder()
    if (fast) sb.append("mod: hızlı\n")
    sb.append("tmdb: ").append(tmdbUrl.trim()).append('\n')
    if (title.isNotBlank()) sb.append("ad: ").append(title.trim()).append('\n')
    if (trailer.isNotBlank()) sb.append("fragman: ").append(trailer.trim()).append('\n')
    items.forEach { it ->
        if (it.season != null) sb.append("bölüm ").append(it.label).append(":\n")
        videoAudio[it]?.takeIf { l -> l.isNotEmpty() && l.any { x -> x != "und" } }?.let { l -> sb.append("video sesleri: ").append(l.joinToString(" ")).append('\n') }
        val firstTr = it.files.firstOrNull { f -> f.role == "audio" && f.lang == "tr" }
        it.files.sortedBy { f -> listOf("video", "audio", "subtitle").indexOf(f.role) }.forEach { f ->
            val key = when (f.role) {
                "video" -> if (f.lang != "und" && f.lang.isNotBlank()) "video ${f.lang}" else "video"
                "audio" -> "ses ${f.lang}" + if (f === firstTr) " varsayılan" else ""
                else -> "altyazı ${f.lang}" + if (f.forced) " zorunlu" else ""
            }
            sb.append(key).append(": ").append(f.uri)
            if (f.role == "audio" && f.delayMs != 0) sb.append(" | gecikme: ").append(f.delayMs)
            sb.append('\n')
        }
    }
    return sb.toString()
}

/** Hızlı yüklemeden önce: bu telefon dosyaları çözebiliyor mu, videonun kendi sesi var mı? */
object FastCheck {
    data class Video(val audioLangs: List<String>, val warnings: List<String>)

    private fun decodable(f: android.media.MediaFormat): Boolean = runCatching {
        val copy = android.media.MediaFormat()
        copy.setString(android.media.MediaFormat.KEY_MIME, f.getString(android.media.MediaFormat.KEY_MIME))
        listOf(android.media.MediaFormat.KEY_WIDTH, android.media.MediaFormat.KEY_HEIGHT, android.media.MediaFormat.KEY_SAMPLE_RATE,
            android.media.MediaFormat.KEY_CHANNEL_COUNT, android.media.MediaFormat.KEY_PROFILE).forEach { k ->
            if (f.containsKey(k)) runCatching { copy.setInteger(k, f.getInteger(k)) }
        }
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(copy) != null
    }.getOrDefault(true)

    private fun tracks(context: android.content.Context, uri: Uri): List<android.media.MediaFormat> {
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            ex.release()
        }
    }

    private fun mime(f: android.media.MediaFormat) = f.getString(android.media.MediaFormat.KEY_MIME).orEmpty()

    fun video(context: android.content.Context, file: MergeFile): Video {
        val ts = tracks(context, Uri.parse(file.uri))
        if (ts.isEmpty()) return Video(emptyList(), listOf("${file.name}: dosya okunamadı ya da tanınmadı."))
        val warnings = mutableListOf<String>()
        ts.filter { mime(it).startsWith("video/") }.forEach { f ->
            if (!decodable(f)) warnings += "${file.name}: bu telefon videoyu (${mime(f).removePrefix("video/")}) oynatamayabilir."
        }
        val audio = ts.filter { mime(it).startsWith("audio/") }
        audio.forEach { f -> if (!decodable(f)) warnings += "${file.name}: içindeki ses (${mime(f).removePrefix("audio/")}) bu telefonda çalmayabilir." }
        val langs = audio.map { f -> Langs.norm(if (f.containsKey(android.media.MediaFormat.KEY_LANGUAGE)) f.getString(android.media.MediaFormat.KEY_LANGUAGE) else null) }
        return Video(langs, warnings)
    }

    fun audio(context: android.content.Context, file: MergeFile): String? {
        val ts = tracks(context, Uri.parse(file.uri)).filter { mime(it).startsWith("audio/") }
        if (ts.isEmpty()) return "${file.name}: içinde ses bulunamadı."
        return ts.firstOrNull { !decodable(it) }?.let { "${file.name}: ses biçimi (${mime(it).removePrefix("audio/")}) bu telefonda çalmayabilir. AAC (.m4a) önerilir." }
    }
}

@Composable
fun AdminMergeScreen(onBack: () -> Unit, onSent: () -> Unit, presetItem: String? = null) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    var tmdbUrl by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var trailerLink by remember { mutableStateOf("") }
    var trailerFile by remember { mutableStateOf<MergeFile?>(null) }
    val items = remember { mutableStateListOf(MergeItem(null, null)) }
    var message by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    // Seçici hedefi: (öğe ya da toplu=null, rol, dil)
    var target by remember { mutableStateOf<Triple<MergeItem?, String, String>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    // Hızlı (birleştirmeden) mod seslerin kaymasına yol açtı; artık hep MKVToolNix ile birleştirilir
    val fast = false
    var toPc by remember { mutableStateOf(true) }
    var trailerEn by remember { mutableStateOf("") }
    var warnedFor by remember { mutableStateOf<String?>(null) }
    // Film kuyruğu: "Sıraya ekle" ile her film ayrı iş olarak gönderilir, telefon/bilgisayar sırayla tek tek yükler
    var releaseAt by remember { mutableStateOf<Long?>(null) }
    val queued = remember { mutableStateListOf<String>() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val tmdb = parseTmdbUrl(tmdbUrl)
    val series = tmdb?.first == "tv"
    androidx.compose.runtime.LaunchedEffect(tmdb?.first) {
        if (series && items.size == 1 && items[0].season == null) { items[0].season = 1; items[0].episode = 1 }
        if (tmdb?.first == "movie" && items.any { it.season != null }) {
            val keep = items.firstOrNull()?.files?.toList().orEmpty()
            items.clear(); items += MergeItem(null, null).also { it.files.addAll(keep) }
        }
    }

    fun take(uri: Uri): String {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return runCatching { MediaProbe.fileInfo(context, uri).first }.getOrDefault("dosya")
    }

    fun guessLang(item: MergeItem, role: String, name: String, preset: String): String {
        if (preset.isNotEmpty()) return preset
        val g = Langs.guessFromName(name)
        if (g != "und") return g
        return if (item.files.any { it.role == role && it.lang == "tr" }) "en" else "tr"
    }

    /** Sadece bölüm no yazan dosyalar için sezon: listede tek sezon varsa o, yoksa en küçük eksik sezon */
    fun defaultSeason(): Int? = items.mapNotNull { it.season }.distinct().minOrNull()

    fun addPicked(uris: List<Uri>, item: MergeItem?, role: String, preset: String, codeOf: (Uri, String) -> Pair<Int, Int>? = { _, n -> episodeCode(n, defaultSeason()) }) {
        if (role == "trailer") {
            uris.firstOrNull()?.let { trailerFile = MergeFile(it.toString(), take(it), "video", "und"); trailerLink = "" }
            return
        }
        if (item != null) {
            uris.forEach { u ->
                val name = take(u)
                val vlang = if (role == "video") preset.ifEmpty { "und" } else ""
                if (role == "video") item.files.removeAll { it.role == "video" && (it.lang == vlang || it.lang == "und" || vlang == "und") }
                item.files += MergeFile(u.toString(), name, role, if (role == "video") vlang else guessLang(item, role, name, preset))
            }
        } else {
            // Toplu seçim (dizi): dosya adındaki bölüm koduna göre eşleştir, yoksa sırayla
            val sorted = uris.map { it to take(it) }.sortedBy { it.second.lowercase() }
            sorted.forEachIndexed { i, (u, name) ->
                val code = codeOf(u, name)
                var it = code?.let { c -> items.firstOrNull { x -> x.season == c.first && x.episode == c.second } }
                if (it == null && role == "video") {
                    it = if (code != null) MergeItem(code.first, code.second).also { n -> items += n }
                    else items.getOrNull(i)?.takeIf { x -> x.files.none { f -> f.role == "video" && f.lang == preset.ifEmpty { "und" } } } ?: run {
                        val last = items.filter { x -> x.season != null }.maxByOrNull { x -> (x.season ?: 0) * 1000 + (x.episode ?: 0) }
                        MergeItem(last?.season ?: 1, (last?.episode ?: 0) + 1).also { n -> items += n }
                    }
                }
                if (it == null) it = items.getOrNull(i)
                if (it == null) { message = "\"$name\" hangi bölüme ait anlaşılamadı (adında S01E02 gibi bir kod olmalı)."; return@forEachIndexed }
                val vl = if (role == "video") preset.ifEmpty { "und" } else ""
                if (role == "video") it.files.removeAll { f -> f.role == "video" && (f.lang == vl || f.lang == "und" || vl == "und") }
                it.files += MergeFile(u.toString(), name, role, if (role == "video") vl else guessLang(it, role, name, preset))
            }
            // Boş kalan ilk bölümü temizle, sırala
            items.removeAll { x -> x.files.isEmpty() && !x.listed && items.size > 1 }
            val s = items.sortedBy { x -> (x.season ?: 0) * 1000 + (x.episode ?: 0) }
            items.clear(); items.addAll(s)
        }
        version++
    }
    val browser = rememberFileBrowser()
    var tmdbApi by remember { mutableStateOf<Tmdb?>(null) }
    var tmdbSearch by remember { mutableStateOf(false) }
    var tmdbReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { tmdbApi = repo.tmdb(); tmdbReady = true }
    val rawCatalog by context.appContainer.catalogRepository.catalog.collectAsState()
    var presetDone by remember { mutableStateOf(false) }
    var listPicker by remember { mutableStateOf<String?>(null) }   // "series" | "soon"
    var shortcutInfo by remember { mutableStateOf<String?>(null) }
    var seasonFilter by remember { mutableStateOf<Int?>(null) }

    /**
     * TMDB bölüm listesi: dizinin tüm sezon/bölümleri çekilir, yüklü olanlar atlanır, eksikler satır satır gelir.
     * Daha önce dosya eklenmiş satırlar korunur. Dosyası eklenmeyen satırlar gönderilmez.
     */
    suspend fun loadEpisodes(tid: Int, name: String?): Boolean {
        val api = tmdbApi ?: return false
        shortcutInfo = "📋 TMDB'den bölüm listesi alınıyor…"
        val eps = runCatching { api.episodes(tid) }.getOrElse { shortcutInfo = "Bölüm listesi alınamadı: ${it.message}"; return false }
        if (eps.isEmpty()) { shortcutInfo = "TMDB'de bu dizinin bölüm listesi yok; bölümleri elle ekle."; return false }
        val lib = rawCatalog?.titles?.firstOrNull { it.tmdbId == tid && it.isSeries }
        val uploaded = lib?.seasons.orEmpty().flatMap { s -> s.episodes.filter { it.stream != null }.map { s.number to it.number } }.toSet()
        val keep = items.filter { it.files.isNotEmpty() && it.season != null }.associateBy { it.season!! to it.episode!! }
        val missing = eps.filter { (it.season to it.episode) !in uploaded }
        val now = System.currentTimeMillis() / 1000
        items.clear()
        missing.forEach { e ->
            items += (keep[e.season to e.episode] ?: MergeItem(e.season, e.episode)).apply { this.name = e.name.ifBlank { null }; airAt = e.airAt; listed = true }
        }
        keep.filterKeys { k -> missing.none { (it.season to it.episode) == k } }.values.forEach { items += it }
        if (items.isEmpty()) items += MergeItem((eps.maxOf { it.season }), (eps.filter { it.season == eps.maxOf { x -> x.season } }.maxOf { it.episode }) + 1)
        val sorted = items.sortedBy { (it.season ?: 0) * 1000 + (it.episode ?: 0) }
        items.clear(); items.addAll(sorted)
        val future = missing.count { (it.airAt ?: 0) > now }
        shortcutInfo = "📋 ${name ?: "Dizi"} — TMDB'de ${eps.size} bölüm · ${uploaded.size} yüklü · ${missing.size} eksik" +
            (if (future > 0) " ($future tanesi henüz yayınlanmadı)" else "") +
            if (missing.isEmpty()) "\nTüm bölümler yüklü. Yeni bölüm TMDB'ye eklenince burada çıkar." else "\nEksikler aşağıda. \"📂 Hepsini seç\" ile dosyaları toplu seç; bölümlerine kendiliğinden oturur. Dosyası olmayan satırlar gönderilmez."
        version++
        return true
    }

    /** Yapımı seçince: TMDB linki, ad ve (dizide) sıradaki bölüm hazır gelir; dosyalar sonra seçilir. */
    fun useTitle(t: com.gflix.app.data.Title) {
        val id = t.tmdbId
        if (!t.isSeries) {
            items.clear(); items += MergeItem(null, null)
            if (id != null) tmdbUrl = "https://www.themoviedb.org/movie/$id" else title = t.title
            shortcutInfo = "🎬 ${t.title} — dosyaları seç" + if (t.releaseAt != null) " (yayın tarihine kadar gizli kalır)." else "."
            return
        }
        shortcutInfo = "Bölümler kontrol ediliyor…"
        scope.launch {
            if (id != null && tmdbApi != null) {
                tmdbUrl = "https://www.themoviedb.org/tv/$id"
                if (loadEpisodes(id, t.title)) return@launch
            }
            val uploaded = t.seasons.flatMap { s -> s.episodes.filter { it.stream != null }.map { s.number to it.number } }
            val seasons = id?.let { tid -> tmdbApi?.let { runCatching { it.seasons(tid) }.getOrNull() } }
            val next = NextEpisode.next(uploaded, seasons)
            items.clear(); items += MergeItem(next.first, next.second)
            if (id != null) tmdbUrl = "https://www.themoviedb.org/tv/$id" else title = t.title
            shortcutInfo = "📺 ${t.title} — " + NextEpisode.summary(uploaded, seasons) +
                "\n→ Sıradaki: %d. sezon %d. bölüm (S%02dE%02d). Dosyalarını seç; birden fazla bölüm seçersen adlarındaki koda göre ayrılır.".format(next.first, next.second, next.first, next.second) +
                if (seasons == null) "\n(TMDB anahtarı yoksa sezon sonu bilinemez; gerekirse bölüm numarasını düzelt.)" else ""
            version++
        }
    }

    // Yakında → "+ Medya ekle → Telefondan dosya": yapım hazır seçili gelir (filmde TR/EN video + altyazı, dizide sıradaki bölüm)
    androidx.compose.runtime.LaunchedEffect(presetItem, rawCatalog, tmdbReady) {
        if (presetItem == null || presetDone || !tmdbReady) return@LaunchedEffect
        rawCatalog?.titles?.firstOrNull { it.id == presetItem }?.let { presetDone = true; useTitle(com.gflix.app.data.Presentation.localize(it, null)) }
    }

    /**
     * Karışık seçim (her şey birden): türü uzantıdan; bölümü addan (S01E02, "1. Sezon 2. Bölüm" …), adda yoksa videonun içindeki
     * başlıktan; video dilini addan, yoksa içindeki ses kanalından; dizi seçilmemişse ortak addan TMDB'de bulur ve bölüm listesini getirir.
     */
    fun addMixed(uris: List<Uri>) {
        scope.launch {
            message = null
            shortcutInfo = "🔍 ${uris.size} dosya inceleniyor…"
            data class Picked(val uri: Uri, val name: String, val kind: FileKind, val code: Pair<Int, Int>?, val lang: String)
            val picked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.map { u ->
                    val n = take(u)
                    val kind = FileKind.of(n)
                    var code = episodeCode(n)
                    var lang = Langs.guessFromName(n)
                    if (kind == FileKind.Video && (code == null || lang == "und")) {
                        if (code == null) code = MediaProbe.embeddedTitle(context, u)?.let { episodeCode(it) }
                        if (lang == "und") lang = runCatching { FastCheck.video(context, MergeFile(u.toString(), n, "video", "und")).audioLangs }
                            .getOrDefault(emptyList()).filter { it != "und" }.distinct().singleOrNull() ?: "und"
                    }
                    Picked(u, n, kind, code, lang)
                }
            }
            // Dizi seçilmediyse: dosya adlarındaki ortak isimle TMDB'de ara
            val coded = picked.filter { it.code != null }
            if (tmdb == null && coded.isNotEmpty() && tmdbApi != null) {
                val guess = coded.map { FileNames.parse(it.name).title }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                val r = guess?.let { g -> runCatching { tmdbApi!!.search("tv", g, null) }.getOrNull()?.firstOrNull() }
                if (r != null) {
                    tmdbUrl = "https://www.themoviedb.org/tv/${r.id}"
                    loadEpisodes(r.id, r.title)
                    shortcutInfo = "🤖 Dosya adlarından bulundu: ${r.title}${r.year?.let { " ($it)" } ?: ""} — yanlışsa 🔍 ile düzelt.\n" + shortcutInfo.orEmpty()
                }
            } else if (items.none { it.listed } && series && tmdb != null) loadEpisodes(tmdb.second, title.ifBlank { null })
            val before = items.sumOf { it.files.size }
            val codes = picked.associate { it.uri to it.code }
            val ds = defaultSeason()
            val movie = items.size == 1 && items[0].season == null
            for (kind in listOf(FileKind.Video, FileKind.Audio, FileKind.Subtitle)) {
                val role = when (kind) { FileKind.Video -> "video"; FileKind.Audio -> "audio"; else -> "subtitle" }
                picked.filter { it.kind == kind }.groupBy { it.lang.takeIf { l -> l != "und" }.orEmpty() }
                    .forEach { (lang, list) ->
                        addPicked(list.map { it.uri }, if (movie) items[0] else null, role, lang) { u, n -> codes[u] ?: episodeCode(n, ds) }
                    }
            }
            val placed = items.sumOf { it.files.size } - before
            val skipped = picked.filter { it.kind == FileKind.Other }
            val lost = picked.size - skipped.size - placed
            shortcutInfo = (shortcutInfo?.takeIf { it.startsWith("🤖") || it.startsWith("📋") }?.let { "$it\n" } ?: "") +
                "✓ $placed dosya yerleşti" + (if (lost > 0) " · $lost dosya eşleşmedi (bölüm kartından elle ekle)" else "") +
                if (skipped.isNotEmpty()) " · tanınmayan: " + skipped.joinToString(", ") { it.name } else ""
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val (item, role, preset) = target ?: return@rememberLauncherForActivityResult
        if (role == "auto") addMixed(uris) else addPicked(uris, item, role, preset)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun pick(item: MergeItem?, role: String, lang: String = "") {
        target = Triple(item, role, lang)
        val kinds = when (role) {
            "video", "trailer" -> setOf(FileKind.Video)
            "audio" -> setOf(FileKind.Audio, FileKind.Video)
            "subtitle" -> setOf(FileKind.Subtitle)
            else -> setOf(FileKind.Video, FileKind.Subtitle, FileKind.Audio)
        }
        val title = when (role) { "video" -> "Video seç"; "trailer" -> "Fragman seç"; "audio" -> "Ses dosyası seç"; "subtitle" -> "Altyazı seç"; else -> "Dosyaları seç" }
        browser.open(title, kinds, multi = role != "trailer") { uris -> if (role == "auto") addMixed(uris) else addPicked(uris, item, role, lang) }
    }

    /** Android'in kendi seçicisi (yedek) */
    fun pickSystem(item: MergeItem?, role: String, lang: String = "") {
        target = Triple(item, role, lang)
        picker.launch(arrayOf("*/*"))
    }

    /** Gönderilecekler: TMDB listesinden gelip dosyası eklenmeyen bölümler atlanır */
    fun used(): List<MergeItem> = items.filter { !it.listed || it.files.isNotEmpty() }

    fun validate(): String? {
        if (tmdb == null) return "TMDB linkini yapıştır (ör. https://www.themoviedb.org/movie/2002)."
        if (used().isEmpty()) return "Hiçbir bölüme dosya eklenmedi."
        used().forEach { it ->
            val vids = it.files.filter { f -> f.role == "video" }
            if (vids.isEmpty()) return "${it.label}: bir video dosyası seç."
            if (vids.size > 1) {
                if (vids.any { f -> f.lang == "und" }) return "${it.label}: iki video varsa her birinin dilini seç (Türkçe / İngilizce)."
                if (vids.map { f -> f.lang }.toSet().size != vids.size) return "${it.label}: aynı dilde iki video var."
            }
            if (it.season != null && ((it.season ?: 0) <= 0 || (it.episode ?: 0) <= 0)) return "${it.label}: sezon/bölüm numarası gerekli."
        }
        if (used().map { it.label }.toSet().size != used().size) return "Aynı bölüm iki kez var."
        return null
    }

    /** [next]: gönder, formu boşalt ve sıradaki filme geç (ekrandan çıkmadan). */
    fun send(next: Boolean = false) {
        if (!next && queued.isNotEmpty() && tmdb == null && items.all { it.files.isEmpty() }) { onSent(); return }
        validate()?.let { message = it; return }
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        sending = true
        scope.launch {
            // Hızlı modda dosyalar olduğu gibi oynatılacağı için önce kontrol et
            val videoAudio = mutableMapOf<MergeItem, List<String>>()
            if (fast) {
                val problems = mutableListOf<String>()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    items.forEach { it ->
                        val v = FastCheck.video(context, it.files.first { f -> f.role == "video" })
                        videoAudio[it] = v.audioLangs
                        problems += v.warnings
                        it.files.filter { f -> f.role == "audio" }.forEach { f -> FastCheck.audio(context, f)?.let(problems::add) }
                        if (v.audioLangs.isEmpty() && it.files.none { f -> f.role == "audio" }) problems += "${it.label}: videoda ses yok ve ses dosyası seçilmedi, sessiz kalır."
                    }
                }
                val key = problems.joinToString("\n")
                if (problems.isNotEmpty() && warnedFor != key) {
                    warnedFor = key
                    message = "⚠ " + problems.take(6).joinToString("\n⚠ ") + "\n\nYine de göndermek için tekrar bas."
                    sending = false
                    return@launch
                }
            }
            runCatching {
                val trailer = trailerFile?.uri ?: trailerLink
                var recipe = buildMergeRecipe(tmdbUrl, title, "", used(), fast, videoAudio)
                // Fragmanlar dile göre: TR (link ya da dosya) ve EN (link)
                val extra = buildString {
                    if (trailer.isNotBlank()) append("fragman tr: ").append(trailer.trim()).append('\n')
                    if (trailerEn.isNotBlank()) append("fragman en: ").append(trailerEn.trim()).append('\n')
                    if (!series) releaseAt?.let { append("yayın: ").append(it).append('\n') }
                }
                recipe = recipe.replaceFirst("\n", "\n$extra")
                val tag = if (toPc) PcJobs.newTag() else "linkjob-" + UUID.randomUUID().toString().take(12)
                val label = title.ifBlank { "${if (series) "Dizi" else "Film"} #${tmdb!!.second}" } +
                    if (series) " (${used().size} bölüm)" else ""
                val rel = repo.gh().createDraftRelease(tag, "📱 $label", "📱 Telefondan aktarılıyor…")
                repo.store.update {
                    it.copy(linkJobs = listOf(LinkJobRef(tag, rel.id, label, System.currentTimeMillis(), phoneMode = true, recipe = recipe, localState = "downloading")) + it.linkJobs)
                }
                LinkPhoneWorker.enqueue(context, tag)
                label
            }.onSuccess { label ->
                sending = false
                if (!next) { onSent(); return@onSuccess }
                queued += label + (releaseAt?.let { " · " + com.gflix.app.data.Library.releaseText(it, false, false) } ?: "")
                tmdbUrl = ""; title = ""; trailerLink = ""; trailerFile = null; trailerEn = ""; releaseAt = null
                items.clear(); items += MergeItem(null, null)
                shortcutInfo = null
                message = "✓ \"$label\" sıraya eklendi (${queued.size}). Sıradaki filmi seç; hepsi sırayla tek tek yüklenir."
                listState.animateScrollToItem(0)
            }
                .onFailure { sending = false; message = "Gönderilemedi: ${it.message}" }
        }
    }

    @Suppress("UNUSED_VARIABLE") val v = version
    listPicker?.let { kind ->
        val all = rawCatalog?.titles.orEmpty()
        val list = if (kind == "series") all.filter { it.isSeries } else com.gflix.app.data.Library.comingSoon(rawCatalog, null)
        TitlePickerDialog(
            title = if (kind == "series") "Hangi dizinin yeni bölümü?" else "Yakındakilerden hangisi?",
            items = list.map { com.gflix.app.data.Presentation.localize(it, null) },
            subtitle = { t ->
                if (t.isSeries) "Dizi · " + t.seasons.sumOf { s -> s.episodes.count { it.stream != null } }.let { n -> if (n > 0) "$n bölüm yüklü" else "henüz bölüm yok" }
                else "Film" + (t.releaseAt?.let { " · " + com.gflix.app.data.Library.releaseText(it, false, false) } ?: "")
            },
            empty = if (kind == "series") "Kütüphanede dizi yok. 🔎 ile ara." else "Yakında'da bir şey yok.",
            onPick = { listPicker = null; useTitle(it) },
            onDismiss = { listPicker = null },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AdminTopBar("Dosyalardan Ekle", onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            if (queued.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1B2A1B)).padding(12.dp)) {
                    Text("Sırada (${queued.size} film)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    queued.forEachIndexed { i, l -> Text("${i + 1}. $l", color = GColors.TextSecondary, fontSize = 13.sp) }
                    Hint("Biri bitince öbürü başlar. İlerlemeyi Linkten Ekle ekranından takip edebilirsin.", Modifier.padding(top = 4.dp))
                }
                message?.takeIf { it.startsWith("✓") }?.let { Text(it, color = Color(0xFF7BD88F), fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            }
            item {
                Text("Kim birleştirsin?", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("🖥️ Bilgisayarım", toPc) { toPc = true }
                    ModeChip("☁️ GitHub", !toPc) { toPc = false }
                }
                Spacer(Modifier.height(6.dp))
                Hint("Önerilen: Türkçe dublajlı videoyu ve İngilizce videoyu ayrı ayrı ekle. Birleştirilmez; oynatıcıda dil değişince " +
                    "aynı saniyeden öbür videoya geçer, sesler hiç kaymaz. Altyazılar ortaktır. (Tek video + ayrı ses dosyası da olur; " +
                    "o zaman MKVToolNix ile birleştirilir, kayarsa sesin yanındaki − / + ile kaydır.)")
                Spacer(Modifier.height(6.dp))
                Hint(
                    if (toPc) "Telefon dosyaları olduğu gibi GitHub'a aktarır; Linux bilgisayarın indirip MKVToolNix ile birleştirir, MKV'yi yükler ve doğrular."
                    else "Telefon dosyaları olduğu gibi GitHub'a aktarır; GitHub MKVToolNix ile birleştirip yükler."
                )
                if (toPc) PcStatusLine(repo)
                Spacer(Modifier.height(10.dp))
                // ---- kısayollar (Linkten Ekle ile aynı): ara, yeni bölüm, yakındakilere
                Text("Ne ekliyorsun?", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "🔎 Film/Dizi ara" to { if (tmdbApi == null) message = "Aramak için Stüdyo Ayarları'na TMDB anahtarı gir." else tmdbSearch = true },
                        "📺 Yeni bölüm" to { listPicker = "series" },
                        "🗓️ Yakındakilere" to { listPicker = "soon" },
                    ).forEach { (l, a) ->
                        Text(l, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Color.White).clickable { a() }.padding(vertical = 10.dp, horizontal = 6.dp))
                    }
                }
                shortcutInfo?.let { Text(it, color = GColors.Match, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tmdbUrl, onValueChange = { tmdbUrl = it; message = null }, singleLine = true,
                    label = { Text("TMDB linki") }, placeholder = { Text("https://www.themoviedb.org/movie/2002-ornek-film") },
                    trailingIcon = if (tmdbApi != null) ({ IconButton(onClick = { tmdbSearch = true }) { Icon(Icons.Filled.Search, "TMDB'de ara", tint = Color.White) } }) else null,
                    colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                if (tmdbSearch) tmdbApi?.let { api ->
                    TmdbPickerDialog(api, title.ifBlank { tmdbUrl.takeIf { !it.startsWith("http") }.orEmpty() }, tv = items.any { it.season != null }, year = null,
                        onPick = { r ->
                            tmdbSearch = false
                            val lib = rawCatalog?.titles?.firstOrNull { it.tmdbId == r.id && it.isSeries == (r.kind == "tv") }
                            if (lib != null) useTitle(com.gflix.app.data.Presentation.localize(lib, null))
                            else {
                                tmdbUrl = "https://www.themoviedb.org/${if (r.kind == "tv") "tv" else "movie"}/${r.id}"
                                shortcutInfo = (if (r.kind == "tv") "📺 " else "🎬 ") + r.title + " — yeni"
                                if (r.kind == "tv") scope.launch { loadEpisodes(r.id, r.title) }
                            }
                        },
                        onDismiss = { tmdbSearch = false })
                }
                Text(
                    when {
                        tmdbUrl.isBlank() -> if (tmdbApi != null) "🔍 ile adını yazıp ara ya da themoviedb.org linkini yapıştır." else "themoviedb.org'da filmi/diziyi açıp adresini kopyala."
                        tmdb == null -> "Link anlaşılamadı (adreste /movie/123 ya da /tv/123 olmalı)."
                        tmdb.first == "tv" -> "✓ Dizi · TMDB #${tmdb.second}"
                        else -> "✓ Film · TMDB #${tmdb.second}"
                    },
                    color = if (tmdb != null) GColors.Match else GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Ad (isteğe bağlı)") },
                    placeholder = { Text("Boşsa TMDB'deki Türkçe ad") }, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Hint("🎞️ Fragman TMDB'den kendiliğinden bulunup yüklenir; burada ayrıca vermene gerek yok.")
                if (!series) {
                    Spacer(Modifier.height(8.dp))
                    Chip("📂 Hepsini seç (TR/EN video ve altyazılar, otomatik)") { pick(null, "auto") }
                }
                if (series) {
                    if (tmdb != null && tmdbApi != null) {
                        Spacer(Modifier.height(10.dp))
                        Chip(if (items.any { it.listed }) "🔄 TMDB bölüm listesini yenile" else "📋 TMDB'den bölüm listesini getir") {
                            scope.launch { loadEpisodes(tmdb.second, title.ifBlank { null }) }
                        }
                    }
                    AdminSection("Toplu seç (dosya adındaki S01E02 / \"1. Sezon 2. Bölüm\"e göre eşleşir)")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val chips = listOf(
                            "📂 Hepsini seç (otomatik eşleştir)" to { pick(null, "auto") },
                            "Türkçe videolar" to { pick(null, "video", "tr") },
                            "İngilizce videolar" to { pick(null, "video", "en") },
                            "Türkçe sesler" to { pick(null, "audio", "tr") },
                            "İngilizce sesler" to { pick(null, "audio", "en") },
                            "Türkçe altyazılar" to { pick(null, "subtitle", "tr") },
                            "İngilizce altyazılar" to { pick(null, "subtitle", "en") },
                        )
                        items(chips) { (l, a) -> Chip(l, a) }
                    }
                }
            }
            val seasonsListed = items.mapNotNull { it.season }.distinct().sorted()
            if (series && seasonsListed.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    item { ModeChip("Tümü (${items.size})", seasonFilter == null) { seasonFilter = null } }
                    items(seasonsListed) { s ->
                        val n = items.count { it.season == s }
                        val done = items.count { it.season == s && it.files.isNotEmpty() }
                        ModeChip("$s. sezon ($done/$n)", seasonFilter == s) { seasonFilter = s }
                    }
                }
            }
            items(items.filter { seasonFilter == null || it.season == seasonFilter }, key = { System.identityHashCode(it) }) { it ->
                MergeItemCard(it, series,
                    onPick = { role, lang -> pick(it, role, lang) },
                    onRemove = { if (items.size > 1) items.remove(it) })
            }
            if (!series) item {
                ReleaseTimeField(releaseAt, { releaseAt = it }, Modifier.padding(vertical = 8.dp), label = "Yayın zamanı (boşsa hemen yayınlanır)")
            }
            if (series) item {
                TextButton(onClick = {
                    val last = items.lastOrNull()
                    items += MergeItem(last?.season ?: 1, (last?.episode ?: 0) + 1)
                }) { Text("+ Bölüm ekle", color = Color.White) }
            }
            message?.takeUnless { it.startsWith("✓") }?.let { item { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) } }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF121212)).navigationBarsPadding().padding(12.dp)) {
            if (!series) {
                Button(
                    onClick = { send(next = true) }, enabled = !sending, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("＋ Sıraya ekle, sonraki filme geç" + if (queued.isNotEmpty()) " (${queued.size})" else "", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { send() }, enabled = !sending, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
            ) {
                if (sending) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Text(
                    when {
                        queued.isNotEmpty() && tmdb == null && items.all { it.files.isEmpty() } -> "Bitti (${queued.size} film sırada)"
                        queued.isNotEmpty() -> "Bunu da ekle ve bitir (${queued.size + 1} film)"
                        series && items.any { it.listed } -> "Gönder (${used().size} bölüm)"
                        toPc -> "Gönder (bilgisayarım birleştirsin)"
                        else -> "Gönder (GitHub birleştirsin)"
                    },
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else Color(0xFF333333))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF333333)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun MergeItemCard(item: MergeItem, series: Boolean, onPick: (String, String) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp)).background(GColors.Card).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (series) {
                SmallNumber("Sezon", item.season) { item.season = it }
                Spacer(Modifier.width(8.dp))
                SmallNumber("Bölüm", item.episode) { item.episode = it }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Bölümü sil", tint = GColors.TextSecondary) }
            } else {
                Text("Film dosyaları", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        if (item.name != null || item.airAt != null) {
            val date = item.airAt?.let { java.text.SimpleDateFormat("d MMMM yyyy", com.gflix.app.data.TR).format(java.util.Date(it * 1000)) }
            val future = (item.airAt ?: 0) > System.currentTimeMillis() / 1000
            Text(listOfNotNull(item.name, date?.let { if (future) "📅 $it'de yayında" else it }).joinToString(" · "),
                color = GColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (item.files.isEmpty()) Hint(if (item.listed) "Dosya eklenmezse bu bölüm gönderilmez." else "Henüz dosya yok.", Modifier.padding(vertical = 6.dp))
        item.files.sortedBy { listOf("video", "audio", "subtitle").indexOf(it.role) }.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((ROLE_NAMES[f.role] ?: f.role) + if (f.role == "video" && f.lang != "und") " " + f.lang.uppercase() else "",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(66.dp))
                Text(f.name, color = GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (f.role == "video") LangDropdown(f.lang) { f.lang = it }
                if (f.role != "video") {
                    if (f.role == "subtitle") {
                        Text(if (f.forced) "☑ Zorunlu" else "☐ Zorunlu", color = GColors.TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.clickable { f.forced = !f.forced }.padding(horizontal = 6.dp))
                    }
                    if (f.role == "audio") DelayStepper(f.delayMs) { f.delayMs = it }
                    LangDropdown(f.lang) { f.lang = it }
                }
                IconButton(onClick = { item.files.remove(f) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Kaldır", tint = GColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            val chips = listOf(
                (if (item.files.any { it.role == "video" && it.lang == "tr" }) "Türkçe videoyu değiştir" else "+ Türkçe video") to { onPick("video", "tr") },
                (if (item.files.any { it.role == "video" && it.lang == "en" }) "İngilizce videoyu değiştir" else "+ İngilizce video") to { onPick("video", "en") },
                "+ Altyazı TR" to { onPick("subtitle", "tr") },
                "+ Altyazı EN" to { onPick("subtitle", "en") },
                "+ Ayrı ses TR" to { onPick("audio", "tr") },
                "+ Ayrı ses EN" to { onPick("audio", "en") },
            )
            items(chips) { (l, a) -> Chip(l, a) }
        }
    }
}

/** Sesi elle kaydırma: − / + 100 ms (uzun basmadan, basit). */
@Composable
private fun DelayStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text("−", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)).clickable { onChange((value - 100).coerceAtLeast(-10_000)) }
                .padding(horizontal = 8.dp, vertical = 1.dp))
        Text(if (value == 0) "0 ms" else "%+d ms".format(value), color = if (value == 0) GColors.TextMuted else Color.White,
            fontSize = 11.sp, modifier = Modifier.width(58.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("+", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)).clickable { onChange((value + 100).coerceAtMost(10_000)) }
                .padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

@Composable
private fun SmallNumber(label: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(), onValueChange = { t -> onChange(t.filter(Char::isDigit).take(3).toIntOrNull()) },
        label = { Text(label) }, singleLine = true, colors = gTextFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
    )
}
