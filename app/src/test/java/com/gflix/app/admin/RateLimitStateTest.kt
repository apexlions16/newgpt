package com.gflix.app.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitStateTest {
    private fun c(old: RateLimitState.Snap?, rem: Int, reset: Long, at: Long) = RateLimitState.combine(old, rem, 5000, reset, at)

    @Test fun neverGoesUpWithinWindow() {
        var s = c(null, 4924, 10_000, 6_500)
        s = c(s, 4931, 10_001, 6_510)      // başka sunucudan eski sayı: yukarı çıkmaz
        assertEquals(4924, s.remaining)
        s = c(s, 4900, 10_000, 6_520)
        assertEquals(4900, s.remaining)
    }

    @Test fun resetsAfterWindow() {
        var s = c(null, 1200, 10_000, 9_000)
        s = c(s, 4990, 13_600, 10_050)     // saat doldu: yeni pencere
        assertEquals(4990, s.remaining)
        assertEquals(13_600, s.reset)
    }

    @Test fun ignoresLateAnswerFromOldWindow() {
        var s = c(null, 4990, 13_600, 10_050)
        s = c(s, 1200, 10_000, 10_060)
        assertEquals(4990, s.remaining)
    }
}
