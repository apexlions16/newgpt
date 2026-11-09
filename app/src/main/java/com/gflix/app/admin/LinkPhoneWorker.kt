package com.gflix.app.admin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gflix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Tarifteki bir dosya satırı (telefonun indireceği / aktaracağı link). */
data class RecipeLink(val lineIndex: Int, val url: String, val headers: Map<String, String>, val role: String = "", val item: Int = 0)

object RecipeLinks {
    private val roleKeys = setOf("video", "film", "ses", "audio", "dublaj", "altyazı", "altyazi", "sub", "subtitle", "altyazılar")
    private val episodeHeader = Regex("^(?:b[öo]l[üu]m|episode)?\\s*[:\\-]?\\s*(?:s\\d{1,2}\\s*e\\d{1,3}|\\d{1,2}x\\d{1,3})\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val fastLine = Regex("^mod\\s*:\\s*(hızlı|hizli|fast|direkt|birleştirmeden|birlestirmeden)\\s*$", RegexOption.IGNORE_CASE)

    /** "mod: hızlı" satırı: dosyalar birleştirilmeden, olduğu gibi oynatılır. */
    fun isFast(recipe: String): Boolean = recipe.lines().any { fastLine.matches(it.trim()) }

    fun find(recipe: String): List<RecipeLink> {
        var item = -1
        return recipe.lines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (episodeHeader.matches(line)) { item++; return@mapIndexedNotNull null }
            if (line.startsWith("#") || !line.contains(':')) return@mapIndexedNotNull null
            val key = line.substringBefore(':').trim().lowercase().split(Regex("\\s+")).first()
            val parts = line.substringAfter(':').split('|').map { it.trim() }
            val url = parts.first()
            // Fragman sadece telefondaki dosyaysa aktarılır (YouTube linkini GitHub indirir)
            if (key !in roleKeys && !(key == "fragman" && url.startsWith("content://"))) return@mapIndexedNotNull null
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("content://")) return@mapIndexedNotNull null
            val headers = mutableMapOf<String, String>()
            parts.drop(1).forEach { o ->
                val k = o.substringBefore(':').trim().lowercase()
                val v = o.substringAfter(':', "").trim()
                when (k) {
                    "referer" -> headers["Referer"] = v
                    "cookie", "çerez", "cerez" -> headers["Cookie"] = v
                    "ua", "user-agent" -> headers["User-Agent"] = v
                    "header", "başlık", "baslik" -> if (v.contains(':')) headers[v.substringBefore(':').trim()] = v.substringAfter(':').trim()
                }
            }
            RecipeLink(i, url, headers, if (key == "fragman") "trailer" else key, item.coerceAtLeast(0))
        }
    }

    /** Hızlı modda film/bölüm dosyalarının kalıcı olarak duracağı Release (iş silinse de kalır). */
    fun mediaTag(jobTag: String, item: Int): String = "media-" + jobTag.substringAfter('-') + "-" + item

    /** Linkleri GitHub'a aktarılan dosyaların adlarıyla değiştirir (asset:f0 ya da hızlı modda asset:media-…/f0). */
    fun rewrite(recipe: String, links: List<RecipeLink>, refs: List<String> = links.indices.map { "asset:f$it" }): String {
        val lines = recipe.lines().toMutableList()
        links.forEachIndexed { idx, l -> lines[l.lineIndex] = lines[l.lineIndex].replaceFirst(l.url, refs[idx]) }
        return lines.joinToString("\n")
    }
}

/**
 * "Telefon indirsin" modu: linkleri 1DM gibi birden fazla bağlantıyla parça parça indirir, her parçayı
 * hemen GitHub'a aktarır ve siler (telefonda en fazla birkaç yüz MB yer kaplar). Bitince tarifi
 * asset:… adlarıyla yükleyip Release'i yayınlar; birleştirme ve yükleme GitHub'da yapılır.
 */
class LinkPhoneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = AdminStore(context)
    private val tag get() = inputData.getString(KEY_TAG).orEmpty()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES).followRedirects(true).followSslRedirects(true).build()

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Hazırlanıyor…", 0, 0)

    private data class Part(val link: Int, val index: Int, val start: Long, val end: Long) // end dahil değil
    private data class Dest(val rel: Release, val base: String)

    /**
     * Telefondan aktarımlar sırayla: biri bitmeden öbürü başlamaz (10-15 film birden sıraya alınabilir).
     * Bekleyen iş "Sırada" yazar; önündeki bitince kendiliğinden başlar.
     */
    override suspend fun doWork(): Result {
        if (QUEUE.isLocked) store.updateLinkJob(tag) { it.copy(localMessage = "⏳ Sırada — önceki yükleme bitince başlar") }
        return QUEUE.withLock { transfer() }
    }

    private suspend fun transfer(): Result {
        val admin = store.current()
        val job = admin.linkJobs.firstOrNull { it.tag == tag } ?: return Result.failure()
        val gh = GitHubAdmin(admin.writeToken ?: return fail("Yönetici anahtarı yok"))
        runCatching { setForeground(foreground(job.title, 0, 0)) }
        if (job.localState == "failed") return Result.success()  // beklerken durduruldu
        store.updateLinkJob(tag) { it.copy(localState = "downloading", localMessage = null) }
        return try {
            val rel = gh.releaseById(job.releaseId) ?: return fail("GitHub'daki iş kaydı silinmiş")
            val links = RecipeLinks.find(job.recipe)
            if (links.isEmpty()) return fail("Tarifte link bulunamadı")
            // Hızlı modda film/bölüm dosyaları doğrudan kalıcı Release'lerine gider (GitHub'da taşıma/birleştirme yok)
            val fast = RecipeLinks.isFast(job.recipe)
            val releases = mutableMapOf(rel.tag to rel)
            val dests = links.mapIndexed { li, l ->
                val target = if (fast && l.role != "trailer") {
                    val t = RecipeLinks.mediaTag(tag, l.item)
                    releases.getOrPut(t) { gh.ensurePublishedRelease(t, "G-Flix · ${job.title}", "G-Flix içerik dosyaları (hızlı yükleme).") }
                } else rel
                Dest(target, "f$li")
            }
            val existingBy = releases.values.associate { r -> r.id to gh.listAssets(r.id).associateBy { it.name } }

            // Boyut ve parça desteğini öğren
            val infos = links.map { probe(it) }
            val total = infos.sumOf { it.first.coerceAtLeast(0) }
            store.updateLinkJob(tag) { it.copy(localTotal = total) }
            val done = AtomicLong(0)
            val window = AtomicLong(0)
            var lastUi = 0L
            var lastT = System.currentTimeMillis()
            fun report(n: Long) {
                done.addAndGet(n); window.addAndGet(n)
                val now = System.currentTimeMillis()
                if (now - lastUi > 1500) {
                    val speed = window.getAndSet(0) * 1000 / maxOf(now - lastT, 1)
                    lastUi = now; lastT = now
                    notifications.notify(NOTIF_ID + tag.hashCode(), notification(job.title, done.get(), total))
                    kotlinx.coroutines.runBlocking { store.updateLinkJob(tag) { it.copy(localDone = done.get(), localSpeed = speed) } }
                }
            }

            val sem = Semaphore(admin.parallel.coerceIn(2, 6) + 1)

            // Bilgisayar işi + telefon aynı Wi-Fi'de: dosyalar GitHub'a uğramadan doğrudan bilgisayara gider
            val lan = if (tag.startsWith(PcJobs.PREFIX) && !fast) runCatching { LanClient.find(gh, admin.writeToken!!) }.getOrNull() else null
            if (lan != null) {
                store.updateLinkJob(tag) { it.copy(localMessage = "📶 Bilgisayara doğrudan gönderiliyor (ev ağı: ${lan.address})") }
                val refs = transferLan(lan, links, infos, sem, ::report)
                val rewritten = RecipeLinks.rewrite(job.recipe, links, refs)
                gh.listAssets(rel.id).filter { it.name == "recipe.txt" }.forEach { gh.deleteAsset(it.id) }
                gh.uploadAsset(rel, "recipe.txt", rewritten.toByteArray().toRequestBody("text/plain; charset=utf-8".toMediaType()))
                gh.updateRelease(rel.id, draft = false, body = "⏳ Sırada — dosyalar bilgisayara doğrudan aktarıldı, bilgisayar birleştirecek")
                store.updateLinkJob(tag) { it.copy(localState = "published", localDone = total, localMessage = "📶 Dosyalar ev ağından bilgisayara gitti") }
                notifications.notify(NOTIF_ID + tag.hashCode(),
                    NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle(job.title).setContentText("Bilgisayara aktarıldı. Bilgisayarın birleştirip yüklüyor…").setAutoCancel(true).build())
                return Result.success()
            }

            coroutineScope {
                val jobs = links.indices.flatMap { li ->
                    val (size, ranged) = infos[li]
                    val d = dests[li]
                    val existing = existingBy.getValue(d.rel.id)
                    // Telefondaki dosya hızlı modda büyük parçalarla gider (doğrudan oynatılacak, parça sayısı az olsun)
                    val partSize = if (fast && links[li].url.startsWith("content://")) FAST_PART else PART
                    if (ranged && size > 0) {
                        val parts = (0 until ((size + partSize - 1) / partSize).toInt()).map { n -> Part(li, n, n * partSize, minOf(size, (n + 1) * partSize)) }
                        parts.map { p ->
                            async {
                                val name = "${d.base}.p%03d".format(p.index)
                                val have = existing[name]
                                if (have != null && have.state == "uploaded" && have.size == p.end - p.start) { report(p.end - p.start); return@async }
                                have?.let { gh.deleteAsset(it.id) }
                                sem.withPermit { transferPart(gh, d.rel, links[p.link], name, p.start, p.end, ::report) }
                            }
                        }
                    } else {
                        // Sunucu parça desteklemiyor: tek bağlantıyla akıt, parçalara bölerek aktar
                        listOf(async { sem.withPermit { streamWhole(gh, d.rel, links[li], d.base, existing, ::report) } })
                    }
                }
                jobs.awaitAll()
            }

            gh.listAssets(rel.id).filter { it.name == "recipe.txt" }.forEach { gh.deleteAsset(it.id) }
            val refs = dests.map { d -> if (d.rel.id == rel.id) "asset:${d.base}" else "asset:${d.rel.tag}/${d.base}" }
            val rewritten = RecipeLinks.rewrite(job.recipe, links, refs)
            gh.uploadAsset(rel, "recipe.txt", rewritten.toByteArray().toRequestBody("text/plain; charset=utf-8".toMediaType()))
            gh.updateRelease(rel.id, draft = false, body = when {
                fast -> "⏳ Sırada — telefon yükledi, GitHub kataloğa ekleyecek"
                tag.startsWith(PcJobs.PREFIX) -> "⏳ Sırada — telefon aktardı, bilgisayar birleştirecek"
                else -> "⏳ Sırada — telefon aktardı, GitHub birleştirecek"
            })
            store.updateLinkJob(tag) { it.copy(localState = "published", localDone = total, localMessage = null) }
            notifications.notify(NOTIF_ID + tag.hashCode(),
                NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(job.title).setContentText(if (fast) "Yüklendi. Birkaç dakika içinde uygulamada görünecek." else if (tag.startsWith(PcJobs.PREFIX)) "Aktarıldı. Bilgisayarın birleştirip yüklüyor…" else "Telefon indirdi ve aktardı. GitHub birleştirip yüklüyor…").setAutoCancel(true).build())
            Result.success()
        } catch (e: CancellationException) {
            store.updateLinkJob(tag) { it.copy(localState = "failed", localMessage = "Durduruldu") }
            throw e
        } catch (e: SourceHttpException) {
            fail(e.message ?: "Link indirilemedi")
        } catch (e: IOException) {
            if (runAttemptCount < 5) {
                store.updateLinkJob(tag) { it.copy(localMessage = "Bağlantı sorunu, yeniden denenecek: ${e.message}") }
                Result.retry()
            } else fail(e.message ?: "İndirme başarısız")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    class SourceHttpException(msg: String) : IOException(msg)

    /**
     * Ev ağı aktarımı: her dosya parça parça (paralel, kaldığı yerden devam) bilgisayara yazılır. Döner: tarifte linklerin
     * yerine geçecek "file:///… | sil" adresleri (bilgisayar, yükleme GitHub'da doğrulanınca bu dosyaları siler).
     */
    private suspend fun transferLan(lan: LanTarget, links: List<RecipeLink>, infos: List<Pair<Long, Boolean>>, sem: Semaphore,
                                    report: (Long) -> Unit): List<String> = coroutineScope {
        links.indices.map { li ->
            async {
                val link = links[li]
                val name = "f$li" + (lanExt(link).let { if (it.isNotEmpty()) ".$it" else "" })
                val (size, ranged) = infos[li]
                if (ranged && size > 0) {
                    val have = LanClient.ranges(lan, tag, name)
                    val parts = (0 until ((size + LAN_PART - 1) / LAN_PART).toInt()).map { n -> Part(li, n, n * LAN_PART, minOf(size, (n + 1) * LAN_PART)) }
                    parts.map { p ->
                        async {
                            if (have.any { p.start >= it.first && p.end - 1 <= it.last }) { report(p.end - p.start); return@async }
                            sem.withPermit { lanPart(lan, link, name, p.start, p.end, size, report) }
                        }
                    }.awaitAll()
                    LanClient.done(lan, tag, name, size) + " | sil"
                } else {
                    // Sunucu parça desteklemiyor: tek bağlantıyla indir, sırayla gönder
                    var offset = 0L
                    withContext(Dispatchers.IO) {
                        http.newCall(request(link)).execute().use { r ->
                            if (!r.isSuccessful) throw SourceHttpException("Link indirilemedi (HTTP ${r.code}): ${link.url.take(80)}")
                            val src = r.body!!.byteStream()
                            val buf = ByteArray(8 * 1024 * 1024)
                            while (true) {
                                if (isStopped) throw CancellationException()
                                var n = 0
                                while (n < buf.size) { val k = src.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                                if (n == 0) break
                                LanClient.put(lan, tag, name, offset, 0, buf.copyOf(n).toRequestBody("application/octet-stream".toMediaType()))
                                offset += n; report(n.toLong())
                                if (n < buf.size) break
                            }
                        }
                    }
                    LanClient.done(lan, tag, name, offset) + " | sil"
                }
            }
        }.awaitAll()
    }

    private fun lanExt(link: RecipeLink): String {
        val name = if (link.url.startsWith("content://")) MediaProbe.fileInfo(applicationContext, android.net.Uri.parse(link.url)).first
        else link.url.substringBefore('?').substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
    }

    private suspend fun lanPart(lan: LanTarget, link: RecipeLink, name: String, start: Long, end: Long, total: Long, report: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            if (isStopped) throw CancellationException()
            val sent = AtomicLong(0)
            try {
                if (link.url.startsWith("content://")) {
                    LanClient.put(lan, tag, name, start, total, UriRangeBody(applicationContext, android.net.Uri.parse(link.url), start, end - start,
                        { n -> sent.addAndGet(n); report(n) }, { isStopped }))
                } else {
                    // Siteden bu aralığı indir (geçici dosya), sonra bilgisayara gönder
                    val tmp = File(applicationContext.cacheDir, "lanpart_${tag}_${name}_$start")
                    try {
                        withContext(Dispatchers.IO) {
                            http.newCall(request(link, "bytes=$start-${end - 1}")).execute().use { r ->
                                if (r.code != 206 && !(r.code == 200 && start == 0L)) throw SourceHttpException("Parça indirilemedi (HTTP ${r.code})")
                                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out, 256 * 1024) }
                            }
                        }
                        if (tmp.length() != end - start) throw IOException("Parça eksik indi (${tmp.length()} / ${end - start})")
                        LanClient.put(lan, tag, name, start, total, tmp.asRequestBody("application/octet-stream".toMediaType()))
                        report(end - start); sent.set(end - start)
                    } finally {
                        tmp.delete()
                    }
                }
                return
            } catch (e: IOException) {
                report(-sent.get())
                if (e is SourceHttpException || isStopped || ++attempt >= 4) throw e
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }
    }

    private fun request(link: RecipeLink, range: String? = null): Request {
        val u = URI(link.url)
        val b = Request.Builder().url(link.url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
            .header("Referer", "${u.scheme}://${u.host}/")
        link.headers.forEach { (k, v) -> b.header(k, v) }
        range?.let { b.header("Range", it) }
        return b.build()
    }

    /** (boyut, parça destekli mi) */
    private suspend fun probe(link: RecipeLink): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        if (link.url.startsWith("content://")) {
            val size = MediaProbe.fileInfo(applicationContext, android.net.Uri.parse(link.url)).second
            if (size <= 0) throw SourceHttpException("Telefondaki dosya okunamadı (silinmiş ya da taşınmış olabilir)")
            return@withContext size to true
        }
        http.newCall(request(link, "bytes=0-0")).execute().use { r ->
            if (r.code == 401 || r.code == 403 || r.code == 404) throw SourceHttpException("Link açılmadı (HTTP ${r.code}): ${link.url.take(80)}")
            if (r.code == 206) {
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                total to (total > 0)
            } else (r.header("Content-Length")?.toLongOrNull() ?: -1L) to false
        }
    }

    private suspend fun transferPart(gh: GitHubAdmin, rel: Release, link: RecipeLink, name: String, start: Long, end: Long, report: (Long) -> Unit) {
        if (link.url.startsWith("content://")) {
            var attempt = 0
            while (true) {
                if (isStopped) throw CancellationException()
                val sent = AtomicLong(0)
                try {
                    gh.uploadAsset(rel, name, UriRangeBody(applicationContext, android.net.Uri.parse(link.url), start, end - start,
                        { n -> sent.addAndGet(n); report(n) }, { isStopped }))
                    return
                } catch (e: IOException) {
                    report(-sent.get())
                    if (isStopped || ++attempt >= 4) throw e
                    runCatching { gh.listAssets(rel.id).filter { it.name == name }.forEach { gh.deleteAsset(it.id) } }
                    kotlinx.coroutines.delay(3000L * attempt)
                }
            }
        }
        val tmp = File(applicationContext.cacheDir, "linkpart_${tag}_$name")
        var attempt = 0
        while (true) {
            if (isStopped) throw CancellationException()
            var got = 0L
            try {
                withContext(Dispatchers.IO) {
                    http.newCall(request(link, "bytes=$start-${end - 1}")).execute().use { r ->
                        if (r.code != 206 && !(r.code == 200 && start == 0L)) throw SourceHttpException("Parça indirilemedi (HTTP ${r.code})")
                        tmp.outputStream().use { out ->
                            val src = r.body!!.byteStream()
                            val buf = ByteArray(256 * 1024)
                            while (got < end - start) {
                                if (isStopped) throw CancellationException()
                                val n = src.read(buf, 0, minOf(buf.size.toLong(), end - start - got).toInt())
                                if (n < 0) break
                                out.write(buf, 0, n); got += n; report(n.toLong())
                            }
                        }
                    }
                }
                if (got != end - start) throw IOException("Parça eksik indi ($got / ${end - start})")
                gh.uploadAsset(rel, name, tmp.asRequestBody("application/octet-stream".toMediaType()))
                tmp.delete()
                return
            } catch (e: IOException) {
                report(-got)
                tmp.delete()
                if (e is SourceHttpException || ++attempt >= 4) throw e
                runCatching { gh.listAssets(rel.id).filter { it.name == name }.forEach { gh.deleteAsset(it.id) } }
                kotlinx.coroutines.delay(3000L * attempt)
            }
        }
    }

    private suspend fun streamWhole(gh: GitHubAdmin, rel: Release, link: RecipeLink, base: String, existing: Map<String, ReleaseAsset>, report: (Long) -> Unit) {
        existing.values.filter { it.name.startsWith("$base.p") }.forEach { gh.deleteAsset(it.id) }
        withContext(Dispatchers.IO) {
            http.newCall(request(link)).execute().use { r ->
                if (!r.isSuccessful) throw SourceHttpException("Link indirilemedi (HTTP ${r.code}): ${link.url.take(80)}")
                val src = r.body!!.byteStream()
                val buf = ByteArray(256 * 1024)
                var index = 0
                var finished = false
                while (!finished) {
                    val name = "$base.p%03d".format(index++)
                    val tmp = File(applicationContext.cacheDir, "linkpart_${tag}_$name")
                    var size = 0L
                    tmp.outputStream().use { out ->
                        while (size < PART) {
                            if (isStopped) throw CancellationException()
                            val n = src.read(buf, 0, minOf(buf.size.toLong(), PART - size).toInt())
                            if (n < 0) { finished = true; break }
                            out.write(buf, 0, n); size += n; report(n.toLong())
                        }
                    }
                    if (size > 0) gh.uploadAsset(rel, name, tmp.asRequestBody("application/octet-stream".toMediaType()))
                    tmp.delete()
                }
            }
        }
    }

    private suspend fun fail(msg: String): Result {
        store.updateLinkJob(tag) { it.copy(localState = "failed", localMessage = msg) }
        return Result.failure()
    }

    private fun notification(title: String, done: Long, total: Long) = run {
        if (notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Yüklemeler", NotificationManager.IMPORTANCE_LOW))
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Aktarılıyor: $title")
            .setContentText(if (total > 0) "%%%d · %,d / %,d MB".format(pct, done / 1_048_576, total / 1_048_576) else "Hazırlanıyor…")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun foreground(title: String, done: Long, total: Long): ForegroundInfo {
        val n = notification(title, done, total)
        val id = NOTIF_ID + tag.hashCode()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }

    companion object {
        private val QUEUE = kotlinx.coroutines.sync.Mutex()
        const val KEY_TAG = "tag"
        private const val CHANNEL = "uploads"
        private const val NOTIF_ID = 5200
        private const val PART = 96L * 1024 * 1024
        private const val FAST_PART = 256L * 1024 * 1024
        private const val LAN_PART = 64L * 1024 * 1024
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        fun enqueue(context: Context, tag: String) {
            val req = OneTimeWorkRequestBuilder<LinkPhoneWorker>()
                .setInputData(workDataOf(KEY_TAG to tag))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("gflix-linkphone")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("linkphone-$tag", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, tag: String) = WorkManager.getInstance(context).cancelUniqueWork("linkphone-$tag")
    }
}
