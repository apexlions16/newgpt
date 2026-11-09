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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gflix.app.data.Library
import com.gflix.app.ui.components.EmptyMessage
import com.gflix.app.ui.theme.GColors
import com.gflix.app.ui.theme.Inter

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenTitle: (String) -> Unit, asTab: Boolean = false, onDownloads: () -> Unit = {},
                 onNotifications: () -> Unit = {}, unseen: Int = 0) {
    val context = LocalContext.current
    val lib = rememberLibraryState()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query, lib.titles) { Library.search(lib.titles, query) }
    val suggestions = remember(lib.titles) { lib.titles.sortedByDescending { it.rating ?: 0.0 }.take(30) }

    LaunchedEffect(Unit) { if (!asTab) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (asTab) {
                Text("Ara", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 16.dp))
                TopIcons(onDownloads, onNotifications, unseen)
            } else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (asTab) 12.dp else 0.dp).height(48.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(if (asTab) 8.dp else 0.dp)).background(Color(0xFF333333)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = GColors.TextSecondary)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Dizileri, filmleri, türleri ve oyuncuları arayın…", color = GColors.TextSecondary, fontSize = 15.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = Inter),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Temizle", tint = GColors.TextSecondary) }
            }
        }
        if (query.isBlank()) {
            Text("Önerilen Dizi ve Filmler", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(suggestions, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenTitle(t.id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).background(GColors.Card)) {
                            AsyncImage(model = t.backdrop ?: t.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text(
                            t.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        IconButton(onClick = { context.playTitle(t, lib.data) }) {
                            Icon(Icons.Outlined.PlayCircle, "Oynat", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                EmptyMessage("Aradığını bulamadık", "Başka bir dizi, film, tür veya oyuncu adıyla aramayı dene.")
            }
        } else {
            PosterGrid(results, onOpenTitle, Modifier.fillMaxSize(), header = {
                Text("Diziler ve Filmler", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            })
        }
    }
}
