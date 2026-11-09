package com.gflix.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Ayrı yüklenen ses dosyalarında (m4a/aac) dil bilgisi çoğunlukla yoktur.
 * Bu fabrika, çıkan ses izlerine katalogdaki dili/adı yazar; böylece oynatıcının ses menüsünde
 * "Türkçe", "İngilizce" diye görünürler. [relabel]: (dosyadaki kaçıncı ses izi, biçim) -> yeni biçim
 */
@OptIn(UnstableApi::class)
class LabeledExtractorsFactory(private val relabel: (Int, Format) -> Format) : ExtractorsFactory {

    private val delegate = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map { LabeledExtractor(it, relabel) }.toTypedArray()

    override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { LabeledExtractor(it, relabel) }.toTypedArray()

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    companion object {
        /** Ayrı ses dosyası: her izine verilen dili ve adı yazar. */
        fun forAudio(lang: String, label: String?, default: Boolean) = LabeledExtractorsFactory { _, f ->
            f.buildUpon()
                .setLanguage(lang)
                .setLabel(label ?: f.label)
                .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        /** Video: içindeki etiketsiz seslere sırayla verilen dilleri yazar. */
        fun forVideo(audioLangs: List<String>) = LabeledExtractorsFactory { i, f ->
            val lang = audioLangs.getOrNull(i)
            if (lang != null && (f.language == null || f.language == "und" || f.language == C.LANGUAGE_UNDETERMINED)) {
                f.buildUpon().setLanguage(lang).build()
            } else f
        }
    }
}

@OptIn(UnstableApi::class)
private class LabeledExtractor(private val inner: Extractor, private val relabel: (Int, Format) -> Format) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
    override fun init(output: ExtractorOutput) = inner.init(LabeledOutput(output, relabel))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@OptIn(UnstableApi::class)
private class LabeledOutput(private val inner: ExtractorOutput, private val relabel: (Int, Format) -> Format) : ExtractorOutput {
    private var audioCount = 0
    private val audioIndex = HashMap<Int, Int>()

    override fun track(id: Int, type: Int): TrackOutput {
        val out = inner.track(id, type)
        if (type != C.TRACK_TYPE_AUDIO) return out
        val index = audioIndex.getOrPut(id) { audioCount++ }
        return LabeledTrack(out) { f -> relabel(index, f) }
    }

    override fun endTracks() = inner.endTracks()
    override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
}

@OptIn(UnstableApi::class)
private class LabeledTrack(private val inner: TrackOutput, private val map: (Format) -> Format) : TrackOutput {
    override fun format(format: Format) = inner.format(map(format))

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        inner.sampleData(input, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
        inner.sampleData(data, length, sampleDataPart)

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
        inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
}
