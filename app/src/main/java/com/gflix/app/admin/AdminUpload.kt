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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.gflix.app.appContainer
import com.gflix.app.data.Title
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "mov", "avi", "ts", "webm", "wmv", "mpg", "mpeg", "flv")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
private val KINDS = listOf("movie" to "Film", "episode" to "Dizi bölümü", "trailer" to "Fragman", "theme" to "Tema müziği")
/** Bir yapıma eklenen dosyalar (yapım seçilir, ad girilmez) */
private val ATTACH_KINDS = setOf("trailer", "theme")

/** Videodan ayrı seçilen ses dosyası */
class ExtraAudio(val uri: String, val name: String, val size: Long, lang: String) {
    var lang by mutableStateOf(lang)
}

/** Ekranda düzenlenen dosya. Probe listeleri değişince [version] artırılır (yeniden çizim için). */
class PendingUpload(val uri: Uri, val name: String) {
    var probe by mutableStateOf<ProbeResult?>(null)
    var error by mutableStateOf<String?>(null)
    var kind by mutableStateOf("movie")
    var title by mutableStateOf("")
    var parsedTitle = ""
    var tmdb by mutableStateOf<Int?>(null)
    var tmdbLabel by mutableStateOf("")
    var year by mutableStateOf<Int?>(null)
    var season by mutableStateOf<Int?>(null)
    var episode by mutableStateOf<Int?>(null)
    var itemId by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)
    /** Videoyla birlikte seçilen altyazılar (video incelenince eklenir) */
    val queuedSubs = mutableListOf<Uri>()
    /** Dil sürümü (archive.org düzeni): aynı yapıma birden çok video yüklenince her biri bu dilin videosu olur */
    var versionLang by mutableStateOf("tr")
    /** Videodan ayrı ses dosyaları (archive.org düzeni: video + sesler + altyazılar ayrı yüklenir, oynatıcı birleştirir) */
    val extraAudio = mutableStateListOf<ExtraAudio>()

    fun summary(): String {
        error?.let { return "⚠ $it" }
        val p = probe ?: return "İnceleniyor…"
        val parts = mutableListOf(KINDS.first { it.first == kind }.second)
        if (kind == "episode") parts += "S%02dE%02d".format(season ?: 0, episode ?: 0)
        parts += title.ifBlank { "—" }
        val langs = (p.audio.filter { it.include }.map { it.lang } + extraAudio.map { it.lang }).joinToString("/") { Langs.label(it) }
        if (langs.isNotEmpty() && kind !in ATTACH_KINDS) parts += "Ses: $langs"
        parts += "%.2f GB".format(p.size / 1_073_741_824.0)
        return parts.joinToString(" · ")
    }
}

@Composable
fun AdminUploadScreen(onBack: () -> Unit, onStarted: () -> Unit, presetTrailerFor: String? = null) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    val catalog by context.appContainer.catalogRepository.catalog.collectAsState()
    val titles = catalog?.titles.orEmpty()
    val adminState by repo.store.state.collectAsState(initial = null)
    val archive = adminState?.archive == true
    val pending = remember { mutableStateListOf<PendingUpload>() }
    var expanded by remember { mutableStateOf<PendingUpload?>(null) }
    var picker by remember { mutableStateOf<PendingUpload?>(null) }
    var subTarget by remember { mutableStateOf<PendingUpload?>(null) }
    var tmdb by remember { mutableStateOf<Tmdb?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { tmdb = repo.tmdb() }

    // Altyazı dosyası ekle (dil addan; aynı dildeki gömülü altyazı kapatılır)
    fun attachSub(p: PendingUpload, uri: Uri) {
        val probe = p.probe ?: run { p.queuedSubs += uri; return }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val (name, _) = MediaProbe.fileInfo(context, uri)
        val lang = Langs.guessFromName(name).let { if (it == "und") "tr" else it }
        probe.subs.filter { it.lang == lang && it.fileUri == null }.forEach { it.include = false }
        probe.subs += ProbeSub(-1, lang, name.substringAfterLast('.').uppercase(), name.lowercase().contains("forced"), "", false, true, fileUri = uri.toString(), fileName = name)
        p.version++
    }

    /** Altyazıyı ait olduğu videoya bağla: bölüm kodu (S01E02) > ortak ad başı > tek video */
    fun matchSub(subName: String): PendingUpload? {
        val code = FileNames.parse(subName).let { if (it.season != null) it.season to it.episode else null }
        if (code != null) pending.firstOrNull { it.season == code.first && it.episode == code.second }?.let { return it }
        val base = subName.substringBeforeLast('.').lowercase()
        pending.maxByOrNull { v -> v.name.substringBeforeLast('.').lowercase().commonPrefixWith(base).length }
            ?.takeIf { v -> v.name.substringBeforeLast('.').lowercase().commonPrefixWith(base).length >= 6 }?.let { return it }
        return pending.singleOrNull()
    }

    /** Ayrı ses dosyasını videoya bağla (dil adından: film.tr.m4a, film.eng.aac …) */
    fun attachAudio(p: PendingUpload, uri: Uri) {
        if (p.extraAudio.any { it.uri == uri.toString() }) return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val (name, size) = MediaProbe.fileInfo(context, uri)
        val guess = Langs.guessFromName(name)
        val lang = if (guess != "und") guess else if (p.extraAudio.none { it.lang == "tr" }) "tr" else "en"
        p.extraAudio += ExtraAudio(uri.toString(), name, size, lang)
        p.version++
    }

    fun addVideos(all: List<Uri>) {
        val (subs, media) = all.partition { u -> FileKind.of(runCatching { MediaProbe.fileInfo(context, u).first }.getOrDefault("")) == FileKind.Subtitle }
        // archive.org düzeninde videoyla birlikte seçilen ses dosyaları o videonun ayrı sesleri olur; tek başına seçilen ses tema müziğidir
        val (audios, uris) = media.partition { u ->
            archive && presetTrailerFor == null && runCatching { MediaProbe.fileInfo(context, u).first }.getOrDefault("").substringAfterLast('.', "").lowercase() in AUDIO_EXT
        }
        val looseAudio = mutableListOf<Uri>()
        val videoCount = uris.size + pending.count { it.kind !in ATTACH_KINDS }
        (uris + audios.filter { videoCount == 0 }).forEach { uri ->
            if (pending.any { it.uri == uri }) return@forEach
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val (name, _) = runCatching { MediaProbe.fileInfo(context, uri) }.getOrDefault("video" to 0L)
            val ext = name.substringAfterLast('.', "").lowercase()
            val isAudio = ext in AUDIO_EXT
            if (ext !in VIDEO_EXT && !(archive && isAudio)) {
                message = "\"$name\" bir video dosyası gibi görünmüyor."
                return@forEach
            }
            if (archive && presetTrailerFor != null && !isAudio) {
                message = "Fragmanlar TMDB'den (YouTube) otomatik gelir, yüklenmez. Buradan sadece tema müziği (mp3, m4a…) yüklenir."
                return@forEach
            }
            val parsed = FileNames.parse(name)
            if (archive && !isAudio && presetTrailerFor == null && parsed.kind == "trailer") {
                message = "\"$name\" fragman gibi görünüyor: fragmanlar TMDB'den (YouTube) otomatik gelir, yüklenmez."
                return@forEach
            }
            val p = PendingUpload(uri, name)
            p.kind = when {
                isAudio -> "theme"
                presetTrailerFor != null -> "trailer"
                else -> parsed.kind
            }
            p.title = parsed.title; p.parsedTitle = parsed.title; p.year = parsed.year; p.season = parsed.season; p.episode = parsed.episode
            if (p.kind in ATTACH_KINDS) {
                p.itemId = presetTrailerFor ?: titles.firstOrNull { it.title.equals(parsed.title, true) || (it.names.values.any { n -> n.equals(parsed.title, true) }) }?.id
                p.itemId?.let { id -> p.title = titles.firstOrNull { it.id == id }?.title ?: p.title }
            }
            pending += p
            if (expanded == null) expanded = p
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { MediaProbe.probe(context, uri) } }
                    .onSuccess {
                        p.probe = it
                        // dublaj dili: dosya adından (film.tr.mkv, dublaj, turkce…), yoksa videonun ilk ses dili
                        p.versionLang = Langs.guessFromName(name).takeIf { g -> g != "und" }
                            ?: if (Regex("dublaj|turkce|türkçe", RegexOption.IGNORE_CASE).containsMatchIn(name)) "tr"
                            else it.audio.firstOrNull()?.lang?.takeIf { l -> l != "und" } ?: "tr"
                        p.version++; val q = p.queuedSubs.toList(); p.queuedSubs.clear(); q.forEach { u -> attachSub(p, u) } }
                    .onFailure { p.error = "Dosya okunamadı: ${it.message}" }
            }
            tmdb?.let { t -> if (p.kind !in ATTACH_KINDS && p.title.isNotBlank()) autoMatch(scope, t, p, pending, titles) }
        }
        // birlikte seçilen ses dosyaları: videolarına bağla
        if (videoCount > 0) audios.forEach { u ->
            val n = runCatching { MediaProbe.fileInfo(context, u).first }.getOrDefault("ses")
            matchSub(n)?.takeIf { it.kind !in ATTACH_KINDS }?.let { attachAudio(it, u) } ?: looseAudio.add(u)
        }
        if (looseAudio.isNotEmpty()) message = "Hangi videoya ait anlaşılamadı: " + looseAudio.joinToString(", ") { runCatching { MediaProbe.fileInfo(context, it).first }.getOrDefault("ses") } +
            " (videonun kartından '+ Ses dosyası' ile ekle)"
        // birlikte seçilen altyazılar: videolarına bağla
        val unmatched = mutableListOf<String>()
        subs.forEach { u ->
            val n = runCatching { MediaProbe.fileInfo(context, u).first }.getOrDefault("altyazı")
            matchSub(n)?.let { attachSub(it, u) } ?: unmatched.add(n)
        }
        if (unmatched.isNotEmpty()) message = "Hangi videoya ait anlaşılamadı: " + unmatched.joinToString(", ") + " (videonun kartından '+ altyazı' ile ekle)"
    }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addVideos(uris) }
    val browser = rememberFileBrowser()
    val rawCatalog by context.appContainer.catalogRepository.catalog.collectAsState()
    var assignPicker by remember { mutableStateOf<String?>(null) }   // "series" | "soon"

    /** Seçilen videoları yapıma ata: dizide yüklü bölümlerden sonrakiler (TMDB sezon sonuna göre), filmde tek video. */
    fun assignTo(t: Title) {
        val vids = pending.filter { it.kind !in ATTACH_KINDS }.sortedBy { it.name.lowercase() }
        if (vids.isEmpty()) { message = "Önce videoları seç."; return }
        scope.launch {
            if (t.isSeries) {
                val uploaded = t.seasons.flatMap { s -> s.episodes.filter { it.stream != null }.map { s.number to it.number } }.toMutableList()
                val seasons = t.tmdbId?.let { id -> tmdb?.let { runCatching { it.seasons(id) }.getOrNull() } }
                // adında bölüm kodu olanlar kendi kodunu korur; olmayanlar sıradakilere dizilir
                vids.forEach { v ->
                    val coded = FileNames.parse(v.name).season != null
                    val se = if (coded) v.season!! to v.episode!! else NextEpisode.next(uploaded, seasons)
                    uploaded += se
                    v.kind = "episode"; v.season = se.first; v.episode = se.second
                    v.title = t.title; v.itemId = t.id; v.tmdb = t.tmdbId; v.tmdbLabel = t.title; v.version++
                }
                message = "📺 ${t.title}: " + vids.joinToString(", ") { "S%02dE%02d".format(it.season, it.episode) } + " olarak atandı. Kartlardan kontrol et."
            } else {
                val v = vids.first()
                v.kind = "movie"; v.season = null; v.episode = null
                v.title = t.title; v.itemId = t.id; v.tmdb = t.tmdbId; v.tmdbLabel = t.title; v.version++
                message = "🎬 \"${v.name}\" → ${t.title}" + if (vids.size > 1) " (filme tek video atanır; diğerlerini ayrıca ayarla)" else ""
            }
        }
    }
    assignPicker?.let { kind ->
        val all = rawCatalog?.titles.orEmpty()
        val list = if (kind == "series") all.filter { it.isSeries } else com.gflix.app.data.Library.comingSoon(rawCatalog, null)
        TitlePickerDialog(
            title = if (kind == "series") "Hangi dizinin yeni bölümleri?" else "Yakındakilerden hangisi?",
            items = list.map { com.gflix.app.data.Presentation.localize(it, null) },
            subtitle = { t ->
                if (t.isSeries) "Dizi · " + t.seasons.sumOf { s -> s.episodes.count { it.stream != null } }.let { n -> if (n > 0) "$n bölüm yüklü" else "henüz bölüm yok" }
                else "Film" + (t.releaseAt?.let { " · " + com.gflix.app.data.Library.releaseText(it, false, false) } ?: "")
            },
            empty = if (kind == "series") "Kütüphanede dizi yok." else "Yakında'da bir şey yok.",
            onPick = { assignPicker = null; assignTo(it) },
            onDismiss = { assignPicker = null },
        )
    }
    fun openVideos() = browser.open(
        if (presetTrailerFor != null) (if (archive) "Tema müziği seç" else "Fragman seç") else "Video, ses ve altyazı seç",
        when {
            presetTrailerFor != null -> if (archive) setOf(FileKind.Audio) else setOf(FileKind.Video)
            archive -> setOf(FileKind.Video, FileKind.Subtitle, FileKind.Audio)
            else -> setOf(FileKind.Video, FileKind.Subtitle)
        },
    ) { addVideos(it) }
    val pickSub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val p = subTarget ?: return@rememberLauncherForActivityResult
        if (uri != null) attachSub(p, uri)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var releaseAt by remember { mutableStateOf<Long?>(null) }

    fun idFor(p: PendingUpload): String = when {
        p.kind in ATTACH_KINDS -> p.itemId!!
        p.itemId != null -> p.itemId!!
        p.tmdb != null -> titles.firstOrNull { it.tmdbId == p.tmdb && it.isSeries == (p.kind == "episode") }?.id ?: FileNames.itemId(p.kind, p.title)
        else -> FileNames.itemId(p.kind, p.title)
    }

    /**
     * Dil sürümü olarak mı yüklensin: aynı yapıma (bölüme) bu listede başka video da varsa ya da yapımda başka dilde
     * video zaten yüklüyse evet (üzerine yazmasın, yanına eklensin). Aynı dilde varsa o dilin videosu değişir.
     */
    fun versionFor(p: PendingUpload): String? {
        if (p.kind in ATTACH_KINDS) return null
        val id = idFor(p)
        val siblings = pending.count { o -> o.kind !in ATTACH_KINDS && idFor(o) == id && o.season == p.season && o.episode == p.episode }
        if (siblings > 1) return p.versionLang
        val t = titles.firstOrNull { it.id == id } ?: return null
        val old = if (p.kind == "episode") t.seasons.firstOrNull { it.number == p.season }?.episodes?.firstOrNull { it.number == p.episode }?.stream else t.stream
        val langs = com.gflix.app.archive.ArchiveCatalog.streamLangs(old)
        return if (old != null && (old.versions.isNotEmpty() || langs.any { it != p.versionLang })) p.versionLang else null
    }

    fun start() {
        val problems = mutableListOf<String>()
        val ok = pending.filter { p -> validate(p)?.also { problems += "• ${p.name}: $it" } == null }
        // Aynı filme (ya da aynı bölüme) iki video: sonradan biten öncekinin üzerine yazar → başlatma, doğru yolu söyle
        val groups = ok.filter { it.kind !in ATTACH_KINDS }.groupBy { idFor(it) + "|" + (it.season ?: 0) + "|" + (it.episode ?: 0) }.values
        // archive.org düzeni: aynı yapıma (bölüme) birden çok video = dil sürümleri; dilleri farklı olmalı
        val sameLang = if (archive) groups.firstOrNull { g -> g.size > 1 && g.map { it.versionLang }.distinct().size < g.size } else null
        if (sameLang != null) {
            message = "⚠ " + sameLang.joinToString(" ve ") { "\"${it.name}\"" } + " aynı yapıma aynı dublaj diliyle atanmış. " +
                "Dil başına ayrı video yüklüyorsan kartlardan \"Dublaj dili\"ni farklı seç (ör. Türkçe ve İngilizce); değilse fazlasını ✕ ile kaldır."
            return
        }
        val clash = if (archive) null else groups.firstOrNull { it.size > 1 }
        if (clash != null) {
            message = "⚠ " + clash.joinToString(" ve ") { "\"${it.name}\"" } + " aynı " + (if (clash.first().kind == "episode") "bölüme" else "filme") +
                " atanmış. Böyle yüklersen sonra biten öncekinin üzerine yazar, biri kaybolur.\n" +
                "Dil başına ayrı video (ör. Türkçe + İngilizce) için Stüdyo → \"Dosyalardan Ekle\"yi kullan: iki videoyu \"video tr\" ve \"video en\" olarak ekle, " +
                "izlerken dil değiştirince video da değişir. Altyazıları orada bir kez eklemen yeter.\nYa da gereksiz olanı ✕ ile kaldır."
            return
        }
        if (ok.isEmpty()) { message = problems.joinToString("\n").ifEmpty { "Yüklenecek dosya yok." }; return }
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        starting = true
        scope.launch {
            runCatching {
                val st = repo.store.current()
                val tasks = withContext(Dispatchers.IO) {
                    ok.map { p ->
                        val itemId = idFor(p)
                        if (st.archive) com.gflix.app.archive.ArchiveUploadWorker.buildTask(
                            context, st.iaPrefix!!, p.kind, p.title.trim(), itemId, p.tmdb, p.year, p.season, p.episode, p.uri, p.probe!!,
                            extraAudio = p.extraAudio.toList(), versionLang = versionFor(p))
                        else {
                            if (p.kind == "theme") throw IllegalStateException("Tema müziği sadece archive.org düzeninde yüklenir.")
                            UploadWorker.buildTask(context, p.kind, p.title.trim(), itemId, p.tmdb, p.year, p.season, p.episode, p.uri, p.probe!!)
                        }
                    }
                }
                // Zamanlı yayın: dosyalar yüklenmeden önce kayda yazılır, böylece yükleme bitse de saati gelene kadar gizli kalır
                val at = releaseAt
                if (at != null) ok.filter { it.kind !in ATTACH_KINDS }.forEach { p ->
                    val sk = Skeleton(p.title.trim(), p.kind == "episode", p.tmdb, p.year)
                    if (p.kind == "episode" && p.season != null && p.episode != null) repo.setEpisodeRelease(idFor(p), p.season!!, p.episode!!, at, sk)
                    else repo.setRelease(idFor(p), at, sk)
                }
                repo.addTasks(tasks)
            }.onSuccess {
                pending.removeAll(ok)
                starting = false
                if (problems.isEmpty()) onStarted() else message = "Bazı dosyalar eklenemedi:\n" + problems.joinToString("\n")
            }.onFailure { starting = false; message = it.message }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AdminTopBar(if (presetTrailerFor != null) (if (archive) "Tema Müziği Yükle" else "Fragman Yükle") else "İçerik Yükle", onBack)
        if (pending.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.VideoFile, null, tint = GColors.TextSecondary, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text("Telefondaki videoları seç", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Hint("Video ve altyazıları birlikte seçebilirsin; altyazılar adlarından videolarına eşleştirilir (ör. Dizi.S01E02.mkv + Dizi.S01E02.tr.srt). Dosya adları tam görünür, en son kaldığın klasörden açılır.")
                Spacer(Modifier.height(20.dp))
                Button(onClick = { openVideos() },
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White)) {
                    Text("Dosya seç", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { pickVideos.launch(arrayOf("video/*", "application/x-matroska", "application/octet-stream")) }) {
                    Text("Android'in kendi seçicisiyle", color = GColors.TextSecondary, fontSize = 13.sp)
                }
                message?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                itemsIndexed(pending, key = { _, p -> p.uri.toString() }) { _, p ->
                    PendingCard(
                        p = p, open = expanded === p, titles = titles, hasTmdb = tmdb != null, archive = archive,
                        onToggle = { expanded = if (expanded === p) null else p },
                        onRemove = { pending.remove(p); if (expanded === p) expanded = null },
                        onSearch = { picker = p },
                        onAddSub = { subTarget = p; browser.open("Altyazı seç", setOf(FileKind.Subtitle), multi = false) { u -> u.firstOrNull()?.let { attachSub(p, it) } } },
                        onAddAudio = { browser.open("Ses dosyası seç", setOf(FileKind.Audio)) { us -> us.forEach { attachAudio(p, it) } } },
                        onApplyToSeries = {
                            var n = 0
                            pending.filter { it !== p && it.parsedTitle.equals(p.parsedTitle, true) }.forEach { o ->
                                o.kind = "episode"; o.title = p.title; o.tmdb = p.tmdb; o.tmdbLabel = p.tmdbLabel; o.itemId = p.itemId; o.year = p.year
                                val src = p.probe; val dst = o.probe
                                if (src != null && dst != null) dst.audio.zip(src.audio).forEach { (a, b) -> a.lang = b.lang; a.include = b.include }
                                o.version++; n++
                            }
                            message = "$n bölüme uygulandı."
                        },
                    )
                }
                item {
                    if (presetTrailerFor == null) Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        listOf("📺 Yeni bölüm olarak ata" to "series", "🗓️ Yakındakine ata" to "soon").forEach { (l, k) ->
                            Text(l, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Color.White).clickable { assignPicker = k }.padding(vertical = 10.dp, horizontal = 8.dp))
                        }
                    }
                    Row {
                        TextButton(onClick = { openVideos() }) { Text("+ Başka video / altyazı ekle", color = Color.White) }
                        TextButton(onClick = { pickVideos.launch(arrayOf("video/*", "application/x-matroska", "application/octet-stream")) }) {
                            Text("Android seçicisi", color = GColors.TextSecondary)
                        }
                    }
                    if (presetTrailerFor == null && pending.any { it.kind !in ATTACH_KINDS }) {
                        ReleaseTimeField(releaseAt, { releaseAt = it }, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), label = "Yayın zamanı (bu listedeki tüm videolar)")
                    }
                    message?.let { Text(it, color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                }
            }
            Box(Modifier.fillMaxWidth().background(Color(0xFF121212)).navigationBarsPadding().padding(12.dp)) {
                Button(
                    onClick = ::start, enabled = !starting && pending.any { it.probe != null },
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
                ) {
                    if (starting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Text("Yüklemeyi Başlat (${pending.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }

    val pick = picker
    val t = tmdb
    if (pick != null && t != null) {
        TmdbPickerDialog(t, pick.parsedTitle.ifBlank { pick.title }, pick.kind == "episode", pick.year,
            onPick = { r ->
                pick.tmdb = r.id; pick.title = r.title; pick.year = r.year ?: pick.year
                if (r.kind == "tv" && pick.kind == "movie") pick.kind = "episode"
                pick.tmdbLabel = "Seçildi: ${r.title} (${r.year ?: "?"}) — TMDB #${r.id}"
                pick.itemId = titles.firstOrNull { it.tmdbId == r.id }?.id
                picker = null
            }, onDismiss = { picker = null })
    }
}

private fun autoMatch(scope: CoroutineScope, tmdb: Tmdb, p: PendingUpload, all: List<PendingUpload>, titles: List<Title>) {
    all.firstOrNull { it !== p && it.kind == "episode" && it.tmdb != null && it.parsedTitle.equals(p.parsedTitle, true) }?.let { s ->
        p.tmdb = s.tmdb; p.title = s.title; p.tmdbLabel = s.tmdbLabel; p.itemId = s.itemId
        return
    }
    scope.launch {
        runCatching { tmdb.search(if (p.kind == "episode") "tv" else "movie", p.parsedTitle, if (p.kind == "movie") p.year else null) }
            .getOrNull()?.firstOrNull()?.let { r ->
                if (p.tmdb == null) {
                    p.tmdb = r.id; p.title = r.title; p.year = p.year ?: r.year
                    p.tmdbLabel = "Otomatik eşleşme: ${r.title} (${r.year ?: "?"}). Yanlışsa 🔍 ile ara."
                    p.itemId = titles.firstOrNull { it.tmdbId == r.id }?.id
                }
            }
    }
}

private fun validate(p: PendingUpload): String? {
    p.error?.let { return it }
    val probe = p.probe ?: return "henüz incelenmedi"
    if (probe.audio.none { it.include } && p.extraAudio.isEmpty()) return "en az bir ses kanalı seçilmeli ya da ayrı ses dosyası eklenmeli"
    if (p.kind in ATTACH_KINDS) return if (p.itemId == null) "${if (p.kind == "theme") "tema müziğinin" else "fragmanın"} hangi yapıma ait olduğunu seç" else null
    if (p.title.isBlank()) return "ad boş"
    if (p.kind == "episode" && ((p.season ?: 0) <= 0 || (p.episode ?: 0) <= 0)) return "sezon ve bölüm numarası gerekli"
    return null
}

@Composable
private fun PendingCard(
    p: PendingUpload, open: Boolean, titles: List<Title>, hasTmdb: Boolean, archive: Boolean,
    onToggle: () -> Unit, onRemove: () -> Unit, onSearch: () -> Unit, onAddSub: () -> Unit, onApplyToSeries: () -> Unit, onAddAudio: () -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val v = p.version // probe değişikliklerinde yeniden çiz
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(GColors.Card)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(p.summary(), color = if (p.error != null) Color(0xFFFF6B6B) else GColors.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Color.White)
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Çıkar", tint = GColors.TextSecondary) }
        }
        val probe = p.probe
        if (!open || probe == null) return@Column
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // archive.org düzeninde fragman yüklenmez (TMDB'den gelir); eski düzende tema müziği yok
                KINDS.filter { if (archive) it.first != "trailer" else it.first != "theme" }.forEach { (k, label) ->
                    val sel = p.kind == k
                    Text(label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color(0xFF3A3A3A))
                            .clickable { p.kind = k }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (p.kind in ATTACH_KINDS) {
                val current = titles.firstOrNull { it.id == p.itemId }
                SimpleDropdown(current?.title ?: if (p.kind == "theme") "Hangi yapımın tema müziği? Seç" else "Hangi yapımın fragmanı? Seç", titles.map { it.id to (it.title + if (it.isSeries) " (Dizi)" else "") },
                    onSelect = { id -> p.itemId = id; p.title = titles.first { it.id == id }.title })
            } else {
                OutlinedTextField(
                    value = p.title, onValueChange = { p.title = it }, label = { Text("Ad") }, singleLine = true, colors = gTextFieldColors(),
                    trailingIcon = if (hasTmdb) ({ IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "TMDB'de ara", tint = Color.White) } }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                var tmdbText by remember(p) { mutableStateOf("") }
                OutlinedTextField(
                    value = tmdbText, singleLine = true, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    label = { Text("TMDB linki (yapıştır, isteğe bağlı)") },
                    onValueChange = { t ->
                        tmdbText = t
                        parseTmdbUrl(t)?.let { (kind, id) ->
                            p.tmdb = id
                            if (kind == "tv" && p.kind == "movie") p.kind = "episode"
                            if (kind == "movie" && p.kind == "episode") p.kind = "movie"
                            p.itemId = titles.firstOrNull { it.tmdbId == id }?.id
                            p.itemId?.let { iid -> titles.firstOrNull { it.id == iid }?.title?.let { n -> p.title = n } }
                            p.tmdbLabel = "TMDB linkinden: ${if (kind == "tv") "Dizi" else "Film"} #$id (adı ve bilgileri TMDB'den çekilir)"
                        }
                    },
                )
                if (p.tmdbLabel.isNotBlank()) Hint(p.tmdbLabel, Modifier.padding(top = 4.dp))
                if (!hasTmdb) Hint(if (archive) "TMDB eşleştirmesi için Stüdyo Ayarları'na TMDB anahtarını gir. Girmezsen yapım sadece adı ve yılıyla eklenir."
                    else "TMDB araması için Stüdyo Ayarları'na TMDB anahtarını gir. Girmezsen GitHub ada göre otomatik eşleştirir.", Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Yıl", p.year, Modifier.weight(1f)) { p.year = it }
                    if (p.kind == "episode") {
                        NumberField("Sezon", p.season, Modifier.weight(1f)) { p.season = it }
                        NumberField("Bölüm", p.episode, Modifier.weight(1f)) { p.episode = it }
                    }
                }
                if (p.kind == "episode") {
                    TextButton(onClick = onApplyToSeries) { Text("Bu bilgileri aynı dizinin diğer bölümlerine uygula", color = GColors.TextSecondary, fontSize = 13.sp) }
                }
            }
            if (archive && p.kind !in ATTACH_KINDS) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Dublaj dili", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Hint("Aynı filme (bölüme) Türkçe ve İngilizce ayrı videolar yüklenirse izlerken ses menüsünden geçilir; altyazılar ortak.")
                    }
                    LangDropdown(p.versionLang) { p.versionLang = it; p.version++ }
                }
            }
            AdminSection("Ses kanalları")
            if (archive) Hint("Dosya olduğu gibi yüklenir (içindeki bütün sesler ve altyazılar kalır). Buradaki dil seçimi, etiketsiz seslerin oynatıcıdaki adını belirler.")
            probe.audio.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = a.include, onCheckedChange = { a.include = it; p.version++ }, colors = CheckboxDefaults.colors(checkedColor = GColors.Red))
                    Text(a.describe(), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LangDropdown(a.lang) { a.lang = it; p.version++ }
                }
            }
            if (probe.audio.isEmpty() && archive) Hint("Videoda ses yok. Ayrı ses dosyası ekle.")
            if (archive && p.kind !in ATTACH_KINDS) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdminSection("Ayrı ses dosyaları", Modifier.weight(1f))
                    TextButton(onClick = onAddAudio) { Text("+ Ses dosyası", color = Color.White, fontSize = 13.sp) }
                }
                if (p.extraAudio.isEmpty()) Hint("Ör. film.mp4 + film.tr.m4a + film.en.m4a: ayrı yüklenir, oynatıcı videoyla birlikte çalar. Sesler videoyla aynı süre ve senkronda olmalı.")
                p.extraAudio.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name + " · " + "%.0f MB".format(a.size / 1_048_576.0), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LangDropdown(a.lang) { a.lang = it; p.version++ }
                        IconButton(onClick = { p.extraAudio.remove(a); p.version++ }) { Icon(Icons.Filled.Close, "Çıkar", tint = GColors.TextSecondary) }
                    }
                }
            }
            if (p.kind !in ATTACH_KINDS) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdminSection("Altyazılar", Modifier.weight(1f))
                    TextButton(onClick = onAddSub) { Text("+ Dosya ekle", color = Color.White, fontSize = 13.sp) }
                }
                if (probe.subs.isEmpty()) Hint("Videoda altyazı bulunamadı. .srt/.vtt dosyası ekleyebilirsin.")
                probe.subs.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s.include, enabled = !s.bitmap, onCheckedChange = { s.include = it; p.version++ }, colors = CheckboxDefaults.colors(checkedColor = GColors.Red))
                        Column(Modifier.weight(1f)) {
                            Text(s.describe(), color = if (s.bitmap) GColors.TextMuted else Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !s.bitmap) { s.forced = !s.forced; p.version++ }) {
                                Text(if (s.forced) "☑ Zorunlu" else "☐ Zorunlu", color = GColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                        LangDropdown(s.lang, enabled = !s.bitmap) { s.lang = it; p.version++ }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(), onValueChange = { t -> onChange(t.filter(Char::isDigit).take(4).toIntOrNull()) },
        label = { Text(label) }, singleLine = true, colors = gTextFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier,
    )
}
