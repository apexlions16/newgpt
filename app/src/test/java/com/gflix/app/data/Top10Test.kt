package com.gflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class Top10Test {
    @Test fun popularityBeatsRating() {
        val old = Title(id = "a", type = "movie", title = "Eski klasik", rating = 8.5, popularity = 20.0)
        val hot = Title(id = "b", type = "movie", title = "Gündemde", rating = 6.1, popularity = 950.0)
        val none = Title(id = "c", type = "movie", title = "Bilinmiyor", rating = 9.0)
        val none2 = Title(id = "d", type = "movie", title = "Bilinmiyor 2", rating = 4.0)
        assertEquals(listOf("b", "a", "c", "d"), Library.top10(listOf(none2, old, none, hot)).map { it.id })
    }
}
