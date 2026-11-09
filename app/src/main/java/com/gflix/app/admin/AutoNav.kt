package com.gflix.app.admin

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.gflix.app.data.AppJson
import kotlin.coroutines.resume

/**
 * Sayfadan Yakala'nın otomatik gezinmesi: sitenin kendi arama kutusuna yazar, sonuçları ve dizi sayfasındaki bölüm
 * linklerini toplar, oynatıcıya gerçek bir dokunuş gönderir. Link yakalama ve indirme yöntemi değişmez.
 */
data class SiteResult(val url: String, val title: String, val image: String?, val kind: String)   // kind: series | movie | ?
data class SiteEpisode(val season: Int, val episode: Int, val url: String, val title: String)

object AutoNav {
    /** evaluateJavascript → JSON metni (dönen değer JSON olarak tırnaklıdır) */
    suspend fun eval(web: WebView, js: String): String = kotlinx.coroutines.withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { c ->
            // web.post değil: tarayıcı henüz bir pencereye bağlı değilse (arka planda) post edilen iş hiç çalışmaz, beklerdi
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { web.evaluateJavascript(js) { r -> if (c.isActive) c.resume(r ?: "null") } }
                    .onFailure { if (c.isActive) c.resume("null") }
            }
        }
    } ?: "null"

    private fun unquote(r: String): String = runCatching { AppJson.parseToJsonElement(r).jsonPrimitive.content }.getOrDefault("")

    /** Arama kutusunu bul, adı yaz. "ok" | "noinput" */
    suspend fun typeSearch(web: WebView, q: String): String = unquote(eval(web, """
        (function(q){
          var ins = Array.prototype.slice.call(document.querySelectorAll('input')).filter(function(i){
            if (i.type === 'hidden' || i.type === 'password' || i.type === 'checkbox') return false;
            var s = [i.type, i.name, i.id, i.placeholder, i.getAttribute('aria-label'), i.className].join(' ');
            return /search|ara|query|keyword|\bq\b|\bs\b|film|dizi/i.test(s);
          });
          if (!ins.length) return 'noinput';
          var i = ins[0];
          try { i.scrollIntoView({block:'center'}); } catch (e) {}
          i.focus();
          var set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; set.call(i, q);
          ['input','keyup','change'].forEach(function(ev){ i.dispatchEvent(new Event(ev, {bubbles:true})); });
          window.__gfxSearchInput = i;
          return 'ok';
        })(${AppJson.encodeToString(kotlinx.serialization.serializer<String>(), q)})
    """.trimIndent()))

    /** Arama kutusundayken gerçek Enter tuşu (sayfa içi tıklama gibi değil, klavyeden basılmış gibi) */
    fun pressEnter(web: WebView) {
        web.post {
            web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        web.postDelayed({ web.evaluateJavascript("try{var i=window.__gfxSearchInput; if(i&&i.form&&document.contains(i)) i.form.submit();}catch(e){}", null) }, 1500)
    }

    /** Sayfadaki, adı aranan kelimeleri içeren yapım linkleri */
    suspend fun results(web: WebView, q: String): List<SiteResult> {
        val r = unquote(eval(web, """
            (function(q){
              function norm(s){ return (s||'').toLowerCase().replace(/ı/g,'i').replace(/[çğöşü]/g,function(c){return {'ç':'c','ğ':'g','ö':'o','ş':'s','ü':'u'}[c];}).replace(/[^a-z0-9]+/g,' ').trim(); }
              var words = norm(q).split(' ').filter(function(w){ return w.length > 1; });
              var out = {}, host = location.hostname;
              document.querySelectorAll('a[href]').forEach(function(a){
                var h = a.href.split('#')[0];
                if (!/^https?:/.test(h) || a.hostname !== host) return;
                if (!a.pathname || a.pathname === '/' || a.pathname.length < 3) return;     // ana sayfa linki (logo vb.)
                if (/[?&](s|q)=|\/(ara|search|tag|etiket|kategori|category|tur|genre|oyuncu|actor|yil|year|page)\b/i.test(h)) return;
                var img = a.querySelector('img');
                var t = (a.getAttribute('title') || (img && (img.getAttribute('alt') || img.getAttribute('title'))) || a.innerText || '').trim().replace(/\s+/g,' ');
                var hay = norm(t + ' ' + decodeURIComponent(a.pathname).replace(/[-_/]/g,' '));
                if (!words.length || !words.every(function(w){ return hay.indexOf(w) >= 0; })) return;
                if (/sezon[-_/]?\d+.*b[oö]l[uü]m|season[-_/]?\d+.*episode/i.test(h)) return;   // bölüm değil yapım sayfası
                var kind = /\/(dizi|series|tv|show)s?\//i.test(h) ? 'series' : (/\/(film|movie)s?\//i.test(h) ? 'movie' : '?');
                var src = img ? (img.getAttribute('data-src') || img.getAttribute('src') || '') : '';
                if (!out[h] || (t.length > out[h].t.length)) out[h] = { u: h, t: t.slice(0, 90) || decodeURIComponent(a.pathname), i: src ? new URL(src, location.href).href : '', k: kind };
              });
              return JSON.stringify(Object.keys(out).map(function(k){ return out[k]; }).slice(0, 40));
            })(${AppJson.encodeToString(kotlinx.serialization.serializer<String>(), q)})
        """.trimIndent()))
        return runCatching {
            (AppJson.parseToJsonElement(r) as JsonArray).map { e ->
                val o = e.jsonObject
                SiteResult(o["u"]!!.jsonPrimitive.content, o["t"]!!.jsonPrimitive.content, o["i"]?.jsonPrimitive?.content?.ifBlank { null }, o["k"]?.jsonPrimitive?.content ?: "?")
            }
        }.getOrDefault(emptyList())
    }

    private const val EPISODES_JS = """
        (function(){
          var out = {};
          var pats = [/sezon[-_/]?(\d+)[^\d]{1,12}b[oö]l[uü]m[-_/]?(\d+)/i, /(\d+)[-_.]?sezon[-_.]?(\d+)[-_.]?b[oö]l[uü]m/i,
                      /season[-_/]?(\d+)[^\d]{1,12}episode[-_/]?(\d+)/i, /[-_/]s(\d{1,2})[-_]?e(\d{1,3})(?![\d])/i];
          document.querySelectorAll('a[href]').forEach(function(a){
            var h = a.href.split('#')[0], p = decodeURIComponent(a.pathname || '');
            for (var i = 0; i < pats.length; i++) {
              var m = p.match(pats[i]);
              if (m) {
                var k = (+m[1]) + 'x' + (+m[2]);
                if (!out[k]) out[k] = { s: +m[1], e: +m[2], u: h, t: (a.getAttribute('title') || a.innerText || '').trim().replace(/\s+/g,' ').slice(0, 80) };
                break;
              }
            }
          });
          return JSON.stringify(Object.keys(out).map(function(k){ return out[k]; }));
        })()
    """

    suspend fun episodes(web: WebView): List<SiteEpisode> = runCatching {
        (AppJson.parseToJsonElement(unquote(eval(web, EPISODES_JS.trimIndent()))) as JsonArray).map { e ->
            val o = e.jsonObject
            SiteEpisode(o["s"]!!.jsonPrimitive.intOrNull ?: 0, o["e"]!!.jsonPrimitive.intOrNull ?: 0, o["u"]!!.jsonPrimitive.content, o["t"]?.jsonPrimitive?.content.orEmpty())
        }.filter { it.season > 0 && it.episode > 0 }
    }.getOrDefault(emptyList())

    /** Sezon sekmeleri ("1. Sezon", "Sezon 2", "Season 3"): sayısı; tıklanınca o sezonun bölümleri yüklenir */
    suspend fun seasonTabs(web: WebView): Int = unquote(eval(web, """
        (function(){
          var re = /^\s*(\d+)\s*\.?\s*sezon\s*$|^\s*sezon\s*(\d+)\s*$|^\s*season\s*(\d+)\s*$/i;
          var els = Array.prototype.slice.call(document.querySelectorAll('a,button,li,span,div')).filter(function(e){
            var t = (e.innerText || '').trim(); return t.length < 20 && re.test(t) && e.children.length <= 2;
          });
          window.__gfxSeasonTabs = els;
          return String(els.length);
        })()
    """.trimIndent())).toIntOrNull() ?: 0

    suspend fun clickSeasonTab(web: WebView, i: Int) {
        eval(web, "(function(){var e=(window.__gfxSeasonTabs||[])[$i]; if(e){ try{e.scrollIntoView({block:'center'});}catch(x){} e.click(); } return 'ok';})()")
    }

    /**
     * Oynatıcıya gerçek dokunuş: sayfadaki en büyük oynatıcı (iframe / video / player kutusu) görünür yapılır, ortasına
     * telefonun kendisi dokunur (oynatıcıların çoğu sayfa içinden yapılan tıklamayı kabul etmez, dokunuşu kabul eder).
     */
    /** Kategori / liste sayfasındaki yapımlar (kapak resimli yapım bağlantıları): kategoriden seçme ekranı için */
    suspend fun pageItems(web: WebView): List<SiteResult> {
        val r = unquote(eval(web, """
            (function(){
              var out = {}, order = [], host = location.hostname;
              document.querySelectorAll('a[href]').forEach(function(a){
                var h = a.href.split('#')[0];
                if (!/^https?:/.test(h) || a.hostname !== host) return;
                if (!a.pathname || a.pathname === '/' || a.pathname.length < 3) return;
                if (/[?&](s|q)=|\/(ara|search|tag|etiket|kategori|category|tur|genre|oyuncu|actor|yil|year|page|sayfa)\b/i.test(h)) return;
                if (/sezon[-_/]?\d+.*b[oö]l[uü]m|season[-_/]?\d+.*episode/i.test(h)) return;
                var img = a.querySelector('img');
                if (!img) return;                                   // yapım kartları kapak resimlidir (menü linkleri değil)
                var t = (a.getAttribute('title') || img.getAttribute('alt') || img.getAttribute('title') || a.innerText || '').trim().replace(/\s+/g,' ');
                if (!t) return;
                var kind = /\/(dizi|series|tv|show)s?\//i.test(h) ? 'series' : (/\/(film|movie)s?\//i.test(h) ? 'movie' : '?');
                var src = img.getAttribute('data-src') || img.getAttribute('src') || '';
                if (!out[h]) { order.push(h); out[h] = { u: h, t: t.slice(0, 90), i: src ? new URL(src, location.href).href : '', k: kind }; }
              });
              return JSON.stringify(order.map(function(k){ return out[k]; }).slice(0, 300));
            })()
        """.trimIndent()))
        return runCatching {
            (AppJson.parseToJsonElement(r) as JsonArray).map { e ->
                val o = e.jsonObject
                SiteResult(o["u"]!!.jsonPrimitive.content, o["t"]!!.jsonPrimitive.content, o["i"]?.jsonPrimitive?.content?.ifBlank { null }, o["k"]?.jsonPrimitive?.content ?: "?")
            }
        }.getOrDefault(emptyList())
    }

    /** Sayfanın sonuna kaydır, "Daha fazla" düğmesi varsa bas (aşağı kaydırdıkça yüklenen kategori sayfaları için) */
    suspend fun scrollMore(web: WebView) {
        eval(web, """
            (function(){
              window.scrollTo(0, document.body.scrollHeight);
              document.querySelectorAll('button,a,div[role=button]').forEach(function(b){
                var t = (b.innerText || '').trim().toLowerCase();
                if (/^(daha fazla( göster| yükle)?|load more|show more|devamını (göster|yükle)|daha fazla film|daha fazla dizi)$/.test(t)) {
                  try { if (!b.href || b.href.indexOf('#') >= 0 || b.tagName !== 'A') b.click(); } catch (e) {}
                }
              });
              return '1';
            })()
        """.trimIndent())
    }

    /** Sayfa değiştirmeyen (sayfanın içinde yenilenen) sayfalama: "Sonraki"ye ya da bir sonraki sayıya bas. Basıldıysa true */
    suspend fun clickNext(web: WebView): Boolean = unquote(eval(web, """
        (function(){
          function vis(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
          var els = Array.prototype.slice.call(document.querySelectorAll('a,button,li,span,div[role=button]'));
          for (var i = 0; i < els.length; i++) {
            var e = els[i];
            var t = (e.innerText || e.getAttribute('aria-label') || e.getAttribute('title') || '').trim().toLowerCase();
            if (/^(sonraki|sonraki sayfa|ileri|next|next page|»|›)$/.test(t) && vis(e) && !/disabled/i.test(e.className || '')) {
              try { e.scrollIntoView({block: 'center'}); e.click(); return '1'; } catch (x) {}
            }
          }
          // etkin sayfa numarası + 1
          var nums = els.filter(function(e){ return /^\d{1,3}$/.test((e.innerText || '').trim()) && vis(e) && e.children.length === 0; });
          var act = nums.filter(function(e){ var c = (e.className || '') + ' ' + ((e.parentElement && e.parentElement.className) || '');
            return /active|current|selected/i.test(c) || e.getAttribute('aria-current') === 'page'; })[0];
          if (act) {
            var want = String(+act.innerText.trim() + 1);
            var nx = nums.filter(function(e){ return e.innerText.trim() === want; })[0];
            if (nx) { try { nx.scrollIntoView({block: 'center'}); nx.click(); return '1'; } catch (x) {} }
          }
          return '';
        })()
    """.trimIndent())) == "1"

    /** Kategori sayfasının sonraki sayfası (rel=next, "Sonraki", "»" ya da bir sonraki sayı) */
    suspend fun nextPage(web: WebView): String = unquote(eval(web, """
        (function(){
          var host = location.hostname, cur = location.href.split('#')[0];
          var n = document.querySelector('a[rel="next"], link[rel="next"]');
          if (n && n.href && n.href !== cur) return n.href;
          var best = '';
          document.querySelectorAll('a[href]').forEach(function(a){
            if (best || a.hostname !== host) return;
            var t = (a.innerText || a.getAttribute('aria-label') || a.getAttribute('title') || '').trim().toLowerCase();
            if (/^(sonraki|sonraki sayfa|ileri|next|»|›|>)$/.test(t) && a.href.split('#')[0] !== cur) best = a.href;
          });
          if (best) return best;
          var m = cur.match(/(\/(?:page|sayfa)\/|[?&](?:page|sayfa)=)(\d+)/i);
          var want = m ? (+m[2] + 1) : 2;
          document.querySelectorAll('a[href]').forEach(function(a){
            if (best || a.hostname !== host) return;
            if ((a.innerText || '').trim() === String(want) && /(page|sayfa)/i.test(a.href)) best = a.href;
          });
          return best;
        })()
    """.trimIndent()))

    suspend fun tapPlayer(web: WebView): Boolean {
        val r = unquote(eval(web, """
            (function(){
              var c = Array.prototype.slice.call(document.querySelectorAll('iframe,video,[id*="player" i],[class*="player" i],[class*="video" i]'));
              var best = null, area = 0;
              c.forEach(function(e){ var r = e.getBoundingClientRect(); var a = r.width * r.height;
                if (r.width >= 200 && r.height >= 120 && a > area) { best = e; area = a; } });
              if (!best) return '';
              try { best.scrollIntoView({block:'center'}); } catch (x) {}
              var r = best.getBoundingClientRect();
              return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2, w: window.innerWidth });
            })()
        """.trimIndent()))
        if (r.isBlank()) return false
        val o = runCatching { AppJson.parseToJsonElement(r).jsonObject }.getOrNull() ?: return false
        val cssX = o["x"]!!.jsonPrimitive.content.toFloat()
        val cssY = o["y"]!!.jsonPrimitive.content.toFloat()
        val innerW = o["w"]!!.jsonPrimitive.content.toFloat().coerceAtLeast(1f)
        web.post {
            val scale = web.width / innerW
            val x = cssX * scale
            val y = cssY * scale
            if (x <= 0 || y <= 0 || x >= web.width || y >= web.height) return@post
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0)
            web.dispatchTouchEvent(down); web.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
        }
        return true
    }

    /** Sayfadaki sekmeye/düğmeye adıyla bas (ör. oynatıcı seçimi "Rapidrame"); kullanıcının dokunması gibi. */
    suspend fun clickTab(web: WebView, name: String): Boolean = unquote(eval(web, """
        (function(n){
          n = n.toLowerCase();
          var els = document.querySelectorAll('a,button,li,span,div,label');
          for (var i = 0; i < els.length; i++) {
            var e = els[i];
            if (e.children.length > 2) continue;
            if ((e.textContent || '').trim().toLowerCase() !== n) continue;
            var r = e.getBoundingClientRect(); if (r.width < 4 || r.height < 4) continue;
            try { e.scrollIntoView({block:'center'}); } catch (x) {}
            e.click(); return 'ok';
          }
          return '';
        })(${AppJson.encodeToString(kotlinx.serialization.serializer<String>(), name)})
    """.trimIndent())) == "ok"
}
