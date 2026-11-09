package com.gflix.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.gflix.app.data.CatalogRepository
import com.gflix.app.data.GitHub
import com.gflix.app.data.TokenHolder
import com.gflix.app.data.UserStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class GFlixApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /** Ekranda açık etkinlik sayısı (güncelleme, uygulama kullanılırken yüklenmesin diye) */
    @Volatile var visibleActivities = 0
        private set

    override fun onCreate() {
        super.onCreate()
        com.gflix.app.data.PcServer.init(this)
        com.gflix.app.archive.ArchiveSource.init(this)
        // Çökerse hatayı kaydet: bir sonraki açılışta gösterilir (kopyalanıp gönderilebilir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile().writeText("G-Flix ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" + android.util.Log.getStackTraceString(e))
            }
            // TV kutusu (yakalama cihazı): çökerse 3 sn sonra kendiliğinden yeniden açılsın, liste kaldığı yerden sürsün
            runCatching {
                if (com.gflix.app.admin.WishList.wasStarted(this) && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
                    val pi = android.app.PendingIntent.getActivity(this, 7, android.content.Intent(this, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT)
                    (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 3000, pi)
                }
            }
            previous?.uncaughtException(t, e)
        }
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { visibleActivities++ }
            override fun onActivityStopped(a: android.app.Activity) {
                visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
                // Uygulamadan çıkıldı: hazır bekleyen güncelleme varsa şimdi (sessizce) yüklensin
                if (visibleActivities == 0) com.gflix.app.update.Updater.installPendingSoon(this@GFlixApp)
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
        com.gflix.app.update.Updater.schedule(this)
        com.gflix.app.data.LinkAlerts.schedule(this)
        com.gflix.app.admin.WishNotify.schedule(this)
        // Bekleyen indirmeler (uygulama kapanmışken) kaldığı yerden sürsün
        try { container.downloads.init() } catch (e: Exception) { android.util.Log.e("GFlix", "indirmeler başlatılamadı", e) }
    }

    fun crashFile() = java.io.File(filesDir, "son_cokme.txt")

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.httpClient)
        .crossfade(true)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .build()
}

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val builtInToken: String? = BuildConfig.GH_TOKEN.ifBlank { null }
    val tokens = TokenHolder(builtInToken)
    val userStore = UserStore(context)
    val httpClient: OkHttpClient = GitHub.buildClient(tokens)
    val catalogRepository = CatalogRepository(context, httpClient)
    /** Çevrimdışı izleme için indirilenler */
    val downloads by lazy { com.gflix.app.offline.Downloads(context) }

    init {
        appScope.launch {
            userStore.state.collect { s -> tokens.token = s.token ?: builtInToken }
        }
    }

    val hasToken: Boolean get() = !tokens.token.isNullOrBlank()
}

val Context.appContainer: AppContainer get() = (applicationContext as GFlixApp).container
