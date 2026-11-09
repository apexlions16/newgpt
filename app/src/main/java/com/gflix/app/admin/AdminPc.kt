package com.gflix.app.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.data.AppJson
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.Locale

/** İstek hakkı koruması: kalan hak [min]'in altındayken yeni işler ve yüklemeler yenilenmeye kadar bekler (TV etkilenmez). */
@Serializable
data class QuotaGuard(val on: Boolean = true, val min: Int = 900, val remaining: Int? = null, val reset: Long = 0, val paused: Boolean = false)

/** Gece modu: işler sadece [start]–[end] arasında başlar (bilgisayarın saatiyle). */
@Serializable
data class NightMode(val on: Boolean = false, val start: String = "04:00", val end: String = "10:00")

/** Linux bilgisayar işçisinin (tools/gflix_worker.py) "pcworker" Release'ine dakikada bir yazdığı durum. */
@Serializable
data class PcStatus(
    val host: String = "", val seen: Long = 0, val busy: String? = null, val version: String = "", val free: Long = 0, @kotlinx.serialization.SerialName("max_jobs") val maxJobs: Int = 1, val jobs: Int = 0,
    /** Ev ağındaki adresleri (192.168.1.20:8765): telefon aynı Wi-Fi'deyse dosyaları doğrudan buraya gönderir */
    val lan: List<String> = emptyList(),
    /** Stüdyo'da seçilen "aynı siteden aynı anda kaç iş" ve şu an uygulanan (site zorlandıysa 1) */
    @kotlinx.serialization.SerialName("site_jobs") val siteJobs: Int = 1,
    @kotlinx.serialization.SerialName("site_jobs_now") val siteJobsNow: Int = 1,
    @kotlinx.serialization.SerialName("backoff_until") val backoffUntil: Long = 0,
    @kotlinx.serialization.SerialName("start_delay_min") val startDelayMin: Int = 0,
    val night: NightMode? = null,
    @kotlinx.serialization.SerialName("in_window") val inWindow: Boolean = true,
    @kotlinx.serialization.SerialName("quota_guard") val quotaGuard: QuotaGuard? = null,
    /** Son işlerin süre kayıtları (tools/gflix_worker.py, kalıcı istatistik) */
    val stats: List<JobStat> = emptyList(),
) {
    val online: Boolean get() = System.currentTimeMillis() / 1000 - seen < 180
}

object PcJobs {
    const val PREFIX = "pcjob-"
    const val HEARTBEAT = "pcworker"

    fun newTag(): String = PREFIX + java.util.UUID.randomUUID().toString().take(12)

    // Stüdyo'daki kartlar (bilgisayar durumu, iş süreleri, ayarlar, Wi-Fi) aynı bilgiyi okur: 20 sn içinde tek istek
    @Volatile private var cached: Pair<Long, PcStatus?>? = null
    private val cacheLock = kotlinx.coroutines.sync.Mutex()

    suspend fun status(gh: GitHubAdmin, maxAgeMs: Long = 20_000): PcStatus? = cacheLock.withLock {
        cached?.takeIf { System.currentTimeMillis() - it.first < maxAgeMs }?.let { return@withLock it.second }
        runCatching { gh.releaseByTag(HEARTBEAT)?.body?.takeIf { it.isNotBlank() }?.let(::parse) }.getOrNull()
            .also { cached = System.currentTimeMillis() to it }
    }

    /** Ayar kaydedilince eski bilgi gösterilmesin */
    fun invalidate() { cached = null }

    /**
     * İşlerin Release'leri: önce tek istekle son 100 Release, bulunamayanlar (çok eski iş) tek tek.
     * Sonuç: etiket → Release (silinmişse null).
     */
    suspend fun jobReleases(gh: GitHubAdmin, refs: List<LinkJobRef>): Map<String, Release?> {
        if (refs.isEmpty()) return emptyMap()
        val recent = gh.recentReleases().associateBy { it.id }
        return refs.associate { j -> j.tag to (recent[j.releaseId] ?: gh.releaseById(j.releaseId)) }
    }

    /** Kalp atışı gövdesi; süre kayıtlarından biri bozuk olsa bile durum okunur (bozuk kayıt atlanır). */
    fun parse(body: String): PcStatus? {
        runCatching { return AppJson.decodeFromString(PcStatus.serializer(), body) }
        return runCatching {
            val obj = AppJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            val stats = (obj["stats"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
                runCatching { AppJson.decodeFromJsonElement(JobStat.serializer(), el) }.getOrNull()
            }
            val rest = kotlinx.serialization.json.JsonObject(obj - "stats")
            AppJson.decodeFromJsonElement(PcStatus.serializer(), rest).copy(stats = stats)
        }.getOrNull()
    }
}

/** "🖥️ Bilgisayar çevrimiçi" satırı; 30 saniyede bir yenilenir. */
@Composable
fun PcStatusLine(repo: AdminRepo, modifier: Modifier = Modifier) {
    var st by remember { mutableStateOf<PcStatus?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            st = runCatching { PcJobs.status(repo.gh()) }.getOrNull()
            loaded = true
            delay(30_000)
        }
    }
    val s = st
    val (text, color) = when {
        !loaded -> "Bilgisayar durumu okunuyor…" to GColors.TextSecondary
        s == null -> "🖥️ Bilgisayar henüz kurulmadı. Linux'ta: bash linux/install.sh (README'de anlatılıyor)" to Color(0xFFFFB74D)
        !s.online -> "🖥️ ${s.host} kapalı (son görülme ${ago(s.seen)}). İş sıraya girer, bilgisayar açılınca kendiliğinden başlar." to Color(0xFFFFB74D)
        s.busy != null && Regex("^(\\d+) iş:").find(s.busy) != null ->
            "🖥️ ${s.host} çevrimiçi, şu an aynı anda ${Regex("^(\\d+) iş:").find(s.busy)!!.groupValues[1]} iş yapıyor; yeni işler sıraya girer." to GColors.Match
        s.busy != null && s.maxJobs > 1 && s.jobs < s.maxJobs ->
            "🖥️ ${s.host} çevrimiçi, şu an ${s.jobs.coerceAtLeast(1)} iş yapıyor (aynı anda en fazla ${s.maxJobs}); yeni iş hemen başlayabilir." to GColors.Match
        s.busy != null -> "🖥️ ${s.host} çevrimiçi, şu an başka bir iş yapıyor; bu iş sıraya girer." to GColors.Match
        else -> "🖥️ ${s.host} çevrimiçi ve boşta" + (if (s.lan.isNotEmpty()) " · 📶 aynı Wi-Fi'deysen dosyalar doğrudan gider" else "") + " · ${String.format(Locale.US, "%.0f GB", s.free / 1e9)} boş alan" to GColors.Match
    }
    Text(text, color = color, fontSize = 12.sp, modifier = modifier.padding(top = 4.dp))
}

private fun ago(epoch: Long): String {
    val m = (System.currentTimeMillis() / 1000 - epoch) / 60
    return when {
        m < 60 -> "$m dk önce"
        m < 60 * 24 -> "${m / 60} saat önce"
        else -> "${m / 1440} gün önce"
    }
}
