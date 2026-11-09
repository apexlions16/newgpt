package com.gflix.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.gflix.app.data.EpisodeRef
import com.gflix.app.data.Library
import com.gflix.app.data.ProfileData
import com.gflix.app.data.Stream
import com.gflix.app.data.Title
import com.gflix.app.data.UserStore
import com.gflix.app.data.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayItem(
    val season: Int?,
    val episode: Int?,
    val stream: Stream,
    val heading: String,
    val subheading: String?,
)

/** Bir dizi/filmin oynatılmasını yönetir: bölümler, ilerleme kaydı, ses/altyazı tercihleri. */
@OptIn(UnstableApi::class)
class PlaybackSession(
    val player: ExoPlayer,
    val title: Title,
    private val profileId: String,
    initialData: ProfileData,
    private val store: UserStore,
    private val scope: CoroutineScope,
    private val dataSourceFactory: DataSource.Factory,
    /** İndirilmiş dil sürümü (dublajlı ayrı videolarda internetsiz de oynasın diye önce o seçilir) */
    private val downloadedVersion: (String) -> String? = { null },
    /** İndirilmiş anlık yayın (adres + altyazılar): varsa bilgisayara sorulmaz, telefondan oynar */
    private val downloadedLive: (String) -> com.gflix.app.offline.OfflineMeta? = { null },
    /** archive.org videolarında hızı ölçer (takılınca ekranda gösterilir) */
    private val prefetcher: ArchiveMonitor? = null,
) {
    /** archive.org'dan gelen hız / gereken hız (oynatıcı takılınca gösterilir) */
    val prefetchInfo get() = prefetcher?.info

    val episodes: List<EpisodeRef> = Library.episodes(title)

    var current by mutableStateOf<PlayItem?>(null)
        private set

    var progress by mutableStateOf(initialData.progress)
        private set

    private var audioLang: String? = initialData.audioLang

    /** Dil başına ayrı video varsa şu an oynayanın dili (ses menüsü bunu gösterir). */
    var versionLang by mutableStateOf<String?>(null)
        private set

    private fun pickVersion(stream: Stream): String? {
        val vs = stream.versions
        if (vs.isEmpty()) return null
        return (vs.firstOrNull { Library.sameLanguage(it.lang, audioLang) } ?: vs.firstOrNull { it.lang == "tr" } ?: vs.first()).lang
    }
    private var subtitleLang: String? = initialData.subtitleLang

    /** Linkten anlık yayın: bilgisayar linki hazırlarken gösterilecek yazı; liveFailed ise hata (Tekrar Dene) */
    var liveStatus by mutableStateOf<String?>(null)
        private set
    var liveFailed by mutableStateOf(false)
        private set
    private var liveJob: Job? = null

    fun start(season: Int?, episode: Int?, positionMs: Long): Boolean {
        val item = if (title.isSeries) {
            val ref = Library.findEpisode(title, season, episode) ?: episodes.firstOrNull() ?: return false
            itemFor(ref)
        } else {
            val stream = title.stream ?: return false
            PlayItem(null, null, stream, title.title, null)
        }
        play(item, positionMs)
        return true
    }

    fun itemFor(ref: EpisodeRef): PlayItem {
        val name = ref.episode.title?.takeIf { it.isNotBlank() } ?: "Bölüm ${ref.episode.number}"
        return PlayItem(ref.season.number, ref.episode.number, ref.episode.stream!!, title.title, "${ref.label} \"$name\"")
    }

    fun play(item: PlayItem, positionMs: Long) {
        // archive.org: bu videonun (ve dil sürümlerinin) açılış parçaları + sıradaki bölüm belleğe (açılışta beklenmesin)
        ArchiveWarm.warmStream(item.stream, onlyStart = positionMs > 0)
        saveProgress()
        current = item
        versionLang = downloadedVersion(Library.progressKey(title.id, item.season, item.episode))
            ?.takeIf { l -> item.stream.versions.any { it.lang == l } } ?: pickVersion(item.stream)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(audioLang ?: "tr")
            .setPreferredTextLanguage(subtitleLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLang == null)
            .build()
        if (item.stream.live) {
            val off = downloadedLive(Library.progressKey(title.id, item.season, item.episode))
            if (off?.hls != null) {
                val ready = item.copy(stream = item.stream.copy(master = off.hls, subtitles = off.subs))
                current = ready
                liveStatus = null
                // doğrudan indirilende sadece seçilen kalite/sesler önbellekte: oynatırken de onlar istenir
                val keys = off.streamKeys.mapNotNull { k -> k.split('.').takeIf { it.size == 3 }?.let {
                    androidx.media3.common.StreamKey(it[0].toInt(), it[1].toInt(), it[2].toInt()) } }
                val src = if (off.mp4) DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(
                        buildMediaItem(ready).buildUpon().setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4).build())
                    else if (keys.isEmpty()) buildSource(ready)
                    else DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(buildMediaItem(ready).buildUpon().setStreamKeys(keys).build())
                player.setMediaSource(src, positionMs.coerceAtLeast(0))
                player.prepare()
                player.playWhenReady = true
                return
            }
            return playLive(item, positionMs, fresh = false)
        }
        player.setMediaSource(buildSource(item), positionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    /** Bilgisayardan o anki taze linki iste (birkaç saniye), gelen HLS'i oynat. Altyazılar bilgisayardan. */
    private fun playLive(item: PlayItem, positionMs: Long, fresh: Boolean, waitRound: Int = 0) {
        liveJob?.cancel()
        player.stop()
        liveFailed = false
        if (waitRound == 0) liveStatus = "Bilgisayar yayını hazırlıyor…\nSiteden taze link alınıyor"
        liveJob = scope.launch {
            val result = runCatching { com.gflix.app.data.PcServer.play(title.id, item.season, item.episode, fresh) }
            withContext(Dispatchers.Main) {
                if (current !== item) return@withContext
                result.onSuccess { info ->
                    val ready = item.copy(stream = item.stream.copy(
                        master = info.hls,
                        subtitles = info.subs.map { com.gflix.app.data.Subtitle(it.lang, it.label, it.url, it.forced) },
                    ))
                    current = ready
                    liveStatus = null
                    player.setMediaSource(buildSource(ready), positionMs.coerceAtLeast(0))
                    player.prepare()
                    player.playWhenReady = true
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) return@onFailure
                    // Link eskimiş: telefon (Stüdyo) yeniden yakalıyor → kullanıcı bir şey yapmadan bekle, 4 sn'de bir dene (en çok ~4 dk)
                    if (e.message.orEmpty().contains("süresi dolmuş") && waitRound < 60) {
                        liveStatus = "🔄 Yeni link alınıyor…\nBu yapımın linki eskimiş; birazdan kendiliğinden başlayacak (${(waitRound + 1) * 4} sn)"
                        liveJob = scope.launch {
                            kotlinx.coroutines.delay(4_000)
                            withContext(Dispatchers.Main) { if (current === item) playLive(item, positionMs, false, waitRound + 1) }
                        }
                        return@onFailure
                    }
                    liveFailed = true
                    liveStatus = if ((e as? com.gflix.app.data.PcException)?.needsSource == true)
                        "Bu yapımın sitedeki sayfası bilgisayarda kayıtlı değil.\nStüdyo → 🌐 Sayfadan Yakala ile sayfayı bir kez aç."
                    else e.message ?: "Bilgisayara ulaşılamadı."
                }
            }
        }
    }

    /** Canlı yayında hata: yeni linkle baştan iste (kaldığı saniyeden). Değilse oynatıcıyı yeniden hazırla. */
    fun retry() {
        val c = current ?: return
        if (c.stream.live) {
            val pos = player.currentPosition
            current = c
            playLive(c, pos, fresh = true)
        } else { player.prepare(); player.play() }
    }

    /** archive.org'daki altyazılar yüklendiği biçimde durur (.srt / .vtt / .ass) */
    private fun subtitleMime(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }

    private fun buildMediaItem(item: PlayItem): MediaItem {
        val subs = item.stream.subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(subtitleMime(s.url))
                .setLanguage(s.lang)
                .setLabel(s.label ?: Library.languageName(s.lang))
                .setSelectionFlags(if (s.forced) C.SELECTION_FLAG_FORCED else 0)
                .setId("sub-${s.lang}-${if (s.forced) "f" else "n"}")
                .build()
        }
        return MediaItem.Builder()
            .setUri(item.stream.master)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaId(Library.progressKey(title.id, item.season, item.episode))
            .setSubtitleConfigurations(subs)
            .build()
    }

    /**
     * HLS içerikler tek kaynaktır. "Direkt" (hızlı yüklenmiş) içeriklerde video, her ses dosyası ve altyazılar
     * ayrı kaynaklardır; MergingMediaSource hepsini aynı zaman çizgisinde birlikte çalar. Seçilmeyen ses
     * dosyası indirilmez.
     */
    private fun buildSource(item: PlayItem): MediaSource {
        val mediaItem = buildMediaItem(item)
        item.stream.versions.firstOrNull { it.lang == versionLang }?.let { v ->
            prefetcher?.follow(v.video.parts.singleOrNull(), v.video.sizes.singleOrNull() ?: 0, item.stream.duration)
            // Dublajlı sürüm: tek dosya (görüntü + o dilin sesi), altyazılar ortak
            val vItem = mediaItem.buildUpon().setUri(PartsDataSource.uriFor(v.video)).setMimeType(null).build()
            return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(vItem)
        }
        val direct = item.stream.direct
        prefetcher?.follow(direct?.video?.parts?.singleOrNull(), direct?.video?.sizes?.singleOrNull() ?: 0, item.stream.duration)
        if (direct == null) return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        val videoItem = mediaItem.buildUpon().setUri(PartsDataSource.uriFor(direct.video)).setMimeType(null).build()
        val videoExtractors = if (direct.videoAudio.isNotEmpty()) LabeledExtractorsFactory.forVideo(direct.videoAudio) else DefaultExtractorsFactory()
        val video = DefaultMediaSourceFactory(dataSourceFactory, videoExtractors).createMediaSource(videoItem)
        val audios = direct.audio.filter { it.parts.isNotEmpty() }.map { a ->
            ProgressiveMediaSource.Factory(dataSourceFactory, LabeledExtractorsFactory.forAudio(a.lang, a.label, a.default))
                .createMediaSource(MediaItem.Builder().setUri(PartsDataSource.uriFor(a.parts, a.sizes)).setMediaId(mediaItem.mediaId).build())
        }
        if (audios.isEmpty()) return video
        return MergingMediaSource(*(listOf(video) + audios).toTypedArray())
    }

    val nextItem: PlayItem?
        get() {
            val c = current ?: return null
            if (!title.isSeries) return null
            return Library.nextEpisode(title, c.season, c.episode)?.let(::itemFor)
        }

    /**
     * Sarma. Uzak sarmada (30 sn'den fazla) en yakın anahtar kareye gidilir: tam kareye ulaşmak için önceki anahtar
     * kareden itibaren indirip çözmek gerekmez, video hemen başlar. Kısa sarmada (±10 sn) tam konuma gidilir.
     */
    fun seekTo(positionMs: Long) {
        val far = kotlin.math.abs(positionMs - player.currentPosition) > 30_000
        player.setSeekParameters(if (far) androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC else androidx.media3.exoplayer.SeekParameters.EXACT)
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) {
        val d = player.duration
        val target = (player.currentPosition + deltaMs).let { if (d > 0) it.coerceAtMost(d) else it }
        seekTo(target)
    }

    fun playNext() {
        val next = nextItem ?: return
        markFinished()
        play(next, 0L)
    }

    /** Video bittiğinde çağrılır. Sonraki bölüm başladıysa true döner. */
    /** Sıradaki bölümün açılış parçaları (bölüm bitince ya da "sonraki bölüm"de hemen başlasın) */
    fun warmNext() { nextItem?.let { ArchiveWarm.warmStream(it.stream) } }

    fun onEnded(): Boolean {
        markFinished()
        val next = nextItem ?: return false
        play(next, 0L)
        return true
    }

    private fun markFinished() {
        val item = current ?: return
        val d = player.duration.takeIf { it > 0 } ?: return
        persist(item, d, d)
        // 🧠 akıllı indirme: izlenen indirilmiş bölüm silinir, sıradaki iner
        runCatching { com.gflix.app.offline.Downloads.instance?.onWatched(title, item.season, item.episode) }
    }

    fun saveProgress() {
        val item = current ?: return
        val d = player.duration
        if (d <= 0 || player.playbackState == androidx.media3.common.Player.STATE_IDLE) return
        persist(item, player.currentPosition, d)
    }

    private fun persist(item: PlayItem, position: Long, duration: Long) {
        val p = WatchProgress(title.id, item.season, item.episode, position, duration, System.currentTimeMillis())
        progress = progress + (Library.progressKey(title.id, item.season, item.episode) to p)
        scope.launch { store.saveProgress(profileId, p) }
    }

    // ---- Ses ve altyazı ----

    fun selectAudio(option: TrackOption) {
        val item = current
        if (option.group == null && item != null && item.stream.versions.isNotEmpty()) {
            switchVersion(item, option.language ?: return)
            return
        }
        val group = option.group ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .setPreferredAudioLanguage(option.language)
            .build()
        audioLang = option.language
        persistLanguages()
    }

    fun selectSubtitle(option: TrackOption) {
        val group = option.group
        val builder = player.trackSelectionParameters.buildUpon()
        if (group == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).setPreferredTextLanguage(null)
            subtitleLang = null
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
                .setPreferredTextLanguage(option.language)
            subtitleLang = option.language
        }
        player.trackSelectionParameters = builder.build()
        persistLanguages()
    }

    /** Öbür dildeki videoya aynı saniyeden geç (oynuyorsa oynamaya devam eder, altyazı seçimi korunur). */
    private fun switchVersion(item: PlayItem, lang: String) {
        if (lang == versionLang) return
        val pos = player.currentPosition
        val playing = player.playWhenReady
        versionLang = lang
        audioLang = lang
        persistLanguages()
        player.setMediaSource(buildSource(item), pos)
        player.prepare()
        player.playWhenReady = playing
    }

    private fun persistLanguages() {
        val a = audioLang
        val s = subtitleLang
        scope.launch { store.setLanguages(profileId, a, s) }
    }

    fun audioOptions(tracks: Tracks): List<TrackOption> {
        val vs = current?.stream?.versions.orEmpty()
        if (vs.isNotEmpty()) {
            return vs.map { v ->
                var label = v.label ?: Library.languageName(v.lang)
                if (Library.sameLanguage(v.lang, title.originalLanguage)) label += " [Orijinal]"
                TrackOption(label, v.lang, null, -1, v.lang == versionLang)
            }
        }
        return TrackHelper.audio(tracks, title.originalLanguage)
    }
    fun subtitleOptions(tracks: Tracks): List<TrackOption> = TrackHelper.subtitles(tracks)
}
