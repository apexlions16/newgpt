package com.gflix.app.ui.screens

import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Download
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.data.CatalogStatus
import com.gflix.app.data.ContinueItem
import com.gflix.app.data.HomeFilter
import com.gflix.app.data.HomeRow
import com.gflix.app.data.Library
import com.gflix.app.data.RowStyle
import com.gflix.app.data.Title
import com.gflix.app.startPlayback
import com.gflix.app.ui.components.EmptyMessage
import com.gflix.app.ui.components.GLoading
import com.gflix.app.ui.components.GrayButton
import com.gflix.app.ui.components.PlayButton
import com.gflix.app.ui.components.PlayCircle
import com.gflix.app.ui.components.PosterCard
import com.gflix.app.ui.components.ProfileAvatar
import com.gflix.app.ui.components.ProgressLine
import com.gflix.app.ui.components.TitleLogo
import com.gflix.app.ui.components.rememberDominantColor
import com.gflix.app.ui.theme.Bebas
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(lib: LibraryState, onOpenTitle: (String) -> Unit, onSearch: () -> Unit, onSwitchProfile: () -> Unit,
               onDownloads: () -> Unit = {}, onNotifications: () -> Unit = {}, unseen: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = lib.container.catalogRepository
    val status by repo.status.collectAsState()
    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    var showCategories by remember { mutableStateOf(false) }

    val pool = remember(lib.titles, filter) { Library.applyFilter(lib.titles, filter) }
    val rows = remember(pool, lib.data.myList, lib.data.liked, filter, lib.comingSoon, lib.comingSoonSeries) {
        val base = Library.homeRows(pool, lib.data, filter)
        val soon = Library.applyFilter(lib.comingSoon, filter)
        val soonSeries = Library.applyFilter(lib.comingSoonSeries, filter)
        // "Yakında" (henüz çıkmamış yapımlar) ve "Yakında Gelen Diziler" (devamı gelen diziler) ayrı satırlar
        val extra = listOfNotNull(
            soon.takeIf { it.isNotEmpty() }?.let { com.gflix.app.data.HomeRow("soon", "Yakında", it) },
            soonSeries.takeIf { it.isNotEmpty() }?.let { com.gflix.app.data.HomeRow("soon-series", "Yakında Gelen Diziler", it) },
        )
        if (extra.isEmpty()) base else {
            val at = base.indexOfFirst { it.key == "new" }.let { if (it < 0) 0 else it + 1 }
            base.toMutableList().apply { addAll(at.coerceAtMost(size), extra) }
        }
    }
    val continueItems = remember(pool, lib.data.progress) { Library.continueWatching(pool, lib.data) }
    val genres = remember(lib.titles) { Library.genres(lib.titles) }
    val hero = remember(lib.catalog?.generatedAt, filter, pool.size) {
        pool.filter { it.poster != null }.ifEmpty { pool }.randomOrNull()
    }
    val heroColor = rememberDominantColor(hero?.poster)
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 } }
    val bgAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 0f else (1f - listState.firstVisibleItemScrollOffset / 900f).coerceIn(0f, 1f)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Hero posterinden gelen renkli arka plan
        Box(
            Modifier.fillMaxWidth().height(760.dp).background(
                Brush.verticalGradient(
                    0f to heroColor.copy(alpha = bgAlpha),
                    0.55f to heroColor.copy(alpha = 0.55f * bgAlpha),
                    1f to Color.Black,
                ),
            ),
        )
        PullToRefreshBox(
            isRefreshing = status == CatalogStatus.Loading,
            onRefresh = { scope.launch { repo.refresh() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "topspace") { Spacer(Modifier.statusBarsPadding().height(108.dp)) }
                if (hero != null) {
                    item(key = "hero") {
                        HeroCard(
                            title = hero,
                            inList = hero.id in lib.data.myList,
                            onPlay = { context.playTitle(hero, lib.data) },
                            onToggleList = { scope.launch { lib.container.userStore.toggleMyList(lib.profileId, hero.id) } },
                            onOpen = { onOpenTitle(hero.id) },
                        )
                    }
                }
                if (continueItems.isNotEmpty()) {
                    item(key = "continue") {
                        ContinueRow(
                            header = "${lib.profile?.name ?: ""} İzlemeye Devam Ediyor".trim(),
                            items = continueItems,
                            onPlay = { ci ->
                                context.startPlayback(ci.title.id, ci.season, ci.episode, ci.progress?.positionMs ?: 0L)
                            },
                            onInfo = { onOpenTitle(it.title.id) },
                            onRemove = { scope.launch { lib.container.userStore.removeFromContinueWatching(lib.profileId, it.title.id) } },
                        )
                    }
                }
                items(rows, key = { it.key }) { row ->
                    when (row.style) {
                        RowStyle.Top10 -> Top10Row(row, onOpenTitle)
                        RowStyle.Poster -> PosterRow(row.title, row.items, onOpenTitle)
                    }
                }
                if (pool.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().height(420.dp)) {
                            when (val s = status) {
                                CatalogStatus.Loading -> GLoading(Modifier.fillMaxSize())
                                is CatalogStatus.Error -> EmptyMessage("Bir sorun var", s.message + "\n\nYenilemek için aşağı çek.")
                                else -> EmptyMessage(
                                    "Henüz içerik yok",
                                    "GitHub deposuna film veya dizi yüklediğinde burada görünecek.\nYenilemek için aşağı çek.",
                                )
                            }
                        }
                    }
                }
            }
        }

        HomeTopBar(
            lib = lib,
            filter = filter,
            scrolled = scrolled,
            barColor = heroColor,
            onFilter = { filter = it },
            onCategories = { showCategories = true },
            onSearch = onSearch,
            onProfile = onSwitchProfile,
            onDownloads = onDownloads,
            onNotifications = onNotifications,
            unseen = unseen,
        )
    }

    if (showCategories) {
        val items = listOf("Ana Sayfa") + genres
        FullScreenPicker(
            items = items,
            selected = HomeFilter.genreOf(filter) ?: "Ana Sayfa",
            onSelect = { i -> filter = if (i == 0) HomeFilter.ALL else HomeFilter.genre(items[i]) },
            onDismiss = { showCategories = false },
        )
    }
}

@Composable
private fun HomeTopBar(
    lib: LibraryState,
    filter: String,
    scrolled: Boolean,
    barColor: Color,
    onFilter: (String) -> Unit,
    onCategories: () -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onDownloads: () -> Unit = {},
    onNotifications: () -> Unit = {},
    unseen: Int = 0,
) {
    val bg = if (scrolled) Brush.verticalGradient(listOf(barColor.copy(alpha = 0.97f), barColor.copy(alpha = 0.9f)))
    else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))
    Column(Modifier.fillMaxWidth().background(bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.gflix.app.ui.components.GMark(size = 38.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                when (filter) {
                    HomeFilter.SERIES -> "Diziler"; HomeFilter.MOVIES -> "Filmler"
                    HomeFilter.TR_SERIES -> "Türk Dizileri"; HomeFilter.TR_MOVIES -> "Türk Filmleri"
                    else -> HomeFilter.genreOf(filter) ?: "Ana Sayfa"
                }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TopIcons(onDownloads, onNotifications, unseen)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (filter != HomeFilter.ALL) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).border(1.dp, GColors.TextSecondary, CircleShape).clickable { onFilter(HomeFilter.ALL) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "Temizle", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
            val genre = HomeFilter.genreOf(filter)
            if (filter == HomeFilter.ALL || filter == HomeFilter.SERIES) {
                FilterChip("Diziler", filter == HomeFilter.SERIES) { onFilter(if (filter == HomeFilter.SERIES) HomeFilter.ALL else HomeFilter.SERIES) }
            }
            if (filter == HomeFilter.ALL || filter == HomeFilter.MOVIES) {
                FilterChip("Filmler", filter == HomeFilter.MOVIES) { onFilter(if (filter == HomeFilter.MOVIES) HomeFilter.ALL else HomeFilter.MOVIES) }
            }
            if (filter == HomeFilter.ALL || filter == HomeFilter.TR_SERIES) {
                FilterChip("Türk Dizileri", filter == HomeFilter.TR_SERIES) { onFilter(if (filter == HomeFilter.TR_SERIES) HomeFilter.ALL else HomeFilter.TR_SERIES) }
            }
            if (filter == HomeFilter.ALL || filter == HomeFilter.TR_MOVIES) {
                FilterChip("Türk Filmleri", filter == HomeFilter.TR_MOVIES) { onFilter(if (filter == HomeFilter.TR_MOVIES) HomeFilter.ALL else HomeFilter.TR_MOVIES) }
            }
            FilterChip(genre ?: "Kategoriler", genre != null, dropdown = true, onClick = onCategories)
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, dropdown: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else GColors.TextSecondary, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (dropdown) 8.dp else 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (dropdown) Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun HeroCard(title: Title, inList: Boolean, onPlay: () -> Unit, onToggleList: () -> Unit, onOpen: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().aspectRatio(0.69f)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .background(GColors.Card)
            .clickable(onClick = onOpen),
    ) {
        AsyncImage(
            model = title.poster ?: title.backdrop, contentDescription = title.title,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TitleLogo(title, Modifier.fillMaxWidth(0.75f).height(84.dp), textSize = 44.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                (listOf(if (title.isSeries) "Dizi" else "Film") + title.genres.take(3)).joinToString("  •  "), color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            // Netflix'teki gibi alt alta: beyaz Oynat, gri Listem
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).clickable(onClick = onPlay),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (title.isSeries) "Diziyi Oynat" else "Oynat", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.22f)).clickable(onClick = onToggleList),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (inList) Icons.Filled.Check else Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(6.dp))
                Text("Listem", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PosterRow(header: String, titles: List<Title>, onOpenTitle: (String) -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(header, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(titles, key = { it.id }) { t -> PosterCard(t, Modifier.width(110.dp)) { onOpenTitle(t.id) } }
        }
    }
}

@Composable
private fun Top10Row(row: HomeRow, onOpenTitle: (String) -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(row.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(row.items, key = { _, t -> t.id }) { i, t ->
                Box(Modifier.width(if (i == 9) 190.dp else 160.dp).height(150.dp).clickable { onOpenTitle(t.id) }) {
                    Text(
                        "${i + 1}",
                        modifier = Modifier.align(Alignment.BottomStart).offset(y = 22.dp),
                        fontFamily = Bebas, fontSize = 150.sp, color = Color(0xFF8C8C8C),
                        style = TextStyle(drawStyle = Stroke(width = 6f, join = StrokeJoin.Round)),
                        letterSpacing = (-8).sp,
                    )
                    PosterCard(t, Modifier.align(Alignment.BottomEnd).width(100.dp), showBadge = false) { onOpenTitle(t.id) }
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(
    header: String,
    items: List<ContinueItem>,
    onPlay: (ContinueItem) -> Unit,
    onInfo: (ContinueItem) -> Unit,
    onRemove: (ContinueItem) -> Unit,
) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(header, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.title.id }) { ci -> ContinueCard(ci, { onPlay(ci) }, { onInfo(ci) }, { onRemove(ci) }) }
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, onPlay: () -> Unit, onInfo: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1F1F1F))) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clickable(onClick = onPlay)) {
            AsyncImage(model = item.title.poster ?: item.title.backdrop, contentDescription = item.title.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            PlayCircle(Modifier.align(Alignment.Center), size = 46.dp)
            if (item.season != null) {
                Text(
                    "S${item.season}:B${item.episode}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 8.dp),
                )
            }
            ProgressLine(item.progress?.fraction ?: 0f, Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onInfo, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Info, "Bilgi", tint = GColors.TextSecondary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Daha fazla", tint = GColors.TextSecondary, modifier = Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = GColors.Card) {
                    DropdownMenuItem(text = { Text("Bölümler ve Bilgi", color = Color.White) }, onClick = { menu = false; onInfo() })
                    DropdownMenuItem(text = { Text("Satırdan Kaldır", color = Color.White) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}


/** Üst sağ: İndirilenler ve 🔔 Bildirimler (okunmamış sayısıyla) — Ana Sayfa, Ara ve Benim G-Flix'im'de ortak */
@Composable
fun TopIcons(onDownloads: () -> Unit, onNotifications: () -> Unit, unseen: Int) {
    IconButton(onClick = onDownloads) { Icon(Icons.Outlined.Download, "İndirilenler", tint = Color.White, modifier = Modifier.size(28.dp)) }
    Box(Modifier.padding(end = 6.dp)) {
        IconButton(onClick = onNotifications) { Icon(Icons.Outlined.Notifications, "Bildirimler", tint = Color.White, modifier = Modifier.size(28.dp)) }
        if (unseen > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(20.dp).clip(CircleShape).background(Color(0xFFE50914)),
            contentAlignment = Alignment.Center) {
            Text(if (unseen > 9) "9+" else "$unseen", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}
