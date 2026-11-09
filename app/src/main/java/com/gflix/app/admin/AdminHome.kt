package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.gflix.app.appContainer
import com.gflix.app.ui.components.ProgressLine
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun AdminHomeScreen(onBack: () -> Unit, onUpload: () -> Unit, onEditTitle: (String) -> Unit, onSettings: () -> Unit, onLinks: () -> Unit, onMerge: () -> Unit, onSoon: () -> Unit = {}, onCapture: () -> Unit = {}, onLog: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val state by repo.store.state.collectAsState(initial = null)
    val catalog by context.appContainer.catalogRepository.catalog.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf<UploadTask?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Canlı ilerleme WorkManager'dan
    val progress by remember {
        WorkManager.getInstance(context).getWorkInfosByTagFlow("gflix-upload").map { infos ->
            infos.filter { it.state == WorkInfo.State.RUNNING }.associate { it.progress.getString("task").orEmpty() to (it.progress.getLong("done", 0) to it.progress.getLong("total", 0)) }
        }
    }.collectAsState(initial = emptyMap())

    val ia = state?.archive == true
    val pc = com.gflix.app.data.PcServer.configured && !ia
    LaunchedEffect(state?.ready) {
        // Yeni düzen: bilgisayar bağlıyken GitHub'a hiç sorulmaz
        while (state?.ready == true && !pc && !ia) {
            runCatching { repo.refreshServerStatus() }
            delay(20_000)
        }
    }

    val s = state ?: return
    if (!s.ready && !pc) {
        AdminSettingsScreen(onBack = onBack, firstTime = true)
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AdminTopBar("G-Flix Stüdyo", onBack) {
            IconButton(onClick = { scope.launch { if (!pc && !ia) runCatching { repo.refreshServerStatus() }; context.appContainer.catalogRepository.refresh() } }) {
                Icon(Icons.Filled.Refresh, "Yenile", tint = Color.White)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Ayarlar", tint = Color.White) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            if (pc) {
                item { PcStatsCard(); Spacer(Modifier.height(12.dp)) }
                item { NewsCard() }
                item { PendingCard() }
                item { DeadLinksCard() }
                item {
                    Button(
                        onClick = onCapture, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
                    ) { Text("🌐  Sayfadan Yakala  ·  Yapımı ekle / yayını tazele", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    Spacer(Modifier.height(8.dp))
                    WishHomeButton()
                    Spacer(Modifier.height(8.dp))
                    ArchiveJobsCard()
                    Spacer(Modifier.height(8.dp))
                    YtFollowButton()
                    Spacer(Modifier.height(8.dp))
                    AddLinkButton()
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onLog, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                    ) { Text("🖥️  Canlı Kayıt  ·  Bilgisayarda akan bütün satırlar", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    Spacer(Modifier.height(6.dp))
                    Hint("İstek listesinde indirme modu açıksa izinli videolar bilgisayara indirilir, archive.org'a yüklenir ve doğrulanınca kataloğa eklenir. " +
                        "Arşivleme kapalıysa eski anlık yayın sistemi kullanılabilir. Yanlış fragmanı ⚑ ile düzelt.")
                }
            }
            if (!pc) catalog?.stats?.let { st -> item { ArchiveStats(st); Spacer(Modifier.height(12.dp)) } }
            // archive.org yükleme modu seçiliyken de TMDB istek listesi ve kategori taraması erişilebilir.
            if (ia && com.gflix.app.data.PcServer.configured) item {
                WishHomeButton()
                Spacer(Modifier.height(8.dp))
                ArchiveJobsCard()
                Spacer(Modifier.height(12.dp))
            }
            if (ia) item {
                Button(
                    onClick = onUpload, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("İçerik Yükle  ·  film, bölüm, tema müziği", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSoon, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                ) { Text("🗓️  Yakında Gelecekler  ·  Duyur, tarihte yayınla", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                Spacer(Modifier.height(8.dp))
                var trailerNote by remember { mutableStateOf<String?>(null) }
                Button(
                    onClick = {
                        trailerNote = "TMDB'de aranıyor…"
                        scope.launch { trailerNote = runCatching { repo.fillTrailersFromTmdb() }.fold({ n -> if (n > 0) "✓ $n yapıma fragman eklendi." else "Fragmanı eksik yapım yok (ya da TMDB'de fragman bulunamadı)." }, { "⚠ ${it.message}" }) }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                ) { Text("🎞️  Eksik fragmanları TMDB'den getir", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                trailerNote?.let { Hint(it, Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(6.dp))
                Hint("Dosyalar olduğu gibi (MKV/MP4, içindeki bütün sesler ve altyazılarla) archive.org'a yüklenir: " +
                    "arşiv \"${s.iaPrefix}\". Uygulamayı kapatsan da yükleme bildirimde sürer; bitenler kataloğa toplu yazılır.")
                s.iaCatalogNote?.let { Hint(it, Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(12.dp))
            }
            if (!pc && !ia) item { RateLimitCard(repo); Spacer(Modifier.height(12.dp)) }
            if (!pc && !ia) item { JobStatsCard(repo); Spacer(Modifier.height(12.dp)) }
            if (!pc && !ia) item { SiteJobsCard(repo); Spacer(Modifier.height(12.dp)) }
            if (!pc && !ia) item {
                Button(
                    onClick = onUpload, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Yeni İçerik Yükle", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onMerge, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("🧩  Dosyalardan Ekle (ayrı ses/altyazı, GitHub birleştirsin)", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCapture, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("🌐  Sayfadan Yakala  ·  Sayfayı aç, gerisi otomatik", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onLinks, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                ) { Text("🔗  Linkten Ekle  ·  İşler ve ilerleme", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSoon, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                ) { Text("🗓️  Yakında Gelecekler  ·  Fragmanla duyur, tarihte yayınla", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onLog, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                ) { Text("🖥️  Canlı Kayıt  ·  Bilgisayarda akan bütün satırlar", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                Spacer(Modifier.height(6.dp))
                Hint("Video telefondan olduğu gibi yüklenir, GitHub kendi sunucusunda hazırlar. Uygulamayı kapatsan da yükleme bildirimde devam eder.")
            }
            error?.let { item { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) } }
            if (s.tasks.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AdminSection("Yüklemeler", Modifier.weight(1f))
                        if (s.tasks.any { it.status == TaskStatus.DONE }) {
                            TextButton(onClick = {
                                scope.launch { repo.store.update { st -> st.copy(tasks = st.tasks.filterNot { it.status == TaskStatus.DONE }) } }
                            }) { Text("Bitenleri temizle", color = GColors.TextSecondary, fontSize = 13.sp) }
                        }
                    }
                }
                items(s.tasks, key = { it.id }) { t ->
                    TaskCard(t, progress[t.id],
                        onRetry = { scope.launch { runCatching { repo.retry(t) }.onFailure { error = it.message } } },
                        onRemove = { confirmRemove = t })
                }
            }
            item { AdminSection("Kütüphane") }
            val titles = catalog?.titles.orEmpty()
            if (titles.isEmpty()) item { Hint("Henüz içerik yok.") }
            items(titles, key = { "t-" + it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { onEditTitle(t.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(t.poster, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(3.dp)).background(GColors.Card))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val eps = t.seasons.sumOf { it.episodes.size }
                        Hint(listOfNotNull(if (t.isSeries) "Dizi · $eps bölüm" else "Film", t.year?.toString(), if (t.trailer != null) "🎞️ Fragman var" else null,
                            if (com.gflix.app.data.Library.isHidden(t)) "🚫 Uygulamadan kaldırıldı (notu silince geri gelir)" else null,
                            com.gflix.app.data.Library.missingEpisodes(t).takeIf { it > 0 }?.let { "$it bölüm eksik" }).joinToString(" · "))
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = GColors.TextSecondary)
                }
            }
        }
    }

    confirmRemove?.let { t ->
        ConfirmDialog(
            when {
                t.status == TaskStatus.DONE -> "Listeden kaldırılsın mı?"
                t.archiveItem != null -> "Bu yükleme iptal edilsin mi? (archive.org'a gitmiş dosyalar item'da kalır, yeniden yüklenirse üzerine yazılır)"
                else -> "Bu yükleme iptal edilip GitHub'a gönderilen parçalar silinsin mi?"
            },
            confirm = if (t.status == TaskStatus.DONE) "Kaldır" else "İptal et ve sil",
            onConfirm = { scope.launch { repo.remove(t) } }, onDismiss = { confirmRemove = null },
        )
    }
}

@Composable
private fun TaskCard(t: UploadTask, live: Pair<Long, Long>?, onRetry: () -> Unit, onRemove: () -> Unit) {
    val icon = when (t.kind) { "episode" -> "📺"; "trailer" -> "🎞️"; "theme" -> "🎵"; else -> "🎬" }
    val (done, total) = live ?: (t.bytesDone to t.bytesTotal)
    val pct = if (total > 0) (done * 100 / total).toInt() else 0
    val (text, color) = when (t.status) {
        TaskStatus.QUEUED -> (t.message ?: "Sırada (telefon)") to GColors.TextSecondary
        TaskStatus.UPLOADING -> (t.message?.let { "$it  " } ?: "") + (if (t.archiveItem != null) "archive.org'a yükleniyor" else "Telefondan yükleniyor") + "  %$pct · ${done / 1_048_576} / ${total / 1_048_576} MB" to GColors.TextSecondary
        TaskStatus.PROCESSING -> (if (t.archiveItem != null) (t.message ?: "archive.org işliyor…") + "  (işlenince kendiliğinden yayına girer)"
            else (t.message ?: "GitHub'a teslim edildi") + "  (hazırlanıyor, birkaç dakika sürer)") to Color(0xFFE8A33D)
        TaskStatus.DONE -> (if (t.archiveItem != null) "✓ archive.org'a yüklendi · ${t.archiveItem}" else "✓ Tamamlandı — uygulamada görünüyor") to GColors.Match
        TaskStatus.SERVER_FAILED -> "GitHub'da hata: ${t.message}" to Color(0xFFFF6B6B)
        else -> "Hata: ${t.message}" to Color(0xFFFF6B6B)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(GColors.Card).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$icon  ${t.display}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.status in setOf(TaskStatus.FAILED, TaskStatus.SERVER_FAILED) || (t.status == TaskStatus.QUEUED && t.message != null)) {
                TextButton(onClick = onRetry) { Text("Tekrar dene", color = Color.White, fontSize = 13.sp) }
            }
            TextButton(onClick = onRemove) { Text(if (t.status == TaskStatus.DONE) "Kaldır" else "Sil", color = GColors.TextSecondary, fontSize = 13.sp) }
        }
        if (t.status == TaskStatus.UPLOADING || t.status == TaskStatus.QUEUED) {
            ProgressLine(if (total > 0) done.toFloat() / total else 0f, Modifier.padding(vertical = 6.dp), height = 4.dp)
        }
        Text(text, color = color, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AdminSettingsScreen(onBack: () -> Unit, firstTime: Boolean = false) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    var iaAccess by remember { mutableStateOf("") }
    var iaSecret by remember { mutableStateOf("") }
    var iaPrefix by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var tmdbKey by remember { mutableStateOf("") }
    var showGitHub by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = repo.store.current()
        iaAccess = com.gflix.app.archive.SecretBox.open(s.iaAccess).orEmpty()
        iaSecret = com.gflix.app.archive.SecretBox.open(s.iaSecret).orEmpty()
        iaPrefix = s.iaPrefix ?: com.gflix.app.archive.ArchiveSource.prefix
        token = s.writeToken.orEmpty(); tmdbKey = s.tmdbKey.orEmpty(); showGitHub = token.isNotBlank(); loaded = true
    }
    val prefix = com.gflix.app.archive.ArchiveSource.normalize(iaPrefix)
    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AdminTopBar(if (firstTime) "G-Flix Stüdyo — Giriş" else "Stüdyo Ayarları", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (firstTime) {
                Text("İçerikler archive.org'a yüklenir. Yüklemek için archive.org hesabının S3 anahtarları gerekir; " +
                    "sadece bu telefonda, şifreli olarak saklanır.", color = GColors.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
            }
            AdminSection("archive.org")
            OutlinedTextField(
                value = iaAccess, onValueChange = { iaAccess = it }, label = { Text("Erişim anahtarı (access key)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = iaSecret, onValueChange = { iaSecret = it }, label = { Text("Gizli anahtar (secret key)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Hint("archive.org'a giriş yap → archive.org/account/s3.php. Kimseyle paylaşma.", Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = iaPrefix, onValueChange = { iaPrefix = it }, label = { Text("Arşiv adı (önek)") }, singleLine = true,
                colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Hint(if (prefix.isBlank()) "Bütün item'lar bu adla başlar; archive.org genelinde benzersiz olmalı. Ör. gflix-ahmet"
                else "Item'lar: $prefix-katalog, $prefix-movie-…, $prefix-series-…-s01 · İzleyici telefonlara bu adı gir: $prefix",
                Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            AdminSection("TMDB")
            OutlinedTextField(
                value = tmdbKey, onValueChange = { tmdbKey = it }, label = { Text("TMDB anahtarı") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Hint("Eşleştirme, ad/özet/tür/oyuncular ve kapaklar için (themoviedb.org → Ayarlar → API).", Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { showGitHub = !showGitHub }) {
                Text(if (showGitHub) "▾ Eski düzen (GitHub)" else "▸ Eski düzen (GitHub)", color = GColors.TextSecondary, fontSize = 13.sp)
            }
            if (showGitHub) {
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, label = { Text("GitHub token (eski düzen, isteğe bağlı)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                Hint("archive.org kullanılırken gerekmez.", Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(24.dp))
            val iaFilled = iaAccess.isNotBlank() && iaSecret.isNotBlank() && prefix.isNotBlank()
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        result = "Kontrol ediliyor…"
                        val check = if (iaFilled) runCatching { com.gflix.app.archive.ArchiveClient(iaAccess.trim(), iaSecret.trim()).checkKeys() }
                        else Result.success(Unit)
                        if (check.isSuccess) {
                            repo.store.update {
                                it.copy(
                                    iaAccess = if (iaFilled) com.gflix.app.archive.SecretBox.seal(iaAccess.trim()) else null,
                                    iaSecret = if (iaFilled) com.gflix.app.archive.SecretBox.seal(iaSecret.trim()) else null,
                                    iaPrefix = prefix.ifBlank { null },
                                    writeToken = token.trim().ifBlank { null },
                                    tmdbKey = tmdbKey.trim().ifBlank { null },
                                )
                            }
                            if (iaFilled) {
                                com.gflix.app.archive.ArchiveSource.save(context, prefix)
                                context.appContainer.catalogRepository.refresh()
                            }
                            result = if (iaFilled) "✓ Kaydedildi. archive.org anahtarları çalışıyor." else "✓ Kaydedildi."
                            if (!firstTime) onBack()
                        } else {
                            result = check.exceptionOrNull()?.message ?: "archive.org anahtarları doğrulanamadı."
                        }
                        busy = false
                    }
                },
                enabled = loaded && !busy && (iaFilled || token.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
            ) { Text("Kaydet", fontWeight = FontWeight.Bold) }
            if (result.isNotEmpty()) Text(result, color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            if (!firstTime) {
                Spacer(Modifier.height(32.dp))
                TextButton(onClick = { scope.launch { repo.store.update { it.copy(writeToken = null, iaAccess = null, iaSecret = null) }; onBack() } }) {
                    Text("Yönetici anahtarlarını bu telefondan sil", color = Color(0xFFFF6B6B))
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

/** Arşiv özeti: kaç film/dizi/sezon/bölüm, toplam süre ve boyut. */
/** 🔗 Link ekle: YouTube ya da sayfa linki → bilgisayar başlığından yapımı/bölümü bulur, kaynak olarak kaydeder. */
@Composable
private fun AddLinkButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Button(
        onClick = { open = true; msg = null }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
    ) { Text("🔗  Link ekle  ·  YouTube ya da sayfa linki", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    if (!open) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        containerColor = GColors.Surface,
        title = { Text("Link ekle", color = Color.White) },
        text = {
            Column {
                Text("YouTube ya da sitedeki izleme sayfasının linkini yapıştır; birden çok linki alt alta yazabilirsin. YouTube oynatma " +
                    "listesi linki (youtube.com/playlist?list=…) verirsen içindeki bütün videolar sırayla gelir. Hepsi önce " +
                    "'Onay bekleyenler'e düşer. Site koruma istiyorsa Sayfadan Yakala'yı kullan.",
                    color = GColors.TextSecondary, fontSize = 13.sp)
                androidx.compose.material3.OutlinedTextField(link, { link = it }, label = { Text("Link(ler)") }, minLines = 3, maxLines = 8)
                msg?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !busy && link.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val links = link.split(Regex("\\s+")).map { it.trim() }.filter { it.startsWith("http") }.distinct()
                    var ok = 0
                    val errs = mutableListOf<String>()
                    links.forEachIndexed { i, l ->
                        msg = "Gönderiliyor ${i + 1}/${links.size}…"
                        runCatching { com.gflix.app.data.PcServer.sendSource(l, "") }.onSuccess { ok++ }.onFailure { errs += it.message.orEmpty() }
                    }
                    msg = (if (links.isEmpty()) "Link bulunamadı" else "✓ $ok link onaya gönderildi") +
                        if (errs.isNotEmpty()) "\n✗ ${errs.size}: ${errs.distinct().joinToString("; ")}" else ""
                    busy = false
                    if (ok > 0) { link = ""; runCatching { com.gflix.app.data.LinkAlerts.check(context) } }
                }
            }) { Text(if (busy) "Ekleniyor…" else "Ekle") }
        },
        dismissButton = { androidx.compose.material3.TextButton(enabled = !busy, onClick = { open = false }) { Text("Kapat") } },
    )
}

/** Onay bekleyen bir link (bilgisayarın tahminiyle). */
private data class PendingLink(val pid: String, val url: String, val page: String, val label: String, val ok: Boolean,
                               val reason: String, val kind: String, val season: Int?, val episode: Int?)

private fun parsePending(o: kotlinx.serialization.json.JsonObject): List<PendingLink> =
    (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
        val x = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val g = x["guess"] as? kotlinx.serialization.json.JsonObject
        fun s(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it != "null" }.orEmpty()
        PendingLink(s(x, "pid"), s(x, "url"), s(x, "page"), s(g, "label").ifBlank { s(g, "query") }, s(g, "ok") == "true",
            s(g, "reason"), s(g, "kind"), s(g, "season").toIntOrNull(), s(g, "episode").toIntOrNull())
    }

/** 📥 Onay bekleyenler: Sayfadan Yakala / Link ekle ile gelenler; TMDB ile eşleştirilip onaylanınca yayına girer. */
@Composable
private fun PendingCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<PendingLink>>(emptyList()) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var matchOpen by remember { mutableStateOf(false) }
    suspend fun load() {
        runCatching { com.gflix.app.data.PcServer.pending() }.onSuccess { o ->
            items = parsePending(o)
            selected.retainAll(items.map { it.pid }.toSet())
        }
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(20_000) } }
    fun approve(tmdb: String?, season: Int?, episode: Int?, start: Int? = null) {
        busy = true
        scope.launch {
            msg = runCatching { com.gflix.app.data.PcServer.approve(selected.toList(), tmdb, season, episode, start) }.fold({ o ->
                val res = (o["results"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                val ok = res.count { (it["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" }
                val errs = res.mapNotNull { (it["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content }.distinct()
                "✓ $ok yayına alındı" + if (errs.isNotEmpty()) " · ✗ ${res.size - ok}: ${errs.joinToString("; ")}" else ""
            }, { "✗ ${it.message}" })
            load(); busy = false
            context.appContainer.catalogRepository.refresh()
        }
    }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF14283A)).padding(14.dp)) {
        Text("📥 Onay bekleyenler (${items.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Onaylanmadan yayınlanmaz. Seç → Onayla (tahmin doğruysa) ya da TMDB ile eşleştir (birden çok link aynı yapıma).",
            color = GColors.TextSecondary, fontSize = 12.sp)
        items.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { if (p.pid in selected) selected.remove(p.pid) else selected.add(p.pid) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = p.pid in selected, onCheckedChange = { if (it) selected.add(p.pid) else selected.remove(p.pid) })
                Column(Modifier.weight(1f)) {
                    Text((if (p.ok) "✓ " else "⚠ ") + p.label.ifBlank { "Tanınmadı" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (!p.ok && p.reason.isNotBlank()) Text(p.reason, color = Color(0xFFFFB86B), fontSize = 12.sp)
                    Text(p.page.ifBlank { p.url }, color = GColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val sel = items.filter { it.pid in selected }
            TextButton(enabled = !busy && sel.isNotEmpty() && sel.all { it.ok }, onClick = { approve(null, null, null) }) {
                Text("Onayla", color = Color.White, fontSize = 13.sp)
            }
            TextButton(enabled = !busy && sel.isNotEmpty(), onClick = { matchOpen = true }) { Text("TMDB ile eşleştir", color = Color.White, fontSize = 13.sp) }
            TextButton(enabled = !busy && sel.isNotEmpty(), onClick = {
                busy = true
                scope.launch { runCatching { com.gflix.app.data.PcServer.reject(selected.toList()) }; load(); busy = false }
            }) { Text("Sil", color = Color(0xFFFF6B6B), fontSize = 13.sp) }
        }
        if (items.size > 1) TextButton(onClick = { if (selected.size == items.size) selected.clear() else { selected.clear(); selected.addAll(items.map { it.pid }) } }) {
            Text(if (selected.size == items.size) "Seçimi kaldır" else "Hepsini seç", color = GColors.TextSecondary, fontSize = 12.sp)
        }
        msg?.let { Text(it, color = Color.White, fontSize = 12.sp) }
    }
    if (matchOpen) {
        val single = items.filter { it.pid in selected }.singleOrNull()
        TmdbMatchDialog(single, selected.size, onDismiss = { matchOpen = false }) { tmdb, s, e, st -> matchOpen = false; approve(tmdb, s, e, st) }
    }
}

/** TMDB ile eşleştir: adla ara ya da TMDB linki yapıştır; dizide (tek link seçiliyse) sezon/bölüm elle verilebilir. */
@Composable
private fun TmdbMatchDialog(single: PendingLink?, count: Int, onDismiss: () -> Unit, onDone: (String, Int?, Int?, Int?) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(single?.label?.substringBefore(" S0")?.substringBefore(" S1").orEmpty()) }
    var tmdb by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }   // (tmdb, ad, tür)
    var season by remember { mutableStateOf(single?.season?.toString().orEmpty()) }
    var episode by remember { mutableStateOf(single?.episode?.toString().orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }
    var inOrder by remember { mutableStateOf(false) }
    var startNo by remember { mutableStateOf("1") }
    val isTv = tmdb.contains("tv", ignoreCase = true)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GColors.Surface,
        title = { Text(if (count > 1) "$count linki TMDB ile eşleştir" else "TMDB ile eşleştir", color = Color.White) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(q, { q = it }, label = { Text("Adla ara") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { com.gflix.app.data.PcServer.tmdbSearch(q) }.onSuccess { o ->
                                    results = (o["results"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                                        val x = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                                        fun s(k: String) = (x[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
                                        Triple(s("tmdb"), s("title") + s("year").let { if (it.isNotBlank()) " ($it)" else "" }, if (s("kind") == "tv") "Dizi" else "Film")
                                    }
                                    err = if (results.isEmpty()) "Sonuç yok" else null
                                }.onFailure { err = it.message }
                            }
                        }) { Text("Ara") }
                    }
                }
                items(results.size) { i ->
                    val (t, name, kind) = results[i]
                    Text((if (tmdb == t) "● " else "○ ") + "$name · $kind", color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { tmdb = t; chosen = name }.padding(vertical = 6.dp))
                }
                item {
                    androidx.compose.material3.OutlinedTextField(tmdb, { tmdb = it; chosen = "" }, label = { Text("ya da TMDB linki (movie/… ya da tv/…)") }, singleLine = true)
                    if (isTv && count == 1) Row {
                        androidx.compose.material3.OutlinedTextField(season, { season = it.filter(Char::isDigit) }, label = { Text("Sezon") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.OutlinedTextField(episode, { episode = it.filter(Char::isDigit) }, label = { Text("Bölüm") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    if (isTv && count > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { inOrder = !inOrder }) {
                            androidx.compose.material3.Checkbox(checked = inOrder, onCheckedChange = { inOrder = it })
                            Text("Sırayla numarala (başlıkta bölüm no yoksa; liste/eklenme sırasıyla)", color = Color.White, fontSize = 13.sp)
                        }
                        if (inOrder) Row {
                            androidx.compose.material3.OutlinedTextField(season, { season = it.filter(Char::isDigit) }, label = { Text("Sezon") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.OutlinedTextField(startNo, { startNo = it.filter(Char::isDigit) }, label = { Text("İlk bölüm") }, singleLine = true, modifier = Modifier.weight(1f))
                        } else Text("Her link kendi başlığındaki sezon/bölüm numarasıyla yerleşir.", color = GColors.TextSecondary, fontSize = 12.sp)
                    }
                    err?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = tmdb.isNotBlank() && (!isTv || (count > 1 && (!inOrder || (season.isNotBlank() && startNo.isNotBlank()))) ||
                    (season.isNotBlank() && episode.isNotBlank())), onClick = {
                if (isTv && count > 1 && inOrder) onDone(tmdb.trim(), season.toIntOrNull(), null, startNo.toIntOrNull() ?: 1)
                else onDone(tmdb.trim(), season.toIntOrNull(), episode.toIntOrNull(), null)
            }) { Text(if (chosen.isNotBlank()) "Yayına al: $chosen" else "Yayına al") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** ⚠ Ölü linkler: bilgisayarın günlük kontrolünde ulaşılamayanlar (sayfa yok, site kapalı, YouTube videosu kaldırılmış). */
@Composable
private fun DeadLinksCard() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<com.gflix.app.data.LinkAlerts.Item>>(emptyList()) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    suspend fun load() { runCatching { com.gflix.app.data.PcServer.alerts() }.onSuccess { items = com.gflix.app.data.LinkAlerts.parse(it) } }
    LaunchedEffect(Unit) { while (true) { load(); delay(60_000) } }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A1414)).padding(14.dp)) {
        Text("⚠ Çalışmayan linkler (${items.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Sayfadan Yakala ya da Link ekle ile yenisini ver; fragmansa ⚑ ile düzelt.", color = GColors.TextSecondary, fontSize = 12.sp)
        items.forEach { a ->
            Spacer(Modifier.height(8.dp))
            Text("${a.title} · ${a.what}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(a.detail, color = GColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
            Row {
                TextButton(enabled = busyKey == null, onClick = {
                    busyKey = a.key
                    scope.launch { runCatching { com.gflix.app.data.PcServer.alertAction(a.key, "recheck") }; load(); busyKey = null }
                }) { Text(if (busyKey == a.key) "Kontrol ediliyor…" else "Tekrar kontrol et", color = Color.White, fontSize = 13.sp) }
                TextButton(enabled = busyKey == null, onClick = {
                    scope.launch { runCatching { com.gflix.app.data.PcServer.alertAction(a.key, "ignore") }; load() }
                }) { Text("Gizle", color = GColors.TextSecondary, fontSize = 13.sp) }
            }
        }
    }
}

/** Yeni düzenin özeti (bilgisayardan): yapım sayıları, oynatılabilenler, izleme ve bu bilgisayardan geçen veri. */
@Composable
private fun PcStatsCard() {
    var st by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { com.gflix.app.data.PcServer.stats() }.onSuccess { st = it; err = null }.onFailure {
                err = if ((it as? com.gflix.app.data.PcException)?.code == 404)
                    "Bilgisayar eski sürümde; 15 dk içinde kendini günceller (hemen: Hasan'da git pull + servisi yeniden başlat)."
                else it.message
            }
            delay(30_000)
        }
    }
    fun n(k: String): Long = st?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 0L
    val size = com.gflix.app.data.CatalogStats::size
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C1C)).padding(14.dp)) {
        // Yayındaki içeriğin toplam boyutu (dosya olsaydı): bilgisayar her yapımı bir kez parça parça ölçer
        val exact = n("contentExact")
        val items = n("contentItems")
        Row(verticalAlignment = Alignment.Bottom) {
            Text((if (exact < items) "≈ " else "") + size(n("contentBytes")), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text("içerik · ${n("contentMinutes") / 60} saat", color = GColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(if (items == 0L) "Henüz yayında içerik yok"
            else if (exact >= items) "📏 Hepsi kesin ölçüldü ($items film/bölüm)"
            else "📏 $exact/$items kesin ölçüldü · kalanlar kimse izlemiyorken ölçülüyor (ölçülmeyenler tahmini)",
            color = GColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf(n("movies") to "Film", n("series") to "Dizi", n("seasons") to "Sezon", n("episodes") to "Bölüm").forEach { (v, l) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$v", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(l, color = GColors.TextSecondary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("▶ Oynatılabilir: ${n("playableMovies")} film · ${n("playableEpisodes")} bölüm · 🎞 ${n("trailers")} fragman" +
            if (n("trailersWrong") > 0) " (${n("trailersWrong")} yanlış atlandı)" else "",
            color = GColors.TextSecondary, fontSize = 12.sp)
        Text("👀 İzleme: bugün ${n("playsToday")} (${size(n("bytesToday"))}) · bu ay ${n("playsMonth")} (${size(n("bytesMonth"))}) · toplam ${n("playsTotal")}",
            color = GColors.TextSecondary, fontSize = 12.sp)
        Text("💾 Bilgisayarda kullanılan alan: ${size(n("ownBytes"))} · ☁️ GitHub'a giden: 0",
            color = GColors.TextSecondary, fontSize = 12.sp)
        val recent = (st?.get("recent") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            ((it as? kotlinx.serialization.json.JsonObject)?.get("title") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
        if (recent.isNotEmpty()) Text("🆕 Son eklenenler: " + recent.joinToString(", "), color = GColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
        if (st == null && err == null) Text("Bilgisayardan alınıyor…", color = GColors.TextMuted, fontSize = 12.sp)
        err?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
    }
}

@Composable
private fun ArchiveStats(st: com.gflix.app.data.CatalogStats) {
    val size = com.gflix.app.data.CatalogStats::size
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C1C)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(size(st.totalBytes), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text("arşiv · ${st.minutes / 60} saat", color = GColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf(st.movies to "Film", st.series to "Dizi", st.seasons to "Sezon", st.episodes to "Bölüm").forEach { (n, l) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$n", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(l, color = GColors.TextSecondary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Filmler + bölümler ${size(st.contentBytes)} · ${st.trailers} fragman ${size(st.trailerBytes)}" + if (st.comingSoon > 0) " · ${st.comingSoon} yakında" else "",
            color = GColors.TextSecondary, fontSize = 12.sp,
        )
    }
}


/** İstek listesinin bilgisayardaki archive.org işlerini görünür kılar; anahtarlar yalnızca bilgisayarda tutulur. */
@Composable
private fun ArchiveJobsCard() {
    var snapshot by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { com.gflix.app.data.PcServer.archiveJobs() }
                .onSuccess { snapshot = it; error = null }
                .onFailure { error = if ((it as? com.gflix.app.data.PcException)?.code == 404)
                    "Bilgisayarın yeni sürümünde indirme kuyruğu görünecek."
                    else it.message }
            delay(15_000)
        }
    }
    val rows = (snapshot?.get("jobs") as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()
    fun field(j: kotlinx.serialization.json.JsonObject, name: String): String =
        (j[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val enabled = (snapshot?.get("enabled") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    val active = rows.count { field(it, "state") in setOf("queued", "downloading", "uploading", "verifying", "catalog", "retry") }
    val done = rows.count { field(it, "state") == "done" }
    val failed = rows.count { field(it, "state") == "failed" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF192632)).padding(12.dp)) {
        Text("📥 İndirme → ☁️ archive.org", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(if (snapshot == null && error == null) "Bilgisayardaki işler kontrol ediliyor…"
            else if (!enabled && snapshot != null) "Otomatik arşivleme kapalı. Bilgisayarda yayın ayarlarından etkinleştir."
            else "$active sırada/işleniyor · $done tamamlandı · $failed başarısız",
            color = GColors.TextSecondary, fontSize = 12.sp)
        rows.filter { field(it, "state") != "done" }.take(3).forEach { j ->
            val name = field(j, "title").ifBlank { field(j, "item_id") }
            val state = when (field(j, "state")) {
                "queued" -> "Sırada"; "downloading" -> "İndiriliyor"
                "uploading" -> "archive.org'a yükleniyor"; "verifying" -> "Doğrulanıyor"
                "catalog" -> "Kataloğa ekleniyor"; "retry" -> "Yeniden denenecek"
                "failed" -> "Başarısız"; else -> "İşleniyor"
            }
            Text("• $name · $state" + field(j, "note").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                color = Color.White, fontSize = 11.sp, maxLines = 2)
        }
        error?.let { Text(it, color = Color(0xFFFFA0A0), fontSize = 11.sp) }
    }
}


/** 🎯 İstek listesi: Stüdyo ana sayfasından doğrudan (özet + aç) */
@Composable
private fun WishHomeButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    remember { CaptureEngine.init(context); 0 }
    var open by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val rev = WishList.rev
    Button(
        onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F3A2A), contentColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("🎯  İstek listesi  ·  TMDB'den seç, indir ve arşivle", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (WishList.items.isNotEmpty()) Text(WishList.summary().removePrefix("🎯 İstek listesi: "), fontSize = 12.sp, color = GColors.TextSecondary)
            if (WishList.now.isNotBlank()) Text(WishList.now, fontSize = 12.sp, color = GColors.Match, maxLines = 1)
            WishList.toReview().size.takeIf { it > 0 }?.let { Text("🔍 $it otomatik eşleşme kontrol bekliyor", fontSize = 12.sp, color = Color(0xFF7FB2FF)) }
        }
    }
    if (WishList.black.isNotBlank()) BlackBanner()
    if (open) WishListDialog(onDismiss = { open = false })
}


private data class NewsItem(val key: String, val title: String, val original: String, val year: Int?, val tmdb: String, val season: Int, val text: String)

/** 🆕 Duyurular: yayındaki dizilere TMDB'de yeni sezon/bölüm geldi → tek dokunuşla İstek listesine */
@Composable
private fun NewsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { com.gflix.app.data.PcServer.news() }.onSuccess { o ->
            items = (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun s(k: String) = (x[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
                NewsItem(s("key"), s("title"), s("original"), s("year").toIntOrNull(), s("tmdb"), s("season").toIntOrNull() ?: 0, s("text"))
            }
        }
    }
    if (items.isEmpty() && msg == null) return
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B2433)).padding(12.dp)) {
        Text("🆕 Duyurular (${items.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        msg?.let { Text(it, color = GColors.Match, fontSize = 12.sp) }
        items.forEach { n ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("📺 ${n.title} — ${n.text}", color = Color.White, fontSize = 13.sp)
                Row {
                    TextButton(onClick = {
                        CaptureEngine.init(context)
                        WishList.add(n.tmdb, "tv", n.title, n.original, n.year, listOf(n.season))
                        scope.launch { runCatching { com.gflix.app.data.PcServer.newsDone(n.key) } }
                        items = items - n
                        msg = "🎯 ${n.title} ${n.season}. sezon İstek listesine eklendi"
                    }) { Text("🎯 İstek listesine ekle", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    TextButton(onClick = {
                        scope.launch { runCatching { com.gflix.app.data.PcServer.newsDone(n.key) } }
                        items = items - n
                    }) { Text("Tamam", color = GColors.TextSecondary, fontSize = 12.sp) }
                }
            }
        }
    }
}
