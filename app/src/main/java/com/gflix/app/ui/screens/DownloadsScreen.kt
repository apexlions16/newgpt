package com.gflix.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.gflix.app.appContainer
import com.gflix.app.data.CatalogStats
import com.gflix.app.offline.DlState
import com.gflix.app.startPlayback
import com.gflix.app.ui.components.DownloadButton
import com.gflix.app.ui.components.EmptyMessage
import com.gflix.app.ui.components.PlayCircle
import com.gflix.app.ui.theme.GColors

/** İndirilenler: internetsiz izleme (sadece bu uygulamada oynar). */
@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenTitle: (String) -> Unit) {
    val context = LocalContext.current
    val lib = rememberLibraryState()
    val downloads = remember { context.appContainer.downloads }
    val items by downloads.items.collectAsState()
    LaunchedEffect(Unit) { downloads.refresh() }
    val list = remember(items) { items.values.sortedByDescending { it.meta.addedAt } }
    val used = remember(items) { downloads.usedBytes() }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
            Text("İndirilenler", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Sadece Wi-Fi'de iner, mobil veri kullanılmaz. İndirilenler şifreli olarak bu uygulamada durur; yalnızca G-Flix içinde, internetsiz izlenir." +
                if (used > 0) "  ·  ${CatalogStats.size(used)} kullanılıyor" else "",
            color = GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        DownloadSettings(downloads)
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize()) { EmptyMessage("Henüz indirilen yok", "Bir film ya da bölümün yanındaki ⬇ ile indir; yolda internetsiz izle.") }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            items(list, key = { it.meta.key }) { it ->
                val m = it.meta
                val title = lib.find(m.titleId)
                val done = it.state == DlState.Done
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(GColors.Card)
                            .clickable(enabled = done) {
                                val p = lib.data.progress[com.gflix.app.data.Library.progressKey(m.titleId, m.season, m.episode)]
                                context.startPlayback(m.titleId, m.season, m.episode, if (p != null && !p.isFinished) p.positionMs else 0L)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(m.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (done) PlayCircle(size = 36.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { onOpenTitle(m.titleId) }) {
                        Text(m.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (it.state) {
                                DlState.Done -> CatalogStats.size(it.bytes) + (if (m.height > 0) " · ${m.height}p" else "") +
                                    (m.versionLang?.let { l -> " · " + com.gflix.app.data.Library.languageName(l) } ?: "")
                                DlState.Downloading -> "İndiriliyor %${(it.percent * 100).toInt()}"
                                DlState.WaitingWifi -> "Wi-Fi bekleniyor"
                                DlState.Queued -> "Sırada"
                                DlState.Preparing -> "Bilgisayar ${if (m.height > 0) "${m.height}p'ye " else ""}küçültüyor %${(it.percent * 100).toInt()} · hazır olunca iner"
                                DlState.Failed -> "İndirilemedi"
                            },
                            color = GColors.TextSecondary, fontSize = 12.sp,
                        )
                    }
                    if (title != null) DownloadButton(title, m.season, m.episode, lib.data, compact = true)
                }
            }
        }
    }
}


/** ⚙️ Akıllı indirme ve indirme kalitesi (Netflix'teki gibi) */
@Composable
private fun DownloadSettings(downloads: com.gflix.app.offline.Downloads) {
    var smart by remember { androidx.compose.runtime.mutableStateOf(downloads.smart) }
    var quality by remember { androidx.compose.runtime.mutableIntStateOf(downloads.quality) }
    var pick by remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(GColors.Card)) {
        Row(Modifier.fillMaxWidth().clickable { smart = !smart; downloads.smart = smart }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🧠 Akıllı İndirme", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("İndirdiğin bölümü bitirince o silinir, sıradaki bölüm kendiliğinden iner (Wi-Fi'de)", color = GColors.TextSecondary, fontSize = 12.sp)
            }
            androidx.compose.material3.Switch(smart, { smart = it; downloads.smart = it })
        }
        Row(Modifier.fillMaxWidth().clickable { pick = true }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🎞 İndirme Görüntü Kalitesi", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(if (quality <= 0) "Her seferinde sor" else "${quality}p'ye kadar", color = GColors.TextSecondary, fontSize = 12.sp)
            }
            Text("›", color = Color.White, fontSize = 20.sp)
        }
    }
    if (pick) androidx.compose.material3.AlertDialog(
        onDismissRequest = { pick = false },
        containerColor = GColors.Surface,
        title = { Text("İndirme kalitesi", color = Color.White) },
        text = {
            Column {
                listOf(0 to "Her seferinde sor", 1080 to "Yüksek · 1080p'ye kadar", 720 to "Orta · 720p'ye kadar", 480 to "Standart · 480p'ye kadar (en küçük)").forEach { (v, l) ->
                    Text((if (v == quality) "● " else "○ ") + l, color = Color.White, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { quality = v; downloads.quality = v; pick = false }.padding(vertical = 12.dp))
                }
                Text("Yayında seçtiğin kalite yoksa ona en yakın düşük olan iner.", color = GColors.TextSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = { pick = false }) { Text("Kapat", color = Color.White) } },
    )
}
