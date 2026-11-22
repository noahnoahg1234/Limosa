package com.limosa.vm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import com.limosa.core.json.Json
import com.limosa.core.storage.ChunkStore
import java.util.concurrent.Executors

/**
 * Shows the localhost dashboard (served by [VmService]) in a WebView, and offers a small
 * JavaScript bridge for things a web page cannot do well on Android: importing a large ISO
 * straight from the file picker and keeping the screen on while a console is open.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private var pendingImport: Pair<String, Boolean>? = null
    private var loadRetries = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "127.0.0.1" || u.host == "localhost") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url?.startsWith("http://127.0.0.1") == true) loadRetries = 0
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame) return
                val target = VmService.engine?.dashboardUrl ?: return
                if (loadRetries++ < 5) {
                    // The local server may still be starting; try again shortly.
                    view.postDelayed({ view.loadUrl(target) }, 1000)
                } else {
                    val msg = android.text.Html.escapeHtml("${error.description} (${error.errorCode})")
                    view.loadDataWithBaseURL(
                        null,
                        "<meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:24px'>" +
                            "<h3>Limosa dashboard did not load</h3><p>$msg</p><p>${android.text.Html.escapeHtml(target)}</p>" +
                            "<button onclick=\"location.href='${target}'\" style='padding:12px 20px'>Retry</button></body>",
                        "text/html", "utf-8", null,
                    )
                    loadRetries = 0
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "LimosaApp")
        setContentView(TextView(this).apply { text = "Starting Limosa…"; setPadding(48, 96, 48, 48) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        VmService.ensureStarted(this) { engine ->
            if (isFinishing || isDestroyed) return@ensureStarted
            if (engine == null) {
                setContentView(TextView(this).apply {
                    text = "Limosa could not start:\n\n${VmService.startError}"
                    setPadding(48, 96, 48, 48)
                })
                return@ensureStarted
            }
            setContentView(web)
            if (savedInstanceState == null || web.url == null) web.loadUrl(engine.dashboardUrl)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        io.shutdown()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE) return
        val (name, readOnly) = pendingImport ?: return
        pendingImport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            js("window.limosaImportDone && window.limosaImportDone(${Json.stringify(name)}, 'cancelled')")
            return
        }
        importFrom(uri, name, readOnly)
    }

    private fun importFrom(uri: Uri, name: String, readOnly: Boolean) {
        val engine = VmService.engine ?: return
        io.execute {
            val error = try {
                val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
                contentResolver.openInputStream(uri)!!.use { input ->
                    engine.store.importDisk(name, input.buffered(1 shl 20), size, readOnly)
                }
                null
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
            runOnUiThread {
                js("window.limosaImportDone && window.limosaImportDone(${Json.stringify(name)}, ${Json.stringify(error)})")
            }
        }
    }

    private fun js(code: String) {
        if (::web.isInitialized) web.evaluateJavascript(code, null)
    }

    /** Exposed to the dashboard as `window.LimosaApp`. */
    inner class Bridge {
        @JavascriptInterface
        fun importImage(name: String, readOnly: Boolean): String {
            if (!ChunkStore.isValidName(name)) return "Invalid name"
            if (VmService.engine?.store?.disk(name) != null) return "A disk named '$name' already exists"
            runOnUiThread {
                pendingImport = name to readOnly
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                @Suppress("DEPRECATION")
                startActivityForResult(pick, PICK_IMAGE)
            }
            return ""
        }

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @JavascriptInterface
        fun info(): String {
            val q = AndroidQemu(this@MainActivity)
            return Json.stringify(
                mapOf(
                    "installedArchs" to q.installed().map { it.name.lowercase() },
                    "kvm" to q.installed().filter { q.kvmAvailable(it) }.map { it.name.lowercase() },
                    "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
                    "android" to Build.VERSION.RELEASE,
                )
            )
        }

        @JavascriptInterface
        fun quit() = runOnUiThread {
            startService(Intent(this@MainActivity, VmService::class.java).setAction(VmService.ACTION_QUIT))
            finishAndRemoveTask()
        }
    }

    companion object {
        private const val PICK_IMAGE = 42
    }
}
