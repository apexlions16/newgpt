package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.appContainer
import com.gflix.app.data.Library
import com.gflix.app.data.PcServer
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch

/**
 * Stüdyo → Yakında: dosyası olmayan yapımı duyur (TMDB bilgileri + isteğe bağlı yayın zamanı), sonra fragmanını yükle.
 * Film/bölüm dosyası sonradan aynı yapıma yüklenince yayın zamanına kadar gizli kalır.
 */
@Composable
fun AdminSoonScreen(
    onBack: () -> Unit, onUploadTrailer: (String) -> Unit, onEditTitle: (String) -> Unit,
    /** (kayıt, yol): "file" Telefondan dosya · "link" Linkten ekle · "capture" Sayfadan yakala */
    onAddMedia: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    val raw by context.appContainer.catalogRepository.catalog.collectAsState()
    var tmdb by remember { mutableStateOf<Tmdb?>(null) }
    LaunchedEffect(Unit) { tmdb = repo.tmdb() }

    var series by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var tmdbId by remember { mutableStateOf<Int?>(null) }
    var year by remember { mutableStateOf<Int?>(null) }
    var poster by remember { mutableStateOf<String?>(null) }
    var releaseAt by remember { mutableStateOf<Long?>(null) }
    var picker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<String?>(null) }
    val soon = remember(raw) { Library.comingSoon(raw, null) }
    var mediaChoice by remember { mutableStateOf<com.gflix.app.data.Title?>(null) }
    var openWhen by remember { mutableStateOf<com.gflix.app.data.Title?>(null) }
    openWhen?.let { t -> OpenWhenDialog(t, onDismiss = { openWhen = null }) }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AdminTopBar("Yakında Gelecekler", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Hint("Dosyası henüz olmayan bir film ya da diziyi duyur. Uygulamada \"Yeni ve Popüler → Yakında\" sekmesinde ve ana sayfadaki \"Yakında\" satırında fragmanıyla görünür. Tarih verirsen o saatte kendiliğinden yayına girer (o zamana kadar dosyasını yüklemiş olman yeter).")
                Spacer(Modifier.height(12.dp))
                Row {
                    listOf(false to "Film", true to "Dizi").forEach { (v, l) ->
                        Text(
                            l, color = if (series == v) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (series == v) Color.White else Color(0xFF333333))
                                .clickable { series = v; tmdbId = null; poster = null }.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    poster?.let {
                        AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.width(54.dp).height(80.dp).clip(RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                    }
                    OutlinedTextField(
                        title, { title = it; tmdbId = null; poster = null }, label = { Text("Ad") }, singleLine = true, colors = gTextFieldColors(),
                        trailingIcon = if (tmdb != null) ({ IconButton(onClick = { picker = true }) { Icon(Icons.Filled.Search, "TMDB'de ara", tint = Color.White) } }) else null,
                        modifier = Modifier.weight(1f),
                    )
                }
                Hint(
                    when {
                        tmdbId != null -> "TMDB #$tmdbId${year?.let { " · $it" } ?: ""} — özet, kapak ve logo otomatik gelir."
                        tmdb != null -> "Büyütece basıp TMDB'den seç: özet, kapak ve logolar otomatik gelir."
                        else -> "TMDB anahtarı yoksa sadece adla eklenir (Stüdyo Ayarları'ndan girebilirsin)."
                    },
                    Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                ReleaseTimeField(releaseAt, { releaseAt = it }, label = "Yayın zamanı (boşsa \"Yakında\" yazar)")
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (title.isBlank()) { message = "Ad gerekli."; return@Button }
                        busy = true; message = null
                        scope.launch {
                            runCatching { repo.addComingSoon(Skeleton(title.trim(), series, tmdbId, year), releaseAt) }
                                .onSuccess { created = it; message = "✓ \"${title.trim()}\" Yakında'ya eklendi. Katalog 1-3 dakika içinde güncellenir. Şimdi fragmanını yükleyebilirsin." }
                                .onFailure { message = "Eklenemedi: ${it.message}" }
                            busy = false
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
                ) { Text(if (busy) "Ekleniyor…" else "Yakında'ya Ekle", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                message?.let { Text(it, color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                created?.let { id ->
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onUploadTrailer(id) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) { Text("🎬  Fragmanını Yükle", fontWeight = FontWeight.Bold) }
                }
                if (soon.isNotEmpty()) AdminSection("Şu an Yakında'da")
            }
            items(soon, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { onEditTitle(t.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.width(46.dp).height(69.dp).clip(RoundedCornerShape(3.dp)).background(GColors.Card))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        val at = Library.nextRelease(t)
                        Hint(Library.releaseText(at, t.isSeries, newEpisode = !Library.isUpcoming(t) && Library.hasPlayable(t)))
                        Hint(
                            listOfNotNull(
                                if (t.trailer != null || t.trailers.isNotEmpty()) "fragman var" else "fragman yok",
                                if (Library.hasPlayable(t)) "video yüklü" else "video yok",
                            ).joinToString(" · "),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (PcServer.configured && t.tmdbId != null) {
                            var listed by remember(t.id) { mutableStateOf(WishList.inList(t)) }
                            Text(if (listed) "✓ Listede" else "🎯 Listeye ekle", color = if (listed) GColors.Match else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (listed) Color(0xFF333333) else Color.White)
                                    .clickable(enabled = !listed) { android.widget.Toast.makeText(context, WishList.addSoon(t), android.widget.Toast.LENGTH_LONG).show(); listed = WishList.inList(t) }.padding(horizontal = 10.dp, vertical = 6.dp))
                            Spacer(Modifier.height(6.dp))
                        }
                        Text("+ Medya ekle", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)).clickable { mediaChoice = t }.padding(horizontal = 10.dp, vertical = 6.dp))
                        if (t.isSeries) {
                            Spacer(Modifier.height(6.dp))
                            Text("📦 Açılış", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)).clickable { openWhen = t }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Düzenle ›", color = GColors.TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    mediaChoice?.let { t ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { mediaChoice = null },
            containerColor = Color(0xFF1F1F1F),
            title = { Text("${t.title} için medya ekle", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Hint(if (t.isSeries) "Dosyalar sıradaki bölüme eklenir (Türkçe + İngilizce video, altyazılar)." else "Türkçe ve İngilizce videoyu, sonra altyazıları eklersin; hepsi bu filme gider.")
                    Spacer(Modifier.height(12.dp))
                    listOf("🌐  Sayfadan yakala" to "capture", "📁  Telefondan dosya seç" to "file", "🔗  Linkten ekle" to "link").forEach { (l, way) ->
                        Text(l, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(4.dp)).background(Color.White)
                                .clickable { mediaChoice = null; onAddMedia(t.id, way) }.padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                    Hint("Sayfadan yakala: uygulama sitede \"${t.title}\" diye arar; diziyse sezon/bölümleri seçip başlatırsın, sıra arka planda sürer.", Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { mediaChoice = null }) { Text("Vazgeç", color = GColors.TextSecondary) } },
        )
    }
    if (picker) tmdb?.let { t ->
        TmdbPickerDialog(t, title, series, null, onPick = { r ->
            title = r.title; tmdbId = r.id; year = r.year; poster = r.poster; series = r.kind == "tv"; picker = false
        }, onDismiss = { picker = false })
    }
}


/**
 * Yakında'daki dizi için açılış: her sezonun ilk KAÇ bölümü tamamen yüklenince sisteme gelsin. O sayıya ulaşana kadar
 * sezonun hiçbir bölümü görünmez, tamamlanınca hepsi birlikte açılır ("Hepsi yüklenince birlikte aç" planı).
 */
@Composable
fun OpenWhenDialog(t: com.gflix.app.data.Title, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    var seasons by remember { mutableStateOf(t.tmdbSeasons.filter { it.n > 0 }.map { it.n to it.c }) }
    LaunchedEffect(t.id) {
        if (seasons.isEmpty()) {
            val tm = repo.tmdb()
            val id = t.tmdbId
            if (tm != null && id != null) seasons = runCatching { tm.seasons(id) }.getOrDefault(emptyList()).filter { it.first > 0 }
            if (seasons.isEmpty()) seasons = listOf(1 to 0)
        }
    }
    val current = remember(t) { t.releasePlan.filter { it.whenComplete && it.from == 1 }.associate { it.season to (it.to ?: 0) } }
    var season by remember { mutableStateOf(current.keys.minOrNull() ?: 1) }
    var count by remember(season) { mutableStateOf((current[season] ?: seasons.firstOrNull { it.first == season }?.second ?: 0).takeIf { it > 0 }?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1F1F1F),
        title = { Text("📦 ${t.title} ne zaman açılsın?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column {
                Hint("Seçtiğin sezonun ilk kaç bölümü tamamen yüklenince sisteme gelsin? O sayıya kadar hiçbir bölüm görünmez; hepsi hazır olunca birlikte açılır.")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    seasons.map { it.first }.distinct().sorted().forEach { n ->
                        val sel = n == season
                        Text("$n. Sezon" + (current[n]?.let { " · $it" } ?: ""), color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color(0xFF333333))
                                .clickable { season = n }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                val total = seasons.firstOrNull { it.first == season }?.second ?: 0
                OutlinedTextField(count, { v -> count = v.filter(Char::isDigit).take(4) }, singleLine = true, colors = gTextFieldColors(),
                    label = { Text("Kaç bölüm yüklenince açılsın" + (if (total > 0) " (TMDB: $total bölüm)" else "")) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())
                if (total > 0) Row(Modifier.padding(top = 6.dp)) {
                    listOf(total, (total + 1) / 2, 3, 1).filter { it in 1..total }.distinct().forEach { n ->
                        Text(if (n == total) "Hepsi ($n)" else "$n", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF333333)).clickable { count = n.toString() }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                current[season]?.let { Hint("Şu an: $season. sezon $it bölüm yüklenince açılacak.", Modifier.padding(top = 6.dp)) }
                msg?.let { Text(it, color = if (it.startsWith("✓")) GColors.Match else Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !busy && (count.toIntOrNull() ?: 0) > 0, onClick = {
                busy = true; msg = null
                scope.launch {
                    runCatching { repo.setOpenWhen(t.id, season, count.toInt()) }
                        .onSuccess { msg = "✓ Kaydedildi: $season. sezon ${count} bölüm yüklenince açılacak. Katalog 1-3 dk içinde güncellenir." }
                        .onFailure { msg = "Kaydedilemedi: ${it.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Kaydediliyor…" else "Kaydet", color = Color.White, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (current.containsKey(season)) androidx.compose.material3.TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { repo.setOpenWhen(t.id, season, null) }
                            .onSuccess { msg = "✓ $season. sezonun açılış koşulu kaldırıldı (bölümler yüklendikçe açılır)." }
                            .onFailure { msg = "Kaldırılamadı: ${it.message}" }
                        busy = false
                    }
                }) { Text("Koşulu kaldır", color = GColors.TextSecondary) }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Kapat", color = GColors.TextSecondary) }
            }
        },
    )
}
