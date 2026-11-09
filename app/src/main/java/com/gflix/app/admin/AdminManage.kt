package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.appContainer
import com.gflix.app.ui.components.GLoading
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val ART_KINDS = listOf("posters" to "Kapaklar", "backdrops" to "Arka planlar", "logos" to "Logolar")

/** Bir görsel türü için sabitle/gizle tercihi (catalog item "art" alanı). */
private class ArtPref(var pin: String? = null, val hidden: MutableSet<String> = mutableSetOf()) {
    fun toJson(): JsonObject? {
        val m = mutableMapOf<String, JsonElement>()
        pin?.let { m["pin"] = JsonPrimitive(it) }
        if (hidden.isNotEmpty()) m["hidden"] = JsonArray(hidden.sorted().map { JsonPrimitive(it) })
        return if (m.isEmpty()) null else JsonObject(m)
    }
}

@Composable
fun AdminManageScreen(titleId: String, onBack: () -> Unit, onUploadTrailer: (String) -> Unit) {
    val ctx = LocalContext.current
    val adminStore = remember { AdminStore(ctx) }
    val archiveMode by adminStore.state.collectAsState(initial = null)
    when (archiveMode?.archive) {
        null -> return
        true -> { ArchiveManageScreen(titleId, onBack, onUploadTrailer); return }
        false -> Unit
    }
    LegacyManageScreen(titleId, onBack, onUploadTrailer)
}

@Composable
private fun LegacyManageScreen(titleId: String, onBack: () -> Unit, onUploadTrailer: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    val catalog by context.appContainer.catalogRepository.catalog.collectAsState()
    val entry = catalog?.titles?.firstOrNull { it.id == titleId }

    var item by remember { mutableStateOf<JsonObject?>(null) }
    var sha by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Yükleniyor…") }
    var tab by remember { mutableIntStateOf(0) }
    var trName by remember { mutableStateOf("") }
    var enName by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf("") }
    var tmdbId by remember { mutableStateOf(entry?.tmdbId?.toString().orEmpty()) }
    val prefs = remember { mutableStateMapOf<String, ArtPref>() }
    var artVersion by remember { mutableIntStateOf(0) }
    var images by remember { mutableStateOf<Map<String, List<TmdbImage>>>(emptyMap()) }
    var artKind by remember { mutableStateOf("posters") }
    var selected by remember { mutableStateOf<String?>(null) }
    var tmdb by remember { mutableStateOf<Tmdb?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

    suspend fun load() {
        runCatching { repo.gh().getItem(titleId) }.onSuccess { (it, h) ->
            item = it; sha = h
            if (it == null) { status = "Kayıt bulunamadı (silinmiş olabilir)."; return }
            val ov = it["overrides"] as? JsonObject
            trName = ov?.s("title").orEmpty()
            enName = (ov?.get("names") as? JsonObject)?.s("en").orEmpty()
            overview = ov?.s("overview").orEmpty()
            it.s("tmdbId")?.let { id -> tmdbId = id }
            val art = it["art"] as? JsonObject
            ART_KINDS.forEach { (k, _) ->
                val o = art?.get(k) as? JsonObject
                prefs[k] = ArtPref(o?.s("pin"), (o?.get("hidden") as? JsonArray)?.map { e -> e.jsonPrimitive.content }?.toMutableSet() ?: mutableSetOf())
            }
            status = ""
        }.onFailure { status = "Hata: ${it.message}" }
    }

    LaunchedEffect(titleId) {
        tmdb = repo.tmdb()
        load()
        val t = tmdb
        val id = entry?.tmdbId
        if (t != null && id != null) runCatching { images = t.images(if (entry.isSeries) "tv" else "movie", id) }
    }

    fun save() {
        val it = item ?: return
        busy = true
        scope.launch {
            val map = it.toMutableMap()
            val ov = (it["overrides"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            if (trName.isNotBlank()) ov["title"] = JsonPrimitive(trName.trim()) else ov.remove("title")
            if (overview.isNotBlank()) ov["overview"] = JsonPrimitive(overview.trim()) else ov.remove("overview")
            val names = (ov["names"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            if (enName.isNotBlank()) names["en"] = JsonPrimitive(enName.trim()) else names.remove("en")
            if (names.isNotEmpty()) ov["names"] = JsonObject(names) else ov.remove("names")
            if (ov.isNotEmpty()) map["overrides"] = JsonObject(ov) else map.remove("overrides")
            tmdbId.trim().toIntOrNull()?.let { id -> map["tmdbId"] = JsonPrimitive(id) }
            val art = prefs.mapNotNull { (k, p) -> p.toJson()?.let { k to it } }.toMap()
            if (art.isNotEmpty()) map["art"] = JsonObject(art) else map.remove("art")
            val updated = JsonObject(map)
            runCatching { repo.gh().putItem(updated, sha, "Düzenlendi (telefon): ${entry?.title ?: titleId}") }
                .onSuccess { newSha -> item = updated; sha = newSha; status = "✓ Kaydedildi. Katalog 1-3 dakika içinde güncellenir." }
                .onFailure { e -> status = "Kaydedilemedi: ${e.message}" }
            busy = false
        }
    }

    val tabs = buildList { add("Bilgiler"); add("Kapaklar"); add("Fragman"); if (entry?.isSeries == true) add("Bölümler") }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AdminTopBar(entry?.title ?: titleId, onBack) {
            TextButton(onClick = ::save, enabled = item != null && !busy) { Text("Kaydet", color = if (item != null) Color.White else GColors.TextMuted, fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            tabs.forEachIndexed { i, label ->
                val sel = i == tab
                Column(Modifier.clickable { tab = i }.padding(end = 18.dp)) {
                    Box(Modifier.width(if (sel) 60.dp else 0.dp).height(3.dp).background(GColors.Red))
                    Text(label, color = if (sel) Color.White else GColors.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (item == null && status.startsWith("Yükleniyor")) { GLoading(Modifier.fillMaxSize()); return@Column }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tabs.getOrNull(tab)) {
                "Bilgiler" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (entry != null) {
                        Hint(listOfNotNull(entry.names["en"], entry.names["original"]).distinct().filter { it != entry.title }.joinToString(" · ").ifBlank { " " })
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(trName, { trName = it }, label = { Text("Türkçe ad (elle)") }, placeholder = { Text("Boşsa TMDB'deki ad") },
                        singleLine = true, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(enName, { enName = it }, label = { Text("İngilizce ad (elle)") }, placeholder = { Text("Boşsa TMDB'deki ad") },
                        singleLine = true, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(overview, { overview = it }, label = { Text("Özet (elle)") }, placeholder = { Text("Boşsa TMDB'deki Türkçe özet") },
                        minLines = 4, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(tmdbId, { tmdbId = it.filter(Char::isDigit) }, label = { Text("TMDB numarası") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = gTextFieldColors(),
                        trailingIcon = if (tmdb != null) ({ IconButton(onClick = { showPicker = true }) { Icon(Icons.Filled.Search, "Ara", tint = Color.White) } }) else null,
                        modifier = Modifier.fillMaxWidth())
                    Hint("Yanlış film/dizi eşleştiyse düzelt. Kaydettikten sonra bilgiler ve kapaklar yeniden çekilir.", Modifier.padding(top = 4.dp))
                    if (entry?.isSeries == true) {
                        Spacer(Modifier.height(18.dp))
                        PlanSection(item, busy, entry.tmdbId, tmdb, entry.seasons.maxOfOrNull { it.number } ?: 1, entry.releasePlan) { plan, note ->
                            busy = true
                            scope.launch {
                                runCatching {
                                    val (fresh, h) = repo.gh().getItem(titleId)
                                    val base = fresh ?: throw IllegalStateException("Kayıt bulunamadı")
                                    repo.gh().putItem(JsonObject(base.toMutableMap().apply {
                                        if (plan.isEmpty()) remove("releasePlan") else put("releasePlan", JsonArray(plan))
                                        if (note.isBlank()) remove("releaseNote") else put("releaseNote", JsonPrimitive(note.trim()))
                                    }), h, "Yayın planı: ${entry.title}")
                                }.onSuccess { load(); status = "✓ Yayın planı kaydedildi. Katalog 1-3 dakika içinde güncellenir." }
                                    .onFailure { e -> status = "Kaydedilemedi: ${e.message}" }
                                busy = false
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    NotesSection(item, busy) { notes, msg ->
                        busy = true
                        scope.launch {
                            runCatching {
                                val (fresh, h) = repo.gh().getItem(titleId)
                                val base = fresh ?: throw IllegalStateException("Kayıt bulunamadı")
                                repo.gh().putItem(JsonObject(base.toMutableMap().apply {
                                    if (notes.isEmpty()) remove("notes") else put("notes", JsonArray(notes))
                                }), h, "$msg: ${entry?.title ?: titleId}")
                            }.onSuccess { load(); status = "✓ $msg. Katalog 1-3 dakika içinde güncellenir." }
                                .onFailure { e -> status = "Kaydedilemedi: ${e.message}" }
                            busy = false
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    BadgesSection(item, busy) { badges ->
                        busy = true
                        scope.launch {
                            runCatching {
                                val (fresh, h) = repo.gh().getItem(titleId)
                                val base = fresh ?: throw IllegalStateException("Kayıt bulunamadı")
                                repo.gh().putItem(JsonObject(base.toMutableMap().apply { if (badges == null) remove("badges") else put("badges", badges) }), h, "Kalite/ses işareti: ${entry?.title ?: titleId}")
                            }.onSuccess { load(); status = "✓ Kaydedildi. Katalog 1-3 dakika içinde güncellenir." }
                                .onFailure { e -> status = "Kaydedilemedi: ${e.message}" }
                            busy = false
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    ReleaseTimeField(
                        value = (item?.get("releaseAt") as? JsonPrimitive)?.content?.toLongOrNull(),
                        onChange = { at ->
                            busy = true
                            scope.launch {
                                runCatching { repo.setRelease(titleId, at) }
                                    .onSuccess { load(); status = if (at != null) "✓ Zamanlandı. Katalog 1-3 dakika içinde güncellenir." else "✓ Hemen yayında. Katalog 1-3 dakika içinde güncellenir." }
                                    .onFailure { e -> status = "Kaydedilemedi: ${e.message}" }
                                busy = false
                            }
                        },
                    )
                    if (!entry?.overview.isNullOrBlank()) {
                        AdminSection("Şu anki özet")
                        Text(entry!!.overview, color = GColors.TextSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(28.dp))
                    OutlinedButton(onClick = {
                        confirm = "\"${entry?.title ?: titleId}\" ve tüm video dosyaları kalıcı olarak silinsin mi?" to suspend {
                            repo.deleteTitle(titleId); context.appContainer.catalogRepository.refresh(); onBack()
                        }
                    }, shape = RoundedCornerShape(4.dp), modifier = Modifier.navigationBarsPadding()) {
                        Text("Yapımı Sil", color = Color(0xFFFF6B6B))
                    }
                }
                "Kapaklar" -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ART_KINDS.forEach { (k, label) ->
                            val sel = artKind == k
                            Text(label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color(0xFF333333))
                                    .clickable { artKind = k; selected = null }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    val list = images[artKind].orEmpty()
                    val pref = prefs.getOrPut(artKind) { ArtPref() }
                    @Suppress("UNUSED_VARIABLE") val v = artVersion
                    if (tmdb == null) Hint("Kapakları görmek için Stüdyo Ayarları'na TMDB anahtarını gir.", Modifier.padding(16.dp))
                    else if (list.isEmpty()) Hint("Görsel bulunamadı ya da yükleniyor…", Modifier.padding(16.dp))
                    else Hint("Bir görsele dokun, sonra Sabitle ya da Gizle. Sabitlenen her profilde hep kullanılır; gizlenenler hiç gösterilmez. En üstteki Kaydet'e basmayı unutma.",
                        Modifier.padding(horizontal = 16.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (artKind == "posters") 3 else 2),
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.path }) { img ->
                            val hidden = img.path in pref.hidden
                            val pinned = img.path == pref.pin
                            Box(
                                Modifier.aspectRatio(if (artKind == "posters") 2f / 3f else 16f / 9f).clip(RoundedCornerShape(4.dp)).background(GColors.Card)
                                    .border(if (selected == img.path) 3.dp else 0.dp, if (selected == img.path) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                                    .clickable { selected = img.path },
                            ) {
                                AsyncImage(Tmdb.img(img.path, if (artKind == "posters") "w342" else "w300"), null,
                                    contentScale = if (artKind == "logos") ContentScale.Fit else ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().alpha(if (hidden) 0.25f else 1f))
                                Text(
                                    listOfNotNull(if (pinned) "★ SABİT" else null, if (hidden) "GİZLİ" else null,
                                        when (img.lang) { "tr" -> "TR"; "en" -> "EN"; null -> "Yazısız"; else -> img.lang.uppercase() }).joinToString(" · "),
                                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    selected?.let { path ->
                        Row(Modifier.fillMaxWidth().background(Color(0xFF121212)).navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pref.pin = if (pref.pin == path) null else path; pref.hidden.remove(path); artVersion++ },
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                                Text(if (pref.pin == path) "Sabitlemeyi kaldır" else "★ Sabitle")
                            }
                            Button(onClick = { if (!pref.hidden.remove(path)) { pref.hidden.add(path); if (pref.pin == path) pref.pin = null }; artVersion++ },
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White)) {
                                Text(if (path in pref.hidden) "Göster" else "Gizle")
                            }
                        }
                    }
                }
                "Fragman" -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    val trailer = item?.get("trailer") as? JsonObject
                    val dur = (trailer?.get("duration") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
                    Text(if (trailer != null) "✓ Fragman var (${dur / 60} dk ${dur % 60} sn)" else "Bu yapımın fragmanı yok.",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Hint("Fragman, uygulamada detay sayfasının üstünde ve Yeni ve Popüler'de sessiz başlar. Yeni fragman eskisinin yerine geçer.", Modifier.padding(vertical = 8.dp))
                    Button(onClick = { onUploadTrailer(titleId) }, shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White)) { Text("Fragman dosyası seç ve yükle") }
                    if (trailer != null) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            confirm = "Fragman silinsin mi?" to suspend { repo.removeTrailer(titleId); load(); status = "✓ Fragman kaldırıldı." }
                        }, shape = RoundedCornerShape(4.dp)) { Text("Fragmanı kaldır", color = Color(0xFFFF6B6B)) }
                    }
                }
                "Bölümler" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    val eps = (item?.get("episodes") as? JsonArray)?.map { it.jsonObject }.orEmpty()
                    if (eps.isEmpty()) Hint("Bölüm yok.", Modifier.padding(vertical = 12.dp))
                    eps.forEach { e ->
                        val s = e.s("season")?.toIntOrNull() ?: 0
                        val n = e.s("episode")?.toIntOrNull() ?: 0
                        val name = entry?.seasons?.firstOrNull { it.number == s }?.episodes?.firstOrNull { it.number == n }?.title
                        val st = e["stream"] as? JsonObject
                        val audio = (st?.get("audio") as? JsonArray)?.joinToString("/") { Langs.label(it.jsonPrimitive.content) }.orEmpty()
                        val subs = (st?.get("subtitles") as? JsonArray)?.joinToString("/") { Langs.label(it.jsonObject.s("lang").orEmpty()) }.orEmpty()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("S%02dE%02d".format(s, n) + (name?.let { "  $it" } ?: ""), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Hint("Ses: ${audio.ifBlank { "—" }} · Altyazı: ${subs.ifBlank { "yok" }}")
                            }
                            IconButton(onClick = {
                                confirm = "S%02dE%02d kalıcı olarak silinsin mi? (video dosyaları da silinir)".format(s, n) to suspend {
                                    repo.deleteEpisode(titleId, s, n); load(); status = "✓ Bölüm silindi."
                                }
                            }) { Icon(Icons.Outlined.Delete, "Sil", tint = GColors.TextSecondary) }
                        }
                    }
                    Hint("Yeni bölüm eklemek için Stüdyo ana sayfasındaki 'Yeni İçerik Yükle'yi kullan.", Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }

    val t = tmdb
    if (showPicker && t != null && entry != null) {
        TmdbPickerDialog(t, entry.names["original"] ?: entry.title, entry.isSeries, entry.year,
            onPick = { r -> tmdbId = r.id.toString(); showPicker = false }, onDismiss = { showPicker = false })
    }
    confirm?.let { (text, action) ->
        ConfirmDialog(text, onConfirm = {
            scope.launch { busy = true; runCatching { action() }.onFailure { status = "Hata: ${it.message}" }; busy = false }
        }, onDismiss = { confirm = null })
    }
}

/** Kalite etiketi (HD … 4K HDR) ve Dolby Vision / Dolby Atmos işaretleri: uygulamada detay sayfasında görünür. */
@Composable
private fun BadgesSection(item: JsonObject?, busy: Boolean, save: (JsonObject?) -> Unit) {
    val b = item?.get("badges") as? JsonObject
    val quality = (b?.get("quality") as? JsonPrimitive)?.content
    val vision = (b?.get("dolbyVision") as? JsonPrimitive)?.content == "true"
    val atmos = (b?.get("dolbyAtmos") as? JsonPrimitive)?.content == "true"
    fun emit(q: String?, v: Boolean, a: Boolean) {
        val m = buildMap<String, JsonElement> {
            q?.let { put("quality", JsonPrimitive(it)) }
            if (v) put("dolbyVision", JsonPrimitive(true))
            if (a) put("dolbyAtmos", JsonPrimitive(true))
        }
        save(if (m.isEmpty()) null else JsonObject(m))
    }
    Text("Kalite ve ses", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Hint("Detay sayfasındaki \"HD\" etiketi yerine ne yazsın? Dolby varsa işaretle. (Türkçe dublaj/altyazı dosyalardan kendiliğinden anlaşılır.)")
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(com.gflix.app.data.Badges.QUALITIES.size) { i ->
            val q = com.gflix.app.data.Badges.QUALITIES[i]
            val sel = (quality ?: "HD") == q
            Text(q, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color(0xFF333333))
                    .clickable(enabled = !busy && !sel) { emit(q.takeIf { it != "HD" }, vision, atmos) }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Dolby Vision" to vision, "Dolby Atmos" to atmos).forEach { (label, on) ->
            Text((if (on) "✓ " else "+ ") + label, color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Color.White else Color(0xFF333333))
                    .clickable(enabled = !busy) { if (label == "Dolby Vision") emit(quality, !vision, atmos) else emit(quality, vision, !atmos) }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/**
 * Dizi yayın planı: "2. sezon 1–5. bölüm: 7 Kasım 20:00", "6–10: 14 Kasım", "3. sezonun tamamı: 1 Mart".
 * Bölümler henüz yüklenmemiş olabilir; yüklenince plandaki zamana kadar gizli kalır. Bilgilendirme metni isteğe bağlı.
 */
@Composable
private fun PlanSection(
    item: JsonObject?, busy: Boolean, tmdbId: Int?, tmdb: Tmdb?, lastSeason: Int,
    catalogPlan: List<com.gflix.app.data.PlanEntry> = emptyList(), save: (List<JsonElement>, String) -> Unit,
) {
    fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()
    val scope = rememberCoroutineScope()
    val current = (item?.get("releasePlan") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    var note by remember(item) { mutableStateOf((item?.get("releaseNote") as? JsonPrimitive)?.content.orEmpty()) }
    var season by remember(item) { mutableStateOf(lastSeason.toString()) }
    var from by remember(item) { mutableStateOf("1") }
    var to by remember(item) { mutableStateOf("") }
    var at by remember(item) { mutableStateOf<Long?>(null) }
    var whenComplete by remember(item) { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val fmt = java.text.SimpleDateFormat("d MMMM EEEE HH:mm", com.gflix.app.data.TR)

    Text("Yayın planı", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Hint("Bölümleri gruplar hâlinde ya da yeni sezonu ileri bir tarihe planla. Telefon ve TV'de bilgi olarak görünür; bölümler yüklense bile o saate kadar gizli kalır.")
    current.sortedBy { it.i("at") ?: 0 }.forEach { p ->
        val s = p.i("season"); val f = p.i("from") ?: 1; val t = p.i("to")
        val batch = (p["whenComplete"] as? JsonPrimitive)?.content == "true"
        val pAt = p.i("at") ?: 0
        // katalogdaki kopyası: kaç bölüm yüklendi
        val cat = catalogPlan.firstOrNull { it.whenComplete && it.season.toLong() == s && it.from.toLong() == f && it.to?.toLong() == t }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1C1C1C)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$s. sezon " + (if (f <= 1 && t == null) "(tamamı)" else if (t == null) "$f. bölümden itibaren" else "$f–$t. bölüm") +
                    (if (batch) " · 📦 hepsi yüklenince birlikte" + (cat?.let { c -> if (c.complete) " (✓ hepsi yüklendi)" else " (${c.have}/${c.total} yüklendi)" } ?: "") else "") +
                    (if (pAt > 0) (if (batch) " · en erken " else " · ") + fmt.format(java.util.Date(pAt * 1000)) else ""),
                color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !busy, onClick = { save(current.filter { it !== p }, note) }) { Text("Sil", color = Color(0xFFFF6B6B)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(season, { season = it.filter(Char::isDigit) }, label = { Text("Sezon") }, singleLine = true, colors = gTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(from, { from = it.filter(Char::isDigit) }, label = { Text("İlk bölüm") }, singleLine = true, colors = gTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(to, { to = it.filter(Char::isDigit) }, label = { Text("Son (boş=hepsi)") }, singleLine = true, colors = gTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1.2f))
    }
    if (tmdb != null && tmdbId != null) TextButton(onClick = {
        val n = season.toIntOrNull() ?: return@TextButton
        hint = "TMDB'ye bakılıyor…"
        scope.launch {
            runCatching { tmdb.season(tmdbId, n) }.onSuccess { r ->
                if (r == null) { hint = "TMDB'de $n. sezon yok."; return@onSuccess }
                if (r.first > 0 && to.isBlank() && (from.toIntOrNull() ?: 1) <= 1) to = r.first.toString()
                r.second?.let { at = it }
                hint = "TMDB: $n. sezon ${r.first} bölüm" + (r.second?.let { " · ilk yayın " + fmt.format(java.util.Date(it * 1000)) } ?: " · yayın tarihi belli değil")
            }.onFailure { hint = "TMDB hatası: ${it.message}" }
        }
    }) { Text("🔎 TMDB'den sezonu doldur", color = Color.White) }
    hint?.let { Hint(it) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { whenComplete = !whenComplete }, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = whenComplete, onCheckedChange = { whenComplete = it },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = com.gflix.app.ui.theme.GColors.Red))
        Column(Modifier.weight(1f)) {
            Text("📦 Hepsi yüklenince birlikte aç", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Bu aralıktaki bölümler (zaten yayında olanlar dahil) gizlenir; hepsi yüklenince aynı anda açılır. Zaman seçersen en erken o saatte açılır. " +
                "Son bölümü boş bırakırsan sezonun bölüm sayısı TMDB'den alınır.",
                color = GColors.TextSecondary, fontSize = 12.sp)
        }
    }
    ReleaseTimeField(at, { at = it }, Modifier.padding(top = 6.dp), label = if (whenComplete) "En erken açılma zamanı (isteğe bağlı)" else "Bu bölümlerin yayın zamanı")
    if (whenComplete && to.toIntOrNull() == null && (tmdb == null || tmdbId == null)) Hint("TMDB bağlı değil: son bölümü de yaz.")
    if (whenComplete) Hint("⚠ Kaydetmek için aşağıdaki \"+ Plana ekle\"ye bas (üstteki Kaydet bu planı kaydetmez).")
    Button(
        enabled = !busy && season.toIntOrNull() != null && (
            if (whenComplete) (to.toIntOrNull()?.let { it >= (from.toIntOrNull() ?: 1) } ?: (tmdb != null && tmdbId != null)) else at != null),
        onClick = {
            scope.launch {
                var last = to.toIntOrNull()
                if (whenComplete && last == null) {
                    hint = "TMDB'den sezonun bölüm sayısı alınıyor…"
                    last = runCatching { tmdb!!.season(tmdbId!!, season.toInt())?.first }.getOrNull()?.takeIf { it > 0 }
                    if (last == null) { hint = "TMDB'de ${season}. sezonun bölüm sayısı bulunamadı; son bölümü elle yaz."; return@launch }
                    hint = "TMDB: ${season}. sezon $last bölüm"
                }
                val e = JsonObject(buildMap {
                    put("season", JsonPrimitive(season.toInt())); put("from", JsonPrimitive((from.toIntOrNull() ?: 1).coerceAtLeast(1)))
                    last?.let { put("to", JsonPrimitive(it)) }; put("at", JsonPrimitive(at ?: 0L))
                    if (whenComplete) put("whenComplete", JsonPrimitive(true))
                })
                save(current + e, note)
                from = ((last ?: 0) + 1).toString(); to = ""; at = null; whenComplete = false
            }
        },
        modifier = Modifier.padding(top = 8.dp), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
    ) { Text(if (whenComplete) "+ Plana ekle (hepsi yüklenince birlikte aç)" else "+ Plana ekle") }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(note, { note = it }, label = { Text("Bilgilendirme metni (isteğe bağlı)") },
        placeholder = { Text("Boşsa plandan yazılır: \"1–5. bölümler 7 Kasım Cuma 20:00 · 6–10. bölümler 14 Kasım…\"") },
        minLines = 2, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
    TextButton(enabled = !busy, onClick = { save(current, note) }) { Text("Metni kaydet", color = Color.White) }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()


/** Hazır not şablonları: emojiyle başlar, metni düzenlenir. */
private val NOTE_TEMPLATES = listOf(
    "📢 Duyuru: ",
    "🔥 Yeni sezon geldi!",
    "🆕 Yeni bölümler eklendi",
    "🎙️ Türkçe dublaj eklendi",
    "💬 Türkçe altyazı eklendi",
    "📺 4K / Dolby ile izle",
    "⭐ Önerimiz",
    "🛠️ Ses / görüntü sorunu düzeltildi",
    "⏳ Yakında kaldırılacak",
    "🎬 Yarın akşam ailece izliyoruz",
)

/**
 * Stüdyo notları: uygulamada detay sayfasında ve Yeni ve Popüler kartında şerit olarak görünür.
 * Bitiş zamanı verilirse o an kendiliğinden kalkar; "yapımı da kaldır" seçiliyse yapım da uygulamalardan gizlenir (dosyalar silinmez).
 */
@Composable
private fun NotesSection(item: JsonObject?, busy: Boolean, save: (List<JsonElement>, String) -> Unit) {
    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
    val current = (item?.get("notes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    var text by remember(item) { mutableStateOf("") }
    var until by remember(item) { mutableStateOf<Long?>(null) }
    var hide by remember(item) { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val fmt = java.text.SimpleDateFormat("d MMMM yyyy EEEE HH:mm", com.gflix.app.data.TR)
    val now = System.currentTimeMillis() / 1000

    Text("Notlar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Hint("Ailene yapımın sayfasında görünen kısa notlar. Bitiş zamanı verirsen o an kendiliğinden kalkar.")
    current.forEach { n ->
        val u = n.str("until")?.toLongOrNull()
        val h = n.str("hide") == "true"
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1C1C1C)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.str("text").orEmpty().ifBlank { "(metinsiz)" }, color = Color.White, fontSize = 13.sp)
                if (u != null) Hint(
                    (if (u <= now) "⌛ Süresi doldu: " else "⏱ Bitiş: ") + fmt.format(java.util.Date(u * 1000)) +
                        if (h) (if (u <= now) " · yapım uygulamadan kaldırıldı (dosyalar duruyor)" else " · bitince yapım da kaldırılır") else "",
                )
            }
            TextButton(enabled = !busy, onClick = { save(current.filter { it !== n }, "Not silindi") }) { Text("Sil", color = Color(0xFFFF6B6B)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(NOTE_TEMPLATES.size) { i ->
            val t = NOTE_TEMPLATES[i]
            Text(t.trim(), color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF333333))
                    .clickable { text = t; if (t.startsWith("⏳")) hide = true }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(text, { text = it }, label = { Text("Not") }, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(), maxLines = 3)
    Spacer(Modifier.height(8.dp))
    ReleaseTimeField(until, { until = it }, label = "Bitiş zamanı (boşsa hep durur)")
    Row(Modifier.fillMaxWidth().clickable(enabled = until != null) { hide = !hide }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = hide && until != null, enabled = until != null, onCheckedChange = { hide = it },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = com.gflix.app.ui.theme.GColors.Red))
        Text("Bitince yapımı da uygulamadan kaldır (dosyalar silinmez, notu silince geri gelir)",
            color = if (until != null) Color.White else com.gflix.app.ui.theme.GColors.TextSecondary, fontSize = 13.sp)
    }
    hint?.let { Hint(it) }
    Button(
        enabled = !busy && text.isNotBlank(),
        onClick = {
            if (until != null && until!! <= now) { hint = "Bitiş zamanı geçmişte olamaz."; return@Button }
            hint = null
            val note = JsonObject(buildMap {
                put("text", JsonPrimitive(text.trim()))
                until?.let { put("until", JsonPrimitive(it)) }
                if (hide && until != null) put("hide", JsonPrimitive(true))
            })
            save(current + note, "Not eklendi")
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
    ) { Text("+ Notu ekle", fontWeight = FontWeight.Bold) }
}
