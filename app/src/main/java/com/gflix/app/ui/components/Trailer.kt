package com.gflix.app.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.gflix.app.R
import com.gflix.app.data.Profile
import com.gflix.app.data.Stream
import com.gflix.app.player.TrailerMedia

/** Fragmanların sesi uygulama boyunca ortak: bir yerde açınca diğerlerinde de açık kalır. */
object TrailerAudio {
    var muted by mutableStateOf(true)
}

fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

/** Profil ayarlarına göre fragman kendiliğinden oynasın mı? */
fun canAutoplayTrailer(context: Context, profile: Profile?): Boolean =
    profile?.autoplayTrailers != false && (profile?.trailersWifiOnly != true || isOnWifi(context))

/**
 * Netflix gibi sessiz başlayan fragman. İlk kare gelene kadar ve fragman bitince kapak gösterilir;
 * bitince ortada "Tekrar Oynat" çıkar. [onFullscreen] verilirse tam ekran düğmesi (o anki konumla) görünür,
 * [langLabel]/[onSwitchLang] verilirse dil düğmesi (TR/EN) görünür. Wi-Fi'de ve telefon destekliyorsa 4K oynar.
 */
@OptIn(UnstableApi::class)
@Composable
fun TrailerView(
    trailer: Stream,
    image: String?,
    modifier: Modifier = Modifier,
    controlsPadding: Dp = 10.dp,
    showReplay: Boolean = true,
    onFullscreen: ((Long) -> Unit)? = null,
    langLabel: String? = null,
    onSwitchLang: (() -> Unit)? = null,
) {
    // TMDB'den gelen YouTube fragmanı: bilgisayar yoksa YouTube'un gömülü oynatıcısıyla
    val yt = trailer.youtube
    if (yt != null && !com.gflix.app.data.PcServer.configured) {
        YouTubeTrailerView(yt, image, modifier, controlsPadding, showReplay, onFullscreen, langLabel, onSwitchLang)
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val key = TrailerMedia.key(trailer)
    var firstFrame by remember(key) { mutableStateOf(false) }
    var ended by remember(key) { mutableStateOf(false) }
    val player = remember(key) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(TrailerMedia.mediaSource(context, trailer, allowUhd = isOnWifi(context)))
            volume = if (TrailerAudio.muted) 0f else 1f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended = true }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (!ended) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(TrailerAudio.muted) { player.volume = if (TrailerAudio.muted) 0f else 1f }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.trailer_player_view, null) as PlayerView).also { it.player = player }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrame || ended) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (ended && showReplay) {
            Row(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .clickable { ended = false; firstFrame = false; player.seekTo(0); player.play() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Replay, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tekrar Oynat", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(controlsPadding), verticalAlignment = Alignment.CenterVertically) {
            if (langLabel != null && onSwitchLang != null) {
                Text(
                    langLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .clickable(onClick = onSwitchLang).padding(horizontal = 10.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (onFullscreen != null) {
                RoundButton(
                    { if (ended) onFullscreen(0L) else { player.pause(); onFullscreen(player.currentPosition) } },
                ) { Icon(Icons.Filled.Fullscreen, "Tam ekran", tint = Color.White, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            if (!ended) {
                RoundButton({ TrailerAudio.muted = !TrailerAudio.muted }) {
                    Icon(
                        if (TrailerAudio.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (TrailerAudio.muted) "Sesi aç" else "Sesi kapat", tint = Color.White, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** [TrailerView]'ın YouTube fragmanı hali: sessiz başlar, ses / tam ekran / dil düğmeleri aynı. */
@Composable
private fun YouTubeTrailerView(
    videoId: String, image: String?, modifier: Modifier, controlsPadding: Dp, showReplay: Boolean,
    onFullscreen: ((Long) -> Unit)?, langLabel: String?, onSwitchLang: (() -> Unit)?,
) {
    var ended by remember(videoId) { mutableStateOf(false) }
    var round by remember(videoId) { mutableStateOf(0) }
    Box(modifier) {
        if (!ended) androidx.compose.runtime.key(videoId, round) {
            YouTubeEmbed(videoId, muted = TrailerAudio.muted, controls = false, modifier = Modifier.fillMaxSize(), onEnded = { ended = true })
        } else {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (showReplay) Row(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .clickable { ended = false; round++ }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Replay, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tekrar Oynat", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(controlsPadding), verticalAlignment = Alignment.CenterVertically) {
            if (langLabel != null && onSwitchLang != null) {
                Text(
                    langLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .clickable(onClick = onSwitchLang).padding(horizontal = 10.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (onFullscreen != null) {
                RoundButton({ onFullscreen(0L) }) { Icon(Icons.Filled.Fullscreen, "Tam ekran", tint = Color.White, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            if (!ended) {
                RoundButton({ TrailerAudio.muted = !TrailerAudio.muted }) {
                    Icon(
                        if (TrailerAudio.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (TrailerAudio.muted) "Sesi aç" else "Sesi kapat", tint = Color.White, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
