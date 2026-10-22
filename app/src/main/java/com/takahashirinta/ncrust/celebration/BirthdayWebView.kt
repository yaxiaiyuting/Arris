package com.takahashirinta.ncrust.celebration

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import org.json.JSONObject

/** Only packaged assets are served. This WebView cannot navigate or use network/file access. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class BirthdayWebView(context: Context, private val config: JSONObject) : WebView(context) {
    var onEvent: (String) -> Unit = {}
    private var disposed = false

    private inner class Bridge {
        @JavascriptInterface
        fun event(value: String) {
            if (value !in setOf("READY", "PLAYING", "WAITING", "DONE", "ERROR")) return
            post { if (!disposed) {
                if (value == "READY") configurePage()
                onEvent(value)
            } }
        }
    }

    init {
        if (com.takahashirinta.ncrust.BuildConfig.DEBUG) setWebContentsDebuggingEnabled(true)
        setBackgroundColor(Color.rgb(8, 22, 15))
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addJavascriptInterface(Bridge(), "ArrisHost")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val path = uri.path.orEmpty()
                if (uri.scheme != "https" || uri.host != "arris-celebration.invalid" ||
                    !path.startsWith("/birthday/") || path.split('/').any { it == ".." || it == "." }) return denied()
                return try {
                    val mime = when (path.substringAfterLast('.')) {
                        "html" -> "text/html"
                        "css" -> "text/css"
                        "js" -> "application/javascript"
                        "png" -> "image/png"
                        else -> return denied()
                    }
                    WebResourceResponse(mime, "UTF-8", context.assets.open(path.removePrefix("/")))
                } catch (_: Exception) { denied() }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!disposed && url.startsWith("https://arris-celebration.invalid/birthday/index.html")) {
                    configurePage()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                post { if (!disposed) onEvent("ERROR") }
                return true
            }
        }
        loadUrl("https://arris-celebration.invalid/birthday/index.html?app=1&still=1&t=0&clean=1&motion=1")
    }

    private fun configurePage() {
        evaluateJavascript("window.ArrisBirthday && window.ArrisBirthday.configure($config)", null)
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    fun command(action: String) {
        if (!disposed && action in setOf("blow", "replay")) {
            evaluateJavascript("window.ArrisBirthday && window.ArrisBirthday.$action()", null)
        }
    }

    fun release() {
        if (disposed) return
        evaluateJavascript("window.ArrisBirthday && window.ArrisBirthday.stop()", null)
        disposed = true
        onEvent = {}
        stopLoading()
        removeJavascriptInterface("ArrisHost")
        onPause()
        removeAllViews()
        destroy()
    }
}
