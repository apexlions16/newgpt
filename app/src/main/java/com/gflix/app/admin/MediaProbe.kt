package com.gflix.app.admin

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.text.Normalizer
import java.util.Locale

data class ProbeAudio(val index: Int, var lang: String, val codec: String, val channels: Int, val title: String, var include: Boolean = true) {
    fun describe(): String = listOfNotNull(
        codec.ifBlank { null },
        when (channels) { 0 -> null; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$channels kanal" },
        title.ifBlank { null },
    ).joinToString(" · ").ifBlank { "Ses #$index" }
}

data class ProbeSub(
    val index: Int, var lang: String, val codec: String, var forced: Boolean, val title: String,
    val bitmap: Boolean, var include: Boolean, val attachment: String? = null,
    val fileUri: String? = null, val fileName: String? = null,
) {
    fun describe(): String = when {
        fileName != null -> fileName
        attachment != null -> "MKV eki: $attachment"
        else -> "Gömülü #$index ($codec)" + (if (title.isNotBlank()) " · $title" else "") + (if (bitmap) " — resim, desteklenmiyor" else "")
    }
}

data class ProbeResult(val name: String, val size: Long, val audio: MutableList<ProbeAudio>, val subs: MutableList<ProbeSub>, val container: String)

object Langs {
    val choices = listOf("tr" to "Türkçe", "en" to "İngilizce", "de" to "Almanca", "fr" to "Fransızca", "es" to "İspanyolca",
        "it" to "İtalyanca", "ja" to "Japonca", "ko" to "Korece", "ru" to "Rusça", "ar" to "Arapça", "und" to "Belirsiz")
    private val aliases = mapOf("tur" to "tr", "turkish" to "tr", "turkce" to "tr", "türkçe" to "tr", "eng" to "en", "english" to "en",
        "ger" to "de", "deu" to "de", "fre" to "fr", "fra" to "fr", "spa" to "es", "ita" to "it", "jpn" to "ja", "kor" to "ko", "rus" to "ru", "ara" to "ar")

    fun norm(v: String?): String {
        val s = v?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_') ?: return "und"
        if (s.isEmpty()) return "und"
        return aliases[s] ?: if (s.length == 2) s else if (s == "und") "und" else s.take(2)
    }

    fun label(code: String) = choices.firstOrNull { it.first == code }?.second ?: code

    fun guessFromName(name: String): String {
        val tokens = name.substringBeforeLast('.').lowercase(Locale("tr")).split(Regex("[^a-zçğıöşü]+")).reversed()
        for (t in tokens) {
            if (t.isEmpty()) continue
            val n = norm(t)
            if (choices.any { it.first == n && n != "und" } && (t.length <= 3 || t in listOf("turkish", "english", "turkce", "türkçe"))) return n
        }
        return "und"
    }
}

object MediaProbe {

    private val TEXT_SUB = setOf("S_TEXT/UTF8", "S_TEXT/ASCII", "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA", "S_TEXT/WEBVTT", "D_WEBVTT/SUBTITLES", "D_WEBVTT/CAPTIONS")

    fun fileInfo(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "video"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
        return name to size
    }

    fun probe(context: Context, uri: Uri): ProbeResult {
        val (name, size) = fileInfo(context, uri)
        val lower = name.lowercase(Locale.ROOT)
        val result = if (lower.endsWith(".mkv") || lower.endsWith(".mka") || lower.endsWith(".webm") || isMatroska(context, uri)) {
            runCatching { probeMatroska(context, uri, name, size) }.getOrElse { probeExtractor(context, uri, name, size) }
        } else probeExtractor(context, uri, name, size)
        autoSelect(result)
        return result
    }

    /** Videonun içine yazılmış başlık (ör. "Dizi - 1. Sezon 3. Bölüm"); dosya adında bölüm kodu yoksa buna bakılır. */
    fun embeddedTitle(context: Context, uri: Uri): String? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try { r.setDataSource(context, uri); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } }
        finally { runCatching { r.release() } }
    }.getOrNull()

    /** Her dilden ilk ses; her dil/zorunlu türünden bir altyazı (masaüstü sürümüyle aynı kural). */
    fun autoSelect(r: ProbeResult) {
        val known = r.audio.any { it.lang != "und" }
        val seen = mutableSetOf<String>()
        r.audio.forEach { a ->
            a.include = !(known && a.lang == "und") && seen.add(a.lang)
        }
        if (r.audio.none { it.include }) r.audio.firstOrNull()?.include = true
        if (r.subs.none { it.lang == "tr" && !it.bitmap }) r.subs.firstOrNull { it.lang == "und" && !it.bitmap && !it.forced }?.lang = "tr"
        val seenSubs = mutableSetOf<Pair<String, Boolean>>()
        r.subs.forEach { s -> s.include = !s.bitmap && seenSubs.add(s.lang to s.forced) }
    }

    private fun isMatroska(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(4); s.read(b) == 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()
        } ?: false
    }.getOrDefault(false)

    private fun probeExtractor(context: Context, uri: Uri, name: String, size: Long): ProbeResult {
        val ex = MediaExtractor()
        val audio = mutableListOf<ProbeAudio>()
        val subs = mutableListOf<ProbeSub>()
        try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                val lang = Langs.norm(if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null)
                when {
                    mime.startsWith("audio/") -> audio += ProbeAudio(
                        i, lang, mime.removePrefix("audio/").removePrefix("mp4a-latm").ifBlank { "AAC" }.uppercase(Locale.ROOT),
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0, "",
                    )
                    mime.startsWith("text/") || mime.contains("subrip") || mime.contains("ssa") -> subs += ProbeSub(i, lang, mime, false, "", false, true)
                }
            }
        } finally {
            ex.release()
        }
        return ProbeResult(name, size, audio, subs, "other")
    }

    // ---------------------------------------------------------------- Matroska (EBML)

    private class Reader(val ch: FileChannel) {
        val size = ch.size()
        private val one = ByteBuffer.allocate(8)

        fun bytes(pos: Long, n: Int): ByteArray {
            val buf = ByteBuffer.allocate(n)
            var p = pos
            while (buf.hasRemaining()) {
                val r = ch.read(buf, p)
                if (r <= 0) break
                p += r
            }
            return buf.array().copyOf(buf.position())
        }

        /** (değer, uzunluk); keepMarker=false ise boyut, true ise kimlik. Bilinmeyen boyut = -1 */
        fun vint(pos: Long, keepMarker: Boolean): Pair<Long, Int> {
            one.clear(); one.limit(1)
            if (ch.read(one, pos) <= 0) throw java.io.EOFException()
            val b = one.get(0).toInt() and 0xFF
            var len = 1
            var mask = 0x80
            while (len <= 8 && b and mask == 0) { mask = mask shr 1; len++ }
            if (len > 8) throw IllegalStateException("EBML")
            var v = (if (keepMarker) b else b and (mask - 1)).toLong()
            val rest = bytes(pos + 1, len - 1)
            for (x in rest) v = (v shl 8) or (x.toLong() and 0xFF)
            if (!keepMarker && v == (1L shl (7 * len)) - 1) return -1L to len
            return v to len
        }

        fun uint(pos: Long, n: Int): Long = bytes(pos, n).fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
        fun str(pos: Long, n: Int): String = String(bytes(pos, minOf(n, 4096)), Charsets.UTF_8).trimEnd('\u0000')

        /** Alt elemanlar: (kimlik, veri başlangıcı, boyut) */
        fun children(start: Long, end: Long): Sequence<Triple<Long, Long, Long>> = sequence {
            var p = start
            while (p < end) {
                val (id, a) = runCatching { vint(p, true) }.getOrNull() ?: break
                val (sz, b) = runCatching { vint(p + a, false) }.getOrNull() ?: break
                val data = p + a + b
                yield(Triple(id, data, sz))
                if (sz < 0) break
                p = data + sz
            }
        }
    }

    private data class MkTrack(var number: Long = 0, var type: Long = 0, var codec: String = "", var lang: String? = null, var name: String = "",
                               var forced: Boolean = false, var channels: Int = 0)

    private fun probeMatroska(context: Context, uri: Uri, name: String, size: Long): ProbeResult =
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch -> probeMatroska(ch, name, size) }
        }

    /** Android'den bağımsız (birim testlerinde de çalışır). Akış sırası ffprobe ile aynıdır. */
    fun probeMatroska(ch: FileChannel, name: String, size: Long): ProbeResult {
        run {
            run {
                val r = Reader(ch)
                var segStart = -1L
                var segEnd = r.size
                for ((id, data, sz) in r.children(0, r.size)) {
                    if (id == 0x18538067L) { segStart = data; if (sz > 0) segEnd = minOf(r.size, data + sz); break }
                }
                require(segStart >= 0) { "Matroska değil" }
                val tracks = mutableListOf<MkTrack>()
                val attachments = mutableListOf<Pair<String, String>>()
                var attachmentsPos: Long? = null
                fun parseAttachments(start: Long, end: Long) {
                    for ((aid, adata, asz) in r.children(start, end)) {
                        if (aid != 0x61A7L) continue
                        var fname = ""; var mime = ""
                        for ((fid, fdata, fsz) in r.children(adata, adata + asz)) {
                            when (fid) { 0x466EL -> fname = r.str(fdata, fsz.toInt()); 0x4660L -> mime = r.str(fdata, fsz.toInt()) }
                        }
                        attachments += fname to mime
                    }
                }
                var seenAttachments = false
                for ((id, data, sz) in r.children(segStart, segEnd)) {
                    when (id) {
                        0x114D9B74L -> for ((sid, sdata, ssz) in r.children(data, data + sz)) { // SeekHead
                            if (sid != 0x4DBBL) continue
                            var target = 0L; var pos = 0L
                            for ((eid, edata, esz) in r.children(sdata, sdata + ssz)) {
                                when (eid) { 0x53ABL -> target = r.uint(edata, esz.toInt()); 0x53ACL -> pos = r.uint(edata, esz.toInt()) }
                            }
                            if (target == 0x1941A469L) attachmentsPos = segStart + pos
                        }
                        0x1654AE6BL -> for ((tid, tdata, tsz) in r.children(data, data + sz)) { // Tracks
                            if (tid != 0xAEL) continue
                            val t = MkTrack()
                            for ((fid, fdata, fsz) in r.children(tdata, tdata + tsz)) {
                                when (fid) {
                                    0xD7L -> t.number = r.uint(fdata, fsz.toInt())
                                    0x83L -> t.type = r.uint(fdata, fsz.toInt())
                                    0x86L -> t.codec = r.str(fdata, fsz.toInt())
                                    0x22B59CL -> if (t.lang == null) t.lang = r.str(fdata, fsz.toInt())
                                    0x22B59DL -> t.lang = r.str(fdata, fsz.toInt())
                                    0x536EL -> t.name = r.str(fdata, fsz.toInt())
                                    0x55AAL -> t.forced = r.uint(fdata, fsz.toInt()) == 1L
                                    0xE1L -> for ((aid, adata, asz) in r.children(fdata, fdata + fsz)) if (aid == 0x9FL) t.channels = r.uint(adata, asz.toInt()).toInt()
                                }
                            }
                            tracks += t
                        }
                        0x1941A469L -> { parseAttachments(data, data + sz); seenAttachments = true }
                        0x1F43B675L -> break // ilk küme: başlık bitti
                    }
                }
                if (!seenAttachments) attachmentsPos?.let { p ->
                    runCatching {
                        val (aid, a) = r.vint(p, true)
                        val (asz, b) = r.vint(p + a, false)
                        if (aid == 0x1941A469L && asz > 0) parseAttachments(p + a + b, p + a + b + asz)
                    }
                }
                val audio = mutableListOf<ProbeAudio>()
                val subs = mutableListOf<ProbeSub>()
                tracks.forEachIndexed { i, t ->
                    // Matroska'da dil yoksa varsayılan İngilizce'dir (ffmpeg de böyle okur)
                    val lang = Langs.norm(t.lang ?: "eng")
                    when (t.type) {
                        2L -> audio += ProbeAudio(i, lang, t.codec.removePrefix("A_").substringBefore('/'), t.channels, t.name)
                        17L -> {
                            val text = t.codec in TEXT_SUB
                            val codec = when (t.codec) {
                                "S_TEXT/UTF8" -> "SRT"; "S_TEXT/WEBVTT", "D_WEBVTT/SUBTITLES" -> "WebVTT"; "S_TEXT/ASS", "S_ASS", "S_TEXT/SSA", "S_SSA" -> "ASS"
                                "S_HDMV/PGS" -> "PGS"; "S_VOBSUB" -> "VobSub"; else -> t.codec
                            }
                            subs += ProbeSub(i, lang, codec, t.forced || t.name.lowercase().contains("forced"), t.name, !text, text)
                        }
                    }
                }
                attachments.forEachIndexed { j, (fname, mime) ->
                    val ext = fname.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext in setOf("vtt", "srt", "ass", "ssa") || mime in setOf("text/vtt", "application/x-subrip", "text/x-ssa", "text/x-ass")) {
                        subs += ProbeSub(tracks.size + j, Langs.guessFromName(fname), ext.uppercase(Locale.ROOT), fname.lowercase().contains("forced"), "", false, true, attachment = fname)
                    }
                }
                return ProbeResult(name, size, audio, subs, "matroska")
            }
        }
    }
}

/** Dosya adından tür / ad / sezon / bölüm tahmini (masaüstüyle aynı kurallar). */
object FileNames {
    data class Parsed(val kind: String, val title: String, val year: Int?, val season: Int?, val episode: Int?)

    // S01E02 · 1x02 · 1×2 (çarpı işareti, tek hane de olur)
    private val ep = Regex("[Ss](\\d{1,2})[ ._-]*[Ee](\\d{1,3})|(?<!\\d)(\\d{1,2}) ?[xX×] ?(\\d{1,3})(?!\\d)")
    private val year = Regex("(?<!\\d)(19[3-9]\\d|20[0-4]\\d)(?!\\d)")
    private val junk = Regex(
        "\\b(2160p|1080p|720p|480p|4k|uhd|hdr|hdr10|dv|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|web|hdtv|dvdrip|x264|x265|h\\.?264|h\\.?265|" +
            "hevc|avc|aac|ac3|dts|ddp?5\\.1|5\\.1|7\\.1|atmos|remux|proper|repack|extended|multi|dual|tr|eng|turkce|dublaj|altyazili|yify|rarbg|amzn|nf|dsnp)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(name: String): Parsed {
        val stem = name.substringBeforeLast('.')
        val trailer = Regex("\\b(trailer|fragman|teaser)\\b", RegexOption.IGNORE_CASE).containsMatchIn(stem)
        val m = if (trailer) null else ep.find(stem)
        var cut = stem.length
        var season: Int? = null
        var episode: Int? = null
        if (m != null) {
            season = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt()
            episode = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toInt()
            cut = m.range.first
        }
        val y = year.find(stem.substring(0, cut))?.takeIf { it.range.first > 0 }
        if (y != null && m == null) cut = y.range.first
        var title = stem.substring(0, cut).trimEnd('(', '[', ' ', '.', '_', '-')
        title = title.replace(Regex("[\\[(].*?[])]"), " ").replace(Regex("[._]+"), " ")
        title = junk.replace(title, " ").replace(Regex("\\b(trailer|fragman|teaser)\\b", RegexOption.IGNORE_CASE), " ")
        title = title.replace(Regex("\\s+"), " ").trim(' ', '-')
        return Parsed(if (trailer) "trailer" else if (m != null) "episode" else "movie", title, y?.groupValues?.get(1)?.toInt(), season, episode)
    }

    fun slug(text: String): String {
        val t = text.replace('ı', 'i').replace('İ', 'i')
        val n = Normalizer.normalize(t, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        return n.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "icerik" }
    }

    fun itemId(kind: String, title: String) = (if (kind == "movie") "movie-" else "series-") + slug(title)
}
