package com.gflix.app.player

import android.graphics.Color as AColor
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.foundation.layout.wrapContentSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.gflix.app.R
import com.gflix.app.data.Library
import com.gflix.app.data.RateLimit
import com.gflix.app.ui.components.ProgressLine
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay

private enum class Panel { Audio, Speed, Episodes, Aspect }

/** Görüntü oranı: Orijinal (sığdır), ekranı doldur (kenarlar kırpılır), uzat, sabit 16:9 / 4:3 / 21:9 */
private enum class Aspect(val label: String, val ratio: Float?) {
    Fit("Orijinal (sığdır)", null), Zoom("Ekranı doldur (kırp)", null), Stretch("Uzat (ekrana yay)", null),
    R169("16:9", 16f / 9f), R43("4:3", 4f / 3f), R219("21:9 (sinema)", 21f / 9f)
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(session: PlaybackSession, onExit: () -> Unit) {
    val player = session.player
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var error by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    val aspectCtx = androidx.compose.ui.platform.LocalContext.current
    val aspectPrefs = remember { aspectCtx.getSharedPreferences("gflix.player", android.content.Context.MODE_PRIVATE) }
    var aspect by remember { mutableStateOf(runCatching { Aspect.valueOf(aspectPrefs.getString("aspect", "Fit")!!) }.getOrDefault(Aspect.Fit)) }
    fun setAspect(a: Aspect) { aspect = a; aspectPrefs.edit().putString("aspect", a.name).apply() }
    var seekFeedback by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var subPrefs by remember { mutableStateOf(SubtitleStyle.load(ctx)) }
    var subView by remember { mutableStateOf<androidx.media3.ui.SubtitleView?>(null) }
    // Netflix gibi: kontroller açıkken altyazı yukarı (çubuğun üstüne) çıkar, kapanınca yerine iner
    val subFraction by androidx.compose.animation.core.animateFloatAsState(
        if (controlsVisible && !locked && panel == null) SubtitleStyle.LIFT_CONTROLS else SubtitleStyle.LIFT_NORMAL,
        label = "altyazi",
    )

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { error = null; session.warmNext() }
                if (state == Player.STATE_ENDED && !session.onEnded()) onExit()
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) {
                // Yoğunluk: hata gösterme, "Sıradasın" de; sıra gelince kaldığı yerden devam
                if (RateLimit.blockedIn(e) != null) { session.saveProgress(); waiting = true } else error = describeError(e)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            buffered = player.bufferedPosition
            delay(250)
            if (++tick % 60 == 0) session.saveProgress() // ~15 sn'de bir
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, interaction, panel, scrub) {
        if (controlsVisible && isPlaying && panel == null && scrub == null) {
            delay(4000)
            controlsVisible = false
        }
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) { delay(700); seekFeedback = null }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(AColor.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    subtitleView?.let { subView = it; SubtitleStyle.apply(it, subPrefs, subFraction) }
                }
            },
            update = { v ->
                v.resizeMode = when (aspect) {
                    Aspect.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    Aspect.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL          // uzat ve sabit oranlar: çerçeveyi doldur
                }
                subView?.let { sv -> SubtitleStyle.apply(sv, subPrefs, subFraction) }
            },
            modifier = aspect.ratio?.let { r -> Modifier.align(Alignment.Center).fillMaxSize().wrapContentSize().aspectRatio(r, matchHeightConstraintsFirst = true) }
                ?: Modifier.fillMaxSize(),
        )

        // Dokunma katmanı: tek dokunuş kontrolleri açar/kapatır, çift dokunuş 10 sn sarar; iki parmakla aç/kapat: doldur/orijinal
        Box(
            Modifier.fillMaxSize().pointerInput(locked) {
                if (locked) return@pointerInput
                awaitEachGesture {
                    var zoom = 1f
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        if (ev.changes.count { it.pressed } >= 2) {
                            zoom *= ev.calculateZoom()
                            ev.changes.forEach { it.consume() }
                        }
                    }
                    if (zoom > 1.15f) setAspect(Aspect.Zoom) else if (zoom < 0.87f) setAspect(Aspect.Fit)
                }
            }.pointerInput(locked) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible; interaction++ },
                    onDoubleTap = { o ->
                        if (!locked) {
                            if (o.x < size.width / 2) {
                                session.seekBy(-10_000); seekFeedback = -1 to System.nanoTime()
                            } else {
                                session.seekBy(10_000); seekFeedback = 1 to System.nanoTime()
                            }
                        }
                    },
                )
            },
        )

        seekFeedback?.let { (dir, _) ->
            Box(Modifier.fillMaxSize().padding(horizontal = 80.dp), contentAlignment = if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (dir < 0) Icons.Filled.Replay10 else Icons.Filled.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Text(if (dir < 0) "-10 sn" else "+10 sn", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (buffering && error == null && session.liveStatus == null) {
            CircularProgressIndicator(color = GColors.Red, strokeWidth = 4.dp, modifier = Modifier.align(Alignment.Center).size(64.dp))
            val pf = session.prefetchInfo?.collectAsState()?.value
            if (pf != null && pf.needBps > 0) {
                val mb = { b: Long -> String.format(com.gflix.app.data.TR, "%.1f MB/sn", b / 1_000_000.0) }
                Text(
                    "archive.org: ${mb(pf.speedBps)} · bu video ${mb(pf.needBps)} istiyor" +
                        (if (pf.aheadSec > 0) " · önde ${pf.aheadSec / 60} dk ${pf.aheadSec % 60} sn hazır" else "") +
                        (if (pf.speedBps in 1 until pf.needBps) "\nBağlantı videonun hızına yetmiyor: biraz duraklatıp dolmasını bekle." else ""),
                    color = Color.White, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(top = 140.dp, start = 24.dp, end = 24.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        session.liveStatus?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!session.liveFailed) {
                    CircularProgressIndicator(color = GColors.Red, strokeWidth = 4.dp, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                } else {
                    Text("Yayın açılamadı", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                }
                Text(msg, color = GColors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                if (session.liveFailed) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { session.retry() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(4.dp),
                        ) { Text("Tekrar Dene", fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = onExit,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                            shape = RoundedCornerShape(4.dp),
                        ) { Text("Çık") }
                    }
                }
            }
        }

        AnimatedVisibility(controlsVisible && !locked && panel == null, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                session = session,
                isPlaying = isPlaying,
                buffering = buffering,
                position = position,
                duration = duration,
                buffered = buffered,
                scrub = scrub,
                speed = speed,
                onScrub = { scrub = it; interaction++ },
                onSeek = { session.seekTo(it); scrub = null; interaction++ },
                onPlayPause = { if (player.isPlaying) player.pause() else player.play(); interaction++ },
                onBack = onExit,
                onPanel = { panel = it },
                onLock = { locked = true; interaction++ },
            )
        }

        if (locked && controlsVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { locked = false; interaction++ }.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text("Ekran Kilitli", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Kilidi açmak için dokun", color = GColors.TextSecondary, fontSize = 12.sp)
            }
        }

        val next = session.nextItem
        if (next != null && duration > 0 && duration - position in 1..25_000 && panel == null && !locked) {
            Button(
                onClick = { session.playNext() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 96.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Sonraki Bölüm", fontWeight = FontWeight.Bold)
            }
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Oynatılamadı", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(msg, color = GColors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { error = null; session.retry() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("Tekrar Dene", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = onExit,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("Çık") }
                }
            }
        }

        if (waiting) {
            QueueScreen(session.current?.heading.orEmpty(), resume = true,
                onReady = { waiting = false; player.prepare(); player.play() }, onCancel = onExit)
        }

        when (panel) {
            Panel.Audio -> AudioSubtitlePanel(session, tracks, subPrefs, onStyle = { subPrefs = it; SubtitleStyle.save(ctx, it) }) { panel = null }
            Panel.Speed -> SpeedPanel(speed, onSelect = { speed = it; player.playbackParameters = PlaybackParameters(it) }) { panel = null }
            Panel.Aspect -> AspectPanel(aspect, onSelect = { setAspect(it) }) { panel = null }
            Panel.Episodes -> EpisodesPanel(session) { panel = null }
            null -> Unit
        }
    }
}

@Composable
private fun Controls(
    session: PlaybackSession,
    isPlaying: Boolean,
    buffering: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    scrub: Long?,
    speed: Float,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onBack: () -> Unit,
    onPanel: (Panel) -> Unit,
    onLock: () -> Unit,
) {
    val item = session.current
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.75f), 0.22f to Color.Transparent,
                0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f),
            ),
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item?.heading.orEmpty(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item?.subheading?.let { Text(it, color = GColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.width(48.dp))
        }

        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(72.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { session.seekBy(-10_000) }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Replay10, "10 sn geri", tint = Color.White, modifier = Modifier.size(52.dp))
            }
            if (!buffering) {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(84.dp)) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Duraklat" else "Oynat", tint = Color.White, modifier = Modifier.size(72.dp))
                }
            } else Spacer(Modifier.size(84.dp))
            IconButton(onClick = { session.seekBy(10_000) }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Forward10, "10 sn ileri", tint = Color.White, modifier = Modifier.size(52.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeekBar(position, buffered, duration, scrub, onScrub, onSeek, Modifier.weight(1f))
                Spacer(Modifier.width(14.dp))
                Text(
                    Library.formatClock((duration - (scrub ?: position)).coerceAtLeast(0)),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ControlButton(Icons.Filled.Speed, "Hız (${formatSpeed(speed)})") { onPanel(Panel.Speed) }
                ControlButton(Icons.Outlined.LockOpen, "Kilitle", onLock)
                ControlButton(Icons.Outlined.AspectRatio, "Görüntü") { onPanel(Panel.Aspect) }
                if (session.title.isSeries) ControlButton(Icons.Outlined.VideoLibrary, "Bölümler") { onPanel(Panel.Episodes) }
                ControlButton(Icons.Outlined.Subtitles, "Ses ve Altyazılar") { onPanel(Panel.Audio) }
                if (session.nextItem != null) ControlButton(Icons.Filled.SkipNext, "Sonraki Bölüm") { session.playNext() }
            }
        }
    }
}

private fun formatSpeed(s: Float) = if (s == 1f) "1x" else "${s}x".replace(".0x", "x")

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    scrub: Long?,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (duration > 0) ((scrub ?: position).toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var widthPx by remember { mutableFloatStateOf(1f) }
    fun at(x: Float) = ((x / widthPx).coerceIn(0f, 1f) * duration).toLong()

    Canvas(
        modifier.height(32.dp)
            .pointerInput(duration) {
                widthPx = size.width.toFloat()
                detectTapGestures { o -> if (duration > 0) onSeek(at(o.x)) }
            }
            .pointerInput(duration) {
                widthPx = size.width.toFloat()
                var last = 0L
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = at(o.x); onScrub(last) },
                    onDragEnd = { onSeek(last) },
                    onDragCancel = { onScrub(null) },
                ) { change, _ -> last = at(change.position.x); onScrub(last) }
            },
    ) {
        val y = size.height / 2
        val h = 3.dp.toPx()
        drawRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - h / 2), Size(size.width, h))
        drawRect(Color.White.copy(alpha = 0.45f), Offset(0f, y - h / 2), Size(size.width * bufferedFraction, h))
        drawRect(GColors.Red, Offset(0f, y - h / 2), Size(size.width * fraction, h))
        drawCircle(GColors.Red, radius = (if (scrub != null) 10.dp else 7.dp).toPx(), center = Offset(size.width * fraction, y))
    }
}

@Composable
private fun PanelScaffold(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        content()
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Icon(Icons.Filled.Close, "Kapat", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun AudioSubtitlePanel(session: PlaybackSession, tracks: Tracks, style: SubtitleStyle.Prefs, onStyle: (SubtitleStyle.Prefs) -> Unit, onClose: () -> Unit) {
    val audio = session.audioOptions(tracks)
    val subs = session.subtitleOptions(tracks)
    PanelScaffold(onClose) {
        Row(Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 36.dp, bottom = 24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Ses", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                if (audio.isEmpty()) Text("Yükleniyor…", color = GColors.TextSecondary)
                LazyColumn {
                    items(audio) { opt -> OptionRow(opt.label, opt.selected) { session.selectAudio(opt) } }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(GColors.Divider))
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text("Altyazılar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                LazyColumn {
                    items(subs) { opt -> OptionRow(opt.label, opt.selected) { session.selectSubtitle(opt) } }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(GColors.Divider))
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState())) {
                Text("Altyazı Görünümü", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                StyleChips("Boyut", SubtitleStyle.Size.entries, style.size, { it.label }) { onStyle(style.copy(size = it)) }
                StyleChips("Arka plan", SubtitleStyle.Background.entries, style.background, { it.label }) { onStyle(style.copy(background = it)) }
                Text("Kontroller açıkken altyazı ilerleme çubuğunun üstüne çıkar.", color = GColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun <T> StyleChips(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Text(label, color = GColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.padding(top = 6.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Text(
                name(o), color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color(0xFF333333))
                    .clickable { onPick(o) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label, color = if (selected) Color.White else GColors.TextSecondary, fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SpeedPanel(current: Float, onSelect: (Float) -> Unit, onClose: () -> Unit) {
    PanelScaffold(onClose) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Oynatma Hızı", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(40.dp))
            Box(Modifier.fillMaxWidth(0.7f)) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 13.dp, start = 30.dp, end = 30.dp).fillMaxWidth().height(2.dp).background(Color(0xFF5A5A5A)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SPEEDS.forEach { s ->
                        val sel = s == current
                        Column(
                            Modifier.width(72.dp).clickable { onSelect(s); onClose() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(if (sel) 26.dp else 14.dp).clip(CircleShape)
                                        .background(if (sel) Color.White else Color(0xFF8C8C8C))
                                        .border(if (sel) 5.dp else 0.dp, Color(0xFF8C8C8C).copy(alpha = if (sel) 0.6f else 0f), CircleShape),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (s == 1f) "1x (Normal)" else formatSpeed(s),
                                color = if (sel) Color.White else GColors.TextSecondary,
                                fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectPanel(current: Aspect, onSelect: (Aspect) -> Unit, onClose: () -> Unit) {
    PanelScaffold(onClose) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Görüntü Oranı", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Aspect.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { a ->
                        val sel = a == current
                        Text(a.label, color = if (sel) Color.Black else Color.White, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.width(190.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Color.White else Color(0xFF2A2A2A))
                                .clickable { onSelect(a); onClose() }.padding(vertical = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("İki parmakla açınca ekranı doldurur, kapatınca orijinale döner", color = GColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EpisodesPanel(session: PlaybackSession, onClose: () -> Unit) {
    val seasons = remember(session.title) { session.episodes.map { it.season }.distinctBy { it.number } }
    var seasonNo by remember { mutableIntStateOf(session.current?.season ?: seasons.firstOrNull()?.number ?: 1) }
    val list = session.episodes.filter { it.season.number == seasonNo }
    val currentIndex = list.indexOfFirst { it.episode.number == session.current?.episode }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    PanelScaffold(onClose) {
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(seasons) { s ->
                    val sel = s.number == seasonNo
                    Text(
                        Library.seasonName(s), color = if (sel) Color.White else GColors.TextSecondary,
                        fontSize = 18.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { seasonNo = s.number }.padding(vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyRow(state = state, contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(list, key = { "${it.season.number}-${it.episode.number}" }) { ref ->
                    val isCurrent = ref.season.number == session.current?.season && ref.episode.number == session.current?.episode
                    val p = session.progress[Library.progressKey(session.title.id, ref.season.number, ref.episode.number)]
                    Column(
                        Modifier.width(260.dp).clip(RoundedCornerShape(6.dp))
                            .border(if (isCurrent) 2.dp else 0.dp, if (isCurrent) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable {
                                if (!isCurrent) session.play(session.itemFor(ref), if (p != null && !p.isFinished) p.positionMs else 0L)
                                onClose()
                            },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(GColors.Card)) {
                            AsyncImage(model = ref.episode.still ?: session.title.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (isCurrent) {
                                Text(
                                    "Şu an oynatılıyor", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(GColors.Red, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (p != null) ProgressLine(p.fraction, Modifier.align(Alignment.BottomCenter))
                        }
                        Text(
                            "${ref.episode.number}. ${ref.episode.title ?: "Bölüm ${ref.episode.number}"}",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                        )
                        ref.episode.overview?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = GColors.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun describeError(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "İnternet bağlantısı kurulamadı."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Video sunucudan alınamadı. GitHub erişim anahtarını kontrol et."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Video dosyası bulunamadı."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Video dosyası bozuk görünüyor."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Bu cihaz videonun formatını desteklemiyor."
    else -> (e.cause?.message ?: e.errorCodeName)
}
