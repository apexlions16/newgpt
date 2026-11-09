package com.gflix.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gflix.app.MainActivity
import com.gflix.app.R
import java.util.concurrent.TimeUnit

/**
 * "Hatırlat" (Netflix'teki "Bana Hatırlat"): yakında gelecek yapım yayına girince bildirim.
 * Tarihi belliyse o saate WorkManager işi kurulur; tarihsizse katalog her yüklendiğinde (ya da tarih değişince) kontrol edilir.
 */
object Reminders {
    private const val PREFS = "gflix.reminders"
    private const val CHANNEL = "reminders"
    const val EXTRA_OPEN_TITLE = "openTitle"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ids(ctx: Context): Set<String> = prefs(ctx).getStringSet("ids", emptySet()).orEmpty()
    fun isOn(ctx: Context, id: String) = id in ids(ctx)

    /** Açar/kapatır; açıldıysa true. */
    fun toggle(ctx: Context, title: Title, releaseAt: Long?): Boolean {
        val set = ids(ctx).toMutableSet()
        val on = if (title.id in set) { set.remove(title.id); cancel(ctx, title.id); false } else { set.add(title.id); schedule(ctx, title.id, title.title, releaseAt); true }
        prefs(ctx).edit().putStringSet("ids", set).putString("name:${title.id}", title.title).apply()
        return on
    }

    private fun schedule(ctx: Context, id: String, name: String, at: Long?) {
        if (at == null) return
        val delay = (at - Library.nowSec()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .setInputData(workDataOf("id" to id, "name" to name))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("remind-$id", ExistingWorkPolicy.REPLACE, req)
    }

    private fun cancel(ctx: Context, id: String) = WorkManager.getInstance(ctx).cancelUniqueWork("remind-$id")

    /**
     * Katalog yüklenince: hatırlatılan yapım artık izlenebiliyorsa bildirim (tarihsizler ve kaçırılanlar için);
     * tarihi değiştiyse iş yeniden kurulur.
     */
    fun sync(ctx: Context, raw: Catalog?) {
        val set = ids(ctx).toMutableSet()
        if (set.isEmpty() || raw == null) return
        val now = Library.nowSec()
        val scheduled = Library.applySchedule(raw, now)
        val byId = scheduled?.titles.orEmpty().associateBy { it.id }
        val rawById = raw.titles.associateBy { it.id }
        for (id in set.toList()) {
            val t = byId[id] ?: run { set.remove(id); null } ?: continue
            if (Library.hasPlayable(t) && Library.nextRelease(rawById.getValue(id), now) == null) {
                notify(ctx, id, t.title); set.remove(id); cancel(ctx, id)
            } else schedule(ctx, id, t.title, Library.nextRelease(rawById.getValue(id), now))
        }
        prefs(ctx).edit().putStringSet("ids", set).apply()
    }

    fun notify(ctx: Context, id: String, name: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TITLE, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("$name şimdi G-Flix'te 🍿")
            .setContentText("Hatırlatmamı istemiştin. Hemen izlemeye başla.")
            .setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id.hashCode(), n) } catch (_: SecurityException) { /* bildirim izni yok */ }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        if (!Reminders.isOn(applicationContext, id)) return Result.success()
        Reminders.notify(applicationContext, id, inputData.getString("name") ?: "Yeni içerik")
        val set = Reminders.ids(applicationContext) - id
        applicationContext.getSharedPreferences("gflix.reminders", Context.MODE_PRIVATE).edit().putStringSet("ids", set).apply()
        return Result.success()
    }
}

/** Bildirimden açılan yapım (ana ekran bunu bir kere açar). */
object PendingOpen {
    val id = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Bildirimden açılacak ekran (ör. "admin/capture": Sayfadan Yakala dokunuş bekliyor) */
    val route = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
