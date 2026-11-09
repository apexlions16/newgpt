package com.gflix.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import com.gflix.app.AppContainer
import com.gflix.app.appContainer
import com.gflix.app.data.Catalog
import com.gflix.app.data.Library
import com.gflix.app.data.Presentation
import com.gflix.app.data.ProfileData
import com.gflix.app.data.Profile
import com.gflix.app.data.Title
import com.gflix.app.data.UserState
import com.gflix.app.startPlayback
import com.gflix.app.ui.components.PosterCard

/** Ekranların ortak kullandığı durum. */
class LibraryState(
    val container: AppContainer,
    val catalog: Catalog?,
    val user: UserState,
    val profile: Profile?,
    val data: ProfileData,
    val titles: List<Title>,
    /** Yakında gelecekler (profil diline göre): henüz hiç izlenemeyen yapımlar */
    val comingSoon: List<Title> = emptyList(),
    /** Ham katalog (zamanlama uygulanmamış): yayın tarihleri için */
    val rawCatalog: Catalog? = catalog,
    val now: Long = Library.nowSec(),
    /** Yakında Gelen Diziler: izlenebilir bölümü olan, yeni bölümü/sezonu beklenen diziler */
    val comingSoonSeries: List<Title> = emptyList(),
) {
    val profileId: String get() = profile?.id.orEmpty()

    /** Yapımın bir sonraki yayın zamanı (yakında / yeni bölüm) */
    fun nextRelease(id: String): Long? = rawCatalog?.titles?.firstOrNull { it.id == id }?.let { Library.nextRelease(it, now) }

    /** Kimliğe göre yapım (profilin dil/kapak tercihi uygulanmış). */
    fun find(id: String): Title? =
        titles.firstOrNull { it.id == id } ?: catalog?.titles?.firstOrNull { it.id == id }?.let { Presentation.localize(it, profile) }
}

@Composable
fun rememberLibraryState(): LibraryState {
    val container = LocalContext.current.appContainer
    val raw by container.catalogRepository.catalog.collectAsState()
    val user by container.userStore.state.collectAsState(initial = UserState())
    val profile = user.activeProfile
    val day = Presentation.today()
    // Dakikada bir: yayın saati gelen yapım uygulama açıkken de kendiliğinden belirir
    val minute by androidx.compose.runtime.produceState(Library.nowSec() / 60) {
        while (true) { kotlinx.coroutines.delay(15_000); value = Library.nowSec() / 60 }
    }
    val catalog = remember(raw, minute) { Library.applySchedule(raw, minute * 60 + 59) }
    val titles = remember(catalog, profile, day) {
        Library.visibleTitles(catalog, profile).map { Presentation.localize(it, profile, day) }
    }
    val soon = remember(raw, profile, day, minute) {
        Library.comingSoonTitles(raw, profile, minute * 60 + 59).map { Presentation.localize(it, profile, day) }
    }
    val soonSeries = remember(raw, profile, day, minute) {
        Library.comingSoonSeries(raw, profile, minute * 60 + 59).map { Presentation.localize(it, profile, day) }
    }
    return LibraryState(container, catalog, user, profile, user.activeData, titles, soon, raw, minute * 60 + 59, comingSoonSeries = soonSeries)
}

/** "Oynat": kaldığı yerden ya da baştan başlatır. */
fun Context.playTitle(title: Title, data: ProfileData) {
    val target = Library.playTarget(title, data) ?: return
    startPlayback(title.id, target.season, target.episode, target.positionMs)
}

fun Context.shareTitle(title: Title) {
    val text = "G-Flix'te \"${title.title}\" izliyorum! 🍿"
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Paylaş"))
}

/** Netflix tarzı tam ekran seçim listesi (kategoriler, sezonlar). */
@Composable
fun FullScreenPicker(items: List<String>, selected: String?, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.93f))) {
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 80.dp, bottom = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                itemsIndexed(items) { i, item ->
                    val isSel = item == selected
                    Text(
                        item,
                        color = if (isSel) Color.White else Color(0xFFB3B3B3),
                        fontSize = if (isSel) 22.sp else 18.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(i); onDismiss() }.padding(vertical = 14.dp),
                    )
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp).size(60.dp)
                    .clip(CircleShape).background(Color.White).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, "Kapat", tint = Color.Black, modifier = Modifier.size(30.dp)) }
        }
    }
}

@Composable
fun PosterGrid(titles: List<Title>, onOpenTitle: (String) -> Unit, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { header() }
        items(titles, key = { it.id }) { t -> PosterCard(t, Modifier.fillMaxWidth()) { onOpenTitle(t.id) } }
    }
}
