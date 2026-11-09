package com.gflix.app.player

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bir dosyanın [start, end) aralığını aynı anda birkaç bağlantıyla, sırayla okur (indirme programları gibi).
 * Diske yazmaz: en fazla [maxInflight] parça bellekte durur, okunan parça hemen bırakılır.
 *
 * Hızlı açılış ve sarma için parçalar küçük başlar ve büyür (512 KB, 512 KB, 1 MB, 2 MB, sonra 4 MB); ilk dört
 * parça aynı anda istenir, bağlantı sayısı okundukça [maxInflight]'a çıkar. Kısa okumalar (dosya sonundaki dizin, sarma sonrası hemen kapanan
 * istekler) boşa çok veri indirmez. Bir parçanın bağlantısı koparsa kaldığı bayttan tekrar denenir.
 *
 * [fetch]: [from, to] (dahil) aralığını indirir, gelen baytları sırayla [sink]'e verir; sink false dönerse durur.
 */
class ParallelRangeReader(
    private val start: Long,
    private val end: Long,
    private val executor: ExecutorService,
    private val maxInflight: Int = 6,
    private val readTimeoutMs: Long = 30_000,
    private val fetch: (from: Long, to: Long, sink: (ByteArray, Int, Int) -> Boolean) -> Unit,
) {
    private class Block(val start: Long, val len: Int) {
        val buf = ByteArray(len)
        var filled = 0
        var error: IOException? = null
        var future: Future<*>? = null
    }

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val blocks = ArrayDeque<Block>()
    private var nextStart = start
    private var pos = start
    private var consumed = 0
    @Volatile private var closed = false

    val position: Long get() = pos

    /** -1: aralığın sonu */
    fun read(dst: ByteArray, off: Int, n: Int): Int {
        if (n == 0) return 0
        if (pos >= end) return -1
        lock.withLock {
            schedule()
            val b = blocks.first()
            val inBlock = (pos - b.start).toInt()
            var waitNs = TimeUnit.MILLISECONDS.toNanos(readTimeoutMs)
            while (b.filled <= inBlock && b.error == null && !closed) {
                if (waitNs <= 0) throw SocketTimeoutException("Sunucu yanıt vermiyor")
                waitNs = cond.awaitNanos(waitNs)
            }
            if (closed) throw InterruptedIOException("Kapatıldı")
            if (b.filled <= inBlock) throw b.error!!
            val k = minOf(n, b.filled - inBlock)
            System.arraycopy(b.buf, inBlock, dst, off, k)
            pos += k
            if (pos == b.start + b.len) {
                blocks.removeFirst()
                consumed++
                schedule()
            }
            return k
        }
    }

    fun close() {
        closed = true
        lock.withLock {
            cond.signalAll()
            blocks.forEach { it.future?.cancel(true) }
            blocks.clear()
        }
    }

    /** Kilit altında: okuma ilerledikçe yeni parçalar başlatılır. */
    private fun schedule() {
        // açılışta/sarmada ilk 4 küçük parça aynı anda istenir (512 KB + 512 KB + 1 MB + 2 MB): ilk görüntü hemen gelir
        val target = minOf(maxInflight, 4 + consumed)
        while (blocks.size < target && nextStart < end) {
            val len = minOf(sizeFor(consumed + blocks.size), end - nextStart).toInt()
            val b = Block(nextStart, len)
            nextStart += len
            blocks.addLast(b)
            b.future = executor.submit { download(b) }
        }
    }

    private fun download(b: Block) {
        var attempt = 0
        while (!closed && b.filled < b.len) {
            try {
                fetch(b.start + b.filled, b.start + b.len - 1) { data, o, n ->
                    lock.withLock {
                        if (closed) return@withLock false
                        val k = minOf(n, b.len - b.filled)
                        System.arraycopy(data, o, b.buf, b.filled, k)
                        b.filled += k
                        cond.signalAll()
                        b.filled < b.len
                    }
                }
                if (b.filled < b.len && !closed) throw EOFException("Bağlantı erken kapandı")
            } catch (e: IOException) {
                if (closed) return
                if (++attempt >= 4) {
                    lock.withLock { b.error = e; cond.signalAll() }
                    return
                }
                try { Thread.sleep(300L * attempt) } catch (_: InterruptedException) { return }
            }
        }
    }

    companion object {
        fun sizeFor(index: Int): Long = when (index) {
            0, 1 -> 512L shl 10
            2 -> 1L shl 20
            3 -> 2L shl 20
            else -> 4L shl 20
        }
    }
}
