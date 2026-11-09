package com.gflix.app.ui.screens

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gflix.app.data.Title
import com.gflix.app.ui.theme.GColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 🔔 Bildirim satırı: yeni yapım ya da yeni bölüm (katalogdan; sunucuya gerek yok) */
data class GNotice(val title: Title, val head: String, val body: String, val at: Long)

object Notices {
    private const val PREFS = "gflix.notices"

    /** Son 30 gün: yeni eklenen yapımlar ve yeni bölümü gelen diziler, en yeni önce */
    fun list(titles: List<Title>, now: Long = System.currentTimeMillis() / 1000): List<GNotice> =
        titles.mapNotNull { t ->
            val added = t.added ?: 0L
            val upd = t.updated ?: added
            when {
                added > 0 && now - added < 30 * 86400 && upd - added < 3600 ->
                    GNotice(t, "Yeni İçerik", t.title, added)
                t.isSeries && upd > 0 && now - upd < 30 * 86400 -> {
                    val last = t.seasons.flatMap { s -> s.episodes.filter { it.stream != null }.map { s.number to it.number } }.maxWithOrNull(compareBy({ it.first }, { it.second }))
                    GNotice(t, "Yeni Bölüm", t.title + (last?.let { " · ${it.first}. Sezon ${it.second}. Bölüm" } ?: ""), upd)
                }
                else -> null
            }
        }.filter { it.at <= now + 60 }.sortedByDescending { it.at }.take(40)

    fun unseen(c: Context, items: List<GNotice>): Int {
        val seen = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("seen", 0L)
        return items.count { it.at > seen }
    }

    fun markSeen(c: Context, items: List<GNotice>) {
        val max = items.maxOfOrNull { it.at } ?: return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("seen", max).apply()
    }
}

@Composable
fun NotificationsScreen(items: List<GNotice>, onBack: () -> Unit, onOpenTitle: (String) -> Unit) {
    val context = LocalContext.current
    val seenBefore = androidx.compose.runtime.remember { context.getSharedPreferences("gflix.notices", Context.MODE_PRIVATE).getLong("seen", 0L) }
    LaunchedEffect(items) { Notices.markSeen(context, items) }
    val fmt = SimpleDateFormat("d MMM", Locale("tr"))
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
            Text("Bildirimler", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Icon(Icons.Outlined.Notifications, null, tint = GColors.TextMuted, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Son 30 günde yeni bir şey yok", color = GColors.TextSecondary, fontSize = 15.sp)
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
            items(items, key = { it.title.id + it.at }) { n ->
                Row(Modifier.fillMaxWidth().clickable { onOpenTitle(n.title.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (n.at > seenBefore) Color(0xFFE50914) else Color.Transparent))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(GColors.Card)) {
                        AsyncImage(n.title.backdrop ?: n.title.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.head, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(n.body, color = GColors.TextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmt.format(Date(n.at * 1000)), color = GColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
