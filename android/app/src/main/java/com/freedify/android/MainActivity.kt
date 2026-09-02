package com.freedify.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : AppCompatActivity() {
    private lateinit var secureSettings: SecureSettings
    private var webView: WebView? = null
    private var webViewContainer: FrameLayout? = null
    private var audiobookSearchWebView: WebView? = null
    private var audiobookSearchRequestId: String? = null
    private var audiobookSearchQuery: String = ""
    private var audiobookSearchPage: Int = 1
    private var audiobookSearchStage: Int = 0
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(
            if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            },
        )
    }

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val contents = pendingExport
        pendingExport = null
        val destination = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || contents == null || destination == null) return@registerForActivityResult
        try {
            val output = contentResolver.openOutputStream(destination)
                ?: throw IllegalStateException("The selected destination cannot be opened")
            output.bufferedWriter().use { it.write(contents) }
            Toast.makeText(this, "Backup exported", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Could not export backup: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureSettings = SecureSettings(this)
        PlaybackService.commandHandler = { command, value ->
            runOnUiThread {
                webView?.evaluateJavascript(
                    "window.FreedifyAndroidMedia?.handleCommand(${JSONObject.quote(command)}, $value)",
                    null,
                )
            }
        }
        requestNotificationPermission()

        val apiKey = secureSettings.getApiKey()
        if (apiKey.isNullOrBlank()) {
            showFirstRunScreen()
        } else {
            launchFreedify(apiKey)
        }
    }

    private fun showFirstRunScreen() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(18, 18, 24))
        }
        container.addView(TextView(this).apply {
            text = "BookDebrid"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = "Connect AllDebrid to search, save, and listen without running a separate server. Your API key is encrypted with Android Keystore and never added to the APK."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, padding)
        })
        val input = apiKeyInput()
        container.addView(input, matchWidthWrapHeight())
        container.addView(Button(this).apply {
            text = "Save and open my library"
            isAllCaps = false
            textSize = 16f
            minHeight = (52 * resources.displayMetrics.density).toInt()
            backgroundTintList = ColorStateList.valueOf(Color.rgb(99, 102, 241))
            setOnClickListener {
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    input.error = "API key is required"
                } else {
                    secureSettings.saveApiKey(key)
                    launchFreedify(key)
                }
            }
        }, matchWidthWrapHeight())
        setContentView(container)
    }

    private fun showSettingsDialog() {
        val input = apiKeyInput()
        AlertDialog.Builder(this)
            .setTitle("Update AllDebrid API key")
            .setMessage("The existing encrypted key is not displayed. Enter a replacement key.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotBlank()) {
                    secureSettings.saveApiKey(key)
                    if (webView == null) {
                        launchFreedify(key)
                    } else {
                        BackendManager.startOrUpdate(
                            applicationContext,
                            key,
                            onReady = {
                                Toast.makeText(
                                    this,
                                    "AllDebrid API key updated",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onError = { message ->
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                } else {
                    Toast.makeText(this, "API key was not changed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun apiKeyInput() = EditText(this).apply {
        hint = "AllDebrid API key"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
        minHeight = (54 * resources.displayMetrics.density).toInt()
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(150, 150, 165))
        backgroundTintList = ColorStateList.valueOf(Color.rgb(129, 140, 248))
    }

    private fun launchFreedify(apiKey: String) {
        showLoadingScreen()
        ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        BackendManager.startOrUpdate(
            applicationContext,
            apiKey,
            onReady = { showWebView() },
            onError = { message ->
                AlertDialog.Builder(this)
                    .setTitle("BookDebrid could not start")
                    .setMessage(message)
                    .setPositiveButton("Edit API key") { _, _ -> showFirstRunScreen() }
                    .setCancelable(false)
                    .show()
            },
        )
    }

    private fun showLoadingScreen() {
        val frame = FrameLayout(this)
        frame.addView(
            ProgressBar(this),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        setContentView(frame)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        val browser = WebView(this)
        webView = browser
        browser.setBackgroundColor(Color.rgb(18, 18, 24))
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = "$userAgentString FreedifyAndroid/${BuildConfig.VERSION_NAME}"
        }
        browser.addJavascriptInterface(AndroidBridge(), "FreedifyAndroid")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (error: Exception) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    Toast.makeText(
                        this@MainActivity,
                        "No compatible file picker is installed",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(
                    "window.FreedifyAndroid?.syncAudiobookLibrary?.(localStorage.getItem('freedify_audiobooks') || '[]')",
                    null,
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                return if (uri.host == "127.0.0.1" || uri.host == "localhost") {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@MainActivity,
                        "Freedify page failed to load: ${error.description}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        val container = FrameLayout(this)
        webViewContainer = container
        container.addView(
            browser,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(container)
        browser.loadUrl("$FREEDIFY_URL?apkVersion=${BuildConfig.VERSION_CODE}")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startAudiobookBaySearch(requestId: String, query: String, page: Int) {
        destroyAudiobookSearchWebView()
        audiobookSearchRequestId = requestId
        audiobookSearchQuery = query
        audiobookSearchPage = page.coerceAtLeast(1)
        audiobookSearchStage = 0

        val searchBrowser = WebView(this)
        audiobookSearchWebView = searchBrowser
        searchBrowser.alpha = 0f
        searchBrowser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        searchBrowser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val host = request.url.host.orEmpty()
                val isAudiobookBay = host == "audiobookbay.lu" || host.endsWith(".audiobookbay.lu")
                return !isAudiobookBay
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (audiobookSearchRequestId != requestId) return
                when (audiobookSearchStage) {
                    0 -> submitAudiobookSearchForm(view, requestId)
                    1 -> {
                        if (audiobookSearchPage > 1) {
                            audiobookSearchStage = 2
                            val encodedQuery = Uri.encode(audiobookSearchQuery)
                            view.loadUrl("$AUDIOBOOKBAY_URL/page/$audiobookSearchPage/?s=$encodedQuery")
                        } else {
                            extractAudiobookSearchResults(view, requestId)
                        }
                    }
                    2 -> extractAudiobookSearchResults(view, requestId)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame && audiobookSearchRequestId == requestId) {
                    finishAudiobookSearchWithError(
                        requestId,
                        "AudiobookBay failed to load: ${error.description}",
                    )
                }
            }
        }

        webViewContainer?.addView(
            searchBrowser,
            FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END),
        )
        searchBrowser.postDelayed({
            if (audiobookSearchRequestId == requestId) {
                finishAudiobookSearchWithError(requestId, "AudiobookBay search timed out")
            }
        }, AUDIOBOOK_SEARCH_TIMEOUT_MS)
        searchBrowser.loadUrl("$AUDIOBOOKBAY_URL/")
    }

    private fun submitAudiobookSearchForm(view: WebView, requestId: String) {
        audiobookSearchStage = 1
        val quotedQuery = JSONObject.quote(audiobookSearchQuery)
        view.evaluateJavascript(
            """
            (() => {
                const input = document.querySelector('input[name="s"]');
                const form = input?.form || input?.closest('form');
                if (!input || !form) return 'missing';
                input.value = $quotedQuery;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                if (typeof form.requestSubmit === 'function') form.requestSubmit();
                else form.submit();
                return 'submitted';
            })()
            """.trimIndent(),
        ) { result ->
            if (result == JSONObject.quote("missing") && audiobookSearchRequestId == requestId) {
                finishAudiobookSearchWithError(requestId, "AudiobookBay search form was not found")
            }
        }
    }

    private fun extractAudiobookSearchResults(view: WebView, requestId: String) {
        view.postDelayed({
            if (audiobookSearchRequestId != requestId) return@postDelayed
            view.evaluateJavascript(
                """
                (() => {
                    const results = [...document.querySelectorAll('div.post')].map(post => {
                        const link = post.querySelector('.postTitle h2 a');
                        if (!link) return null;
                        const rawTitle = (link.textContent || '').trim();
                        let title = rawTitle;
                        let author = null;
                        const separator = rawTitle.lastIndexOf(' - ');
                        if (separator > 0) {
                            const candidate = rawTitle.slice(separator + 3).trim();
                            const words = candidate.split(/\s+/).filter(Boolean);
                            if (words.length >= 1 && words.length <= 8 && candidate.length <= 80 &&
                                !/(audiobook|\bbooks?\b|series)/i.test(candidate)) {
                                title = rawTitle.slice(0, separator).trim();
                                author = candidate;
                            }
                        }
                        let id = link.getAttribute('href') || '';
                        try { id = new URL(id, location.href).pathname.replace(/^\/+|\/+$/g, ''); } catch (_) {}
                        const image = post.querySelector('img');
                        const content = post.querySelector('.postContent');
                        const genres = [...post.querySelectorAll('a[href*="/genre/"], a[href*="/genres/"], a[href*="/category/"], a[href*="?cat="]')]
                            .map(anchor => (anchor.textContent || '').trim())
                            .filter((genre, index, all) => genre && genre.toLowerCase() !== 'audiobook' && all.indexOf(genre) === index)
                            .slice(0, 8);
                        return {
                            id,
                            title,
                            author,
                            url: link.href,
                            cover_image: image?.src || null,
                            description: (content?.innerText || '').trim().slice(0, 203),
                            genres,
                            source: 'audiobookbay'
                        };
                    }).filter(item => item?.id && item?.title);
                    return JSON.stringify({
                        heading: (document.querySelector('h1')?.innerText || '').trim(),
                        url: location.href,
                        results
                    });
                })()
                """.trimIndent(),
            ) { encodedResult ->
                if (audiobookSearchRequestId != requestId) return@evaluateJavascript
                try {
                    val decoded = JSONTokener(encodedResult).nextValue() as? String
                        ?: throw IllegalStateException("Search page returned no data")
                    val payload = JSONObject(decoded)
                    val heading = payload.optString("heading")
                    val currentUrl = payload.optString("url")
                    if (!currentUrl.contains("?s=") && heading.isBlank()) {
                        throw IllegalStateException("AudiobookBay returned its homepage")
                    }
                    finishAudiobookSearch(requestId, payload)
                } catch (error: Exception) {
                    finishAudiobookSearchWithError(
                        requestId,
                        error.message ?: "AudiobookBay search could not be read",
                    )
                }
            }
        }, AUDIOBOOK_RESULTS_SETTLE_MS)
    }

    private fun finishAudiobookSearch(requestId: String, payload: JSONObject) {
        webView?.evaluateJavascript(
            "window.FreedifyAndroidSearch?.resolve(${JSONObject.quote(requestId)}, ${payload})",
            null,
        )
        destroyAudiobookSearchWebView()
    }

    private fun finishAudiobookSearchWithError(requestId: String, message: String) {
        webView?.evaluateJavascript(
            "window.FreedifyAndroidSearch?.reject(${JSONObject.quote(requestId)}, ${JSONObject.quote(message)})",
            null,
        )
        destroyAudiobookSearchWebView()
    }

    private fun destroyAudiobookSearchWebView() {
        audiobookSearchRequestId = null
        audiobookSearchWebView?.let { searchBrowser ->
            webViewContainer?.removeView(searchBrowser)
            searchBrowser.stopLoading()
            searchBrowser.destroy()
        }
        audiobookSearchWebView = null
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        val browser = webView
        if (browser == null) {
            moveTaskToBack(true)
            return
        }
        browser.evaluateJavascript(
            "(() => { try { return window.FreedifyAndroidNavigation?.goBack() === true; } catch (_) { return false; } })()"
        ) { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        PlaybackService.commandHandler = null
        destroyAudiobookSearchWebView()
        super.onDestroy()
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun syncAudiobookLibrary(payload: String) {
            AudiobookStore.get(applicationContext).importLegacy(payload)
        }

        @JavascriptInterface
        fun openApiKeySettings() {
            runOnUiThread { showSettingsDialog() }
        }

        @JavascriptInterface
        fun searchAudiobookBay(requestId: String, query: String, page: Int) {
            runOnUiThread {
                if (query.isBlank()) {
                    finishAudiobookSearchWithError(requestId, "Search term is empty")
                } else {
                    startAudiobookBaySearch(requestId, query, page)
                }
            }
        }

        @JavascriptInterface
        fun cancelAudiobookBaySearch(requestId: String) {
            runOnUiThread {
                if (audiobookSearchRequestId == requestId) destroyAudiobookSearchWebView()
            }
        }

        @JavascriptInterface
        fun saveTextFile(filename: String, mimeType: String, contents: String) {
            runOnUiThread {
                pendingExport = contents
                val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType.substringBefore(';').ifBlank { "text/plain" }
                    putExtra(Intent.EXTRA_TITLE, safeName.ifBlank { "freedify_backup.json" })
                }
                try {
                    exportFileLauncher.launch(intent)
                } catch (error: Exception) {
                    pendingExport = null
                    Toast.makeText(
                        this@MainActivity,
                        "No compatible file saver is installed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun updateMetadata(title: String, artist: String, album: String) {
            PlaybackService.publishMetadata(title, artist, album)
        }

        @JavascriptInterface
        fun updatePlaybackState(
            playing: Boolean,
            positionSeconds: Double,
            durationSeconds: Double,
            playbackRate: Double,
        ) {
            PlaybackService.publishPlaybackState(
                playing,
                (positionSeconds * 1000).toLong(),
                (durationSeconds * 1000).toLong(),
                playbackRate.toFloat(),
            )
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = (12 * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val FREEDIFY_URL = "http://127.0.0.1:8000/"
        private const val AUDIOBOOKBAY_URL = "https://audiobookbay.lu"
        private const val AUDIOBOOK_SEARCH_TIMEOUT_MS = 45_000L
        private const val AUDIOBOOK_RESULTS_SETTLE_MS = 750L
    }
}
