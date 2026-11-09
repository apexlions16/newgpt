package com.gflix.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Response
import java.io.IOException

/**
 * GitHub istek hakkı (bütün telefonlar aynı anahtarı paylaşır: saatte 5000 istek). Kullanıcıya "sınır" gösterilmez;
 * bunun yerine yeni izlemeler sıraya alınır ("Sıradasın"), hak yenilenince kendiliğinden başlar.
 *
 *  - Her api.github.com yanıtındaki X-RateLimit-Remaining / -Reset başlıkları izlenir.
 *  - Kalan hak [RESERVE]'ın altına inince yeni izleme başlatılmaz (şu an izleyenler kesilmesin diye hak onlara kalır).
 *  - Hak biterse (403/429) ya da GitHub "biraz bekle" derse (Retry-After) o ana kadar hiç istek atılmaz;
 *    istekler [Blocked] hatasıyla hemen döner, oynatıcı kaldığı yerde bekleyip sonra devam eder.
 */
object RateLimit {
    data class State(val remaining: Int? = null, val limit: Int? = null, val resetAt: Long = 0, val blockedUntil: Long = 0)

    /** Yeni izleme için bırakılan pay (izleyen biri saatte ~75-200 istek harcar) */
    const val RESERVE = 250

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    class Blocked(val untilMs: Long) : IOException("Yoğunluk: ${(untilMs - System.currentTimeMillis()) / 1000} sn sonra tekrar")

    private fun now() = System.currentTimeMillis()

    internal fun resetForTest() { _state.value = State() }

    /** Engel varsa istek atmadan hata ver (hakkı boşa harcamamak için). */
    fun check() {
        val until = _state.value.blockedUntil
        if (until > now()) throw Blocked(until)
    }

    /** Yanıt başlıklarını işler; hak bittiyse engeller ve [Blocked] fırlatır. */
    fun track(resp: Response) {
        val remaining = resp.header("X-RateLimit-Remaining")?.toIntOrNull()
        val limit = resp.header("X-RateLimit-Limit")?.toIntOrNull()
        val reset = resp.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
        val retryAfter = resp.header("Retry-After")?.toLongOrNull()?.times(1000)
        val s = _state.value
        var next = s.copy(
            remaining = remaining ?: s.remaining, limit = limit ?: s.limit,
            resetAt = reset ?: s.resetAt,
        )
        val limited = (resp.code == 403 || resp.code == 429) && (remaining == 0 || retryAfter != null)
        if (limited) {
            // Retry-After (ikincil sınır) ya da saatlik hakkın yenilenme anı; küçük bir pay ekle
            val until = (retryAfter?.let { now() + it } ?: reset ?: (now() + 60_000)) + 3_000
            next = next.copy(blockedUntil = maxOf(until, s.blockedUntil), remaining = if (retryAfter == null) 0 else next.remaining)
        }
        _state.value = next
        if (limited) throw Blocked(next.blockedUntil)
    }

    /** Yeni bir izleme şimdi başlayabilir mi? */
    fun canStart(): Boolean {
        val s = _state.value
        if (s.blockedUntil > now()) return false
        val r = s.remaining ?: return true
        if (r >= RESERVE) return true
        return s.resetAt in 1..now() // yenilenme anı geçtiyse sayaç eskidir
    }

    /** Sıradaki biri için tahmini bekleme (ms) */
    fun waitMs(): Long {
        val s = _state.value
        val until = maxOf(s.blockedUntil, if ((s.remaining ?: Int.MAX_VALUE) < RESERVE) s.resetAt else 0)
        return (until - now()).coerceAtLeast(0)
    }

    /** Hata zincirinde yoğunluk engeli var mı (oynatıcı hataları sarılı gelir) */
    fun blockedIn(t: Throwable?): Blocked? {
        var e = t
        repeat(8) { if (e is Blocked) return e as Blocked; e = e?.cause ?: return null }
        return null
    }
}
