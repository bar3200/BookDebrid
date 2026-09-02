package com.freedify.android

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AudiobookBay ignores ordinary query-string searches on some Android routes.
 * This isolated 1x1 WebView submits the site's real form and returns only
 * result metadata to the native Compose screen. It has no privileged bridge,
 * no access to the AllDebrid key, and is destroyed after every request.
 */
object NativeAudiobookSearch {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var activityRef = WeakReference<Activity>(null)

    fun attach(activity: Activity) { activityRef = WeakReference(activity) }
    fun detach(activity: Activity) { if (activityRef.get() === activity) activityRef.clear() }

    suspend fun search(query: String): List<Audiobook> = suspendCancellableCoroutine { continuation ->
        val owner = activityRef.get()
        if (owner == null) {
            continuation.resumeWithException(ApiException("Audiobook search is unavailable while the app is in the background"))
            return@suspendCancellableCoroutine
        }
        owner.runOnUiThread {
            start(owner, query) { result ->
                if (continuation.isActive) {
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            }.also { webView -> continuation.invokeOnCancellation { handler.post { webView.destroy() } } }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(activity: Activity, query: String, finish: (Result<List<Audiobook>>) -> Unit): WebView {
        val browser = WebView(activity)
        var stage = 0
        var completed = false
        val timeout = Runnable {
            if (!completed) complete(browser, activity, finish, Result.failure(ApiException("AudiobookBay search timed out"))).also { completed = true }
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
        CookieManager.getInstance().setAcceptCookie(true)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                return host != "audiobookbay.lu" && !host.endsWith(".audiobookbay.lu")
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (completed) return
                if (stage == 0) {
                    stage = 1
                    val quoted = JSONObject.quote(query)
                    view.evaluateJavascript(
                        """
                        (() => {
                            const input = document.querySelector('input[name="s"]');
                            const form = input?.form || input?.closest('form');
                            if (!input || !form) return 'missing';
                            input.value = $quoted;
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                            if (typeof form.requestSubmit === 'function') form.requestSubmit(); else form.submit();
                            return 'submitted';
                        })()
                        """.trimIndent(),
                    ) { result ->
                        if (result == JSONObject.quote("missing") && !completed) {
                            completed = true
                            complete(browser, activity, finish, Result.failure(ApiException("AudiobookBay search form was not found")))
                        }
                    }
                } else {
                    handler.postDelayed({ extract(browser, activity, query, finish) { completed = true } }, 900)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !completed) {
                    completed = true
                    complete(browser, activity, finish, Result.failure(ApiException("AudiobookBay failed to load: ${error.description}")))
                }
            }
        }
        activity.addContentView(
            browser,
            FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END),
        )
        handler.postDelayed(timeout, 30_000)
        browser.loadUrl("https://audiobookbay.lu/")
        return browser
    }

    private fun extract(
        browser: WebView,
        activity: Activity,
        query: String,
        finish: (Result<List<Audiobook>>) -> Unit,
        markComplete: () -> Unit,
    ) {
        browser.evaluateJavascript(
            """
            (() => JSON.stringify({
                heading: (document.querySelector('h1')?.innerText || '').trim(),
                url: location.href,
                results: [...document.querySelectorAll('div.post')].map(post => {
                    const link = post.querySelector('.postTitle h2 a');
                    if (!link) return null;
                    const rawTitle = (link.textContent || '').trim();
                    let title = rawTitle, author = '';
                    const separator = rawTitle.lastIndexOf(' - ');
                    if (separator > 0) {
                        const candidate = rawTitle.slice(separator + 3).trim();
                        if (candidate.length <= 80 && !/(audiobook|\bbooks?\b|series)/i.test(candidate)) {
                            title = rawTitle.slice(0, separator).trim(); author = candidate;
                        }
                    }
                    let id = link.getAttribute('href') || '';
                    try { id = new URL(id, location.href).pathname.replace(/^\/+|\/+$/g, ''); } catch (_) {}
                    return {
                        id, title, author, cover_image: post.querySelector('img')?.src || '',
                        description: (post.querySelector('.postContent')?.innerText || '').trim().slice(0, 203),
                        genres: [...post.querySelectorAll('a[href*="/genre/"], a[href*="/category/"]')]
                            .map(a => (a.textContent || '').trim()).filter(Boolean).slice(0, 8)
                    };
                }).filter(item => item?.id && item?.title)
            }))()
            """.trimIndent(),
        ) { encoded ->
            val result = runCatching {
                val decoded = JSONTokener(encoded).nextValue() as String
                val payload = JSONObject(decoded)
                val heading = payload.optString("heading").lowercase()
                val url = payload.optString("url")
                if (!url.contains("?s=") && !heading.contains(query.lowercase())) {
                    throw ApiException("AudiobookBay returned its homepage instead of search results")
                }
                val array = payload.optJSONArray("results")
                (0 until (array?.length() ?: 0)).mapNotNull { index ->
                    array?.optJSONObject(index)?.let(Audiobook::fromSearch)
                }
            }
            markComplete()
            complete(browser, activity, finish, result)
        }
    }

    private fun complete(
        browser: WebView,
        activity: Activity,
        finish: (Result<List<Audiobook>>) -> Unit,
        result: Result<List<Audiobook>>,
    ) {
        handler.removeCallbacksAndMessages(null)
        (browser.parent as? ViewGroup)?.removeView(browser)
        browser.stopLoading()
        browser.destroy()
        if (!activity.isFinishing) finish(result)
    }
}
