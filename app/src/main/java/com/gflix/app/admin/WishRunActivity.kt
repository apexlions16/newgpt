package com.gflix.app.admin

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * 🔒 Kilit ekranında İstek listesi: telefon kilitliyken/ekran kapalıyken bir iş başlayınca bu sayfa kilidin ÜZERİNDE açılır
 * (kilit açılmaz), ekranı en düşük parlaklıkta açar, tarayıcıyı gösterir (görünmeyen sayfada oynatıcı başlamıyor).
 * Liste boşa düşünce kendiliğinden kapanır, ekran yine kararır. Çalar saat uygulamalarının kullandığı yol.
 */
class WishRunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        // Dokunulamaz: dokunuşlar alttaki kilit ekranına gider (sayfaya kimse dokunup bir şey açamaz)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // Kullanıcı kilidi açınca bu sayfa hemen kapanır (kilit açıkken üstte kalıp dokunuşları geçirmesin)
        val unlocked = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { finish() }
        }
        androidx.core.content.ContextCompat.registerReceiver(this, unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        unlockReceiver = unlocked
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }     // ekran açık ama en karanlık
        val e = CaptureEngine
        e.hideOverlay()
        setContent {
            DisposableEffect(Unit) {
                e.runActivityOn = true
                onDispose { e.runActivityOn = false; e.bumpHost() }
            }
            // iş bitince ve 20 sn yeni iş başlamazsa kapan
            LaunchedEffect(Unit) {
                var idle = 0
                while (true) {
                    delay(1000)
                    idle = if (e.wishActive) 0 else idle + 1
                    if (idle >= 20) { finish(); break }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Column(Modifier.fillMaxWidth().align(Alignment.Center)) {
                    androidx.compose.runtime.key(e.hostGen) {
                        AndroidView(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), factory = { ctx -> e.attach(ctx) })
                    }
                    Text("🔒 İstek listesi çalışıyor (kilit açılmadı). " + WishList.now, color = Color(0xFF888888), fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    private var unlockReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) {
            runCatching {
                c.startActivity(Intent(c, WishRunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
    }
}
