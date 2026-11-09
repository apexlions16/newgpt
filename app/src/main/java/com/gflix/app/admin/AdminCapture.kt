package com.gflix.app.admin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.content.MutableContextWrapper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.gflix.app.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Stüdyo → 🌐 Sayfadan Yakala: film/bölüm sayfasını kullanıcı kendisi açar (sitenin girişini/kontrollerini kendisi geçer).
 * Uygulama sayfadaki oynatıcıyı başlatır, reklam düğmelerine basar; bütün yayın listelerini (iframe'ler dahil), altyazıları,
 * çerezleri ve sayfa başlığını toplar. Uzun (asıl) yayın görününce hepsini bilgisayara gönderir ("yakalama: capture.json");
 * bilgisayar asıl yayını TMDB süresiyle seçer, reklamları atar, dil başına Experimental videoları yapıp yükler.
 */

private const val LONG_SEC = 10 * 60.0
/** Kısa bölümlü diziler (8–10 dk): 3 dk'dan uzun yayın, 25 sn içinde daha uzunu gelmezse asıl video sayılır (reklamlar 15–60 sn) */
private const val MID_SEC = 3 * 60.0
private const val MID_WAIT_MS = 25_000L
private const val PREFS = "gflix.capture"

internal class Found(val url: String, var kind: String, var frame: String?, var headers: Map<String, String> = emptyMap())
internal class Analyzed(val url: String, val kind: String, val text: String, val duration: Double, val headers: Map<String, String>)

/** Bir yakalama oturumu = bir sayfa. Sayfa değişince yenisi başlar. */
internal class CaptureSession(val pageUrl: String) {
    val found = ConcurrentHashMap<String, Found>()
    val analyzed = ConcurrentHashMap<String, Analyzed>()
    val subs = ConcurrentHashMap<String, String>()     // url -> metin
    /** Telefonun indirdiği alt listeler (en iyi görüntü + sesler): bilgisayara da gider, bazı siteler linki bir kez verir */
    val playlists = ConcurrentHashMap<String, String>()
    @Volatile var title: String = ""
    @Volatile var longAt: Long = 0
    @Volatile var midAt: Long = 0          // 3–10 dk'lık yayının ilk görüldüğü an (kısa bölüm olabilir)
    @Volatile var sent = false
    @Volatile var skipped = 0
    @Volatile var duplicate = false     // bu sayfa daha önce gönderilmiş, kullanıcıdan "Yine de gönder" bekleniyor
    @Volatile var force = false
    /** Gönderilince açılan işin etiketi (sıra, bilgisayar bu işi indirene kadar bekler) */
    @Volatile var sentTag: String? = null
    /** Yayının en yüksek görüntü yüksekliği (master listedeki RESOLUTION; bilinmiyorsa 0) */
    @Volatile var height: Int = 0
    /** Yedek oynatıcı kalitesi Close'tan düşük: gönderilmedi */
    @Volatile var lowAlt = false
    /** Oynatıcı çerçevesi (Close/Rapidrame gömülü sayfası) site tarafından reddedildi: sebep (ERR_BLOCKED_BY_RESPONSE, HTTP 403/429 …) */
    @Volatile var blocked: String? = null
    @Volatile var blockedAt: Long = 0
}

private val siteClient by lazy {
    // Site istekleri için ayrı istemci: GitHub anahtarı başka sitelere asla gitmez
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
}

private fun durationOf(text: String): Double =
    Regex("#EXTINF:\\s*([\\d.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

private fun bestVariant(master: String, base: String): String? {
    val lines = master.lines()
    var best: String? = null
    var score = -1L
    lines.forEachIndexed { i, l ->
        if (l.startsWith("#EXT-X-STREAM-INF")) {
            val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
            val sc = h * 10_000_000_000L + bw
            if (sc > score) { score = sc; best = runCatching { java.net.URI(base).resolve(next.trim()).toString() }.getOrNull() }
        }
    }
    return best
}

/**
 * Sayfadan Yakala motoru: tarayıcı (WebView), yakalama oturumu ve sırayla otomatik yakalama EKRANDAN BAĞIMSIZ yaşar.
 * Bölümleri seçip "Başlat"a basınca ekrandan çıkabilirsin: sıra arka planda sürer (tarayıcı uygulamanın arkasında
 * görünmez durur, dokunuşlar yine çalışır), bildirimde ilerleme görünür.
 *
 * Sıra kuralı (aynı anda tek iş): bir bölüm yakalanıp bilgisayara gönderilir; bilgisayar o bölümü siteden İNDİRMEYİ
 * BİTİRİNCE (ya da iş bitince/düşünce) sıradaki bölüm açılır. Böylece siteye aynı anda tek iş gider; bilgisayar önceki
 * bölümü GitHub'a yüklerken telefon sıradakini yakalar (üst üste bindirme). Beklerken tarayıcı boş sayfaya geçer
 * (video siteden boşuna akmasın).
 */
@SuppressLint("StaticFieldLeak")
object CaptureEngine {
    /** Yakalandıktan sonra videoda kalma süresi (sn): kullanıcı yazar (0–600), telefonda hatırlanır */
    var staySec by mutableIntStateOf(60)
        private set

    fun setStay(sec: Int) {
        staySec = sec.coerceIn(0, 600)
        if (::prefs.isInitialized) prefs.edit().putInt("stay", staySec).apply()
    }

    const val DEFAULT_STATUS = "Film ya da bölüm sayfasını aç. Gerisini uygulama yapar: videoyu başlatır, reklamları geçer, linkleri yakalayıp bilgisayara gönderir."

    // ---- tarayıcı + oturum
    /** Durum olarak tutulur: arka planda oluşturulunca CaptureBackgroundHost onu pencereye bağlasın */
    var web by mutableStateOf<WebView?>(null)
        private set
    private var webCtx: MutableContextWrapper? = null
    var address by mutableStateOf("")
    var started by mutableStateOf(false)
    var status by mutableStateOf(DEFAULT_STATUS)
    internal var session by mutableStateOf<CaptureSession?>(null)
    var sentTitle by mutableStateOf<String?>(null)
    private var ua = ""

    // ---- ada göre ara / sezon-bölüm seçimi
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SiteResult>>(emptyList())
    var episodes by mutableStateOf<List<SiteEpisode>>(emptyList())
    /** Bölüm sayfalarının bilgisayardaki durumu: "live" (yayında) / "pending" (onayda) */
    var srcState by mutableStateOf<Map<String, String>>(emptyMap())

    fun refreshSrcState() {
        val urls = episodes.map { it.url }
        if (urls.isEmpty() || !com.gflix.app.data.PcServer.configured) return
        scope.launch { runCatching { com.gflix.app.data.PcServer.sourceStatus(urls) }.onSuccess { srcState = it } }
    }
    var seriesTitle by mutableStateOf("")
    var pickSeason by mutableStateOf<Int?>(null)
    val picked = mutableStateListOf<String>()
    var navBusy by mutableStateOf(false)
    /** Yakında'dan "Medya ekle → Sayfadan yakala" ile gelindiyse o kayıt (açılış ayarı için) */
    var presetItem by mutableStateOf<String?>(null)

    // ---- sıra
    var queue by mutableStateOf<List<SiteEpisode>>(emptyList())
    var queueAt by mutableIntStateOf(-1)
    val queueDone = mutableStateMapOf<String, String>()      // adres → ✅ / ⏭ / ❌
    var needsUser by mutableStateOf(false)
    var skipNow by mutableStateOf(false)
    var waitingJob by mutableStateOf<String?>(null)           // sıradaki bölüm için beklenen işin etiketi
    var skipWait by mutableStateOf(false)
    private var queueJob: Job? = null
    val running: Boolean get() = queueAt >= 0 || wishActive

    // ---- İstek listesi (WishList.kt): arkada, siteye girmeden seçilen yapımları alana kadar dener
    /** Sayfa adresi → kullanıcının TMDB'den seçtiği yapım/bölüm (bilgisayardaki onayda tahmin yerine geçer) */
    internal val hints = ConcurrentHashMap<String, JsonObject>()
    /** İstek listesi şu an bir sayfa üzerinde çalışıyor (elle sıra/arama kapalı) */
    var wishActive by mutableStateOf(false)
    /** İstek listesi ekranı açık: Sayfadan Yakala ekranındayken de liste çalışabilir */
    var listOpen by mutableStateOf(false)
    /** Tarayıcı İstek listesi ekranındaki önizlemede (arka plan tutucusu onu bırakır) */
    var previewing by mutableStateOf(false)

    fun endPreview() { previewing = false; hostGen++ }

    // ---- 🪟 Arkada küçük pencere: uygulama arkadayken tarayıcı görünür kalsın (görünmeyen sayfada oynatıcı başlamıyor).
    // Kullanıcının verdiği "diğer uygulamaların üzerinde göster" izniyle; köşede küçük bir pencere.
    var overlayOn by mutableStateOf(false)
        private set
    /** 🔒 Kilit ekranı sayfası (WishRunActivity) açık: tarayıcı orada */
    var runActivityOn by mutableStateOf(false)
    fun bumpHost() { hostGen++ }
    private var overlayView: android.widget.LinearLayout? = null

    fun canOverlay(c: Context) = android.provider.Settings.canDrawOverlays(c)

    fun showOverlay() {
        if (runActivityOn || overlayOn || !::app.isInitialized || !canOverlay(app)) return
        val w = web ?: return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = app.resources.displayMetrics
        val d = dm.density
        val box = android.widget.LinearLayout(app).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()) }
        // Tutma çubuğu: sürükleyerek pencereyi taşı (tarayıcıya dokunuşlar oynatıcıya gider)
        val handle = android.widget.TextView(app).apply {
            text = "⠿  taşı"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 11f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF2A2A2A.toInt())
        }
        box.addView(handle, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (22 * d).toInt()))
        (w.parent as? ViewGroup)?.removeView(w)
        box.addView(w, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        var flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (WishList.night) flags = flags or android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        // Görünmez mod (varsayılan): pencere saydam ve dokunulmaz; sayfa yine "görünür" sayılır (oynatıcı başlar),
        // kullanıcı sadece üstteki çipi görür. Uygulamanın kendi dokunuşları doğrudan tarayıcıya gider, etkilenmez.
        val hidden = !WishList.showWindow || watching          // izlerken hiç görünmez (oynatıcının üstünde)
        if (hidden) flags = flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val wPx = (192 * d).toInt(); val hPx = (130 * d).toInt()
        val lp = android.view.WindowManager.LayoutParams(wPx, hPx,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = overlayX ?: (dm.widthPixels - wPx - (8 * d).toInt())
            y = overlayY ?: (dm.heightPixels - hPx - (96 * d).toInt())
            if (hidden) alpha = 0f
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (ev.rawX - downX)).toInt().coerceIn(0, dm.widthPixels - wPx)
                    lp.y = (startY + (ev.rawY - downY)).toInt().coerceIn(0, dm.heightPixels - hPx)
                    runCatching { wm.updateViewLayout(box, lp) }
                    overlayX = lp.x; overlayY = lp.y
                    true
                }
                else -> true
            }
        }
        runCatching { wm.addView(box, lp); overlayView = box; overlayOn = true }
            .onFailure { box.removeView(w); diag("pencere açılamadı: ${it.message}", force = true) }
    }

    /** Pencerenin son yeri (kullanıcı taşıdıysa orada açılır) */
    private var overlayX: Int? = null
    private var overlayY: Int? = null

    fun hideOverlay() {
        val box = overlayView ?: return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        web?.let { box.removeView(it) }
        runCatching { wm.removeView(box) }
        overlayView = null; overlayOn = false
        hostGen++                         // arka plan tutucusu / ekran tarayıcıyı geri alsın
    }

    // ---- bu ekrandan gönderilen işler (son 3'ü ekranın altında)
    val sentJobs = mutableStateListOf<LinkJobRef>()

    /** Tarayıcı Sayfadan Yakala ekranında mı (değilse sıra sürerken uygulamanın arkasında tutulur) */
    var onScreen by mutableStateOf(false)
    var hostGen by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private lateinit var prefs: android.content.SharedPreferences
    private var hookJs = ""
    /**
     * 🧪 Sade mod: sayfaya hiçbir betik eklenmez, otomatik dokunuş/oynatma yapılmaz (Chrome gibi açılır). Video
     * linkleri yine ağ seviyesinde yakalanır. Bazı oynatıcılar (ör. Close) bizim müdahalemizle başlamıyorsa bunu dene.
     */
    var plainMode by mutableStateOf(false)
    private var hookHandle: androidx.webkit.ScriptHandler? = null

    fun setPlain(on: Boolean) {
        plainMode = on
        prefs.edit().putBoolean("plain", on).apply()
        val w = web ?: return
        if (on) { hookHandle?.remove(); hookHandle = null }
        else if (hookHandle == null && hookJs.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            hookHandle = runCatching { WebViewCompat.addDocumentStartJavaScript(w, hookJs, setOf("*")) }.getOrNull()
        }
        w.reload()
    }

    /** G-Flix'in ekranda olan sayfa sayısı ve izleme (oynatıcı/fragman) sayfası açık mı */
    @Volatile private var startedActs = 0
    var watching by mutableStateOf(false)
        private set
    private var watchingActs = 0

    /** Uygulamanın hiçbir sayfası ekranda değil (gerçekten arka planda) */
    val appInBackground: Boolean get() = startedActs == 0

    fun init(context: Context, fromCreate: Boolean = false) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // Stüdyo'dan: MainActivity zaten ekranda (1). MainActivity.onCreate'ten: henüz başlamadı, onStart'ta sayılacak (0)
        startedActs = if (fromCreate) 0 else 1
        (app as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private fun isWatch(a: android.app.Activity) = a is com.gflix.app.player.PlayerActivity || a is com.gflix.app.player.TrailerActivity
            override fun onActivityStarted(a: android.app.Activity) {
                startedActs++
                if (isWatch(a)) { watchingActs++; watching = true }
                // Film/dizi izlenirken liste arkada sürer: tarayıcı oynatıcının üstünde görünmez pencerede kalır (sessiz)
                if (isWatch(a) && wishActive) { if (!overlayOn) showOverlay() }
                else if (a !is WishRunActivity) hideOverlay()     // uygulamanın bir sayfası açıldı: küçük pencere kalksın
            }
            override fun onActivityStopped(a: android.app.Activity) {
                startedActs = (startedActs - 1).coerceAtLeast(0)
                if (isWatch(a)) { watchingActs = (watchingActs - 1).coerceAtLeast(0); watching = watchingActs > 0 }
                // uygulama tamamen arkaya geçtiyse ve liste çalışıyorsa küçük pencere
                if (startedActs == 0 && WishList.started && WishList.bgRun && WishList.hasOpen()) showOverlay()
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        address = prefs.getString("last", "") ?: ""
        plainMode = prefs.getBoolean("plain", false)
        staySec = prefs.getInt("stay", 60)
        hookJs = runCatching { app.assets.open("gflix_hook.js").bufferedReader().readText() }.getOrDefault("")
        WishList.init(app)
        scope.launch { WishList.loop() }
        scope.launch { WishList.refreshWatch() }
        scope.launch { WishList.remoteLoop() }
        scope.launch { WishList.cmdLoop() }
        scope.launch { analyzeForever() }
        scope.launch { pollJobsForever() }
    }

    private fun repo() = AdminRepo(app)

    /** Tarayıcıyı verilen yere (ekran ya da arka plan) bağla: tek WebView, yeri değişir. */
    fun attach(ctx: Context): WebView {
        val w = web ?: createWeb(ctx)
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    /** Ekran açılınca/kapanınca: tarayıcının yeri değişsin diye arka plan tutucusu yeniden kurulur. */
    fun showOnScreen(v: Boolean) { onScreen = v; hostGen++ }

    /** Sıra yoksa ve ekran kapandıysa tarayıcı kapanır (eskisi gibi); sıra sürerken asla. */
    fun releaseIfIdle() {
        if (running || navBusy) return
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); w.destroy() }
        web = null; webCtx = null; started = false
    }

    // ---------------------------------------------------------------- yakalama

    internal fun onReport(json: String) {
        val s = session ?: return
        val o = runCatching { com.gflix.app.data.AppJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        val k = o["k"]?.jsonPrimitive?.content ?: return
        if (k == "skip") { s.skipped++; return }
        val u = o["u"]?.jsonPrimitive?.content ?: return
        val frame = o["p"]?.jsonPrimitive?.content
        o["t"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && frame == s.pageUrl }?.let { s.title = it }
        val path = u.substringBefore('?').lowercase()
        val kind = when {
            k == "hls" || path.endsWith(".m3u8") -> "hls"
            k == "sub" || path.endsWith(".vtt") || path.endsWith(".srt") -> "sub"
            else -> return
        }
        s.found.getOrPut(u) { Found(u, kind, frame) }
    }

    // ---- Sade mod: betik yok, o yüzden uzantısız oyun listelerini (ör. Close) içerikten tanı.
    // Aday istek bir kez, aynı başlık/çerezlerle ilk ~1 KB okunur; #EXTM3U ise yakalanır.
    private val probed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val SKIP_EXT = Regex("\\.(js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|ts|m4s|mp4|aac|html?|json)$")

    private fun plainProbe(s: CaptureSession, u: String, path: String, req: WebResourceRequest) {
        if (req.method != "GET" || SKIP_EXT.containsMatchIn(path) || req.isForMainFrame) return
        val acc = req.requestHeaders["Accept"].orEmpty()
        if (acc.startsWith("image/") || acc.startsWith("text/css") || acc.contains("text/html")) return
        if (probed.size > 400 || !probed.add(u)) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val b = Request.Builder().url(u)
                req.requestHeaders.forEach { (k, v) -> if (!k.equals("Range", true)) b.header(k, v) }
                CookieManager.getInstance().getCookie(u)?.let { b.header("Cookie", it) }
                siteClient.newCall(b.build()).execute().use { r ->
                    val ct = r.header("Content-Type").orEmpty().lowercase()
                    val head = r.body?.source()?.let { src -> src.request(1024); src.buffer.snapshot().utf8().take(1024) }.orEmpty()
                    if (ct.contains("mpegurl") || head.trimStart().startsWith("#EXTM3U")) {
                        val f = s.found.getOrPut(u) { Found(u, "hls", req.requestHeaders["Referer"]) }
                        if (f.frame == null) f.frame = req.requestHeaders["Referer"]
                        diag("🧪 liste bulundu: " + u.substringBefore('?').takeLast(60))
                    }
                }
            }
        }
    }

    private var watchdog: kotlinx.coroutines.Job? = null

    /** Gömülü oynatıcı sayfası mı (resim/yazı tipi/reklam pikseli değil) */
    private fun looksLikeFrame(r: WebResourceRequest): Boolean {
        val p = r.url.path.orEmpty().lowercase()
        if (Regex("\\.(js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|json|mp4|ts|m4s|vtt|srt)$").containsMatchIn(p)) return false
        val acc = r.requestHeaders["Accept"].orEmpty()
        return acc.contains("text/html") || p.contains("embed") || p.contains("/video/") || p.contains("player")
    }

    private fun noteBlocked(u: android.net.Uri, why: String) {
        val s = session ?: return
        if (s.longAt != 0L || s.blocked != null) return
        s.blocked = why; s.blockedAt = System.currentTimeMillis()
        diag("⛔ oynatıcı açılmadı: ${u.host}${u.path.orEmpty().take(40)} → $why", force = true)
    }

    // ---- tanı: video başlamıyorsa sebebi (oynatıcı hataları, pencere/izin istekleri) bilgisayarın Canlı Kayıt'ına gider
    private val diagBuf = java.util.ArrayDeque<String>()
    private var diagFlush: kotlinx.coroutines.Job? = null
    val diagLines = androidx.compose.runtime.mutableStateListOf<String>()

    fun diag(line: String, force: Boolean = false) {
        val s = session
        if (!force && s != null && s.longAt != 0L) return            // video bulunduktan sonrası gürültü
        synchronized(diagBuf) { if (diagBuf.size < 60) diagBuf.add(line) }
        scope.launch {
            if (diagLines.size >= 30) diagLines.removeAt(0)
            diagLines.add(line)
        }
        if (diagFlush?.isActive == true || !com.gflix.app.data.PcServer.configured) return
        diagFlush = scope.launch {
            delay(4000)
            val lines = synchronized(diagBuf) { diagBuf.toList().also { diagBuf.clear() } }
            if (lines.isNotEmpty()) runCatching { com.gflix.app.data.PcServer.clientLog(lines) }
        }
    }

    private fun newSession(url: String) {
        probed.clear()
        if (url.startsWith("about:")) { session = null; return }
        if (session?.pageUrl == url) return
        val s = CaptureSession(url)
        session = s
        sentTitle = null
        diagLines.clear()
        if (!running) needsUser = false
        status = if (plainMode) "🧪 Sade mod: videoyu oynatıcıya dokunarak kendin başlat; linkler yakalanınca bilgisayara gider."
            else "🔎 Video aranıyor… (oynatıcı başlatılıyor, reklamlar geçiliyor)"
        // Tek sayfada: oynatıcı başlamazsa birkaç kez dokun; yine olmazsa kullanıcıya ne yapacağını söyle (sırada kendi mantığı var)
        watchdog?.cancel()
        watchdog = scope.launch {
            for (wait in listOf(8_000L, 10_000L, 12_000L)) {
                delay(wait)
                if (session !== s || s.longAt != 0L || running) return@launch
                if (!plainMode) web?.let { AutoNav.tapPlayer(it) }
            }
            delay(10_000)
            if (session === s && s.longAt == 0L && !running) {
                needsUser = true
                status = (s.blocked?.let { "⛔ Site oynatıcıyı reddetti ($it): siyah ekranın sebebi bu. Birkaç dakika sonra \"Sayfayı yenile\" ile tekrar dene.\n" }.orEmpty()) +
                    "✋ Video başlamadı (oynatıcı siyah kalmış olabilir). Oynatıcıya kendin dokun; reklam çıkarsa kapat. " +
                    "Yine olmazsa sayfadaki diğer oynatıcı kaynağını seç ya da \"Sayfayı yenile\"ye bas. Video başlayınca yakalama kendiliğinden sürer."
            }
        }
    }

    fun reloadPage() {
        needsUser = false
        web?.reload()
        session?.let { session = null; newSession(it.pageUrl) }
    }

    fun rescan() {
        refreshSrcState()
        session?.let { session = null; newSession(it.pageUrl) }
        web?.evaluateJavascript("window.__gfxRescan && window.__gfxRescan(); window.__gfxAutoplay && window.__gfxAutoplay();", null)
    }

    fun forceSend() { session?.let { it.force = true; it.duplicate = false } }

    /** Yakalananları bilgisayara gönder: capture.json + "yakalama" tarifi; yer yetiyorsa onay sorulmaz. */
    private suspend fun send(s: CaptureSession): LinkJobRef {
        val title = s.title.ifBlank { web?.title.orEmpty() }.ifBlank { Uri.parse(s.pageUrl).host ?: "Sayfa" }
        val capture = buildJsonObject {
            put("page_url", s.pageUrl)
            put("title", title)
            put("streams", buildJsonArray {
                s.analyzed.values.filter { it.kind == "hls" }.forEach { a ->
                    add(buildJsonObject {
                        put("url", a.url); put("text", a.text); put("duration", a.duration)
                        put("headers", JsonObject(a.headers.mapValues { JsonPrimitive(it.value) }))
                    })
                }
            })
            put("subs", buildJsonArray { s.subs.forEach { (u, t) -> add(buildJsonObject { put("url", u); put("text", t) }) } })
            put("playlists", buildJsonArray { s.playlists.forEach { (u, t) -> add(buildJsonObject { put("url", u); put("text", t) }) } })
        }
        if (com.gflix.app.data.PcServer.configured) {
            // Yeni düzen (linkten anlık yayın): bilgisayar indirmez. Sayfanın adresi "kaynak" olarak, yakalanan yayın
            // (linkler, başlıklar, altyazılar) da bilgisayara gider: site bilgisayarın tarayıcısına yayın vermezse
            // bilgisayar bunu kullanır. GitHub'a hiçbir şey gitmez.
            val what = com.gflix.app.data.PcServer.sendSource(s.pageUrl, title, capture, hints[s.pageUrl])
            runCatching { com.gflix.app.data.LinkAlerts.check(app) }     // "onay bekliyor" bildirimi
            sentTitle = what
            s.sentTag = PC_SOURCE
            return LinkJobRef(PC_SOURCE, 0L, what, System.currentTimeMillis())
        }
        val repo = repo()
        val gh = repo.gh()
        val tag = PcJobs.newTag()
        val rel = gh.createDraftRelease(tag, "🌐 $title", "Hazırlanıyor…")
        gh.uploadAsset(rel, "capture.json", capture.toString().toByteArray().toRequestBody("application/json".toMediaType()))
        gh.uploadAsset(rel, "recipe.txt", "yakalama: capture.json\nonay: otomatik\n".toByteArray().toRequestBody("text/plain; charset=utf-8".toMediaType()))
        gh.updateRelease(rel.id, draft = false, body = "⏳ Sırada — bilgisayar alacak")
        val ref = LinkJobRef(tag, rel.id, title, System.currentTimeMillis())
        repo.store.update { it.copy(linkJobs = listOf(ref) + it.linkJobs) }
        sentJobs.add(0, ref)
        sentTitle = title
        s.sentTag = tag
        return ref
    }

    /** Yakalananları incele: listeleri indir, süreyi ölç; uzun yayın görününce altyazılar için 8 sn bekle ve gönder. */
    private suspend fun analyzeForever() {
        while (true) {
            delay(2000)
            val s = session ?: continue
            if (s.sent) continue
            val cm = CookieManager.getInstance()
            withContext(Dispatchers.IO) {
                s.found.values.filter { !s.analyzed.containsKey(it.url) && (it.kind != "sub" || !s.subs.containsKey(it.url)) }.take(12).forEach { f ->
                    val ref = f.frame ?: s.pageUrl
                    val h = buildMap {
                        put("Referer", ref)
                        Uri.parse(ref).let { r -> if (r.scheme != null && r.host != null) put("Origin", "${r.scheme}://${r.host}") }
                        cm.getCookie(f.url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                        if (ua.isNotBlank()) put("User-Agent", ua)
                    }
                    val text = runCatching {
                        val rb = Request.Builder().url(f.url)
                        h.forEach { (k, v) -> rb.header(k, v) }
                        siteClient.newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.string()?.take(4_000_000) else null }
                    }.getOrNull()
                    if (text == null) { s.analyzed[f.url] = Analyzed(f.url, "?", "", 0.0, h); return@forEach }
                    if (f.kind == "sub" || text.trimStart().startsWith("WEBVTT") || Regex("^\\s*\\d+\\s*\\n\\d\\d:").containsMatchIn(text.take(200))) {
                        s.subs[f.url] = text
                        s.analyzed[f.url] = Analyzed(f.url, "sub", "", 0.0, h)
                        return@forEach
                    }
                    if (!text.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")) {
                        s.analyzed[f.url] = Analyzed(f.url, "?", "", 0.0, h); return@forEach
                    }
                    fun fetchList(u: String): String? = runCatching {
                        val rb = Request.Builder().url(u)
                        h.forEach { (k, vv) -> rb.header(k, vv) }
                        siteClient.newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.string()?.take(4_000_000) else null }
                    }.getOrNull()?.takeIf { it.contains("#EXTINF") }
                    Regex("RESOLUTION=\\d+x(\\d+)").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                        ?.let { if (it > s.height) s.height = it }
                    val dur = if (text.contains("#EXT-X-STREAM-INF")) {
                        val vt = bestVariant(text, f.url)?.let { v -> fetchList(v)?.also { s.playlists[v] = it } }
                        // Sesler: bilgisayar aynı linkleri isteyince site reddedebilir, metinlerini şimdi al
                        Regex("#EXT-X-MEDIA:[^\\n]*TYPE=AUDIO[^\\n]*URI=\"([^\"]+)\"").findAll(text).take(6).forEach { m ->
                            runCatching { java.net.URI(f.url).resolve(m.groupValues[1]).toString() }.getOrNull()?.let { au ->
                                if (!s.playlists.containsKey(au)) fetchList(au)?.let { s.playlists[au] = it }
                            }
                        }
                        vt?.let(::durationOf) ?: 0.0
                    } else durationOf(text)
                    s.analyzed[f.url] = Analyzed(f.url, "hls", text, dur, h)
                    if (dur >= LONG_SEC && s.longAt == 0L) s.longAt = System.currentTimeMillis()
                    if (dur >= MID_SEC && s.midAt == 0L) s.midAt = System.currentTimeMillis()
                }
            }
            if (session !== s) continue
            // Kısa bölüm: 3–10 dk'lık yayın görüldü ve 25 sn içinde daha uzunu gelmedi → asıl video
            if (s.longAt == 0L && s.midAt != 0L && System.currentTimeMillis() - s.midAt > MID_WAIT_MS) s.longAt = System.currentTimeMillis()
            val streams = s.analyzed.values.filter { it.kind == "hls" }
            val longest = streams.maxOfOrNull { it.duration } ?: 0.0
            if (s.longAt != 0L && needsUser && !running) needsUser = false
            if (!needsUser) status = when {
                s.longAt == 0L && streams.isEmpty() && plainMode -> "🧪 Sade mod: videoyu oynatıcıya dokunarak kendin başlat; linkler yakalanınca bilgisayara gider."
                s.longAt == 0L && streams.isEmpty() -> "🔎 Video aranıyor… (oynatıcı başlatılıyor, reklamlar geçiliyor" +
                    (if (s.skipped > 0) ", ${s.skipped} reklam düğmesine basıldı" else "") + ")"
                s.longAt == 0L && s.midAt != 0L -> "⏳ ${(longest / 60).toInt()} dk'lık yayın bulundu (kısa bölümlü dizi olabilir); " +
                    "daha uzunu gelmezse ${((MID_WAIT_MS - (System.currentTimeMillis() - s.midAt)) / 1000).coerceAtLeast(0)} sn içinde gönderilecek…"
                s.longAt == 0L -> "⏳ ${streams.size} yayın bulundu ama hepsi kısa (reklam?), asıl video bekleniyor…"
                else -> "▶ Asıl video bulundu: ${(longest / 60).toInt()} dk" + (if (s.height > 0) " · ${s.height}p" else "") + " · ${s.subs.size} altyazı — gönderiliyor…"
            }
            if (s.longAt != 0L && System.currentTimeMillis() - s.longAt > 8000 && !s.sent && altMinHeight >= 0 && s.height < altMinHeight) {
                // Yedek oynatıcı (Rapidrame) Close'tan düşük kalitede: gönderme, Close sonra tekrar denenir
                if (!s.lowAlt) diag("Rapidrame ${if (s.height > 0) "${s.height}p" else "kalitesi bilinmiyor"} < Close ${altMinHeight}p: alınmadı")
                s.lowAlt = true
                continue
            }
            if (s.longAt != 0L && System.currentTimeMillis() - s.longAt > 8000 && !s.sent) {
                // Aynı sayfa son 24 saatte gönderildiyse kendiliğinden tekrar gönderme (yanlışlıkla iki kez sıraya girmesin)
                val sentAt = prefs.getLong("sent:" + s.pageUrl, 0L)
                // Bilgisayar bağlıyken her yakalama taze link: tekrar göndermek serbest
                if (System.currentTimeMillis() - sentAt < 24 * 3600_000L && !s.force && !com.gflix.app.data.PcServer.configured) {
                    status = "ℹ️ Bu sayfa zaten gönderildi (${(System.currentTimeMillis() - sentAt) / 60000} dk önce). Yeniden göndermek için \"Yine de gönder\"e bas."
                    s.duplicate = true
                    continue
                }
                s.sent = true
                runCatching { send(s) }
                    .onSuccess { prefs.edit().putLong("sent:" + s.pageUrl, System.currentTimeMillis()).apply(); status = if (s.sentTag == PC_SOURCE) "${sentTitle.orEmpty()}\nStüdyo → Onay bekleyenler'den TMDB ile eşleştirip yayına al." else "📤 Bilgisayara gönderildi: ${sentTitle.orEmpty()}\nBilgisayar filmi/bölümü tanıyıp en iyi kalitede indirecek." }
                    .onFailure { s.sent = false; s.longAt = System.currentTimeMillis(); status = "Gönderilemedi, yeniden denenecek: ${it.message}" }
            }
        }
    }

    /** Gönderilen işlerin durumu (tek istekte bütün işler; ekran kapalıyken de sıra beklerken kullanılır). */
    private suspend fun pollJobsForever() {
        while (true) {
            delay(10_000)
            val open = sentJobs.any { j -> JobsTracker.viewOf(j)?.terminal != true }
            if ((onScreen && open) || waitingJob != null) runCatching { JobsTracker.refresh(app, repo()) }
        }
    }

    // ---------------------------------------------------------------- gezinme

    fun open(raw: String) {
        val u = raw.trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (u.length < 12) return
        address = u
        prefs.edit().putString("last", u).apply()
        if (started) web?.loadUrl(u) else started = true     // tarayıcı ilk açılışta bu adresi yükler
    }

    private fun siteHome(): String? = runCatching { Uri.parse(address.ifBlank { prefs.getString("last", "") ?: "" }) }.getOrNull()
        ?.takeIf { it.host != null && it.scheme?.startsWith("http") == true }?.let { "${it.scheme ?: "https"}://${it.host}/" }

    fun hasSite(): Boolean = siteHome() != null

    private suspend fun waitLoaded(minMs: Long = 2500) {
        delay(minMs)
        repeat(20) {
            val st = web?.let { AutoNav.eval(it, "document.readyState") }.orEmpty()
            if (st.contains("complete") || st.contains("interactive")) return
            delay(500)
        }
    }

    fun runSearch() {
        val q = query.trim()
        val home = siteHome()
        if (q.length < 2) return
        if (home == null) { status = "Önce siteyi bir kez aç (adresini yapıştır); sonra adı yazıp arayabilirsin."; return }
        results = emptyList(); episodes = emptyList(); picked.clear(); pickSeason = null
        navBusy = true
        status = "🔎 \"$q\" sitede aranıyor…"
        if (!started) { address = home; started = true } else web?.loadUrl(home)
        scope.launch {
            runCatching {
                var waited = 0
                while (web == null && waited < 15_000) { delay(200); waited += 200 }
                waitLoaded(3500)
                val w = web ?: run { status = "Tarayıcı açılamadı."; return@runCatching }
                if (AutoNav.typeSearch(w, q) == "noinput") { status = "Bu sitede arama kutusu bulunamadı. Sayfayı kendin aç."; return@runCatching }
                delay(2500)
                var found = AutoNav.results(w, q)     // canlı arama: sonuçlar açılır listede
                if (found.isEmpty()) {
                    AutoNav.pressEnter(w)
                    waitLoaded(4000)
                    found = AutoNav.results(w, q)
                    if (found.isEmpty()) { delay(3000); found = AutoNav.results(w, q) }
                }
                results = found
                status = if (found.isEmpty()) "\"$q\" için sonuç bulunamadı. Adı farklı yazmayı dene ya da sayfayı kendin aç."
                         else "${found.size} sonuç: birine dokun. Diziyse sezon ve bölümler çıkar, filmse hemen yakalanır."
            }.onFailure { status = "Arama yapılamadı: ${it.message}" }
            navBusy = false
        }
    }

    fun openResult(r: SiteResult) {
        results = emptyList(); episodes = emptyList(); picked.clear(); pickSeason = null
        seriesTitle = r.title
        navBusy = true
        status = "📂 ${r.title} açılıyor…"
        web?.loadUrl(r.url)
        scope.launch {
            runCatching {
                waitLoaded(4000)
                val w = web ?: return@runCatching
                val all = LinkedHashMap<String, SiteEpisode>()
                AutoNav.episodes(w).forEach { all["${it.season}x${it.episode}"] = it }
                val tabs = AutoNav.seasonTabs(w)
                if (tabs > 1 || (tabs == 1 && all.isEmpty())) for (i in 0 until tabs) {
                    AutoNav.clickSeasonTab(w, i); delay(2200)
                    AutoNav.episodes(w).forEach { all.putIfAbsent("${it.season}x${it.episode}", it) }
                }
                episodes = all.values.sortedWith(compareBy({ it.season }, { it.episode }))
                if (episodes.isEmpty()) {
                    status = "🎬 Film sayfası: video başlatılıyor, linkler yakalanınca bilgisayara gönderilecek."
                    delay(8000)
                    if (session?.longAt == 0L && !plainMode) { web?.let { AutoNav.tapPlayer(it) } }
                } else {
                    pickSeason = episodes.first().season
                    refreshSrcState()
                    status = "📺 ${episodes.map { it.season }.distinct().size} sezon, ${episodes.size} bölüm bulundu. Sezon seç, bölümleri işaretle (ya da \"Sezonun hepsi\" / \"Bütün sezonlar\"), sonra Başlat. Başlattıktan sonra ekrandan çıkabilirsin."
                }
            }.onFailure { status = "Sayfa okunamadı: ${it.message}" }
            navBusy = false
        }
    }

    // ---------------------------------------------------------------- İstek listesi için (arka planda)

    /** Liste bu an tarayıcıyı kullanabilir mi: elle sıra/arama yok, kullanıcı Sayfadan Yakala'da gezinmiyor (film izlerken de sürer) */
    internal fun canWish(): Boolean = ::app.isInitialized && queueAt < 0 && !navBusy && (!onScreen || listOpen) && hasSite() && (appVisible || WishList.bgRun || watching)

    /** Tazeleme (biri izlemek istiyor) için: telefonda film/dizi izlenirken de olur (tarayıcı sessiz ve görünmez) */
    internal fun canRefresh(): Boolean = ::app.isInitialized && queueAt < 0 && !navBusy && (!onScreen || listOpen) && hasSite() &&
        (appVisible || WishList.bgRun || watching)

    /** Uygulama ekranda mı: ekran kapalıyken/arka plandayken tarayıcı videoyu başlatmaz (sayfa "gizli" sayılır) */
    var appVisible by mutableStateOf(true)

    private fun ensureWeb(url: String) {
        address = url
        if (web == null) { createWeb(app); started = true } else web?.loadUrl(url)
        if ((appInBackground && WishList.bgRun) || watching) showOverlay()      // uygulama arkadayken açıldıysa küçük pencereye
    }

    /** Sitenin kendi arama kutusuyla ara (kullanıcının yaptığı gibi). */
    internal suspend fun siteSearchFor(q: String): List<SiteResult> {
        val home = siteHome() ?: return emptyList()
        ensureWeb(home)
        waitLoaded(3500)
        val w = web ?: return emptyList()
        if (AutoNav.typeSearch(w, q) == "noinput") return emptyList()
        delay(2500)
        var found = AutoNav.results(w, q)
        if (found.isEmpty()) {
            AutoNav.pressEnter(w)
            waitLoaded(4000)
            found = AutoNav.results(w, q)
            if (found.isEmpty()) { delay(3000); found = AutoNav.results(w, q) }
        }
        return found
    }

    /** Dizi sayfasındaki bütün sezonların bölümleri. */
    /** İstek listesinin tarayıcısında kategori/liste sayfasını aç: yapımlar + sonraki sayfa adresi */
    internal suspend fun categoryAt(url: String, maxPages: Int = 40, say: (String) -> Unit = {}): Pair<List<SiteResult>, String> {
        val all = LinkedHashMap<String, SiteResult>()
        val seen = hashSetOf(url)
        var cur = url
        var pages = 0
        var rest = ""                                           // yarıda kalırsa sonraki sayfanın adresi
        ensureWeb(url)
        waitLoaded(4000)
        while (pages < maxPages) {
            if (pages > 0 && preempt) { rest = cur; break }      // izleyen biri yeni link bekliyor: önce o; kalanı sonra
            pages++
            val w = web ?: break
            // aşağı kaydırdıkça yüklenen sayfa: yeni yapım gelmeyene kadar kaydır (en çok 15 kez)
            var last = -1
            for (i in 0 until 15) {
                val n = AutoNav.pageItems(w).size
                if (n == last) break
                last = n
                AutoNav.scrollMore(w); delay(1800)
            }
            val here = AutoNav.pageItems(w)
            here.forEach { all.putIfAbsent(it.url, it) }
            say("📂 kategori okunuyor: $pages. sayfa · ${all.size} yapım")
            // 1) sonraki sayfa ayrı bir adresse oraya git
            val next = AutoNav.nextPage(w)
            if (next.isNotBlank() && next !in seen) {
                seen += next; cur = next
                delay(2000)                                     // sayfalar arası nazik ara
                ensureWeb(next); waitLoaded(4000)
                continue
            }
            // 2) sayfa içinde yenilenen sayfalama: "Sonraki"ye bas, yeni yapımlar gelene kadar bekle
            val before = here.map { it.url }.toSet()
            if (!AutoNav.clickNext(w)) break
            var changed = false
            for (i in 0 until 10) {
                delay(1000)
                val now = AutoNav.pageItems(w).map { it.url }.toSet()
                if (now.isNotEmpty() && now != before) { changed = true; break }
            }
            if (!changed) break                                 // son sayfa (basınca bir şey değişmedi)
            delay(1500)
        }
        if (pages >= maxPages) rest = AutoNav.nextPage(web ?: return all.values.toList() to "").takeIf { it !in seen }.orEmpty()
        web?.loadUrl("about:blank")
        // sınıra takıldıysa / yarıda bırakıldıysa kalan sayfalar "Kalan sayfaları oku" ile sürdürülür
        return all.values.toList() to rest
    }

    internal suspend fun siteEpisodesAt(url: String): List<SiteEpisode> {
        ensureWeb(url)
        waitLoaded(4000)
        val w = web ?: return emptyList()
        val all = LinkedHashMap<String, SiteEpisode>()
        AutoNav.episodes(w).forEach { all["${it.season}x${it.episode}"] = it }
        val tabs = AutoNav.seasonTabs(w)
        if (tabs > 1 || (tabs == 1 && all.isEmpty())) for (i in 0 until tabs) {
            AutoNav.clickSeasonTab(w, i); delay(2200)
            AutoNav.episodes(w).forEach { all.putIfAbsent("${it.season}x${it.episode}", it) }
        }
        return all.values.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * Bir sayfayı aç, yakalanıp bilgisayara gidene kadar bekle. "ok" | "black" (video gelmedi: siyah ekran/kısıtlama).
     * Siyah kalırsa sayfa bırakılır; liste sonra tekrar dener.
     */
    /** Yedek oynatıcı denenirken kabul edilecek en düşük kalite (Close'un kalitesi); -1 = yedek denenmiyor */
    @Volatile var altMinHeight = -1
        private set
    /** Son captureAt sonucu: hangi oynatıcı, kaç p */
    var lastPlayer = ""
        private set
    var lastHeight = 0
        private set
    /** Son denemede sitenin oynatıcıyı reddetme sebebi (yoksa boş) */
    var lastBlock = ""

    /**
     * Bir sayfayı aç, yakalanıp bilgisayara gidene kadar bekle. "ok" | "black" (video gelmedi: siyah ekran/kısıtlama).
     * Sadece Close sekmesi denenir (Rapidrame'e geçilmez). Olmazsa sayfa bırakılır; liste sonra dener.
     */
    internal suspend fun captureAt(url: String, hint: JsonObject?, closeHeight: Int, stay: Int = staySec, say: (String) -> Unit): String {
        say("sayfa açılıyor")
        hint?.let { hints[url] = it }
        lastBlock = ""
        var result = "black"
        var restarts = 0
        try {
            while (true) {
                altMinHeight = -1
                val g = clearGen
                ensureWeb(url)
                waitLoaded(3000)
                try {
                    web?.let { w -> if (AutoNav.clickTab(w, "Close")) diag("liste: Close sekmesine basıldı") }
                    // Sadece Close (kullanıcı kararı): Rapidrame hiç denenmez
                    if (waitCapture(say, "Close", g)) { result = "ok"; lastPlayer = "Close" }
                    break
                } catch (_: Preempted) {
                    preempt = false
                    diag("liste: izleyen biri için tazeleme öne alındı → ${url.substringAfter("://").take(50)} sonra baştan", force = true)
                    result = "preempted"
                    break
                } catch (_: RestartCapture) {
                    // Kullanıcı 🧹 Temizle'ye bastı: bu deneme sayılmaz, temiz tarayıcıyla aynı sayfa baştan açılır
                    if (++restarts > 5) break
                    say("🧹 tarayıcı temizlendi, sayfa yeniden açılıyor")
                    diag("liste: kullanıcı temizledi → ${url.substringAfter("://").take(50)} baştan", force = true)
                    lastBlock = ""
                    delay(1500)
                }
            }
            lastHeight = session?.height ?: 0
            diag("liste: ${url.substringAfter("://").take(60)} → ${if (result == "ok") "alındı ($lastPlayer ${lastHeight}p)" else "video yok"}" +
                " · görülen yayın ${session?.found?.size ?: 0}, incelenen ${session?.analyzed?.size ?: 0}" + if (plainMode) " · sade mod" else "", force = true)
            if (result == "ok") stayOnVideo(say, stay)
            if (result == "preempted") return result
        } finally {
            altMinHeight = -1
            web?.loadUrl("about:blank")       // yakalandı ya da bırakıldı: video siteden boşuna akmasın
        }
        return result
    }

    /** Liste bir sayfadayken kullanıcı tarayıcıyı temizledi: o sayfa baştan açılır (deneme sayılmaz) */
    private class RestartCapture : RuntimeException()
    private class Preempted : RuntimeException()

    /** İstek listesi: izleyen birinin tazelemesi bekliyor → o anki (tazeleme olmayan) iş bırakılsın */
    @Volatile var preempt = false

    /** Her temizlemede artar (liste, yarıdaki sayfayı baştan açmak için izler) */
    @Volatile private var clearGen = 0

    /** Yakalandıktan sonra videoda 1 dk kal (hemen kaçmak siteye şüpheli görünmesin), sonra sıradakine geç. */
    private suspend fun stayOnVideo(say: (String) -> Unit, staySec: Int = this.staySec) {
        val gen0 = clearGen
        for (left in staySec downTo 1) {
            if (clearGen != gen0) return            // temizlendi: sayfa zaten kapandı, sıradakine geç
            if (left % 10 == 0 || left == staySec) say("✅ alındı · videoda kalınıyor ($left sn)")
            delay(1000)
        }
    }

    private suspend fun waitCapture(say: (String) -> Unit, player: String, gen0: Int = clearGen): Boolean {
        var t0 = System.currentTimeMillis()
        var taps = 0
        var firstTap = 0L
        while (true) {
            delay(1000)
            // Ekran kapandı/uygulama arkaya gitti: video başlamaz; süre saymadan ekranın açılmasını bekle
            if (!appVisible && !WishList.bgRun) { t0 += 1000; say("$player: ekran kapalı, açılınca devam"); continue }
            if (clearGen != gen0) throw RestartCapture()
            // Biri bir şey izlemek istiyor (linki eski): sıradaki işi bırak, önce onu tazele; bu iş sonra baştan (deneme sayılmaz)
            if (preempt) throw Preempted()
            val s = session
            val el = (System.currentTimeMillis() - t0) / 1000
            if (s != null && s.sent && s.sentTag != null) return true
            if (s != null && s.lowAlt) return false
            // Oynatıcı görünür görünmez dokun, 2 sn sonra bir daha (ilki çoğu zaman reklam/kaplamayı geçer), sonra 10 sn'de bir
            val due = when (taps) { 0 -> el >= 1; 1 -> el >= firstTap + 2; else -> el >= firstTap + 2 + 10L * (taps - 1) }
            if (s != null && s.longAt == 0L && taps < 16 && due) {
                // Liste arka planda: kimse oynatıcıya dokunamaz. Sade modda da dokun (sayfaya betik eklemeden, ekrana dokunur gibi)
                val hit = web?.let { AutoNav.tapPlayer(it) } == true
                if (hit || taps > 0) {
                    if (taps == 0) firstTap = el
                    taps++
                    diag("liste: $player oynatıcıya dokunuldu ($taps) · görülen istek ${s.found.size}" + (s.blocked?.let { " · ⛔ $it" } ?: ""))
                }
            }
            say("$player: video bekleniyor (${el} sn)" + (if ((s?.analyzed?.size ?: 0) > 0) " · ${s!!.analyzed.size} yayın görüldü" else ""))
            if (s != null && s.longAt == 0L && s.analyzed.isEmpty() && el > 3 * 60) return false
            // Site oynatıcıyı reddetti (siyah ekranın sebebi): 40 sn içinde yayın gelmezse boşuna bekleme, sonra tekrar denenir
            if (s != null && s.blocked != null && s.longAt == 0L && s.analyzed.isEmpty() && System.currentTimeMillis() - s.blockedAt > 40_000) {
                lastBlock = s.blocked.orEmpty()
                return false
            }
            if (el > 10 * 60) return false
        }
    }

    /** 🧹 Elle: tarayıcının bütün verileri (çerezler, site depolaması, önbellek, geçmiş, form) silinir. Sadece kullanıcı basınca. */
    /** Kullanıcı sıra/liste bir sayfadayken bastı: o sayfa bitince temizlenir */
    var clearRequested by mutableStateOf(false)
    private var clearDone: ((String) -> Unit)? = null

    /** Sayfa arası güvenli an (sıra/liste bir sayfayı bitirdi): bekleyen temizleme isteği varsa yap */
    internal fun clearIfRequested() {
        if (!clearRequested) return
        clearRequested = false
        val d = clearDone; clearDone = null
        clearNow { d?.invoke(it); WishList.cleared() }
    }

    /** Kullanıcı bastığı an temizler (sıra/liste o an bir sayfadaysa o sayfa yarıda kalır, sonra tekrar denenir). */
    fun clearBrowserData(done: (String) -> Unit) {
        clearRequested = false; clearDone = null
        clearNow(done)
    }

    private fun clearNow(done: (String) -> Unit) {
        clearGen++
        web?.apply { stopLoading(); loadUrl("about:blank"); clearCache(true); clearHistory(); clearFormData() }
        android.webkit.WebStorage.getInstance().deleteAllData()
        runCatching { android.webkit.WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword() }
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done("🧹 Tarayıcı verileri ve çerezler silindi.") }
        session = null
    }

    fun tapAgain() { web?.let { w -> scope.launch { AutoNav.tapPlayer(w) } } }

    /** Sırayı durdur (ekrandaki Durdur ya da "Tüm işleri iptal et"). */
    fun stopQueue(msg: String = "Sıra durduruldu.") {
        if (!running && queueJob == null) return
        queueJob?.cancel(); queueJob = null
        queueAt = -1; needsUser = false; waitingJob = null
        status = msg
        if (::app.isInitialized) CaptureService.stop(app)
    }

    private fun label(ep: SiteEpisode) = seriesTitle.ifBlank { "Bölüm" } + " S%02dE%02d".format(ep.season, ep.episode)

    private fun notify(text: String) { if (::app.isInitialized) CaptureService.update(app, text) }

    /**
     * Sıradaki işin siteyle işi bitti mi: indirme bitti (bilgisayar şimdi GitHub'a yüklüyor) ya da iş bitti/düştü.
     * Bilgisayar 15 sn'de bir durum yazar; telefon 10 sn'de bir okur (tek istek).
     */
    private fun siteFree(tag: String): Pair<Boolean, String> {
        val v = JobsTracker.views[tag] ?: return false to "bilgisayarın alması bekleniyor"
        if (v.terminal) return true to (v.status?.stage ?: "bitti")
        val st = v.status ?: return false to (if (v.waiting) "sırada" else v.head.ifBlank { "başlıyor" })
        if ((st.timing?.dlEnd ?: 0L) > 0L) return true to "indirme bitti, yükleniyor"
        val items = st.items
        if (items.isNotEmpty() && items.all { it.state in setOf("processing", "done", "error", "skipped") }) return true to "indirme bitti, yükleniyor"
        return false to st.stage.ifBlank { "çalışıyor" }
    }

    fun startQueue(list: List<SiteEpisode>) {
        if (list.isEmpty()) return
        queue = list; queueDone.clear(); queueAt = 0; needsUser = false; waitingJob = null
        queueJob?.cancel()
        queueJob = scope.launch {
            for ((i, ep) in list.withIndex()) {
                // Önceki bölüm bilgisayarda siteden inerken sıradakini açma (aynı anda tek iş)
                val prevTag = waitingJob
                if (prevTag != null && prevTag != PC_SOURCE) {     // kaynak kaydında indirme yok: beklemeden sıradaki
                    skipWait = false
                    while (true) {
                        runCatching { JobsTracker.refresh(app, repo()) }
                        val (free, what) = siteFree(prevTag)
                        if (free || skipWait) break
                        status = "⏳ ${i}/${list.size} gönderildi. Bilgisayar o bölümü indiriyor ($what); indirmesi bitince ${label(ep)} açılacak (aynı anda tek iş)."
                        notify("${i}/${list.size} gönderildi · bilgisayar indiriyor ($what) · sıradaki: ${label(ep)}")
                        delay(10_000)
                    }
                    waitingJob = null
                }
                queueAt = i; needsUser = false; skipNow = false
                status = "▶ ${i + 1}/${list.size}: ${ep.season}. sezon ${ep.episode}. bölüm açılıyor…"
                notify("▶ ${i + 1}/${list.size}: ${label(ep)} yakalanıyor")
                web?.loadUrl(ep.url)
                waitLoaded(3000)
                val t0 = System.currentTimeMillis()
                var taps = 0
                var result = "❌"
                while (true) {
                    delay(1000)
                    val s = session
                    val el = (System.currentTimeMillis() - t0) / 1000
                    if (skipNow) { result = "⏭"; break }
                    if (s != null && s.sent && s.sentTag != null) { result = "✅"; waitingJob = s.sentTag; break }
                    if (s != null && s.duplicate) { result = "⏭"; status = "ℹ️ ${ep.season}x${ep.episode} zaten gönderilmişti, atlandı"; break }
                    if (s != null && s.longAt == 0L && el >= 8 && taps < 4 && el >= 8L * (taps + 1)) {
                        taps++
                        if (!plainMode) web?.let { AutoNav.tapPlayer(it) }
                        status = "▶ ${i + 1}/${list.size}: ${ep.season}x${ep.episode} — oynatıcı başlatılıyor (dokunuş $taps)…"
                    }
                    if (s != null && s.longAt == 0L && el >= 60 && !needsUser) {
                        needsUser = true
                        status = "✋ ${ep.season}x${ep.episode}: video kendiliğinden başlamadı. Oynatıcıya dokun (reklam çıkarsa kapat); yakalanınca sıra devam eder."
                        CaptureService.alert(app, "${label(ep)}: video kendiliğinden başlamadı. Uygulamayı açıp oynatıcıya dokun; yakalanınca sıra kendiliğinden devam eder.")
                    }
                    // Site oynatıcıyı reddettiyse (siyah ekranın sebebi) 40 sn'de bırak
                    if (s != null && s.blocked != null && s.longAt == 0L && s.analyzed.isEmpty() && System.currentTimeMillis() - s.blockedAt > 40_000) { result = "❌"; break }
                    // Hiç video gelmiyorsa (oynatıcı o bölümde açılmıyor; Chrome'da da) 3 dk'da atla; kısa yayınlar varsa 15 dk bekle
                    if (s != null && s.longAt == 0L && s.analyzed.isEmpty() && el > 3 * 60) { result = "❌"; break }
                    if (el > 15 * 60) { result = "❌"; break }
                }
                if (needsUser) CaptureService.clearAlert(app)
                if (result == "✅") for (left in staySec downTo 1) {
                    if (skipNow) break
                    if (left % 10 == 0 || left == staySec) {
                        status = "✅ ${i + 1}/${list.size}: ${ep.season}x${ep.episode} alındı · videoda kalınıyor ($left sn), sonra sıradaki"
                        notify("✅ ${label(ep)} alındı · ${left} sn sonra sıradaki")
                    }
                    delay(1000)
                }
                queueDone[ep.url] = result
                clearIfRequested()
                needsUser = false
                // Gönderildi: video siteden boşuna akmasın (bilgisayar indirirken siteye ikinci yük olmasın)
                web?.loadUrl("about:blank")
                delay(1500)
            }
            queueAt = -1; waitingJob = null
            clearIfRequested()
            refreshSrcState()
            val failed = list.filter { queueDone[it.url] == "❌" }.map { "${it.season}x${it.episode}" }
            status = "Sıra bitti: ${queueDone.values.count { it == "✅" }} bölüm gönderildi" +
                queueDone.values.count { it != "✅" }.takeIf { it > 0 }?.let { ", $it atlandı" }.orEmpty() + "." +
                if (failed.isNotEmpty()) "\n❌ Video açılmayan bölümler: ${failed.joinToString(", ")} (oynatıcı o bölümde çalışmıyor olabilir; sonra tekrar dene)" else ""
            CaptureService.stop(app)
            queueJob = null
        }
    }

    // ---------------------------------------------------------------- tarayıcı

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb(ctx: Context): WebView {
        // Uygulama bağlamıyla: tarayıcı bir ekran sayfasına (Activity) bağlanırsa o sayfa arkaya gidince Chromium sayfayı
        // "gizli" sayıp oynatıcıyı durduruyor (uygulamaya girince hemen bulmasının sebebi). Böylece görünürlüğü sadece
        // nerede gösterildiğine (ekran, küçük pencere, kilit ekranı sayfası) bağlı.
        val wrapper = MutableContextWrapper(android.view.ContextThemeWrapper(ctx.applicationContext, android.R.style.Theme_DeviceDefault))
        webCtx = wrapper
        return WebView(wrapper).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportMultipleWindows(true)          // açılır reklam pencerelerini engellemek için
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Yakalama tarayıcısı hiç ses çıkarmaz (arka planda açılan film/dizinin sesi gelmesin); uygulamanın kendi oynatıcısı etkilenmez
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) runCatching { WebViewCompat.setAudioMuted(this, true) }
            val defaultUa = settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            val rules = setOf("*")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "GFXBridge", rules) { _, msg, _, _, _ -> msg.data?.let { onReport(it) } }
            }
            addJavascriptInterface(object { @JavascriptInterface fun report(json: String) = onReport(json) }, "GFX")
            val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (!plainMode && hookJs.isNotEmpty() && docStart) {
                hookHandle = runCatching { WebViewCompat.addDocumentStartJavaScript(this, hookJs, rules) }.getOrNull()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    return !(u.startsWith("http://") || u.startsWith("https://"))   // intent://, market:// reklam yönlendirmeleri
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    val p = u.substringBefore('?').lowercase()
                    val s = session
                    if (s != null && (p.endsWith(".m3u8") || p.endsWith(".vtt") || p.endsWith(".srt"))) {
                        val f = s.found.getOrPut(u) { Found(u, if (p.endsWith(".m3u8")) "hls" else "sub", request.requestHeaders["Referer"]) }
                        if (f.frame == null) f.frame = request.requestHeaders["Referer"]
                    } else if (s != null && plainMode) plainProbe(s, u, p, request)
                    return null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!url.startsWith("about:")) address = url
                    newSession(url)
                    if (!docStart && hookJs.isNotEmpty() && !plainMode) view.evaluateJavascript(hookJs, null)
                }
                // Site sayfayı yeniden yüklemeden adres değiştirirse (pushState: film kartına tıklayınca) onPageStarted gelmez;
                // eski sayfanın adresi ve yakaladıkları yeni filme karışmasın diye yeni oturum başlat
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val cur = session?.pageUrl
                    if (!isReload && cur != null && url.substringBefore('#') != cur.substringBefore('#')) {
                        address = url
                        newSession(url)
                        session?.title = view.title.orEmpty().takeIf { !it.startsWith("http") }.orEmpty()
                        view.evaluateJavascript("window.__gfxRescan && window.__gfxRescan(); window.__gfxAutoplay && window.__gfxAutoplay();", null)
                    }
                }
                // ---- siyah ekranın sebebi: oynatıcı çerçevesi açılmadı mı, site hangi kodla reddetti
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame || !looksLikeFrame(request)) return
                    val why = error.description?.toString().orEmpty()
                    noteBlocked(request.url, why.ifBlank { "hata ${error.errorCode}" })
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                    val code = resp.statusCode
                    if (code < 400) return
                    val p = request.url.path.orEmpty().lowercase()
                    if (p.endsWith(".m3u8") || p.endsWith(".ts") || looksLikeFrame(request))
                        noteBlocked(request.url, "HTTP $code" + (resp.responseHeaders?.get("Retry-After")?.let { " (Retry-After $it)" } ?: ""))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    session?.let { if (it.title.isBlank()) it.title = view.title.orEmpty() }
                    view.evaluateJavascript("window.__gfxRescan && window.__gfxRescan(); window.__gfxAutoplay && window.__gfxAutoplay();", null)
                }
            }
            post { ua = defaultUa }
            webChromeClient = object : WebChromeClient() {
                // Uygulama bağlamında sayfa açılır pencereleri (alert/confirm) gösterilemez: sessizce geç
                override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
                override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
                override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean { result.cancel(); return true }
                override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean { result.confirm(); return true }
                override fun onReceivedTitle(view: WebView, title: String?) {
                    session?.let { if (!title.isNullOrBlank() && !title.startsWith("http")) it.title = title }
                }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    diag("⛔ açılır pencere engellendi")
                    return false
                }
                // Tanı: oynatıcının hataları Canlı Kayıt'a (video başlamazsa sebebi görülsün)
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        val src = runCatching { android.net.Uri.parse(m.sourceId()).host }.getOrNull().orEmpty()
                        diag("⚠ $src: ${m.message().take(160)}")
                    }
                    return false
                }
            }
            web = this
            loadUrl(address)
        }
    }
}

/**
 * Sıra sürerken kullanıcı Sayfadan Yakala ekranından çıktıysa tarayıcı uygulamanın EN ARKASINDA (ekranlar onu örter)
 * tam boy durur: sayfa çalışmaya, oynatıcıya dokunuşlar gitmeye devam eder. MainActivity'de NavHost'un altına konur.
 */
@Composable
fun CaptureBackgroundHost() {
    val e = CaptureEngine
    val ctx = LocalContext.current
    // İstek listesi uygulama açılınca kendiliğinden çalışsın (Sayfadan Yakala'ya girmeyi beklemesin)
    LaunchedEffect(Unit) { if (com.gflix.app.data.PcServer.configured) e.init(ctx) }
    // Uygulama görünür mü (ekran kapalıyken liste boşuna deneyip hak harcamasın)
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> { e.appVisible = true; e.hideOverlay() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    e.appVisible = false
                    // küçük pencereye geçiş uygulamanın TÜM sayfaları kapanınca (film izlerken çıkmasın): init'teki takipte
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // 🌙 Gece modu: liste bekleyen iş varken ekran kapanmasın (telefon şarjda, uygulama açık bırakılır)
    val view = androidx.compose.ui.platform.LocalView.current
    // TV kutusu (yakalama cihazı): ekran hiç kapanmaz, ekran koruyucu da çıkmaz (menüdeki en fazla 2 saat sınırı aşılır)
    val keep = WishList.tvWorker || (WishList.night && WishList.hasOpen())
    androidx.compose.runtime.DisposableEffect(keep) {
        view.keepScreenOn = keep
        onDispose { view.keepScreenOn = false }
    }
    if (e.onScreen || e.previewing || e.overlayOn || e.runActivityOn || !(e.running || e.navBusy) || e.web == null) return
    androidx.compose.runtime.key(e.hostGen) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> e.attach(ctx) })
    }
}

@Composable
fun AdminCaptureScreen(onBack: () -> Unit, onJobs: () -> Unit, presetQuery: String? = null, presetItem: String? = null) {
    val context = LocalContext.current
    val e = CaptureEngine
    remember { e.init(context); 0 }
    val scope = rememberCoroutineScope()
    val raw by context.appContainer.catalogRepository.catalog.collectAsState()
    var openWhen by remember { mutableStateOf<com.gflix.app.data.Title?>(null) }
    var wishOpen by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    if (askClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askClear = false },
        containerColor = GColors.Surface,
        title = { Text("Tarayıcı verilerini temizle", color = Color.White) },
        text = { Text("Sayfadan Yakala tarayıcısının çerezleri, site verileri, önbelleği ve geçmişi silinir. Sitelerdeki oturumlar kapanır.", color = GColors.TextSecondary) },
        confirmButton = { TextButton(onClick = { askClear = false; e.clearBrowserData { e.status = it; if (it.startsWith("🧹")) WishList.cleared() } }) { Text("Sil", color = Color.White, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Vazgeç", color = GColors.TextSecondary) } },
    )
    if (wishOpen) WishListDialog(onDismiss = { wishOpen = false })

    // Yakında → Medya ekle → Sayfadan yakala: adı yaz, site biliniyorsa hemen ara
    LaunchedEffect(presetQuery, presetItem) {
        if (presetItem != null) e.presetItem = presetItem
        if (!presetQuery.isNullOrBlank() && !e.running) {
            e.query = presetQuery
            if (e.hasSite()) e.runSearch() else e.status = "\"$presetQuery\" için: önce sitenin adresini bir kez aç, sonra Ara'ya bas."
        }
    }
    DisposableEffect(Unit) {
        e.showOnScreen(true)
        onDispose { e.showOnScreen(false); e.releaseIfIdle() }
    }
    BackHandler { if (e.web?.canGoBack() == true && !e.running) e.web?.goBack() else onBack() }
    openWhen?.let { t -> OpenWhenDialog(t, onDismiss = { openWhen = null }) }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AdminTopBar("Sayfadan Yakala", onBack)
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                e.address, { e.address = it }, singleLine = true, colors = gTextFieldColors(),
                placeholder = { Text("Film ya da bölüm sayfasının adresi") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); e.open(e.address) }),
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !e.running, onClick = {
                val clip = clipboard.getText()?.text?.trim().orEmpty()
                focus.clearFocus()
                if (clip.startsWith("http")) e.open(clip) else e.open(e.address)
            }) { Text(if (e.address.isBlank()) "Yapıştır" else "Aç", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        // ---- Ada göre ara (sitenin kendi arama kutusu)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                e.query, { e.query = it }, singleLine = true, colors = gTextFieldColors(),
                placeholder = { Text("Dizi ya da film adı (sitede arar)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); e.runSearch() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !e.navBusy && !e.running, onClick = { focus.clearFocus(); e.runSearch() }) { Text("Ara", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        if (e.results.isNotEmpty() || e.episodes.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).background(Color(0xFF141414))
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (e.episodes.isNotEmpty()) "📺 ${e.seriesTitle}" else "Sonuçlar", color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { e.results = emptyList(); e.episodes = emptyList() }) { Text("Kapat", color = GColors.TextSecondary) }
                }
                e.results.forEach { r ->
                    Text((if (r.kind == "series") "📺 " else if (r.kind == "movie") "🎬 " else "▫️ ") + r.title,
                        color = Color.White, fontSize = 14.sp, maxLines = 2,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !e.navBusy) { e.openResult(r) }.padding(vertical = 8.dp))
                }
                if (e.episodes.isNotEmpty()) {
                    val seasons = e.episodes.map { it.season }.distinct().sorted()
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 6.dp)) {
                        seasons.forEach { sn ->
                            val sel = e.pickSeason == sn
                            val n = e.episodes.count { it.season == sn && it.url in e.picked }
                            Text("$sn. Sezon" + (if (n > 0) " ($n)" else ""), color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color(0xFF333333))
                                    .clickable { e.pickSeason = sn }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    LaunchedEffect(e.episodes) { while (true) { e.refreshSrcState(); delay(30_000) } }
                    val eps = e.episodes.filter { it.season == e.pickSeason }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        // Toplu seçim sadece eksikleri alır (yayında ✅ olanlar tekrar yakalanmasın; istenirse tek tek işaretlenir)
                        val missing = eps.filter { e.srcState[it.url] != "live" }.map { it.url }
                        val missingAll = e.episodes.filter { e.srcState[it.url] != "live" }.map { it.url }
                        TextButton(enabled = !e.running, onClick = {
                            if (missing.isNotEmpty() && e.picked.containsAll(missing)) e.picked.removeAll(eps.map { it.url }.toSet())
                            else missing.forEach { if (it !in e.picked) e.picked.add(it) }
                        }) { Text(if (missing.isNotEmpty() && e.picked.containsAll(missing)) "Sezonu bırak" else "Sezonun eksikleri (${missing.size})", color = Color.White) }
                        if (seasons.size > 1) TextButton(enabled = !e.running, onClick = {
                            if (missingAll.isNotEmpty() && e.picked.containsAll(missingAll)) e.picked.clear()
                            else missingAll.forEach { if (it !in e.picked) e.picked.add(it) }
                        }) { Text(if (missingAll.isNotEmpty() && e.picked.containsAll(missingAll)) "Hiçbiri" else "Bütün eksikler (${missingAll.size})", color = Color.White) }
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = e.picked.isNotEmpty() && !e.running, onClick = {
                            e.startQueue(e.episodes.filter { it.url in e.picked })
                        }) { Text("▶ Başlat (${e.picked.size})", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                    // Yakında'daki dizi: kaç bölüm yüklenince açılsın
                    val soonTitle = remember(raw, e.seriesTitle, e.presetItem) {
                        val soon = com.gflix.app.data.Library.comingSoon(raw, null)
                        val key = JobGroups.normalize(e.seriesTitle)
                        soon.firstOrNull { it.id == e.presetItem && it.isSeries }
                            ?: soon.firstOrNull { it.isSeries && key.isNotBlank() && (JobGroups.normalize(it.title).let { n -> n.isNotBlank() && (key.startsWith(n) || n.startsWith(key)) }) }
                    }
                    soonTitle?.let { t ->
                        Text("📦 Yakında'da: kaç bölüm yüklenince açılsın? ›", color = GColors.Match, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable { openWhen = com.gflix.app.data.Presentation.localize(t, null) }.padding(vertical = 6.dp))
                    }
                    eps.forEach { ep ->
                        val on = ep.url in e.picked
                        val mark = e.queueDone[ep.url] ?: if (e.running && e.queue.getOrNull(e.queueAt)?.url == ep.url) "⏳" else
                            when (e.srcState[ep.url]) { "live" -> "✅ yayında"; "pending" -> "📥 onayda"; else -> "" }
                        Text("${if (on) "☑" else "☐"}  ${ep.episode}. Bölüm" + (ep.title.takeIf { it.isNotBlank() && !it.contains("Bölüm", true) }?.let { " · $it" } ?: "") + " $mark",
                            color = Color.White, fontSize = 13.sp, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !e.running) { if (on) e.picked.remove(ep.url) else e.picked.add(ep.url) }.padding(vertical = 6.dp))
                    }
                }
            }
        }
        if (!e.started) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(20.dp)) {
                Text("1. Filmin ya da bölümün sayfa adresini kopyala.\n2. \"Yapıştır\"a bas (ya da adresi yazıp klavyeden Git).\n3. Gerisi otomatik: video başlatılır, reklamlar geçilir, linkler yakalanıp bilgisayara gönderilir.\n\nDizi için: adı yazıp Ara → sezon/bölüm seç → Başlat. Sıra arka planda sürer; ekrandan çıkabilirsin, ilerleme bildirimde görünür.",
                    color = GColors.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
                if (e.address.startsWith("http")) TextButton(onClick = { e.open(e.address) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Son açılan sayfayı aç ›", color = Color.White)
                }
            }
        } else androidx.compose.runtime.key(e.hostGen) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp).clipToBounds(),
                factory = { ctx -> e.attach(ctx) },
            )
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF141414)).navigationBarsPadding().padding(12.dp)) {
            Text(e.status, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
            e.sentJobs.take(3).forEach { j ->
                val v = JobsTracker.viewOf(j)
                val st = v?.status
                val icon = when {
                    v?.gone == true -> "❔"
                    st == null -> "⏳"
                    else -> when (st.state) { "running" -> "⚙️"; "done" -> "✅"; "partial" -> "⚠️"; "cancelled" -> "🛑"; else -> "❌" }
                }
                val pct = st?.items?.takeIf { it.isNotEmpty() }?.let { its -> (its.sumOf { it.progress } / its.size * 100).toInt() }
                val ahead = JobsTracker.ahead(j.tag)
                val waiting = if (ahead > 0) "⏳ Sırada — önünde $ahead iş var" else "Sırada, bilgisayar alacak"
                Text("$icon ${(st?.title?.ifBlank { null } ?: j.title).take(50)} — " + listOfNotNull(st?.stage ?: waiting, pct?.takeIf { st?.state == "running" }?.let { "%$it" }).joinToString(" · "),
                    color = if (st?.state == "error") Color(0xFFFF6B6B) else GColors.Match, fontSize = 12.sp, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                st?.log?.lastOrNull()?.let { Text(it.trim(), color = GColors.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
            if (e.running) Row(Modifier.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Sıra: ${e.queueAt + 1}/${e.queue.size}", color = GColors.Match, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (e.needsUser) TextButton(onClick = { e.tapAgain() }) { Text("Yeniden dokun", color = Color.White) }
                if (e.waitingJob != null) TextButton(onClick = { e.skipWait = true }) { Text("Beklemeden geç", color = Color.White) }
                else TextButton(onClick = { e.skipNow = true }) { Text("Bu bölümü atla", color = Color.White) }
                TextButton(onClick = { e.stopQueue() }) { Text("Durdur", color = Color(0xFFFF6B6B)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("⏱ Yakalandıktan sonra videoda kal (sn):", color = GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                var stayText by remember { mutableStateOf(e.staySec.toString()) }
                OutlinedTextField(
                    stayText, { v -> stayText = v.filter { it.isDigit() }.take(3); stayText.toIntOrNull()?.let { e.setStay(it) } },
                    singleLine = true, colors = gTextFieldColors(), modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
            }
            if (!e.running) Row(Modifier.fillMaxWidth().clickable { e.setPlain(!e.plainMode) }.padding(top = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (e.plainMode) "🧪 Sade mod AÇIK: sayfaya müdahale yok, videoyu kendin başlat (linkler yine yakalanır)"
                    else "🧪 Sade mod: oynatıcı siyah kalıyorsa dene (sayfaya hiçbir şey eklemez, Chrome gibi açar)",
                    color = if (e.plainMode) GColors.Match else GColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = e.plainMode, onCheckedChange = { e.setPlain(it) })
            }
            if (e.needsUser && e.diagLines.isNotEmpty()) Column(Modifier.padding(top = 4.dp)) {
                Text("🔧 Tanı (Canlı Kayıt'ta da var):", color = GColors.TextMuted, fontSize = 11.sp)
                e.diagLines.takeLast(4).forEach { Text(it, color = GColors.TextMuted, fontSize = 11.sp, maxLines = 2) }
            }
            if (!e.running && e.needsUser) Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { e.tapAgain() }) { Text("👆 Oynatıcıya dokun", color = Color.White, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { e.reloadPage() }) { Text("↻ Sayfayı yenile", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            if (WishList.black.isNotBlank()) BlackBanner()
            Row(Modifier.padding(top = 6.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                TextButton(onClick = onJobs) { Text("İşler ve ilerleme ›", color = Color.White, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { wishOpen = true }) { Text("🎯 İstek listesi", color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(4.dp))
                if (e.session?.duplicate == true) TextButton(onClick = { e.forceSend() }) {
                    Text("Yine de gönder", color = Color.White, fontWeight = FontWeight.Bold)
                }
                if (!e.running) TextButton(onClick = { e.rescan() }) { Text("Yeniden tara", color = GColors.TextSecondary) }
                TextButton(enabled = !e.clearRequested, onClick = { askClear = true }) {
                    Text(if (e.clearRequested) "⏳ Temizlenecek" else "🧹 Temizle", color = GColors.TextSecondary)
                }
            }
        }
    }
}


/** Sayfadan Yakala'nın bilgisayara "kaynak" kaydı (iş değil): sıra bunu beklemez. */
private const val PC_SOURCE = "pc-kaynak"
