package com.gflix.app.admin

/**
 * İşler listesinde aynı dizinin bölümleri tek kartta: "Gece Bekçisi 1. Sezon 3. Bölüm izle | Site" ve bilgisayarın
 * çözdüğü "Gece Bekçisi (2021)" aynı anahtarı ("gecebekcisi") verir. Filmler ve farklı diziler ayrı kart olur.
 */
object JobGroups {
    data class Parsed(val key: String, val name: String, val season: Int?, val episode: Int?)

    private val EP_PATTERNS = listOf(
        Regex("(?i)^(.*?)[\\s\\-–:]*\\b(\\d{1,2})\\s*\\.?\\s*sezon[\\s\\-]*(\\d{1,4})\\s*\\.?\\s*b[öo]l[üu]m"),
        Regex("(?i)^(.*?)[\\s\\-–:]*\\bsezon\\s*(\\d{1,2})[\\s\\-,]*b[öo]l[üu]m\\s*(\\d{1,4})"),
        Regex("(?i)^(.*?)[\\s\\-–:]*\\bseason\\s*(\\d{1,2})[\\s\\-,]*episode\\s*(\\d{1,4})"),
        Regex("(?i)^(.*?)[\\s\\-–:(\\[]*\\bs(\\d{1,2})\\s*e(\\d{1,4})\\b"),
        Regex("(?i)^(.*?)[\\s\\-–:]*\\b(\\d{1,2})x(\\d{1,4})\\b"),
    )
    private val ABS_EP = Regex("(?i)^(.*?)[\\s\\-–:]*\\b(\\d{1,4})\\s*\\.\\s*b[öo]l[üu]m")

    /** Site adı, "izle", yıl gibi ekleri at; karşılaştırma için sadeleştir. */
    fun normalize(s: String): String = s.lowercase()
        .replace('ı', 'i').replace('ç', 'c').replace('ğ', 'g').replace('ö', 'o').replace('ş', 's').replace('ü', 'u')
        .replace(Regex("\\((19|20)\\d{2}\\)"), " ")
        .replace(Regex("\\b(izle|watch|online|full|hd|turkce|dublaj|altyazili|tr)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** "A izle | Site | Film izle" → en anlamlı parça (bölüm kodlu, sonra "izle" geçen, sonra ilk). */
    private fun mainPart(title: String): String {
        val parts = title.split('|', '–', '—').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return title.trim()
        return parts.firstOrNull { p -> EP_PATTERNS.any { it.containsMatchIn(p) } || ABS_EP.containsMatchIn(p) }
            ?: parts.firstOrNull { Regex("(?i)\\bizle\\b|\\bwatch\\b").containsMatchIn(it) }
            ?: parts.first()
    }

    fun parse(title: String): Parsed {
        val t = mainPart(title.removePrefix("🌐").removePrefix("🔗").trim())
        for (re in EP_PATTERNS) {
            val m = re.find(t) ?: continue
            val name = m.groupValues[1].trim().trimEnd('-', ':', '–').trim()
            if (name.isNotEmpty()) return Parsed("s:" + normalize(name), clean(name), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        ABS_EP.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) return Parsed("s:" + normalize(name), clean(name), null, m.groupValues[2].toInt())
        }
        val name = clean(t)
        return Parsed("t:" + normalize(name), name, null, null)
    }

    private fun clean(s: String): String = s.replace(Regex("(?i)\\s+(izle|watch)\\b.*$"), "").trim().ifEmpty { s.trim() }

    /**
     * İşin grup anahtarı. Bilgisayar yapımı çözdüyse (st.title "Gece Bekçisi (2021)", kind tv/series) o ad kullanılır;
     * böylece sayfa başlığı farklı yazılmış bölümler de aynı dizide toplanır.
     */
    fun keyOf(jobTitle: String, resolvedTitle: String?, resolvedKind: String?): Parsed {
        val p = parse(jobTitle)
        val series = resolvedKind == "series" || resolvedKind == "tv"
        if (!resolvedTitle.isNullOrBlank() && series) {
            val name = resolvedTitle.replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "").trim()
            return p.copy(key = "s:" + normalize(name), name = name)
        }
        if (!resolvedTitle.isNullOrBlank() && p.key.startsWith("t:")) {
            val name = resolvedTitle.trim()
            return p.copy(key = "t:" + normalize(name), name = name)
        }
        return p
    }

    /** Aynı anahtarlı işleri ilk görünme sırasıyla gruplar (liste sırası korunur). */
    fun <T> group(items: List<T>, key: (T) -> String): List<Pair<String, List<T>>> {
        val out = LinkedHashMap<String, MutableList<T>>()
        items.forEach { out.getOrPut(key(it)) { mutableListOf() }.add(it) }
        return out.map { it.key to it.value }
    }
}
