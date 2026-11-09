package com.gflix.app.admin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gflix.app.MainActivity
import com.gflix.app.R

/**
 * Arka planda Sayfadan Yakala: sıra sürerken uygulama kapatılmasın diye ön plan hizmeti + bildirim
 * ("Sayfadan Yakala 3/12 · Gece Bekçisi S01E03"). Kullanıcının dokunması gerekirse (video başlamadı, site kontrolü)
 * ayrı bir uyarı bildirimi çıkar; dokununca Sayfadan Yakala ekranı açılır.
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onDestroy() { instance = null; session?.release(); session = null; super.onDestroy() }

    // ---- "Şu an çalıyor" biçimi: müzik/YouTube gibi durum çubuğunda çip ve kilit ekranında kart (⏸/▶ ile liste)
    private var session: android.media.session.MediaSession? = null

    private fun mediaSession(): android.media.session.MediaSession = session ?: android.media.session.MediaSession(this, "gflix-yakala").apply {
        setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { WishList.changeStarted(true); refresh() }
            override fun onPause() { WishList.changeStarted(!WishList.started); refresh() }
            override fun onStop() { WishList.changeStarted(false); refresh() }
        })
        isActive = true
        session = this
    }

    private var lastText = ""
    private fun refresh() { runCatching { getSystemService(NotificationManager::class.java).notify(ID, mediaNotification(lastText)) } }

    private fun mediaNotification(text: String): android.app.Notification {
        val s = mediaSession()
        val on = WishList.started
        val title = text.lineSequence().firstOrNull().orEmpty().ifBlank { "G-Flix yakalama" }
        s.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "G-Flix · İstek listesi")
            .build())
        s.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_STOP)
            // Hep "çalıyor": çip (durum çubuğu) ve kilit ekranı kartı kullanıcı kapatana kadar kalsın; ⏸/▶ listeyi yönetir
            .setState(android.media.session.PlaybackState.STATE_PLAYING, 0L, 1f)
            .build())
        val toggle = PendingIntent.getService(this, 7310, Intent(this, CaptureService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 7311, Intent(this, CaptureService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        s.isActive = true                     // başka uygulama sustuktan sonra çip yine bize dönsün
        val icon = android.graphics.drawable.Icon.createWithResource(this,
            if (on) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        return android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(if (on) "G-Flix · İstek listesi çalışıyor" else "G-Flix · İstek listesi duraklatıldı")
            .setStyle(android.app.Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0))
            .addAction(android.app.Notification.Action.Builder(icon, if (on) "Duraklat" else "Başlat", toggle).build())
            .addAction(android.app.Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Kapat", close).build())
            .setDeleteIntent(PendingIntent.getService(this, 7312, Intent(this, CaptureService::class.java).setAction(ACTION_REPOST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent(this))
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat(); stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE) WishList.changeStarted(!WishList.started)
        if (intent?.action == ACTION_CLOSE) {      // kullanıcı kapattı: liste durur, çip kalkar
            WishList.changeStarted(false); WishList.chipClosed = true
            stopForegroundCompat(); stopSelf(); return START_NOT_STICKY
        }
        lastText = intent?.getStringExtra(EXTRA_TEXT) ?: lastText.ifBlank { "Sayfadan Yakala çalışıyor" }
        val n = mediaNotification(lastText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
                .onFailure { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        private const val CHANNEL = "gflix_capture"
        private const val ID = 7301
        private const val ID_ALERT = 7302
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "stop"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_CLOSE = "close"
        private const val ACTION_REPOST = "repost"      // bildirim kaydırılıp silinirse hemen geri gelir
        const val EXTRA_OPEN_ROUTE = "gflix.open_route"
        @Volatile private var instance: CaptureService? = null

        private fun channel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sayfadan Yakala", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel("$CHANNEL.alert") == null) nm.createNotificationChannel(NotificationChannel("$CHANNEL.alert", "Sayfadan Yakala — dokunman gerekiyor", NotificationManager.IMPORTANCE_HIGH))
        }

        private fun openIntent(c: Context): PendingIntent = PendingIntent.getActivity(
            c, 7300, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_ROUTE, "admin/capture"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun build(c: Context, text: String, alert: Boolean) = NotificationCompat.Builder(c, if (alert) "$CHANNEL.alert" else CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (alert) "✋ Sayfadan Yakala: dokunman gerekiyor" else "🌐 Sayfadan Yakala")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(!alert).setOnlyAlertOnce(!alert).setAutoCancel(alert)
            .setContentIntent(openIntent(c)).build()

        /** Sıra başlayınca / ilerledikçe (aynı bildirim güncellenir). */
        fun update(c: Context, text: String) {
            channel(c)
            runCatching {
                c.startForegroundService(Intent(c, CaptureService::class.java).putExtra(EXTRA_TEXT, text))
            }.onFailure {     // arka plandan başlatılamadıysa sadece bildirimi güncelle
                runCatching { instance?.let { svc -> svc.lastText = text; svc.refresh() } ?: c.getSystemService(NotificationManager::class.java).notify(ID, build(c, text, false)) }
            }
        }

        fun alert(c: Context, text: String) {
            channel(c)
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID_ALERT, build(c, text, true)) }
        }

        fun clearAlert(c: Context) {
            runCatching { c.getSystemService(NotificationManager::class.java).cancel(ID_ALERT) }
        }

        fun stop(c: Context) {
            clearAlert(c)
            // Çip, kullanıcı 'Kapat' diyene kadar kalır: elle sıra bitince de İstek listesinin durumunu göstermeye döner
            if (!WishList.chipClosed && WishList.chipWanted()) { update(c, WishList.summary()); return }
            instance?.let { svc -> runCatching { svc.stopForegroundCompat(); svc.stopSelf() } }
            runCatching { c.getSystemService(NotificationManager::class.java).cancel(ID) }
        }
    }
}
