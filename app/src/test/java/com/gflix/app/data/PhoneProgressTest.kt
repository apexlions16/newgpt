package com.gflix.app.data

import com.gflix.app.archive.PhoneProgress
import org.junit.Assert.*
import org.junit.Test

class PhoneProgressTest {
    @Test fun unknownTotalIsIndeterminate() {
        assertEquals(-1, PhoneProgress.percent(500, 0))
        assertEquals(50, PhoneProgress.percent(500, 1000))
        assertEquals(100, PhoneProgress.percent(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0, PhoneProgress.percent(-1, 100))
    }
    @Test fun interruptedMuxNeverResumesAsAnUploadOrArchiveVerification() {
        assertEquals("download", PhoneProgress.recoveryStage("processing", false))
        assertEquals("download", PhoneProgress.recoveryStage("queued", false))
        assertEquals("verify", PhoneProgress.recoveryStage("processing", true))
        assertEquals("upload", PhoneProgress.recoveryStage("queued", true))
        assertNull(PhoneProgress.recoveryStage("done", true))
        assertNull(PhoneProgress.recoveryStage("failed", false))
    }
    @Test fun cleanupNeedsBothSizeAndChecksum() {
        assertFalse(PhoneProgress.verified(10, "abc", 10L to null))
        assertFalse(PhoneProgress.verified(10, null, 10L to "abc"))
        assertFalse(PhoneProgress.verified(10, "abc", 9L to "abc"))
        assertFalse(PhoneProgress.verified(10, "abc", 10L to "def"))
        assertFalse(PhoneProgress.verified(0, "abc", 0L to "abc"))
        assertTrue(PhoneProgress.verified(10, "ABC", 10L to "abc"))
    }
}
