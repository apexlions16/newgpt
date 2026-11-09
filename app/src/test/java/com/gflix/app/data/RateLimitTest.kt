package com.gflix.app.data

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RateLimitTest {
    private fun resp(code: Int, remaining: Int?, resetSec: Long?, retryAfter: Long? = null): Response {
        val b = Response.Builder().request(Request.Builder().url("https://api.github.com/x").build()).protocol(Protocol.HTTP_1_1).code(code).message("")
        remaining?.let { b.header("X-RateLimit-Remaining", "$it") }
        resetSec?.let { b.header("X-RateLimit-Reset", "$it") }
        retryAfter?.let { b.header("Retry-After", "$it") }
        b.header("X-RateLimit-Limit", "5000")
        return b.build()
    }

    @org.junit.Before fun setUp() = RateLimit.resetForTest()

    @Test fun flow() {
        val now = System.currentTimeMillis() / 1000
        RateLimit.track(resp(200, 4000, now + 3600))
        assertTrue("bolca hak var", RateLimit.canStart())
        RateLimit.track(resp(200, RateLimit.RESERVE - 1, now + 600))
        assertFalse("pay altına inince yeni izleme sıraya girer", RateLimit.canStart())
        assertTrue(RateLimit.waitMs() in 590_000..600_000)
        RateLimit.check() // engel yok: izleyenlerin istekleri sürer
        try { RateLimit.track(resp(403, 0, now + 300)); fail("hak bitince hata beklenir") } catch (e: RateLimit.Blocked) { assertNotNull(e) }
        try { RateLimit.check(); fail("engelliyken istek atılmaz") } catch (_: RateLimit.Blocked) { }
        val wrapped = RuntimeException(java.io.IOException(RateLimit.Blocked(1)))
        assertNotNull(RateLimit.blockedIn(wrapped))
        assertEquals(null, RateLimit.blockedIn(RuntimeException("başka")))
    }

    @Test fun staleCounterAfterReset() {
        val now = System.currentTimeMillis() / 1000
        // ikincil sınır geçtikten ve saatlik yenilenme anı da geçtikten sonra sayaç eskidir: başlayabilir
        RateLimit.track(resp(200, 10, now - 5))
        assertTrue(RateLimit.canStart())
    }
}
