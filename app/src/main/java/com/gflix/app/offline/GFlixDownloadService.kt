package com.gflix.app.offline

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.gflix.app.R
import com.gflix.app.appContainer

/** İndirmeleri arka planda (bildirimle) yürütür; Wi-Fi gelince kendiliğinden devam eder. */
@OptIn(UnstableApi::class)
class GFlixDownloadService : DownloadService(
    NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL, R.string.downloads_channel, 0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL) }

    override fun getDownloadManager(): DownloadManager = appContainer.downloads.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val msg = when {
            notMetRequirements and Requirements.NETWORK_UNMETERED != 0 -> "Wi-Fi bekleniyor (mobil veri kullanılmaz)"
            else -> "${downloads.count { it.state == Download.STATE_DOWNLOADING }} dosya indiriliyor"
        }
        return helper.buildProgressNotification(this, R.drawable.ic_launcher_monochrome, null, msg, downloads, notMetRequirements)
    }

    companion object {
        private const val NOTIFICATION_ID = 7301
        private const val JOB_ID = 7302
        private const val CHANNEL = "downloads"
    }
}
