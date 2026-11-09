package com.gflix.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.gflix.app.appContainer
import com.gflix.app.ui.theme.GFlixTheme
import kotlinx.coroutines.runBlocking

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var session: PlaybackSession? = null
    private var prefetcher: ArchiveMonitor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val c = appContainer
        val titleId = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val catalog = c.catalogRepository.catalog.value ?: c.catalogRepository.loadCachedBlocking()
        val state = runBlocking { c.userStore.current() }
        val title = catalog?.titles?.firstOrNull { it.id == titleId }
            ?.let { com.gflix.app.data.Presentation.localize(it, state.activeProfile) }
        if (title == null) {
            Toast.makeText(this, "İçerik bulunamadı", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (c.tokens.token.isNullOrBlank()) c.tokens.token = state.token ?: c.builtInToken
        val profileId = state.activeProfileId.orEmpty()

        val loadControl = DefaultLoadControl.Builder()
            // Sadece bellekte (diske yazılmaz). Takılınca 8 sn dolsun ki hemen yine takılmasın
            .setBufferDurationsMs(30_000, 120_000, 1_000, 6_000)
            // Son 30 sn bellekte kalsın: geri sarınca yeniden indirmeden anında oynar
            .setBackBuffer(30_000, true)
            .build()
        // İndirildiyse cihazdaki şifreli kopyadan (internet/veri kullanmadan), değilse GitHub'dan
        val network = OkHttpDataSource.Factory(c.httpClient)
        val dataSources = com.gflix.app.offline.Offline.playbackFactory(this, PartsDataSource.Factory(ArchiveStreamCache.Factory(network)))
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo
        val pre = ArchiveMonitor(exo)
        ArchiveStreamCache.cleanupDisk(this)
        prefetcher = pre

        val s = PlaybackSession(exo, title, profileId, state.dataFor(profileId), c.userStore, c.appScope, dataSources,
            downloadedVersion = { key -> c.downloads.versionFor(key) },
            downloadedLive = { key -> c.downloads.liveFor(key) },
            prefetcher = pre)
        session = s
        val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it >= 0 }
        val ref = if (title.isSeries) (com.gflix.app.data.Library.findEpisode(title, season, episode) ?: s.episodes.firstOrNull()) else null
        if (if (title.isSeries) ref == null else title.stream == null) {
            Toast.makeText(this, "Bu içeriğin videosu henüz yüklenmemiş", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val startPos = intent.getLongExtra(EXTRA_POSITION, 0L)
        // İndirilmiş içerik internet kullanmaz: hiç sıraya girmez
        val key = com.gflix.app.data.Library.progressKey(title.id, ref?.season?.number, ref?.episode?.number)
        val offline = c.downloads.state(key)?.state == com.gflix.app.offline.DlState.Done
        var started = false
        fun begin() { if (!started) { started = true; s.start(season, episode, startPos) } }
        // GitHub istek sırası sadece GitHub'daki içerikler için: archive.org'dan oynatırken GitHub'a hiç gidilmez
        val mustQueue = !offline && !com.gflix.app.archive.ArchiveSource.configured && !com.gflix.app.data.RateLimit.canStart()
        if (!mustQueue) begin()

        setContent {
            GFlixTheme {
                val queued = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(mustQueue) }
                if (queued.value) QueueScreen(title.title, resume = false, onReady = { begin(); queued.value = false }, onCancel = { finish() })
                else PlayerScreen(s, onExit = { finish() })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        session?.saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        prefetcher?.release()
        session?.saveProgress()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_POSITION = "position"

        fun start(context: Context, titleId: String, season: Int?, episode: Int?, positionMs: Long) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_TITLE, titleId)
                    .putExtra(EXTRA_SEASON, season ?: -1)
                    .putExtra(EXTRA_EPISODE, episode ?: -1)
                    .putExtra(EXTRA_POSITION, positionMs),
            )
        }
    }
}
