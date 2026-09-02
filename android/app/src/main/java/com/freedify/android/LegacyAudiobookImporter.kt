package com.freedify.android

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Recovers richer chapter titles already cached by the former WebView UI. */
object LegacyAudiobookImporter {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var activity: Activity? = null

    fun attach(activity: Activity) { this.activity = activity }
    fun detach(activity: Activity) {
        if (this.activity === activity) this.activity = null
    }

    suspend fun importSavedLibrary(): Boolean = suspendCancellableCoroutine { continuation ->
        handler.post {
            val host = activity
            if (host == null || host.isFinishing) {
                continuation.resume(false)
                return@post
            }
            start(host) { imported ->
                if (continuation.isActive) continuation.resume(imported)
            }.also { browser ->
                continuation.invokeOnCancellation { handler.post { destroy(browser) } }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(activity: Activity, finish: (Boolean) -> Unit): WebView {
        val browser = WebView(activity)
        var completed = false
        fun complete(imported: Boolean) {
            if (completed) return
            completed = true
            handler.removeCallbacksAndMessages(browser)
            destroy(browser)
            if (!activity.isFinishing) finish(imported)
        }

        browser.setBackgroundColor(Color.TRANSPARENT)
        browser.alpha = 0f
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
        }
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("localStorage.getItem('freedify_audiobooks')") { encoded ->
                    val payload = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                    if (!payload.isNullOrBlank()) {
                        AudiobookStore.get(activity.applicationContext).importLegacy(payload)
                        complete(true)
                    } else complete(false)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) complete(false)
            }
        }
        activity.addContentView(browser, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))
        handler.postAtTime({ complete(false) }, browser, android.os.SystemClock.uptimeMillis() + 10_000)
        browser.loadUrl(BookDebridApi.BASE_URL)
        return browser
    }

    private fun destroy(browser: WebView) {
        (browser.parent as? ViewGroup)?.removeView(browser)
        browser.stopLoading()
        browser.destroy()
    }
}
