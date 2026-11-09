package com.gflix.app.archive

import java.io.File

/** One video rendition and every audio/subtitle track go into the same lossless MKV. */
object PhoneMux {
    fun arguments(input: String, headers: String, subtitles: List<File>, target: File): Array<String> {
        val args = mutableListOf("-hide_banner", "-nostdin", "-y", "-loglevel", "warning",
            "-rw_timeout", "30000000")
        if (headers.isNotEmpty()) args += listOf("-headers", headers)
        args += listOf("-i", input)
        subtitles.forEach { args += listOf("-i", it.absolutePath) }
        args += listOf("-map", "0:v:0", "-map", "0:a?", "-map", "0:s?")
        subtitles.indices.forEach { args += listOf("-map", "${it + 1}:0") }
        args += listOf("-c:v", "copy", "-c:a", "copy", "-c:s", "srt",
            "-max_muxing_queue_size", "2048", "-f", "matroska", target.absolutePath)
        return args.toTypedArray()
    }
}
