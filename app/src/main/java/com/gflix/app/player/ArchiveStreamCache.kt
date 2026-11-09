package com.gflix.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * archive.org'dan oynatma. Diske hiçbir şey yazılmaz: oynatıcı bir konumu istediğinde o konumdan itibaren veri aynı
 * anda birkaç ayrı bağlantıyla çekilip sırayla verilir ([ParallelRangeReader]); bellekte en fazla birkaç parça durur.
 *
 * Neden: archive.org'da tek bağlantının hızı sınırlı ve her istek 0,5-2 sn bekletiyor; indirme programları gibi
 * birkaç bağlantıyı aynı anda açmak toplam hızı katlıyor. archive.org HTTP/2 konuştuğu için istemci HTTP/1.1'e
 * zorlanır (HTTP/2'de bütün istekler tek TCP bağlantısında birleşir, paralellik kaybolur). archive.org/download
 * yönlendirmesi bir kez çözülür, istekler doğrudan veri sunucusuna gider.
 */
@OptIn(UnstableApi::class)
object ArchiveStreamCache {

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val noRedirect by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /** Paralel parçaları indiren iş parçacıkları (uygulama kapanınca beklemez) */
    val executor by lazy {
        Executors.newCachedThreadPool { r -> Thread(r, "gflix-archive").apply { isDaemon = true } }
    }

    /** archive.org'dan gelen toplam bayt (ekrandaki hız için) */
    val received = AtomicLong(0)

    private val resolved = ConcurrentHashMap<String, Pair<String, Long>>()

    fun isArchive(uri: Uri?): Boolean {
        val h = uri?.host ?: return false
        return h == "archive.org" || h.endsWith(".archive.org")
    }

    /** archive.org/download/… adresinin yönlendirdiği veri sunucusu (30 dk hatırlanır). Ağ isteği: ana iş parçacığında çağırma. */
    fun resolve(url: String): String {
        resolved[url]?.let { (u, at) -> if (System.currentTimeMillis() - at < 30 * 60_000) return u }
        val target = runCatching {
            noRedirect.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                r.header("Location")?.takeIf { r.isRedirect && it.startsWith("https://") }
            }
        }.getOrNull() ?: return url
        resolved[url] = target to System.currentTimeMillis()
        return target
    }

    fun forget(url: String) { resolved.remove(url) }

    /**
     * Paralel okunacak büyük dosya mı (video/ses). archive.org metin dosyalarını (altyazılar, katalog) veri sunucusuna
     * yönlendirmeden kendi önyüzünden verir: boyut başlığı yok ve aralık isteğini (Range) yok sayar; bunlar normal okunur.
     */
    fun isParallel(uri: Uri?): Boolean {
        if (!isArchive(uri)) return false
        val ext = uri?.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext !in TEXT_EXT
    }
    private val TEXT_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "sub", "json", "txt", "xml")

    /** Eski sürümlerin diske yazdığı ön yükleme klasörü silinir (artık kullanılmıyor). */
    fun cleanupDisk(context: Context) {
        Thread { runCatching { File(context.cacheDir, "akis").deleteRecursively() } }.start()
    }

    /** archive.org adresleri paralel kaynaktan, diğerleri olduğu gibi [upstream]'den. */
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val plain = upstream.createDataSource()
            return Routing(WarmDataSource(ParallelHttpDataSource(plain)), WarmDataSource(plain), plain)
        }
    }

    /** archive.org videosu → (ısıtılmış bellek +) paralel; archive.org metin dosyası → (bellek +) normal; diğerleri → normal */
    private class Routing(private val parallel: DataSource, private val archiveText: DataSource, private val plain: DataSource) : DataSource {
        private var active: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) {
            parallel.addTransferListener(transferListener); plain.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val ds = when {
                isParallel(dataSpec.uri) -> parallel
                isArchive(dataSpec.uri) -> archiveText
                else -> plain
            }
            active = ds
            return ds.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() { active?.close(); active = null }
    }
}

/** archive.org dosyasını [ParallelRangeReader] ile okuyan Media3 kaynağı. */
@OptIn(UnstableApi::class)
class ParallelHttpDataSource(
    /** Boyutu bilinmeyen ya da aralık isteğini desteklemeyen dosyalar için normal (tek bağlantılı) kaynak */
    private val fallback: DataSource? = null,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var fallbackOpen = false
    private var reader: ParallelRangeReader? = null
    private var opened = false
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val orig = dataSpec.uri.toString()
        val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else contentLength(orig)
        if (end == null) {
            // archive.org boyut söylemedi (önyüzden verilen dosya): paralel okunamaz, normal oku
            val fb = fallback ?: throw IOException("Dosya boyutu alınamadı")
            fallbackOpen = true
            return fb.open(dataSpec)
        }
        if (dataSpec.position > end) throw IOException("Konum dosyanın dışında")
        val net = ArchiveStreamCache.http
        reader = ParallelRangeReader(dataSpec.position, end, ArchiveStreamCache.executor) { from, to, sink ->
            val target = ArchiveStreamCache.resolve(orig)
            val call = net.newCall(Request.Builder().url(target).header("Range", "bytes=$from-$to").build())
            calls += call
            try {
                call.execute().use { r ->
                    if (r.code != 206 && !(r.code == 200 && from == 0L)) {
                        ArchiveStreamCache.forget(orig)   // veri sunucusu değişmiş olabilir: sonraki denemede yeniden sorulur
                        throw HttpDataSource.InvalidResponseCodeException(r.code, r.message, null, r.headers.toMultimap(), dataSpec, ByteArray(0))
                    }
                    val src = r.body!!.source()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf, 0, buf.size)
                        if (n < 0) break
                        ArchiveStreamCache.received.addAndGet(n.toLong())
                        if (!sink(buf, 0, n)) break
                    }
                }
            } finally {
                calls -= call
            }
        }
        opened = true
        transferStarted(dataSpec)
        return end - dataSpec.position
    }

    /** Dosya boyutu; archive.org söylemiyorsa (ya da aralık desteklemiyorsa) null */
    private fun contentLength(orig: String): Long? {
        val target = ArchiveStreamCache.resolve(orig)
        ArchiveStreamCache.http.newCall(Request.Builder().url(target).head().build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("archive.org hatası (HTTP ${r.code})")
            if (r.header("Accept-Ranges")?.contains("bytes") != true) return null
            return r.header("Content-Length")?.toLongOrNull()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (fallbackOpen) return fallback!!.read(buffer, offset, length)
        val n = reader?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (fallbackOpen) { fallbackOpen = false; fallback?.close() }
        reader?.close()
        reader = null
        calls.forEach { it.cancel() }
        calls.clear()
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

/**
 * Oynatıcı takılınca ekranda gösterilecek bilgi: archive.org'dan gelen hız, videonun gerektirdiği hız
 * (dosya boyutu / süre) ve bellekte hazır duran süre. İndirme yapmaz, sadece ölçer.
 */
@OptIn(UnstableApi::class)
class ArchiveMonitor(private val player: ExoPlayer) {
    data class Info(val speedBps: Long, val needBps: Long, val aheadSec: Int)
    val info = MutableStateFlow<Info?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var size = 0L
    private var fallbackDurationMs = 0L
    private var speedAvg = 0.0

    fun follow(videoUrl: String?, totalBytes: Long, durationSec: Double?) {
        val archive = videoUrl != null && ArchiveStreamCache.isArchive(Uri.parse(videoUrl)) && totalBytes > 0
        size = if (archive) totalBytes else 0
        fallbackDurationMs = ((durationSec ?: 0.0) * 1000).toLong()
        info.value = null; speedAvg = 0.0
        if (!archive) { loop?.cancel(); loop = null; return }
        if (loop == null) loop = scope.launch {
            var lastBytes = ArchiveStreamCache.received.get()
            var lastAt = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val b = ArchiveStreamCache.received.get()
                val inst = (b - lastBytes) * 1000.0 / (now - lastAt).coerceAtLeast(1)
                lastBytes = b; lastAt = now
                speedAvg = if (speedAvg == 0.0) inst else speedAvg * 0.6 + inst * 0.4
                val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: fallbackDurationMs
                if (dur <= 0 || size <= 0) continue
                info.value = Info(speedAvg.toLong(), (size * 1000.0 / dur).toLong(), ((player.bufferedPosition - player.currentPosition) / 1000).toInt())
            }
        }
    }

    fun release() {
        loop?.cancel(); loop = null
        info.value = null
    }
}
