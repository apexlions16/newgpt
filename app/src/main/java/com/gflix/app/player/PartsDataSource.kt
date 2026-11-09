package com.gflix.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.gflix.app.data.PartedFile

/**
 * Uç uca eklenmiş parçaları tek dosya gibi okuyan saf mantık (Android'siz test edilebilir).
 * [Io.open] bir parçayı (sıra, parça içi başlangıç, uzunluk) açar; parça sonunda read -1 döner.
 */
class PartConcat(private val sizes: List<Long>, private val io: Io) {
    interface Io {
        fun open(index: Int, offset: Long, length: Long)
        fun read(buffer: ByteArray, offset: Int, length: Int): Int
        fun close()
    }

    private val starts: List<Long> = sizes.runningFold(0L) { a, b -> a + b }
    val total: Long = starts.last()
    private var position = 0L
    private var remaining = 0L
    private var isOpen = false

    /** [length] < 0: dosyanın sonuna kadar. Okunacak bayt sayısını döner. */
    fun open(pos: Long, length: Long): Long {
        require(pos in 0..total) { "konum dosyanın dışında: $pos / $total" }
        position = pos
        remaining = if (length < 0) total - pos else minOf(length, total - pos)
        if (remaining > 0) openAt(position)
        return remaining
    }

    private fun openAt(pos: Long) {
        var i = starts.indexOfLast { it <= pos }.coerceAtMost(sizes.size - 1)
        while (i < sizes.size - 1 && pos >= starts[i + 1]) i++
        val inPart = pos - starts[i]
        io.open(i, inPart, minOf(sizes[i] - inPart, remaining))
        isOpen = true
    }

    /** -1: bitti */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (remaining <= 0) return -1
            val n = io.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n < 0) {
                close()
                if (position >= total) return -1
                openAt(position)   // parça bitti: bir sonrakine geç
                continue
            }
            position += n
            remaining -= n
            return n
        }
    }

    fun close() {
        if (isOpen) {
            isOpen = false
            io.close()
        }
    }
}

/**
 * GitHub'da dosya başına 2 GB sınırı var; büyük videolar parça parça (f0.p000, f0.p001 …) durur.
 * Bu kaynak, parçaları uç uca eklenmiş tek bir dosya gibi okur: ileri-geri sarmada hangi parçanın
 * hangi baytından okunacağını hesaplar, parça sonunda bir sonrakine kesintisiz geçer.
 *
 * Adres biçimi: gflixparts://file?u=<parça1>&s=<boyut1>&u=<parça2>&s=<boyut2>…
 * Başka adresler olduğu gibi alttaki kaynağa gider (HLS vb.).
 */
@OptIn(UnstableApi::class)
class PartsDataSource(private val upstream: DataSource) : DataSource {

    private var uri: Uri? = null
    private var concat: PartConcat? = null
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        if (dataSpec.uri.scheme != SCHEME) {
            passthrough = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        val (uris, sizes) = parse(dataSpec.uri)
        if (uris.isEmpty()) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val c = PartConcat(sizes, object : PartConcat.Io {
            override fun open(index: Int, offset: Long, length: Long) {
                upstream.open(
                    DataSpec.Builder().setUri(uris[index]).setPosition(offset).setLength(length)
                        .setHttpRequestHeaders(dataSpec.httpRequestHeaders).setFlags(dataSpec.flags).build(),
                )
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
            override fun close() = upstream.close()
        })
        concat = c
        if (dataSpec.position > c.total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        return c.open(dataSpec.position, if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1 else dataSpec.length)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        val n = concat?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        return if (n < 0) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (passthrough) {
            passthrough = false
            upstream.close()
        }
        concat?.close()
        concat = null
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = PartsDataSource(upstream.createDataSource())
    }

    companion object {
        const val SCHEME = "gflixparts"

        fun uriFor(file: PartedFile): Uri = uriFor(file.parts, file.sizes)

        fun uriFor(parts: List<String>, sizes: List<Long>): Uri {
            val b = Uri.Builder().scheme(SCHEME).authority("file")
            parts.forEachIndexed { i, p -> b.appendQueryParameter("u", p).appendQueryParameter("s", sizes.getOrElse(i) { 0L }.toString()) }
            return b.build()
        }

        private fun parse(uri: Uri): Pair<List<Uri>, List<Long>> {
            val us = uri.getQueryParameters("u")
            val ss = uri.getQueryParameters("s").map { it.toLongOrNull() ?: 0L }
            val keep = us.indices.filter { ss.getOrElse(it) { 0L } > 0 }
            return keep.map { Uri.parse(us[it]) } to keep.map { ss[it] }
        }
    }
}
