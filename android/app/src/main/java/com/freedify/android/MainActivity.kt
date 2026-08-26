package com.freedify.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var secureSettings: SecureSettings
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Freedify"
        secureSettings = SecureSettings(this)
        requestNotificationPermission()

        val apiKey = secureSettings.getApiKey()
        if (apiKey.isNullOrBlank()) {
            showFirstRunScreen()
        } else {
            launchFreedify(apiKey)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "AllDebrid API key")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) {
            showSettingsDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
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
                    launchFreedify(key)
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
            allowContentAccess = false
            userAgentString = "$userAgentString FreedifyAndroid/1.0"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        browser.webChromeClient = WebChromeClient()
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
        browser.loadUrl(FREEDIFY_URL)
    }

    override fun onBackPressed() {
        val browser = webView
        if (browser != null && browser.canGoBack()) browser.goBack() else super.onBackPressed()
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
        private const val MENU_SETTINGS = 1
        private const val FREEDIFY_URL = "http://127.0.0.1:8000/"
    }
}
