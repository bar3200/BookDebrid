package com.freedify.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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

class MainActivity : AppCompatActivity() {
    private lateinit var secureSettings: SecureSettings
    private var webView: WebView? = null
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
            text = "Connect AllDebrid"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = "Enter an AllDebrid API key to start the private backend on this device. The key is encrypted with Android Keystore and never added to the APK."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, padding)
        })
        val input = apiKeyInput()
        container.addView(input, matchWidthWrapHeight())
        container.addView(Button(this).apply {
            text = "Save and start Freedify"
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
        hint = "ALLDEBRID_API_KEY"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
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
                    .setTitle("Freedify could not start")
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
        setContentView(browser)
        browser.loadUrl("$FREEDIFY_URL?apkVersion=${BuildConfig.VERSION_CODE}")
    }

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
        super.onDestroy()
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun openApiKeySettings() {
            runOnUiThread { showSettingsDialog() }
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
    }
}
