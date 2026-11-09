package com.gflix.app.archive

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PhoneMuxDeviceTest {
    @Test fun nativeMuxKeepsBothLanguagesAndBothSubtitles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "mux-device-test").apply { mkdirs() }
        try {
            val source = File(folder, "source.mkv")
            val generated = FFmpegKit.executeWithArguments(arrayOf("-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100",
                "-map", "0:v", "-map", "1:a", "-map", "2:a", "-t", "3",
                "-c:v", "mpeg4", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-metadata:s:a:0", "language=tur", "-metadata:s:a:1", "language=eng", source.absolutePath))
            assertTrue("Native fixture generation failed", ReturnCode.isSuccess(generated.returnCode))
            val sub1 = File(folder, "tr.srt").apply { writeText("1\n00:00:00,000 --> 00:00:02,000\nMerhaba\n\n") }
            val sub2 = File(folder, "en.vtt").apply { writeText("WEBVTT\n\n00:00.000 --> 00:02.000\nHello\n\n") }
            val target = File(folder, "all-tracks.mkv")
            val completed = CountDownLatch(1)
            val statistics = AtomicInteger(0)
            val session = FFmpegKit.executeWithArgumentsAsync(PhoneMux.arguments(source.absolutePath, "", listOf(sub1, sub2), target),
                { completed.countDown() }, { }, { statistics.incrementAndGet() })
            assertTrue("Mux did not finish", completed.await(60, TimeUnit.SECONDS))
            assertTrue("MKV remux failed", ReturnCode.isSuccess(session.returnCode))
            assertTrue("Statistics callback never fired", statistics.get() > 0)
            val info = FFprobeKit.getMediaInformation(target.absolutePath).mediaInformation
            assertNotNull(info)
            val streams = info!!.streams
            assertEquals(1, streams.count { it.type == "video" })
            assertEquals(2, streams.count { it.type == "audio" })
            assertEquals(2, streams.count { it.type == "subtitle" })
            assertEquals(setOf("tur", "eng"), streams.filter { it.type == "audio" }
                .map { it.tags.optString("language") }.toSet())
        } finally { folder.deleteRecursively() }
    }
}
