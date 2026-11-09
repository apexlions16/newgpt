package com.gflix.app.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.gflix.app.data.Library

data class TrackOption(
    val label: String,
    val language: String?,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val selected: Boolean,
)

object TrackHelper {

    fun audio(tracks: Tracks, originalLanguage: String?): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                var label = if (f.language != null) Library.languageName(f.language) else (f.label ?: "Ses ${options.size + 1}")
                if (Library.sameLanguage(f.language, originalLanguage)) label += " [Orijinal]"
                options += TrackOption(label, f.language, group, i, group.isTrackSelected(i))
            }
        }
        // Aynı dilde birden fazla iz varsa (ör. videonun kendi İngilizce sesi + ayrı yüklenen İngilizce ses) sonuncusu, yani ayrı dosya tercih edilir
        return options.groupBy { it.language ?: it.label }.map { (_, same) -> same.firstOrNull { it.selected } ?: same.last() }
    }

    fun subtitles(tracks: Tracks): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                if (f.sampleMimeType == MimeTypes.APPLICATION_CEA608 || f.sampleMimeType == MimeTypes.APPLICATION_CEA708) continue
                if (f.language == null && f.label == null) continue
                val forced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                val label = (f.label ?: Library.languageName(f.language)) + if (forced && f.label == null) " (Zorunlu)" else ""
                options += TrackOption(label, f.language, group, i, group.isTrackSelected(i))
            }
        }
        val anySelected = options.any { it.selected }
        return listOf(TrackOption("Kapalı", null, null, -1, !anySelected)) + options
    }
}
