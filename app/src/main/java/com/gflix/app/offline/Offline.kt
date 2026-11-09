package com.gflix.app.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.AesCipherDataSink
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Çevrimdışı izleme: indirilen videolar uygulamanın özel klasöründe, bu kuruluma özel anahtarla AES ile şifreli
 * önbellek parçaları olarak durur. Dosya yöneticisi ya da başka bir oynatıcı açamaz; sadece G-Flix oynatır.
 * İndirmeler yalnızca ölçülmeyen ağda (Wi-Fi) çalışır: mobil veri kullanılmaz.
 */
@OptIn(UnstableApi::class)
object Offline {
    @Volatile private var cacheRef: SimpleCache? = null
    @Volatile private var managerRef: DownloadManager? = null
    @Volatile private var keyRef: ByteArray? = null
    private var db: StandaloneDatabaseProvider? = null

    /** Bu kuruluma özel 128 bit anahtar (uygulama verisinde; uygulama silinince indirmeler de okunamaz). */
    fun key(context: Context): ByteArray = keyRef ?: synchronized(this) {
        keyRef ?: run {
            val prefs = context.getSharedPreferences("gflix.offline", Context.MODE_PRIVATE)
            val saved = prefs.getString("k", null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            val k = saved ?: ByteArray(16).also { SecureRandom().nextBytes(it); prefs.edit().putString("k", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).apply() }
            keyRef = k; k
        }
    }

    fun database(context: Context) = db ?: synchronized(this) { db ?: StandaloneDatabaseProvider(context.applicationContext).also { db = it } }

    fun cache(context: Context): SimpleCache = cacheRef ?: synchronized(this) {
        cacheRef ?: SimpleCache(File(context.noBackupFilesDir, "indirilenler"), NoOpCacheEvictor(), database(context)).also { cacheRef = it }
    }

    /**
     * Şifreli önbellek kaynağı. [write]: indirirken önbelleğe (şifreleyerek) yazar; oynatırken false (izlenen her şey
     * önbelleğe dolmasın) — önbellekte varsa oradan, yoksa ağdan ([upstream]) okur.
     */
    fun cacheFactory(cache: Cache, key: ByteArray, write: Boolean, upstream: () -> DataSource): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { upstream() }
            .setCacheReadDataSourceFactory { AesCipherDataSource(key, FileDataSource()) }
            .setCacheWriteDataSinkFactory(
                if (write) DataSink.Factory { AesCipherDataSink(key, CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE), ByteArray(64 * 1024)) } else null,
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Oynatıcı için: indirildiyse cihazdan (ağ ve veri kullanmadan), değilse internetten. */
    fun playbackFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        cacheFactory(cache(context), key(context), write = false) { upstream.createDataSource() }

    fun manager(context: Context, upstream: DataSource.Factory): DownloadManager = managerRef ?: synchronized(this) {
        managerRef ?: run {
            val app = context.applicationContext
            val factory = cacheFactory(cache(app), key(app), write = true) { upstream.createDataSource() }
            DownloadManager(app, DefaultDownloadIndex(database(app)), DefaultDownloaderFactory(factory, Executors.newFixedThreadPool(3))).apply {
                // Veri paketinden yemesin: sadece Wi-Fi gibi ölçülmeyen ağda indir, mobil veride bekle
                requirements = Requirements(Requirements.NETWORK_UNMETERED)
                maxParallelDownloads = 1          // indirmeler sırayla: biri bitmeden sonraki başlamaz
            }.also { managerRef = it }
        }
    }
}
