package com.gflix.app.player

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.gflix.app.appContainer
import com.gflix.app.data.Library
import com.gflix.app.data.Profile
import com.gflix.app.data.ProfileData
import com.gflix.app.data.Stream
import com.gflix.app.data.Title

/** Fragman seçimi (dil, 4K) ve oynatma kaynağı. */
@OptIn(UnstableApi::class)
object TrailerMedia {

    fun all(title: Title): List<Stream> = title.trailers.ifEmpty { listOfNotNull(title.trailer) }

    /** Profilin diline uygun fragman: seçilen ses dili > başlık dili (en ise İngilizce) > Türkçe > ilk. */
    fun preferredLang(profile: Profile?, data: ProfileData?): String =
        data?.audioLang ?: if (profile?.titleLang == "en") "en" else "tr"

    fun pick(title: Title, lang: String?): Stream? {
        val list = all(title)
        return list.firstOrNull { it.lang == lang } ?: list.firstOrNull { it.lang == "tr" } ?: title.trailer ?: list.firstOrNull()
    }

    fun label(s: Stream): String = when (s.lang) {
        null, "", "und" -> "Fragman"
        else -> Library.languageName(s.lang)
    }

    fun shortLabel(s: Stream): String = when (s.lang) {
        null, "", "und" -> "—"
        else -> s.lang.uppercase()
    }

    /** Aynı fragmanı tanımak için (Compose remember anahtarı). */
    fun key(s: Stream): String = s.master.ifEmpty { s.youtube ?: s.direct?.video?.parts?.firstOrNull().orEmpty() } + "|" + s.lang

    /** Bu telefon 4K VP9 çözebiliyor mu (donanım ya da yazılım çözücü)? */
    val supportsUhd: Boolean by lazy {
        runCatching {
            val f = MediaFormat.createVideoFormat(MimeTypes.VIDEO_VP9, 3840, 2160)
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
        }.getOrDefault(false)
    }

    fun uses4k(s: Stream, allowUhd: Boolean): Boolean = allowUhd && s.uhd != null && supportsUhd

    /**
     * Eski fragmanlar HLS (master.m3u8), yeniler doğrudan MP4/MKV (parça parça). 4K sürümü, telefon destekliyorsa
     * ve [allowUhd] ise (Wi-Fi) seçilir; yoksa 1080p H.264 sürümü oynar.
     */
    fun mediaSource(context: Context, s: Stream, allowUhd: Boolean): MediaSource {
        val factory = DefaultMediaSourceFactory(PartsDataSource.Factory(OkHttpDataSource.Factory(context.appContainer.httpClient)))
        val item = when {
            // Anlık yayın: bilgisayar YouTube'dan o an çözer (dosya tutulmaz)
            s.youtube != null -> MediaItem.Builder()
                .setUri(com.gflix.app.data.PcServer.liveUrl("/yt/${s.youtube}/master.m3u8").orEmpty())
                .setMimeType(MimeTypes.APPLICATION_M3U8).build()
            uses4k(s, allowUhd) -> MediaItem.fromUri(PartsDataSource.uriFor(s.uhd!!))
            s.direct != null -> MediaItem.fromUri(PartsDataSource.uriFor(s.direct.video))
            else -> MediaItem.Builder().setUri(s.master).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        }
        return factory.createMediaSource(item)
    }
}
