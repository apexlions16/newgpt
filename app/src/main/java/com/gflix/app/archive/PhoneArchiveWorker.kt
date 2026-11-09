package com.gflix.app.archive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.gflix.app.R
import com.gflix.app.admin.AdminStore
import com.gflix.app.admin.JobManifest
import com.gflix.app.admin.TaskStatus
import com.gflix.app.admin.UploadFile
import com.gflix.app.admin.UploadTask
import com.gflix.app.data.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URI
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Phone-only, one-title-at-a-time authorized ingest.
 *
 * CaptureEngine supplies browser-discovered media playlists; this worker fetches the actual
 * streams, remuxes all discoverable audio + subtitle tracks into one MKV, then hands the
 * private file to the already tested archive.org upload / checksum verification pipeline.
 *
 * Files remain on disk until ArchiveVerifyWorker confirms archive metadata, publishes the
 * updated catalog, and removes the completed job's staging directory.
 */
class PhoneArchiveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val store = AdminStore(ctx)
    private val id: String = inputData.getString(KEY_ID).orEmpty()
    private val folder get() = File(applicationContext.filesDir, "phone-archive/$id")

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Video hazırlanıyor…")

    override suspend fun doWork(): Result {
        if (id.isBlank()) return Result.failure()
        val queue = store.current().tasks.filter { it.tag == TAG && it.status != TaskStatus.DONE }
            .sortedBy { it.createdAt }
        if (queue.firstOrNull()?.id != id) {
            store.updateTask(id) { it.copy(message = "Sırada · önceki iş arşivde doğrulanıp temizlenince başlayacak") }
            return Result.retry()
        }
        val raw = File(folder, "capture.json")
        if (!raw.isFile) return Result.failure()
        val task = store.task(id) ?: return Result.failure()
        if (task.status == TaskStatus.DONE) return Result.success()
        // WorkManager may redeliver this worker after a process death between handoffs.
        if (task.files.isNotEmpty()) {
            if (task.processingSince > 0) ArchiveVerifyWorker.enqueue(applicationContext, id, 0)
            else ArchiveUploadWorker.enqueue(applicationContext, id)
            return Result.success()
        }
        val admin = store.current()
        if (!admin.archive || !admin.archiveRightsConfirmed) return fail("İzin onayı ve archive.org anahtarları Stüdyo Ayarları'nda gerekli")
        setForeground(foreground("Video ve sesler telefona indiriliyor"))
        return try {
            val snapshot = AppJson.parseToJsonElement(raw.readText()).jsonObject
            val media = snapshot["capture"] as? JsonObject ?: throw IOException("Yakalama verileri yok")
            val video = File(folder, "video.mkv")
            if (!video.isFile || video.length() < 1024) {
                // Atomic rename: a killed ffmpeg job must never look like a completed video.
                val partial = File(folder, "download.partial.mkv")
                partial.delete()
                remux(media, partial)
                if (!partial.renameTo(video)) throw IOException("MKV tamamlandı ama atomik kayıt yapılamadı")
            }
            if (!video.isFile || video.length() < 1024) throw IOException("Birleştirilmiş video boş")
            val file = task.files.firstOrNull()
            val name = file?.assetName ?: remoteName(task)
            val mediaInfo = withContext(Dispatchers.IO) { FFprobeKit.getMediaInformation(video.absolutePath).mediaInformation }
                ?: throw IOException("Tamamlanan MKV okunamadı")
            val tracks = mediaInfo.streams.orEmpty()
            if (tracks.none { it.type == "video" }) throw IOException("MKV içinde video izi yok")
            val manifest = task.manifest.copy(size = video.length(), fileName = name, parts = listOf(name),
                audio = tracks.filter { it.type == "audio" }.map {
                    com.gflix.app.admin.TrackSel(it.index.toInt(), it.tags?.optString("language", "und") ?: "und")
                },
                subs = tracks.filter { it.type == "subtitle" }.map {
                    com.gflix.app.admin.SubSel(it.index.toInt(), it.tags?.optString("language", "und") ?: "und")
                })
            store.updateTask(id) { it.copy(
                status = TaskStatus.QUEUED, message = "Birleştirme tamamlandı; archive.org yüklemesi başlıyor",
                manifest = manifest, files = listOf(UploadFile(Uri.fromFile(video).toString(), name, 0, video.length())),
                bytesDone = 0, bytesTotal = video.length(), phase = "upload", percent = 0, updatedAt = System.currentTimeMillis()
            ) }
            ArchiveUploadWorker.enqueue(applicationContext, id)
            Result.success()
        } catch (e: CancellationException) {
            store.updateTask(id) { it.copy(status = TaskStatus.QUEUED,
                message = "İşlem kesildi · kaynaklar korundu, yeniden başlatılacak") }
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2 && e is IOException) {
                store.updateTask(id) { it.copy(status = TaskStatus.QUEUED, message = "Tekrar denenecek: ${e.message}") }
                Result.retry()
            } else fail(e.message ?: "Telefonda birleştirme başarısız")
        }
    }

    private suspend fun fail(message: String): Result {
        store.updateTask(id) { it.copy(status = TaskStatus.FAILED, message = message.take(240)) }
        return Result.failure()
    }

    private suspend fun remux(capture: JsonObject, target: File) = withContext(Dispatchers.IO) {
        val streams = (capture["streams"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { text(it, "text").startsWith("#EXTM3U") }
        if (streams.isEmpty()) throw IOException("DRM korumasız HLS video kaynağı bulunamadı")
        // Prefer the master playlist over a single rendition: it exposes all audio languages.
        val best = streams.maxWithOrNull(compareBy<JsonObject> {
            text(it, "text").contains("#EXT-X-MEDIA:TYPE=AUDIO")
        }.thenBy {
            text(it, "duration").toDoubleOrNull() ?: 0.0
        })!!
        val url = text(best, "url")
        requirePublicHttps(url)
        val playlists = (capture["playlists"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val manifestTexts = listOf(text(best, "text")) + playlists.map { text(it, "text") }
        if (manifestTexts.any { "#EXT-X-KEY:" in it || "#EXT-X-SESSION-KEY:" in it })
            throw IOException("Şifreli/DRM içerikleri indirmek veya çözmek desteklenmiyor")

        // WebView's trusted capture headers are passed only to the specific HTTPS media input.
        val headers = (best["headers"] as? JsonObject).orEmpty()
            .mapNotNull { (key, value) ->
                val v = (value as? JsonPrimitive)?.content.orEmpty()
                if (key in setOf("User-Agent", "Referer", "Origin", "Cookie") &&
                    '\n' !in v && '\r' !in v && v.length < 4096) "$key: $v\r\n" else null
            }.joinToString("")
        // HLS is finite here: live playlists must not leave the queue running forever.
        val durationMs = ((streams + playlists).maxOfOrNull { obj ->
            text(obj, "duration").toDoubleOrNull() ?: Regex("#EXTINF:([0-9.]+)")
                .findAll(text(obj, "text")).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        } ?: 0.0).times(1000).toLong()
        val args = mutableListOf("-hide_banner", "-nostdin", "-y", "-loglevel", "warning",
            "-rw_timeout", "30000000")
        if (headers.isNotEmpty()) args += listOf("-headers", headers)
        args += listOf("-i", url)

        // Every available audio stream in the master playlist, and its first/highest video.
        // Alternative video resolutions are intentionally not duplicated.
        val subtitles = (capture["subs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { text(it, "text").isNotBlank() }
        val subFiles = mutableListOf<File>()
        for ((i, sub) in subtitles.withIndex()) {
            val contents = text(sub, "text")
            if (contents.length > 2_000_000) continue
            val ext = if (contents.trimStart().startsWith("WEBVTT")) "vtt" else "srt"
            val f = File(folder, "sub-$i.$ext")
            f.writeText(contents)
            subFiles.add(f)
            args += listOf("-i", f.absolutePath)
        }
        args += listOf("-map", "0:v:0", "-map", "0:a?", "-map", "0:s?")
        subFiles.indices.forEach { j -> args += listOf("-map", "${j + 1}:0") }
        args += listOf("-c:v", "copy", "-c:a", "copy", "-c:s", "srt",
            "-max_muxing_queue_size", "2048", "-f", "matroska", target.absolutePath)
        store.updateTask(id) { it.copy(status = TaskStatus.PROCESSING, phase = "download", percent = -1,
            message = "Kaynak açılıyor · ses ve altyazılar hazırlanıyor", updatedAt = System.currentTimeMillis()) }
        val completed = CompletableDeferred<com.arthenica.ffmpegkit.FFmpegSession>()
        val time = AtomicLong(0)
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(),
            { completed.complete(it) }, { /* Never persist source URLs, cookies or headers from FFmpeg logs. */ },
            { stats -> time.set(stats.time.toLong()) })
        val began = android.os.SystemClock.elapsedRealtime()
        var movedAt = began
        var lastSize = 0L
        var lastTime = 0L
        var previousTick = began
        try {
            while (!completed.isCompleted) {
                delay(1500)
                if (isStopped) throw CancellationException("Durduruldu")
                val now = android.os.SystemClock.elapsedRealtime()
                val size = target.length()
                val ms = time.get()
                if (size > lastSize || ms > lastTime) movedAt = now
                val speed = (size - lastSize).coerceAtLeast(0) * 1000 / (now - previousTick).coerceAtLeast(1)
                val pct = PhoneProgress.percent(ms, durationMs).let { if (it < 0) -1 else it.coerceAtMost(99) }
                val message = (if (pct >= 0) "%$pct · " else "") +
                    "${PhoneProgress.clock(ms)}" + (if (durationMs > 0) " / ${PhoneProgress.clock(durationMs)}" else "") +
                    " · ${size / 1_048_576} MB · ${speed / 1024} KB/sn · geçen ${PhoneProgress.clock(now - began)}" +
                    (if (now - movedAt > 15_000) " · kaynaktan veri bekleniyor (${(now - movedAt) / 1000} sn)" else "")
                store.updateTask(id) { it.copy(message = message, bytesDone = size, percent = pct,
                    updatedAt = System.currentTimeMillis()) }
                setForeground(foreground(message, pct))
                lastSize = size; lastTime = ms; previousTick = now
                if (now - movedAt > 180_000) throw IOException("Kaynak 3 dakikadır veri göndermiyor; yeniden denenecek")
            }
            val result = completed.await()
            if (!ReturnCode.isSuccess(result.returnCode)) {
                val reason = when {
                    folder.usableSpace < 64L * 1024 * 1024 -> "Telefonda yeterli boş alan yok"
                    else -> "Kaynak bağlantısı veya bir medya izi okunamadı"
                }
                throw IOException("$reason (FFmpeg ${result.returnCode?.value ?: -1}); kaynak sayfasını yeniden yakalamak gerekebilir")
            }
            store.updateTask(id) { it.copy(message = "MKV dosyası ve medya izleri kontrol ediliyor", percent = 100) }
            val info = withTimeout(60_000) {
                withContext(Dispatchers.IO) { FFprobeKit.getMediaInformation(target.absolutePath).mediaInformation }
            }
            if (info == null || target.length() < 1024 || info.streams.orEmpty().none { it.type == "video" })
                throw IOException("MKV doğrulanamadı; eksik dosya yüklenmeyecek")
            val actualMs = (info.duration?.toDoubleOrNull() ?: 0.0) * 1000
            if (durationMs > 0 && actualMs + 5000 < durationMs * 0.98)
                throw IOException("Video eksik indi; tamamı indirilmeden arşive gönderilmeyecek")
        } finally {
            if (!completed.isCompleted) {
                FFmpegKit.cancel(session.sessionId)
                // Wait for the writer to close before a retry may reuse the partial path.
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { withTimeout(10_000) { completed.await() } }
                }
            }
        }
    }

    private fun remoteName(task: UploadTask): String =
        ArchiveUploadWorker.remoteBase(task.kind, task.manifest.itemId.orEmpty(),
            task.manifest.season, task.manifest.episode, null) + ".mkv"

    private fun foreground(message: String, percent: Int = -1): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Telefonla arşivleme", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("G-Flix · Telefonda indiriliyor")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(8200 + id.hashCode().and(0x3fff), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(8200 + id.hashCode().and(0x3fff), notification)
    }

    companion object {
        const val TAG = "phone-archive"
        const val KEY_ID = "job_id"
        private const val CHANNEL = "phone-archive"

        private fun text(obj: JsonObject, key: String) =
            (obj[key] as? JsonPrimitive)?.content.orEmpty()

        private fun requirePublicHttps(url: String) {
            val u = URI(url)
            val host = u.host?.lowercase().orEmpty()
            require(u.scheme.equals("https", true) && host.isNotBlank() &&
                host != "localhost" && !host.endsWith(".local") && !host.endsWith(".internal") &&
                host !in setOf("127.0.0.1", "0.0.0.0", "::1")) { "Yalnızca genel HTTPS kaynakları destekleniyor" }
        }

        private fun slug(text: String): String {
            val ascii = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "media" }
        }

        fun retry(context: Context, jobId: String) {
            val req = OneTimeWorkRequestBuilder<PhoneArchiveWorker>()
                .setInputData(workDataOf(KEY_ID to jobId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 45, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$TAG-$jobId", ExistingWorkPolicy.REPLACE, req)
        }

        suspend fun startNext(context: Context) {
            AdminStore(context).current().tasks.filter { it.tag == TAG && it.status != TaskStatus.DONE }
                .minByOrNull { it.createdAt }?.takeIf { it.status == TaskStatus.QUEUED && it.files.isEmpty() }
                ?.let { retry(context, it.id) }
        }

        /** A captured page becomes exactly one durable WorkManager job, never concurrent uploads. */
        suspend fun enqueue(context: Context, title: String, capture: JsonObject, hint: JsonObject?): String {
            val admin = AdminStore(context).current()
            require(admin.archive && admin.archiveRightsConfirmed) {
                "Stüdyo Ayarları'nda archive.org anahtarlarını ve yayımlama hakkı onayını kaydet"
            }
            val tmdb = hint?.let { text(it, "tmdb") }.orEmpty()
            val tv = tmdb.startsWith("tv/") || hint?.containsKey("season") == true
            val kind = if (tv) "episode" else "movie"
            val number = tmdb.substringAfter('/', "").toIntOrNull()
            val season = hint?.let { text(it, "season").toIntOrNull() }
            val episode = hint?.let { text(it, "episode").toIntOrNull() }
            if (tv && (season == null || episode == null)) throw IOException("Sezon/bölüm bilgileri eksik")
            val itemId = (if (tv) "series-" else "movie-") +
                (if (number != null) "tmdb-$number" else slug(title))
            val prefix = ArchiveSource.prefix.ifBlank { admin.iaPrefix.orEmpty() }
            require(prefix.isNotBlank()) { "archive.org öneki eksik" }
            val id = UUID.randomUUID().toString()
            val folder = File(context.filesDir, "phone-archive/$id")
            if (!folder.mkdirs()) throw IOException("Geçici indirme klasörü oluşturulamadı")
            val snapshot = buildJsonObject {
                put("capture", capture); put("hint", hint ?: JsonObject(emptyMap())); put("title", title)
            }
            File(folder, "capture.json").writeText(snapshot.toString())
            val name = ArchiveUploadWorker.remoteBase(kind, itemId, season, episode, null) + ".mkv"
            val manifest = JobManifest(kind = kind, title = title, itemId = itemId, tmdb = number,
                season = season, episode = episode, fileName = name, size = 0, parts = listOf(name))
            val task = UploadTask(id = id, tag = TAG, title = title, kind = kind,
                createdAt = System.currentTimeMillis(), manifest = manifest,
                files = emptyList(), archiveItem = ArchiveSource.itemFor(prefix, itemId, kind, season),
                status = TaskStatus.QUEUED, message = "Telefonda indirmeyi bekliyor")
            AdminStore(context).update { it.copy(tasks = it.tasks + task) }
            val work = OneTimeWorkRequestBuilder<PhoneArchiveWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 45, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$TAG-$id", ExistingWorkPolicy.KEEP, work)
            return id
        }
    }
}
