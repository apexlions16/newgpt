package com.gflix.app.offline

import android.content.Context
import com.gflix.app.data.AppJson
import com.gflix.app.data.PcServer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient

/**
 * Doğrudan indirme (video telefona siteden, bilgisayardan geçmeden): sitenin istediği başlıklar (Referer vb.)
 * indirme isteklerine eklenir. Uygulama kapanıp açılınca süren indirmeler için saklanır.
 */
object DirectHeaders {
    private const val PREFS = "gflix.direct"
    @Volatile private var headers: Map<String, String> = emptyMap()
    private val ser = MapSerializer(String.serializer(), String.serializer())

    fun load(c: Context) {
        headers = runCatching { AppJson.decodeFromString(ser, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("h", "{}") ?: "{}") }
            .getOrDefault(emptyMap())
    }

    fun set(c: Context, h: Map<String, String>) {
        if (h.isEmpty()) return
        headers = h
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("h", AppJson.encodeToString(ser, h)).apply()
    }

    /** İndirme istemcisi: bilgisayara ve GitHub'a giden isteklere dokunmaz, diğerlerine (site) başlıkları ekler */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder().addInterceptor { chain ->
        val r = chain.request()
        val hp = "${r.url.host}:${r.url.port}"
        if (headers.isEmpty() || PcServer.isPc(hp) || r.url.host.endsWith("github.com") || r.url.host.endsWith("githubusercontent.com")) {
            chain.proceed(r)
        } else {
            val b = r.newBuilder()
            headers.forEach { (k, v) -> if (r.header(k) == null) b.header(k, v) }
            chain.proceed(b.build())
        }
    }.build()
}
