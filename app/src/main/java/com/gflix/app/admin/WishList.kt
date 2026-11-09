package com.gflix.app.admin

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.gflix.app.appContainer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gflix.app.data.AppJson
import com.gflix.app.data.PcServer
import com.gflix.app.ui.screens.gTextFieldColors
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bir bölüm (filmde tek parça: sezon 0). state: wait | work | done | retry */
@Serializable
data class WishEp(
    val season: Int, val episode: Int, var url: String = "",
    var state: String = "wait", var tries: Int = 0, var nextAt: Long = 0, var note: String = "",
    /** Bilgisayarda yakalanıyorsa işin kimliği */
    var pcJid: String = "",
)

/**
 * İstek listesindeki bir yapım. Kullanıcı TMDB'den seçer; sayfayı sitede liste kendisi bulur.
 * state: wait (sırada) | work (işlemde) | done (hepsi alındı) | retry (tekrar denenecek) | miss (sitede bulunamadı, sonra tekrar aranır)
 */
@Serializable
data class WishItem(
    val id: String, val tmdb: String, var kind: String, val title: String, val original: String = "", val year: Int? = null,
    var seasons: List<Int> = emptyList(),          // boş = bütün sezonlar
    var pageUrl: String = "", var state: String = "wait", var tries: Int = 0, var nextAt: Long = 0, var note: String = "",
    var lastScan: Long = 0, val eps: MutableList<WishEp> = mutableListOf(),
    /** Close'tan alınan en iyi kalite (p): Rapidrame ancak bu kalitede ya da daha iyiyse kabul edilir */
    var closeQ: Int = 0,
    /** Bilgisayarda yakalanıyorsa işin kimliği (film) */
    var pcJid: String = "",
    /** Eski sürümde Yakında'dan kendiliğinden gelmişti (sadece 🗓 işareti için) */
    var soon: Boolean = false,
    /** 🆕 Kataloğdaki yabancı dizinin yeni bölümü (kendiliğinden): yakalanınca onay beklemeden yayına girer */
    var newEp: Boolean = false,
    /** Sayfayı liste kendisi eşleştirdi (kullanıcı kontrol etsin); okd: kullanıcı 'doğru' dedi */
    var auto: Boolean = false,
    var okd: Boolean = false,
)

/** 💡 Öneri: okutulan kategorilerde olup katalogda/listede olmayan yapım (kullanıcı Ekle ya da Geç der) */
@Serializable
data class Suggest(val url: String, val title: String, val kind: String, val cat: String = "", val at: Long = 0)

/**
 * İstek listesi: siyah ekran/kısıtlama olunca bekleyip tekrar dener, alana kadar. Siteye karşı nazik: aynı anda tek sayfa,
 * işler arasında ara, başarısızlıkta kullanıcının ayarladığı sabit aralıkla (varsayılan 15 dk) tekrar. Kısıtlamayı aşmaya çalışmaz; sadece bekler.
 */
object WishList {
    private const val PREFS = "wishlist"

    /** Liste ▶ Başlat ile çalışır durumda bırakılmış mı (açılışta / cihaz yeniden başlayınca kendiliğinden sürsün) */
    /** Bu cihaz işi yapan cihaz mı (açık uygulama olmadan da bakılabilsin) */
    fun wasWorker(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("worker", c.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))

    fun wasStarted(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("started", false)
    private const val MISS_WAIT = 6 * 3600_000L
    private const val RESCAN_MS = 6 * 3600_000L

    val items = mutableStateListOf<WishItem>()
    /** Ekran yenilensin diye her değişiklikte artar */
    var rev by mutableIntStateOf(0)
        private set
    /** Son siyah ekran (kullanıcıya "istersen 🧹 Temizle'ye bas" uyarısı); temizleyince ya da yakalanınca kalkar */
    var black by mutableStateOf("")

    private fun warnBlack(what: String) {
        black = "⚫ $what: siyah ekran (${hhmm(System.currentTimeMillis())}). İstersen 🧹 Temizle'ye bas; liste sonra kendisi tekrar dener."
        if (::app.isInitialized) CaptureService.alert(app, black)
    }

    /** Kullanıcı 🧹 Temizle'ye bastı */
    private fun loadLocal(): List<WishItem> =
        runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WishItem.serializer()), prefs.getString("items", "[]") ?: "[]") }.getOrDefault(emptyList())

    fun cleared() {
        black = ""
        if (::app.isInitialized) CaptureService.clearAlert(app)
    }

    var now by mutableStateOf("")
        private set
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var app: Context
    private var notified = false

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        retryMin = prefs.getInt("retryMin", 20)
        started = prefs.getBoolean("started", false)
        listStay = prefs.getInt("listStay", 10)
        lockRun = prefs.getBoolean("lockRun", true)
        showWindow = prefs.getBoolean("showWindow", false)
        bgRun = prefs.getBoolean("bgRun", true)
        pcShare = prefs.getBoolean("pcShare", true)
        // One-time upgrade to phone-only operation; keep every credential, site and queue entry.
        if (!prefs.getBoolean("phonePipelineV2", false))
            prefs.edit().putBoolean("phoneArchive", true).putBoolean("phonePipelineV2", true)
                .putInt("titleGapMin", 0).putLong("restUntil", 0).apply()
        phoneArchive = prefs.getBoolean("phoneArchive", true)
        night = prefs.getBoolean("night", false)
        gapSec = prefs.getInt("gapSec", 20)
        cats = prefs.getString("cats", "").orEmpty().split("\n").filter { it.startsWith("http") }
        oneByOne = prefs.getBoolean("oneByOne", true)
        titleGapMin = prefs.getInt("titleGapMin", 15)
        sugEveryH = prefs.getInt("sugEveryH", 6)
        restUntil = prefs.getLong("restUntil", 0)
        sugSeen.addAll(prefs.getString("sugSeen", "").orEmpty().split("\n").filter { it.isNotBlank() })
        prefs.getString("catReadAt", "").orEmpty().lines().forEach { l ->
            val i = l.indexOf('\t'); if (i > 0) l.substring(i + 1).toLongOrNull()?.let { catReadAt[l.substring(0, i)] = it }
        }
        runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Suggest.serializer()), prefs.getString("sugs", "[]") ?: "[]") }
            .getOrNull()?.let { if (prefs.getBoolean("worker", isTv(app) || !PcServer.configured)) sugs.addAll(it) }
        worker = if (phoneArchive) true else prefs.getBoolean("worker", isTv(app) || !PcServer.configured)
        prefs.getString("events", "").orEmpty().lines().forEach { l ->
            val i = l.indexOf('\t'); if (i > 0) l.substring(0, i).toLongOrNull()?.let { events.addLast(it to l.substring(i + 1)) }
        }
        val saved = loadLocal()
        // Telefon artık panel: eski listesi, yakalama cihazı (TV kutusu) ilk kez görünce ona aktarılır (bir kez)
        if (!worker && saved.isNotEmpty() && !prefs.getBoolean("imported", false)) pendingImport = saved
        // uygulama kapanırken "işlemde" kalanlar sıraya döner
        saved.forEach { w ->
            if (w.state == "work" && w.pcJid.isBlank()) w.state = "wait"; w.eps.forEach { if (it.state == "work" && it.pcJid.isBlank()) it.state = "wait" }
            if (w.pageUrl.isNotBlank() && isHome(w.pageUrl) && w.state != "done") {
                w.pageUrl = ""; w.eps.clear(); w.lastScan = 0; w.note = "Yanlış sayfaya (ana/kategori sayfası) eşleşmişti; doğru sayfa yeniden aranacak"
            }
        }
        // "oynatıcı yok" denetimi kaldırıldı: öyle işaretlenip yarına bırakılanlar hemen siyah ekran gibi yeniden denenir
        saved.forEach { w ->
            if (w.note.startsWith("Sitede oynatıcı yok")) { w.nextAt = 0; w.note = "" }
            w.eps.forEach { if (it.note.startsWith("Sitede oynatıcı yok")) { it.nextAt = 0; it.note = "" } }
        }
        if (worker) items.addAll(saved)
        // Yakında'dan kendiliğinden eklenenler (özellik kaldırıldı, yanlış algılıyordu) bir kez listeden çıkarılır
        if (!prefs.getBoolean("soonPurged", false)) {
            items.removeAll { it.soon }
            prefs.edit().putBoolean("soonPurged", true).apply()
            save()
        }
    }

    private fun save() {
        rev++
        if (::prefs.isInitialized && worker) prefs.edit().putString("items", AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(WishItem.serializer()), items.toList())).apply()
    }

    /** Kullanıcı çipteki 'Kapat'a bastı (uygulama yeniden açılınca ya da ▶ Başlat'a basınca geri gelir) */
    @Volatile var chipClosed = false

    /** Çip gösterilsin mi: bilgisayara bağlı Stüdyo telefonunda, liste başlatılmışsa ya da içinde iş varsa */
    // ---------------------------------------------------------------- 📺 yakalama cihazı / uzaktan panel
    /** Bu cihaz işi yapar mı (TV kutusu). Kapalıysa (telefon) sadece panel: durum bilgisayardan okunur, komutlar oraya gider. */
    var worker by mutableStateOf(false)
        private set

    /** Phone captures, downloads, verifies archive.org and clears space before the next title. */
    var phoneArchive by mutableStateOf(true)
        private set
    fun changePhoneArchive(on: Boolean) {
        phoneArchive = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("phoneArchive", on).apply()
        if (on && !worker && ::app.isInitialized) changeWorker(true)
    }
    var remoteDevice by mutableStateOf("")
        private set
    /** Yakalama cihazından son haber kaç sn önce (null: hiç yok) */
    var remoteAge by mutableStateOf<Long?>(null)
        private set
    private var remoteEtas: Map<String, Long> = emptyMap()
    /** Kategori okuma sonucu (cihaz okur, panel gösterir) */
    var remoteCat by mutableStateOf<Triple<String, List<SiteResult>, String>?>(null)
        private set
    private var catForPanel: Triple<String, List<SiteResult>, String>? = null

    /** TV kutusunda işi yapan cihaz: uygulama açıkken ekran hiç kapanmaz */
    val tvWorker: Boolean get() = worker && ::app.isInitialized && isTv(app)

    private fun isTv(c: Context) = c.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    fun changeWorker(on: Boolean) {
        if (on == worker) return
        if (!on && items.isNotEmpty()) {
            // telefon panele dönüyor: elindeki liste yakalama cihazına aktarılır (orada olmayanlar eklenir)
            val json = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(WishItem.serializer()), items.toList())
            sendCmd(buildJsonObject { put("op", "import"); put("items", json) })
        }
        worker = on
        prefs.edit().putBoolean("worker", on).apply()
        if (!on) { holdWake(false); runCatching { CaptureService.stop(app) } }
        else { items.clear(); items.addAll(loadLocal()) }
    }

    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    private fun sendCmd(c: JsonObject) {
        ioScope.launch {
            runCatching { PcServer.wishCmd(c) }.onFailure { now = "⚠ Komut gönderilemedi: ${it.message}" }
            delay(1500); runCatching { pull() }
        }
    }
    private fun cmd(op: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = sendCmd(buildJsonObject { put("op", op); b() })

    /** Panel: yakalama cihazının durumunu al (liste, anlık durum, tahminler) */
    private suspend fun pull() {
        val o = PcServer.wishView()
        remoteAge = (o["age"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
        remoteDevice = (o["device"] as? JsonPrimitive)?.content.orEmpty()
        val st = o["state"] as? JsonObject ?: return
        fun str(k: String) = (st[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
        val list = runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WishItem.serializer()), str("items")) }.getOrNull()
        if (list != null) { items.clear(); items.addAll(list) }
        runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Suggest.serializer()), str("sugs")) }.getOrNull()?.let {
            if (it != sugs.toList()) { sugs.clear(); sugs.addAll(it) }
        }
        if (str("oneByOne").isNotBlank()) oneByOne = str("oneByOne") == "true"
        str("titleGapMin").toIntOrNull()?.let { titleGapMin = it }
        str("sugEveryH").toIntOrNull()?.let { sugEveryH = it }
        restUntil = str("restUntil").toLongOrNull() ?: 0L
        now = str("now"); pcNow = str("pcNow"); black = str("black"); catProgress = str("catProgress")
        started = str("started") == "true"
        remoteEtas = (st["etas"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toLongOrNull()?.let { k to it } }?.toMap().orEmpty()
        WishNotify.events(app, st)
        (st["cat"] as? JsonObject)?.let { c ->
            fun cs(k: String) = (c[k] as? JsonPrimitive)?.content.orEmpty()
            val found = (c["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                fun xs(k: String) = (x[k] as? JsonPrimitive)?.content.orEmpty()
                SiteResult(xs("u"), xs("t"), null, xs("k").ifBlank { "?" })
            }
            val t = Triple(cs("url"), found, cs("next"))
            if (remoteCat?.first != t.first || remoteCat?.second?.size != found.size) remoteCat = t
        }
        rev++
    }

    /** Yakalama cihazı: durumunu bilgisayara yazar (değiştiyse 3 sn'de, yoksa 20 sn'de bir), panelin komutlarını uygular */
    private var lastReport = 0L
    private var reportKey = ""
    private suspend fun report(force: Boolean = false) {
        if (force) reportKey = ""
        val json = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(WishItem.serializer()), items.toList())
        val sj = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Suggest.serializer()), sugs.toList())
        val key = json.hashCode().toString() + sj.hashCode() + oneByOne + titleGapMin + sugEveryH + restUntil + now + pcNow + started + catProgress + (catForPanel?.first ?: "") + events.size + (events.lastOrNull()?.first ?: 0)
        if (key == reportKey && System.currentTimeMillis() - lastReport < 20_000L) return
        val e = etas()
        val state = buildJsonObject {
            put("items", json); put("now", now); put("pcNow", pcNow); put("black", black); put("started", started.toString())
            put("catProgress", catProgress)
            put("sugs", sj); put("oneByOne", oneByOne.toString()); put("titleGapMin", titleGapMin); put("sugEveryH", sugEveryH); put("restUntil", restUntil)
            put("events", JsonArray(events.map { (at, t) -> buildJsonObject { put("at", at); put("text", t) } }))
            put("etas", buildJsonObject { e.forEach { (k, v) -> put(k, v) } })
            catForPanel?.let { (u, f, n) ->
                put("cat", buildJsonObject {
                    put("url", u); put("next", n)
                    put("items", JsonArray(f.map { r -> buildJsonObject { put("u", r.url); put("t", r.title); put("k", r.kind) } }))
                })
            }
        }
        val o = PcServer.wishReport(android.os.Build.MODEL.ifBlank { "TV kutusu" }, state)
        reportKey = key; lastReport = System.currentTimeMillis()
        for (c in (o["cmds"] as? JsonArray).orEmpty()) runCatching { apply(c as JsonObject) }
    }

    private fun apply(c: JsonObject) {
        fun s(k: String) = (c[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
        fun item() = items.firstOrNull { it.id == s("id") }
        when (s("op")) {
            "add" -> add(s("tmdb"), s("kind"), s("title"), s("original"), s("year").toIntOrNull(), emptyList(), s("page"), front = s("front") == "true")
            "addPage" -> { addPage(s("url"), s("title"), s("kind")); save() }
            "remove" -> item()?.let { remove(it) }
            "move" -> item()?.let { move(it, s("delta").toIntOrNull() ?: 0) }
            "retry" -> item()?.let { retryNow(it) }
            "confirm" -> item()?.let { confirmPage(it) }
            "setPage" -> item()?.let { setPage(it, s("url")) }
            "start" -> changeStarted(s("on") == "true")
            "clear" -> {
                // "Hepsini sil": tutulacaklar (keep: virgülle kimlikler) dışında hepsi
                val keep = s("keep").split(',').filter { it.isNotBlank() }.toSet()
                items.removeAll { it.id !in keep }; save()
            }
            "sugAdd" -> { addSuggestion(s("url")) }
            "sugSkip" -> { skipSuggestion(s("url")) }
            "sugRefresh" -> refreshSuggestionsSoon()
            "oneByOne" -> changeOneByOne(s("on") == "true")
            "titleGapMin" -> s("v").toIntOrNull()?.let { setTitleGap(it) }
            "sugEveryH" -> s("v").toIntOrNull()?.let { setSugEvery(it) }
            "retryMin" -> s("v").toIntOrNull()?.let { setRetry(it) }
            "gapSec" -> s("v").toIntOrNull()?.let { setGap(it) }
            "listStay" -> s("v").toIntOrNull()?.let { changeListStay(it) }
            "readCat" -> ioScope.launch {
                val u = s("url")
                val r = runCatching { readCategory(u) }.getOrNull()
                catForPanel = Triple(u, r?.first.orEmpty(), r?.second.orEmpty())
            }
            "import" -> {
                val list = runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WishItem.serializer()), s("items")) }.getOrDefault(emptyList())
                list.forEach { w ->
                    val dup = items.any { (w.tmdb.isNotBlank() && it.tmdb == w.tmdb) || (w.pageUrl.isNotBlank() && it.pageUrl == w.pageUrl) }
                    if (!dup) {
                        if (w.state == "work") w.state = "wait"; w.pcJid = ""
                        w.eps.forEach { if (it.state == "work") it.state = "wait"; it.pcJid = "" }
                        items.add(w)
                    }
                }
                save()
            }
        }
    }

    // ---- 🔔 bitenler: sezonu tamamlanan dizi / alınan film → panel telefonuna bildirim
    private val events = ArrayDeque<Pair<Long, String>>()

    private fun milestone(text: String) {
        var at = System.currentTimeMillis()
        if (events.isNotEmpty() && events.last().first >= at) at = events.last().first + 1
        events.addLast(at to text)
        while (events.size > 50) events.removeFirst()
        prefs.edit().putString("events", events.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    private var pendingImport: List<WishItem>? = null

    /** TV kutusu: uygulama bir şekilde arkaya düştüyse (ana ekrana dönüldü) 15 sn sonra kendini yeniden öne alır */
    private var backSince = 0L
    private fun keepFront() {
        if (!tvWorker || !started) { backSince = 0; return }
        if (!CaptureEngine.appInBackground) { backSince = 0; return }
        if (backSince == 0L) { backSince = System.currentTimeMillis(); return }
        if (System.currentTimeMillis() - backSince < 15_000) return
        backSince = 0
        runCatching {
            app.startActivity(android.content.Intent(app, com.gflix.app.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    /** İkisi için de: cihazsa 3 sn'de bir durum yazar; panelse 3 sn'de bir okur */
    suspend fun remoteLoop() {
        while (true) {
            delay(3_000)
            if (!::app.isInitialized || !PcServer.configured) continue
            if (worker) { runCatching { report() }; keepFront(); continue }
            runCatching { pull() }
            val imp = pendingImport
            if (imp != null && remoteAge != null && remoteAge!! < 120) {
                pendingImport = null
                prefs.edit().putBoolean("imported", true).apply()
                sendCmd(buildJsonObject { put("op", "import"); put("items", AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(WishItem.serializer()), imp)) })
            }
        }
    }

    /** Yakalama cihazı: panelin komutlarını bekler, gelir gelmez uygular ve yeni durumu hemen yazar */
    suspend fun cmdLoop() {
        while (true) {
            if (!::app.isInitialized || !PcServer.configured || !worker) { delay(5_000); continue }
            val o = runCatching { PcServer.wishCmds(25) }.getOrNull()
            if (o == null) { delay(5_000); continue }
            val cmds = (o["cmds"] as? JsonArray).orEmpty()
            for (c in cmds) runCatching { apply(c as JsonObject) }
            if (cmds.isNotEmpty()) runCatching { report(force = true) }
        }
    }

    /** "Hepsini sil" (tutulacaklar hariç) */
    fun clearAll(keep: Set<String> = emptySet()) {
        if (!worker) { cmd("clear") { put("keep", keep.joinToString(",")) }; items.removeAll { it.id !in keep }; return }
        items.removeAll { it.id !in keep }; save()
    }

    /** Panelde kategori okut: cihaz okur, sonuç durumla gelir */
    fun askCategory(url: String) { remoteCat = null; cmd("readCat") { put("url", url) } }

    fun chipWanted(): Boolean = ::app.isInitialized && (PcServer.configured || phoneArchive) && (started || hasOpen())

    fun hasOpen(): Boolean = items.any { it.state != "done" }

    fun add(tmdb: String, kind: String, title: String, original: String, year: Int?, seasons: List<Int>, page: String = "",
            front: Boolean = false): WishItem {
        if (!worker) {
            cmd("add") { put("tmdb", tmdb); put("kind", kind); put("title", title); put("original", original); year?.let { put("year", it) }
                put("page", page); put("front", front.toString()) }
            return WishItem("", tmdb, kind, title, original, year, seasons)
        }
        val old = items.firstOrNull { it.tmdb == tmdb && it.state != "done" }
        val w = old ?: WishItem(System.currentTimeMillis().toString(36), tmdb, kind, title, original, year, seasons.sorted()).also { items.add(it) }
        // aynı dizi tekrar istenirse sezonlar birleşir (biri "hepsi" ise hepsi)
        if (old != null) old.seasons = if (old.seasons.isEmpty() || seasons.isEmpty()) emptyList() else (old.seasons + seasons).distinct().sorted()
        w.state = "wait"; w.nextAt = 0; w.lastScan = 0
        if (front) { items.remove(w); items.add(0, w) }        // (artık kullanılmıyor: yeni eklenen sıranın sonuna girer, ↑↓ ile öne alınır)
        if (page.isBlank() || !setPage(w, page)) save()
        return w
    }

    /** Kategoriden seçilen site sayfası (TMDB eşleştirmesi sonra onayda kullanıcıda). Film/dizi belli değilse sayfadan anlaşılır. */
    fun addPage(url: String, title: String, kind: String): Boolean {
        if (!worker) { cmd("addPage") { put("url", url); put("title", title); put("kind", kind) }; return true }
        if (items.any { it.pageUrl == url && it.state != "done" }) return false
        val k = when (kind) { "series" -> "tv"; "movie" -> "movie"; else -> "auto" }
        items.add(WishItem(System.currentTimeMillis().toString(36) + items.size, "", k, title, pageUrl = url, okd = true))
        return true
    }

    fun saveNow() = save()

    // ---- 📂 kategoriden ekle: listenin kendi tarayıcısında okunur (liste o anki işi bitirince; Sayfadan Yakala'yla çakışmaz)
    var cats by mutableStateOf<List<String>>(emptyList())
        private set
    var catProgress by mutableStateOf("")

    suspend fun readCategory(url: String): Pair<List<SiteResult>, String> {
        var out: Pair<List<SiteResult>, String> = emptyList<SiteResult>() to ""
        busy { now = "📂 kategori okunuyor"; out = CaptureEngine.categoryAt(url) { live(it); catProgress = it } }
        catProgress = ""
        if (out.first.isNotEmpty() && url !in cats) {
            cats = (listOf(url) + cats).take(30)
            prefs.edit().putString("cats", cats.joinToString("\n")).apply()
        }
        return out
    }

    fun forgetCategory(url: String) { cats = cats - url; prefs.edit().putString("cats", cats.joinToString("\n")).apply() }

    // ---- 💡 öneriler: okutulan kategoriler belli aralıkla yeniden okunur; katalogda/listede olmayanlar önerilir.
    //      Kendiliğinden listeye bir şey eklenmez: kullanıcı ＋ Ekle ya da Geç der.
    val sugs = mutableStateListOf<Suggest>()
    /** Bir kez önerilmiş (eklenmiş ya da geçilmiş): bir daha önerilmez */
    private val sugSeen = LinkedHashSet<String>()
    private val catReadAt = mutableMapOf<String, Long>()
    var sugEveryH by mutableIntStateOf(6)
        private set
    fun setSugEvery(h: Int) { if (!worker) cmd("sugEveryH") { put("v", h) }; sugEveryH = h.coerceIn(1, 168); if (::prefs.isInitialized) prefs.edit().putInt("sugEveryH", sugEveryH).apply() }

    private fun saveSugs() {
        rev++
        if (!::prefs.isInitialized || !worker) return
        while (sugSeen.size > 5000) sugSeen.remove(sugSeen.first())
        prefs.edit().putString("sugs", AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Suggest.serializer()), sugs.toList()))
            .putString("sugSeen", sugSeen.joinToString("\n"))
            .putString("catReadAt", catReadAt.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    /** ＋ Ekle: listenin sonuna girer (diziyse bütün sezonlar), sıra gelince alınıp onaya gider */
    fun addSuggestion(url: String) {
        if (!worker) { cmd("sugAdd") { put("url", url) }; sugs.removeAll { it.url == url }; return }
        val sg = sugs.firstOrNull { it.url == url } ?: return
        addPage(sg.url, sg.title, sg.kind)
        sugs.remove(sg); sugSeen.add(url); save(); saveSugs()
    }

    fun skipSuggestion(url: String) {
        if (!worker) { cmd("sugSkip") { put("url", url) }; sugs.removeAll { it.url == url }; return }
        sugs.removeAll { it.url == url }; sugSeen.add(url); saveSugs()
    }

    /** 🔄 Şimdi yenile: bütün kategoriler sıradaki boşlukta yeniden okunur */
    fun refreshSuggestionsSoon() {
        if (!worker) { cmd("sugRefresh"); return }
        catReadAt.clear(); saveSugs(); now = "💡 Öneriler sıradaki boşlukta yenilenecek"
    }

    private fun sugDueCat(t: Long): String? =
        cats.filter { t - (catReadAt[it] ?: 0L) >= sugEveryH * 3600_000L }.minByOrNull { catReadAt[it] ?: 0L }

    fun catLabel(u: String): String = runCatching { java.net.URI(u).path.trim('/').substringAfterLast('/').ifBlank { u } }.getOrDefault(u).take(28)

    private fun catalogNames(): Set<String> = runCatching {
        app.appContainer.catalogRepository.catalog.value?.titles.orEmpty()
            .flatMap { listOfNotNull(it.title, it.originalTitle) }.map { JobGroups.normalize(it) }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    /** Site başlığından ("… izle", yıl, dublaj) arınmış ad: katalogla karşılaştırmak için */
    fun bareName(title: String): String = JobGroups.normalize(
        title.replace(Regex("(?i)\\b(izle|türkçe dublaj|altyazılı|full hd|hd)\\b"), "").replace(Regex("\\(?\\b(19|20)\\d{2}\\b\\)?"), ""))

    /** Bir kategoriyi okur, yenileri önerilere ekler (yakalama cihazında, liste boşluğunda) */
    private suspend fun sugStep(url: String) {
        val (found, _) = readCategory(url)
        catReadAt[url] = System.currentTimeMillis()
        if (found.isEmpty()) { saveSugs(); return }
        val have = catalogNames()
        val known = sugs.map { it.url }.toSet() + items.map { it.pageUrl }.toSet()
        var fresh = found.filter { it.url !in sugSeen && it.url !in known && !isHome(it.url) }
            .filter { r -> bareName(r.title).let { it.isBlank() || it !in have } }
            .distinctBy { it.url }
        // yayında ya da onayda olan sayfalar da önerilmez
        if (fresh.isNotEmpty()) runCatching {
            val st = fresh.map { it.url }.chunked(200).flatMap { PcServer.sourceStatus(it).entries }.associate { it.key to it.value }
            fresh = fresh.filter { st[it.url] != "live" && st[it.url] != "pending" }
        }
        val t = System.currentTimeMillis()
        val label = catLabel(url)
        fresh.forEach { sugs.add(Suggest(it.url, it.title, it.kind, label, t)) }
        saveSugs()
        if (fresh.isNotEmpty()) milestone("💡 ${fresh.size} yeni öneri ($label) · İstek listesi → 💡 Öneriler")
    }

    // ---- 1️⃣ tek tek: sıradaki yapım bitmeden (dizide bütün sezonlar) yenisine geçilmez; bitince mola, sonra sıradaki
    var oneByOne by mutableStateOf(true)
        private set
    fun changeOneByOne(on: Boolean) { if (!worker) cmd("oneByOne") { put("on", on.toString()) }; oneByOne = on; if (::prefs.isInitialized) prefs.edit().putBoolean("oneByOne", on).apply() }
    var titleGapMin by mutableIntStateOf(15)
        private set
    fun setTitleGap(min: Int) { if (!worker) cmd("titleGapMin") { put("v", min) }; titleGapMin = min.coerceIn(0, 240); if (::prefs.isInitialized) prefs.edit().putInt("titleGapMin", titleGapMin).apply() }
    /** Mola bitişi (bir yapım bitince) */
    var restUntil by mutableStateOf(0L)
        private set

    private fun titleFinished() {
        if (!oneByOne || titleGapMin <= 0) return
        restUntil = System.currentTimeMillis() + titleGapMin * 60_000L
        prefs.edit().putLong("restUntil", restUntil).apply()
    }

    /** Çalışılacak yapımlar: tek tek açıksa sadece sıradaki (sitede bulunamayıp bekleyenler atlanır) */
    private fun pool(): List<WishItem> {
        val open = items.filter { it.state != "done" }
        if (!oneByOne) return open
        return listOfNotNull(open.firstOrNull { it.state != "miss" } ?: open.firstOrNull())
    }

    // ---- ⏱ tahmini tarih: son alınanların hızına göre her yapımın ne zaman alınacağı; TMDB'si seçili olanlar Yakında'da görünür
    private val doneTimes = ArrayDeque<Long>()
    private fun noteDone() { doneTimes.addLast(System.currentTimeMillis()); while (doneTimes.size > 30) doneTimes.removeFirst() }

    /** Bir parçanın (film / bölüm) ortalama süresi (ms): son alınanlardan; az veri varsa 4 dk */
    private fun unitMs(): Long {
        if (doneTimes.size < 5) return 4 * 60_000L
        return ((doneTimes.last() - doneTimes.first()) / (doneTimes.size - 1)).coerceIn(60_000L, 30 * 60_000L)
    }

    private fun unitsOf(w: WishItem): Int = when {
        w.kind == "movie" -> if (w.state == "done") 0 else 1
        w.eps.isNotEmpty() -> w.eps.count { it.state != "done" }
        else -> 10 * maxOf(1, w.seasons.size)                // bölüm listesi henüz okunmadı: tahmin
    }

    /** Liste sırasına göre her yapımın tahmini bitişi (ms) */
    fun etas(): Map<String, Long> {
        if (!worker) return remoteEtas
        val per = unitMs()
        var t = System.currentTimeMillis()
        val out = HashMap<String, Long>()
        for (w in items) {
            if (w.state == "done") continue
            t += unitsOf(w) * (per + gapSec * 1000L)
            out[w.id] = t
        }
        return out
    }

    private var lastPlan = 0L
    private var planKey = ""
    private suspend fun syncPlan() {
        val e = etas()
        val plan = items.filter { it.tmdb.isNotBlank() && it.state != "done" && e[it.id] != null }
        val key = plan.joinToString { it.tmdb + ":" + (e[it.id]!! / 86_400_000L) }
        if (key == planKey && System.currentTimeMillis() - lastPlan < 6 * 3600_000L) return
        if (System.currentTimeMillis() - lastPlan < 30 * 60_000L && key.isNotEmpty() && planKey.isNotEmpty()) return
        PcServer.soonPlan(plan.map { it.tmdb to e[it.id]!! / 1000 })
        planKey = key; lastPlan = System.currentTimeMillis()
    }

    /** Kendi eşleştirdiği sayfayı kullanıcı onayladı */
    fun confirmPage(w: WishItem) { if (!worker) { cmd("confirm") { put("id", w.id) }; return }; w.okd = true; save() }

    fun toReview(): List<WishItem> = items.filter { it.auto && !it.okd && it.pageUrl.isNotBlank() }

    // ---- 🔄 tazeleme: izlemeye çalışılan yapımın linki eskimiş, bilgisayar açamadı → telefon hemen yeniden yakalar
    private data class Refresh(val id: String, val season: Int?, val episode: Int?, val page: String, val title: String)
    private val refreshQ = ArrayDeque<Refresh>()
    private var lastRf = 0L
    private val rfFailUntil = HashMap<String, Long>()
    /** Tarayıcıyı aynı anda tek iş kullanır (liste döngüsü ve tazeleme ayrı koşar) */
    private val runLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun syncRefresh(waitSec: Int = 0) {
        if (waitSec == 0 && System.currentTimeMillis() - lastRf < 20_000L) return
        lastRf = System.currentTimeMillis()
        val o = PcServer.refreshList(waitSec)
        for (e in (o["items"] as? JsonArray).orEmpty()) {
            val x = e as? JsonObject ?: continue
            fun str(k: String) = (x[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
            val r = Refresh(str("id"), str("season").toIntOrNull(), str("episode").toIntOrNull(), str("page"), str("title"))
            val k = "${r.id}|${r.season}|${r.episode}"
            if (System.currentTimeMillis() < (rfFailUntil[k] ?: 0L)) continue          // az önce siyah ekran: 1 dk sonra
            if (r.id.isNotBlank() && r.page.isNotBlank() && refreshQ.none { it.id == r.id && it.season == r.season && it.episode == r.episode }) refreshQ.addLast(r)
        }
    }

    /** Tazelemeleri 5 sn'de bir izle: biri izlemek istiyorsa hemen yakala (liste duraklatılmışken, telefonda film
     *  izlenirken de). Liste o an başka işteyse o iş bırakılır, önce tazelenir. */
    suspend fun refreshWatch() {
        while (true) {
            if (!::app.isInitialized || !PcServer.configured || !worker) { delay(5_000); continue }
            // Uzun sorgu: bilgisayar iş gelene kadar bekletir, iş gelir gelmez döner (sorgu aralığı gecikmesi yok)
            val t0 = System.currentTimeMillis()
            val ok = runCatching { syncRefresh(waitSec = if (refreshQ.isEmpty()) 25 else 0) }.isSuccess
            if (!ok) { delay(5_000); continue }
            if (refreshQ.isEmpty()) {
                // bilgisayar hemen döndü ama yapılacak yok (ör. az önce siyah ekran: 1 dk bekleyen): sunucuyu boşuna yorma
                if (System.currentTimeMillis() - t0 < 2_000) delay(3_000)
                continue
            }
            when {
                refreshing -> delay(1_000)
                CaptureEngine.wishActive -> { CaptureEngine.preempt = true; delay(500) }
                !runLock.isLocked && CaptureEngine.canRefresh() -> { runCatching { doRefresh() }; save() }
                else -> delay(1_000)
            }
        }
    }
    @Volatile private var refreshing = false

    /** Sırada tazeleme varsa önce o (izleyen biri bekliyor); liste duraklatılmış olsa da yapılır */
    private suspend fun doRefresh(): Boolean {
        val r = refreshQ.removeFirstOrNull() ?: return false
        val hint = buildJsonObject {
            put("refresh", buildJsonObject { put("id", r.id); r.season?.let { put("season", it) }; r.episode?.let { put("episode", it) } })
        }
        refreshing = true
        CaptureEngine.preempt = false
        busy {
            val res = try { CaptureEngine.captureAt(r.page, hint, 0, 0) { live("🔄 ${r.title}: $it") } } finally { refreshing = false }
            now = if (res == "ok") "🔄 ${r.title} tazelendi (izlenebilir)" else "🔄 ${r.title} tazelenemedi (siyah ekran); 1 dk sonra tekrar"
            if (res != "ok") rfFailUntil["${r.id}|${r.season}|${r.episode}"] = System.currentTimeMillis() + 60_000L
        }
        return true
    }


    // ---- 🆕 yeni bölümler: yabancı dizinin TMDB'de yayınlanmış bölümü katalogda yoksa listenin başına (saatte bir sorulur;
    // bilgisayar aynı bölümü günde bir kez verir, sitede yoksa ertesi gün yine bakılır)
    private var lastNew = 0L

    private suspend fun syncNewEps() {
        if (System.currentTimeMillis() - lastNew < 60 * 60_000L) return
        lastNew = System.currentTimeMillis()
        val o = PcServer.newEps()
        var added = 0
        for (e in (o["items"] as? JsonArray).orEmpty()) {
            val x = e as? JsonObject ?: continue
            fun str(k: String) = (x[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
            val tmdb = str("tmdb"); val season = str("season").toIntOrNull() ?: continue
            if (tmdb.isBlank()) continue
            // dizinin sitedeki sayfası daha önce bulunduysa yine o (aramaya gerek yok)
            val page = items.lastOrNull { it.tmdb == tmdb && it.pageUrl.isNotBlank() && (it.okd || !it.auto) }?.pageUrl.orEmpty()
            val w = add(tmdb, "tv", str("title"), str("original"), str("year").toIntOrNull(), listOf(season), page)
            w.newEp = true
            val eps = (x["episodes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            w.note = "🆕 yeni bölüm yayınlandı (S%02d".format(season) + (eps.lastOrNull()?.let { "E%02d".format(it) } ?: "") + "); sitede aranıyor"
            // yeni bölüm de sıranın sonuna (kullanıcı ↑↓ ile öne alabilir)
            added++
        }
        if (added > 0) { now = "🆕 $added dizinin yeni bölümü listeye alındı"; save() }
    }

    /** Sitedeki sayfayı kullanıcı kendisi eşleştirdi (link yapıştırdı ya da sitede arayıp seçti) */
    /** Sitenin ana sayfası (ya da arama sayfası) yapım sayfası değildir: orada video yok */
    fun isHome(u: String): Boolean {
        val path = runCatching { java.net.URI(u).path.orEmpty() }.getOrDefault("").trim('/')
        if (path.isEmpty() || path.startsWith("?") || path == "search" || path.startsWith("arama")) return true
        // Kategori/liste sayfası (ör. /dizi/, /filmler/, /tur/aksiyon): yapımın kendi sayfası değil (onlarda '-' olur: loki-izle-…)
        val segs = path.split('/').filter { it.isNotBlank() }
        val cat = setOf("dizi", "diziler", "film", "filmler", "series", "movies", "tur", "kategori", "category", "yil", "oyuncu", "imdb", "en-cok-izlenenler", "yeni-eklenenler")
        return segs.isEmpty() || segs.first().lowercase() in cat && (segs.size == 1 || segs.size == 2 && !segs[1].contains('-')) || segs.size == 1 && !segs[0].contains('-')
    }

    /** false: link yapım sayfası değil (ör. sitenin ana sayfası) */
    fun setPage(w: WishItem, raw: String): Boolean {
        if (!worker) { if (isHome(raw.trim())) return false; cmd("setPage") { put("id", w.id); put("url", raw.trim()) }; return true }
        val u = raw.trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (u.length < 12 || isHome(u)) return false
        w.pageUrl = u; w.eps.clear(); w.lastScan = 0; w.tries = 0; w.nextAt = 0; w.state = "wait"
        w.note = "Sayfa elle eşleştirildi"
        w.auto = false; w.okd = true
        save()
        return true
    }

    /** Sitede ara (liste o an tarayıcıyı kullanmıyorsa). null = meşgul, sonra dene */
    suspend fun searchSite(q: String): List<SiteResult>? {
        if (CaptureEngine.wishActive || !CaptureEngine.canWish()) return null
        CaptureEngine.wishActive = true
        try { return CaptureEngine.siteSearchFor(q) } finally { CaptureEngine.wishActive = false }
    }

    fun remove(w: WishItem) { if (!worker) { cmd("remove") { put("id", w.id) }; items.remove(w); return }; items.remove(w); save() }

    /** "Şimdi dene": bekleyen her şeyin süresi sıfırlanır */
    fun retryNow(w: WishItem) {
        if (!worker) { cmd("retry") { put("id", w.id) }; return }
        w.nextAt = 0
        if (w.state == "miss" || w.state == "retry") w.state = "wait"
        w.eps.forEach { if (it.state == "retry") { it.nextAt = 0 } }
        save()
    }

    /** Siyah ekranda tekrar deneme aralığı (dk, kullanıcı ayarlar; sabit — her seferinde aynı) */
    /** 🌙 Gece modu: liste bekleyen iş varken ekran açık kalır (telefon şarjda bırakılır) */
    var night by mutableStateOf(false)
        private set

    fun changeNight(on: Boolean) { night = on; if (::prefs.isInitialized) prefs.edit().putBoolean("night", on).apply() }

    // ---- 🖥️ bilgisayar da yakalasın (işler bölüşülür)
    var pcShare by mutableStateOf(true)
        private set
    fun changePcShare(on: Boolean) { pcShare = on; if (::prefs.isInitialized) prefs.edit().putBoolean("pcShare", on).apply() }
    var pcNow by mutableStateOf("")
        private set

    // Yakında'dakiler artık kendiliğinden gelmez; Stüdyo → Yakında'dan elle eklenir (saat aralığı yok)

    /** Liste için: yakalandıktan sonra videoda kalma (sn), varsayılan 10 */
    var listStay by mutableIntStateOf(10)
        private set
    fun changeListStay(sec: Int) { if (!worker) cmd("listStay") { put("v", sec) }; listStay = sec.coerceIn(0, 600); if (::prefs.isInitialized) prefs.edit().putInt("listStay", listStay).apply() }

    /** Uygulama arka plandayken / ekran kapalıyken de denesin (varsayılan açık) */
    var bgRun by mutableStateOf(true)
        private set
    fun changeBgRun(on: Boolean) { bgRun = on; if (::prefs.isInitialized) prefs.edit().putBoolean("bgRun", on).apply() }

    /** Arkadaki pencereyi göster (kapalıyken saydam: sadece durum çubuğu çipi görünür) */
    var showWindow by mutableStateOf(false)
        private set
    fun changeShowWindow(on: Boolean) { showWindow = on; if (::prefs.isInitialized) prefs.edit().putBoolean("showWindow", on).apply() }

    var retryMin by mutableIntStateOf(20)
        private set
    /** İki yakalama arası bekleme (sn, kullanıcı ayarlar) */
    var gapSec by mutableIntStateOf(20)
        private set

    fun setRetry(min: Int) { if (!worker) cmd("retryMin") { put("v", min) }; retryMin = min.coerceIn(5, 180); if (::prefs.isInitialized) prefs.edit().putInt("retryMin", retryMin).apply() }
    fun setGap(sec: Int) { if (!worker) cmd("gapSec") { put("v", sec) }; gapSec = sec.coerceIn(0, 3600); if (::prefs.isInitialized) prefs.edit().putInt("gapSec", gapSec).apply() }

    @Suppress("UNUSED_PARAMETER")
    private fun backoff(tries: Int): Long = retryMin * 60_000L
    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    private fun hint(w: WishItem, e: WishEp?): JsonObject = buildJsonObject {
        put("tmdb", w.tmdb); put("title", w.title)
        if (w.newEp) put("auto", true)
        if (w.kind == "tv" && e != null) { put("season", e.season); put("episode", e.episode) }
    }

    private fun label(w: WishItem, e: WishEp?) = w.title + if (w.kind == "tv" && e != null) " S%02dE%02d".format(e.season, e.episode) else ""

    fun summary(): String {
        val eps = items.flatMap { w -> if (w.kind == "tv") w.eps else listOf(WishEp(0, 0, state = if (w.state == "done") "done" else w.state)) }
        val done = eps.count { it.state == "done" }
        val retry = eps.count { it.state == "retry" } + items.count { it.state == "miss" }
        val wait = eps.count { it.state == "wait" || it.state == "work" }
        val next = (items.filter { it.state in setOf("retry", "miss") }.map { it.nextAt } + items.flatMap { it.eps }.filter { it.state == "retry" }.map { it.nextAt })
            .filter { it > System.currentTimeMillis() }.minOrNull()
        return (if (oneByOne && restUntil > System.currentTimeMillis()) "☕ mola ${hhmm(restUntil)}'e kadar · " else "") + "🎯 İstek listesi: $done alındı · $wait sırada · $retry tekrar denenecek" + (next?.let { " · sonraki deneme ${hhmm(it)}" } ?: "")
    }

    // ------------------------------------------------------------------ arka plan döngüsü

    suspend fun loop() {
        while (true) {
            delay(15_000)
            if (!::app.isInitialized || !worker) continue        // panel (telefon): iş yapılmaz, sadece izlenir
            if (PcServer.configured && !phoneArchive) {
                runCatching { syncRefresh() }
                runCatching { syncNewEps() }.onFailure { now = "⚠ Yeni bölümler: ${it.message}" }
                runCatching { syncPlan() }
                if (refreshQ.isNotEmpty() && CaptureEngine.canRefresh()) { runCatching { doRefresh() }; save(); continue }
            }
            // Çip (durum çubuğu + kilit ekranı): kullanıcı 'Kapat' diyene kadar hep görünür; liste boşken de
            if (!chipClosed && chipWanted() && (!CaptureEngine.running || CaptureEngine.wishActive)) {
                CaptureService.update(app, now.ifBlank { pcNow }.ifBlank { if (hasOpen()) summary() else "✓ İstek listesi boş · yeni istek bekleniyor" })
                notified = true
            }
            if (!hasOpen()) {
                holdWake(false)
                // liste boş: sırası gelen kategori okunur (başlatılmışsa)
                val due = sugDueCat(System.currentTimeMillis())
                if (started && due != null && (phoneArchive || PcServer.configured) && CaptureEngine.canWish()) runCatching { sugStep(due) }
                continue
            }
            // "işlemde" kalıp sahipsiz kalan (takılan) iş sıraya döner
            if (!CaptureEngine.wishActive) {
                var fixed = false
                items.forEach { w ->
                    if (w.state == "work" && w.pcJid.isBlank() && (w.kind == "movie" || w.eps.none { it.state == "work" })) { w.state = "wait"; fixed = true }
                    w.eps.forEach { if (it.state == "work" && it.pcJid.isBlank()) { it.state = "wait"; fixed = true } }
                }
                if (fixed) save()
            }
            if (phoneArchive) {
                val waiting = runCatching { phoneStep(System.currentTimeMillis()) }
                    .getOrElse { now = "📱 Arşiv işleri kontrol edilemedi: ${it.message}"; true }
                save()
                if (waiting) continue  // One item in the entire pipeline; never start the next.
            } else if (PcServer.configured) {
                runCatching { pcStep(System.currentTimeMillis()) }.onFailure { pcNow = "🖥️ ⚠ ${it.message}" }
                save()
            }
            holdWake(started && bgRun && hasOpen())
            if (!started) { now = "⏸ Duraklatıldı: sırayı ayarla, ▶ Başlat'a bas"; continue }
            if ((!phoneArchive && !PcServer.configured) || !CaptureEngine.canWish()) {
                now = when {
                    !phoneArchive && !PcServer.configured -> "⏸ bilgisayar bağlı değil"
                    !CaptureEngine.hasSite() -> "⏸ önce Sayfadan Yakala'da siteyi bir kez aç"
                    CaptureEngine.running -> ""
                    !CaptureEngine.appVisible -> "⏸ ekran kapalı/uygulama arkada: video ancak ekran açıkken başlar. Şarja tak, 🌙 Gece modu'nu aç, uygulamayı açık bırak."
                    else -> "⏸ Sayfadan Yakala'da geziniyorsun; çıkınca liste devam eder"
                }
                continue
            }
            val t = System.currentTimeMillis()
            runCatching { step(t) }.onFailure { now = "⚠ ${it.message}" }
            save()
        }
    }

    /** 🔒 Kilit ekranında da çalışsın (ekran kapalı/kilitliyse kilidin üzerinde karanlık sayfa açılır) */
    var lockRun by mutableStateOf(true)
        private set
    fun changeLockRun(on: Boolean) { lockRun = on; if (::prefs.isInitialized) prefs.edit().putBoolean("lockRun", on).apply() }

    private fun screenOffOrLocked(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return !pm.isInteractive || km.isKeyguardLocked
    }

    // işlemci uyumasın (liste başlatılmış ve iş varken)
    private var wake: android.os.PowerManager.WakeLock? = null
    private fun holdWake(on: Boolean) {
        if (on && wake == null) {
            wake = (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "gflix:istek-listesi").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        } else if (!on) { wake?.let { runCatching { it.release() } }; wake = null }
    }

    private suspend fun busy(block: suspend () -> Unit) {
        // Telefon kilitli/ekran kapalı: kilidin üzerinde karanlık sayfayı aç (oynatıcı ancak görünür sayfada başlıyor)
        if (lockRun && bgRun && CaptureEngine.appInBackground && !CaptureEngine.runActivityOn && screenOffOrLocked()) {
            WishRunActivity.start(app)
            repeat(6) { if (!CaptureEngine.runActivityOn) delay(500) }
        }
        runLock.lock()
        CaptureEngine.wishActive = true
        try { block() } finally {
            CaptureEngine.wishActive = false; now = ""; CaptureEngine.clearIfRequested()
            if (CaptureEngine.watching) CaptureEngine.hideOverlay()      // telefonda izlenirken yapılan tazeleme bitti
            runLock.unlock()
        }
    }

    private suspend fun step(t: Long) {
        items.filter { it.pageUrl.isNotBlank() && isHome(it.pageUrl) && it.state != "done" }.forEach { w ->
            // ana sayfa / kategori sayfasına eşleşmiş: oradan okunan bölümler başka yapımın olabilir → baştan
            w.pageUrl = ""; w.state = "wait"; w.nextAt = 0; w.eps.clear(); w.lastScan = 0; w.auto = false
            w.note = "Yanlış sayfaya (ana/kategori sayfası) eşleşmişti; doğru sayfa yeniden aranacak"
        }
        // 💡 sırası gelen kategori (işler arasında; bir kategori)
        sugDueCat(t)?.let { u -> runCatching { sugStep(u) }; delay(gapSec * 1000L); return }
        // ☕ bir yapım bitti: mola
        if (oneByOne && restUntil > t && pool().isNotEmpty()) { now = "☕ Mola: sıradaki yapım ${hhmm(restUntil)}'de başlar"; return }
        val items = pool()
        // 0) kategoriden gelen sayfa: film mi dizi mi (bölüm listesi varsa dizi)
        items.firstOrNull { it.kind == "auto" && it.pageUrl.isNotBlank() && it.state != "done" && it.nextAt <= t }
            ?.let { w -> busy { detect(w) }; delay(gapSec * 1000L); return }
        // 1) sayfası bilinmeyen yapım: sitede ara
        items.firstOrNull { it.pageUrl.isBlank() && it.state != "done" && it.nextAt <= t }?.let { w -> busy { resolve(w) }; delay(gapSec * 1000L); return }
        // 2) dizinin bölüm listesi (ilk kez ya da 6 saatte bir: yeni bölüm eklenmiş olabilir)
        items.firstOrNull { it.kind == "tv" && it.pageUrl.isNotBlank() && it.state != "done" && it.nextAt <= t && (it.eps.isEmpty() || t - it.lastScan > RESCAN_MS) }
            ?.let { w -> busy { scan(w) }; delay(gapSec * 1000L); return }
        // 3) sıradaki parça: listedeki SIRAYA göre (kullanıcı ↑↓ ile ayarlar)
        val (w, e) = nextUnit(items, t) ?: return
        if (pcShare && !phoneArchive) {
            now = if (PcServer.configured) "🖥️ Bilgisayarda indirme ve archive.org doğrulaması bekleniyor"
                  else "🖥️ İndirmek için bilgisayarı Profil → Bilgisayar bağlantısı bölümünden bağla"
            return
        }
        if (e == null) busy { captureMovie(w) } else busy { captureEp(w, e) }
        delay(gapSec * 1000L)
    }

    // ---------------------------------------------------------------- 📱 cihazda indirme, doğrulama ve temizleme
    private suspend fun phoneStep(t: Long): Boolean {
        val tracked = items.flatMap { w ->
            listOf(Triple(w, null as WishEp?, w.pcJid)) +
                w.eps.map { e -> Triple(w, e as WishEp?, e.pcJid) }
        }.filter { it.third.startsWith("phone:") }
        for ((w, e, jid) in tracked) {
            val taskId = jid.removePrefix("phone:")
            val task = AdminStore(app).task(taskId)
            val target = e ?: w
            if (task == null) {
                if (e == null) { w.pcJid = ""; w.state = "retry"; w.nextAt = t + backoff(w.tries) }
                else { e.pcJid = ""; e.state = "retry"; e.nextAt = t + backoff(e.tries); refreshState(w) }
                continue
            }
            val name = label(w, e)
            when (task.status) {
                TaskStatus.DONE -> {
                    if (e == null) { w.state = "done"; w.pcJid = ""; w.note = "☁️ archive.org doğrulandı, geçici dosyalar silindi" }
                    else { e.state = "done"; e.pcJid = ""; e.note = "☁️ archive.org doğrulandı, geçici dosyalar silindi"; refreshState(w) }
                    noteDone()
                    if (w.state == "done") { titleFinished(); milestone("✅ $name arşivlendi") }
                    now = "✅ $name: arşivlendi ve telefon alanı temizlendi"
                }
                TaskStatus.FAILED, TaskStatus.SERVER_FAILED -> {
                    val next = if (e == null) w.nextAt else e.nextAt
                    if (next == 0L) {
                        if (e == null) w.nextAt = t + backoff(w.tries) else e.nextAt = t + backoff(e.tries)
                    } else if (next <= t) {
                        AdminRepo(app).retry(task)
                        if (e == null) w.nextAt = t + backoff(w.tries) else e.nextAt = t + backoff(e.tries)
                    }
                    now = "⚠ $name: ${task.message.orEmpty()} · aynı dosyalar korunuyor, yeniden denenecek"
                }
                else -> {
                    val label = when (task.status) {
                        TaskStatus.UPLOADING -> "archive.org'a yükleniyor"
                        TaskStatus.PROCESSING -> com.gflix.app.archive.PhoneProgress.label(task.phase)
                        else -> "indiriliyor / hazırlanıyor"
                    }
                    now = "📱 $name: $label · ${task.message.orEmpty()}"
                }
            }
        }
        save()
        // Includes tasks created just before the capture-to-wishlist handoff was interrupted.
        return AdminStore(app).current().tasks.any {
            it.tag == com.gflix.app.archive.PhoneArchiveWorker.TAG && it.status != TaskStatus.DONE
        }
    }

    // ---------------------------------------------------------------- 🖥️ bilgisayara verilen işler

    private fun pcUnits(): List<Triple<WishItem, WishEp?, String>> =
        items.filter { it.kind == "movie" && it.pcJid.isNotBlank() }.map { Triple(it, null, it.pcJid) } +
            items.flatMap { w -> w.eps.filter { it.pcJid.isNotBlank() }.map { Triple(w, it, it.pcJid) } }

    private suspend fun pcStep(t: Long) {
        val units = pcUnits()
        if (units.isNotEmpty()) {
            val st = PcServer.pccapStatus(units.map { it.third })
            for ((w, e, jid) in units) {
                val (state, note) = st[jid] ?: ("lost" to "")
                val lbl = label(w, e)
                when (state) {
                    "ok" -> { if (e != null) { e.state = "done"; e.pcJid = ""; e.note = "☁️ archive.org'a yüklendi ${hhmm(t)}" } else { w.state = "done"; w.pcJid = ""; w.note = "☁️ archive.org'a yüklendi ${hhmm(t)}" }
                        pcNow = "☁️ $lbl archive.org'a yüklendi" }
                    "black" -> {
                        val next = t + backoff(0)
                        if (e != null) { e.state = "retry"; e.pcJid = ""; e.nextAt = next; e.note = "🖥️ $note; ${hhmm(next)}'de tekrar · ${e.tries}. deneme" }
                        else { w.state = "retry"; w.pcJid = ""; w.nextAt = next; w.note = "🖥️ $note; ${hhmm(next)}'de tekrar · ${w.tries}. deneme" }
                        black = "⚠️ $lbl: bilgisayarda indirme veya archive.org yüklemesi başarısız. $note · ${hhmm(next)}'de yeniden denenecek."
                    }
                    "lost" -> { if (e != null) { e.state = "wait"; e.pcJid = "" } else { w.state = "wait"; w.pcJid = "" } }   // bilgisayar yeniden başladı
                    else -> pcNow = "☁️ $lbl: " + note.ifBlank { if (state == "work") "indiriliyor / yükleniyor" else "sırada" }
                }
                if (e != null) refreshState(w)
            }
            if (pcUnits().isNotEmpty()) return          // bilgisayar meşgul: aynı anda tek iş
        }
        if (!pcShare || !started) { pcNow = ""; return }
        val archiving = runCatching { PcServer.archiveJobs() }.getOrNull()
        if ((archiving?.get("enabled") as? JsonPrimitive)?.content != "true") {
            pcNow = "⚠ Bilgisayarda archive.org indirme ayarı kapalı veya sunucu güncel değil"
            return
        }
        if (oneByOne && restUntil > t) { pcNow = ""; return }
        val next = nextUnit(pool(), t)
        val movie = next?.takeIf { it.second == null }?.first
        val ep = next?.takeIf { it.second != null }?.let { it.first to it.second!! }
        when {
            movie != null -> {
                movie.pcJid = PcServer.pccapAdd(movie.pageUrl, hint(movie, null)); movie.state = "work"; movie.tries++
                movie.note = "🖥️ bilgisayarda indirilecek"; pcNow = "🖥️ ${movie.title}: bilgisayara verildi"
            }
            ep != null -> {
                val (w, e) = ep
                e.pcJid = PcServer.pccapAdd(e.url, hint(w, e)); e.state = "work"; e.tries++
                e.note = "🖥️ bilgisayarda indirilecek"; pcNow = "🖥️ ${label(w, e)}: bilgisayara verildi"
            }
            else -> pcNow = ""
        }
    }

    // ---------------------------------------------------------------- 🗓 Yakında → listeye (sadece elle: Stüdyo → Yakında → 🎯 Listeye ekle)

    /** Yakında'daki yapım listede mi (bitmemiş) */
    fun inList(t: com.gflix.app.data.Title): Boolean {
        val id = t.tmdbId ?: return false
        val tmdb = "${if (t.isSeries) "tv" else "movie"}/$id"
        return items.any { it.tmdb == tmdb && it.state != "done" }
    }

    /** Yakında'daki yapımı listeye al: film ise kendisi, diziyse eksik/duyurulan sezonları. Sonuç mesajı döner. */
    fun addSoon(t: com.gflix.app.data.Title): String {
        val id = t.tmdbId ?: return "Bu yapımın TMDB numarası yok; önce düzenleyip TMDB'yle eşleştir."
        val kind = if (t.isSeries) "tv" else "movie"
        val seasons = if (!t.isSeries || !com.gflix.app.data.Library.hasPlayable(t)) emptyList() else
            (t.tmdbSeasons.filter { ts -> (t.seasons.firstOrNull { it.number == ts.n }?.episodes?.count { it.stream != null } ?: 0) < ts.c }.map { it.n } +
                listOfNotNull(t.nextEpisode?.s?.takeIf { com.gflix.app.data.Library.announced(t) })).distinct()
        if (t.isSeries && com.gflix.app.data.Library.hasPlayable(t) && seasons.isEmpty()) return "Eksik bölüm yok."
        items.removeAll { it.tmdb == "$kind/$id" && it.state == "done" }
        add("$kind/$id", kind, t.title, t.originalTitle.orEmpty(), t.year, seasons.sorted())
        return "🎯 ${t.title} listeye eklendi" + (if (seasons.isNotEmpty()) " (sezon ${seasons.sorted().joinToString(", ")})" else "") + ". Sırayı İstek listesinden ayarla."
    }

    /** Sıradaki iş: listedeki sıraya göre ilk yapımın (filmse kendisi, diziyse sıradaki bölümü) */
    private fun nextUnit(list: List<WishItem>, t: Long): Pair<WishItem, WishEp?>? {
        for (w in list) {
            if (w.kind == "movie") {
                if (w.pageUrl.isNotBlank() && w.state in setOf("wait", "retry") && w.nextAt <= t) return w to null
            } else if (w.state != "done") {
                w.eps.firstOrNull { it.state in setOf("wait", "retry") && it.nextAt <= t && it.url.isNotBlank() }?.let { return w to it }
            }
        }
        return null
    }

    /** Sırada yukarı/aşağı (delta -1/+1) ya da en üste (Int.MIN_VALUE) */
    fun move(w: WishItem, delta: Int) {
        if (!worker) { cmd("move") { put("id", w.id); put("delta", delta) } }
        val i = items.indexOf(w)
        if (i < 0) return
        val j = if (delta == Int.MIN_VALUE) 0 else (i + delta).coerceIn(0, items.size - 1)
        if (i == j) return
        items.removeAt(i); items.add(j, w)
        save()
    }

    /** ▶ Başlat / ⏸ Duraklat: sırayı ayarlayınca başlatılır (istekler/Yakında yine listeye eklenir) */
    var started by mutableStateOf(false)
        private set
    fun changeStarted(on: Boolean) { if (!worker) { cmd("start") { put("on", on.toString()) }; started = on; return }; started = on; if (on) chipClosed = false; if (::prefs.isInitialized) prefs.edit().putBoolean("started", on).apply() }

    private fun norm(s: String) = s.lowercase(Locale("tr")).filter { it.isLetterOrDigit() }

    private suspend fun resolve(w: WishItem) {
        w.state = "work"; now = "🔎 ${w.title}: sitede aranıyor"; save()
        val wantKind = if (w.kind == "tv") "series" else "movie"
        val names = listOf(w.title, w.original).filter { it.isNotBlank() }.distinct()
        var hit: SiteResult? = null
        for (q in names) {
            val res = CaptureEngine.siteSearchFor(q)
            hit = res.filter { (it.kind == wantKind || it.kind !in setOf("series", "movie")) && !isHome(it.url) }.firstOrNull { r ->
                val n = norm(r.title)
                val nameOk = names.any { nm -> norm(nm).let { it.length >= 2 && n.contains(it) } }
                val years = Regex("\\b(19|20)\\d{2}\\b").findAll(r.title).map { it.value.toInt() }.toList()
                nameOk && (w.year == null || years.isEmpty() || years.any { kotlin.math.abs(it - w.year) <= 1 })
            }
            if (hit != null) break
        }
        if (hit == null) {
            w.state = "miss"; w.tries++; w.nextAt = System.currentTimeMillis() + MISS_WAIT
            w.note = "Sitede bulunamadı; ${hhmm(w.nextAt)}'de tekrar aranacak"
        } else {
            w.pageUrl = hit.url; w.state = "wait"; w.note = "Sayfa bulundu: ${hit.title.take(60)} (🔍 kontrol et)"; w.nextAt = 0
            w.auto = true; w.okd = false
        }
    }

    private suspend fun detect(w: WishItem) {
        w.kind = "tv"
        scan(w)
        if (w.eps.isEmpty()) { w.kind = "movie"; w.state = "wait"; w.tries = 0; w.nextAt = 0; w.note = "film (bölüm listesi yok)" }
    }

    private suspend fun scan(w: WishItem) {
        w.state = "work"; now = "📂 ${w.title}: bölümler okunuyor"; save()
        val list = CaptureEngine.siteEpisodesAt(w.pageUrl).filter { w.seasons.isEmpty() || it.season in w.seasons }
        w.lastScan = System.currentTimeMillis()
        if (list.isEmpty()) {
            w.state = "retry"; w.tries++; w.nextAt = System.currentTimeMillis() + backoff(w.tries)
            w.note = "Bölüm listesi okunamadı (siyah/kısıtlama olabilir); ${hhmm(w.nextAt)}'de tekrar"
            return
        }
        list.forEach { se ->
            val e = w.eps.firstOrNull { it.season == se.season && it.episode == se.episode }
            if (e == null) w.eps.add(WishEp(se.season, se.episode, se.url)) else e.url = se.url
        }
        w.eps.sortWith(compareBy({ it.season }, { it.episode }))
        // yayında ya da onayda olanlar tekrar alınmaz
        runCatching { PcServer.sourceStatus(w.eps.map { it.url }) }.getOrNull()?.let { st ->
            w.eps.forEach { e -> if (st[e.url] == "live" || st[e.url] == "pending") { e.state = "done"; e.note = if (st[e.url] == "live") "zaten yayında" else "onayda" } }
        }
        w.tries = 0; w.nextAt = 0; w.note = "${w.eps.size} bölüm bulundu"
        refreshState(w)
    }

    private fun refreshState(w: WishItem) {
        w.state = when {
            w.kind == "tv" && w.eps.isNotEmpty() && w.eps.all { it.state == "done" } -> "done"
            w.kind == "tv" && w.eps.any { it.state == "retry" } && w.eps.none { it.state == "wait" } -> "retry"
            w.kind == "tv" -> "wait"
            else -> w.state
        }
    }

    private suspend fun captureMovie(w: WishItem) {
        val st = runCatching { PcServer.sourceStatus(listOf(w.pageUrl)) }.getOrNull()?.get(w.pageUrl)
        if (st == "live" || st == "pending") { w.state = "done"; w.note = if (st == "live") "zaten yayında" else "onayda"; return }
        w.state = "work"; w.tries++; save()
        val r = CaptureEngine.captureAt(w.pageUrl, hint(w, null), w.closeQ, listStay) { live("▶ ${w.title}: $it") }
        if (r == "preempted") { w.state = "wait"; w.tries--; w.note = "Tazeleme için ara verildi; sırada"; return }
        if (r == "ok") {
            if (phoneArchive) {
                val id = CaptureEngine.lastPhoneJobId
                if (id == null) {
                    w.state = "retry"; w.nextAt = System.currentTimeMillis() + backoff(w.tries)
                    w.note = "Telefon indirme işi oluşturulamadı; yeniden denenecek"
                } else {
                    w.pcJid = "phone:$id"; w.state = "work"
                    w.note = "📱 İndirme → MKV → archive.org → doğrulama → temizleme"
                }
            } else {
                noteDone(); w.state = "done"; w.note = "Alındı ${hhmm(System.currentTimeMillis())} · ${got()} · Stüdyo → Onay bekleyenler"
                milestone("🎬 ${w.title} alındı · onay bekliyor")
                titleFinished()
            }
        }
        else { w.state = "retry"; w.nextAt = System.currentTimeMillis() + backoff(w.tries); w.note = "Video gelmedi (siyah ekran${why()}); ${hhmm(w.nextAt)}'de tekrar · ${w.tries}. deneme"; warnBlack(w.title) }
    }

    private fun why() = CaptureEngine.lastBlock.takeIf { it.isNotBlank() }?.let { " — site oynatıcıyı reddetti: $it" }.orEmpty()

    private var lastLive = 0L
    /** Anlık durum: ekranda + (5 sn'de bir) durum çubuğu/kilit ekranı kartında */
    private fun live(msg: String) {
        now = msg
        if (System.currentTimeMillis() - lastLive > 5000 && ::app.isInitialized) { lastLive = System.currentTimeMillis(); CaptureService.update(app, msg) }
    }

    private fun got() = CaptureEngine.lastPlayer + (if (CaptureEngine.lastHeight > 0) " ${CaptureEngine.lastHeight}p" else "")

    private suspend fun captureEp(w: WishItem, e: WishEp) {
        e.state = "work"; e.tries++; w.state = "work"; save()
        val r = CaptureEngine.captureAt(e.url, hint(w, e), w.closeQ, listStay) { live("▶ ${label(w, e)}: $it") }
        if (r == "preempted") { e.state = "wait"; e.tries--; e.note = "Tazeleme için ara verildi; sırada"; refreshState(w); return }
        if (r == "ok") {
            if (phoneArchive) {
                val id = CaptureEngine.lastPhoneJobId
                if (id != null) {
                    e.pcJid = "phone:$id"; e.state = "work"
                    e.note = "📱 İndirme → MKV → archive.org → doğrulama → temizleme"
                } else {
                    e.state = "retry"; e.nextAt = System.currentTimeMillis() + backoff(e.tries)
                    e.note = "İndirme kuyruğu oluşturulamadı"
                }
                refreshState(w)
                return
            }
            noteDone()
            if (CaptureEngine.lastPlayer == "Close" && CaptureEngine.lastHeight > w.closeQ) w.closeQ = CaptureEngine.lastHeight
            e.state = "done"; e.note = "Alındı ${hhmm(System.currentTimeMillis())} · ${got()}"
            val sea = w.eps.filter { it.season == e.season }
            if (sea.all { it.state == "done" }) {
                val got = sea.count { it.note.startsWith("Alındı") }
                milestone("📺 ${w.title} ${e.season}. sezon tamamlandı · $got bölüm onay bekliyor")
            }
        }
        else { e.state = "retry"; e.nextAt = System.currentTimeMillis() + backoff(e.tries); e.note = "Siyah ekran${why()}; ${hhmm(e.nextAt)}'de tekrar · ${e.tries}. deneme"; warnBlack(label(w, e)) }
        refreshState(w)
        if (r == "ok" && w.state == "done") { milestone("✅ ${w.title} tamamlandı (bütün sezonlar) · onay bekliyor"); titleFinished() }
    }
}

// ---------------------------------------------------------------------- ekran

private fun stateIcon(s: String) = when (s) {
    "done" -> "✅"; "work" -> "⚙️"; "retry" -> "🔁"; "miss" -> "❔"; else -> "⏳"
}

private fun stateText(s: String) = when (s) {
    "done" -> "alındı"; "work" -> "işlemde"; "retry" -> "tekrar denenecek"; "miss" -> "sitede bulunamadı"; else -> "sırada"
}

private data class TmdbHit(val tmdb: String, val kind: String, val title: String, val original: String, val year: Int?)

@Composable
fun WishListDialog(onDismiss: () -> Unit) {
    val bars = hostBars()
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        CaptureEngine.listOpen = true
        onDispose { CaptureEngine.listOpen = false }
    }
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TmdbHit>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<TmdbHit?>(null) }
    val open = remember { mutableStateListOf<String>() }
    var addPage by remember { mutableStateOf("") }
    var catUrl by remember { mutableStateOf("") }
    var catBusy by remember { mutableStateOf(false) }
    var catMsg by remember { mutableStateOf<String?>(null) }
    var catFound by remember { mutableStateOf<Pair<List<SiteResult>, String>?>(null) }
    fun readCat(u: String) {
        if (!WishList.worker) {
            // Panel: kategoriyi TV kutusu okur, sonuç durumla gelir
            catBusy = true; catMsg = "⏳ ${WishList.remoteDevice.ifBlank { "TV kutusu" }} okuyor…"
            WishList.askCategory(u)
            scope.launch {
                repeat(450) {
                    delay(2_000)
                    val c = WishList.remoteCat
                    if (c != null && c.first == u) {
                        catBusy = false
                        if (c.second.isEmpty()) catMsg = "Bu sayfada yapım bulunamadı (kategori/liste sayfası mı?)" else { catMsg = null; catFound = c.second to c.third }
                        return@launch
                    }
                }
                catBusy = false; catMsg = "TV kutusundan cevap gelmedi (açık ve bilgisayara bağlı mı?)"
            }
            return
        }
        catBusy = true; catMsg = "⏳ okunuyor (liste o an bir iş yapıyorsa bitince)…"
        scope.launch {
            val r = runCatching { WishList.readCategory(u) }.getOrNull()
            catBusy = false
            if (r == null || r.first.isEmpty()) catMsg = "Bu sayfada yapım bulunamadı (kategori/liste sayfası mı?)" else { catMsg = null; catFound = r }
        }
    }
    catFound?.let { (f, next) ->
        CategoryPickDialog(f, next.takeIf { it.startsWith("http") }, onNext = { n -> catFound = null; catUrl = n; readCat(n) }) { catFound = null }
    }
    var showSugs by remember { mutableStateOf(false) }
    if (showSugs) SuggestDialog { showSugs = false }
    var showBrowser by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var pageFor by remember { mutableStateOf<String?>(null) }         // sayfası eşleştirilen yapım
    var pageText by remember { mutableStateOf("") }
    var siteHits by remember { mutableStateOf<List<SiteResult>>(emptyList()) }
    var siteMsg by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val rev = WishList.rev

    val context = androidx.compose.ui.platform.LocalContext.current
    fun search() {
        if (q.trim().length < 2) return
        err = null; pick = null
        scope.launch {
            runCatching {
                val result = if (PcServer.configured) runCatching { PcServer.tmdbSearch(q.trim()) }.getOrNull() else null
                if (result != null) {
                    (result["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                        val x = e as? JsonObject ?: return@mapNotNull null
                        fun s(k: String) = (x[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }.orEmpty()
                        TmdbHit(s("tmdb"), s("kind"), s("title"), s("original"), s("year").toIntOrNull())
                    }
                } else {
                    // Telefon bilgisayardan bağımsız arayabilsin; anahtar bir kez Stüdyo Ayarları'nda saklanır.
                    val tmdbKey = AdminStore(context).current().tmdbKey.orEmpty()
                    require(tmdbKey.isNotBlank()) {
                        "TMDB için Stüdyo Ayarları'na bir kez TMDB API anahtarı kaydet veya bilgisayara bağlan."
                    }
                    val client = Tmdb(tmdbKey, okhttp3.OkHttpClient())
                    listOf("movie", "tv").flatMap { kind ->
                        client.search(kind, q.trim(), null).take(8).map {
                            TmdbHit("$kind/${it.id}", kind, it.title, it.originalTitle, it.year)
                        }
                    }
                }
            }.onSuccess { found ->
                hits = found
                if (hits.isEmpty()) err = "TMDB'de sonuç yok"
            }.onFailure { err = it.message ?: "TMDB araması başarısız" }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(bars).imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🎯 İstek listesi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (!WishList.worker) {
                        val age = WishList.remoteAge
                        Text(when {
                            age == null -> if (WishList.phoneArchive) "📱 İş bu telefonda · bilgisayar gerekmiyor" else "📺 Yakalama cihazı yok (TV kutusunda G-Flix açık ve bilgisayara bağlı olmalı)"
                            age < 30 -> "📺 İş ${WishList.remoteDevice.ifBlank { "TV kutusunda" }} · canlı"
                            else -> "⚠ ${WishList.remoteDevice.ifBlank { "TV kutusu" }}ndan ${age / 60} dk'dır haber yok"
                        }, color = if (age != null && age < 30) GColors.Match else GColors.TextSecondary, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Kapat", color = GColors.TextSecondary) }
            }
            // Tek kaydırılabilir sayfa: küçük ekranda da her şey sığar
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item(key = "intro") {
                    Column {
                        Text(if (WishList.phoneArchive)
                            "Bu telefon sırayla indirir, ses ve altyazıları tek MKV'de toplar; arşiv doğrulamasından sonra dosyaları silip sıradakine geçer."
                            else if (WishList.pcShare)
                            "TMDB'den film veya dizi seç; sayfasını bulur. Bilgisayar izinli videoyu indirir, archive.org'a yükler, doğrular ve kataloğa ekler. Başarısız olursa sıraya alıp tekrar dener."
                            else "TMDB'den seç; sayfayı sitede kendisi bulur. Arka planda bağlantıyı yakalar; gerektiğinde tekrar dener.",
                            color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedTextField(q, { q = it }, singleLine = true, colors = gTextFieldColors(), placeholder = { Text("Film ya da dizi adı (TMDB)") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { search() }) { Text("Ara", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                        err?.let { Text(it, color = GColors.TextSecondary, fontSize = 12.sp) }
                        val p = pick
                        if (p != null) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1C)).padding(10.dp)) {
                                Text((if (p.kind == "tv") "📺 " else "🎬 ") + p.title + (p.year?.let { " ($it)" } ?: ""), color = Color.White, fontWeight = FontWeight.Bold)
                                if (p.kind == "tv") Text("Bütün sezonlar sıraya girer (yeni bölümler de)", color = GColors.TextSecondary, fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 4.dp))
                                OutlinedTextField(addPage, { addPage = it }, singleLine = true, colors = gTextFieldColors(),
                                    placeholder = { Text("Sitedeki sayfa linki (boş: uygulama sitede arar)", fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { pick = null }) { Text("Vazgeç", color = GColors.TextSecondary) }
                                    TextButton(onClick = {
                                        WishList.add(p.tmdb, p.kind, p.title, p.original, p.year, emptyList(), addPage)
                                        pick = null; hits = emptyList(); q = ""; addPage = ""
                                    }) { Text("Listeye ekle", color = Color.White, fontWeight = FontWeight.Bold) }
                                }
                            }
                        } else hits.take(12).forEach { h ->
                            Text((if (h.kind == "tv") "📺 " else "🎬 ") + h.title + (h.year?.let { " ($it)" } ?: "") +
                                (if (h.original.isNotBlank() && h.original != h.title) " · ${h.original}" else ""),
                                color = Color.White, fontSize = 14.sp, maxLines = 2,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    pick = h
                                }.padding(vertical = 7.dp))
                        }
                    }
                }
                item(key = "cat") {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1C)).padding(10.dp)) {
                        Text("📂 Kategoriden ekle", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Sitedeki kategori/liste sayfasının linkini yapıştır (Marvel, DC, Türk filmleri…). Bütün sayfaları sırayla okur (aşağı kaydırdıkça yüklenenler dahil); o an bir iş varsa bitince.",
                            color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(catUrl, { catUrl = it.trim() }, singleLine = true, colors = gTextFieldColors(),
                                placeholder = { Text("https://… kategori sayfası", fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                            TextButton(enabled = catUrl.startsWith("http") && !catBusy, onClick = { readCat(catUrl) }) {
                                Text(if (catBusy) "⏳" else "Oku", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (WishList.cats.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                            WishList.cats.forEach { c ->
                                val label = runCatching { java.net.URI(c).path.trim('/').substringAfterLast('/').ifBlank { c } }.getOrDefault(c).take(28)
                                Text(label, color = Color.White, fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF333333))
                                        .clickable(enabled = !catBusy) { catUrl = c; readCat(c) }.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                        (WishList.catProgress.ifBlank { null } ?: catMsg)?.let { Text(it, color = GColors.TextSecondary, fontSize = 12.sp) }
                    }
                }
                item(key = "sugs") {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2433))
                        .clickable { showSugs = true }.padding(10.dp)) {
                        Text("💡 Öneriler (${WishList.sugs.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(if (WishList.cats.isEmpty()) "Önce yukarıdan bir kategori okut: öneriler okuttuğun kategorilerden gelir."
                            else "Okuttuğun ${WishList.cats.size} kategoriden katalogda olmayanlar; ${WishList.sugEveryH} saatte bir yenilenir. Dokun: ＋ Ekle ya da Geç.",
                            color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
                item(key = "clearall") {
                    var ask by remember { mutableStateOf(false) }
                    if (WishList.items.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { ask = true }) { Text("🗑 Hepsini sil", color = Color(0xFFFF6B6B), fontSize = 13.sp) }
                    }
                    if (ask) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { ask = false }, containerColor = Color(0xFF1F1F1F),
                        title = { Text("Listedeki bütün işler silinsin mi?", color = Color.White) },
                        text = { Text("Alınmış olanlar etkilenmez (onayda/yayında kalır). İstersen işaretlediklerini tut.", color = GColors.TextSecondary) },
                        confirmButton = { TextButton(onClick = { WishList.clearAll(); ask = false }) { Text("Hepsini sil", color = Color(0xFFFF6B6B)) } },
                        dismissButton = { TextButton(onClick = { ask = false }) { Text("Vazgeç", color = Color.White) } },
                    )
                }
                item(key = "status") {
                    Column(Modifier.padding(top = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(WishList.summary(), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            androidx.compose.material3.Button(onClick = { WishList.changeStarted(!WishList.started) },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = if (WishList.started) Color(0xFF333333) else GColors.Red, contentColor = Color.White)) {
                                Text(if (WishList.started) "⏸ Duraklat" else "▶ Başlat", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        if (!WishList.started) Text("Sırayı ↑ ↓ ile ayarla, sonra ▶ Başlat. Liste yukarıdan aşağı işler.", color = GColors.TextSecondary, fontSize = 11.sp)
                        if (WishList.now.isNotBlank()) Text(WishList.now, color = GColors.Match, fontSize = 12.sp)
                        if (!WishList.phoneArchive && WishList.pcNow.isNotBlank()) Text(WishList.pcNow, color = GColors.Match, fontSize = 12.sp)
                        if (WishList.phoneArchive) PhoneQueueProgress()
                        if (WishList.phoneArchive) Text("📱 Bu telefon sırayla indirir ve arşivler. Stüdyo Ayarları'nda arşiv anahtarları ve hak onayı gerekli.", color = GColors.TextSecondary, fontSize = 12.sp)
                        else if (!PcServer.configured) Text("⚠ Bilgisayar bağlı değil; liste bilgisayar bağlanınca çalışır.", color = GColors.TextSecondary, fontSize = 12.sp)
                        else if (!CaptureEngine.hasSite()) Text("⚠ Önce Sayfadan Yakala'da kaynak siteyi aç; liste o siteyi kullanır. archive.org için ayrıca bilgisayarda arşivleme etkin olmalı.", color = GColors.TextSecondary, fontSize = 12.sp)
                    }
                }
                // 👁 Arka plandaki tarayıcı: liste çalışırken görünür; oynatıcıya kendin de dokunabilirsin (gizlenebilir)
                item(key = "browser") {
                    val ce = CaptureEngine
                    Column {
                        if (ce.web != null && showBrowser) {
                            DisposableEffect(Unit) { ce.previewing = true; onDispose { ce.endPreview() } }
                            androidx.compose.runtime.key(ce.hostGen) {
                                androidx.compose.ui.viewinterop.AndroidView(
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)),
                                    factory = { ctx -> ce.attach(ctx) },
                                )
                            }
                        }
                        var cm by remember { mutableStateOf<String?>(null) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ce.web != null) TextButton(onClick = { showBrowser = !showBrowser }) {
                                Text(if (showBrowser) "👁 Gizle" else "👁 Tarayıcı", color = Color.White, fontSize = 12.sp)
                            }
                            TextButton(onClick = { CaptureEngine.clearBrowserData { cm = it + if (CaptureEngine.wishActive) " Sayfa baştan açılıyor." else "" } }) {
                                Text("🧹 Temizle", color = Color.White, fontSize = 12.sp)
                            }
                            TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "⚙️ Kapat" else "⚙️ Ayarlar", color = Color.White, fontSize = 12.sp) }
                        }
                        cm?.let { Text(it, color = GColors.TextSecondary, fontSize = 11.sp) }
                        if (ce.web != null && showBrowser) Text("Video başlamazsa oynatıcıya kendin dokun; yakalanınca liste devam eder.", color = GColors.TextMuted, fontSize = 11.sp)
                        if (WishList.black.isNotBlank()) BlackBanner()
                    }
                }
                if (showSettings) item(key = "settings") { WishSettings() }
                item(key = "review") {
                    Column {
                        val review = WishList.toReview()
                        if (review.isNotEmpty()) {
                            val uri = androidx.compose.ui.platform.LocalUriHandler.current
                            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B2433)).padding(8.dp)) {
                                Text("🔍 Kendi eşleştirdikleri — kontrol et (${review.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                review.take(6).forEach { w ->
                                    val tmdbUrl = "https://www.themoviedb.org/" + w.tmdb
                                    Text("${if (w.kind == "tv") "📺" else "🎬"} ${w.title}${w.year?.let { " ($it)" } ?: ""}", color = Color.White, fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 6.dp))
                                    Text("TMDB: $tmdbUrl", color = Color(0xFF7FB2FF), fontSize = 11.sp, modifier = Modifier.clickable { runCatching { uri.openUri(tmdbUrl) } })
                                    Text("Site: ${w.pageUrl}", color = Color(0xFF7FB2FF), fontSize = 11.sp, maxLines = 2, modifier = Modifier.clickable { runCatching { uri.openUri(w.pageUrl) } })
                                    Row {
                                        TextButton(onClick = { WishList.confirmPage(w) }) { Text("✓ Doğru", color = GColors.Match, fontSize = 12.sp) }
                                        TextButton(onClick = { pageFor = w.id; pageText = ""; siteHits = emptyList(); siteMsg = "Doğru sayfanın linkini yapıştır ya da sitede ara." }) {
                                            Text("✗ Yanlış, değiştir", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }
                                if (review.size > 6) Text("+${review.size - 6} tane daha (listede '🔍' işaretli)", color = GColors.TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
                    val etaMap = WishList.etas()
                    items(WishList.items.toList(), key = { it.id }) { w ->
                        val ex = w.id in open
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF161616)).padding(10.dp)) {
                            val counts = if (w.kind == "tv" && w.eps.isNotEmpty())
                                " · ${w.eps.count { it.state == "done" }}/${w.eps.size} alındı" +
                                    w.eps.count { it.state == "retry" }.takeIf { it > 0 }?.let { ", $it tekrar denenecek" }.orEmpty() else ""
                            Text("${stateIcon(w.state)} " + (if (w.newEp) "🆕 " else if (w.soon) "🗓 " else "") + (when (w.kind) { "tv" -> "📺 "; "auto" -> "❔ "; else -> "🎬 " }) + w.title + (w.year?.let { " ($it)" } ?: "") +
                                (if (w.seasons.isNotEmpty()) " · S" + w.seasons.joinToString(",") else ""),
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                modifier = Modifier.clickable { if (ex) open.remove(w.id) else open.add(w.id) })
                            Text(stateText(w.state) + counts + (if (w.note.isNotBlank()) " — ${w.note}" else "") +
                                (etaMap[w.id]?.let { " · ⏱ tahmini " + SimpleDateFormat("d MMM HH:mm", Locale("tr")).format(Date(it)) } ?: ""),
                                color = GColors.TextSecondary, fontSize = 12.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { WishList.move(w, -1) }, modifier = Modifier.width(44.dp)) { Text("↑", color = Color.White, fontSize = 16.sp) }
                            TextButton(onClick = { WishList.move(w, 1) }, modifier = Modifier.width(44.dp)) { Text("↓", color = Color.White, fontSize = 16.sp) }
                            if (w.state != "done") TextButton(onClick = { WishList.retryNow(w) }) { Text("Şimdi dene", color = Color.White, fontSize = 12.sp) }
                                if (w.kind == "tv") TextButton(onClick = { if (ex) open.remove(w.id) else open.add(w.id) }) {
                                    Text(if (ex) "Bölümleri gizle" else "Bölümler", color = Color.White, fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    if (pageFor == w.id) pageFor = null else { pageFor = w.id; pageText = w.pageUrl; siteHits = emptyList(); siteMsg = null }
                                }) { Text("🔗 Sayfa", color = Color.White, fontSize = 12.sp) }
                                TextButton(onClick = { WishList.remove(w) }) { Text("Kaldır", color = GColors.TextSecondary, fontSize = 12.sp) }
                            }
                            if (w.pageUrl.isNotBlank()) Text("Sayfa: " + w.pageUrl.substringAfter("://").take(70), color = GColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                            if (pageFor == w.id) Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(pageText, { pageText = it }, singleLine = true, colors = gTextFieldColors(),
                                        placeholder = { Text("Sitedeki sayfa linki", fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        if (WishList.setPage(w, pageText)) pageFor = null
                                        else siteMsg = "⚠ Bu, sitenin ana sayfası; filmin/dizinin kendi sayfasının linkini yapıştır (ör. …/kara-dul-izle-…/)."
                                    }) { Text("Kaydet", color = Color.White, fontWeight = FontWeight.Bold) }
                                }
                                if (WishList.worker) TextButton(onClick = {
                                    siteMsg = "🔎 Sitede aranıyor…"; siteHits = emptyList()
                                    scope.launch {
                                        var res: List<SiteResult>? = null
                                        for (name in listOf(w.title, w.original).filter { it.isNotBlank() }.distinct()) {
                                            res = WishList.searchSite(name) ?: break
                                            if (res.isNotEmpty()) break
                                        }
                                        siteHits = res.orEmpty().filterNot { WishList.isHome(it.url) }
                                        siteMsg = when {
                                            res == null -> "Liste şu an bir sayfada çalışıyor ya da site açılmamış; biraz sonra tekrar dene."
                                            res.isEmpty() -> "Sitede sonuç yok; linki kendin yapıştır."
                                            else -> "Doğru sayfaya dokun:"
                                        }
                                    }
                                }) { Text("🔎 Sitede ara ve seç", color = Color.White, fontSize = 12.sp) }
                                siteMsg?.let { Text(it, color = GColors.TextSecondary, fontSize = 12.sp) }
                                siteHits.forEach { r ->
                                    Text((if (r.kind == "series") "📺 " else if (r.kind == "movie") "🎬 " else "▫️ ") + r.title, color = Color.White, fontSize = 13.sp, maxLines = 2,
                                        modifier = Modifier.fillMaxWidth().clickable { WishList.setPage(w, r.url); pageFor = null; siteHits = emptyList() }.padding(vertical = 6.dp))
                                }
                            }
                            if (ex) w.eps.forEach { e ->
                                Text("${stateIcon(e.state)} S%02dE%02d · ${stateText(e.state)}".format(e.season, e.episode) + (if (e.note.isNotBlank()) " — ${e.note}" else ""),
                                    color = if (e.state == "done") GColors.Match else Color.White, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
            }
        }
    }
}


/** Siyah ekran uyarısı + kullanıcının kendi basacağı 🧹 Temizle */
@Composable
fun BlackBanner() {
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A1F1F)).padding(8.dp)) {
        val transferError = WishList.black.startsWith("⚠️")
        Text(WishList.black, color = Color.White, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!transferError) TextButton(onClick = {
                CaptureEngine.clearBrowserData { msg = it; WishList.cleared() }
            }) { Text("🧹 Temizle", color = Color.White, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { WishList.cleared() }) { Text("Kapat", color = GColors.TextSecondary) }
        }
        msg?.let { Text(it, color = GColors.TextSecondary, fontSize = 11.sp) }
    }
}


/** İstek listesi ayarları (dar ekranda alt alta) */
@Composable
private fun WishSettings() {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF141414)).padding(10.dp)) {
        PrefetchSettings()
        SettingSwitch("📺 İşi bu cihaz yapsın (yakalama cihazı)",
            "Açık: sayfaları bu cihaz açar, linkleri bu cihaz yeniler (TV kutusu). Kapalı: bu cihaz sadece izler ve yönetir; iş yakalama cihazında.",
            WishList.worker) { WishList.changeWorker(it) }
        SettingSwitch("📱 Telefon indir + archive.org'a yükle", "Tek tek indirir; bütün ses ve altyazıları MKV'ye birleştirir. Arşiv ve katalog doğrulanınca dosyaları siler.", WishList.phoneArchive) { WishList.changePhoneArchive(it) }
        if (!WishList.phoneArchive) SettingSwitch("🖥️ Bilgisayarda indir + archive.org'a yükle", "Eski bilgisayar modu", WishList.pcShare) { WishList.changePcShare(it) }
        SettingSwitch("📱 Arka planda da çalışsın", "Uygulama arkadayken de dener (pil kısıtlamasını kapat ki Android durdurmasın)", WishList.bgRun) { WishList.changeBgRun(it) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        TextButton(onClick = {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("🔋 Pil kısıtlamasını kapat (G-Flix → Kısıtlama yok)", color = Color.White, fontSize = 12.sp) }
        val ovOk = remember { mutableStateOf(CaptureEngine.canOverlay(ctx)) }
        TextButton(onClick = {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) {
            Text(if (ovOk.value) "🪟 Arkada küçük pencere: izin verildi ✓" else "🪟 Arkada küçük pencere için izin ver (önerilir: arkadayken video başlasın)",
                color = if (ovOk.value) GColors.Match else Color.White, fontSize = 12.sp)
        }
        SettingSwitch("👁 Arkadaki pencereyi göster", "Kapalıyken pencere görünmez (yine çalışır), sadece üstteki çip görünür; açıkken taşınabilir küçük pencere", WishList.showWindow) { WishList.changeShowWindow(it) }
        SettingSwitch("🔒 Kilit ekranında da çalışsın", "Telefon kilitliyken iş başlayınca ekranı karanlık açar, kilidin üzerinde çalışır, bitince kapanır (küçük pencere izni gerekir)", WishList.lockRun) { WishList.changeLockRun(it) }
        SettingSwitch("🌙 Gece modu", "Liste bitene kadar ekran kapanmaz (şarja tak, uygulamayı açık bırak)", WishList.night) { WishList.changeNight(it) }
        SettingSwitch("1️⃣ Tek tek bitir", "Sıradaki yapım bitmeden (dizide bütün sezonlar) yenisine geçmez; bitince aşağıdaki mola kadar bekler", WishList.oneByOne) { WishList.changeOneByOne(it) }
        var tg by remember { mutableStateOf(WishList.titleGapMin.toString()) }
        var se by remember { mutableStateOf(WishList.sugEveryH.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(tg, { v -> tg = v.filter { it.isDigit() }.take(3); tg.toIntOrNull()?.let { WishList.setTitleGap(it) } }, singleLine = true,
                colors = gTextFieldColors(), label = { Text("Yapımlar arası mola (dk)", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.weight(1f))
            Text("  ", color = Color.White)
            OutlinedTextField(se, { v -> se = v.filter { it.isDigit() }.take(3); se.toIntOrNull()?.let { WishList.setSugEvery(it) } }, singleLine = true,
                colors = gTextFieldColors(), label = { Text("Öneri yenileme (saat)", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        var r by remember { mutableStateOf(WishList.retryMin.toString()) }
        var g by remember { mutableStateOf(WishList.gapSec.toString()) }
        var ls by remember { mutableStateOf(WishList.listStay.toString()) }
        OutlinedTextField(ls, { v -> ls = v.filter { it.isDigit() }.take(3); ls.toIntOrNull()?.let { WishList.changeListStay(it) } }, singleLine = true,
            colors = gTextFieldColors(), label = { Text("Yakalandıktan sonra videoda kal (sn)", fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(r, { v -> r = v.filter { it.isDigit() }.take(3); r.toIntOrNull()?.let { WishList.setRetry(it) } }, singleLine = true,
                colors = gTextFieldColors(), label = { Text("Siyah ekranda tekrar (dk)", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.weight(1f))
            Text("  ", color = Color.White)
            OutlinedTextField(g, { v -> g = v.filter { it.isDigit() }.take(4); g.toIntOrNull()?.let { WishList.setGap(it) } }, singleLine = true,
                colors = gTextFieldColors(), label = { Text("İşler arası (sn)", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SettingSwitch(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (on) GColors.Match else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = GColors.TextSecondary, fontSize = 11.sp)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = set)
    }
}


/** 📂 Kategoriden seç: Sayfadan Yakala'da açık olan kategori/liste sayfasındaki yapımlar; seçilenler listenin sonuna girer
 *  (dizide bütün sezonlar). TMDB eşleştirmesi alındıktan sonra Onay bekleyenler'de kullanıcıda. */
@Composable
fun CategoryPickDialog(found: List<SiteResult>, next: String?, onNext: (String) -> Unit, onDismiss: () -> Unit) {
    val bars = hostBars()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val raw by ctx.appContainer.catalogRepository.catalog.collectAsState()
    val have = remember(raw) {
        raw?.titles.orEmpty().flatMap { listOfNotNull(it.title, it.originalTitle) }.map { JobGroups.normalize(it) }.filter { it.isNotBlank() }.toSet()
    }
    fun inCatalog(r: SiteResult): Boolean {
        val n = JobGroups.normalize(r.title.replace(Regex("(?i)\\b(izle|türkçe dublaj|altyazılı|full hd|hd)\\b"), "").replace(Regex("\\(?\\b(19|20)\\d{2}\\b\\)?"), ""))
        return n.isNotBlank() && n in have
    }
    fun inList(r: SiteResult) = WishList.items.any { it.pageUrl == r.url && it.state != "done" }
    val picked = remember { mutableStateListOf<String>() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(bars).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📂 Sayfadaki yapımlar (${found.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Kapat", color = GColors.TextSecondary) }
            }
            Text("Seçtiklerin listenin sonuna girer (diziyse bütün sezonlar). TMDB eşleştirmesi alındıktan sonra Onay bekleyenler'de sende. " +
                "Eklediklerin listede, okunan sayfa burada kalır.", color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            val fresh = found.filter { !inCatalog(it) && !inList(it) }
            Row(Modifier.padding(vertical = 4.dp)) {
                TextButton(onClick = { picked.clear(); picked.addAll(fresh.map { it.url }) }) { Text("Katalogda olmayanların hepsi (${fresh.size})", color = Color.White, fontSize = 13.sp) }
                TextButton(onClick = { picked.clear() }) { Text("Hiçbiri", color = GColors.TextSecondary, fontSize = 13.sp) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(found, key = { it.url }) { r ->
                    val cat = inCatalog(r); val lst = inList(r); val on = r.url in picked
                    Text((if (on) "☑ " else "☐ ") + (when (r.kind) { "series" -> "📺 "; "movie" -> "🎬 "; else -> "" }) + r.title +
                        (if (cat) "  · ✓ katalogda" else "") + (if (lst) "  · 🎯 listede" else ""),
                        color = if (cat || lst) GColors.TextSecondary else Color.White, fontSize = 14.sp, maxLines = 2,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !lst) { if (on) picked.remove(r.url) else picked.add(r.url) }.padding(vertical = 7.dp))
                }
            }
            androidx.compose.material3.Button(enabled = picked.isNotEmpty(), onClick = {
                var n = 0
                found.filter { it.url in picked }.forEach { if (WishList.addPage(it.url, it.title, it.kind)) n++ }
                WishList.saveNow()
                android.widget.Toast.makeText(ctx, "🎯 $n yapım listenin sonuna eklendi", android.widget.Toast.LENGTH_LONG).show()
                picked.clear()
            }, modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text("Listeye ekle (${picked.size})", fontWeight = FontWeight.Bold)
            }
            if (next != null) TextButton(onClick = { onNext(next) }, modifier = Modifier.fillMaxWidth()) {
                Text("Kalan sayfaları oku ›", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}


/** 💡 Öneriler: okutulan kategorilerden katalogda olmayanlar. ＋ Ekle → listenin sonuna (sıra gelince alınır, onaya gider), Geç → bir daha önerilmez */
@Composable
fun SuggestDialog(onDismiss: () -> Unit) {
    val bars = hostBars()
    @Suppress("UNUSED_VARIABLE") val rev = WishList.rev
    var filter by remember { mutableStateOf("all") }
    var catF by remember { mutableStateOf("") }
    val all = WishList.sugs.toList()
    val catsIn = all.map { it.cat }.filter { it.isNotBlank() }.distinct()
    val shown = all.filter { (filter == "all" || it.kind == filter) && (catF.isBlank() || it.cat == catF) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(bars).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💡 Öneriler (${all.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { WishList.refreshSuggestionsSoon() }) { Text("🔄 Yenile", color = Color.White, fontSize = 12.sp) }
                TextButton(onClick = onDismiss) { Text("Kapat", color = GColors.TextSecondary) }
            }
            Text("Okuttuğun kategorilerde olup katalogda olmayanlar. ＋ Ekle: listenin sonuna girer, sırası gelince tek tek alınıp onaya gelir. Geç: bir daha önerilmez.",
                color = GColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                listOf("all" to "Hepsi", "movie" to "🎬 Film", "series" to "📺 Dizi").forEach { (k, l) ->
                    Text(l, color = if (filter == k) Color.Black else Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (filter == k) Color.White else Color(0xFF333333))
                            .clickable { filter = k }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                catsIn.forEach { c ->
                    Text(c, color = if (catF == c) Color.Black else Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (catF == c) Color.White else Color(0xFF333333))
                            .clickable { catF = if (catF == c) "" else c }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            if (shown.isEmpty()) Text(if (WishList.cats.isEmpty()) "Henüz öneri yok: İstek listesinde bir kategori okut." else "Şimdilik öneri yok; kategoriler ${WishList.sugEveryH} saatte bir yeniden okunur.",
                color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.url }) { sg ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF161616)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((when (sg.kind) { "series" -> "📺 "; "movie" -> "🎬 "; else -> "❔ " }) + sg.title, color = Color.White, fontSize = 14.sp, maxLines = 2)
                            if (sg.cat.isNotBlank()) Text(sg.cat, color = GColors.TextMuted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { WishList.addSuggestion(sg.url) }) { Text("＋ Ekle", color = GColors.Match, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        TextButton(onClick = { WishList.skipSuggestion(sg.url) }) { Text("Geç", color = GColors.TextSecondary, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}


/** Sistem çubukları (üst saat çubuğu, alt gezinme çubuğu): tam ekran pencerenin İÇİNDE sıfır görünür, bu yüzden
 *  pencereyi açan ekranda ölçülür ve pencereye boşluk olarak verilir (FileBrowser'daki gibi). */
@Composable
internal fun hostBars(): androidx.compose.foundation.layout.PaddingValues {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val top = with(d) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottom = with(d) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return androidx.compose.foundation.layout.PaddingValues(top = top, bottom = maxOf(bottom, 16.dp))
}


/** 🌙 Gece önceden yenileme: ayarlar bilgisayarda durur (işi yakalama cihazı yapar) */
@Composable
private fun PrefetchSettings() {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf("03:00") }
    var to by remember { mutableStateOf("07:00") }
    var max by remember { mutableStateOf("25") }
    var info by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { PcServer.prefetchGet() }.onSuccess { o ->
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
            on = s("on") != "false"; from = s("from").ifBlank { "03:00" }; to = s("to").ifBlank { "07:00" }; max = s("max").ifBlank { "25" }
            info = "Son gece: ${s("checked").ifBlank { "0" }} yapıma bakıldı, ${s("tonight").ifBlank { "0" }} tanesinin linki yenilendi"
            loaded = true
        }.onFailure { info = "Bilgisayara ulaşılamadı" }
    }
    fun save() {
        if (!loaded) return
        val m = max.toIntOrNull()?.coerceIn(1, 200) ?: return
        if (!Regex("^\\d{1,2}:\\d{2}$").matches(from) || !Regex("^\\d{1,2}:\\d{2}$").matches(to)) return
        scope.launch { runCatching { PcServer.prefetchSet(on, from, to, m) } }
    }
    SettingSwitch("🌙 Gece önceden yenileme",
        "İzlenen dizilerin sıradaki bölümleri, son açılan filmler ve yeni eklenenler: linki ölmüşse gece yenilenir, izlerken bekleme olmaz",
        on) { on = it; save() }
    if (on) Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(from, { from = it.take(5); save() }, singleLine = true, colors = gTextFieldColors(),
            label = { Text("Başlangıç", fontSize = 10.sp) }, modifier = Modifier.weight(1f))
        Text(" – ", color = Color.White)
        OutlinedTextField(to, { to = it.take(5); save() }, singleLine = true, colors = gTextFieldColors(),
            label = { Text("Bitiş", fontSize = 10.sp) }, modifier = Modifier.weight(1f))
        Text("  ", color = Color.White)
        OutlinedTextField(max, { v -> max = v.filter { it.isDigit() }.take(3); save() }, singleLine = true, colors = gTextFieldColors(),
            label = { Text("Gecede en çok", fontSize = 10.sp) }, modifier = Modifier.weight(1f))
    }
    if (info.isNotBlank()) Text(info, color = GColors.TextSecondary, fontSize = 11.sp)
}
