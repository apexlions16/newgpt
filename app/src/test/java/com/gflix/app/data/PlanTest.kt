package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private val now = 1_800_000_000L

    @Test fun groupsAndSeason() {
        val t = Title(id = "s", type = "series", title = "D", releasePlan = listOf(
            PlanEntry(1, 1, 5, now + 86400), PlanEntry(1, 6, 10, now + 8 * 86400), PlanEntry(2, 1, null, now + 40 * 86400)))
        val text = Library.planText(t, now)!!
        assertTrue(text, text.startsWith("1. sezon 1–5. bölümler"))
        assertTrue(text, text.contains("1. sezon 6–10. bölümler"))
        assertTrue(text, text.contains("2. sezon ") && text.endsWith("yayında"))
        assertEquals(now + 86400, Library.nextRelease(t, now))
    }

    @Test fun noteOverrides() = assertEquals("Her cuma yeni bölüm", Library.planText(Title(id = "s", title = "D", releaseNote = "Her cuma yeni bölüm", releasePlan = listOf(PlanEntry(1, 1, 2, now + 5))), now))
    @Test fun pastPlanIsHidden() = assertNull(Library.planText(Title(id = "s", title = "D", releasePlan = listOf(PlanEntry(1, 1, 2, now - 5))), now))

    @Test fun turkishFilters() {
        val list = listOf(Title(id = "a", type = "series", title = "A", originalLanguage = "tr"), Title(id = "b", title = "B", originalLanguage = "tr"), Title(id = "c", title = "C", originalLanguage = "en"))
        assertEquals(listOf("a"), Library.applyFilter(list, HomeFilter.TR_SERIES).map { it.id })
        assertEquals(listOf("b"), Library.applyFilter(list, HomeFilter.TR_MOVIES).map { it.id })
    }
}
