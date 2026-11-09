package com.gflix.app.archive

/** Progress measures the current phase, never invents a percentage for a remote queue. */
object PhoneProgress {
    fun percent(done: Long, total: Long): Int =
        if (total <= 0) -1 else (done.coerceAtLeast(0).toDouble() / total * 100).toInt().coerceIn(0, 100)

    fun clock(ms: Long): String {
        val sec = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    }

    fun verified(size: Long, md5: String?, remote: Pair<Long, String?>?): Boolean =
        size > 0 && !md5.isNullOrBlank() && remote?.first == size && md5.equals(remote?.second, true)

    fun label(phase: String): String = when (phase) {
        "download" -> "1/5 · İndirme ve birleştirme"
        "upload" -> "2/5 · Arşive yükleme"
        "verify" -> "3/5 · Arşivde boyut ve MD5 kontrolü"
        "catalog" -> "4/5 · Katalog doğrulama"
        "cleanup" -> "5/5 · Telefonu temizleme"
        "done" -> "Tamamlandı"
        else -> "Sırada"
    }
}
