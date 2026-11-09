package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gflix.app.data.PcServer
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private data class Follow(val key: String, val title: String, val url: String, val season: Int, val first: Int, val min: Int, val found: Int, val checked: Long)

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()

/** 📡 YouTube takibi: Türk dizilerinin kanalına/listesine yeni TAM bölüm gelince Onay bekleyenler'e (eşleşmiş) düşer. */
@Composable
fun YtFollowButton() {
    var open by remember { mutableStateOf(false) }
    Button(
        onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1F1F), contentColor = Color.White),
    ) { Text("📡  YouTube takibi  ·  Dizinin kanalına yeni bölüm gelince ekle", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    if (open) YtFollowDialog { open = false }
}

@Composable
private fun YtFollowDialog(onDismiss: () -> Unit) {
    val bars = hostBars()
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Follow>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }   // (tmdb, ad, yıl)
    var pick by remember { mutableStateOf<Pair<String, String>?>(null) }
    var url by remember { mutableStateOf("") }
    var season by remember { mutableStateOf("1") }
    var first by remember { mutableStateOf("1") }
    var min by remember { mutableStateOf("30") }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching { PcServer.follows() }.onSuccess { o ->
            list = (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                Follow(x.str("key"), x.str("title"), x.str("url"), x.str("season").toIntOrNull() ?: 1, x.str("first").toIntOrNull() ?: 1,
                    x.str("min").toIntOrNull() ?: 30, x.str("found").toIntOrNull() ?: 0, x.str("checked").toLongOrNull() ?: 0)
            }
        }.onFailure { msg = "Bilgisayara ulaşılamadı: ${it.message}" }
    }
    LaunchedEffect(Unit) { reload() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(bars).imePadding().padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📡 YouTube takibi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Kapat", color = GColors.TextSecondary) }
            }
            Text("Dizinin resmi YouTube kanalını ya da bölüm oynatma listesini ver. Bilgisayar 30 dk'da bir bakar; yeni TAM bölüm " +
                "(fragman/kesit/kısa değil, en az verdiğin dakika) gelince Onay bekleyenler'e dizinin sezon/bölümüyle eşleşmiş düşer. " +
                "Eklerken kanaldaki eski videolar atlanır.", color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            msg?.let { Text(it, color = GColors.Match, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }

            // ---- yeni takip
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF161616)).padding(10.dp)) {
                val p = pick
                if (p == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(q, { q = it }, singleLine = true, colors = gTextFieldColors(), placeholder = { Text("Dizi adı (TMDB)") }, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { PcServer.tmdbSearch(q.trim()) }.onSuccess { o ->
                                    hits = (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                                        val x = e as? JsonObject ?: return@mapNotNull null
                                        if (x.str("kind") != "tv") null else Triple(x.str("tmdb"), x.str("title"), x.str("year"))
                                    }
                                    if (hits.isEmpty()) msg = "TMDB'de dizi bulunamadı"
                                }.onFailure { msg = it.message }
                            }
                        }) { Text("Ara", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                    hits.take(8).forEach { h ->
                        Text("📺 ${h.second}" + (if (h.third.isNotBlank()) " (${h.third})" else ""), color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { pick = h.first to h.second; hits = emptyList() }.padding(vertical = 7.dp))
                    }
                } else {
                    Text("📺 ${p.second}", color = Color.White, fontWeight = FontWeight.Bold)
                    OutlinedTextField(url, { url = it }, singleLine = true, colors = gTextFieldColors(),
                        placeholder = { Text("Kanal ya da oynatma listesi linki", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        NumField("Sezon", season) { season = it }
                        NumField("Sezonun 1. bölümü", first) { first = it }
                        NumField("En az dk", min) { min = it }
                    }
                    Text("'Sezonun 1. bölümü': kanalda bu sezonun ilk bölümü kaçıncı bölüm diye geçiyor (ör. 2. sezon kanalda 40. bölümle başlıyorsa 40).",
                        color = GColors.TextMuted, fontSize = 11.sp)
                    Row {
                        TextButton(onClick = { pick = null }) { Text("Vazgeç", color = GColors.TextSecondary) }
                        TextButton(enabled = !busy && url.contains("youtu"), onClick = {
                            busy = true
                            scope.launch {
                                runCatching { PcServer.followAdd(url.trim(), p.first, p.second, season.toIntOrNull() ?: 1, first.toIntOrNull() ?: 1, min.toIntOrNull() ?: 30) }
                                    .onSuccess { msg = "📡 ${p.second} takipte. Yeni tam bölüm gelince Onay bekleyenler'e düşer."; pick = null; url = ""; q = ""; reload() }
                                    .onFailure { msg = "Eklenemedi: ${it.message}" }
                                busy = false
                            }
                        }) { Text(if (busy) "Kanal okunuyor…" else "Takibe al", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // ---- takipler
            list.forEach { f ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF161616)).padding(10.dp)) {
                    Text("📺 ${f.title} · ${f.season}. sezon", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(f.url.substringAfter("://").take(60), color = GColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                    Text("${f.found} bölüm bulundu · en az ${f.min} dk · son bakış " +
                        (if (f.checked > 0) java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.checked * 1000)) else "—"),
                        color = GColors.TextSecondary, fontSize = 12.sp)
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { PcServer.followCheck(f.key) }.onSuccess { n -> msg = if (n > 0) "📥 $n yeni bölüm Onay bekleyenler'e düştü" else "Yeni tam bölüm yok"; reload() }
                                    .onFailure { msg = it.message }
                            }
                        }) { Text("Şimdi bak", color = Color.White, fontSize = 12.sp) }
                        TextButton(onClick = { scope.launch { runCatching { PcServer.followRemove(f.key) }; reload() } }) {
                            Text("Takibi bırak", color = GColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumField(label: String, v: String, set: (String) -> Unit) {
    OutlinedTextField(v, { set(it.filter(Char::isDigit).take(4)) }, singleLine = true, colors = gTextFieldColors(),
        label = { Text(label, fontSize = 10.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(100.dp).padding(end = 6.dp))
}
