package com.gflix.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.gflix.app.data.PartedFile
import com.gflix.app.data.ProfileData
import com.gflix.app.data.Stream
import com.gflix.app.data.Title
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * "Isıtma": oynatmadan önce archive.org'dan küçük parçaları belleğe alır, böylece açılışta beklenmez.
 *
 * Bir MKV/MP4 açılırken oynatıcı sırayla dosyanın başını (başlık), sonunu (sarma dizini / moov) ve altyazıları ister;
 * archive.org her isteği 0,5-2 sn bekletir. Yapım sayfası açılınca (oynat'a basmadan) ve oynatma başlayınca diğer dil
 * sürümleri ile sıradaki bölüm için şunlar alınır: videonun ilk 2 MB'ı, son 1 MB'ı, altyazı dosyaları ve veri sunucusu
 * adresi. Oynatıcı bu aralıkları bellekten anında okur, kalanı ağdan devam eder. Diske yazılmaz; en fazla ~32 MB.
 */
object ArchiveWarm {
    private const val HEAD = 2L shl 20
    private const val TAIL = 1L shl 20
    private const val MAX_TEXT = 2L shl 20
    private const val BUDGET = 32L shl 20

    /** Bellekteki parça: [start] baytından itibaren [data]; [total] dosyanın tam boyutu (biliniyorsa) */
    class Chunk(val start: Long, val data: ByteArray, val total: Long?) {
        val end: Long get() = start + data.size
        val at = System.currentTimeMillis()
    }

    private val chunks = ConcurrentHashMap<String, List<Chunk>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** [url]'nin [position] baytını içeren parça */
    fun find(url: String, position: Long): Chunk? = chunks[url]?.firstOrNull { position >= it.start && position < it.end }

    // ------------------------------------------------------------ neyi ısıtalım

    /** Yapım sayfası: oynat'a basınca açılacak bölüm/film (bütün dil sürümleri ve altyazılarıyla). */
    fun warmTitle(title: Title, data: ProfileData?) {
        val target = data?.let { com.gflix.app.data.Library.playTarget(title, it) }
        val stream = if (title.isSeries) {
            val ref = target?.let { com.gflix.app.data.Library.findEpisode(title, it.season, it.episode) }
                ?: com.gflix.app.data.Library.episodes(title).firstOrNull()
            ref?.episode?.stream
        } else title.stream
        stream?.let { warmStream(it, onlyStart = (target?.positionMs ?: 0L) > 0) }
    }

    /**
     * Bir videonun açılış parçaları. [onlyStart]: kaldığı yerden devam edilecekse baştaki 2 MB yerine sadece başlığın
     * ilk 512 KB'ı (oynatıcı başlıktan sonra doğrudan kalınan yere gider).
     */
    fun warmStream(s: Stream, onlyStart: Boolean = false) {
        val files = buildList {
            s.direct?.video?.let(::add)
            s.versions.forEach { add(it.video) }
        }
        files.forEach { f -> warmVideo(f, if (onlyStart) 512L shl 10 else HEAD) }
        s.subtitles.forEach { warmText(it.url) }
    }

    private fun warmVideo(f: PartedFile, head: Long) {
        val url = f.parts.singleOrNull() ?: return
        val size = f.sizes.singleOrNull() ?: return
        if (!ArchiveStreamCache.isArchive(Uri.parse(url)) || size <= 0) return
        submit("$url#head") { fetchRange(url, 0, minOf(head, size), size) }
        if (size > head + TAIL) submit("$url#tail") { fetchRange(url, size - TAIL, TAIL, size) }
    }

    private fun warmText(url: String) {
        if (!ArchiveStreamCache.isArchive(Uri.parse(url))) return
        submit("$url#text") {
            if (find(url, 0)?.total != null) return@submit
            ArchiveStreamCache.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use
                val bytes = r.body?.bytes() ?: return@use
                if (bytes.size <= MAX_TEXT) put(url, Chunk(0, bytes, bytes.size.toLong()))
            }
        }
    }

    private fun submit(key: String, job: () -> Unit) {
        if (!inFlight.add(key)) return
        ArchiveStreamCache.executor.execute {
            try { runCatching(job) } finally { inFlight.remove(key) }
        }
    }

    private fun fetchRange(url: String, start: Long, len: Long, total: Long) {
        if (len <= 0) return
        find(url, start)?.let { if (it.end >= start + len) return }
        val target = ArchiveStreamCache.resolve(url)
        val req = Request.Builder().url(target).header("Range", "bytes=$start-${start + len - 1}").build()
        ArchiveStreamCache.http.newCall(req).execute().use { r ->
            if (r.code != 206) return
            val bytes = r.body?.bytes() ?: return
            if (bytes.size.toLong() == len) put(url, Chunk(start, bytes, total))
        }
    }

    private fun put(url: String, c: Chunk) {
        chunks.merge(url, listOf(c)) { a, b -> (a.filterNot { it.start == c.start } + b) }
        trim()
    }

    /** Bellek sınırı: en eski parçalar bırakılır */
    private fun trim() {
        var used = chunks.values.sumOf { l -> l.sumOf { it.data.size.toLong() } }
        if (used <= BUDGET) return
        val all = chunks.flatMap { (u, l) -> l.map { u to it } }.sortedBy { it.second.at }
        for ((u, c) in all) {
            if (used <= BUDGET) break
            chunks.computeIfPresent(u) { _, l -> (l - c).ifEmpty { null } }
            used -= c.data.size
        }
    }
}

/**
 * Önce bellekteki ısıtılmış parçadan okur, parça bitince asıl kaynağı ([inner]) kaldığı bayttan açar.
 * İstenen aralığın tamamı bellekteyse ağa hiç gidilmez.
 */
@OptIn(UnstableApi::class)
class WarmDataSource(private val inner: DataSource) : DataSource {
    private var spec: DataSpec? = null
    private var chunk: ArchiveWarm.Chunk? = null
    private var pos = 0L
    private var remaining = 0L        // C.LENGTH_UNSET: bilinmiyor
    private var innerOpen = false

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val c = ArchiveWarm.find(dataSpec.uri.toString(), dataSpec.position)
        if (c == null) {
            innerOpen = true
            return inner.open(dataSpec)
        }
        spec = dataSpec; chunk = c; pos = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            c.total != null -> c.total - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (innerOpen) return inner.read(buffer, offset, length)
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val c = chunk ?: return C.RESULT_END_OF_INPUT
        if (pos < c.end) {
            var n = minOf(length.toLong(), c.end - pos)
            if (remaining != C.LENGTH_UNSET.toLong()) n = minOf(n, remaining)
            System.arraycopy(c.data, (pos - c.start).toInt(), buffer, offset, n.toInt())
            pos += n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            return n.toInt()
        }
        // bellekteki parça bitti: kalanı ağdan (dosya sonuysa bitti)
        if (c.total != null && pos >= c.total) return C.RESULT_END_OF_INPUT
        val s = spec ?: return C.RESULT_END_OF_INPUT
        innerOpen = true
        inner.open(s.subrange(pos - s.position))
        return inner.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = if (innerOpen) inner.uri else spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (innerOpen) inner.responseHeaders else emptyMap()

    override fun close() {
        if (innerOpen) { innerOpen = false; inner.close() }
        spec = null; chunk = null
    }
}
