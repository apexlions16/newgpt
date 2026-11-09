package com.gflix.app.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.data.RateLimit
import com.gflix.app.ui.theme.Bebas
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay

/**
 * "Sıradasın": yoğunlukta yeni izleme bekletilir (kullanıcıya sınır/istek sayısı gösterilmez).
 * Sıra gelince [onReady] çağrılır. [resume]: izlerken bekletildiyse ("Kaldığın yerden devam edecek").
 */
@Composable
fun QueueScreen(title: String, resume: Boolean, onReady: () -> Unit, onCancel: () -> Unit) {
    var wait by remember { mutableLongStateOf(RateLimit.waitMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            wait = RateLimit.waitMs()
            if (RateLimit.canStart()) { onReady(); return@LaunchedEffect }
            delay(2000)
        }
    }
    val t = rememberInfiniteTransition(label = "q")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (resume) 0.82f else 1f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("G-FLIX", fontFamily = Bebas, fontSize = 42.sp, color = GColors.Red)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { i ->
                    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse), label = "d$i")
                    Box(Modifier.size(12.dp).alpha(a).clip(CircleShape).background(Color.White))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Sıradasın", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                (if (resume) "Şu an çok yoğun. \"$title\" kaldığın yerden kendiliğinden devam edecek."
                else "Şu an çok yoğun. \"$title\" sıran gelince kendiliğinden başlayacak.") +
                    "\nUygulamadan çıkma, burada beklemen yeterli.",
                color = GColors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
            )
            val min = ((wait + 59_999) / 60_000).toInt()
            if (wait > 0) {
                Spacer(Modifier.height(10.dp))
                Text(if (min <= 1) "Tahmini: 1 dakikadan az" else "Tahmini: ~$min dakika", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onCancel, shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
            ) { Text("Vazgeç") }
        }
    }
}
