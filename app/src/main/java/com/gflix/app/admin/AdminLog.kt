package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.data.AppJson
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bilgisayarın canlı kaydı: {"seq": son no, "first": tampondaki ilk no, "lines": [[no, zaman, metin], …]} */
@Serializable
data class LiveLogChunk(val seq: Long = 0, val first: Long = 0, val lines: List<List<kotlinx.serialization.json.JsonElement>> = emptyList(), val at: Long = 0, val host: String = "")

data class LogLine(val seq: Long, val time: Long, val text: String, val id: Long = 0)

internal fun LiveLogChunk.parsed(): List<LogLine> = lines.mapNotNull { l ->
    runCatching {
        LogLine(
            (l[0] as kotlinx.serialization.json.JsonPrimitive).content.toLong(),
            (l[1] as kotlinx.serialization.json.JsonPrimitive).content.toLong(),
            (l[2] as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }.getOrNull()
}

/** Satır rengi: hata kırmızı, uyarı turuncu, başarı yeşil, ayrıntı soluk. */
private fun lineColor(t: String): Color = when {
    t.contains("❌") || t.contains("Traceback") || t.contains("Error") || t.contains("hata", ignoreCase = true) -> Color(0xFFFF6B6B)
    t.contains("⚠") || t.contains("⏳") || t.contains("429") -> Color(0xFFE8A33D)
    t.contains("✓") || t.contains("✅") || t.contains("■ İş bitti") -> Color(0xFF7BD88F)
    t.startsWith("    ") || t.startsWith("mkvmerge:") -> Color(0xFF8A8A8A)
    else -> Color(0xFFDDDDDD)
}

/**
 * Canlı Kayıt: Linux bilgisayarda `gflix-kayit` ile görülen satırlar telefonda akar.
 * Aynı Wi-Fi'deyse doğrudan bilgisayardan (1 sn), değilse GitHub üzerinden (panel açıkken ~5 sn gecikmeyle).
 */
@Composable
fun AdminLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val lines = remember { mutableStateListOf<LogLine>() }
    var mode by remember { mutableStateOf("Bağlanılıyor…") }
    var error by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var last by remember { mutableStateOf(0L) }
    var nextId by remember { mutableStateOf(1L) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun add(l: LogLine) { lines.add(l.copy(id = nextId++)) }

    fun take(chunk: LiveLogChunk) {
        if (last > 0 && chunk.seq < last) {     // bilgisayar yeniden başladı: numaralar baştan
            add(LogLine(-1, System.currentTimeMillis() / 1000, "──── bilgisayardaki işçi yeniden başladı ────"))
            last = 0
        }
        val new = chunk.parsed().filter { it.seq > last }
        if (new.isEmpty()) return
        if (last > 0 && new.first().seq > last + 1) add(LogLine(-1, new.first().time, "… (arada ${new.first().seq - last - 1} satır kaçtı)"))
        new.forEach(::add)
        last = new.last().seq
        if (lines.size > 4000) lines.removeRange(0, lines.size - 3000)
    }

    LaunchedEffect(Unit) {
        if (com.gflix.app.data.PcServer.configured) {
            // Yeni düzen: kayıt doğrudan bilgisayardan (ev ağı ya da Tailscale), GitHub yok
            mode = "🖥️ Bilgisayardan canlı (${com.gflix.app.data.PcServer.summary})"
            while (isActive) {
                runCatching { AppJson.decodeFromString(LiveLogChunk.serializer(), com.gflix.app.data.PcServer.logText(last)) }
                    .onSuccess { error = null; if (!paused) take(it) }
                    .onFailure { error = it.message }
                delay(1000)
            }
            return@LaunchedEffect
        }
        while (isActive) {
            val gh = runCatching { repo.gh() }.getOrElse { error = it.message; return@LaunchedEffect }
            val token = repo.store.current().writeToken.orEmpty()
            // 1) Ev ağı: doğrudan bilgisayardan
            val lan = runCatching { LanClient.find(gh, token) }.getOrNull()
            if (lan != null) {
                mode = "📶 Wi-Fi'den canlı (${lan.address})"
                error = null
                var fails = 0
                while (isActive && fails < 5) {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            LanClient.http.newCall(Request.Builder().url("${lan.base}/log?after=$last").header("X-GFlix-Key", lan.key).build()).execute().use { r ->
                                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                                AppJson.decodeFromString(LiveLogChunk.serializer(), r.body!!.string())
                            }
                        }
                    }.onSuccess { if (!paused) take(it) }.isSuccess
                    fails = if (ok) 0 else fails + 1
                    delay(1000)
                }
                continue    // Wi-Fi koptu: yeniden dene / GitHub'a geç
            }
            // 2) GitHub üzerinden: "izliyorum" işareti + bilgisayarın yazdığı son satırlar
            mode = "☁️ GitHub üzerinden (~5-10 sn gecikmeli)"
            var lastWatch = 0L
            var rounds = 0
            while (isActive && rounds < 24) {     // 2 dakikada bir Wi-Fi yeniden denenir
                rounds++
                runCatching {
                    val rel = gh.releaseByTag("pclog") ?: throw IllegalStateException(
                        "Bilgisayar bu özelliği henüz almadı. Bilgisayar boşta olduğunda kendini günceller.")
                    val now = System.currentTimeMillis() / 1000
                    if (now - lastWatch > 30) { gh.updateRelease(rel.id, name = "watch:$now"); lastWatch = now }
                    if (rel.body.isNotBlank()) {
                        val c = AppJson.decodeFromString(LiveLogChunk.serializer(), rel.body)
                        if (!paused) take(c)
                        if (lines.isEmpty()) mode = "☁️ GitHub üzerinden — bilgisayar ilk satırları gönderiyor (20 sn kadar)…"
                        else mode = "☁️ GitHub üzerinden (~5-10 sn gecikmeli)"
                    }
                    error = null
                }.onFailure { error = it.message }
                delay(5000)
            }
        }
    }

    val shown by remember(filter) { derivedStateOf { if (filter.isBlank()) lines.toList() else lines.filter { it.text.contains(filter, ignoreCase = true) } } }
    // En alttaysa yeni satırlarla birlikte aşağı kay; kullanıcı yukarı kaydırdıysa dokunma
    val atBottom by remember { derivedStateOf { val i = listState.layoutInfo; i.visibleItemsInfo.lastOrNull()?.index ?: 0 >= i.totalItemsCount - 3 } }
    LaunchedEffect(shown.size) { if (shown.isNotEmpty() && (atBottom || shown.size < 60) && !paused) listState.scrollToItem(shown.size - 1) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AdminTopBar("Canlı Kayıt", onBack)
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(mode, color = GColors.TextSecondary, fontSize = 12.sp)
            error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Ara / süz (ör. ❌, 429, Hızlı Adam)") }, singleLine = true,
                    colors = gTextFieldColors(), modifier = Modifier.weight(1f))
                Chip(if (paused) "▶ Devam" else "⏸ Durdur") { paused = !paused }
                Chip("⬇") { if (shown.isNotEmpty()) scope.launch { listState.scrollToItem(shown.size - 1) } }
            }
            Text("${lines.size} satır" + if (filter.isNotBlank()) " · ${shown.size} eşleşen" else "", color = GColors.TextMuted, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0D0D0D))) {
            if (shown.isEmpty()) Text(if (lines.isEmpty()) "Satırlar bekleniyor…" else "Eşleşen satır yok", color = GColors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center))
            LazyColumn(state = listState, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { l ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(if (l.time > 0) fmt.format(Date(l.time * 1000)) else "", color = Color(0xFF5F5F5F), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp, top = 1.dp))
                        Text(l.text, color = lineColor(l.text), fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF333333)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}
