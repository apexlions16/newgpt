package com.gflix.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.data.EpisodeRef
import com.gflix.app.data.Library
import com.gflix.app.data.Title
import com.gflix.app.startPlayback
import com.gflix.app.ui.components.BottomGradient
import com.gflix.app.ui.components.EmptyMessage
import com.gflix.app.ui.components.GMark
import com.gflix.app.ui.components.IconAction
import com.gflix.app.ui.components.MaturityBadge
import com.gflix.app.ui.components.OutlineBadge
import com.gflix.app.ui.components.PlayButton
import com.gflix.app.ui.components.PlayCircle
import com.gflix.app.ui.components.PosterCard
import com.gflix.app.ui.components.ProgressLine
import com.gflix.app.ui.components.TrailerView
import com.gflix.app.ui.components.ThemeMusic
import com.gflix.app.ui.components.canAutoplayTrailer
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(titleId: String, onBack: () -> Unit, onOpenTitle: (String) -> Unit) {
    val context = LocalContext.current
    val lib = rememberLibraryState()
    val scope = rememberCoroutineScope()
    val title = lib.find(titleId)

    if (title == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            EmptyMessage("Bulunamadı", "Bu içerik artık mevcut değil.")
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White)
            }
        }
        return
    }

    // archive.org: oynat'a basmadan açılış parçaları belleğe (basınca beklemeden başlasın)
    androidx.compose.runtime.LaunchedEffect(title.id) { if (com.gflix.app.archive.ArchiveSource.configured) com.gflix.app.player.ArchiveWarm.warmTitle(title, lib.data) }
    val seasons = remember(title) { title.seasons.filter { s -> s.episodes.any { it.stream != null } }.sortedBy { it.number } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val latest = Library.latestProgress(title.id, lib.data)
    var seasonIndex by rememberSaveable(title.id) {
        mutableIntStateOf(seasons.indexOfFirst { it.number == latest?.season }.coerceAtLeast(0))
    }
    var showSeasonPicker by remember { mutableStateOf(false) }
    var showTrailer by remember(title.id) { mutableStateOf(canAutoplayTrailer(context, lib.profile)) }
    val trailers = remember(title) { com.gflix.app.player.TrailerMedia.all(title) }
    var trailerLang by remember(title.id) { mutableStateOf(com.gflix.app.player.TrailerMedia.preferredLang(lib.profile, lib.data)) }
    val similar = remember(title, lib.titles) { Library.similar(title, lib.titles) }
    val target = Library.playTarget(title, lib.data)
    val inList = title.id in lib.data.myList
    val liked = title.id in lib.data.liked
    val tabs = if (title.isSeries) listOf("Bölümler", "Benzerleri") else listOf("Benzerleri")
    val currentTab = tabs.getOrElse(tab) { tabs.first() }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
        item(key = "backdrop") {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(GColors.Card)) {
                val trailer = com.gflix.app.player.TrailerMedia.pick(title, trailerLang)
                if (trailer != null && showTrailer) {
                    TrailerView(
                        trailer, title.backdrop ?: title.poster, Modifier.fillMaxSize(),
                        onFullscreen = { pos -> com.gflix.app.player.TrailerActivity.start(context, title.id, trailer.lang, pos) },
                        langLabel = if (trailers.size > 1) com.gflix.app.player.TrailerMedia.shortLabel(trailer) else null,
                        onSwitchLang = if (trailers.size > 1) ({
                            val i = trailers.indexOfFirst { com.gflix.app.player.TrailerMedia.key(it) == com.gflix.app.player.TrailerMedia.key(trailer) }
                            trailerLang = trailers[(i + 1) % trailers.size].lang ?: "und"
                        }) else null,
                    )
                } else {
                    AsyncImage(model = title.backdrop ?: title.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    BottomGradient(Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter))
                    if (target != null) PlayCircle(
                        Modifier.align(Alignment.Center).clickable { context.playTitle(title, lib.data) },
                        size = 56.dp,
                    )
                    if (trailer != null) {
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.6f)).clickable { showTrailer = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Fragmanı İzle", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Box(
                    Modifier.statusBarsPadding().padding(8.dp).size(38.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
        }
        item(key = "info") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GMark(size = 20.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (title.isSeries) "D İ Z İ" else "F İ L M", color = GColors.TextSecondary,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(title.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Library.matchPercent(title)?.let { Text("%$it Eşleşme", color = GColors.Match, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    title.year?.let { Text("$it", color = GColors.TextSecondary, fontSize = 13.sp) }
                    title.certification?.let { MaturityBadge(it) }
                    (Library.seasonsText(title) ?: Library.formatRuntime(title.runtime))?.let { Text(it, color = GColors.TextSecondary, fontSize = 13.sp) }
                    com.gflix.app.ui.components.QualityBadges(title)
                }
                com.gflix.app.ui.components.LanguageLine(title, Modifier.padding(top = 6.dp))
                com.gflix.app.ui.components.TitleNotes(title, Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp))
                val soonAt = lib.nextRelease(title.id)
                val upcoming = target == null && (soonAt != null || lib.comingSoon.any { it.id == title.id })
                if (upcoming || soonAt != null) {
                    // Yakında: Netflix'teki gibi tarih şeridi + Hatırlat
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val rawTitle = lib.rawCatalog?.titles?.firstOrNull { it.id == title.id }
                            val seriesLine = rawTitle?.takeIf { target != null && Library.isContinuingSeries(it, lib.now) }?.let { Library.seriesSoonText(it, lib.now) }
                            Text(
                                seriesLine ?: Library.releaseText(soonAt, title.isSeries, newEpisode = target != null),
                                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                            if (seriesLine == null) rawTitle?.let { Library.planText(it, lib.now) }?.let {
                                Text("🗓️ $it", color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (upcoming) Text("Fragmanı şimdi izleyebilirsin.", color = GColors.TextSecondary, fontSize = 12.sp)
                        }
                        com.gflix.app.ui.components.ReminderAction(title, soonAt)
                    }
                    Spacer(Modifier.height(10.dp))
                } else lib.rawCatalog?.titles?.firstOrNull { it.id == title.id }?.let { Library.newEpisodesText(it, lib.now) }?.let {
                    // Eksik bölümleri olan dizi: TMDB'de var, henüz yüklenmedi
                    Text(it, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                }
                if (target != null) {
                    PlayButton(Modifier.fillMaxWidth(), text = if (title.isSeries && latest != null) "Devam Et" else "Oynat") {
                        context.startPlayback(title.id, target.season, target.episode, target.positionMs)
                    }
                    Spacer(Modifier.height(8.dp))
                    val live = title.stream?.live == true || title.seasons.any { s -> s.episodes.any { it.stream?.live == true } }
                    if (live && com.gflix.app.data.PcServer.configured) {
                        // Linkten anlık yayın: bilgisayar TV'ye "aç" der; TV kendiliğinden açar (kaldığı saniyeden)
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val msg = runCatching {
                                        com.gflix.app.data.PcServer.openOnTv(title.id, target.season, target.episode, target.positionMs / 1000, title.title)
                                    }.fold({ "📺 TV'ye gönderildi" }, { it.message ?: "Gönderilemedi" })
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) { Text("📺 TV'de aç", fontWeight = FontWeight.Bold) }
                    }
                    // Netflix'teki gibi: filmde "İndir", dizide sıradaki bölümü indir. Anlık yayında bilgisayar aktarır, telefon saklar
                    if (live) Spacer(Modifier.height(8.dp))
                    com.gflix.app.ui.components.DownloadButton(title, target.season, target.episode, lib.data)
                }
                if (latest != null && !latest.isFinished) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(latest.fraction, Modifier.weight(1f), height = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            (if (latest.season != null) "S${latest.season}:B${latest.episode}  " else "") + Library.remainingText(latest.remainingMs),
                            color = GColors.TextSecondary, fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (title.overview.isNotBlank()) {
                    Text(title.overview, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (title.cast.isNotEmpty()) {
                    Text(
                        "Oyuncular: " + title.cast.take(4).joinToString(", ") + if (title.cast.size > 4) "… daha fazla" else "",
                        color = GColors.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (title.creators.isNotEmpty()) {
                    Text(
                        (if (title.isSeries) "Yaratıcı: " else "Yönetmen: ") + title.creators.take(3).joinToString(", "),
                        color = GColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    IconAction(if (inList) Icons.Filled.Check else Icons.Filled.Add, "Listem") {
                        scope.launch { lib.container.userStore.toggleMyList(lib.profileId, title.id) }
                    }
                    IconAction(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Değerlendir") {
                        scope.launch { lib.container.userStore.toggleLiked(lib.profileId, title.id) }
                    }
                    IconAction(Icons.Outlined.Share, "Paylaş") { context.shareTitle(title) }
                }
            }
        }
        item(key = "tabs") {
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(GColors.Divider))
                Row(Modifier.padding(horizontal = 12.dp)) {
                    tabs.forEachIndexed { i, label ->
                        val sel = label == currentTab
                        Column(Modifier.clickable { tab = i }.padding(end = 20.dp)) {
                            Box(Modifier.width(if (sel) 90.dp else 0.dp).height(4.dp).background(if (sel) GColors.Red else Color.Transparent))
                            Text(
                                label, color = if (sel) Color.White else GColors.TextSecondary,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        if (currentTab == "Bölümler" && seasons.isNotEmpty()) {
            val season = seasons[seasonIndex.coerceIn(0, seasons.lastIndex)]
            item(key = "season") {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2B2B2B))
                        .clickable(enabled = seasons.size > 1) { showSeasonPicker = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Library.seasonName(season), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (seasons.size > 1) Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White)
                }
            }
            items(season.episodes.filter { it.stream != null }.sortedBy { it.number }, key = { "ep-${season.number}-${it.number}" }) { ep ->
                val ref = EpisodeRef(season, ep)
                EpisodeItem(title, ref, lib.data.progress[Library.progressKey(title.id, season.number, ep.number)]?.let { it.fraction }, lib.data) {
                    val p = lib.data.progress[Library.progressKey(title.id, season.number, ep.number)]
                    context.startPlayback(title.id, season.number, ep.number, if (p != null && !p.isFinished) p.positionMs else 0L)
                }
            }
        } else {
            if (similar.isEmpty()) {
                item(key = "nosimilar") {
                    Text("Henüz benzer içerik yok.", color = GColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
            }
            items(similar.chunked(3), key = { row -> "sim-" + row.joinToString { it.id } }) { row ->
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t -> PosterCard(t, Modifier.weight(1f)) { onOpenTitle(t.id) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
    // Tema müziği: sayfa kaydırılınca da sürer; fragman açıkken susar
    ThemeMusic(title.id, enabled = lib.profile?.themeMusic != false && !(showTrailer && com.gflix.app.player.TrailerMedia.pick(title, trailerLang) != null),
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp))
    }

    if (showSeasonPicker) {
        FullScreenPicker(
            items = seasons.map { Library.seasonName(it) },
            selected = Library.seasonName(seasons[seasonIndex.coerceIn(0, seasons.lastIndex)]),
            onSelect = { seasonIndex = it },
            onDismiss = { showSeasonPicker = false },
        )
    }
}

@Composable
private fun EpisodeItem(title: Title, ref: EpisodeRef, progress: Float?, data: com.gflix.app.data.ProfileData, onPlay: () -> Unit) {
    val ep = ref.episode
    Column(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(GColors.Card)) {
                AsyncImage(model = ep.still ?: title.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                PlayCircle(Modifier.align(Alignment.Center), size = 34.dp)
                if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "${ep.number}. ${ep.title ?: "Bölüm ${ep.number}"}", color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Library.formatRuntime(ep.runtime ?: ep.stream?.duration?.let { (it / 60).toInt() })?.let {
                    Text(it, color = GColors.TextSecondary, fontSize = 12.sp)
                }
            }
            com.gflix.app.ui.components.DownloadButton(title, ref.season.number, ep.number, data, compact = true)
        }
        if (!ep.overview.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(ep.overview, color = GColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}
