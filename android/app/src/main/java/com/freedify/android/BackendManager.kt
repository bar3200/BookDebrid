package com.freedify.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.chaquo.python.Python
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object BackendManager {
    private const val HEALTH_URL = "http://127.0.0.1:8000/api/health"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startOrUpdate(
        context: Context,
        apiKey: String,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        executor.execute {
            try {
                val module = Python.getInstance().getModule("android_server")
                module.callAttr("start_server", context.filesDir.absolutePath, apiKey)
                waitUntilHealthy()
                mainHandler.post(onReady)
            } catch (exception: Exception) {
                mainHandler.post {
                    onError(exception.message ?: "Embedded backend failed to start")
                }
            }
        }
    }

    private fun waitUntilHealthy() {
        var lastError: Exception? = null
        repeat(120) {
            try {
                val connection = URL(HEALTH_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 500
                connection.readTimeout = 500
                connection.requestMethod = "GET"
                try {
                    if (connection.responseCode == 200) return
                } finally {
                    connection.disconnect()
                }
            } catch (exception: Exception) {
                lastError = exception
            }
            Thread.sleep(500)
        }
        throw IllegalStateException(
            "Freedify backend did not become ready within 60 seconds",
            lastError,
        )
    }
}
