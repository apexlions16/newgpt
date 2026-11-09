package com.gflix.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

sealed interface CatalogStatus {
    data object Idle : CatalogStatus
    data object Loading : CatalogStatus
    data object Ready : CatalogStatus
    data class Error(val message: String) : CatalogStatus
}

class CatalogRepository(context: Context, private val client: OkHttpClient) {

    private val cacheFile = File(context.filesDir, "catalog.json")
    private val appContext = context.applicationContext

    private val _catalog = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = _catalog

    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle)
    val status: StateFlow<CatalogStatus> = _status

    private var retryJob: kotlinx.coroutines.Job? = null
    private fun retryAt(untilMs: Long) {
        if (retryJob?.isActive == true) return
        retryJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay((untilMs - System.currentTimeMillis()).coerceAtLeast(1000) + 2000)
            refresh()
        }
    }

    fun title(id: String): Title? = _catalog.value?.titles?.firstOrNull { it.id == id }

    /** Önbellekteki kataloğu senkron yükler (oynatıcı için). */
    fun loadCachedBlocking(): Catalog? {
        if (_catalog.value == null && cacheFile.exists()) {
            runCatching { AppJson.decodeFromString<Catalog>(cacheFile.readText()) }
                .onSuccess { _catalog.value = it }
        }
        return _catalog.value
    }

    suspend fun loadCached() = withContext(Dispatchers.IO) { loadCachedBlocking() }

    /** Yönetici telefonunda katalog değişti (archive.org'a yazılmadan önce de hemen görünsün). */
    fun setLocal(catalog: Catalog, text: String) {
        runCatching { cacheFile.writeText(text) }
        _catalog.value = catalog
        _status.value = CatalogStatus.Ready
    }

    /**
     * archive.org'daki catalog.json. Yönetici telefonunda kendi kopyası daha yeniyse o kullanılır
     * (archive.org yeni yazılan dosyayı birkaç dakika gecikmeyle gösterebilir).
     */
    private fun refreshArchive(): Boolean {
        val local = com.gflix.app.archive.ArchiveCatalog.file(appContext).takeIf { it.exists() }?.let { f ->
            runCatching { f.readText().let { it to AppJson.decodeFromString<Catalog>(it) } }.getOrNull()
        }
        return try {
            val url = com.gflix.app.archive.ArchiveSource.catalogUrl() + "?t=" + System.currentTimeMillis() / 60_000
            val remote = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> resp.body?.string().orEmpty().let { it to AppJson.decodeFromString<Catalog>(it) }
                    resp.code == 404 -> null
                    // olmayan item'ın adresi 503 döner: katalog item'ı hiç açılmamışsa "bulunamadı"
                    com.gflix.app.archive.ArchiveSource.itemExists(client, com.gflix.app.archive.ArchiveSource.catalogItem()) == false -> null
                    else -> throw java.io.IOException("archive.org hatası (HTTP ${resp.code})")
                }
            }
            val pick = listOfNotNull(remote, local).maxByOrNull { it.second.generatedAt.orEmpty() }
            if (pick == null) {
                if (_catalog.value == null) _catalog.value = Catalog()
                _status.value = CatalogStatus.Error("Katalog bulunamadı: archive.org'da \"${com.gflix.app.archive.ArchiveSource.catalogItem()}\" henüz yok. İlk içerik yüklenince oluşur.")
                return false
            }
            cacheFile.writeText(pick.first)
            _catalog.value = pick.second
            _status.value = CatalogStatus.Ready
            runCatching { Reminders.sync(appContext, pick.second) }
            true
        } catch (e: Exception) {
            if (local != null) {
                _catalog.value = local.second
                _status.value = CatalogStatus.Ready
                return true
            }
            if (_catalog.value == null) loadCachedBlocking() ?: run { _catalog.value = Catalog() }
            _status.value = CatalogStatus.Error("archive.org'a ulaşılamadı. İnternet bağlantını kontrol et.")
            false
        }
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        _status.value = CatalogStatus.Loading
        if (com.gflix.app.archive.ArchiveSource.configured) return@withContext refreshArchive()
        if (PcServer.configured) {
            // Yeni düzen: katalog evdeki bilgisayardan (kaynağı bilinen film/bölümler oynatılabilir); GitHub'a istek yok
            try {
                val text = PcServer.catalogText()
                val parsed = AppJson.decodeFromString<Catalog>(text)
                cacheFile.writeText(text)
                _catalog.value = parsed
                _status.value = CatalogStatus.Ready
                runCatching { Reminders.sync(appContext, parsed) }
                return@withContext true
            } catch (e: Exception) {
                if (_catalog.value == null) loadCachedBlocking() ?: run { _catalog.value = Catalog() }
                _status.value = CatalogStatus.Error(e.message ?: "Bilgisayara ulaşılamadı.")
                return@withContext false
            }
        }
        try {
            val request = Request.Builder().url(GitHub.contentsUrl("catalog/catalog.json")).build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val text = resp.body?.string().orEmpty()
                        val parsed = AppJson.decodeFromString<Catalog>(text)
                        cacheFile.writeText(text)
                        _catalog.value = parsed
                        _status.value = CatalogStatus.Ready
                        runCatching { Reminders.sync(appContext, parsed) }
                        true
                    }
                    else -> {
                        if (_catalog.value == null) _catalog.value = Catalog()
                        _status.value = CatalogStatus.Error(
                            when (resp.code) {
                                401 -> "GitHub erişim anahtarı geçersiz ya da süresi dolmuş."
                                403, 429 -> "Şu an çok yoğun. Katalog birazdan kendiliğinden yenilenecek."
                                404 -> "Katalog evdeki bilgisayarda. Profilim → Bilgisayar bağlantısı'nı kur (bilgisayarda: gflix-yayin anahtar)."
                                else -> "Sunucu hatası (HTTP ${resp.code})."
                            }
                        )
                        false
                    }
                }
            }
        } catch (e: RateLimit.Blocked) {
            // Yoğunluk: önbellekteki katalog kullanılır, sıra gelince kendiliğinden yenilenir
            if (_catalog.value == null) loadCachedBlocking() ?: run { _catalog.value = Catalog() }
            _status.value = CatalogStatus.Error("Şu an çok yoğun. Katalog birazdan kendiliğinden yenilenecek.")
            retryAt(e.untilMs)
            false
        } catch (e: Exception) {
            if (_catalog.value == null) _catalog.value = Catalog()
            _status.value = CatalogStatus.Error("Bağlantı kurulamadı. İnternet bağlantını kontrol et.")
            false
        }
    }
}
