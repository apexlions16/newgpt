package com.gflix.app.admin

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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gflix.app.R
import com.gflix.app.data.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** İçerik URI'sinin bir bölümünü boyutu belli olarak gönderir (GitHub "chunked" kabul etmez). */
class UriRangeBody(
    private val context: Context,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
    private val onBytes: (Long) -> Unit,
    private val cancelled: () -> Boolean,
    /** Gönderilen baytların özeti (archive.org'daki dosyayla karşılaştırmak için MD5) */
    private val digest: java.security.MessageDigest? = null,
) : RequestBody() {
    override fun contentType(): MediaType = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = length
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = ByteBuffer.allocate(1024 * 1024)
                var pos = offset
                val end = offset + length
                while (pos < end) {
                    if (cancelled()) throw IOException("İptal edildi")
                    buf.clear()
                    if (end - pos < buf.capacity()) buf.limit((end - pos).toInt())
                    val n = ch.read(buf, pos)
                    if (n <= 0) throw IOException("Dosya beklenenden kısa (telefonda silinmiş ya da taşınmış olabilir)")
                    sink.write(buf.array(), 0, n)
                    digest?.update(buf.array(), 0, n)
                    pos += n
                    onBytes(n.toLong())
                }
            }
        } ?: throw IOException("Dosya açılamadı. Dosyayı yeniden seçmen gerekebilir.")
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = AdminStore(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val taskId get() = inputData.getString(KEY_TASK).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Hazırlanıyor…", 0, 0)

    override suspend fun doWork(): Result {
        val admin = store.current()
        var task = store.task(taskId) ?: return Result.failure()
        val token = admin.writeToken ?: return fail("Yönetici anahtarı yok")
        val gh = GitHubAdmin(token)
        runCatching { setForeground(foreground(task.display, 0, 0)) }
        store.updateTask(task.id) { it.copy(status = TaskStatus.UPLOADING, message = null) }
        return try {
            var rel = task.releaseId?.let { gh.releaseById(it) }
            if (rel == null) {
                rel = gh.createDraftRelease(task.tag, "📱 ${task.display}", "📤 Telefondan yükleniyor…")
                store.updateTask(task.id) { it.copy(releaseId = rel.id) }
            } else if (!rel.draft) {
                // Daha önce teslim edilmiş
                store.updateTask(task.id) { it.copy(status = TaskStatus.PROCESSING) }
                return Result.success()
            }
            val existing = gh.listAssets(rel.id).associateBy { it.name }
            val total = task.files.sumOf { it.length }
            val done = AtomicLong(0)
            val pending = task.files.filter { f ->
                val a = existing[f.assetName]
                val ok = a != null && a.state == "uploaded" && a.size == f.length
                if (ok) done.addAndGet(f.length)
                !ok
            }
            pending.forEach { f -> existing[f.assetName]?.let { gh.deleteAsset(it.id) } }
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
            val sem = Semaphore(admin.parallel.coerceIn(1, 6))
            val release = rel
            coroutineScope {
                pending.map { f ->
                    async {
                        sem.withPermit {
                            var attempt = 0
                            while (true) {
                                if (isStopped) throw CancellationException()
                                val sent = AtomicLong(0)
                                try {
                                    gh.uploadAsset(release, f.assetName, UriRangeBody(applicationContext, Uri.parse(f.uri), f.offset, f.length,
                                        { n -> sent.addAndGet(n); report(n) }, { isStopped }))
                                    break
                                } catch (e: IOException) {
                                    report(-sent.get())
                                    if (isStopped || ++attempt >= 4) throw e
                                    runCatching { gh.listAssets(release.id).filter { it.name == f.assetName }.forEach { gh.deleteAsset(it.id) } }
                                    kotlinx.coroutines.delay(4000L * attempt)
                                }
                            }
                            store.updateTask(task.id) { it.copy(bytesDone = done.get(), bytesTotal = total) }
                        }
                    }
                }.awaitAll()
            }
            // İş tanımı en son yüklenir; sonra Release yayınlanınca GitHub Actions işlemeye başlar
            existing["job.json"]?.let { gh.deleteAsset(it.id) }
            gh.listAssets(release.id).filter { it.name == "job.json" }.forEach { gh.deleteAsset(it.id) }
            val manifest = AppJson.encodeToString(JobManifest.serializer(), task.manifest).toByteArray()
            gh.uploadAsset(release, "job.json", manifest.toRequestBody("application/json".toMediaType()))
            gh.updateRelease(release.id, draft = false, body = "⏳ Sırada — GitHub'da işlenecek")
            store.updateTask(task.id) { it.copy(status = TaskStatus.PROCESSING, bytesDone = total, bytesTotal = total, message = null) }
            notifications.notify(NOTIF_ID + task.id.hashCode(),
                NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(task.display).setContentText("Telefondan yükleme bitti. GitHub'da hazırlanıyor…").setAutoCancel(true).build())
            Result.success()
        } catch (e: CancellationException) {
            store.updateTask(task.id) { it.copy(status = TaskStatus.QUEUED, message = "Duraklatıldı") }
            throw e
        } catch (e: IOException) {
            if (runAttemptCount < 6) {
                store.updateTask(task.id) { it.copy(status = TaskStatus.QUEUED, message = "Bağlantı sorunu, yeniden denenecek: ${e.message}") }
                Result.retry()
            } else fail(e.message ?: "Yükleme başarısız")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
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
            .setContentTitle("Yükleniyor: $title")
            .setContentText(if (total > 0) "%%%d · %,d / %,d MB".format(pct, done / 1_048_576, total / 1_048_576) else "Hazırlanıyor…")
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
        private const val CHANNEL = "uploads"
        private const val NOTIF_ID = 4200
        const val PART_SIZE = 256L * 1024 * 1024

        fun enqueue(context: Context, taskId: String) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_TASK to taskId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("gflix-upload")
                .build()
            // Yüklemeler sırayla: aynı zincire eklenir
            WorkManager.getInstance(context).enqueueUniqueWork("upload-$taskId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("upload-$taskId")
        }

        /** Seçimlerden yükleme görevi oluşturur. */
        fun buildTask(
            context: Context,
            kind: String, title: String, itemId: String?, tmdb: Int?, year: Int?, season: Int?, episode: Int?,
            uri: Uri, probe: ProbeResult,
        ): UploadTask {
            val tag = "staging-" + UUID.randomUUID().toString().take(12)
            val files = mutableListOf<UploadFile>()
            var off = 0L
            var i = 0
            while (off < probe.size) {
                val len = minOf(PART_SIZE, probe.size - off)
                files += UploadFile(uri.toString(), "part_%03d.bin".format(i++), off, len)
                off += len
            }
            val subFiles = mutableListOf<SubFileSel>()
            probe.subs.filter { it.fileUri != null && it.include }.forEachIndexed { j, s ->
                val ext = s.fileName?.substringAfterLast('.', "srt")?.lowercase() ?: "srt"
                val asset = "subfile_${j}_${s.lang}.$ext"
                val size = MediaProbe.fileInfo(context, Uri.parse(s.fileUri!!)).second
                files += UploadFile(s.fileUri, asset, 0, size)
                subFiles += SubFileSel(asset, s.lang, s.forced)
            }
            val manifest = JobManifest(
                kind = kind, title = title, itemId = itemId, tmdb = tmdb, year = year, season = season, episode = episode,
                fileName = probe.name, size = probe.size, parts = files.filter { it.assetName.startsWith("part_") }.map { it.assetName },
                audio = probe.audio.filter { it.include }.map { TrackSel(it.index, it.lang) },
                subs = probe.subs.filter { it.fileUri == null && it.include && !it.bitmap }.map { SubSel(it.index, it.lang, it.forced) },
                subFiles = subFiles,
            )
            return UploadTask(UUID.randomUUID().toString(), tag, title, kind, System.currentTimeMillis(), manifest, files,
                bytesTotal = files.sumOf { it.length })
        }
    }
}
