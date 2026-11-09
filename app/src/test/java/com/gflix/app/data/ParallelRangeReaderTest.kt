package com.gflix.app.data

import com.gflix.app.player.ParallelRangeReader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ParallelRangeReaderTest {
    private val pool = Executors.newCachedThreadPool()
    private val file = Random(7).nextBytes(20 * 1024 * 1024 + 12345)

    @After fun tearDown() { pool.shutdownNow() }

    /** Sahte sunucu: aralığı rastgele boyutlu dilimlerle, biraz gecikmeyle verir. */
    private fun server(fetched: AtomicLong = AtomicLong(), failOnce: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf()), ranges: MutableList<LongRange>? = null) =
        fetch@{ from: Long, to: Long, sink: (ByteArray, Int, Int) -> Boolean ->
            ranges?.add(from..to)
            var p = from
            val rnd = Random(from)
            while (p <= to) {
                val n = minOf((to - p + 1).toInt(), 1 + rnd.nextInt(70_000))
                if (p > from && failOnce.remove(from)) throw IOException("kopma")
                fetched.addAndGet(n.toLong())
                if (!sink(file, p.toInt(), n)) return@fetch
                p += n
                if (rnd.nextInt(10) == 0) Thread.sleep(1)
            }
        }

    private fun readAll(r: ParallelRangeReader, bufSize: Int = 32 * 1024): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(bufSize)
        while (true) {
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test fun readsWholeFileInOrder() {
        val r = ParallelRangeReader(0, file.size.toLong(), pool, fetch = server())
        assertArrayEquals(file, readAll(r))
    }

    @Test fun readsFromMiddle() {
        val start = 3_333_333L
        val r = ParallelRangeReader(start, file.size.toLong(), pool, fetch = server())
        assertArrayEquals(file.copyOfRange(start.toInt(), file.size), readAll(r, 7777))
    }

    @Test fun resumesBrokenBlockFromWhereItStopped() {
        // ilk üç parçanın bağlantısı bir kez kopar
        val fail = Collections.synchronizedSet(mutableSetOf(0L, 512L * 1024, 1024L * 1024))
        val r = ParallelRangeReader(0, file.size.toLong(), pool, fetch = server(failOnce = fail))
        assertArrayEquals(file, readAll(r))
    }

    @Test fun blocksGrowAndShortReadDoesNotDownloadMuch() {
        assertEquals(512L * 1024, ParallelRangeReader.sizeFor(0))
        assertEquals(4L shl 20, ParallelRangeReader.sizeFor(5))
        val fetched = AtomicLong()
        val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
        val r = ParallelRangeReader(0, file.size.toLong(), pool, fetch = server(fetched, ranges = ranges))
        val buf = ByteArray(1000)
        assertEquals(1000, r.read(buf, 0, 1000).let { n -> var t = n; while (t < 1000) t += r.read(buf, t, 1000 - t); t })
        Thread.sleep(200)
        r.close()
        // kısa okuma: sadece ilk dört küçük parça (512 KB + 512 KB + 1 MB + 2 MB) istenir
        assertTrue("istenen aralıklar: $ranges", ranges.size <= 4)
        assertTrue(fetched.get() <= 4L * 1024 * 1024)
    }

    @Test fun failsAfterRetries() {
        val calls = AtomicInteger()
        val r = ParallelRangeReader(0, 1000, pool) { _, _, _ -> calls.incrementAndGet(); throw IOException("yok") }
        val e = runCatching { r.read(ByteArray(10), 0, 10) }.exceptionOrNull()
        assertTrue(e is IOException)
        assertEquals(4, calls.get())
    }
}
