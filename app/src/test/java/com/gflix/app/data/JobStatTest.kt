package com.gflix.app.data

import com.gflix.app.admin.JobStat
import com.gflix.app.admin.LinkStatus
import com.gflix.app.admin.PcJobs
import com.gflix.app.admin.durationLine
import com.gflix.app.admin.parseLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobStatTest {
    @Test fun phasesFromTimestamps() {
        // Gölge Adam: 12 dk toplam, 7 dk 28 sn indirme, 4,5 GB
        val j = JobStat(start = 1000, site = 1001, dlStart = 1012, dlEnd = 1460, end = 1720, bytes = 4_500_000_000, state = "done")
        assertEquals(720, j.total); assertEquals(1, j.wait); assertEquals(448, j.download); assertEquals(260, j.upload); assertEquals(11, j.prep)
        assertEquals(4_500_000_000 / 448, j.speed)
        val line = durationLine(LinkStatus(state = "done", timing = j))!!
        assertTrue(line, line.startsWith("⏱ Toplam 12 dk 0 sn") && line.contains("indirme 7 dk 28 sn") && line.contains("4.5 GB"))
    }

    @Test fun parsesWorkerJson() {
        val body = "✅ X\n\n<!--GFLIX_STATUS\n{\"state\":\"done\",\"started\":1000,\"updated\":1720,\"timing\":{\"start\":1000,\"site\":1001,\"dl_start\":1012,\"dl_end\":1460,\"end\":1720,\"bytes\":4500000000,\"items\":1,\"done_items\":1}}\n-->"
        val st = parseLinkStatus(body)!!
        assertEquals(448, st.timing!!.download)
        val pc = PcJobs.parse("{\"host\":\"Hasan\",\"seen\":1,\"stats\":[{\"tag\":\"pcjob-a\",\"title\":\"Gölge Adam\",\"state\":\"done\",\"start\":1000,\"end\":1720,\"bytes\":4500000000}]}")!!
        assertEquals(720, pc.stats.single().total)
        // bozuk kayıt (ondalıklı sayı) durumu bozmaz: o kayıt atlanır, bilgisayar bilgisi okunur
        val bad = PcJobs.parse("{\"host\":\"Hasan\",\"seen\":1,\"stats\":[{\"tag\":\"x\",\"bytes\":4.5},{\"tag\":\"y\",\"start\":1,\"end\":61}]}")!!
        assertEquals("Hasan", bad.host); assertEquals(listOf("y"), bad.stats.map { it.tag })
        val badJob = parseLinkStatus("<!--GFLIX_STATUS\n{\"state\":\"done\",\"stage\":\"Tamam\",\"timing\":{\"bytes\":1.5}}\n-->")!!
        assertEquals("Tamam", badJob.stage)
        // eski işler: süre kaydı yok → başlangıç/son güncelleme farkı
        assertEquals("⏱ Toplam ~12 dk 0 sn", durationLine(LinkStatus(state = "done", started = 1000, updated = 1720)))
    }
}
