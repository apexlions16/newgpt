package com.gflix.app.admin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gflix.app.MainActivity
import com.gflix.app.R
import com.gflix.app.data.PcServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * 🔔 Panel telefonu: yakalama cihazında (TV kutusu) bir dizinin sezonu tamamlanınca ya da film alınınca bildirim.
 * Uygulama açıkken anında (panel 3 sn'de bir okur), kapalıyken 15 dk'da bir bakılır. Her olay bir kez bildirilir.
 */
object WishNotify {
    private const val CHANNEL = "wish-done"
    private const val PREFS = "gflix.wish-notify"

    fun schedule(c: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("wish-done", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WishNotifyWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
    }

    suspend fun check(c: Context) {
        if (WishList.wasWorker(c)) return                    // işi yapan cihaz kendine bildirim atmaz
        val st = PcServer.wishView()["state"] as? JsonObject ?: return
        events(c, st)
    }

    @Synchronized
    fun events(c: Context, st: JsonObject) {
        val list = (st["events"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val at = (o["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            at to (o["text"] as? JsonPrimitive)?.content.orEmpty()
        }
        if (list.isEmpty()) return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", -1L)
        val newest = list.maxOf { it.first }
        prefs.edit().putLong("seen", maxOf(newest, seen)).apply()
        if (seen < 0) return                                  // ilk kez: eskileri bildirme
        val fresh = list.filter { it.first > seen }
        if (fresh.isEmpty()) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "İstek listesi: biten sezonlar ve filmler", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 4720,
            Intent(c, MainActivity::class.java).putExtra(CaptureService.EXTRA_OPEN_ROUTE, "admin")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fresh.takeLast(8).forEach { (at, text) ->
            val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(text.substringBefore(" · "))
                .setContentText(text.substringAfter(" · ", "").ifBlank { "Stüdyo → Onay bekleyenler" })
                .setContentIntent(open).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(c).notify((at % 100000).toInt() + 50000, n) } catch (_: SecurityException) { }
        }
    }
}

class WishNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { WishNotify.check(applicationContext) }   // bilgisayara ulaşılamazsa sessizce geç
        return Result.success()
    }
}
