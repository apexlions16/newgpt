package com.gflix.app.offline

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.gflix.app.appContainer
import com.gflix.app.data.AppJson
import com.gflix.app.data.Library
import com.gflix.app.data.Stream
import com.gflix.app.data.Title
import com.gflix.app.player.PartsDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Bir film/bölüm indirmesinin bilgisi (her dosya ayrı Media3 indirmesi; hepsi bu kayda bağlı). */
@Serializable
data class OfflineMeta(
    val key: String,
    val titleId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val name: String,
    val image: String? = null,
    val versionLang: String? = null,
    val addedAt: Long = 0,
    /** Anlık yayından indirilen: yayın adresi ve altyazılar (çevrimdışı oynatma aynı adreslerle önbellekten) */
    val hls: String? = null,
    val subs: List<com.gflix.app.data.Subtitle> = emptyList(),
    /** Doğrudan indirmede seçilen kalite/sesler (dönem.grup.sıra); oynatırken aynıları istenir */
    val streamKeys: List<String> = emptyList(),
    val height: Int = 0,
    /** Bilgisayarda küçültülmüş tek dosya (MP4); indirilince bilgisayardaki dosya silinir */
    val mp4: Boolean = false,
    val tcJob: String = "",
)

enum class DlState { Preparing, Queued, WaitingWifi, Downloading, Done, Failed }

data class OfflineItem(val meta: OfflineMeta, val state: DlState, val percent: Float, val bytes: Long)

/**
 * İndirilenler: ekle / sil / durum. Durum, Media3 indirme listesinden toplanır (yapım/bölüm başına).
 */
@OptIn(UnstableApi::class)
class Downloads(private val context: Context) {
    companion object {
        /** Oynatıcının ulaşması için (akıllı indirme) */
        @Volatile var instance: Downloads? = null
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _items = MutableStateFlow<Map<String, OfflineItem>>(emptyMap())
    val items: StateFlow<Map<String, OfflineItem>> = _items
    private var poller: Job? = null

    /**
     * Media3 indirme yöneticisi. Dikkat: kurulurken dinleyiciler [refresh]'i hemen çağırabilir; refresh bu özelliği
     * değil, kurulmuş nesneyi ([managerOrNull]) kullanır — yoksa kurulum kendini tekrar tekrar çağırıp uygulamayı çökertir.
     */
    @Volatile private var managerOrNull: DownloadManager? = null
    val manager: DownloadManager
        get() = managerOrNull ?: synchronized(this) {
            managerOrNull ?: Offline.manager(app, PartsDataSource.Factory(OkHttpDataSource.Factory(DirectHeaders.client(app.appContainer.httpClient)))).also { m ->
                managerOrNull = m
                m.addListener(object : DownloadManager.Listener {
                    override fun onInitialized(downloadManager: DownloadManager) = refresh()
                    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
                    override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) = refresh()
                })
            }
        }

    // ---- ayarlar: indirme kalitesi (0 = her seferinde sor) ve akıllı indirme
    private val prefs by lazy { app.getSharedPreferences("gflix.downloads", Context.MODE_PRIVATE) }
    var quality: Int
        get() = prefs.getInt("quality", 0)
        set(v) { prefs.edit().putInt("quality", v).apply() }
    var smart: Boolean
        get() = prefs.getBoolean("smart", true)
        set(v) { prefs.edit().putBoolean("smart", v).apply() }

    /**
     * 🧠 Akıllı indirme (Netflix gibi): indirilmiş bölüm sonuna kadar izlenince silinir, sıradaki bölüm indirilir
     * (sadece Wi-Fi'de; kalite: ayardaki, "sor" ise en iyisi).
     */
    fun onWatched(title: Title, season: Int?, episode: Int?) {
        if (!smart || season == null) return
        val key = Library.progressKey(title.id, season, episode)
        if (state(key)?.state != DlState.Done) return
        remove(key)
        val next = Library.nextEpisode(title, season, episode) ?: return
        val nk = Library.progressKey(title.id, next.season.number, next.episode.number)
        if (state(nk) == null) add(title, next.season.number, next.episode.number, null, quality)
    }

    fun init() {
        instance = this
        DirectHeaders.load(app)
        manager
        // Yoğunlukta indirmeler hata verip "başarısız"a düşmesin: bekletilir, sıra gelince kendiliğinden sürer
        scope.launch {
            com.gflix.app.data.RateLimit.state.collect { st ->
                val wait = st.blockedUntil - System.currentTimeMillis()
                if (wait > 0 && !com.gflix.app.archive.ArchiveSource.configured) {
                    manager.pauseDownloads()
                    delay(wait + 2000)
                    manager.resumeDownloads()
                }
            }
        }
    }

    fun refresh() {
        val m = managerOrNull ?: return
        if (!m.isInitialized) return
        val waitingWifi = m.notMetRequirements and Requirements.NETWORK_UNMETERED != 0 || m.notMetRequirements and Requirements.NETWORK != 0
        val groups = mutableMapOf<String, MutableList<Download>>()
        val metas = mutableMapOf<String, OfflineMeta>()
        m.downloadIndex.getDownloads().use { c ->
            while (c.moveToNext()) {
                val d = c.download
                val meta = runCatching { AppJson.decodeFromString<OfflineMeta>(String(d.request.data)) }.getOrNull() ?: continue
                groups.getOrPut(meta.key) { mutableListOf() }.add(d)
                metas[meta.key] = meta
            }
        }
        _items.value = groups.mapValues { (k, list) ->
            val state = when {
                list.any { it.state == Download.STATE_FAILED } -> DlState.Failed
                list.all { it.state == Download.STATE_COMPLETED } -> DlState.Done
                list.any { it.state == Download.STATE_DOWNLOADING } -> DlState.Downloading
                waitingWifi -> DlState.WaitingWifi
                else -> DlState.Queued
            }
            val done = list.sumOf { it.bytesDownloaded }
            // İlerleme asıl görüntü indirmesinden (HLS'te boyut baştan bilinmez; altyazılar küçük ama boyutları bilinir:
            // toplam bayttan hesaplanınca ilk saniyede %100 görünüp sonra geri düşüyordu)
            val main = list.filter { it.request.id.endsWith("|hls") || it.request.id.endsWith("|v") }
            val pct = when {
                state == DlState.Done -> 1f
                main.isNotEmpty() -> main.map { if (it.state == Download.STATE_COMPLETED) 1f else (it.percentDownloaded.takeIf { p -> p >= 0 } ?: 0f) / 100f }
                    .average().toFloat().coerceIn(0f, 0.999f)
                else -> list.map { if (it.percentDownloaded >= 0) it.percentDownloaded / 100f else 0f }.average().toFloat()
            }
            OfflineItem(metas.getValue(k), state, pct, done)
        }
        releaseTranscoded(_items.value.values)
        val pend = tcPending
        if (pend.isNotEmpty()) {
            _items.value = _items.value + pend.filter { it.meta.key !in _items.value }.associate { it.meta.key to OfflineItem(it.meta, DlState.Preparing, it.pct / 100f, 0) }
            pollTranscodes()
        }
        val active = _items.value.values.any { it.state == DlState.Downloading }
        if (active && poller?.isActive != true) poller = scope.launch { while (isActive) { delay(1000); refresh(); if (_items.value.values.none { it.state == DlState.Downloading }) break } }
    }

    fun state(key: String): OfflineItem? = _items.value[key]

    /** İndirilen dil sürümü (dublajlı ayrı videolarda oynatıcı bunu seçer). */
    fun versionFor(key: String): String? = _items.value[key]?.takeIf { it.state == DlState.Done }?.meta?.versionLang

    /** İndirilmiş anlık yayın (oynatıcı internetsiz buradan oynatır) */
    fun liveFor(key: String): OfflineMeta? = _items.value[key]?.takeIf { it.state == DlState.Done }?.meta?.takeIf { it.hls != null }

    /**
     * Anlık yayını telefona indir: bilgisayar siteden o anki yayını çözer ve aktarır (kendi diskine yazmaz); telefon
     * bütün görüntü/ses parçalarını ve altyazıları kendi şifreli deposuna alır. GitHub'a hiçbir şey gitmez.
     */
    private fun addLive(title: Title, season: Int?, episode: Int?, maxHeight: Int = 0) {
        val key = Library.progressKey(title.id, season, episode)
        val ep = Library.findEpisode(title, season, episode)
        scope.launch {
            // Önce doğrudan: video telefona siteden iner (bilgisayar sadece adresi verir); olmazsa bilgisayar üzerinden
            val d = runCatching { com.gflix.app.data.PcServer.direct(title.id, season, episode) }.getOrNull()
            if (d != null && d.master.isNotBlank()) {
                // istenen kalite sitede yok (hepsi daha yüksek): bilgisayar küçültsün, hazır dosya insin
                if (maxHeight > 0 && d.heights.isNotEmpty() && d.heights.none { it <= maxHeight }) {
                    startTranscode(title, season, episode, maxHeight, d); return@launch
                }
                addDirect(title, season, episode, maxHeight, d); return@launch
            }
            addViaPc(title, season, episode, maxHeight)
        }
    }

    private fun metaName(title: Title, season: Int?, episode: Int?): String {
        val ep = Library.findEpisode(title, season, episode)
        return if (ep != null) "${title.title} · S${ep.season.number}:B${ep.episode.number} ${ep.episode.title.orEmpty()}".trim() else title.title
    }

    /** Doğrudan indirme: kalite ve sesler telefonda seçilir (sitenin ana listesindeki gerçek kaliteler), dönüştürme yok */
    private fun addDirect(title: Title, season: Int?, episode: Int?, maxHeight: Int, d: com.gflix.app.data.DirectInfo) {
        val key = Library.progressKey(title.id, season, episode)
        val ep = Library.findEpisode(title, season, episode)
        DirectHeaders.set(app, d.headers)
        val subs = d.subs.map { com.gflix.app.data.Subtitle(it.lang, it.label, it.url, it.forced) }
        val item = androidx.media3.common.MediaItem.Builder().setUri(d.master).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        val helper = androidx.media3.exoplayer.offline.DownloadHelper.forMediaItem(app, item,
            androidx.media3.exoplayer.DefaultRenderersFactory(app), OkHttpDataSource.Factory(DirectHeaders.client(app.appContainer.httpClient)))
        helper.prepare(object : androidx.media3.exoplayer.offline.DownloadHelper.Callback {
            override fun onPrepared(h: androidx.media3.exoplayer.offline.DownloadHelper) {
                runCatching {
                    val mh = if (maxHeight > 0) maxHeight else Int.MAX_VALUE
                    val base = androidx.media3.exoplayer.offline.DownloadHelper.getDefaultTrackSelectorParameters(app)
                    for (p in 0 until h.periodCount) {
                        h.clearTrackSelections(p)
                        // istenen kaliteye kadar görüntü; Türkçe ses + orijinal ses
                        h.addTrackSelection(p, base.buildUpon().setMaxVideoSize(Int.MAX_VALUE, mh).setPreferredAudioLanguage("tr").build())
                        h.addTrackSelection(p, base.buildUpon().setMaxVideoSize(Int.MAX_VALUE, mh).setPreferredAudioLanguages("en", "und").build())
                    }
                    val keys = h.getDownloadRequest(ByteArray(0)).streamKeys
                    val got = d.heights.filter { it <= mh }.maxOrNull() ?: d.heights.minOrNull() ?: 0
                    val meta = OfflineMeta(key, title.id, season, episode, name = metaName(title, season, episode),
                        image = ep?.episode?.still ?: title.backdrop ?: title.poster, addedAt = System.currentTimeMillis(),
                        hls = d.master, subs = subs, streamKeys = keys.map { "${it.periodIndex}.${it.groupIndex}.${it.streamIndex}" }, height = got)
                    val data = AppJson.encodeToString(OfflineMeta.serializer(), meta).toByteArray()
                    DownloadService.sendAddDownload(app, GFlixDownloadService::class.java,
                        DownloadRequest.Builder("$key|hls", Uri.parse(d.master)).setMimeType(MimeTypes.APPLICATION_M3U8).setStreamKeys(keys).setData(data).build(), false)
                    subs.forEachIndexed { i, s ->
                        DownloadService.sendAddDownload(app, GFlixDownloadService::class.java,
                            DownloadRequest.Builder("$key|s$i", Uri.parse(s.url)).setData(data).build(), false)
                    }
                    val only = d.heights.size <= 1 || (maxHeight > 0 && d.heights.none { it <= maxHeight })
                    android.widget.Toast.makeText(app, "İndirme başladı · ${if (got > 0) "${got}p" else "en iyi kalite"}" +
                        (if (only && maxHeight > 0 && got > maxHeight) " (sitede daha düşük kalite yok)" else "") + " · doğrudan siteden, Wi-Fi'de",
                        android.widget.Toast.LENGTH_LONG).show()
                }.onFailure { android.widget.Toast.makeText(app, "İndirme başlatılamadı: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
                h.release()
            }

            override fun onPrepareError(h: androidx.media3.exoplayer.offline.DownloadHelper, e: java.io.IOException) {
                h.release()
                // site doğrudan vermedi: bilgisayar üzerinden dene
                addViaPc(title, season, episode, maxHeight)
            }
        })
    }

    // ---- 🎞 bilgisayarda küçültme işleri: hazırlanırken listede "Hazırlanıyor %x", hazır olunca tek MP4 dosya iner
    @kotlinx.serialization.Serializable
    data class TcPending(val meta: OfflineMeta, val job: String, val pct: Int = 0)
    private val tcSer = kotlinx.serialization.builtins.ListSerializer(TcPending.serializer())
    private val tcPrefs by lazy { app.getSharedPreferences("gflix.transcode", Context.MODE_PRIVATE) }
    private var tcPending: List<TcPending>
        get() = runCatching { AppJson.decodeFromString(tcSer, tcPrefs.getString("p", "[]") ?: "[]") }.getOrDefault(emptyList())
        set(v) { tcPrefs.edit().putString("p", AppJson.encodeToString(tcSer, v)).apply() }
    private var tcPoller: Job? = null

    private suspend fun startTranscode(title: Title, season: Int?, episode: Int?, h: Int, d: com.gflix.app.data.DirectInfo) {
        val key = Library.progressKey(title.id, season, episode)
        val ep = Library.findEpisode(title, season, episode)
        val job = runCatching { com.gflix.app.data.PcServer.transcodeAdd(title.id, season, episode, h) }.getOrElse {
            android.widget.Toast.makeText(app, "Küçültme başlatılamadı: ${it.message}", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val subs = d.subs.map { com.gflix.app.data.Subtitle(it.lang, it.label, it.url, it.forced) }
        val meta = OfflineMeta(key, title.id, season, episode, name = metaName(title, season, episode),
            image = ep?.episode?.still ?: title.backdrop ?: title.poster, addedAt = System.currentTimeMillis(), subs = subs, height = h, mp4 = true, tcJob = job)
        tcPending = tcPending.filter { it.meta.key != key } + TcPending(meta, job)
        android.widget.Toast.makeText(app, "Sitede ${h}p yok: bilgisayar ${h}p'ye küçültüyor, hazır olunca kendiliğinden iner.",
            android.widget.Toast.LENGTH_LONG).show()
        pollTranscodes()
        refresh()
    }

    private fun pollTranscodes() {
        if (tcPoller?.isActive == true) return
        tcPoller = scope.launch {
            while (isActive && tcPending.isNotEmpty()) {
                val next = mutableListOf<TcPending>()
                for (p in tcPending) {
                    val st = runCatching { com.gflix.app.data.PcServer.transcodeStatus(p.job) }.getOrNull()
                    when (st?.state) {
                        "ready" -> {
                            val meta = p.meta.copy(hls = st.url)
                            val data = AppJson.encodeToString(OfflineMeta.serializer(), meta).toByteArray()
                            DownloadService.sendAddDownload(app, GFlixDownloadService::class.java,
                                DownloadRequest.Builder("${meta.key}|hls", Uri.parse(st.url)).setMimeType(MimeTypes.VIDEO_MP4).setData(data).build(), false)
                            meta.subs.forEachIndexed { i, s ->
                                DownloadService.sendAddDownload(app, GFlixDownloadService::class.java,
                                    DownloadRequest.Builder("${meta.key}|s$i", Uri.parse(s.url)).setData(data).build(), false)
                            }
                        }
                        "error", "gone" -> android.widget.Toast.makeText(app, "Küçültülemedi: ${p.meta.name} ${st.err}".trim(), android.widget.Toast.LENGTH_LONG).show()
                        else -> next += p.copy(pct = st?.pct ?: p.pct)          // sürüyor (ya da bilgisayara ulaşılamadı: sonra yine bak)
                    }
                }
                tcPending = next
                refresh()
                delay(4000)
            }
        }
    }

    /** İndirme bittiyse bilgisayardaki küçültülmüş dosya silinsin (bir kez) */
    private fun releaseTranscoded(done: Collection<OfflineItem>) {
        done.filter { it.state == DlState.Done && it.meta.tcJob.isNotBlank() && !tcPrefs.getBoolean("done:${it.meta.tcJob}", false) }.forEach { it2 ->
            tcPrefs.edit().putBoolean("done:${it2.meta.tcJob}", true).apply()
            scope.launch { runCatching { com.gflix.app.data.PcServer.transcodeDone(it2.meta.tcJob) } }
        }
    }

    /** Bilgisayar üzerinden (eski yol): bilgisayar yayını aktarır */
    private fun addViaPc(title: Title, season: Int?, episode: Int?, maxHeight: Int) {
        val key = Library.progressKey(title.id, season, episode)
        val ep = Library.findEpisode(title, season, episode)
        scope.launch {
            val r = runCatching { com.gflix.app.data.PcServer.play(title.id, season, episode, fresh = true, maxHeight = maxHeight) }
            r.onFailure {
                android.widget.Toast.makeText(app, "İndirme başlatılamadı: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val info = r.getOrThrow()
            val subs = info.subs.map { com.gflix.app.data.Subtitle(it.lang, it.label, it.url, it.forced) }
            val meta = OfflineMeta(
                key, title.id, season, episode,
                name = if (ep != null) "${title.title} · S${ep.season.number}:B${ep.episode.number} ${ep.episode.title.orEmpty()}".trim() else title.title,
                image = ep?.episode?.still ?: title.backdrop ?: title.poster, addedAt = System.currentTimeMillis(),
                hls = info.hls, subs = subs,
            )
            val data = AppJson.encodeToString(OfflineMeta.serializer(), meta).toByteArray()
            // Ana liste + bütün sesler (Türkçe ve orijinal) tek HLS indirmesi; altyazılar ayrı küçük dosyalar
            DownloadService.sendAddDownload(app, GFlixDownloadService::class.java,
                DownloadRequest.Builder("$key|hls", Uri.parse(info.hls)).setMimeType(MimeTypes.APPLICATION_M3U8).setData(data).build(), false)
            subs.forEachIndexed { i, s ->
                DownloadService.sendAddDownload(app, GFlixDownloadService::class.java,
                    DownloadRequest.Builder("$key|s$i", Uri.parse(s.url)).setData(data).build(), false)
            }
            android.widget.Toast.makeText(app, "İndirme başladı" + (if (info.height > 0) " (${info.height}p)" else "") + " · sadece Wi-Fi'de.",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Film ya da bölümü indirir: video (dil sürümü), ayrı sesler ve altyazılar. */
    fun add(title: Title, season: Int?, episode: Int?, audioLang: String?, maxHeight: Int = 0) {
        val stream: Stream = if (season == null) title.stream ?: return
            else Library.findEpisode(title, season, episode)?.episode?.stream ?: return
        if (stream.live) return addLive(title, season, episode, maxHeight)
        val key = Library.progressKey(title.id, season, episode)
        val ep = Library.findEpisode(title, season, episode)
        val version = stream.versions.takeIf { it.isNotEmpty() }?.let { vs ->
            vs.firstOrNull { Library.sameLanguage(it.lang, audioLang) } ?: vs.firstOrNull { it.lang == "tr" } ?: vs.first()
        }
        val meta = OfflineMeta(
            key, title.id, season, episode,
            name = if (ep != null) "${title.title} · S${ep.season.number}:B${ep.episode.number} ${ep.episode.title.orEmpty()}".trim() else title.title,
            image = ep?.episode?.still ?: title.backdrop ?: title.poster, versionLang = version?.lang, addedAt = System.currentTimeMillis(),
        )
        val data = AppJson.encodeToString(OfflineMeta.serializer(), meta).toByteArray()
        val reqs = mutableListOf<DownloadRequest>()
        fun progressive(id: String, uri: Uri) = reqs.add(DownloadRequest.Builder("$key|$id", uri).setData(data).build())
        when {
            version != null -> progressive("v", PartsDataSource.uriFor(version.video))
            stream.direct != null -> {
                progressive("v", PartsDataSource.uriFor(stream.direct.video))
                stream.direct.audio.forEachIndexed { i, a -> if (a.parts.isNotEmpty()) progressive("a$i", PartsDataSource.uriFor(a.parts, a.sizes)) }
            }
            stream.master.isNotEmpty() -> reqs.add(DownloadRequest.Builder("$key|hls", Uri.parse(stream.master)).setMimeType(MimeTypes.APPLICATION_M3U8).setData(data).build())
            else -> return
        }
        stream.subtitles.forEachIndexed { i, s -> progressive("s$i", Uri.parse(s.url)) }
        reqs.forEach { DownloadService.sendAddDownload(app, GFlixDownloadService::class.java, it, false) }
    }

    fun remove(key: String) {
        tcPending.firstOrNull { it.meta.key == key }?.let { p ->
            tcPending = tcPending.filter { it.meta.key != key }
            scope.launch { runCatching { com.gflix.app.data.PcServer.transcodeDone(p.job) } }
            _items.value = _items.value - key
        }
        val ids = mutableListOf<String>()
        manager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) if (c.download.request.id.startsWith("$key|")) ids += c.download.request.id }
        ids.forEach { DownloadService.sendRemoveDownload(app, GFlixDownloadService::class.java, it, false) }
    }

    /** Diskte kapladığı toplam yer */
    fun usedBytes(): Long = runCatching { Offline.cache(app).cacheSpace }.getOrDefault(0L)
}
