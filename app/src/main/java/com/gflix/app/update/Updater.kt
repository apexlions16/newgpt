package com.gflix.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gflix.app.BuildConfig
import com.gflix.app.GFlixApp
import com.gflix.app.MainActivity
import com.gflix.app.R
import com.gflix.app.appContainer
import com.gflix.app.data.AppJson
import com.gflix.app.data.GitHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kendini güncelleme: GitHub Releases'taki en yeni "app-v1.0.N" APK'sını Wi-Fi'de arka planda indirir ve
 * uygulama kullanılmıyorken yükler. Android 12+'da, uygulama bir kez kendi kendini güncelledikten sonra
 * sonraki güncellemeler onay sormadan yüklenir; ilkinde (ya da eski Android'de) bildirimden tek dokunuş yeter.
 */
object Updater {
    /** Sadece bu uygulamanın APK'ları (eski G-Flix'in APK'ları başka pakettir, kurulamaz) */
    private const val APK_PREFIX = "gflix-archive-"

    data class Info(val code: Int, val name: String, val apkUrl: String, val size: Long)

    /** Hazır bekleyen güncelleme (ayarlar ekranı gösterir) */
    val ready = MutableStateFlow<Info?>(null)
    private const val CHANNEL = "updates"

    fun schedule(context: Context) {
        val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(wifi).build())
        wm.enqueueUniqueWork("update-check-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(wifi).build())
    }

    fun installPendingSoon(context: Context) {
        if (ready.value == null) return
        WorkManager.getInstance(context).enqueueUniqueWork("update-install", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>().setInitialDelay(20, TimeUnit.SECONDS).build())
    }

    /** Sürüm kodu = 10000 + derleme no (android.yml); etiket archive-v2.0.N */
    private const val CODE_BASE = 10000

    suspend fun latest(context: Context): Info? = withContext(Dispatchers.IO) {
        // Bilgisayar eski G-Flix'in APK'sını verir; bu ayrı paket (com.gflix.archive) onu kuramaz
        if (com.gflix.app.data.PcServer.configured) return@withContext null
        // archive.org düzeninde anahtarsız GitHub isteği atılmaz (saatlik hak dolup başka şeyleri bekletmesin)
        if (com.gflix.app.archive.ArchiveSource.configured && context.appContainer.tokens.token.isNullOrBlank()) return@withContext null
        val client = context.appContainer.httpClient
        var best: Info? = null
        for (page in 1..3) {
            val req = Request.Builder().url("${GitHub.API}/repos/${GitHub.owner}/${GitHub.repo}/releases?per_page=100&page=$page").build()
            val arr = client.newCall(req).execute().use { r -> if (!r.isSuccessful) return@withContext best; AppJson.parseToJsonElement(r.body!!.string()).jsonArray }
            for (rel in arr) {
                val o = rel.jsonObject
                val tag = o["tag_name"]?.jsonPrimitive?.content ?: continue
                val n = Regex("^archive-v2\\.0\\.(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val code = CODE_BASE + n
                if (o["draft"]?.jsonPrimitive?.content == "true") continue
                val apk = (o["assets"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { a -> a["name"]?.jsonPrimitive?.content.orEmpty().let { it.endsWith(".apk") && it.startsWith(APK_PREFIX) } } ?: continue
                if (best == null || code > best.code) best = Info(code, "2.0.$n", apk["url"]!!.jsonPrimitive.content, apk["size"]?.jsonPrimitive?.longOrNull ?: 0L)
            }
            if (arr.size < 100 || best != null) break
        }
        best?.takeIf { it.code > BuildConfig.VERSION_CODE }
    }

    private fun apkFile(context: Context, info: Info) = File(context.cacheDir, "guncelleme/G-Flix-Archive-${info.code}.apk")

    suspend fun download(context: Context, info: Info): File = withContext(Dispatchers.IO) {
        val f = apkFile(context, info)
        if (f.exists() && (info.size == 0L || f.length() == info.size)) return@withContext f
        f.parentFile?.listFiles()?.forEach { it.delete() }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        context.appContainer.httpClient.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Güncelleme indirilemedi (HTTP ${r.code})")
            tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        tmp.renameTo(f)
        f
    }

    fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("G-Flix.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(context, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    fun notifyConfirm(context: Context, confirm: Intent?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val target = confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 9001, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("G-Flix güncellemesi hazır")
            .setContentText("Yüklemek için dokun (bir kerelik onay; sonrakiler kendiliğinden yüklenir).")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(9001, n) } catch (_: SecurityException) { }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val info = Updater.latest(ctx) ?: run { Updater.ready.value = null; return Result.success() }
            val apk = Updater.download(ctx, info)
            Updater.ready.value = info
            // Kullanılırken yükleme (uygulama kapanır): ekranda değilse şimdi, değilse çıkınca
            if ((ctx as GFlixApp).visibleActivities == 0) Updater.install(ctx, apk)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** PackageInstaller sonucu: onay gerekiyorsa (ilk güncelleme) bildirim, ekrandaysa doğrudan onay penceresi. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                val app = context.applicationContext as GFlixApp
                if (app.visibleActivities > 0 && confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else Updater.notifyConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.ready.value = null
            else -> Unit
        }
    }
}
