package com.gflix.app.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.launch

@Composable
fun AdminTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun AdminSection(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = modifier)
}

@Composable
fun LangDropdown(selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)).clickable(enabled) { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Langs.label(selected), color = if (enabled) Color.White else GColors.TextMuted, fontSize = 13.sp)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = GColors.Card) {
            Langs.choices.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name, color = Color.White) }, onClick = { open = false; onSelect(code) })
            }
        }
    }
}

@Composable
fun <T> SimpleDropdown(label: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)).clickable { open = true }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = GColors.Card) {
            options.forEach { (v, name) -> DropdownMenuItem(text = { Text(name, color = Color.White) }, onClick = { open = false; onSelect(v) }) }
        }
    }
}

@Composable
fun ConfirmDialog(text: String, confirm: String = "Sil", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, color = Color.White) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = GColors.Red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç", color = Color.White) } },
        containerColor = GColors.Card,
    )
}

/** TMDB'de arama yapıp posterleriyle seçtirir. */
@Composable
fun TmdbPickerDialog(tmdb: Tmdb, initialQuery: String, tv: Boolean, year: Int?, onPick: (TmdbResult) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var isTv by remember { mutableStateOf(tv) }
    var results by remember { mutableStateOf<List<TmdbResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    fun search() {
        if (query.isBlank()) return
        status = "Aranıyor…"
        scope.launch {
            runCatching { tmdb.search(if (isTv) "tv" else "movie", query.trim(), year) }
                .onSuccess { results = it; status = if (it.isEmpty()) "Sonuç yok. İngilizce adını dene." else "" }
                .onFailure { status = "Hata: ${it.message}" }
        }
    }
    LaunchedEffect(Unit) { search() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GColors.Surface,
        title = { Text("TMDB'de Ara", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, colors = gTextFieldColors(),
                    trailingIcon = { IconButton(onClick = ::search) { Icon(Icons.Filled.Search, "Ara", tint = Color.White) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(vertical = 8.dp)) {
                    listOf(false to "Film", true to "Dizi").forEach { (v, l) ->
                        Text(
                            l, color = if (isTv == v) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (isTv == v) Color.White else Color(0xFF333333))
                                .clickable { isTv = v; search() }.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                if (status.isNotEmpty()) Hint(status)
                LazyColumn(Modifier.height(360.dp)) {
                    items(results, key = { it.id }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(r.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.width(46.dp).height(69.dp).clip(RoundedCornerShape(3.dp)).background(GColors.Card))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title + (r.year?.let { " ($it)" } ?: ""), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                if (r.originalTitle.isNotBlank() && r.originalTitle != r.title) Hint(r.originalTitle)
                                Hint("TMDB #${r.id}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat", color = Color.White) } },
    )
}

/**
 * Yayın zamanı seçici: "Hemen yayınla" ya da tarih + saat (telefonun saat diliminde).
 * value/onChange epoch saniye; null = hemen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReleaseTimeField(value: Long?, onChange: (Long?) -> Unit, modifier: Modifier = Modifier, label: String = "Yayın zamanı") {
    var step by remember { mutableStateOf(0) } // 0 kapalı, 1 tarih, 2 saat
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    val zone = java.util.TimeZone.getDefault()
    val text = value?.let { java.text.SimpleDateFormat("d MMMM yyyy EEEE, HH:mm", com.gflix.app.data.TR).format(java.util.Date(it * 1000)) }
    Column(modifier) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text ?: "Hemen yayınla", color = if (text != null) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(if (text != null) Color.White else Color(0xFF333333))
                    .clickable { step = 1 }.padding(horizontal = 14.dp, vertical = 12.dp),
            )
            if (value != null) TextButton(onClick = { onChange(null) }) { Text("Hemen", color = GColors.TextSecondary) }
        }
        Hint(if (value != null) "Bu saate kadar \"Yakında\"da fragmanıyla durur; saati gelince kendiliğinden yayına girer." else "Tarih seçersen dosyalar yüklense bile o saate kadar gizli kalır.")
    }
    if (step == 1) {
        // DatePicker UTC gece yarısı döndürür
        val initial = (value ?: (System.currentTimeMillis() / 1000 + 86400)) * 1000
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = initial }
        val utcMidnight = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = utcMidnight)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = { TextButton(onClick = { pickedDay = state.selectedDateMillis; step = if (pickedDay != null) 2 else 0 }) { Text("Saat seç") } },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Vazgeç") } },
        ) { androidx.compose.material3.DatePicker(state) }
    }
    if (step == 2) {
        val cur = java.util.Calendar.getInstance(zone).apply { timeInMillis = (value ?: (System.currentTimeMillis() / 1000)) * 1000 }
        val state = androidx.compose.material3.rememberTimePickerState(
            initialHour = if (value != null) cur.get(java.util.Calendar.HOUR_OF_DAY) else 20,
            initialMinute = if (value != null) cur.get(java.util.Calendar.MINUTE) else 0, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { step = 0 },
            containerColor = GColors.Surface,
            title = { Text("Yayın saati", color = Color.White) },
            text = { androidx.compose.material3.TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val day = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedDay ?: 0 }
                    val local = java.util.Calendar.getInstance(zone).apply {
                        clear(); set(day.get(java.util.Calendar.YEAR), day.get(java.util.Calendar.MONTH), day.get(java.util.Calendar.DAY_OF_MONTH), state.hour, state.minute)
                    }
                    onChange(local.timeInMillis / 1000); step = 0
                }) { Text("Tamam", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Vazgeç", color = GColors.TextSecondary) } },
        )
    }
}

/** Kütüphaneden yapım seçtirir (yeni bölüm / yakındakilere medya). */
@Composable
fun TitlePickerDialog(
    title: String, items: List<com.gflix.app.data.Title>, subtitle: (com.gflix.app.data.Title) -> String, empty: String,
    onPick: (com.gflix.app.data.Title) -> Unit, onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q, items) { if (q.isBlank()) items else com.gflix.app.data.Library.search(items, q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GColors.Surface,
        title = { Text(title, color = Color.White) },
        text = {
            Column {
                if (items.size > 6) OutlinedTextField(q, { q = it }, placeholder = { Text("Ara") }, singleLine = true, colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth())
                if (items.isEmpty()) Hint(empty)
                LazyColumn(Modifier.height(380.dp)) {
                    items(shown, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(t.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.width(46.dp).height(69.dp).clip(RoundedCornerShape(3.dp)).background(GColors.Card))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Hint(subtitle(t))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat", color = Color.White) } },
    )
}
