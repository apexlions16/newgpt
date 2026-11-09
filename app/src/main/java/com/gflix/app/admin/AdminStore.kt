package com.gflix.app.admin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gflix.app.data.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.adminDataStore by preferencesDataStore(name = "gflix_admin")

/** GitHub Actions'taki tools/staged_ingest.py'nin okuduğu iş tanımı (job.json). */
@Serializable
data class JobManifest(
    val version: Int = 1,
    val kind: String,              // movie | episode | trailer
    val title: String,
    val itemId: String? = null,
    val tmdb: Int? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val fileName: String,
    val size: Long,
    val parts: List<String>,
    val audio: List<TrackSel>? = null,
    val subs: List<SubSel>? = null,
    val subFiles: List<SubFileSel> = emptyList(),
    val audioFiles: List<AudioFileSel> = emptyList(),
    /** Dil başına ayrı video (Türkçe dublaj, İngilizce …): bu videonun dili; null ise yapımın tek videosu */
    val versionLang: String? = null,
)

@Serializable
data class TrackSel(val index: Int, val lang: String)

@Serializable
data class SubSel(val index: Int, val lang: String, val forced: Boolean = false)

@Serializable
data class SubFileSel(val asset: String, val lang: String, val forced: Boolean = false)

/** Videodan ayrı yüklenen ses dosyası (oynatıcı videoyla birlikte çalar) */
@Serializable
data class AudioFileSel(val asset: String, val lang: String, val default: Boolean = false)

@Serializable
data class UploadFile(val uri: String, val assetName: String, val offset: Long, val length: Long, val md5: String? = null)

object TaskStatus {
    const val QUEUED = "queued"          // telefonda sırada
    const val UPLOADING = "uploading"
    const val PROCESSING = "processing"  // GitHub'a teslim edildi
    const val DONE = "done"
    const val FAILED = "failed"          // telefondaki yükleme başarısız
    const val SERVER_FAILED = "server_failed"
}

@Serializable
data class UploadTask(
    val id: String,
    val tag: String,
    val title: String,
    val kind: String,
    val createdAt: Long,
    val manifest: JobManifest,
    val files: List<UploadFile>,
    val releaseId: Long? = null,
    val status: String = TaskStatus.QUEUED,
    val message: String? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** archive.org'a giden işlerde dosyaların yükleneceği item (yoksa eski GitHub işi) */
    val archiveItem: String? = null,
    /** archive.org'un işlemesi beklenmeye başlandığı an (ms); işlenince kataloğa eklenir */
    val processingSince: Long = 0,
) {
    val display: String
        get() = when (kind) {
            "episode" -> "$title S%02dE%02d".format(manifest.season ?: 0, manifest.episode ?: 0)
            "trailer" -> "$title (Fragman)"
            "theme" -> "$title (Tema müziği)"
            else -> title
        }
}

/** Linkten toplu ekleme işi (GitHub'da "linkjob-…" Release'i). */
@Serializable
data class LinkJobRef(
    val tag: String,
    val releaseId: Long,
    val title: String,
    val createdAt: Long,
    /** Telefon indirsin modu: dosyalar telefonda indirilip GitHub'a aktarılır */
    val phoneMode: Boolean = false,
    val recipe: String = "",
    val localState: String? = null,      // downloading | failed | published
    val localDone: Long = 0,
    val localTotal: Long = 0,
    val localSpeed: Long = 0,
    val localMessage: String? = null,
    /** Bilgisayardaki son durum (done | partial | error | cancelled | gone): bitmiş işin durumu bir daha sorulmaz */
    val finalState: String? = null,
    val finalTitle: String? = null,
    val finishedAt: Long = 0,
    /** Listeden gizlendi (sadece görünüm: GitHub'daki iş ve dosyalar durur). Başarılı işler biraz sonra kendiliğinden gizlenir. */
    val hidden: Boolean = false,
)

@Serializable
data class AdminState(
    val writeToken: String? = null,
    val tmdbKey: String? = null,
    val parallel: Int = 3,
    val tasks: List<UploadTask> = emptyList(),
    val linkJobs: List<LinkJobRef> = emptyList(),
    /** archive.org anahtarları (Android Keystore ile şifreli) ve item öneki */
    val iaAccess: String? = null,
    val iaSecret: String? = null,
    val iaPrefix: String? = null,
    /** Explicit permission to publish these non-DRM files publicly on Internet Archive. */
    val archiveRightsConfirmed: Boolean = false,
    /** Kataloğun archive.org'a son yazılışı ("✓ 12:03" ya da hata) */
    val iaCatalogNote: String? = null,
) {
    /** Yeni düzen: içerikler archive.org'da */
    val archive: Boolean get() = !iaAccess.isNullOrBlank() && !iaSecret.isNullOrBlank() && !iaPrefix.isNullOrBlank()
    val ready: Boolean get() = !writeToken.isNullOrBlank() || archive

    /** Şifresi çözülmüş archive.org anahtarları (erişim, gizli) */
    fun archiveKeys(): Pair<String, String>? {
        if (!archive) return null
        val a = com.gflix.app.archive.SecretBox.open(iaAccess) ?: return null
        val s = com.gflix.app.archive.SecretBox.open(iaSecret) ?: return null
        return a to s
    }
}

class AdminStore(private val context: Context) {
    private val key = stringPreferencesKey("state")

    val state: Flow<AdminState> = context.adminDataStore.data.map { decode(it[key]) }

    suspend fun current(): AdminState = state.first()

    suspend fun update(transform: (AdminState) -> AdminState) {
        context.adminDataStore.edit { p -> p[key] = AppJson.encodeToString(AdminState.serializer(), transform(decode(p[key]))) }
    }

    suspend fun updateTask(id: String, transform: (UploadTask) -> UploadTask) = update { s ->
        s.copy(tasks = s.tasks.map { if (it.id == id) transform(it) else it })
    }

    suspend fun updateLinkJob(tag: String, transform: (LinkJobRef) -> LinkJobRef) = update { s ->
        s.copy(linkJobs = s.linkJobs.map { if (it.tag == tag) transform(it) else it })
    }

    suspend fun task(id: String): UploadTask? = current().tasks.firstOrNull { it.id == id }

    private fun decode(raw: String?): AdminState =
        raw?.let { runCatching { AppJson.decodeFromString(AdminState.serializer(), it) }.getOrNull() } ?: AdminState()
}
