package com.gflix.app.archive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gflix.app.R
import com.gflix.app.admin.AdminRepo
import com.gflix.app.admin.AdminStore
import com.gflix.app.admin.FileNames
import com.gflix.app.admin.MediaProbe
import com.gflix.app.admin.ProbeResult
import com.gflix.app.admin.SubFileSel
import com.gflix.app.admin.TaskStatus
import com.gflix.app.admin.TrackSel
import com.gflix.app.admin.UploadFile
import com.gflix.app.admin.UploadTask
import com.gflix.app.admin.JobManifest
import com.gflix.app.admin.UriRangeBody
import com.gflix.app.data.DirectMedia
import com.gflix.app.data.Episode
import com.gflix.app.data.PartedFile
import com.gflix.app.data.Stream
import com.gflix.app.data.Subtitle
import com.gflix.app.data.Title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Telefondaki dosyayı olduğu gibi (MKV/MP4, içindeki bütün sesler ve altyazılarla) archive.org'a yükler,
 * sonra yapımı TMDB bilgileriyle kataloğa ekler. Katalog her işten sonra değil, işler bitince toplu yazılır
 * ([ArchivePublishWorker]). Bağlantı koparsa ya da archive.org "yavaşla" derse bekleyip kaldığı dosyadan devam eder;
 * archive.org'da aynı boyutla duran dosya tekrar gönderilmez.
 */
class ArchiveUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = AdminStore(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val taskId get() = inputData.getString(KEY_TASK).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Sırada…", 0, 0)

    override suspend fun doWork(): Result {
        val admin = store.current()
        val task = store.task(taskId) ?: return Result.failure()
        if (task.status == TaskStatus.DONE) return Result.success()
        val item = task.archiveItem ?: return fail("archive.org item'ı belirlenmemiş")
        val (access, secret) = admin.archiveKeys() ?: return fail("archive.org anahtarları yok (Stüdyo Ayarları)")
        val client = ArchiveClient(access, secret)
        runCatching { setForeground(foreground(task.display, 0, 0)) }
        return try {
            gate.withPermit {
                store.updateTask(task.id) { it.copy(status = TaskStatus.UPLOADING, message = null) }
                upload(task, item, client)
            }
            // Gönderildi; yayına girmeden önce archive.org'un dosyaları işlemesi beklenir (ArchiveVerifyWorker)
            store.updateTask(task.id) {
                it.copy(status = TaskStatus.PROCESSING, message = "archive.org işliyor…", bytesDone = it.bytesTotal, processingSince = System.currentTimeMillis())
            }
            notifications.notify(NOTIF_ID + task.id.hashCode(),
                NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(task.display).setContentText("archive.org'a gönderildi. İşlenince kendiliğinden yayına girecek.")
                    .setAutoCancel(true).build())
            ArchiveVerifyWorker.enqueue(applicationContext, task.id, delaySec = 60)
            Result.success()
        } catch (e: CancellationException) {
            store.updateTask(task.id) { it.copy(status = TaskStatus.QUEUED, message = "Duraklatıldı") }
            throw e
        } catch (e: ArchiveRejected) {
            fail(e.message ?: "archive.org isteği reddetti")
        } catch (e: IOException) {
            if (runAttemptCount < 8) {
                store.updateTask(task.id) { it.copy(status = TaskStatus.QUEUED, message = "Bağlantı sorunu, yeniden denenecek: ${e.message}") }
                Result.retry()
            } else fail(e.message ?: "Yükleme başarısız")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun upload(task: UploadTask, item: String, client: ArchiveClient) {
        val existing = client.filesInfo(item)
        val total = task.files.sumOf { it.length }
        val done = AtomicLong(0)
        var lastUi = 0L
        val report: (Long) -> Unit = { n ->
            val now = done.addAndGet(n)
            val t = System.currentTimeMillis()
            if (t - lastUi > 1500) {
                lastUi = t
                notifications.notify(NOTIF_ID + task.id.hashCode(), buildNotification(task.display, now, total))
                setProgressAsync(workDataOf("task" to task.id, "done" to now, "total" to total))
            }
        }
        for (f in task.files) {
            // Daha önce gönderilmiş (md5'i kayıtlı) ya da archive.org'da aynı boyutla duran dosya tekrar gitmez
            val previousMd5 = store.task(task.id)?.files?.firstOrNull { it.assetName == f.assetName }?.md5
            val remote = existing[f.assetName]
            // A matching size is not proof; only a matching remote checksum allows a safe skip.
            if (previousMd5 != null && remote?.first == f.length &&
                remote.second != null && remote.second.equals(previousMd5, ignoreCase = true)) {
                report(f.length); continue
            }
            var attempt = 0
            var busyWaits = 0
            while (true) {
                if (isStopped) throw CancellationException()
                // Hesabın yükleme sırası doluysa archive.org kendisi söyler: boşalana kadar beklenir
                while (client.overLimit(item) && busyWaits++ < 60) {
                    store.updateTask(task.id) { it.copy(message = "archive.org sırası dolu, bekleniyor…") }
                    delay(60_000)
                }
                val sent = AtomicLong(0)
                val md5 = java.security.MessageDigest.getInstance("MD5")
                try {
                    client.put(item, f.assetName,
                        UriRangeBody(applicationContext, Uri.parse(f.uri), f.offset, f.length, { n -> sent.addAndGet(n); report(n) }, { isStopped }, md5),
                        meta = itemMeta(task), replace = true, sizeHint = total)
                    val hex = md5.digest().joinToString("") { "%02x".format(it) }
                    store.updateTask(task.id) { t ->
                        t.copy(bytesDone = done.get(), bytesTotal = total, message = null,
                            files = t.files.map { if (it.assetName == f.assetName) it.copy(md5 = hex) else it })
                    }
                    break
                } catch (e: ArchiveBusy) {
                    report(-sent.get())
                    if (++busyWaits > 60) throw e
                    store.updateTask(task.id) { it.copy(message = e.message) }
                    delay(e.retryAfterSec.coerceIn(30, 600) * 1000)
                } catch (e: IOException) {
                    report(-sent.get())
                    if (isStopped || ++attempt >= 5) throw e
                    store.updateTask(task.id) { it.copy(message = "Bağlantı koptu, tekrar deneniyor ($attempt/5)…") }
                    delay(5000L * attempt)
                }
            }
        }
    }

    private suspend fun fail(msg: String): Result {
        store.updateTask(taskId) { it.copy(status = TaskStatus.FAILED, message = msg) }
        return Result.failure()
    }

    private fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Yüklemeler", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(title: String, done: Long, total: Long) = run {
        ensureChannel()
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("archive.org'a yükleniyor: $title")
            .setContentText(if (total > 0) "%%%d · %,d / %,d MB".format(pct, done / 1_048_576, total / 1_048_576) else "Sırada…")
            .setProgress(100, pct, total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun foreground(title: String, done: Long, total: Long): ForegroundInfo {
        val n = buildNotification(title, done, total)
        val id = NOTIF_ID + taskId.hashCode()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }

    companion object {
        const val KEY_TASK = "task"
        const val TAG = "gflix-upload"
        private const val CHANNEL = "uploads"
        private const val NOTIF_ID = 5200

        /**
         * Aynı anda en fazla üç dosya yüklenir: archive.org tek dosyayı parçalı yüklemeyi desteklemez, dosya başına hız
         * sınırlı; birkaç dosyanın birlikte gitmesi toplamı artırır (daha fazlasında archive.org "yavaşla" diyor).
         */
        private val gate = Semaphore(3)

        fun enqueue(context: Context, taskId: String) {
            val req = OneTimeWorkRequestBuilder<ArchiveUploadWorker>()
                .setInputData(workDataOf(KEY_TASK to taskId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ia-upload-$taskId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("ia-verify-$taskId")
            WorkManager.getInstance(context).cancelUniqueWork("ia-upload-$taskId")
        }

        /** archive.org'daki dosya adı: film adı / s01e02 / fragman-tr / tema + orijinal uzantı */
        fun remoteBase(kind: String, itemId: String, season: Int?, episode: Int?, lang: String?): String = when (kind) {
            "episode" -> "s%02de%02d".format(season ?: 1, episode ?: 1)
            "trailer" -> "fragman-" + (lang ?: "und")
            "theme" -> "tema"
            else -> itemId.removePrefix("movie-").take(60)
        }

        /** Seçimlerden archive.org yükleme görevi: video tek dosya olarak (bölünmeden), yanına seçilen altyazı dosyaları. */
        fun buildTask(
            context: Context, prefix: String,
            kind: String, title: String, itemId: String, tmdb: Int?, year: Int?, season: Int?, episode: Int?,
            uri: Uri, probe: ProbeResult, extraAudio: List<com.gflix.app.admin.ExtraAudio> = emptyList(),
            versionLang: String? = null,
        ): UploadTask {
            val ext = probe.name.substringAfterLast('.', "mkv").lowercase()
            val audio = probe.audio.map { TrackSel(it.index, it.lang) }
            val base = remoteBase(kind, itemId, season, episode, audio.firstOrNull()?.lang)
            // Dil sürümleri aynı item'da yan yana: s01e01.tr.mkv, s01e01.en.mkv
            val files = mutableListOf(UploadFile(uri.toString(), (if (versionLang != null) "$base.$versionLang" else base) + ".$ext", 0, probe.size))
            val subFiles = mutableListOf<SubFileSel>()
            probe.subs.filter { it.fileUri != null && it.include }.forEach { s ->
                val subExt = s.fileName?.substringAfterLast('.', "srt")?.lowercase() ?: "srt"
                var name = "$base.${s.lang}${if (s.forced) ".forced" else ""}.$subExt"
                var n = 2
                while (files.any { it.assetName == name }) name = "$base.${s.lang}$n.$subExt".also { n++ }
                val size = MediaProbe.fileInfo(context, Uri.parse(s.fileUri!!)).second
                files += UploadFile(s.fileUri, name, 0, size)
                subFiles += SubFileSel(name, s.lang, s.forced)
            }
            val audioFiles = mutableListOf<com.gflix.app.admin.AudioFileSel>()
            extraAudio.forEachIndexed { i, a ->
                val aExt = a.name.substringAfterLast('.', "m4a").lowercase()
                var name = "$base.ses.${a.lang}.$aExt"
                var n = 2
                while (files.any { it.assetName == name }) name = "$base.ses.${a.lang}$n.$aExt".also { n++ }
                files += UploadFile(a.uri, name, 0, a.size)
                audioFiles += com.gflix.app.admin.AudioFileSel(name, a.lang, default = i == 0 && audio.isEmpty())
            }
            val manifest = JobManifest(
                kind = kind, title = title, itemId = itemId, tmdb = tmdb, year = year, season = season, episode = episode,
                fileName = probe.name, size = probe.size, parts = listOf(files.first().assetName),
                audio = audio, subs = emptyList(), subFiles = subFiles, audioFiles = if (versionLang != null) emptyList() else audioFiles,
                versionLang = versionLang,
            )
            return UploadTask(
                id = UUID.randomUUID().toString(), tag = "ia", title = title, kind = kind, createdAt = System.currentTimeMillis(),
                manifest = manifest, files = files, bytesTotal = files.sumOf { it.length },
                archiveItem = ArchiveSource.itemFor(prefix, itemId, kind, season),
            )
        }

        /** Item ilk açılırken yazılan bilgiler (archive.org sayfasında görünür, aramada "gflix" etiketiyle bulunur). */
        fun itemMeta(task: UploadTask): List<Pair<String, String>> {
            val m = task.manifest
            val audio = task.kind == "theme"
            val name = when (task.kind) {
                "episode" -> "${m.title} - ${m.season ?: 1}. Sezon"
                "trailer" -> "${m.title} - Fragman"
                "theme" -> "${m.title} - Tema Müziği"
                else -> m.title + (m.year?.let { " ($it)" } ?: "")
            }
            return listOfNotNull(
                "mediatype" to if (audio) "audio" else "movies",
                "collection" to if (audio) "opensource_audio" else "opensource_movies",
                "title" to name,
                "subject" to "gflix",
                "subject" to when (task.kind) { "episode" -> "dizi"; "trailer" -> "fragman"; "theme" -> "tema"; else -> "film" },
                m.year?.let { "date" to it.toString() },
                (m.audio.orEmpty().map { it.lang } + m.audioFiles.map { it.lang }).filter { it != "und" }?.distinct()?.takeIf { it.isNotEmpty() }?.let { "language" to it.joinToString(";") },
            )
        }

        /** Yüklenen dosyayı yapımın kaydına işler (yapım yoksa TMDB'den bilgileriyle açılır). */
        suspend fun addToCatalog(context: Context, task: UploadTask, item: String) {
            val m = task.manifest
            val itemId = m.itemId ?: FileNames.itemId(task.kind, task.title)
            val video = task.files.first()
            val file = PartedFile(listOf(ArchiveSource.fileUrl(item, video.assetName)), listOf(video.length))
            val langs = m.audio.orEmpty().map { it.lang }
            val subs = m.subFiles.map { Subtitle(it.lang, null, ArchiveSource.fileUrl(item, it.asset), it.forced) }
            val duration = runCatching {
                MediaMetadataRetriever().run {
                    try { setDataSource(context, Uri.parse(video.uri)); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) }
                    finally { release() }
                }
            }.getOrNull()
            val sizes = task.files.associate { it.assetName to it.length }
            val audios = m.audioFiles.map { a ->
                com.gflix.app.data.DirectAudio(a.lang, null, a.default, listOf(ArchiveSource.fileUrl(item, a.asset)), listOf(sizes[a.asset] ?: 0L))
            }
            val stream = Stream(direct = DirectMedia(video = file, audio = audios, videoAudio = langs), subtitles = subs, duration = duration)
            val series = task.kind == "episode"
            val catalog = ArchiveCatalog(context)
            val exists = catalog.current().titles.any { it.id == itemId }
            val meta = AdminRepo(context).tmdb()?.let { TmdbMeta(it) }
            val created = if (!exists && m.tmdb != null && task.kind in setOf("movie", "episode")) runCatching { meta?.title(itemId, series, m.tmdb) }.getOrNull() else null
            // Var olan yapımın fragmanı yoksa TMDB'den (YouTube) eklenir
            val backfill = if (exists && m.tmdb != null && catalog.current().titles.firstOrNull { it.id == itemId }?.let { it.trailers.isEmpty() && it.trailer == null } == true)
                runCatching { meta?.trailers(series, m.tmdb) }.getOrNull().orEmpty() else emptyList()
            val ep = if (series && m.tmdb != null) runCatching { meta?.episode(m.tmdb, m.season ?: 1, m.episode ?: 1) }.getOrNull() else null
            val now = System.currentTimeMillis() / 1000
            catalog.edit { c ->
                with(catalog) {
                    c.upsert(itemId, create = {
                        created ?: Title(id = itemId, type = if (series) "series" else "movie", tmdbId = m.tmdb, title = m.title, year = m.year, added = now, updated = now)
                    }) { t0 ->
                        val t = if (backfill.isNotEmpty() && t0.trailers.isEmpty() && t0.trailer == null)
                            t0.copy(trailers = backfill, trailer = backfill.firstOrNull { it.lang == "tr" } ?: backfill.first()) else t0
                        when (task.kind) {
                            "episode" -> ArchiveCatalog.putEpisode(t, m.season ?: 1, Episode(
                                number = m.episode ?: 1, title = ep?.title, overview = ep?.overview, still = ep?.still, runtime = ep?.runtime,
                                added = now, stream = ArchiveCatalog.mergeStream(
                                    t.seasons.firstOrNull { it.number == (m.season ?: 1) }?.episodes?.firstOrNull { it.number == (m.episode ?: 1) }?.stream,
                                    stream, m.versionLang),
                            )).copy(updated = now, comingSoon = false)
                            "trailer" -> {
                                val tr = stream.copy(lang = langs.firstOrNull() ?: "und", subtitles = emptyList())
                                val list = t.trailers.filterNot { it.lang == tr.lang } + tr
                                t.copy(trailers = list, trailer = list.firstOrNull { it.lang == "tr" } ?: list.first())
                            }
                            "theme" -> t.copy(theme = file)
                            else -> t.copy(stream = ArchiveCatalog.mergeStream(t.stream, stream, m.versionLang), comingSoon = false, updated = now)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Gönderilen dosyalar archive.org'da işlenip item'a girdi mi: her dosya listede aynı boyut ve aynı MD5 ile görünüyor
 * ve video indirilebiliyorsa yapım kataloğa eklenir ve yayına girer. Değilse bir süre sonra tekrar bakılır
 * (ilk 15 dk dakikada bir, sonra 3 dk'da bir); 24 saatte işlenmezse iş "başarısız" olur, "Tekrar dene" ile sürer.
 */
class ArchiveVerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = AdminStore(context)

    override suspend fun doWork(): Result {
        val id = inputData.getString(ArchiveUploadWorker.KEY_TASK) ?: return Result.failure()
        val task = store.task(id) ?: return Result.success()
        if (task.status != TaskStatus.PROCESSING) return Result.success()
        val item = task.archiveItem ?: return Result.success()
        val (access, secret) = store.current().archiveKeys() ?: return Result.retry()
        val client = ArchiveClient(access, secret)
        val since = task.processingSince.takeIf { it > 0 } ?: System.currentTimeMillis()
        val minutes = (System.currentTimeMillis() - since) / 60_000
        val remote = client.filesInfo(item)
        val ready = task.files.count { f ->
            val r = remote[f.assetName] ?: return@count false
            r.first == f.length && (if (task.tag == PhoneArchiveWorker.TAG)
                f.md5 != null && r.second != null && r.second.equals(f.md5, ignoreCase = true)
                else f.md5 == null || r.second == null || r.second.equals(f.md5, ignoreCase = true))
        }
        // aynı boyutta ama farklı içerik: archive.org'daki dosya bozuk ya da eski sürüm
        val mismatch = task.files.firstOrNull { f -> remote[f.assetName]?.let { r -> r.first == f.length && f.md5 != null && r.second != null && !r.second.equals(f.md5, true) } == true }
        val videoOk = ready == task.files.size && downloadable(ArchiveSource.fileUrl(item, task.files.first().assetName))
        if (videoOk) {
            return try {
                store.updateTask(id) { it.copy(message = "Kataloğa ekleniyor…") }
                ArchiveUploadWorker.addToCatalog(applicationContext, task, item)
                if (task.tag == PhoneArchiveWorker.TAG) {
                    // Confirm the updated catalog is also on archive.org before freeing phone space.
                    ArchiveCatalog(applicationContext).publish(client)
                    val staging = File(applicationContext.filesDir, "phone-archive/$id")
                    if (staging.exists() && !staging.deleteRecursively())
                        throw IOException("Arşiv doğrulandı fakat geçici dosyalar temizlenemedi")
                } else {
                    ArchivePublishWorker.enqueue(applicationContext)
                }
                store.updateTask(id) { it.copy(status = TaskStatus.DONE, message = null) }
                applicationContext.getSystemService(android.app.NotificationManager::class.java).notify(7300 + id.hashCode(),
                    NotificationCompat.Builder(applicationContext, "uploads").setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle(task.display).setContentText("archive.org işledi, yayında.").setAutoCancel(true).build())
                Result.success()
            } catch (e: Exception) {
                store.updateTask(id) { it.copy(message = "Kataloğa eklenemedi, tekrar denenecek: ${e.message}") }
                ArchiveVerifyWorker.enqueue(applicationContext, id, delaySec = 120)
                Result.success()
            }
        }
        if (mismatch != null && minutes > 30) {
            store.updateTask(id) { t ->
                t.copy(status = TaskStatus.FAILED, message = "archive.org'daki ${mismatch.assetName} gönderilenle aynı değil (MD5 farklı). \"Tekrar dene\" ile yeniden gönderilir.",
                    files = t.files.map { if (it.assetName == mismatch.assetName) it.copy(md5 = null) else it })
            }
            return Result.success()
        }
        if (minutes >= 24 * 60) {
            store.updateTask(id) { it.copy(status = TaskStatus.FAILED, message = "archive.org 24 saattir işlemedi. \"Tekrar dene\" ile kontrol sürer, eksik dosya yeniden gönderilir.") }
            return Result.success()
        }
        store.updateTask(id) { it.copy(message = "archive.org işliyor… $ready/${task.files.size} dosya hazır · ${minutes} dk") }
        enqueue(applicationContext, id, delaySec = if (minutes < 15) 60 else 180)
        return Result.success()
    }

    /** Video indirme adresi gerçekten açılıyor mu (ilk baytlar gelir mi) */
    private fun downloadable(url: String): Boolean = runCatching {
        com.gflix.app.player.ArchiveStreamCache.http.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use { r ->
            r.code == 206 || r.code == 200
        }
    }.getOrDefault(false)

    companion object {
        fun enqueue(context: Context, taskId: String, delaySec: Long) {
            val req = OneTimeWorkRequestBuilder<ArchiveVerifyWorker>()
                .setInputData(workDataOf(ArchiveUploadWorker.KEY_TASK to taskId))
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                .build()
            // kendini yeniden sıraya koyarken çalışan işi iptal etmesin: sona eklenir
            WorkManager.getInstance(context).enqueueUniqueWork("ia-verify-$taskId", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** Kataloğu archive.org'a yazar. Art arda biten işler tek yazışta toplanır (yeni iş gelince bekleme baştan başlar). */
class ArchivePublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = AdminStore(applicationContext)
        val (access, secret) = store.current().archiveKeys() ?: return Result.failure()
        val time = java.time.LocalTime.now().withNano(0).withSecond(0)
        return try {
            ArchiveCatalog(applicationContext).publish(ArchiveClient(access, secret))
            store.update { it.copy(iaCatalogNote = "✓ Katalog archive.org'a yazıldı ($time). İzleyicilerde birkaç dakika içinde görünür.") }
            Result.success()
        } catch (e: ArchiveRejected) {
            store.update { it.copy(iaCatalogNote = "⚠ Katalog yazılamadı: ${e.message}") }
            Result.failure()
        } catch (e: IOException) {
            store.update { it.copy(iaCatalogNote = "Katalog yazılamadı, tekrar denenecek: ${e.message}") }
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context, delaySec: Long = 15) {
            val req = OneTimeWorkRequestBuilder<ArchivePublishWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ia-catalog", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
