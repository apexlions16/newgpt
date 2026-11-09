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
        val raw = File(folder, "capture.json")
        if (!raw.isFile) return Result.failure()
        val task = store.task(id) ?: return Result.failure()
        if (task.status == TaskStatus.DONE) return Result.success()
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
            val manifest = task.manifest.copy(size = video.length(), fileName = name, parts = listOf(name))
            store.updateTask(id) { it.copy(
                status = TaskStatus.QUEUED, message = "Birleştirme tamamlandı; archive.org yüklemesi başlıyor",
                manifest = manifest, files = listOf(UploadFile(Uri.fromFile(video).toString(), name, 0, video.length())),
                bytesTotal = video.length()
            ) }
            ArchiveUploadWorker.enqueue(applicationContext, id)
            Result.success()
        } catch (e: CancellationException) {
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
        val best = streams.maxByOrNull { text(it, "duration").toDoubleOrNull() ?: 0.0 }!!
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
        val args = mutableListOf("-hide_banner", "-nostdin", "-y", "-loglevel", "error")
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
        store.updateTask(id) { it.copy(status = TaskStatus.PROCESSING, message = "Video, tüm erişilebilir sesler ve altyazılar MKV'ye birleştiriliyor") }
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            target.delete()
            throw IOException("FFmpeg birleştirme başarısız (çıkış: ${session.returnCode?.value ?: -1})")
        }
        val info = FFprobeKit.getMediaInformation(target.absolutePath).mediaInformation
        if (info == null || target.length() < 1024)
            throw IOException("MKV doğrulanamadı")
    }

    private fun remoteName(task: UploadTask): String =
        ArchiveUploadWorker.remoteBase(task.kind, task.manifest.itemId.orEmpty(),
            task.manifest.season, task.manifest.episode, null) + ".mkv"

    private fun foreground(message: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Telefonla arşivleme", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("G-Flix · Telefonda indiriliyor")
            .setContentText(message)
            .setOngoing(true).build()
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
