package com.gflix.app.data

import android.os.SystemClock
import com.gflix.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tüm veriler private bir GitHub deposunda durur:
 *  - catalog/catalog.json   -> Contents API ile okunur
 *  - videolar / altyazılar  -> Release dosyaları (asset) olarak, API adresiyle okunur
 */
object GitHub {
    const val API = "https://api.github.com"
    val owner: String = BuildConfig.GH_OWNER
    val repo: String = BuildConfig.GH_REPO
    val branch: String = BuildConfig.GH_BRANCH

    fun contentsUrl(path: String): String {
        val base = "$API/repos/$owner/$repo/contents/$path"
        return if (branch.isBlank()) base else "$base?ref=$branch"
    }

    fun buildClient(tokens: TokenHolder): OkHttpClient {
        val resolver = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return OkHttpClient.Builder()
            .addInterceptor(GitHubInterceptor(tokens, resolver))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class TokenHolder(@Volatile var token: String?)

class GitHubHttpException(val code: Int, message: String) : IOException(message)

/**
 * - api.github.com isteklerine yetki başlığını ekler.
 * - Release asset isteklerini (videolar) GitHub'ın verdiği kısa ömürlü imzalı indirme
 *   adresine çevirir ve bu adresi birkaç dakika önbellekte tutar. Böylece video
 *   parçalarının her biri için API limiti harcanmaz ve oynatma akıcı olur.
 */
class GitHubInterceptor(
    private val tokens: TokenHolder,
    private val resolver: OkHttpClient,
) : Interceptor {

    private data class Signed(val url: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Signed>()
    private val locks = ConcurrentHashMap<String, Any>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != "api.github.com") return chain.proceed(request)

        val path = url.encodedPath
        if (ASSET_PATH.containsMatchIn(path)) {
            val key = url.toString()
            val signed = resolve(key, force = false)
                ?: return chain.proceed(authorized(request, octet = true)).also { r -> trackOrClose(r) }
            val first = chain.proceed(request.newBuilder().url(signed).removeHeader("Authorization").build())
            if (first.code != 401 && first.code != 403 && first.code != 404) return first
            // İmzalı adresin süresi dolmuş olabilir: yenile ve bir kez daha dene
            first.close()
            val again = resolve(key, force = true)
                ?: return chain.proceed(authorized(request, octet = true)).also { r -> trackOrClose(r) }
            return chain.proceed(request.newBuilder().url(again).removeHeader("Authorization").build())
        }
        RateLimit.check()
        return chain.proceed(authorized(request, octet = false)).also { r -> trackOrClose(r) }
    }

    /** İstek hakkını izler; hak bittiyse yanıtı kapatıp yoğunluk hatası verir. */
    private fun trackOrClose(r: Response) {
        try { RateLimit.track(r) } catch (e: RateLimit.Blocked) { r.close(); throw e }
    }

    private fun authorized(request: Request, octet: Boolean): Request {
        val b = request.newBuilder().header("X-GitHub-Api-Version", "2022-11-28")
        tokens.token?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        when {
            octet -> b.header("Accept", "application/octet-stream")
            request.url.encodedPath.contains("/contents/") -> b.header("Accept", "application/vnd.github.raw+json")
        }
        return b.build()
    }

    /** İmzalı indirme adresini döndürür; GitHub yönlendirme yapmazsa null. */
    private fun resolve(assetUrl: String, force: Boolean): String? {
        if (!force) cache[assetUrl]?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }?.let { return it.url }
        val lock = locks.getOrPut(assetUrl) { Any() }
        synchronized(lock) {
            if (!force) cache[assetUrl]?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }?.let { return it.url }
            // Önbellekteki imzalı adres geçerliyse buraya gelinmez: izleyenin videosu engelden etkilenmez
            RateLimit.check()
            val req = authorized(Request.Builder().url(assetUrl).get().build(), octet = true)
            resolver.newCall(req).execute().use { resp ->
                RateLimit.track(resp)
                if (resp.code in 300..399) {
                    val location = resp.header("Location")
                        ?: throw GitHubHttpException(resp.code, "GitHub yönlendirme adresi vermedi")
                    cache[assetUrl] = Signed(location, SystemClock.elapsedRealtime() + SIGNED_TTL_MS)
                    return location
                }
                if (resp.isSuccessful) return null
                throw GitHubHttpException(resp.code, "GitHub video isteği başarısız (HTTP ${resp.code})")
            }
        }
    }

    companion object {
        private val ASSET_PATH = Regex("/releases/assets/\\d+")
        private const val SIGNED_TTL_MS = 4 * 60 * 1000L
    }
}
