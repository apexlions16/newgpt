package com.gflix.app.data

import com.gflix.app.admin.LiveLogChunk
import com.gflix.app.admin.parsed
import org.junit.Assert.assertEquals
import org.junit.Test

/** Bilgisayarın (tools/gflix/livelog.py) gönderdiği biçim telefonda okunuyor. */
class LiveLogTest {
    @Test fun parsesWorkerFormat() {
        val c = AppJson.decodeFromString(LiveLogChunk.serializer(), "{\"seq\": 3, \"first\": 1, \"lines\": [[1, 1793665148, \"▶ İş başladı: pcjob-x (🌐 Hızlı Adam)\"], [2, 1793665148, \"  ⏳ site hız sınırı (429): 2 sn\"], [3, 1793665148, \"\\\"tırnak\\\" ve \\\\ ters bölü\"]], \"at\": 1, \"host\": \"Hasan\"}")
        val l = c.parsed()
        assertEquals(3, l.size); assertEquals(3L, c.seq)
        assertEquals("▶ İş başladı: pcjob-x (🌐 Hızlı Adam)", l[0].text)
        assertEquals("\"tırnak\" ve \\ ters bölü", l[2].text)
    }
}
