package com.gflix.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import com.gflix.app.data.Library
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gflix.app.appContainer
import com.gflix.app.data.Presentation
import com.gflix.app.data.Stream
import com.gflix.app.data.Title
import com.gflix.app.ui.components.isOnWifi
import com.gflix.app.ui.theme.GFlixTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Fragmanı tam ekranda, sesli oynatır (asıl oynatıcının Netflix arayüzüyle); dil (TR/EN) değiştirilebilir, bitince "Tekrar Oynat". */
@OptIn(UnstableApi::class)
class TrailerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val c = appContainer
        val catalog = c.catalogRepository.catalog.value ?: c.catalogRepository.loadCachedBlocking()
        val state = runBlocking { c.userStore.current() }
        val title = catalog?.titles?.firstOrNull { it.id == intent.getStringExtra(EXTRA_TITLE) }
            ?.let { Presentation.localize(it, state.activeProfile) }
        val trailers = title?.let { TrailerMedia.all(it) }.orEmpty()
        if (title == null || trailers.isEmpty()) {
            Toast.makeText(this, "Fragman bulunamadı", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (c.tokens.token.isNullOrBlank()) c.tokens.token = state.token ?: c.builtInToken
        val startLang = intent.getStringExtra(EXTRA_LANG)
        val startPos = intent.getLongExtra(EXTRA_POSITION, 0L)
        val firstPick = trailers.firstOrNull { it.lang == startLang } ?: TrailerMedia.pick(title, startLang)!!
        if (firstPick.youtube != null && !com.gflix.app.data.PcServer.configured) {
            // TMDB'den gelen YouTube fragmanı: YouTube'un gömülü oynatıcısıyla, sesli ve denetimli
            setContent { GFlixTheme { YouTubeTrailerScreen(trailers.filter { it.youtube != null }, firstPick, onClose = { finish() }) } }
            return
        }
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo
        val first = trailers.firstOrNull { it.lang == startLang } ?: TrailerMedia.pick(title, startLang)!!
        load(exo, first, startPos)
        setContent { GFlixTheme { TrailerScreen(exo, title, trailers, first, onLoad = { s -> load(exo, s, 0L) }, onClose = { finish() }) } }
    }

    private fun load(exo: ExoPlayer, s: Stream, pos: Long) {
        exo.setMediaSource(TrailerMedia.mediaSource(this, s, allowUhd = isOnWifi(this)), pos)
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LANG = "lang"
        private const val EXTRA_POSITION = "position"

        fun start(context: Context, titleId: String, lang: String?, positionMs: Long) {
            context.startActivity(
                Intent(context, TrailerActivity::class.java)
                    .putExtra(EXTRA_TITLE, titleId).putExtra(EXTRA_LANG, lang).putExtra(EXTRA_POSITION, positionMs),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TrailerScreen(player: ExoPlayer, title: Title, trailers: List<Stream>, initial: Stream, onLoad: (Stream) -> Unit, onClose: () -> Unit) {
    var current by remember { mutableStateOf(initial) }
    var ended by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    val wifi = isOnWifi(androidx.compose.ui.platform.LocalContext.current)
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                buffering = state == Player.STATE_BUFFERING
                if (ended) controlsVisible = true
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition; duration = player.duration.coerceAtLeast(0L); buffered = player.bufferedPosition
            delay(250)
        }
    }
    LaunchedEffect(controlsVisible, isPlaying, interaction, scrub) {
        if (controlsVisible && isPlaying && scrub == null) { delay(3500); controlsVisible = false }
    }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // tek dokunuş: kontroller; çift dokunuş: 10 sn sar
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { controlsVisible = !controlsVisible; interaction++ },
                onDoubleTap = { o -> if (o.x < size.width / 2) player.seekBack() else player.seekForward(); interaction++ },
            )
        })
        if (buffering && !ended) {
            CircularProgressIndicator(color = GColors.Red, strokeWidth = 4.dp, modifier = Modifier.align(Alignment.Center).size(64.dp))
        }
        AnimatedVisibility(controlsVisible || ended, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.22f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
                ),
            ) {
                // üst: geri + başlık (asıl oynatıcıdaki gibi ortada)
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White, modifier = Modifier.size(28.dp)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Fragman · ${TrailerMedia.label(current)}" + if (TrailerMedia.uses4k(current, wifi)) " · 4K" else "",
                            color = GColors.TextSecondary, fontSize = 13.sp, maxLines = 1,
                        )
                    }
                    if (com.gflix.app.data.PcServer.configured && current.youtube != null) TrailerFix(title, current, onClose)
                    else Spacer(Modifier.width(48.dp))
                }
                // orta: 10 sn geri · oynat/duraklat (bitince tekrar oynat) · 10 sn ileri
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(72.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekBack(); interaction++ }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Filled.Replay10, "10 sn geri", tint = Color.White, modifier = Modifier.size(52.dp))
                    }
                    when {
                        ended -> IconButton(onClick = { ended = false; player.seekTo(0); player.play(); interaction++ }, modifier = Modifier.size(84.dp)) {
                            Icon(Icons.Filled.Replay, "Tekrar oynat", tint = Color.White, modifier = Modifier.size(68.dp))
                        }
                        buffering -> Spacer(Modifier.size(84.dp))
                        else -> IconButton(onClick = { if (player.isPlaying) player.pause() else player.play(); interaction++ }, modifier = Modifier.size(84.dp)) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Duraklat" else "Oynat", tint = Color.White, modifier = Modifier.size(72.dp))
                        }
                    }
                    IconButton(onClick = { player.seekForward(); interaction++ }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Filled.Forward10, "10 sn ileri", tint = Color.White, modifier = Modifier.size(52.dp))
                    }
                }
                // alt: kırmızı ilerleme çubuğu + kalan süre, dil seçimi
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SeekBar(position, buffered, duration, scrub, { scrub = it; interaction++ }, { player.seekTo(it); scrub = null; interaction++ }, Modifier.weight(1f))
                        Spacer(Modifier.width(14.dp))
                        Text(Library.formatClock((duration - (scrub ?: position)).coerceAtLeast(0)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (trailers.size > 1) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Subtitles, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            trailers.forEach { t ->
                                val sel = TrailerMedia.key(t) == TrailerMedia.key(current)
                                Text(
                                    TrailerMedia.label(t), color = if (sel) Color.White else GColors.TextSecondary, fontSize = 13.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { if (!sel) { current = t; ended = false; onLoad(t) }; interaction++ }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/** ⚑ Yanlış fragman: bilgisayar bu videoyu bir daha seçmez (sıradaki aday gelir) ya da verilen YouTube linkini kullanır. */
@Composable
private fun TrailerFix(title: Title, current: Stream, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Text("⚑", color = Color.White, fontSize = 22.sp) }
    if (!open) return
    fun send(bad: String?, url: String?) {
        busy = true
        scope.launch {
            val r = runCatching {
                com.gflix.app.data.PcServer.fixTrailer(title.id, bad = bad, url = url, lang = current.lang ?: "tr")
                context.appContainer.catalogRepository.refresh()
            }
            busy = false
            open = false
            Toast.makeText(context, r.fold({ "Fragman düzeltildi" }, { it.message ?: "Gönderilemedi" }), Toast.LENGTH_SHORT).show()
            if (r.isSuccess) onClose()
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        containerColor = GColors.Surface,
        title = { Text("Fragman yanlış mı?", color = Color.White) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("\"Yanlış\" dersen bu video ${title.title} için bir daha seçilmez, sıradaki fragman gelir. " +
                    "Doğrusunu biliyorsan YouTube linkini yapıştır.", color = GColors.TextSecondary, fontSize = 13.sp)
                androidx.compose.material3.OutlinedTextField(link, { link = it }, label = { Text("YouTube linki (isteğe bağlı)") }, singleLine = true)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !busy, onClick = {
                if (link.isNotBlank()) send(current.youtube, link.trim()) else send(current.youtube, null)
            }) { Text(if (link.isNotBlank()) "Bu linki kullan" else "Yanlış, sıradakini getir") }
        },
        dismissButton = { androidx.compose.material3.TextButton(enabled = !busy, onClick = { open = false }) { Text("Vazgeç") } },
    )
}

@Composable
private fun YouTubeTrailerScreen(trailers: List<Stream>, initial: Stream, onClose: () -> Unit) {
    var current by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.runtime.key(current.youtube) {
            com.gflix.app.ui.components.YouTubeEmbed(current.youtube!!, muted = false, controls = true,
                modifier = Modifier.fillMaxSize(), onEnded = onClose)
        }
        androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trailers.size > 1) trailers.forEach { t ->
                val sel = t.youtube == current.youtube
                Text(TrailerMedia.label(t), color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color.Black.copy(alpha = 0.6f))
                        .clickable { current = t }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            androidx.compose.material3.IconButton(onClick = onClose) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Kapat", tint = Color.White)
            }
        }
    }
}
