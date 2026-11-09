package com.gflix.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * TMDB'den gelen YouTube fragmanını YouTube'un resmi gömülü oynatıcısıyla (IFrame API) oynatır. Video dosyası
 * indirilmez ya da saklanmaz; bilgisayar bağlantısı gerekmez.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeEmbed(
    videoId: String,
    muted: Boolean,
    controls: Boolean,
    modifier: Modifier = Modifier,
    onEnded: () -> Unit = {},
) {
    val ended = rememberUpdatedState(onEnded)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AColor.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                addJavascriptInterface(object {
                    @JavascriptInterface fun ended() { post { ended.value() } }
                }, "GFlix")
                loadDataWithBaseURL(ORIGIN, html(videoId, muted, controls), "text/html", "utf-8", null)
            }
        },
        update = { web -> web.evaluateJavascript(if (muted) "window.p&&p.mute&&p.mute()" else "window.p&&p.unMute&&p.unMute()", null) },
        onRelease = { web -> web.stopLoading(); web.destroy() },
    )
}

private const val ORIGIN = "https://gflix-archive.app"

private fun html(id: String, muted: Boolean, controls: Boolean): String {
    val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    return """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,#p{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
<body><div id="p"></div>
<script>
var p;
function onYouTubeIframeAPIReady(){
  p=new YT.Player('p',{width:'100%',height:'100%',videoId:'$safe',
    playerVars:{autoplay:1,mute:${if (muted) 1 else 0},controls:${if (controls) 1 else 0},playsinline:1,rel:0,modestbranding:1,iv_load_policy:3,fs:0,origin:'$ORIGIN'},
    events:{onReady:function(e){e.target.playVideo()},onStateChange:function(e){if(e.data===0&&window.GFlix)GFlix.ended()}}});
}
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
""".trimIndent()
}
