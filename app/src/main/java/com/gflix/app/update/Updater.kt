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
    private const val APK_PREFIX = "G-Flix-Next-Telefon-"
    private const val UPDATE_REPO = "apexlions16/newgpt"
    private val updateHttp = okhttp3.OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES).build()

    data class Info(val code: Int, val name: String, val apkUrl: String, val size: Long, val digest: String? = null)

    /** Hazır bekleyen güncelleme (ayarlar ekranı gösterir) */
    val ready = MutableStateFlow<Info?>(null)
    val error = MutableStateFlow<String?>(null)

    fun recordError(context: Context, message: String?) {
        error.value = message
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("error", message).apply()
    }
    private const val CHANNEL = "updates"

    fun schedule(context: Context) {
        error.value = context.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("error", null)
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

    /** App updates always come from the app repository, independent of the user's media repository. */
    suspend fun latest(context: Context): Info? = withContext(Dispatchers.IO) {
        var best: Info? = null
        for (page in 1..3) {
            val req = Request.Builder().url("https://api.github.com/repos/$UPDATE_REPO/releases?per_page=100&page=$page").build()
            val arr = updateHttp.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("Güncelleme denetlenemedi (HTTP ${r.code})")
                AppJson.parseToJsonElement(r.body!!.string()).jsonArray
            }
            for (rel in arr) {
                val o = rel.jsonObject
                val tag = o["tag_name"]?.jsonPrimitive?.content ?: continue
                val n = Regex("^phone-v1\\.0\\.(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (o["draft"]?.jsonPrimitive?.content == "true" || o["prerelease"]?.jsonPrimitive?.content == "true") continue
                val apk = (o["assets"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { a ->
                    a["name"]?.jsonPrimitive?.content.orEmpty().let { it.endsWith(".apk") && it.startsWith(APK_PREFIX) }
                } ?: continue
                if (best == null || n > best.code) best = Info(n, "1.0.$n", apk["browser_download_url"]!!.jsonPrimitive.content,
                    apk["size"]?.jsonPrimitive?.longOrNull ?: 0L, apk["digest"]?.jsonPrimitive?.content)
            }
            if (arr.size < 100) break
        }
        recordError(context, null)
        best?.takeIf { it.code > BuildConfig.VERSION_CODE }
    }

    private fun apkFile(context: Context, info: Info) = File(context.cacheDir, "guncelleme/G-Flix-Archive-${info.code}.apk")

    @Suppress("DEPRECATION")
    private fun validate(context: Context, apk: File, expectedCode: Int? = null) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES
        val incoming = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalStateException("APK eksik veya bozuk; yeniden indir")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(incoming.packageName == context.packageName) { "Bu APK başka uygulamaya ait; kayıtlı bilgiler korunarak kurulamaz" }
        val code = if (Build.VERSION.SDK_INT >= 28) incoming.longVersionCode else incoming.versionCode.toLong()
        require(code > BuildConfig.VERSION_CODE && (expectedCode == null || code == expectedCode.toLong())) {
            "APK sürümü uygun değil; daha yeni telefon sürümünü indir"
        }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                .orEmpty().map { it.toCharsString() }.toSet()
        val known = signatures(installed)
        require(known.isNotEmpty() && known == signatures(incoming)) {
            "APK imzası mevcut uygulamayla eşleşmiyor. Uygulamayı silme; aynı imzayla hazırlanmış güncelleme gerekli"
        }
    }

    suspend fun download(context: Context, info: Info): File = withContext(Dispatchers.IO) {
        val f = apkFile(context, info)
        if (f.isFile && info.size > 0 && f.length() == info.size) {
            if (runCatching { validate(context, f, info.code) }.isSuccess) return@withContext f
            f.delete()
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        try {
            updateHttp.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("Güncelleme indirilemedi (HTTP ${r.code})")
                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            require(info.size > 0 && tmp.length() == info.size) { "APK eksik indi; yeniden denenecek" }
            info.digest?.takeIf { it.startsWith("sha256:") }?.let { expected ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                tmp.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected.removePrefix("sha256:"), true)) {
                    "APK bütünlük kontrolü başarısız; yeniden indir"
                }
            }
            validate(context, tmp, info.code)
            check(tmp.renameTo(f)) { "Güncelleme dosyası kaydedilemedi" }
            f
        } finally { tmp.delete() }
    }

    fun install(context: Context, apk: File) {
        validate(context, apk)
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            recordError(context, "G-Flix için bu kaynaktan uygulama yükleme iznini aç, ardından Şimdi Güncelle'ye dokun")
            if ((context.applicationContext as GFlixApp).visibleActivities > 0) context.startActivity(settings)
            else notifyConfirm(context, settings)
            return
        }
        recordError(context, null)
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
            .setContentText("Yüklemek için dokun. Android kurulum izni veya onay isteyebilir; kayıtlı bilgilerin korunur.")
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
            val activePhoneJob = com.gflix.app.admin.AdminStore(ctx).current().tasks.any {
                it.tag == com.gflix.app.archive.PhoneArchiveWorker.TAG && it.status != com.gflix.app.admin.TaskStatus.DONE
            }
            if ((ctx as GFlixApp).visibleActivities == 0 && !activePhoneJob) Updater.install(ctx, apk)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Updater.recordError(ctx, e.message ?: "Güncelleme tamamlanamadı")
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
            PackageInstaller.STATUS_SUCCESS -> { Updater.ready.value = null; Updater.recordError(context, null) }
            else -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Güncelleme imzası mevcut uygulamayla uyumlu değil; uygulamayı silmeden uygun APK'yı kullan"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Kurulum için telefonda boş alan aç ve yeniden dene"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android kurulumu engelledi; G-Flix için uygulama yükleme iznini kontrol et"
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Kurulum iptal edildi; istediğinde Şimdi Güncelle ile yeniden deneyebilirsin"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "APK geçersiz veya eksik; yeniden indir"
                    else -> "Android güncellemeyi tamamlayamadı (kod $status); yeniden dene"
                }
                Updater.recordError(context, message)
            }
        }
    }
}
