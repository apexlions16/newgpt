package com.gflix.app.data

import com.gflix.app.player.PartConcat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class PartConcatTest {
    private val whole = Random(7).nextBytes(10_000)
    private val sizes = listOf(3000L, 1L, 4000L, 2999L)

    /** Her parçayı ayrı bir "sunucu" gibi davranan sahte okuyucu; açılan aralıkları kaydeder. */
    private inner class FakeIo : PartConcat.Io {
        val opened = mutableListOf<Triple<Int, Long, Long>>()
        private var data = ByteArray(0)
        private var pos = 0
        override fun open(index: Int, offset: Long, length: Long) {
            opened += Triple(index, offset, length)
            val start = sizes.take(index).sum() + offset
            data = whole.copyOfRange(start.toInt(), (start + length).toInt())
            pos = 0
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos, 700)   // ağdan gelir gibi küçük parçalar
            System.arraycopy(data, pos, buffer, offset, n); pos += n
            return n
        }
        override fun close() {}
    }

    private fun readAll(c: PartConcat): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val n = c.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun readsWholeFileAcrossParts() {
        val io = FakeIo()
        val c = PartConcat(sizes, io)
        assertEquals(10_000L, c.open(0, -1))
        assertArrayEquals(whole, readAll(c))
        assertEquals(listOf(0, 1, 2, 3), io.opened.map { it.first })
    }

    @Test
    fun seeksIntoMiddleWithBoundedLength() {
        for ((pos, len) in listOf(2999L to 2002L, 3000L to 1L, 3001L to 5000L, 9999L to -1L, 0L to 3000L, 7001L to -1L)) {
            val io = FakeIo()
            val c = PartConcat(sizes, io)
            val expectedLen = if (len < 0) 10_000 - pos else len
            assertEquals(expectedLen, c.open(pos, len))
            assertArrayEquals("pos=$pos len=$len", whole.copyOfRange(pos.toInt(), (pos + expectedLen).toInt()), readAll(c))
        }
    }

    @Test
    fun opensRightPartAtBoundary() {
        val io = FakeIo()
        val c = PartConcat(sizes, io)
        c.open(3001, 10)
        assertEquals(Triple(2, 0L, 10L), io.opened.first())
        assertEquals(0L, PartConcat(sizes, FakeIo()).open(10_000, -1))
    }
}
