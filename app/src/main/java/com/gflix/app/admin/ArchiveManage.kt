package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.gflix.app.archive.ArchiveCatalog
import com.gflix.app.archive.ArchivePublishWorker
import com.gflix.app.archive.TmdbMeta
import com.gflix.app.data.CatalogStats
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch

/**
 * archive.org düzeninde yapım yönetimi: TMDB bilgilerini yenile / başka TMDB kaydıyla eşleştir, yayın zamanı,
 * fragman / tema müziği / bölüm / yapım kaldırma. Değişiklik telefondaki katalogda hemen görünür, archive.org'a
 * birkaç saniye içinde yazılır. Kaldırılan kayıtların dosyaları archive.org'daki item'larda durur.
 */
@Composable
fun ArchiveManageScreen(titleId: String, onBack: () -> Unit, onUploadTrailer: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { AdminRepo(context) }
    val scope = rememberCoroutineScope()
    val catalog by context.appContainer.catalogRepository.catalog.collectAsState()
    val t = catalog?.titles?.firstOrNull { it.id == titleId }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var picker by remember { mutableStateOf<Tmdb?>(null) }

    fun act(label: String, block: suspend () -> Unit) {
        busy = true; status = "$label…"
        scope.launch {
            status = runCatching { block() }.fold({ "✓ $label tamam." }, { "⚠ ${it.message}" })
            busy = false
        }
    }

    /** TMDB'den yeniden çeker; yüklü videolar, bölümler, fragman, tema ve zamanlama korunur. */
    suspend fun refreshMeta(tmdbId: Int) {
        val tmdb = repo.tmdb() ?: throw IllegalStateException("Önce Stüdyo Ayarları'na TMDB anahtarını gir.")
        val cur = t ?: return
        val meta = TmdbMeta(tmdb)
        val fresh = meta.title(cur.id, cur.isSeries, tmdbId) ?: throw IllegalStateException("TMDB'de bulunamadı (#$tmdbId).")
        val seasons = if (cur.isSeries) cur.seasons.map { s ->
            s.copy(episodes = s.episodes.map { e ->
                val m = runCatching { meta.episode(tmdbId, s.number, e.number) }.getOrNull()
                if (m == null) e else e.copy(title = m.title ?: e.title, overview = m.overview ?: e.overview, still = m.still ?: e.still, runtime = m.runtime ?: e.runtime)
            })
        } else cur.seasons
        val c = ArchiveCatalog(context)
        c.edit { cat ->
            with(c) {
                cat.mapTitle(cur.id) { old ->
                    fresh.copy(
                        stream = old.stream, seasons = seasons, theme = old.theme,
                        // elle yüklenmiş (eski) fragman varsa korunur; yoksa TMDB'nin YouTube fragmanları
                        trailer = if (hasUploadedTrailer(old)) old.trailer else fresh.trailer,
                        trailers = if (hasUploadedTrailer(old)) old.trailers else fresh.trailers,
                        releaseAt = old.releaseAt, comingSoon = old.comingSoon, releasePlan = old.releasePlan, releaseNote = old.releaseNote,
                        notes = old.notes, badges = old.badges, added = old.added, updated = old.updated,
                    )
                }
            }
        }
        ArchivePublishWorker.enqueue(context, delaySec = 5)
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AdminTopBar(t?.title ?: "Yapım", onBack)
        if (t == null) { Hint("Yapım katalogda yok.", Modifier.padding(16.dp)); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(t.poster, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(80.dp).height(120.dp).clip(RoundedCornerShape(4.dp)).background(GColors.Card))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Hint(listOfNotNull(if (t.isSeries) "Dizi" else "Film", t.year?.toString(), t.tmdbId?.let { "TMDB #$it" } ?: "TMDB eşleşmesi yok").joinToString(" · "))
                    Hint("Boyut: " + CatalogStats.size(ArchiveCatalog.sizeOf(t.stream) + t.seasons.sumOf { s -> s.episodes.sumOf { ArchiveCatalog.sizeOf(it.stream) } }))
                    Text(t.overview, color = GColors.TextSecondary, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(enabled = !busy && t.tmdbId != null, onClick = { act("TMDB bilgileri yenileme") { refreshMeta(t.tmdbId!!) } }) {
                    Text("↻ TMDB'den yenile", color = Color.White)
                }
                TextButton(enabled = !busy, onClick = { scope.launch { picker = repo.tmdb() ?: run { status = "⚠ Önce TMDB anahtarını gir."; null } } }) {
                    Text("🔍 Başka kayıtla eşleştir", color = Color.White)
                }
            }
            ReleaseTimeField(t.releaseAt, { at -> act("Yayın zamanı") { repo.setRelease(t.id, at) } }, Modifier.padding(vertical = 8.dp))

            AdminSection("Fragman ve tema müziği")
            Hint("Fragmanlar TMDB'den (YouTube) otomatik gelir; \"TMDB'den yenile\" ile güncellenir.")
            val trailers = (listOfNotNull(t.trailer) + t.trailers).distinctBy { com.gflix.app.archive.TrailerKey.of(it) }
            Hint(if (trailers.isEmpty()) "Fragman yok." else "Fragmanlar: " + trailers.joinToString { com.gflix.app.data.Library.languageName(it.lang ?: "und") })
            Hint(if (t.theme == null) "Tema müziği yok." else "Tema müziği var (${CatalogStats.size(ArchiveCatalog.sizeOf(t.theme))}).")
            Row {
                TextButton(onClick = { onUploadTrailer(t.id) }) { Text("+ Tema müziği yükle", color = Color.White) }
                if (trailers.isNotEmpty()) TextButton(enabled = !busy, onClick = { confirm = "Fragmanlar katalogdan kaldırılsın mı?" to { repo.removeTrailer(t.id) } }) {
                    Text("Fragmanı kaldır", color = GColors.TextSecondary)
                }
                if (t.theme != null) TextButton(enabled = !busy, onClick = {
                    confirm = "Tema müziği kaldırılsın mı?" to {
                        val c = ArchiveCatalog(context)
                        c.edit { cat -> with(c) { cat.mapTitle(t.id) { it.copy(theme = null) } } }
                        ArchivePublishWorker.enqueue(context, delaySec = 5)
                    }
                }) { Text("Temayı kaldır", color = GColors.TextSecondary) }
            }

            if (t.isSeries) {
                AdminSection("Bölümler")
                if (t.seasons.isEmpty()) Hint("Henüz bölüm yok.")
                t.seasons.forEach { s ->
                    Text("${s.number}. Sezon", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    s.episodes.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("%02d. %s".format(e.number, e.title ?: "Bölüm ${e.number}") + if (e.stream == null) "  (video yok)" else "",
                                color = if (e.stream == null) GColors.TextMuted else Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(enabled = !busy, onClick = {
                                confirm = "S%02dE%02d katalogdan kaldırılsın mı?".format(s.number, e.number) to { repo.deleteEpisode(t.id, s.number, e.number) }
                            }) { Text("Kaldır", color = GColors.TextSecondary, fontSize = 12.sp) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { confirm = "\"${t.title}\" katalogdan kaldırılsın mı? (archive.org'daki dosyalar durur)" to { repo.deleteTitle(t.id); onBack() } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A), contentColor = Color(0xFFFF6B6B)),
            ) { Text("Yapımı kaldır", fontWeight = FontWeight.Bold) }
            if (status.isNotEmpty()) Text(status, color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }

    confirm?.let { (text, action) ->
        ConfirmDialog(text, confirm = "Evet", onConfirm = { act("İşlem") { action() } }, onDismiss = { confirm = null })
    }
    val tm = picker
    if (tm != null && t != null) {
        TmdbPickerDialog(tm, t.title, t.isSeries, t.year,
            onPick = { r -> picker = null; act("TMDB eşleştirme") { refreshMeta(r.id) } },
            onDismiss = { picker = null })
    }
}

private fun hasUploadedTrailer(t: com.gflix.app.data.Title) = (t.trailers + listOfNotNull(t.trailer)).any { it.direct != null || it.master.isNotBlank() }
