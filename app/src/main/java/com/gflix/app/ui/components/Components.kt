package com.gflix.app.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.gflix.app.data.Title
import com.gflix.app.ui.theme.Bebas
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- Marka ----------

@Composable
fun GLogo(modifier: Modifier = Modifier, size: TextUnit = 40.sp) {
    Text(
        text = "G-FLIX",
        modifier = modifier,
        color = GColors.Red,
        fontFamily = Bebas,
        fontSize = size,
        letterSpacing = (size.value * 0.04f).sp,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 6f), 12f)),
    )
}

@Composable
fun GMark(modifier: Modifier = Modifier, size: TextUnit = 34.sp) {
    Text(
        text = "G",
        modifier = modifier,
        color = GColors.Red,
        fontFamily = Bebas,
        fontSize = size,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 6f)),
    )
}

// ---------- Profil avatarı ----------

val AvatarColors = listOf(
    Color(0xFF0071EB), Color(0xFFE50914), Color(0xFFF5C518), Color(0xFF2BB673),
    Color(0xFF8E44AD), Color(0xFF16A2B8), Color(0xFFFF7A00), Color(0xFFE91E63),
    Color(0xFF5C6BC0), Color(0xFF7CB342), Color(0xFF6D4C41), Color(0xFF455A64),
)
const val AVATAR_COUNT = 12

@Composable
fun ProfileAvatar(avatar: Int, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val color = AvatarColors[((avatar % AVATAR_COUNT) + AVATAR_COUNT) % AVATAR_COUNT]
    val face = avatar % 4
    Canvas(modifier.clip(RoundedCornerShape(corner)).background(color)) {
        val w = size.width
        val h = size.height
        val white = Color.White
        val eyeW = w * 0.085f
        val eyeH = h * 0.2f
        val eyeY = h * 0.27f
        val leftX = w * 0.3f
        val rightX = w * 0.62f
        drawRoundRect(white, Offset(leftX, eyeY), Size(eyeW, eyeH), CornerRadius(eyeW / 2))
        if (face == 2) {
            drawLine(white, Offset(rightX - eyeW * 0.4f, eyeY + eyeH * 0.6f), Offset(rightX + eyeW * 1.4f, eyeY + eyeH * 0.6f), w * 0.06f, StrokeCap.Round)
        } else {
            drawRoundRect(white, Offset(rightX, eyeY), Size(eyeW, eyeH), CornerRadius(eyeW / 2))
        }
        val mouthTop = Offset(w * 0.22f, h * 0.28f)
        val mouthSize = Size(w * 0.56f, h * 0.46f)
        when (face) {
            1 -> drawArc(white, 10f, 160f, true, mouthTop, mouthSize)
            3 -> drawLine(white, Offset(w * 0.33f, h * 0.68f), Offset(w * 0.67f, h * 0.68f), w * 0.065f, StrokeCap.Round)
            else -> drawArc(white, 20f, 140f, false, mouthTop, mouthSize, style = Stroke(w * 0.065f, cap = StrokeCap.Round))
        }
    }
}

// ---------- Butonlar ----------

@Composable
fun PlayButton(modifier: Modifier = Modifier, text: String = "Oynat", onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(28.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun GrayButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = GColors.ButtonGray, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Ikon üstte, yazı altta (Listem / Değerlendir / Paylaş). */
@Composable
fun IconAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = GColors.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
fun PlayCircle(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

// ---------- İlerleme ----------

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Color(0xFF5A5A5A)) {
    Box(modifier.fillMaxWidth().height(height).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(GColors.Red))
    }
}

@Composable
fun GLoading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = GColors.Red, strokeWidth = 3.dp)
    }
}

@Composable
fun MaturityBadge(text: String) {
    Box(Modifier.background(Color(0xFF414141), RoundedCornerShape(2.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun OutlineBadge(text: String) {
    Box(Modifier.border(1.dp, GColors.TextSecondary, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp)) {
        Text(text, color = GColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Posterler ----------

private const val NEW_WINDOW_SEC = 14 * 24 * 3600L

fun isRecentlyAdded(t: Title): Boolean = t.lastChange > 0 && System.currentTimeMillis() / 1000 - t.lastChange < NEW_WINDOW_SEC

@Composable
fun PosterCard(title: Title, modifier: Modifier = Modifier, showBadge: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(GColors.Card).clickable(onClick = onClick),
    ) {
        if (title.poster != null) {
            AsyncImage(model = title.poster, contentDescription = title.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                title.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
        // Yazısız kapak: Netflix gibi logo altta üstüne konur
        if (title.poster != null && !title.posterHasText && title.logo != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
            TitleLogo(title, Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = if (showBadge && isRecentlyAdded(title)) 20.dp else 10.dp)
                .fillMaxWidth().height(44.dp), textSize = 18.sp)
        }
        GMark(Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 1.dp), size = 18.sp)
        if (showBadge && isRecentlyAdded(title)) {
            NewBadge(title.isSeries, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
fun NewBadge(series: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(bottom = 0.dp).background(GColors.Red, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            if (series) "Yeni Bölüm" else "Yeni Eklendi", color = Color.White, fontSize = 9.sp,
            fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

/** Arka plan görseli + başlık logosu ya da yazısı */
@Composable
fun TitleLogo(title: Title, modifier: Modifier = Modifier, textSize: TextUnit = 36.sp, align: TextAlign = TextAlign.Center) {
    // Logo resmi inmezse (ağ/TMDB) boş kalmasın: adı yaz
    var failed by androidx.compose.runtime.remember(title.logo) { androidx.compose.runtime.mutableStateOf(false) }
    if (title.logo != null && !failed) {
        AsyncImage(model = title.logo, contentDescription = title.title, contentScale = ContentScale.Fit, modifier = modifier, onError = { failed = true })
    } else {
        Text(
            title.title.uppercase(com.gflix.app.data.TR), modifier = modifier, color = Color.White, fontFamily = Bebas,
            fontSize = textSize, textAlign = align, lineHeight = textSize * 0.95f, maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 3f), 10f)),
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun BottomGradient(modifier: Modifier = Modifier, color: Color = Color.Black) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.6f), color))))
}

@Composable
fun BoxScope.EmptyMessage(title: String, body: String) {
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = GColors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** Posterden baskın koyu rengi çıkarır (ana sayfadaki renkli arka plan için). */
@Composable
fun rememberDominantColor(url: String?, default: Color = Color(0xFF262626)): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(default) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(160).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@LaunchedEffect
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        val swatch = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
        swatch?.let {
            val c = Color(it.rgb)
            // Çok parlak renkleri koyulaştır
            color = Color(c.red * 0.6f, c.green * 0.6f, c.blue * 0.6f, 1f)
        }
    }
    return animateColorAsState(color, tween(600), label = "dominant").value
}

/** Kalite (HD/4K…) ve Dolby Vision/Atmos rozetleri — yöneticinin işaretine göre; işaret yoksa "HD". */
@Composable
fun QualityBadges(title: Title) {
    val b = title.badges
    OutlineBadge(b?.quality?.takeIf { it.isNotBlank() } ?: "HD")
    if (b?.dolbyVision == true) DolbyBadge("Dolby Vision")
    if (b?.dolbyAtmos == true) DolbyBadge("Dolby Atmos")
}

@Composable
private fun DolbyBadge(text: String) {
    Text(
        text, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFE5E5E5)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** "🔊 Türkçe Dublaj · 💬 Türkçe Altyazı" */
@Composable
fun LanguageLine(title: Title, modifier: Modifier = Modifier) {
    val l = androidx.compose.runtime.remember(title) { com.gflix.app.data.Presentation.languageBadges(title) }
    if (l.isEmpty()) return
    Text(
        l.joinToString("   ") { if (it.contains("Dublaj")) "🔊 $it" else "💬 $it" },
        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier,
    )
}

/** Stüdyo notları: "📢 Yeni sezon 7 Kasım'da" gibi kısa şeritler; süresi dolan kendiliğinden kaybolur. */
@Composable
fun TitleNotes(title: Title, modifier: Modifier = Modifier) {
    val notes = com.gflix.app.data.Library.activeNotes(title)
    if (notes.isEmpty()) return
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.forEach { n ->
            Text(
                n.text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(Color(0x33E50914))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
