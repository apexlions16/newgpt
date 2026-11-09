package com.gflix.app.data

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Ölü link bildirimi: bilgisayar kayıtlı sayfaları, YouTube kaynaklarını ve fragmanları günde bir kontrol eder.
 * Telefon birkaç saatte bir sorar; yeni ölü link varsa bildirim gösterir (dokununca Stüdyo açılır). Aynı link bir kez bildirilir.
 */
object LinkAlerts {
    private const val CHANNEL = "link-alerts"
    private const val PREFS = "gflix.link-alerts"

    fun schedule(context: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("link-alerts", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LinkAlertWorker>(4, TimeUnit.HOURS).setConstraints(net).build())
    }

    data class Item(val key: String, val title: String, val what: String, val detail: String)

    fun parse(o: JsonObject): List<Item> = (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
        val x = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (x[k] as? JsonPrimitive)?.content.orEmpty()
        Item(s("key"), s("title"), s("what"), s("detail")).takeIf { it.key.isNotEmpty() }
    }

    suspend fun check(context: Context) {
        if (!PcServer.configured) return
        val all = PcServer.alerts()
        notifyPending(context, (all["pending"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
        notifyNews(context, (all["news"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
        val items = parse(all)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        val fresh = items.filter { it.key !in seen }
        // Bilgisayarda düzelen linkler unutulur (tekrar ölürse yeniden bildirilir)
        prefs.edit().putStringSet("seen", items.map { it.key }.toSet()).apply()
        if (fresh.isEmpty()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ölü linkler", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 4711,
            Intent(context, MainActivity::class.java).putExtra(com.gflix.app.admin.CaptureService.EXTRA_OPEN_ROUTE, "admin")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lines = fresh.take(5).joinToString("\n") { "• ${it.title} ${it.what}" }
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (fresh.size == 1) "⚠ Bir link çalışmıyor" else "⚠ ${fresh.size} link çalışmıyor")
            .setContentText(fresh.first().let { "${it.title} ${it.what}" })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$lines\nSayfadan Yakala ile yeniden ekle ya da Stüdyo'dan bak."))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(4711, n) } catch (_: SecurityException) { /* bildirim izni yok */ }
    }
}

/** Onay bekleyen linkler: sayı arttıysa bildirim (dokununca Stüdyo). */
private fun notifyPending(context: Context, count: Int) {
    val prefs = context.getSharedPreferences("gflix.link-alerts", Context.MODE_PRIVATE)
    val last = prefs.getInt("pending", 0)
    prefs.edit().putInt("pending", count).apply()
    if (count <= last) return
    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("pending-links") == null)
        nm.createNotificationChannel(NotificationChannel("pending-links", "Onay bekleyen linkler", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(
        context, 4712,
        Intent(context, MainActivity::class.java).putExtra(com.gflix.app.admin.CaptureService.EXTRA_OPEN_ROUTE, "admin")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(context, "pending-links").setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle("📥 $count link onay bekliyor")
        .setContentText("Stüdyo'da TMDB ile eşleştir, sonra yayına girsin.")
        .setContentIntent(open).setAutoCancel(true).build()
    try { NotificationManagerCompat.from(context).notify(4712, n) } catch (_: SecurityException) { }
}

/** TMDB'de yeni sezon/bölüm duyuruları: sayı arttıysa bildirim (dokununca Stüdyo). */
private fun notifyNews(context: Context, count: Int) {
    val prefs = context.getSharedPreferences("gflix.link-alerts", Context.MODE_PRIVATE)
    val last = prefs.getInt("news", 0)
    prefs.edit().putInt("news", count).apply()
    if (count <= last) return
    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("news") == null)
        nm.createNotificationChannel(NotificationChannel("news", "Yeni sezon/bölüm duyuruları", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(
        context, 4713,
        Intent(context, MainActivity::class.java).putExtra(com.gflix.app.admin.CaptureService.EXTRA_OPEN_ROUTE, "admin")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(context, "news").setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle("🆕 $count yeni duyuru")
        .setContentText("Dizilerine yeni sezon/bölüm geliyor. Stüdyo'dan İstek listesine ekle.")
        .setContentIntent(open).setAutoCancel(true).build()
    try { NotificationManagerCompat.from(context).notify(4713, n) } catch (_: SecurityException) { }
}

class LinkAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { LinkAlerts.check(applicationContext) }    // bilgisayara ulaşılamazsa (dışarıda, kapalı) sessizce geç
        return Result.success()
    }
}
