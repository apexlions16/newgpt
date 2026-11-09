package com.gflix.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.gflix.app.data.PcServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gflix.app.appContainer

/** Yapım → tema müziği (YouTube kimliği); uygulama açıkken tekrar sorulmasın */
private val themeCache = HashMap<String, String?>()

/**
 * Yapım sayfasında arkada tema müziği (Plex/Emby'deki gibi). Ses bilgisayardan anlık gelir (YouTube'dan sadece ses,
 * /yta): telefona da bilgisayara da dosya yazılmaz, GitHub'a hiçbir şey gitmez. Kısık sesle başlar, ~75 sn sonra
 * yavaşça kapanır. [enabled] false olunca (fragman açıldı, profil kapattı) susar.
 */
@Composable
fun ThemeMusic(titleId: String, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // archive.org'a yüklenmiş tema müziği (yapım kaydında); yoksa bilgisayardan YouTube sesi
    val archived = remember(titleId) {
        context.appContainer.catalogRepository.title(titleId)?.theme?.parts?.singleOrNull()
    }
    if (archived == null && !PcServer.configured) return
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var vid by remember(titleId) { mutableStateOf(themeCache[titleId]) }
    var stopped by remember(titleId) { mutableStateOf(false) }
    var playing by remember(titleId) { mutableStateOf(false) }

    LaunchedEffect(titleId) {
        if (archived != null) { vid = "ia"; return@LaunchedEffect }
        if (!themeCache.containsKey(titleId)) {
            vid = runCatching { PcServer.theme(titleId) }.getOrNull()
            themeCache[titleId] = vid
        }
    }
    val v = vid
    if (v == null || !enabled || stopped) return

    val player = remember(v) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(archived ?: PcServer.liveUrl("/yta/$v").orEmpty()))
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (!stopped) player.play()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); player.release() }
    }
    LaunchedEffect(player) {
        playing = true
        for (i in 1..20) { player.volume = 0.35f * i / 20; delay(100) }         // yavaşça aç
        delay(73_000)
        for (i in 20 downTo 0) { player.volume = 0.35f * i / 20; delay(150) }   // yavaşça kapat
        player.pause(); playing = false; stopped = true
    }
    if (playing) Row(modifier) {
        Text("♪ Sustur", color = Color.White, fontSize = 11.sp,
            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                .clickable { player.pause(); stopped = true; playing = false }.padding(horizontal = 10.dp, vertical = 5.dp))
        if (archived == null) Text("♪ Başka müzik", color = Color.White, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                .clickable {
                    player.pause()
                    scope.launch {
                        vid = runCatching { PcServer.theme(titleId, bad = v) }.getOrNull()
                        themeCache[titleId] = vid
                    }
                }.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}
