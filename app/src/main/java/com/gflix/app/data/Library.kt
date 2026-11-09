package com.gflix.app.data

import java.text.Normalizer
import java.util.Locale

val TR: Locale = Locale.forLanguageTag("tr-TR")

data class PlayTarget(val season: Int?, val episode: Int?, val positionMs: Long)

data class EpisodeRef(val season: Season, val episode: Episode) {
    val label: String get() = "S${season.number}:B${episode.number}"
}

data class ContinueItem(val title: Title, val progress: WatchProgress?, val season: Int?, val episode: Int?)

enum class RowStyle { Poster, Top10 }

data class HomeRow(val key: String, val title: String, val items: List<Title>, val style: RowStyle = RowStyle.Poster)

/** Ana sayfa filtresi: "all", "series", "movies" ya da "genre:<ad>" */
object HomeFilter {
    const val ALL = "all"
    const val SERIES = "series"
    const val MOVIES = "movies"
    const val TR_SERIES = "tr-series"
    const val TR_MOVIES = "tr-movies"
    fun genre(name: String) = "genre:$name"
    fun genreOf(filter: String): String? = filter.removePrefix("genre:").takeIf { filter.startsWith("genre:") }
}

object Library {

    fun progressKey(titleId: String, season: Int?, episode: Int?): String =
        if (season == null || episode == null) titleId else "$titleId/$season/$episode"

    fun hasPlayable(t: Title): Boolean = t.stream != null || t.seasons.any { s -> s.episodes.any { it.stream != null } }

    fun isKidsFriendly(t: Title): Boolean {
        val m = t.maturity
        if (m != null) return m <= 7
        return t.genres.any { it.contains("Aile") || it.contains("Animasyon") || it.contains("Çocuk") }
    }

    // ---- Yakında / zamanlanmış yayın ----

    fun nowSec(): Long = System.currentTimeMillis() / 1000

    fun isUpcoming(t: Title, now: Long = nowSec()): Boolean = (t.releaseAt ?: 0L) > now

    // ---- Notlar / gizleme ----

    /** Süresi dolmamış notlar (metni boş olan "sadece gizle" notları gösterilmez). */
    fun activeNotes(t: Title, now: Long = nowSec()): List<TitleNote> =
        t.notes.filter { it.text.isNotBlank() && (it.until == null || it.until > now) }

    /** "Bitince yapımı kaldır" notunun zamanı geldiyse yapım uygulamada görünmez (dosyalar silinmez). */
    fun isHidden(t: Title, now: Long = nowSec()): Boolean = t.notes.any { it.hide == true && it.until != null && it.until <= now }

    // ---- Eksik bölümler (TMDB'ye göre) ----

    /** TMDB'de olup henüz yüklenmemiş bölüm sayısı (Özel bölümler hariç). */
    fun missingEpisodes(t: Title): Int {
        if (!t.isSeries || t.tmdbSeasons.isEmpty()) return 0
        return t.tmdbSeasons.sumOf { ts ->
            val have = t.seasons.firstOrNull { it.number == ts.n }?.episodes?.count { it.stream != null && it.number in 1..ts.c } ?: 0
            (ts.c - have).coerceAtLeast(0)
        }
    }

    /** TMDB'de yeni sezon duyurulmuş ama bölümleri henüz girilmemiş (bölümü girilenleri missingEpisodes sayar) */
    fun announced(t: Title, now: Long = nowSec()): Boolean {
        val n = t.nextEpisode ?: return false
        if (!t.isSeries) return false
        return n.s > (t.tmdbSeasons.maxOfOrNull { it.n } ?: 0) && t.seasons.none { it.number == n.s && it.episodes.any { e -> e.stream != null } }
    }

    /** TMDB tahminine göre "yeni bölüm yakında" yazılmaz (yanlış algılıyordu: bölümü gelmemiş dizileri Yakında'ya koyuyordu).
     *  Yakında'ya yalnızca yöneticinin verdiği tarih / yayın planı / fragman sokar. */
    @Suppress("UNUSED_PARAMETER")
    fun newEpisodesText(t: Title, now: Long = nowSec()): String? = null

    /**
     * Yüklenmiş ama yayın saati gelmemiş bölümlerden plan: aynı sezonda aynı saatte açılan ardışık bölümler tek satır
     * ("1. sezon 1–15. bölümler · 7 Kasım 20:00"). Yöneticinin yayın planı yoksa bu kullanılır.
     */
    fun episodePlan(t: Title, now: Long = nowSec()): List<PlanEntry> {
        val future = t.seasons.flatMap { s -> s.episodes.mapNotNull { e -> e.releaseAt?.takeIf { it > now }?.let { Triple(s.number, e.number, it) } } }
            .sortedWith(compareBy({ it.third }, { it.first }, { it.second }))
        val out = mutableListOf<PlanEntry>()
        for ((season, ep, at) in future) {
            val last = out.lastOrNull()
            if (last != null && last.season == season && last.at == at && last.to == ep - 1) out[out.size - 1] = last.copy(to = ep)
            else out += PlanEntry(season, ep, ep, at)
        }
        return out
    }

    /** Devamı gelen dizinin Yakında ibaresi: plan varsa "🆕 Yeni bölümler: 1–15. bölümler 7 Kasım Cuma 20:00", yoksa "Yeni bölümler yakında". */
    fun seriesSoonText(t: Title, now: Long = nowSec()): String? =
        planText(t, now)?.let { if (t.releaseNote?.isNotBlank() == true) "🆕 $it" else "🆕 Yeni bölümler: $it" } ?: newEpisodesText(t, now)

    /** Zamanı gelmemiş yapım ve bölümlerin videolarını gizler (dosyalar yüklü olsa bile); zamanı dolan "kaldır" notlu yapımları çıkarır. */
    fun applySchedule(catalog: Catalog?, now: Long = nowSec()): Catalog? = catalog?.copy(titles = catalog.titles.filterNot { isHidden(it, now) }.map { t ->
        when {
            isUpcoming(t, now) -> t.copy(stream = null, seasons = emptyList())
            t.seasons.any { s -> s.episodes.any { (it.releaseAt ?: 0L) > now } } ->
                t.copy(seasons = t.seasons.map { s -> s.copy(episodes = s.episodes.filter { (it.releaseAt ?: 0L) <= now }) }.filter { it.episodes.isNotEmpty() })
            else -> t
        }
    })

    /** Bir sonraki yayın zamanı: yapımın kendisi ya da dizinin sıradaki bölümü (yoksa null). */
    fun nextRelease(raw: Title, now: Long = nowSec()): Long? {
        if (isUpcoming(raw, now)) return raw.releaseAt
        return (raw.seasons.flatMap { it.episodes }.mapNotNull { it.releaseAt } + raw.releasePlan.filterNot { it.waiting }.map { it.at }).filter { it > now }.minOrNull()
    }

    private fun planDate(at: Long): String {
        val d = java.util.Date(at * 1000)
        val day = java.text.SimpleDateFormat("d MMMM EEEE", TR).format(d)
        val time = java.text.SimpleDateFormat("HH:mm", TR).format(d)
        return if (time == "00:00") day else "$day $time"
    }

    /**
     * Yayın planı bilgilendirmesi: yöneticinin metni, yoksa gelecek plan satırlarından
     * ("1–5. bölümler 7 Kasım Cuma 20:00'de · 2. sezon 15 Aralık Pazartesi'de yayında"). Yoksa null.
     */
    fun planText(t: Title, now: Long = nowSec()): String? {
        t.releaseNote?.takeIf { it.isNotBlank() }?.let { return it }
        // Yöneticinin planı (zamanı gelmemiş ya da "hepsi yüklenince" bekleyen) + yüklenmiş bölümlerin saatlerinden çıkan, planla çakışmayan aralıklar
        val admin = t.releasePlan.filter { it.waiting || it.at > now }
        fun covered(e: PlanEntry) = admin.any { a -> a.season == e.season && a.from <= (e.to ?: e.from) && e.from <= (a.to ?: Int.MAX_VALUE) }
        val future = (admin + episodePlan(t, now).filterNot(::covered)).sortedBy { if (it.waiting && it.at <= now) Long.MAX_VALUE else it.at }
        if (future.isEmpty()) return null
        val multiSeason = future.map { it.season }.distinct().size > 1 || t.releasePlan.map { it.season }.distinct().size > 1 || t.seasons.size > 1
        return future.joinToString(" · ") { p ->
            if (p.waiting) {
                val eps = if (p.from == p.to) "${p.from}. bölüm" else "${p.from}–${p.to}. bölümler"
                return@joinToString (if (multiSeason) "${p.season}. sezon " else "") + "$eps hepsi hazır olunca birlikte açılacak" +
                    (if (p.at > now) " (en erken ${planDate(p.at)})" else "")
            }
            val eps = when {
                p.from <= 1 && p.to == null -> null
                p.to == null -> "${p.from}. bölümden itibaren"
                p.from == p.to -> "${p.from}. bölüm"
                else -> "${p.from}–${p.to}. bölümler"
            }
            when {
                eps == null -> "${p.season}. sezon ${planDate(p.at)} yayında"
                multiSeason -> "${p.season}. sezon $eps ${planDate(p.at)}"
                else -> "$eps ${planDate(p.at)}"
            }
        }
    }

    /**
     * "Yakında" listesi (ham katalogdan): yayın tarihi gelmemiş yapımlar, yeni bölümü zamanlanmış diziler
     * ve henüz videosu olmayan ama fragmanı yüklenmiş yapımlar. En yakın tarih önce; tarihsizler sonda.
     */
    fun comingSoon(raw: Catalog?, profile: Profile?, now: Long = nowSec()): List<Title> {
        val list = raw?.titles.orEmpty().filter { t ->
            !isHidden(t, now) && (nextRelease(t, now) != null || t.releasePlan.any { it.waiting } || (!hasPlayable(t) && (t.comingSoon || t.trailer != null || t.trailers.isNotEmpty())))
        }
        val visible = if (profile?.kids == true) list.filter(::isKidsFriendly) else list
        return visible.sortedWith(compareBy<Title> { nextRelease(it, now) ?: it.nextEpisode?.at?.takeIf { a -> a > now } ?: Long.MAX_VALUE }.thenByDescending { it.added ?: 0L })
    }

    /** Devamı gelen dizi: şu an izlenebilir bölümü var, yeni bölüm/sezon bekleniyor ("Yakında Gelen Diziler"). */
    fun isContinuingSeries(t: Title, now: Long = nowSec()): Boolean =
        t.isSeries && !isUpcoming(t, now) && t.seasons.any { s -> s.episodes.any { it.stream != null && (it.releaseAt ?: 0L) <= now } }

    /** "Yakında": henüz hiç izlenemeyen yapımlar (devamı gelen diziler hariç; onlar ayrı kategoride). */
    fun comingSoonTitles(raw: Catalog?, profile: Profile?, now: Long = nowSec()): List<Title> =
        comingSoon(raw, profile, now).filterNot { isContinuingSeries(it, now) }

    /** "Yakında Gelen Diziler": izlenebilir bölümü olan ve yeni bölümü/sezonu beklenen diziler. */
    fun comingSoonSeries(raw: Catalog?, profile: Profile?, now: Long = nowSec()): List<Title> =
        comingSoon(raw, profile, now).filter { isContinuingSeries(it, now) }

    fun releaseText(at: Long?, series: Boolean, newEpisode: Boolean): String {
        if (at == null) return "Yakında"
        val d = java.util.Date(at * 1000)
        val day = java.text.SimpleDateFormat("d MMMM EEEE", TR).format(d)
        val time = java.text.SimpleDateFormat("HH:mm", TR).format(d)
        val whenText = if (time == "00:00") day else "$day, $time"
        return if (newEpisode) "Yeni bölüm: $whenText" else "$whenText tarihinde geliyor"
    }

    fun visibleTitles(catalog: Catalog?, profile: Profile?): List<Title> {
        val all = catalog?.titles.orEmpty().filter(::hasPlayable)
        return if (profile?.kids == true) all.filter(::isKidsFriendly) else all
    }

    /** Türk yapımı: orijinal dili Türkçe */
    fun isTurkish(t: Title): Boolean = sameLanguage(t.originalLanguage, "tr")

    fun applyFilter(titles: List<Title>, filter: String): List<Title> = when (filter) {
        HomeFilter.SERIES -> titles.filter { it.isSeries }
        HomeFilter.MOVIES -> titles.filter { !it.isSeries }
        HomeFilter.TR_SERIES -> titles.filter { it.isSeries && isTurkish(it) }
        HomeFilter.TR_MOVIES -> titles.filter { !it.isSeries && isTurkish(it) }
        else -> HomeFilter.genreOf(filter)?.let { g -> titles.filter { g in it.genres } } ?: titles
    }

    fun genres(titles: List<Title>): List<String> =
        titles.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    fun episodes(t: Title): List<EpisodeRef> =
        t.seasons.sortedBy { it.number }.flatMap { s ->
            s.episodes.filter { it.stream != null }.sortedBy { it.number }.map { EpisodeRef(s, it) }
        }

    fun findEpisode(t: Title, season: Int?, episode: Int?): EpisodeRef? =
        episodes(t).firstOrNull { it.season.number == season && it.episode.number == episode }

    fun nextEpisode(t: Title, season: Int?, episode: Int?): EpisodeRef? {
        val all = episodes(t)
        val i = all.indexOfFirst { it.season.number == season && it.episode.number == episode }
        return if (i >= 0) all.getOrNull(i + 1) else null
    }

    fun latestProgress(titleId: String, data: ProfileData): WatchProgress? =
        data.progress.values.filter { it.titleId == titleId }.maxByOrNull { it.updatedAt }

    /** "Oynat" butonuna basıldığında nereden başlanacağı. */
    fun playTarget(t: Title, data: ProfileData): PlayTarget? {
        if (!t.isSeries) {
            if (t.stream == null) return null
            val p = data.progress[t.id]
            return PlayTarget(null, null, if (p != null && !p.isFinished) p.positionMs else 0L)
        }
        val eps = episodes(t)
        if (eps.isEmpty()) return null
        val latest = latestProgress(t.id, data)
        if (latest?.season != null && latest.episode != null) {
            val current = findEpisode(t, latest.season, latest.episode)
            if (current != null && !latest.isFinished) return PlayTarget(latest.season, latest.episode, latest.positionMs)
            val next = nextEpisode(t, latest.season, latest.episode)
            if (next != null) return PlayTarget(next.season.number, next.episode.number, 0L)
        }
        val first = eps.first()
        return PlayTarget(first.season.number, first.episode.number, 0L)
    }

    fun continueWatching(titles: List<Title>, data: ProfileData): List<ContinueItem> {
        val byId = titles.associateBy { it.id }
        return data.progress.values.groupBy { it.titleId }
            .mapNotNull { (id, list) ->
                val title = byId[id] ?: return@mapNotNull null
                val latest = list.maxBy { it.updatedAt }
                if (!latest.isFinished) {
                    ContinueItem(title, latest, latest.season, latest.episode) to latest.updatedAt
                } else if (title.isSeries) {
                    val next = nextEpisode(title, latest.season, latest.episode) ?: return@mapNotNull null
                    ContinueItem(title, null, next.season.number, next.episode.number) to latest.updatedAt
                } else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun similar(t: Title, titles: List<Title>, limit: Int = 12): List<Title> =
        titles.asSequence().filter { it.id != t.id }
            .map { it to it.genres.count { g -> g in t.genres } + (if (it.type == t.type) 1 else 0) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Title, Int>> { it.second }.thenByDescending { it.first.rating ?: 0.0 })
            .map { it.first }.take(limit).toList()

    /** Bugünün İlk 10'u: TMDB'nin günlük popülerliğine göre (gerçek hayattaki ilgi); eşitlikte/eksikte puan. */
    fun top10(pool: List<Title>): List<Title> = byPopularity(pool).take(10)

    fun byPopularity(pool: List<Title>): List<Title> =
        pool.sortedWith(compareByDescending<Title> { it.popularity ?: 0.0 }.thenByDescending { it.rating ?: 0.0 })

    fun homeRows(pool: List<Title>, data: ProfileData, filter: String): List<HomeRow> {
        if (pool.isEmpty()) return emptyList()
        val rows = mutableListOf<HomeRow>()
        val byId = pool.associateBy { it.id }
        data.myList.mapNotNull { byId[it] }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow("mylist", "Listem", it) }
        rows += HomeRow("new", "Yeni Eklenenler", pool.sortedByDescending { it.lastChange }.take(20))
        if (pool.size >= 4) {
            val label = when (filter) {
                HomeFilter.SERIES -> "G-Flix'te İlk 10 Dizi"
                HomeFilter.MOVIES -> "G-Flix'te İlk 10 Film"
                HomeFilter.TR_SERIES -> "İlk 10 Türk Dizisi"
                HomeFilter.TR_MOVIES -> "İlk 10 Türk Filmi"
                else -> "G-Flix'te Bugünün İlk 10 Yapımı"
            }
            rows += HomeRow("top10", label, top10(pool), RowStyle.Top10)
        }
        if (filter == HomeFilter.ALL) {
            pool.filter { it.isSeries }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow("series", "Diziler", it.shuffled()) }
            pool.filter { !it.isSeries }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow("movies", "Filmler", it.shuffled()) }
            pool.filter { it.isSeries && isTurkish(it) }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow("tr-series", "Türk Dizileri", it.sortedByDescending { t -> t.lastChange }) }
            pool.filter { !it.isSeries && isTurkish(it) }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow("tr-movies", "Türk Filmleri", it.sortedByDescending { t -> t.lastChange }) }
        }
        data.liked.mapNotNull { byId[it] }.takeIf { it.size >= 2 }?.let { rows += HomeRow("liked", "Beğendiklerin", it) }
        val selectedGenre = HomeFilter.genreOf(filter)
        genres(pool).filter { it != selectedGenre }.forEach { g ->
            val items = pool.filter { g in it.genres }
            if (items.isNotEmpty()) rows += HomeRow("genre:$g", g, items.sortedByDescending { it.rating ?: 0.0 })
        }
        return rows
    }

    private fun normalize(s: String): String {
        val lower = s.lowercase(TR).replace('ı', 'i')
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    fun search(titles: List<Title>, query: String): List<Title> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        return titles.mapNotNull { t ->
            val score = when {
                normalize(t.title).startsWith(q) -> 4
                normalize(t.title).contains(q) -> 3
                t.originalTitle?.let { normalize(it).contains(q) } == true -> 3
                t.names.values.any { normalize(it).contains(q) } -> 3
                t.genres.any { normalize(it).contains(q) } -> 2
                (t.cast + t.creators).any { normalize(it).contains(q) } -> 1
                else -> 0
            }
            if (score > 0) t to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    // ---- Biçimlendirme ----

    fun formatRuntime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m dk"
            m == 0 -> "$h sa"
            else -> "$h sa $m dk"
        }
    }

    fun formatClock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun remainingText(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).toInt()
        return "Kalan " + (formatRuntime(min) ?: "1 dk")
    }

    fun seasonsText(t: Title): String? {
        val seasons = t.seasons.count { s -> s.episodes.any { it.stream != null } }
        if (!t.isSeries || seasons == 0) return null
        return if (seasons == 1) "${episodes(t).size} Bölüm" else "$seasons Sezon"
    }

    fun seasonName(s: Season): String = "${s.number}. Sezon"

    fun languageName(code: String?): String {
        val c = code?.lowercase()?.substringBefore('-') ?: return "Bilinmiyor"
        return when (c) {
            "tr", "tur" -> "Türkçe"
            "en", "eng" -> "İngilizce"
            "und", "" -> "Orijinal"
            else -> Locale.forLanguageTag(c).getDisplayLanguage(TR).replaceFirstChar { it.titlecase(TR) }.ifBlank { c }
        }
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        fun n(x: String?) = when (val c = x?.lowercase()?.substringBefore('-')) {
            "tur" -> "tr"; "eng" -> "en"; else -> c
        }
        return a != null && b != null && n(a) == n(b)
    }

    fun matchPercent(t: Title): Int? = t.rating?.takeIf { it > 0 }?.let { (it * 10).toInt().coerceIn(50, 99) }
}

/**
 * Profil tercihine göre başlık dili ve kapak seçimi.
 * Değişken modda seçim gün içinde sabit kalır (kaydırırken kapaklar titremez), ertesi gün değişir.
 */
object Presentation {

    fun today(): Long = System.currentTimeMillis() / 86_400_000L

    private fun seed(vararg parts: Any?): Int = parts.joinToString("|").hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }

    /** Bu yapım için kullanılacak dil: tr | en | original */
    fun languageFor(t: Title, profile: Profile?, day: Long): String = when (profile?.titleLang) {
        TitleLang.EN -> "en"
        TitleLang.ORIGINAL -> "original"
        TitleLang.MIXED -> if (seed(profile.id, t.id, day, "lang") % 2 == 0) "tr" else "en"
        else -> "tr"
    }

    fun localize(t: Title, profile: Profile?, day: Long = today()): Title {
        if (profile == null) return t
        val lang = languageFor(t, profile, day)
        val rotate = profile.artMode == ArtMode.ROTATE
        val artLang = when (lang) {
            "original" -> t.originalLanguage ?: "en"
            else -> lang
        }
        val name = when (lang) {
            "en" -> t.names["en"]
            "original" -> t.names["original"]
            else -> t.names["tr"]
        }?.takeIf { it.isNotBlank() } ?: t.title
        val s = seed(profile.id, t.id, if (rotate) day else 0)

        // Kapaklarda yazısız (logosuz) görsel: logo ayrıca üstüne konur. Yazısızı yoksa dile uygun yazılı olan.
        val textlessPoster = pickTextless(t.posters, s, rotate)
        val poster = textlessPoster ?: pick(t.posters, artLang, s, rotate, allowTextless = true) ?: t.poster
        val backdrop = pickBackdrop(t.backdrops, s, rotate) ?: t.backdrop
        val logo = pickLogo(t, artLang, name) ?: if (t.logos.isEmpty()) t.logo else null
        val seasons = if (lang == "tr") t.seasons else t.seasons.map { season ->
            season.copy(episodes = season.episodes.map { ep -> ep.copy(title = ep.titleEn?.takeIf { it.isNotBlank() } ?: ep.title) })
        }
        return t.copy(title = name, poster = poster, backdrop = backdrop, logo = logo, seasons = seasons,
            posterHasText = textlessPoster == null && t.posters.isNotEmpty())
    }

    private fun pick(list: List<Art>, lang: String, seed: Int, rotate: Boolean, allowTextless: Boolean): String? {
        if (list.isEmpty()) return null
        list.firstOrNull { it.pinned }?.let { return it.url }
        val matching = list.filter { it.lang == lang }
        val textless = if (allowTextless) list.filter { it.lang == null } else emptyList()
        if (!rotate) return (matching.firstOrNull() ?: textless.firstOrNull() ?: list.firstOrNull { it.lang == "en" } ?: list.first()).url
        val pool = (matching + textless).ifEmpty { list.filter { it.lang == "en" } }.ifEmpty { list }
        return pool[seed % pool.size].url
    }

    /** Sabitlenen ya da yazısız görsel; yoksa null (yazılı görsel kullanılmaz). */
    private fun pickTextless(list: List<Art>, seed: Int, rotate: Boolean): String? {
        list.firstOrNull { it.pinned }?.let { return it.url }
        val textless = list.filter { it.lang == null }
        if (textless.isEmpty()) return null
        return if (rotate) textless[seed % textless.size].url else textless.first().url
    }

    /** Arka plan: yalnız yazısız (logolu görsel hiç kullanılmaz; yoksa katalogdaki varsayılan yazısız arka plan). */
    private fun pickBackdrop(list: List<Art>, seed: Int, rotate: Boolean): String? = pickTextless(list, seed, rotate)

    /** "Türkçe Dublaj", "Türkçe Altyazı" (film ya da dizinin herhangi bir bölümünde varsa). */
    fun languageBadges(t: Title): List<String> {
        val streams = listOfNotNull(t.stream) + t.seasons.flatMap { s -> s.episodes.mapNotNull { it.stream } }
        val audio = streams.flatMap { st -> st.audio + st.versions.map { it.lang } + st.direct?.audio.orEmpty().map { it.lang } + st.direct?.videoAudio.orEmpty() }
        val subs = streams.flatMap { st -> st.subtitles.filter { !it.forced }.map { it.lang } }
        val out = mutableListOf<String>()
        if (!Library.sameLanguage(t.originalLanguage, "tr") && audio.any { Library.sameLanguage(it, "tr") }) out += "Türkçe Dublaj"
        if (subs.any { Library.sameLanguage(it, "tr") }) out += "Türkçe Altyazı"
        return out
    }

    /**
     * Logo: seçilen dilde varsa o; yoksa İngilizce (Netflix de çevrilmemiş logoyu gösterir), sonra yazısız,
     * sonra orijinal dildeki, en son herhangi biri. Hiç logo yoksa ad yazıyla gösterilir.
     */
    private fun pickLogo(t: Title, lang: String, @Suppress("UNUSED_PARAMETER") name: String): String? {
        if (t.logos.isEmpty()) return null
        t.logos.firstOrNull { it.pinned }?.let { return it.url }
        return (t.logos.firstOrNull { it.lang == lang }
            ?: t.logos.firstOrNull { it.lang == "en" }
            ?: t.logos.firstOrNull { it.lang == null }
            ?: t.logos.firstOrNull { it.lang == t.originalLanguage }
            ?: t.logos.first()).url
    }
}
