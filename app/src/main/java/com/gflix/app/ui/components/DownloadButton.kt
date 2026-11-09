package com.gflix.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.appContainer
import com.gflix.app.data.Library
import com.gflix.app.data.ProfileData
import com.gflix.app.data.Title
import com.gflix.app.offline.DlState
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch

/** İndirme durumu simgesi (bölüm satırı ve detay butonu ortak). */
@Composable
private fun DownloadIcon(state: DlState?, percent: Float, size: Dp) {
    when (state) {
        null -> Icon(Icons.Outlined.Download, "İndir", tint = Color.White, modifier = Modifier.size(size))
        DlState.Done -> Icon(Icons.Filled.CheckCircle, "İndirildi", tint = Color(0xFF2F80ED), modifier = Modifier.size(size))
        DlState.Failed -> Icon(Icons.Filled.ErrorOutline, "Hata", tint = Color(0xFFFF6B6B), modifier = Modifier.size(size))
        DlState.WaitingWifi -> Icon(Icons.Outlined.WifiOff, "Wi-Fi bekleniyor", tint = GColors.TextSecondary, modifier = Modifier.size(size))
        DlState.Queued -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(size * 0.8f))
        DlState.Preparing -> Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { percent.coerceAtLeast(0.02f) }, color = Color(0xFFF5A623), trackColor = Color(0x55FFFFFF), strokeWidth = 2.5.dp, modifier = Modifier.size(size))
            Text("${(percent * 100).toInt()}", color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold)
        }
        DlState.Downloading -> Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { percent.coerceAtLeast(0.02f) }, color = Color(0xFF2F80ED), trackColor = Color(0x55FFFFFF), strokeWidth = 2.5.dp, modifier = Modifier.size(size))
            Text("${(percent * 100).toInt()}", color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun stateText(state: DlState?, percent: Float, series: Boolean): String = when (state) {
    null -> if (series) "Bölümü İndir" else "İndir"
    DlState.Done -> "İndirildi"
    DlState.Failed -> "İndirilemedi — tekrar dene"
    DlState.WaitingWifi -> "Wi-Fi bekleniyor (mobil veri kullanılmaz)"
    DlState.Queued -> "Sırada…"
    DlState.Preparing -> "Bilgisayar küçültüyor %${(percent * 100).toInt()}"
    DlState.Downloading -> "İndiriliyor %${(percent * 100).toInt()}"
}

/**
 * İndir/İndirildi. İlk dokunuş indirir; indirilmiş ya da inerken dokununca silme sorusu.
 * [compact]: bölüm satırındaki küçük simge.
 */
@Composable
fun DownloadButton(title: Title, season: Int?, episode: Int?, data: ProfileData, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val downloads = remember { context.appContainer.downloads }
    val items by downloads.items.collectAsState()
    val key = Library.progressKey(title.id, season, episode)
    val item = items[key]
    var confirm by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<List<Int>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val live = (if (season == null) title.stream else Library.findEpisode(title, season, episode)?.episode?.stream)?.live == true
    fun start(h: Int) {
        downloads.add(title, season, episode, data.audioLang, h)
        Toast.makeText(context, if (live) "Bilgisayar yayını hazırlıyor" + (if (h > 0) " (${h}p)" else "") + ", indirme birazdan başlar (sadece Wi-Fi'de)."
            else "İndirme başladı. Sadece Wi-Fi'de iner, mobil veri kullanılmaz.", Toast.LENGTH_SHORT).show()
    }
    val onClick: () -> Unit = {
        when (item?.state) {
            null -> when {
                !live || downloads.quality > 0 -> start(downloads.quality)
                else -> scope.launch {
                    // Kaç p indirilsin: yayının sunduğu kaliteler sorulur (tek kalite varsa sormadan)
                    val hs = runCatching { com.gflix.app.data.PcServer.qualities(title.id, season, episode) }.getOrDefault(emptyList())
                    if (hs.size <= 1) start(0) else ask = hs
                }
            }
            DlState.Failed -> { downloads.remove(key); downloads.add(title, season, episode, data.audioLang) }
            else -> confirm = true
        }
    }
    if (compact) {
        Box(modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(8.dp), contentAlignment = Alignment.Center) {
            DownloadIcon(item?.state, item?.percent ?: 0f, 24.dp)
        }
    } else {
        Row(
            modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF262626)).clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadIcon(item?.state, item?.percent ?: 0f, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(stateText(item?.state, item?.percent ?: 0f, title.isSeries), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
    ask?.let { hs ->
        var remember by remember { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask = null },
            containerColor = GColors.Surface,
            title = { Text("İndirme kalitesi", color = Color.White) },
            text = {
                androidx.compose.foundation.layout.Column {
                    hs.forEach { h ->
                        Text("${h}p" + when { h >= 2000 -> " · 4K"; h >= 1000 -> " · Full HD (en yüksek, en büyük dosya)"; h >= 700 -> " · HD"; else -> " · Standart (en küçük dosya)" },
                            color = Color.White, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                                if (remember) downloads.quality = h
                                ask = null; start(h)
                            }.padding(vertical = 12.dp, horizontal = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { remember = !remember }.padding(top = 6.dp)) {
                        androidx.compose.material3.Checkbox(remember, { remember = it })
                        Text("Hep bunu kullan (Ayarlar'dan değiştirilir)", color = GColors.TextSecondary, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { ask = null }) { Text("Vazgeç", color = Color.White) } },
        )
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = GColors.Surface,
            title = { Text(if (item?.state == DlState.Done) "İndirilen silinsin mi?" else "İndirme iptal edilsin mi?", color = Color.White) },
            text = { Text(item?.meta?.name ?: title.title, color = GColors.TextSecondary) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { downloads.remove(key); confirm = false }) { Text("Sil", color = Color(0xFFFF6B6B)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Vazgeç", color = Color.White) } },
        )
    }
}
