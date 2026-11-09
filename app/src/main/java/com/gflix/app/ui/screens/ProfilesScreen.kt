package com.gflix.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.appContainer
import com.gflix.app.data.ArtMode
import com.gflix.app.data.Profile
import com.gflix.app.data.TitleLang
import com.gflix.app.ui.components.AVATAR_COUNT
import com.gflix.app.ui.components.GLoading
import com.gflix.app.ui.components.GLogo
import com.gflix.app.ui.components.ProfileAvatar
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val MAX_PROFILES = 5

@Composable
fun ProfilesScreen(manageInitially: Boolean, onSelected: () -> Unit, onEdit: (String) -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    val store = context.appContainer.userStore
    val state by store.state.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var manage by rememberSaveable { mutableStateOf(manageInitially) }
    var selecting by remember { mutableStateOf<String?>(null) }

    // Netflix gibi karşılama: İlk 10'dan afişler birkaç saniyede bir değişir, profiller altta
    val raw by context.appContainer.catalogRepository.catalog.collectAsState()
    val heroes = remember(raw) { welcomeHeroes(raw) }
    if (!manage && heroes.isNotEmpty() && state?.profiles.orEmpty().isNotEmpty()) {
        WelcomeScreen(heroes, state?.profiles.orEmpty(), selecting,
            onPick = { p ->
                if (selecting == null) {
                    selecting = p.id
                    scope.launch { store.selectProfile(p.id); delay(650); onSelected() }
                }
            },
            onManage = { manage = true }, onAdd = onAdd)
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp)) {
            if (manage) {
                Text("Profilleri Yönet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = { manage = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Bitti", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                GLogo(Modifier.align(Alignment.Center), size = 36.sp)
                IconButton(onClick = { manage = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Outlined.Edit, "Düzenle", tint = Color.White)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            if (manage) "Düzenlemek istediğin profili seç" else "Kim izliyor?",
            color = Color.White, fontSize = if (manage) 16.sp else 22.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        val profiles = state?.profiles.orEmpty()
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(profiles, key = { it.id }) { p ->
                ProfileTile(p, manage, selected = selecting == p.id) {
                    if (manage) onEdit(p.id)
                    else if (selecting == null) {
                        selecting = p.id
                        scope.launch {
                            store.selectProfile(p.id)
                            delay(650)
                            onSelected()
                        }
                    }
                }
            }
            if (profiles.size < MAX_PROFILES) {
                item(key = "add") { AddProfileTile(onAdd) }
            }
        }
        if (selecting != null) GLoading(Modifier.fillMaxWidth().padding(bottom = 48.dp))
    }
}

@Composable
private fun ProfileTile(profile: Profile, manage: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, label = "scale")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).scale(scale)) {
            ProfileAvatar(profile.avatar, Modifier.fillMaxSize(), corner = 6.dp)
            if (manage) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            if (profile.kids) {
                Text(
                    "Çocuk", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(profile.name, color = GColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, Color(0xFF4D4D4D), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF2B2B2B)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Profil Ekle", color = GColors.TextSecondary, fontSize = 14.sp)
    }
}

@Composable
fun ProfileEditScreen(profileId: String, isFirst: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = context.appContainer.userStore
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState(initial = null)
    val existing = state?.profiles?.firstOrNull { it.id == profileId }
    val isNew = profileId == "new"

    var loaded by remember { mutableStateOf(isNew) }
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableIntStateOf((0 until AVATAR_COUNT).random()) }
    var kids by remember { mutableStateOf(false) }
    var titleLang by remember { mutableStateOf(TitleLang.TR) }
    var artMode by remember { mutableStateOf(ArtMode.ROTATE) }
    var autoplay by remember { mutableStateOf(true) }
    var wifiOnly by remember { mutableStateOf(false) }
    var themeMusic by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
        if (!loaded && existing != null) {
            name = existing.name; avatar = existing.avatar; kids = existing.kids
            titleLang = existing.titleLang; artMode = existing.artMode
            autoplay = existing.autoplayTrailers; wifiOnly = existing.trailersWifiOnly
            themeMusic = existing.themeMusic
            loaded = true
        }
    }

    fun save() {
        val clean = name.trim()
        if (clean.isEmpty()) return
        scope.launch {
            if (isNew) {
                val p = store.addProfile(clean, avatar, kids)
                store.updateProfile(p.copy(titleLang = titleLang, artMode = artMode, autoplayTrailers = autoplay, trailersWifiOnly = wifiOnly, themeMusic = themeMusic))
                if (isFirst) store.selectProfile(p.id)
            } else if (existing != null) {
                store.updateProfile(
                    existing.copy(
                        name = clean, avatar = avatar, kids = kids, titleLang = titleLang, artMode = artMode,
                        autoplayTrailers = autoplay, trailersWifiOnly = wifiOnly, themeMusic = themeMusic,
                    ),
                )
            }
            onDone()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isFirst) {
                IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
            } else Spacer(Modifier.width(48.dp))
            Text(
                if (isNew) "Profil Oluştur" else "Profili Düzenle", color = Color.White, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            TextButton(onClick = ::save, enabled = name.isNotBlank()) {
                Text("Kaydet", color = if (name.isNotBlank()) Color.White else GColors.TextMuted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            if (isFirst) {
                Text("G-Flix'e hoş geldin! İlk profilini oluştur.", color = GColors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
            }
            ProfileAvatar(avatar, Modifier.size(110.dp))
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name, onValueChange = { if (it.length <= 20) name = it },
                label = { Text("Profil adı") }, singleLine = true, colors = gTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Text("Bir simge seç", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            (0 until AVATAR_COUNT).chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { i ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .border(if (i == avatar) 3.dp else 0.dp, if (i == avatar) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(4.dp).clickable { avatar = i },
                        ) { ProfileAvatar(i, Modifier.fillMaxSize()) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Çocuk Profili", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Sadece çocuklara uygun dizi ve filmler gösterilir.", color = GColors.TextSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = kids, onCheckedChange = { kids = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = GColors.Red, checkedThumbColor = Color.White),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text("Görünüm Tercihleri", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            PrefLabel("Başlık dili", "Dizi ve film adları hangi dilde görünsün? Aramada her dildeki ad bulunur.")
            ChoiceRow(
                listOf(TitleLang.TR to "Türkçe", TitleLang.EN to "İngilizce", TitleLang.ORIGINAL to "Orijinal", TitleLang.MIXED to "Karışık"),
                titleLang,
            ) { titleLang = it }
            Spacer(Modifier.height(18.dp))
            PrefLabel("Kapaklar", "Değişken seçilirse her gün farklı kapak ve arka plan görürsün.")
            ChoiceRow(listOf(ArtMode.ROTATE to "Değişken", ArtMode.FIXED to "Sabit"), artMode) { artMode = it }
            Spacer(Modifier.height(18.dp))
            SwitchRow("Fragmanları otomatik oynat", "Detay sayfasında ve Yeni ve Popüler'de fragmanlar sessiz başlar.", autoplay) { autoplay = it }
            if (autoplay) {
                Spacer(Modifier.height(12.dp))
                SwitchRow("Sadece Wi-Fi'de", "Mobil veride fragmanlar kendiliğinden oynamaz.", wifiOnly) { wifiOnly = it }
            }
            if (!isNew && (state?.profiles?.size ?: 0) > 1) {
                Spacer(Modifier.height(32.dp))
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.Delete, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Profili Sil", color = Color.White, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Profil silinsin mi?") },
            text = { Text("Bu profilin izleme geçmişi ve listesi kalıcı olarak silinecek.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { store.deleteProfile(profileId); onDone() }
                }) { Text("Sil", color = GColors.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç", color = Color.White) } },
            containerColor = GColors.Card,
        )
    }
}

@Composable
private fun PrefLabel(title: String, desc: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(desc, color = GColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                    .background(if (sel) Color.White else Color(0xFF2B2B2B))
                    .clickable { onSelect(value) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(desc, color = GColors.TextSecondary, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = GColors.Red, checkedThumbColor = Color.White))
    }
}


// ---------------------------------------------------------------- 🎬 karşılama (profil seçimi)

private data class Hero(val title: String, val poster: String, val logo: String?, val label: String)

/** İlk 10 film ve dizi, sırayla karışık: afiş (yazısız varsa o, büyük boy), logo, "Filmlerde Bugün N Numara" */
private fun welcomeHeroes(raw: com.gflix.app.data.Catalog?): List<Hero> {
    val pool = raw?.titles.orEmpty().filter { com.gflix.app.data.Library.hasPlayable(it) && !com.gflix.app.data.Library.isHidden(it) }
    fun big(u: String) = u.replace(Regex("/t/p/[^/]+/"), "/t/p/w780/")
    fun hero(t: com.gflix.app.data.Title, rank: Int, series: Boolean): Hero? {
        val poster = (t.posters.firstOrNull { it.lang == null }?.url ?: t.poster ?: t.posters.firstOrNull()?.url) ?: return null
        val logo = t.logos.firstOrNull { it.lang == "tr" }?.url ?: t.logos.firstOrNull { it.lang == "en" }?.url ?: t.logo ?: t.logos.firstOrNull()?.url
        return Hero(t.title, big(poster), logo?.let(::big), (if (series) "Dizilerde" else "Filmlerde") + " Bugün $rank Numara")
    }
    val movies = com.gflix.app.data.Library.top10(pool.filter { !it.isSeries }).mapIndexedNotNull { i, t -> hero(t, i + 1, false) }
    val series = com.gflix.app.data.Library.top10(pool.filter { it.isSeries }).mapIndexedNotNull { i, t -> hero(t, i + 1, true) }
    val out = mutableListOf<Hero>()
    for (i in 0 until maxOf(movies.size, series.size)) { movies.getOrNull(i)?.let(out::add); series.getOrNull(i)?.let(out::add) }
    return out
}

@Composable
private fun WelcomeScreen(heroes: List<Hero>, profiles: List<Profile>, selecting: String?, onPick: (Profile) -> Unit,
                          onManage: () -> Unit, onAdd: () -> Unit) {
    var i by remember { mutableIntStateOf((heroes.indices).random()) }
    LaunchedEffect(heroes) { while (true) { delay(5_000); i = (i + 1) % heroes.size } }
    val h = heroes[i % heroes.size]
    val bg = com.gflix.app.ui.components.rememberDominantColor(h.poster, Color(0xFF1B1B1B))
    val panel = Color(bg.red * 0.75f + 0.08f, bg.green * 0.75f + 0.08f, bg.blue * 0.75f + 0.08f, 1f)
    Box(Modifier.fillMaxSize().background(bg)) {
        androidx.compose.animation.Crossfade(h, animationSpec = androidx.compose.animation.core.tween(900), label = "hero") { cur ->
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                coil.compose.AsyncImage(cur.poster, cur.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent, 0.55f to Color.Transparent, 0.85f to bg.copy(alpha = 0.85f), 1f to bg)))
            }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            androidx.compose.animation.Crossfade(h, animationSpec = androidx.compose.animation.core.tween(900), label = "logo") { cur ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (cur.logo != null) coil.compose.AsyncImage(cur.logo, cur.title, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.62f).height(84.dp))
                    else Text(cur.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                        maxLines = 2, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.background(Color(0xFFE50914), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TOP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, lineHeight = 8.sp)
                            Text("10", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(cur.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)).background(panel)
                .padding(top = 18.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Profilinizi Seçin", color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                val tiles: List<Profile?> = profiles + listOf(null)        // sonda Düzenle / Ekle
                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { p ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(96.dp).clickable { if (p != null) onPick(p) else onManage() }.padding(bottom = 14.dp)) {
                                val sc by animateFloatAsState(if (p != null && selecting == p.id) 1.12f else 1f, label = "sc")
                                Box(Modifier.size(84.dp).scale(sc)) {
                                    if (p != null) ProfileAvatar(p.avatar, Modifier.fillMaxSize(), corner = 12.dp)
                                    else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Edit, "Düzenle", tint = Color.White, modifier = Modifier.size(30.dp)) }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(p?.name ?: "Düzenle", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.width(96.dp)) }
                    }
                }
                if (selecting != null) GLoading(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}
