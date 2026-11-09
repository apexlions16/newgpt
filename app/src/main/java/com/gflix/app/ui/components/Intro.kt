package com.gflix.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.ui.theme.Bebas
import kotlin.random.Random

private val RED = Color(0xFFE50914)
private val STREAK_COLORS = listOf(Color(0xFFE50914), Color(0xFFB20710), Color(0xFF7A0C12), Color(0xFFFF4D57), Color(0xFFD81F26))

/** 0..1 aralığında geri esneyerek oturan hareket */
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f; val c3 = c1 + 1f; val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

/**
 * 🎬 G-Flix giriş animasyonu (~2,4 sn): ortada bir ışık çizgisi açılır, ondan kırmızı ışık şeritleri yükselir,
 * harfler sırayla aşağıdan esneyerek yerine oturur, logonun üstünden bir parıltı geçer, sonra logo büyüyerek kararır.
 * Dokununca atlanır.
 */
@Composable
fun GFlixIntro(modifier: Modifier = Modifier, onFinished: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(2400, easing = LinearEasing))
        onFinished()
    }
    val p = t.value
    fun seg(a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    // her şerit: yatay konum, boy, gecikme, kalınlık (sabit tohum: her açılışta aynı görüntü)
    val streaks = remember {
        val r = Random(2026)
        List(34) { floatArrayOf(r.nextFloat(), 0.25f + r.nextFloat() * 0.75f, r.nextFloat(), 2f + r.nextFloat() * 9f) }
    }
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val out = seg(0.84f, 1f)
            // arka kırmızı ışıma
            val glow = seg(0.15f, 0.6f) * (1f - out)
            if (glow > 0f) {
                val rad = size.minDimension * (0.35f + 0.6f * glow)
                drawCircle(Brush.radialGradient(listOf(RED.copy(alpha = 0.42f * glow), RED.copy(alpha = 0.12f * glow), Color.Transparent),
                    center = Offset(cx, cy), radius = rad), radius = rad, center = Offset(cx, cy))
            }
            // ortadaki ışık çizgisi
            val beam = easeOut(seg(0f, 0.18f))
            val beamFade = 1f - seg(0.42f, 0.62f)
            if (beam > 0f && beamFade > 0f) {
                val w = size.width * beam
                val h = 3.dp.toPx() * (1f + 0.6f * (1f - beamFade))
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, RED, Color.White, RED, Color.Transparent), startX = cx - w / 2, endX = cx + w / 2),
                    topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h), alpha = beamFade)
            }
            // yükselen ışık şeritleri
            val fade = 1f - seg(0.52f, 0.8f)
            if (fade > 0f) streaks.forEachIndexed { i, s ->
                val start = 0.1f + s[2] * 0.22f
                val rise = easeOut(seg(start, start + 0.3f))
                if (rise <= 0f) return@forEachIndexed
                val x = cx + (s[0] - 0.5f) * size.width * 0.95f
                val half = size.height * 0.42f * s[1] * rise
                val bw = s[3].dp.toPx()
                val c = STREAK_COLORS[i % STREAK_COLORS.size]
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = 0.9f), Color.White.copy(alpha = 0.55f), c.copy(alpha = 0.9f), Color.Transparent),
                    startY = cy - half, endY = cy + half), topLeft = Offset(x - bw / 2, cy - half), size = Size(bw, half * 2), alpha = fade * (0.55f + 0.45f * s[1]))
            }
        }
        // logo: harfler sırayla oturur, parıltı geçer, sonunda büyüyerek kararır
        val out = seg(0.84f, 1f)
        val shine = seg(0.6f, 0.8f)
        Row(
            Modifier.graphicsLayer {
                scaleX = 1f + 0.28f * out; scaleY = 1f + 0.28f * out; alpha = 1f - out
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                if (shine > 0f && shine < 1f) {
                    val band = size.width * 0.35f
                    val x = -band + shine * (size.width + band * 2)
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
                        start = Offset(x - band / 2, 0f), end = Offset(x + band / 2, size.height)), blendMode = BlendMode.SrcAtop)
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            "G-FLIX".forEachIndexed { i, ch ->
                val s = seg(0.28f + i * 0.055f, 0.48f + i * 0.055f)
                val e = easeOutBack(s)
                Text(ch.toString(), color = RED, fontFamily = Bebas, fontSize = 86.sp, letterSpacing = 3.sp,
                    style = TextStyle(shadow = Shadow(RED.copy(alpha = 0.55f * s), Offset.Zero, 28f)),
                    modifier = Modifier.graphicsLayer {
                        alpha = s
                        translationY = (1f - e) * 70.dp.toPx()
                        scaleX = 1f + (1f - s) * 0.5f
                        scaleY = 0.6f + 0.4f * e
                    })
            }
        }
    }
}
