package com.gflix.app.admin

/**
 * Yeni bölüm eklerken sıradakini bulur: yüklü son bölümden sonraki; sezon bittiyse (TMDB'ye göre) sonraki sezonun 1. bölümü.
 * TMDB bilgisi yoksa aynı sezonda bir sonraki bölüm.
 */
object NextEpisode {
    /** [uploaded]: yüklü (sezon, bölüm); [seasons]: TMDB'den (sezon, bölüm sayısı) ya da null */
    fun next(uploaded: List<Pair<Int, Int>>, seasons: List<Pair<Int, Int>>?): Pair<Int, Int> {
        val last = uploaded.maxWithOrNull(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
            ?: return (seasons?.firstOrNull()?.first ?: 1) to 1
        val count = seasons?.firstOrNull { it.first == last.first }?.second
        if (count != null && last.second >= count) {
            seasons.filter { it.first > last.first }.minByOrNull { it.first }?.let { return it.first to 1 }
        }
        return last.first to last.second + 1
    }

    /** "Yüklü: 1. sezon 1–8 · TMDB: 1. sezon 8 bölüm, 2. sezon 10 bölüm" */
    fun summary(uploaded: List<Pair<Int, Int>>, seasons: List<Pair<Int, Int>>?): String {
        val have = uploaded.groupBy { it.first }.toSortedMap().entries.joinToString(", ") { (s, eps) ->
            val ns = eps.map { it.second }.sorted()
            "$s. sezon " + if (ns.size > 1 && ns.last() - ns.first() + 1 == ns.size) "${ns.first()}–${ns.last()}" else ns.joinToString(",")
        }.ifBlank { "henüz bölüm yok" }
        val tmdb = seasons?.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.first}. sezon ${it.second} bölüm" }
        return "Yüklü: $have" + (tmdb?.let { " · TMDB: $it" } ?: "")
    }
}
