package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Bir işin süre kaydı (unix sn). İş durumundaki "timing" ve bilgisayarın kalıcı istatistiği aynı biçimde:
 * start → site (siteden indirme sırası) → dl_start → dl_end → end; approval: onay bekleme (sn); bytes: inen veri.
 */
@Serializable
data class JobStat(
    val tag: String = "", val title: String = "", val kind: String = "", val state: String = "",
    val start: Long = 0, val site: Long = 0, @SerialName("dl_start") val dlStart: Long = 0, @SerialName("dl_end") val dlEnd: Long = 0,
    val end: Long = 0, val approval: Long = 0, val bytes: Long = 0, val items: Int = 0, @SerialName("done_items") val doneItems: Int = 0,
) {
    val total: Long get() = if (start > 0 && end >= start) end - start else 0
    /** Sırada bekleme (önceki iş siteden inerken) */
    val wait: Long get() = if (start > 0 && site >= start) site - start else 0
    val download: Long get() = if (dlStart > 0 && dlEnd >= dlStart) dlEnd - dlStart else 0
    /** İndirme bittikten sonrası: birleştirme + GitHub'a yükleme */
    val upload: Long get() = if (dlEnd > 0 && end >= dlEnd) end - dlEnd else 0
    /** Hazırlık: sayfayı inceleme, TMDB, boyut (onay bekleme hariç) */
    val prep: Long get() = if (dlStart > 0 && site > 0 && dlStart >= site) (dlStart - site - approval).coerceAtLeast(0) else 0
    /** İndirme hızı (bayt/sn) */
    val speed: Long get() = if (download > 0) bytes / download else 0
}

fun dur(sec: Long): String = when {
    sec <= 0 -> "0 sn"
    sec < 60 -> "$sec sn"
    sec < 3600 -> "${sec / 60} dk ${sec % 60} sn"
    else -> "${sec / 3600} sa ${(sec % 3600) / 60} dk"
}

private fun gb(n: Long): String = String.format(Locale.US, "%.1f GB", n / 1e9)
private fun mbps(n: Long): String = String.format(Locale.US, "%.1f MB/sn", n / 1e6)

/** İş kartındaki süre satırı: sürerken geçen süre, bitince aşama aşama. */
fun durationLine(st: LinkStatus): String? {
    val t = st.timing
    val now = System.currentTimeMillis() / 1000
    if (st.state == "running") {
        val start = t?.start?.takeIf { it > 0 } ?: st.started.takeIf { it > 0 } ?: return null
        val parts = mutableListOf("⏱ ${dur(now - start)} sürüyor")
        if (t != null && t.dlStart > 0) parts += if (t.dlEnd > 0) "indirme ${dur(t.download)}" else "indiriliyor ${dur(now - t.dlStart)}"
        return parts.joinToString(" · ")
    }
    if (t == null || t.end == 0L) {
        return if (st.started > 0 && st.updated > st.started) "⏱ Toplam ~${dur(st.updated - st.started)}" else null
    }
    return listOfNotNull(
        "⏱ Toplam ${dur(t.total)}",
        t.wait.takeIf { it >= 5 }?.let { "sırada ${dur(it)}" },
        t.download.takeIf { it > 0 }?.let { "indirme ${dur(it)}" },
        t.upload.takeIf { it > 0 && t.dlEnd > 0 }?.let { "birleştirme+yükleme ${dur(it)}" },
        t.bytes.takeIf { it > 0 }?.let { b -> gb(b) + (t.speed.takeIf { it > 0 }?.let { " (${mbps(it)})" } ?: "") },
    ).joinToString(" · ")
}

/** Stüdyo ana sayfasında: bilgisayarın yaptığı işlerin süre istatistiği (pcworker kalp atışından, son 150 iş). */
@Composable
fun JobStatsCard(repo: AdminRepo) {
    var stats by remember { mutableStateOf<List<JobStat>?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { PcJobs.status(repo.gh()) }.getOrNull()?.let { stats = it.stats }
            delay(60_000)
        }
    }
    val all = stats ?: return
    val done = all.filter { it.state == "done" && it.total > 0 }.sortedByDescending { it.end }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(GColors.Card).clickable { open = !open }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⏱  İş süreleri", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (open) "Gizle" else "Ayrıntı", color = GColors.TextSecondary, fontSize = 13.sp)
        }
        if (done.isEmpty()) {
            Text("Henüz süre kaydı yok. Bilgisayar bundan sonra biten her işin süresini buraya yazar.", color = GColors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
            return@Column
        }
        val dl = done.filter { it.download > 0 }
        val up = done.filter { it.upload > 0 && it.dlEnd > 0 }
        val totalBytes = dl.sumOf { it.bytes }
        val totalDl = dl.sumOf { it.download }
        val dayAgo = System.currentTimeMillis() / 1000 - 86_400
        val today = done.filter { it.end >= dayAgo }
        Spacer(Modifier.height(6.dp))
        StatRow("Biten iş", "${done.size}" + (all.count { it.state == "error" }.takeIf { it > 0 }?.let { " · $it başarısız" } ?: ""))
        StatRow("Ortalama iş süresi", dur(done.sumOf { it.total } / done.size))
        if (dl.isNotEmpty()) StatRow("Ortalama indirme", dur(totalDl / dl.size) + if (totalDl > 0) " · ${mbps(totalBytes / totalDl)}" else "")
        if (up.isNotEmpty()) StatRow("Ortalama birleştirme+yükleme", dur(up.sumOf { it.upload } / up.size))
        if (totalBytes > 0 && totalDl > 0) StatRow("1 GB başına", dur(done.filter { it.bytes > 0 }.let { l -> l.sumOf { it.total } * 1_000_000_000L / l.sumOf { it.bytes }.coerceAtLeast(1) }))
        if (today.isNotEmpty()) StatRow("Son 24 saat", "${today.size} iş · ${gb(today.sumOf { it.bytes })}")
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text("Son işler", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            val fmt = java.text.SimpleDateFormat("d MMM HH:mm", com.gflix.app.data.TR)
            all.filter { it.end > 0 }.sortedByDescending { it.end }.take(30).forEach { j ->
                val icon = when (j.state) { "done" -> "✅"; "partial" -> "⚠️"; "cancelled" -> "🛑"; else -> "❌" }
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row {
                        Text("$icon ${j.title.ifBlank { j.tag }}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(dur(j.total), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        listOfNotNull(
                            fmt.format(java.util.Date(j.end * 1000)),
                            j.wait.takeIf { it >= 5 }?.let { "sırada ${dur(it)}" },
                            j.download.takeIf { it > 0 }?.let { "indirme ${dur(it)}" },
                            j.upload.takeIf { it > 0 && j.dlEnd > 0 }?.let { "yükleme ${dur(it)}" },
                            j.bytes.takeIf { it > 0 }?.let { gb(it) },
                        ).joinToString(" · "),
                        color = GColors.TextSecondary, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(label, color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 🖥️ Bilgisayar ayarları ("pcconfig" Release gövdesi; bilgisayar dakikada bir okur):
 * aynı siteden aynı anda kaç iş, işe başlama gecikmesi, gece modu (işler sadece seçilen saatlerde başlar).
 * Acil işler, iş kartındaki ⚡ Hemen başlat ile gecikmeyi ve gece modunu atlar.
 */
@Composable
fun SiteJobsCard(repo: AdminRepo) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pc by remember { mutableStateOf<PcStatus?>(null) }
    var delayMin by remember { mutableStateOf<Int?>(null) }
    var nightOn by remember { mutableStateOf(false) }
    var nightStart by remember { mutableStateOf("04:00") }
    var nightEnd by remember { mutableStateOf("10:00") }
    var guardOn by remember { mutableStateOf(true) }
    var guardMin by remember { mutableStateOf<Int?>(900) }
    var loaded by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { PcJobs.status(repo.gh()) }.getOrNull()?.let { st ->
                pc = st
                if (!loaded) {
                    loaded = true
                    delayMin = st.startDelayMin
                    st.night?.let { nightOn = it.on; nightStart = it.start; nightEnd = it.end }
                    st.quotaGuard?.let { guardOn = it.on; guardMin = it.min }
                }
            }
            delay(30_000)
        }
    }
    val p = pc ?: return
    fun valid(t: String) = Regex("^([01]?\\d|2[0-3])[:.][0-5]\\d$").matches(t.trim())
    fun norm(t: String) = t.trim().replace('.', ':').let { if (it.length == 4) "0$it" else it }
    fun save(note: String) {
        if (nightOn && (!valid(nightStart) || !valid(nightEnd))) { msg = "Saatleri SS:DD biçiminde yaz (ör. 04:00)."; return }
        msg = "Kaydediliyor…"
        scope.launch {
            runCatching {
                val gh = repo.gh()
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("site_jobs", kotlinx.serialization.json.JsonPrimitive(1))     // sabit: aynı anda tek iş
                    put("start_delay_min", kotlinx.serialization.json.JsonPrimitive(delayMin ?: 0))
                    put("quota_guard", kotlinx.serialization.json.buildJsonObject {
                        put("on", kotlinx.serialization.json.JsonPrimitive(guardOn))
                        put("min", kotlinx.serialization.json.JsonPrimitive(guardMin ?: 900))
                    })
                    put("night", kotlinx.serialization.json.buildJsonObject {
                        put("on", kotlinx.serialization.json.JsonPrimitive(nightOn))
                        put("start", kotlinx.serialization.json.JsonPrimitive(norm(nightStart)))
                        put("end", kotlinx.serialization.json.JsonPrimitive(norm(nightEnd)))
                    })
                }.toString()
                val rel = gh.releaseByTag("pcconfig")
                if (rel == null) gh.ensurePublishedRelease("pcconfig", "G-Flix bilgisayar ayarları", body)
                else gh.updateRelease(rel.id, body = body)
            }.onSuccess { PcJobs.invalidate(); msg = "✓ $note — bilgisayar 1 dakika içinde uygular." }.onFailure { msg = "Kaydedilemedi: ${it.message}" }
        }
    }
    @Composable
    fun chips(values: List<Int>, sel: Int?, label: (Int) -> String, onPick: (Int) -> Unit) {
        Row(Modifier.padding(top = 6.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            values.forEach { n ->
                val on = sel == n
                Text(label(n), color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color(0xFF333333))
                        .clickable { onPick(n) }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(GColors.Card).padding(12.dp)) {
        Text("🖥️  Bilgisayar ayarları", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)

        Text("Aynı anda tek iş", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        Text("Siteden her zaman tek iş iner (sabit). Biri GitHub'a yüklenirken sıradakinin indirmesi başlar; siteye giden yük yine tek iştir.",
            color = GColors.TextSecondary, fontSize = 12.sp)

        Text("İşe başlama gecikmesi", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text("İş gönderildikten bu kadar sonra başlar (acele gerekmiyorsa sorunsuz başlasın diye).", color = GColors.TextSecondary, fontSize = 12.sp)
        chips(listOf(0, 1, 2, 3, 5, 10), delayMin, { if (it == 0) "Hemen" else "$it dk" }) { delayMin = it; save(if (it == 0) "İşler hemen başlar" else "İşler $it dk sonra başlar") }
        Text("Bekleyen işin kartında geri sayım görünür (uygulamadan çıksan da kayar); acilse ⚡ Hemen başlat.", color = GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { nightOn = !nightOn; save(if (nightOn) "Gece modu açık" else "Gece modu kapalı") },
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = nightOn, onCheckedChange = { nightOn = it; save(if (it) "Gece modu açık" else "Gece modu kapalı") },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = GColors.Red))
            Column(Modifier.weight(1f)) {
                Text("🌙 Gece modu", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Sıradaki işler her gün sadece bu saatlerde başlar; başlamış iş saat bitse de tamamlanır. Acil iş için iş kartında ⚡ Hemen başlat.",
                    color = GColors.TextSecondary, fontSize = 12.sp)
            }
        }
        if (nightOn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedTextField(nightStart, { nightStart = it }, label = { Text("Başlangıç") }, singleLine = true,
                colors = com.gflix.app.ui.screens.gTextFieldColors(), modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedTextField(nightEnd, { nightEnd = it }, label = { Text("Bitiş") }, singleLine = true,
                colors = com.gflix.app.ui.screens.gTextFieldColors(), modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { save("Gece modu $nightStart–$nightEnd") }) { Text("Kaydet", color = Color.White, fontWeight = FontWeight.Bold) }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { guardOn = !guardOn; save(if (guardOn) "İstek hakkı koruması açık" else "İstek hakkı koruması kapalı") },
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = guardOn, onCheckedChange = { guardOn = it; save(if (it) "İstek hakkı koruması açık" else "İstek hakkı koruması kapalı") },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = GColors.Red))
            Column(Modifier.weight(1f)) {
                Text("🛡️ İstek hakkı koruması", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Kalan GitHub hakkı bu sınırın altına inince yeni işler başlamaz, süren işlerin yüklemesi durur; hak yenilenince " +
                    "kendiliğinden devam eder. TV'de izleme etkilenmez. Olanlar Canlı Kayıt'ta ve iş kartında yazar.",
                    color = GColors.TextSecondary, fontSize = 12.sp)
            }
        }
        if (guardOn) chips(listOf(500, 900, 1500, 2500), guardMin, { "$it" }) { guardMin = it; save("Koruma sınırı $it") }

        val now = System.currentTimeMillis() / 1000
        p.quotaGuard?.takeIf { it.on && it.paused }?.let { q ->
            Text("⏸ İstek hakkı ${q.remaining} (sınır ${q.min}): yeni işler ve yüklemeler " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(q.reset * 1000)) + "'de devam edecek",
                color = Color(0xFFE8A33D), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            listOfNotNull(
                "Aynı anda 1 iş",
                p.startDelayMin.takeIf { it > 0 }?.let { "$it dk gecikme" },
                p.night?.takeIf { it.on }?.let { n -> if (p.inWindow) "🌙 ${n.start}–${n.end} (şu an başlatıyor)" else "🌙 ${n.start}–${n.end} (yeni işler ${n.start}'te başlar)" },
                if (p.jobs > 0) "${p.jobs} iş sürüyor" else null,
            ).joinToString(" · "),
            color = GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        msg?.let { Text(it, color = GColors.TextSecondary, fontSize = 12.sp) }
        // Sistem takıldıysa: bilgisayara "bütün işleri iptal et, yeni iş beklemeye geç" komutu
        val context = androidx.compose.ui.platform.LocalContext.current
        var confirm by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { confirm = true }, modifier = Modifier.padding(top = 4.dp)) {
            Text("🛑 Tüm işleri iptal et", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        if (confirm) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false }, containerColor = Color(0xFF1F1F1F),
            title = { Text("🛑 Tüm işler iptal edilsin mi?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Bilgisayar süren işi hemen durdurur, sıradakileri kapatır ve yeni iş beklemeye geçer (Wi-Fi'de anında, dışarıdayken 20 sn içinde). Yüklenmiş dosyalara dokunulmaz.", color = GColors.TextSecondary, fontSize = 13.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = false; msg = "🛑 İptal ediliyor…"
                    scope.launch { msg = runCatching { JobsTracker.cancelAll(context, repo) }.getOrElse { "İptal gönderilemedi: ${it.message}" } }
                }) { Text("Hepsini iptal et", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Vazgeç", color = GColors.TextSecondary) } },
        )
    }
}

/**
 * 📡 GitHub istek hakkı (hesap başına, saatte 5000): telefon, TV sunucusu ve bilgisayar aynı hakkı paylaşır.
 * /rate_limit hakkın kendisinden düşmez (ücretsiz), bu yüzden seçilen aralıkta (10 sn / 30 sn / 1 dk) sürekli bakılır.
 *
 * "Zırt pırt değişme" sebebi: GitHub'ın farklı sunucuları aynı anda biraz farklı kalan sayısı dönebiliyor (önbellek);
 * bir cevap 4.924, sonraki 4.931 gibi. Hak bir saat içinde sadece AZALIR; bu yüzden aynı saat dilimi içinde
 * (yenilenme zamanı aynıyken) gösterilen sayı asla yukarı çıkmaz, yenilenince 5000'e döner. Değer ekranlar
 * arasında paylaşılır (sayfaya her girişte sıfırdan başlamaz).
 */
object RateLimitState {
    data class Snap(val remaining: Int, val limit: Int, val reset: Long, val at: Long)
    var snap by mutableStateOf<Snap?>(null)
        private set

    /** Yeni ölçümü birleştir: aynı pencerede (yenilenme ±2 dk) en düşük kalan, yeni pencerede yeni değer. */
    fun merge(remaining: Int, limit: Int, reset: Long, at: Long = System.currentTimeMillis() / 1000): Snap =
        combine(snap, remaining, limit, reset, at).also { snap = it }

    fun combine(old: Snap?, remaining: Int, limit: Int, reset: Long, at: Long): Snap =
        when {
            old == null -> Snap(remaining, limit, reset, at)
            at >= old.reset + 1 && reset > old.reset + 120 -> Snap(remaining, limit, reset, at)       // saat doldu: yenilendi
            kotlin.math.abs(reset - old.reset) <= 120 -> Snap(minOf(old.remaining, remaining), limit, maxOf(old.reset, reset), at)
            reset > old.reset -> Snap(remaining, limit, reset, at)
            else -> old.copy(at = at)     // eski pencereden gelen gecikmiş cevap: yok say
        }
}

@Composable
fun RateLimitCard(repo: AdminRepo) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("gflix.admin.ui", android.content.Context.MODE_PRIVATE) }
    var every by remember { mutableStateOf(prefs.getInt("rate_every", 10)) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(every) {
        while (true) {
            runCatching { repo.gh().rateLimit() }.onSuccess { (rem, lim, reset) -> RateLimitState.merge(rem, lim, reset); err = null }.onFailure { err = it.message }
            repeat(every) { delay(1000); tick = System.currentTimeMillis() / 1000 }     // geri sayım her saniye
        }
    }
    val r = RateLimitState.snap ?: return
    val now = tick
    val left = (r.reset - now).coerceAtLeast(0)
    val remaining = if (left == 0L) r.limit else r.remaining      // yenilenme anı geçtiyse hak dolmuştur (sonraki ölçüm doğrular)
    val frac = if (r.limit > 0) remaining.toFloat() / r.limit else 0f
    val color = when {
        frac < 0.1f -> Color(0xFFFF6B6B)
        frac < 0.3f -> Color(0xFFE8A33D)
        else -> GColors.Match
    }
    fun n(v: Int) = "%,d".format(v).replace(',', '.')
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(GColors.Card).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📡  GitHub istek hakkı", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${n(remaining)} / ${n(r.limit)}", color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF333333))) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
        val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(r.reset * 1000))
        Text(
            "Bu saatte harcanan ${n(r.limit - remaining)} · yenilenme $at (${left / 60} dk ${left % 60} sn sonra ${n(r.limit)}'e döner) · telefon, TV ve bilgisayar ortak kullanır" +
                (if (frac < 0.1f) "\n⚠ Az kaldı: TV'de yeni izleme 'Sıradasın' diyebilir, bilgisayar durum yazmayı seyreltir." else ""),
            color = GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kontrol: ", color = GColors.TextSecondary, fontSize = 12.sp)
            listOf(10 to "10 sn", 30 to "30 sn", 60 to "1 dk").forEach { (v, l) ->
                val on = every == v
                Text(l, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (on) Color.White else Color(0xFF333333))
                        .clickable { every = v; prefs.edit().putInt("rate_every", v).apply() }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("${(now - r.at).coerceAtLeast(0)} sn önce", color = GColors.TextMuted, fontSize = 11.sp)
        }
        Text("Bu kontrol hakkı harcamaz.", color = GColors.TextMuted, fontSize = 11.sp)
        err?.let { Text("Okunamadı: $it", color = Color(0xFFFF6B6B), fontSize = 11.sp) }
    }
}
