package com.gflix.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

// ---------- Katalog (GitHub'daki catalog/catalog.json) ----------

@Serializable
data class Catalog(
    val version: Int = 1,
    val generatedAt: String? = null,
    val titles: List<Title> = emptyList(),
    /** Arşiv özeti (katalog her üretildiğinde hesaplanır) */
    val stats: CatalogStats? = null,
)

@Serializable
data class CatalogStats(
    val movies: Int = 0,
    val series: Int = 0,
    val seasons: Int = 0,
    val episodes: Int = 0,
    val trailers: Int = 0,
    val comingSoon: Int = 0,
    val contentBytes: Long = 0,
    val trailerBytes: Long = 0,
    val totalBytes: Long = 0,
    val minutes: Long = 0,
) {
    companion object {
        /** Ondalık birim (1 GB = 1000 MB), telefonun depolama ekranı gibi */
        fun size(n: Long): String = when {
            n >= 1_000_000_000_000 -> String.format(TR, "%.2f TB", n / 1e12)
            n >= 1_000_000_000 -> String.format(TR, "%.1f GB", n / 1e9)
            else -> String.format(TR, "%.0f MB", n / 1e6)
        }
    }
}

@Serializable
data class Title(
    val id: String,
    val type: String = "movie", // "movie" | "series"
    val tmdbId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    val overview: String = "",
    val tagline: String? = null,
    val year: Int? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
    /** TMDB günlük popülerlik puanı (İlk 10 sıralaması) */
    val popularity: Double? = null,
    val maturity: Int? = null,
    val certification: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val originalLanguage: String? = null,
    /** Eklenme zamanı (epoch saniye) */
    val added: Long? = null,
    /** Son güncellenme / yeni bölüm zamanı (epoch saniye) */
    val updated: Long? = null,
    val stream: Stream? = null,
    val seasons: List<Season> = emptyList(),
    /** Türkçe / İngilizce / orijinal adlar */
    val names: Map<String, String> = emptyMap(),
    /** Alternatif kapaklar, arka planlar ve logolar */
    val posters: List<Art> = emptyList(),
    val backdrops: List<Art> = emptyList(),
    val logos: List<Art> = emptyList(),
    /** Kullanıcının yüklediği fragman (tercih edilen; eski kayıtlarda tek fragman) */
    val trailer: Stream? = null,
    /** Dillere göre fragmanlar (Türkçe, İngilizce…) */
    val trailers: List<Stream> = emptyList(),
    /** Yayın zamanı (epoch saniye): bu saate kadar "Yakında"da durur, oynatılamaz; saati gelince kendiliğinden açılır. */
    val releaseAt: Long? = null,
    /** Yönetici "Yakında"ya ekledi (videosu henüz yok) */
    val comingSoon: Boolean = false,
    /** Tema müziği (yapım sayfasında arkada çalar; ~75 sn AAC) */
    val theme: PartedFile? = null,
    /** Yöneticinin işaretlediği kalite / Dolby rozetleri */
    val badges: Badges? = null,
    /** Dizinin yayın planı (bölüm grupları / yeni sezon) ve isteğe bağlı bilgilendirme metni */
    val releasePlan: List<PlanEntry> = emptyList(),
    val releaseNote: String? = null,
    /** Stüdyo notları (📢 Duyuru …); [TitleNote.until] dolunca kaybolur, hide=true ise yapım da gizlenir */
    val notes: List<TitleNote> = emptyList(),
    /** TMDB'deki sezonlar (n: sezon, c: bölüm sayısı) ve sıradaki bölüm — eksik bölümler "Yakında"da görünür */
    val tmdbSeasons: List<TmdbSeason> = emptyList(),
    val nextEpisode: NextEpisodeInfo? = null,
    /** Seçilen kapakta (poster) ad yazılı mı; yazısızsa kartta logo üstüne konur (uygulamada hesaplanır) */
    @kotlinx.serialization.Transient val posterHasText: Boolean = true,
) {
    val isSeries: Boolean get() = type == "series"
    val lastChange: Long get() = updated ?: added ?: 0L
}

@Serializable
data class TitleNote(val text: String = "", val until: Long? = null, val hide: Boolean? = null)

@Serializable
data class TmdbSeason(val n: Int, val c: Int)

@Serializable
data class NextEpisodeInfo(val s: Int, val e: Int, val at: Long? = null, val tbd: Boolean? = null)

/**
 * Yayın planı satırı: [season]. sezonun [from]–[to]. bölümleri [at] anında (to yoksa sezonun geri kalanı).
 * [whenComplete]: aralığın hepsi yüklenince birlikte açılır ([at] varsa en erken o saatte); katalog [have]/[total]/[complete] yazar.
 */
@Serializable
data class PlanEntry(
    val season: Int, val from: Int = 1, val to: Int? = null, val at: Long = 0,
    val whenComplete: Boolean = false, val have: Int = 0, val total: Int = 0, val complete: Boolean = false,
) {
    /** "Hepsi yüklenince" bekleyen aralık (henüz eksik bölüm var) */
    val waiting: Boolean get() = whenComplete && !complete
}

/** Kalite etiketi (HD, HD+, Full HD, 4K, … HDR) ve Dolby Vision / Dolby Atmos işaretleri. */
@Serializable
data class Badges(
    val quality: String? = null,
    val dolbyVision: Boolean = false,
    val dolbyAtmos: Boolean = false,
) {
    companion object {
        val QUALITIES = listOf("HD", "HD+", "Full HD", "4K", "HD+ HDR", "Full HD HDR", "4K HDR")
    }
}

@Serializable
data class Art(
    val url: String,
    val lang: String? = null,
    val pinned: Boolean = false,
)

@Serializable
data class Season(
    val number: Int,
    val name: String? = null,
    val overview: String? = null,
    val poster: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Episode(
    val number: Int,
    val title: String? = null,
    val titleEn: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val runtime: Int? = null,
    val added: Long? = null,
    val stream: Stream? = null,
    /** Bölümün yayın zamanı (epoch saniye); gelene kadar bölüm listesinde görünmez. */
    val releaseAt: Long? = null,
)

@Serializable
data class Stream(
    /** HLS ana oynatma listesi (master.m3u8) adresi; "direkt" içeriklerde boş */
    val master: String = "",
    val audio: List<String> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    /** Saniye */
    val duration: Double? = null,
    /** Dosyaların durduğu Release etiketi */
    val release: String? = null,
    /** Birleştirilmeden yüklenen içerik: video, sesler ve altyazılar ayrı dosyalar */
    val direct: DirectMedia? = null,
    /** Fragmanın dili (tr, en, und) */
    val lang: String? = null,
    /** Fragmanın 4K (VP9) sürümü; destekleyen telefonda oynatılır */
    val uhd: PartedFile? = null,
    /**
     * Dil başına ayrı video (Türkçe dublajlı, İngilizce …): her biri kendi sesiyle senkron, birleştirilmeden yüklenir.
     * Ses menüsünde dil değişince oynatıcı aynı saniyeden öbür videoya geçer; altyazılar ortaktır.
     */
    val versions: List<StreamVersion> = emptyList(),
    /** Linkten anlık yayın: bilgisayar (gflix-yayin) basılınca siteden taze linki çözer; adres oynatırken alınır */
    val live: Boolean = false,
    /** Fragman/yapım YouTube'dan: bilgisayar o an çözüp yayınlar (/yt/<kimlik>/master.m3u8); dosya tutulmaz */
    val youtube: String? = null,
)

@Serializable
data class StreamVersion(
    val lang: String,
    val label: String? = null,
    val video: PartedFile,
)

/** Hızlı yükleme: MP4 video + ayrı ses dosyaları (m4a/aac) + VTT altyazılar, oynatıcı aynı anda çalar. */
@Serializable
data class DirectMedia(
    val video: PartedFile,
    val audio: List<DirectAudio> = emptyList(),
    /** Videonun kendi içindeki seslerin dilleri (etiketsizse), sırayla */
    val videoAudio: List<String> = emptyList(),
)

/** 2 GB sınırı yüzünden parçalara bölünmüş tek dosya: parçalar uç uca eklenince asıl dosya olur. */
@Serializable
data class PartedFile(
    val parts: List<String>,
    val sizes: List<Long>,
)

@Serializable
data class DirectAudio(
    val lang: String,
    val label: String? = null,
    val default: Boolean = false,
    val parts: List<String>,
    val sizes: List<Long>,
)

@Serializable
data class Subtitle(
    val lang: String,
    val label: String? = null,
    val url: String,
    val forced: Boolean = false,
)

// ---------- Kullanıcı verisi (cihazda) ----------

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val avatar: Int = 0,
    val kids: Boolean = false,
    /** Başlık dili: tr | en | original | mixed */
    val titleLang: String = TitleLang.TR,
    /** Kapaklar: rotate (her gün farklı) | fixed */
    val artMode: String = ArtMode.ROTATE,
    val autoplayTrailers: Boolean = true,
    val trailersWifiOnly: Boolean = false,
    /** Yapım sayfasında tema müziği (Plex/Emby'deki gibi) */
    val themeMusic: Boolean = true,
)

object TitleLang {
    const val TR = "tr"
    const val EN = "en"
    const val ORIGINAL = "original"
    const val MIXED = "mixed"
}

object ArtMode {
    const val ROTATE = "rotate"
    const val FIXED = "fixed"
}

@Serializable
data class WatchProgress(
    val titleId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isFinished: Boolean get() = durationMs > 0 && (positionMs >= durationMs * 0.95 || durationMs - positionMs < 30_000)
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

@Serializable
data class ProfileData(
    val myList: List<String> = emptyList(),
    val liked: List<String> = emptyList(),
    val progress: Map<String, WatchProgress> = emptyMap(),
    val audioLang: String? = null,
    val subtitleLang: String? = null,
)

@Serializable
data class UserState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val data: Map<String, ProfileData> = emptyMap(),
    val token: String? = null,
    val setupSkipped: Boolean = false,
) {
    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileId }
    val activeData: ProfileData get() = dataFor(activeProfileId)
    fun dataFor(id: String?): ProfileData = id?.let { data[it] } ?: ProfileData()
}
