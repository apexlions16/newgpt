package com.gflix.app.ui.screens

import com.gflix.app.appContainer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.BuildConfig
import com.gflix.app.data.CatalogStatus
import com.gflix.app.data.GitHub
import com.gflix.app.data.Library
import com.gflix.app.startPlayback
import com.gflix.app.ui.components.EmptyMessage
import com.gflix.app.ui.components.PlayCircle
import com.gflix.app.ui.components.PosterCard
import com.gflix.app.ui.components.ProfileAvatar
import com.gflix.app.ui.components.ProgressLine
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch

@Composable
fun MyGFlixScreen(
    lib: LibraryState,
    onOpenTitle: (String) -> Unit,
    onSearch: () -> Unit,
    onSwitchProfile: () -> Unit,
    onManageProfiles: () -> Unit,
    onMyList: () -> Unit,
    onSettings: () -> Unit,
    onEditProfile: (String) -> Unit,
    onAdmin: () -> Unit,
    onDownloads: () -> Unit = {},
    onNotifications: () -> Unit = {},
    unseen: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(false) }
    val downloads by context.appContainer.downloads.items.collectAsState()
    val byId = remember(lib.titles) { lib.titles.associateBy { it.id } }
    val myList = lib.data.myList.mapNotNull { byId[it] }
    val liked = lib.data.liked.mapNotNull { byId[it] }
    val continueItems = remember(lib.titles, lib.data.progress) { Library.continueWatching(lib.titles, lib.data) }

    LazyColumn(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            // Netflix gibi: solda profil resmi + ad ▾ (profil penceresi), sağda indirilenler ve bildirimler
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { sheet = true }, verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(lib.profile?.avatar ?: 0, Modifier.size(40.dp), corner = 6.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(lib.profile?.name ?: "", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.ArrowDropDown, "Profil", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                TopIcons(onDownloads, onNotifications, unseen)
            }
        }
        item {
            val n = downloads.size
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1C))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)).clickable(onClick = onDownloads).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Download, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("İndirilenler", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (n == 0) "İndirdiğin dizi ve filmler burada görüntülenecek." else "$n film / bölüm · internetsiz izle",
                        color = GColors.TextSecondary, fontSize = 14.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
            }
        }
        if (continueItems.isNotEmpty()) {
            item { SectionHeaderLink("İzlemeye Devam Edin") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(continueItems, key = { it.title.id }) { ci ->
                        Column(Modifier.width(180.dp).clickable { context.startPlayback(ci.title.id, ci.season, ci.episode, ci.progress?.positionMs ?: 0) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(GColors.Card)) {
                                AsyncImage(model = ci.title.backdrop ?: ci.title.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                PlayCircle(Modifier.align(Alignment.Center), size = 38.dp)
                                ProgressLine(ci.progress?.fraction ?: 0f, Modifier.align(Alignment.BottomCenter))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(ci.title.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ci.season != null) Text("S${ci.season}:B${ci.episode}", color = GColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item { SectionHeaderLink("Beğendiğiniz Dizi ve Filmler") }
        item {
            if (liked.isEmpty()) HintText("Beğendiğin yapımları 👍 ile işaretle, burada görünsünler.")
            else LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(liked, key = { it.id }) { t -> PosterCard(t, Modifier.width(110.dp)) { onOpenTitle(t.id) } }
            }
        }
        item { SectionHeaderLink("Listem", onAll = if (myList.isNotEmpty()) onMyList else null) }
        item {
            if (myList.isEmpty()) HintText("Listen boş. İzlemek istediklerini + Listem ile ekle.")
            else LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(myList, key = { it.id }) { t -> PosterCard(t, Modifier.width(110.dp)) { onOpenTitle(t.id) } }
            }
        }
    }
    if (sheet) ProfileSheet(lib, onDismiss = { sheet = false }, onSwitchProfile = { sheet = false; onSwitchProfile() },
        onManageProfiles = { sheet = false; onManageProfiles() }, onSettings = { sheet = false; onSettings() },
        onEditProfile = { sheet = false; onEditProfile(it) }, onAdmin = { sheet = false; onAdmin() })
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF2B2B2B)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = GColors.TextSecondary)
    }
}

@Composable
private fun SectionHeaderLink(text: String, onAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onAll != null) {
            Row(Modifier.clickable(onClick = onAll), verticalAlignment = Alignment.CenterVertically) {
                Text("Tümünü Gör", color = GColors.TextSecondary, fontSize = 13.sp)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = GColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, color = GColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun MyListScreen(onBack: () -> Unit, onOpenTitle: (String) -> Unit) {
    val lib = rememberLibraryState()
    val byId = remember(lib.titles) { lib.titles.associateBy { it.id } }
    val items = lib.data.myList.mapNotNull { byId[it] }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
            Text("Listem", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize()) { EmptyMessage("Listen boş", "Beğendiğin dizi ve filmleri + Listem ile buraya ekle.") }
        } else {
            PosterGrid(items, onOpenTitle, Modifier.fillMaxSize().navigationBarsPadding())
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val lib = rememberLibraryState()
    val scope = rememberCoroutineScope()
    val repo = lib.container.catalogRepository
    val status by repo.status.collectAsState()
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
            Text("Uygulama Ayarları", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SettingsLabel("İçerik Kaynağı")
            SettingsValue("GitHub deposu", "${GitHub.owner}/${GitHub.repo}" + (GitHub.branch.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            SettingsValue("Katalog", "${lib.catalog?.titles?.size ?: 0} yapım")
            lib.catalog?.generatedAt?.let { SettingsValue("Son güncelleme", it.replace('T', ' ').take(16)) }
            SettingsValue(
                "Durum",
                when (val s = status) {
                    CatalogStatus.Loading -> "Yükleniyor…"
                    CatalogStatus.Ready -> "Bağlı ✓"
                    is CatalogStatus.Error -> s.message
                    CatalogStatus.Idle -> "-"
                },
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { scope.launch { repo.refresh() } },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                shape = RoundedCornerShape(4.dp),
            ) { Text("Kataloğu Şimdi Yenile") }

            Spacer(Modifier.height(28.dp))
            SettingsLabel("GitHub Erişim Anahtarı")
            Text(
                if (lib.user.token != null) "Uygulamada kayıtlı özel bir anahtar kullanılıyor."
                else if (lib.container.builtInToken != null) "Uygulamaya gömülü anahtar kullanılıyor."
                else "Anahtar yok.",
                color = GColors.TextSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it; saved = false },
                label = { Text("Yeni anahtar (isteğe bağlı)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            lib.container.userStore.setToken(token)
                            lib.container.tokens.token = token.trim()
                            saved = true
                            repo.refresh()
                        }
                    },
                    enabled = token.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
                    shape = RoundedCornerShape(4.dp),
                ) { Text(if (saved) "Kaydedildi ✓" else "Kaydet") }
                if (lib.user.token != null && lib.container.builtInToken != null) {
                    Button(
                        onClick = { scope.launch { lib.container.userStore.setToken(null); lib.container.tokens.token = lib.container.builtInToken; repo.refresh() } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("Gömülü anahtara dön") }
                }
            }

            Spacer(Modifier.height(28.dp))
            SettingsLabel("Hakkında")
            SettingsValue("Sürüm", BuildConfig.VERSION_NAME)
            val ready by com.gflix.app.update.Updater.ready.collectAsState()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var checking by remember { mutableStateOf<String?>(null) }
            SettingsValue("Güncelleme", ready?.let { "Yeni sürüm hazır: ${it.name}" } ?: checking ?: "Kendiliğinden (Wi-Fi'de denetlenir, uygulama kapalıyken yüklenir)")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val r = ready
                        if (r != null) {
                            checking = "Yükleniyor…"
                            runCatching { com.gflix.app.update.Updater.install(ctx, com.gflix.app.update.Updater.download(ctx, r)) }
                                .onFailure { checking = "Yüklenemedi: ${it.message}" }
                        } else {
                            checking = "Denetleniyor…"
                            runCatching { com.gflix.app.update.Updater.latest(ctx) }
                                .onSuccess { info ->
                                    if (info == null) checking = "En güncel sürümü kullanıyorsun ✓"
                                    else { checking = "İndiriliyor…"; com.gflix.app.update.Updater.download(ctx, info); com.gflix.app.update.Updater.ready.value = info; checking = null }
                                }
                                .onFailure { checking = "Denetlenemedi: ${it.message}" }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (ready != null) GColors.Red else Color(0xFF333333), contentColor = Color.White),
                shape = RoundedCornerShape(4.dp),
            ) { Text(if (ready != null) "Şimdi Güncelle" else "Güncellemeleri Denetle") }
            SettingsValue("Uygulama", "G-Flix • kişisel film ve dizi arşivi")
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text.uppercase(com.gflix.app.data.TR), color = GColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SettingsValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = GColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.width(130.dp))
        Text(value, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}


/** archive.org arşivinin adı (öneki): katalog <ad>-katalog/catalog.json'dan okunur. Boş bırakılırsa arşiv bağlantısı kalkar. */
@Composable
private fun ArchiveConnectDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val ia = com.gflix.app.archive.ArchiveSource
    val name = remember { mutableStateOf(ia.prefix) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(false) },
        containerColor = GColors.Surface,
        title = { Text("Arşiv bağlantısı", color = Color.White) },
        text = {
            Column {
                Text("İçeriklerin durduğu archive.org arşivinin adı (yöneticinin Stüdyo Ayarları'ndaki önek, ör. gflix-ahmet). Anahtar gerekmez.",
                    color = GColors.TextSecondary, fontSize = 13.sp)
                OutlinedTextField(name.value, { name.value = it }, label = { Text("Arşiv adı") }, singleLine = true)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { ia.save(context, name.value); onDone(true) }) { Text("Kaydet") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { onDone(false) }) { Text("Vazgeç") } },
    )
}

/** Evdeki bilgisayarın (gflix-yayin) adresleri ve anahtarı. QR okutulursa kendiliğinden dolar. */
@Composable
private fun PcConnectDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val pc = com.gflix.app.data.PcServer
    val parts = pc.summary.split(" · ")
    val lan = remember { mutableStateOf(parts.getOrNull(0).orEmpty()) }
    val ts = remember { mutableStateOf(parts.getOrNull(1).orEmpty()) }
    val key = remember { mutableStateOf(pc.key) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(false) },
        containerColor = GColors.Surface,
        title = { Text("Bilgisayar bağlantısı", color = Color.White) },
        text = {
            Column {
                Text("Bilgisayarda: gflix-yayin anahtar — çıkan QR'ı telefonun kamerasıyla okutursan bu alanlar kendiliğinden dolar.",
                    color = GColors.TextSecondary, fontSize = 13.sp)
                OutlinedTextField(lan.value, { lan.value = it }, label = { Text("Ev ağı adresi (192.168.1.20:8766)") }, singleLine = true)
                OutlinedTextField(ts.value, { ts.value = it }, label = { Text("Tailscale adresi (dışarıdayken, isteğe bağlı)") }, singleLine = true)
                OutlinedTextField(key.value, { key.value = it }, label = { Text("Anahtar") }, singleLine = true)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                pc.save(context, lan.value, ts.value, key.value)
                onDone(true)
            }) { Text("Kaydet") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { onDone(false) }) { Text("Vazgeç") } },
    )
}


/** Profil penceresi (alttan açılır): büyük profil resmi, diğer profiller, yönetim ve ayarlar */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(lib: LibraryState, onDismiss: () -> Unit, onSwitchProfile: () -> Unit, onManageProfiles: () -> Unit,
                         onSettings: () -> Unit, onEditProfile: (String) -> Unit, onAdmin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = context.appContainer.userStore
    val state by store.state.collectAsState(initial = null)
    var pcDialog by remember { mutableStateOf(false) }
    var iaDialog by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF232323)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Profil", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF2E2E2E)).padding(18.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfileAvatar(lib.profile?.avatar ?: 0, Modifier.size(110.dp), corner = 14.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(lib.profile?.name ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { lib.profile?.let { onEditProfile(it.id) } }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Outlined.Edit, "Düzenle", tint = Color.White)
                }
            }
            Spacer(Modifier.height(16.dp))
            val others = state?.profiles.orEmpty().filter { it.id != lib.profile?.id }
            if (others.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                others.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable {
                        scope.launch { store.selectProfile(p.id); onDismiss() }
                    }) {
                        ProfileAvatar(p.avatar, Modifier.size(64.dp), corner = 10.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(p.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Profil Yönetimi", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF333333)).clickable(onClick = onManageProfiles).padding(horizontal = 20.dp, vertical = 10.dp))
            Spacer(Modifier.height(18.dp))
            SheetRow(Icons.Outlined.Settings, "Uygulama Ayarları", onSettings)
            SheetRow(Icons.Outlined.Tune, "Görünüm Tercihleri") { lib.profile?.let { onEditProfile(it.id) } }
            SheetRow(Icons.Filled.Refresh, "Kataloğu Yenile") { scope.launch { lib.container.catalogRepository.refresh() }; onDismiss() }
            val pc = com.gflix.app.data.PcServer
            val ia = com.gflix.app.archive.ArchiveSource
            SheetRow(Icons.Outlined.Settings, if (ia.configured) "Arşiv: ${ia.prefix}" else "Arşiv Bağlantısı (archive.org)") { iaDialog = true }
            if (!ia.configured) SheetRow(Icons.Outlined.Settings, if (pc.configured) "Bilgisayar Bağlantısı" else "Bilgisayar Bağlantısı (kurulmadı)") { pcDialog = true }
            SheetRow(Icons.Outlined.CloudUpload, "G-Flix Stüdyo", onAdmin)
            SheetRow(Icons.Outlined.People, "Profil Değiştir", onSwitchProfile)
        }
    }
    if (iaDialog) ArchiveConnectDialog(onDone = { changed ->
        iaDialog = false
        if (changed) scope.launch { lib.container.catalogRepository.refresh() }
    })
    if (pcDialog) PcConnectDialog(onDone = { changed ->
        pcDialog = false
        if (changed) scope.launch { lib.container.catalogRepository.refresh() }
    })
}

@Composable
private fun SheetRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E2E2E)).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
    }
}
