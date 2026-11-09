package com.gflix.app.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.data.Library
import com.gflix.app.data.TR
import com.gflix.app.data.Title
import com.gflix.app.ui.components.EmptyMessage
import com.gflix.app.ui.components.GMark
import com.gflix.app.ui.components.IconAction
import com.gflix.app.ui.components.ReminderAction
import com.gflix.app.ui.components.TitleLogo
import com.gflix.app.ui.components.TrailerView
import com.gflix.app.ui.components.canAutoplayTrailer
import com.gflix.app.ui.theme.Bebas
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

private val TABS = listOf("🍿  Yakında", "📺  Yakında Gelen Diziler", "🆕  Yeni Eklenenler", "🔥  Herkes Bunu İzliyor", "🔟  İlk 10 Dizi", "🔟  İlk 10 Film")

@Composable
fun NewHotScreen(lib: LibraryState, onOpenTitle: (String) -> Unit, onSearch: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Yakında boşsa "Yakında Gelen Diziler"le, o da boşsa "Yeni Eklenenler"le açılır
    var tab by rememberSaveable {
        mutableIntStateOf(if (lib.comingSoon.isNotEmpty()) 0 else if (lib.comingSoonSeries.isNotEmpty()) 1 else 2)
    }
    val list = remember(lib.titles, lib.comingSoon, lib.comingSoonSeries, tab) {
        when (tab) {
            0 -> lib.comingSoon
            1 -> lib.comingSoonSeries
            2 -> lib.titles.sortedByDescending { it.lastChange }
            3 -> Library.byPopularity(lib.titles)
            4 -> Library.top10(lib.titles.filter { it.isSeries })
            else -> Library.top10(lib.titles.filter { !it.isSeries })
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Yeni ve Popüler", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Ara", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(TABS) { i, label ->
                val sel = i == tab
                Text(
                    label, color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color.Transparent)
                        .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                if (tab == 0) EmptyMessage("Yakında gelecek bir şey yok", "Yeni bir film ya da dizi duyurulduğunda burada fragmanıyla göreceksin.")
                else if (tab == 1) EmptyMessage("Devamı beklenen dizi yok", "İzlediğin bir dizinin yeni bölümü ya da sezonu duyurulduğunda burada göreceksin.")
                else EmptyMessage("Burada henüz bir şey yok", "Yeni içerik eklendiğinde burada göreceksin.")
            }
        } else {
            val listState = rememberLazyListState()
            val autoplay = remember(lib.profile) { canAutoplayTrailer(context, lib.profile) }
            // Ekranın ortasına en yakın kartın fragmanı oynar
            val activeId by remember(list) {
                derivedStateOf {
                    if (listState.isScrollInProgress) return@derivedStateOf null
                    val info = listState.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 3 - center) }?.key as? String
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                    NewHotItem(
                        title = t,
                        playTrailer = autoplay && t.id == activeId && t.trailer != null,
                        rank = if (tab >= 4) i + 1 else null,
                        soon = if (tab <= 1) lib.rawCatalog?.titles?.firstOrNull { it.id == t.id }.let { raw ->
                            val at = lib.nextRelease(t.id)
                            // Devamı gelen dizi: "🆕 Yeni bölümler: 1–15. bölümler 7 Kasım Cuma 20:00" ya da "Yeni bölümler yakında"
                            if (raw != null && Library.isContinuingSeries(raw, lib.now)) Soon(at, true, null, label = Library.seriesSoonText(raw, lib.now))
                            else Soon(at, !Library.isUpcoming(t, lib.now) && Library.hasPlayable(t), raw?.let { Library.planText(it, lib.now) },
                                label = if (at == null) raw?.let { Library.newEpisodesText(it, lib.now) } else null)
                        } else null,
                        inList = t.id in lib.data.myList,
                        onToggleList = { scope.launch { lib.container.userStore.toggleMyList(lib.profileId, t.id) } },
                        onPlay = { (lib.find(t.id) ?: t).let { context.playTitle(it, lib.data) } },
                        onOpen = { onOpenTitle(t.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewHotItem(title: Title, playTrailer: Boolean, rank: Int?, inList: Boolean, onToggleList: () -> Unit, onPlay: () -> Unit, onOpen: () -> Unit, soon: Soon? = null) {
    val date = (if (soon != null) soon.at else title.lastChange.takeIf { it > 0 })?.let { Date(it * 1000) }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, end = 12.dp)) {
        Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (rank != null) {
                Text("$rank", fontFamily = Bebas, fontSize = 52.sp, color = Color.White)
            } else if (soon != null && date == null) {
                Text("YAKIN", color = GColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("DA", color = GColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else if (date != null) {
                Text(SimpleDateFormat("MMM", TR).format(date).uppercase(TR).take(3), color = GColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("d", TR).format(date), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(GColors.Card).clickable(onClick = onOpen)) {
                val trailer = title.trailer
                if (playTrailer && trailer != null) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TrailerView(
                        trailer, title.backdrop ?: title.poster, Modifier.fillMaxSize(), controlsPadding = 8.dp, showReplay = true,
                        onFullscreen = { pos -> com.gflix.app.player.TrailerActivity.start(ctx, title.id, trailer.lang, pos) },
                    )
                } else {
                    AsyncImage(model = title.backdrop ?: title.poster, contentDescription = title.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                GMark(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp), size = 22.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(56.dp), contentAlignment = Alignment.CenterStart) {
                    TitleLogo(title, Modifier.fillMaxWidth(0.85f).height(52.dp), textSize = 28.sp, align = TextAlign.Start)
                }
                if (soon != null) {
                    ReminderAction(title, soon.at)
                    if (soon.newEpisode) IconAction(Icons.Filled.PlayArrow, "Oynat", onClick = onPlay)
                } else {
                    IconAction(Icons.Filled.PlayArrow, "Oynat", onClick = onPlay)
                    IconAction(if (inList) Icons.Filled.Check else Icons.Filled.Add, "Listem", onClick = onToggleList)
                }
                IconAction(Icons.Outlined.Info, "Bilgi", onClick = onOpen)
            }
            Spacer(Modifier.height(6.dp))
            if (soon != null) {
                Text(soon.label ?: Library.releaseText(soon.at, title.isSeries, soon.newEpisode), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                soon.plan?.let { Text("🗓️ $it", color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp)) }
                Spacer(Modifier.height(6.dp))
            } else if (date != null && rank == null) {
                Text(
                    if (title.isSeries) "Yeni bölüm: " + SimpleDateFormat("d MMMM", TR).format(date) else SimpleDateFormat("d MMMM", TR).format(date) + " tarihinde eklendi",
                    color = GColors.TextSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(title.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            com.gflix.app.ui.components.TitleNotes(title, Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(4.dp))
            if (title.overview.isNotBlank()) {
                Text(title.overview, color = GColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (title.genres.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(title.genres.take(4).joinToString("  •  "), color = Color.White, fontSize = 12.sp)
            }
            com.gflix.app.ui.components.LanguageLine(title, Modifier.padding(top = 6.dp))
        }
    }
}

/** Yakında kartı bilgisi: yayın zamanı (tarihsizse null) ve dizinin yeni bölümü mü (dizi izlenebilir, sıradaki bölüm bekleniyor). */
private data class Soon(val at: Long?, val newEpisode: Boolean, val plan: String? = null, val label: String? = null)
