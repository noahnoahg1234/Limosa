package com.limosa.xr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * A browser for WebXR apps that pretends to be a standalone VR headset. Pages get an emulated
 * `navigator.xr` (assets/xr/webxr-emulator.js); while a VR session runs, [ControllerOverlay]
 * turns touches into head and controller input, and [HeadTracker] can use the gyro for looking.
 */
class MainActivity : Activity(), ControllerOverlay.Listener {

    private lateinit var web: WebView
    private lateinit var toolbar: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var controls: ControllerOverlay
    private lateinit var prefs: SharedPreferences
    private lateinit var head: HeadTracker
    private val state = XrState()
    private var presenting = false
    private var emulatorJs = ""
    private var documentStartSupported = false

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        head = HeadTracker(this, state)
        applySettings()
        emulatorJs = assets.open("xr/webxr-emulator.js").bufferedReader().use { it.readText() }

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        defaultUserAgent = web.settings.userAgentString
        applyUserAgent()
        web.addJavascriptInterface(QuestBridge(), "QuestXR")

        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            // Runs before any page script, in every frame, so apps see navigator.xr on load.
            WebViewCompat.addDocumentStartJavaScript(web, emulatorJs, setOf("*"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Old WebViews without document-start scripts: inject as early as we can.
                if (!documentStartSupported) view.evaluateJavascript(emulatorJs, null)
                if (presenting) onSessionEnded()
                if (url != null && !urlBar.hasFocus()) urlBar.setText(displayUrl(url))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!documentStartSupported) view.evaluateJavascript(emulatorJs, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("LimosaXR", "${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                return true
            }
        }

        controls = ControllerOverlay(this, state).apply {
            listener = this@MainActivity
            visibility = View.GONE
            gyroOn = prefs.getBoolean("gyro", false)
            stereoOn = state.stereo
        }

        toolbar = buildToolbar()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        val start = intent?.dataString ?: savedInstanceState?.getString("url") ?: HOME_URL
        web.loadUrl(start)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { web.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.url?.let { outState.putString("url", it) }
    }

    private fun buildToolbar(): LinearLayout {
        val d = resources.displayMetrics.density
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            setOnClickListener { onClick() }
        }
        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "Enter a WebXR app URL"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString()); true
                } else false
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 26, 32))
            addView(btn("⌂") { web.loadUrl(HOME_URL) })
            addView(btn("←") { if (web.canGoBack()) web.goBack() })
            addView(btn("⟳") { web.reload() })
            addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("Enter VR") { enterVr() })
        }
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.contains("://") -> t
            t.startsWith("localhost") || t.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+.*")) -> "http://$t"
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode("$t webxr")
        }
        web.loadUrl(url)
        urlBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun displayUrl(url: String) = if (url.startsWith("https://$ASSET_HOST/assets/home/")) "" else url

    private fun enterVr() {
        web.evaluateJavascript("window.__limosaXR ? window.__limosaXR.enterVR() : null") { result ->
            if (result == null || result == "null") {
                android.widget.Toast.makeText(this, "No \"Enter VR\" button found on this page", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------ session --

    private fun onSessionStarted() {
        if (presenting) return
        presenting = true
        toolbar.visibility = View.GONE
        controls.visibility = View.VISIBLE
        controls.showHint()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFullscreen(true)
        head.setEnabled(prefs.getBoolean("gyro", false))
        controls.gyroOn = head.enabled
    }

    private fun onSessionEnded() {
        if (!presenting) return
        presenting = false
        controls.releaseAll()
        controls.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFullscreen(false)
        head.setEnabled(false)
    }

    private fun endSession() {
        web.evaluateJavascript("window.__limosaXR && window.__limosaXR.endSession()", null)
        // Also tidy up natively in case the page's session lived in a frame we cannot reach.
        web.postDelayed({ onSessionEnded() }, 300)
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else 0
        }
    }

    override fun onSystemAction(action: String) {
        when (action) {
            "exit" -> endSession()
            "home" -> { endSession(); web.loadUrl(HOME_URL) }
            "gyro" -> {
                val on = !head.enabled
                head.setEnabled(on)
                if (on && !head.available) {
                    android.widget.Toast.makeText(this, "This phone has no rotation sensor", android.widget.Toast.LENGTH_SHORT).show()
                }
                prefs.edit().putBoolean("gyro", head.enabled).apply()
                controls.gyroOn = head.enabled
            }
            "stereo" -> {
                val on = !state.stereo
                synchronized(state) { state.stereo = on }
                prefs.edit().putBoolean("stereo", on).apply()
                controls.stereoOn = on
            }
            "center" -> head.recenter()
        }
    }

    // ------------------------------------------------------------------ settings --

    private var defaultUserAgent = ""

    private fun applySettings() {
        synchronized(state) {
            state.stereo = prefs.getBoolean("stereo", false)
            state.eyeHeight = prefs.getFloat("eyeHeight", 1.6f)
            state.fovDegrees = prefs.getFloat("fov", 80f)
            state.ipd = prefs.getFloat("ipd", 0.063f)
            state.renderScale = prefs.getFloat("renderScale", 1f)
        }
    }

    private fun applyUserAgent() {
        web.settings.userAgentString = if (prefs.getBoolean("questUserAgent", true)) QUEST_UA else defaultUserAgent
    }

    private fun settingsJson() = JSONObject().apply {
        put("questUserAgent", prefs.getBoolean("questUserAgent", true))
        put("gyro", prefs.getBoolean("gyro", false))
        put("stereo", prefs.getBoolean("stereo", false))
        put("eyeHeight", prefs.getFloat("eyeHeight", 1.6f).toDouble())
        put("fov", prefs.getFloat("fov", 80f).toDouble())
        put("ipd", prefs.getFloat("ipd", 0.063f).toDouble())
        put("renderScale", prefs.getFloat("renderScale", 1f).toDouble())
        put("gyroAvailable", head.available)
        put("documentStart", documentStartSupported)
    }.toString()

    // ------------------------------------------------------------------ lifecycle --

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            presenting -> endSession()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
        if (presenting) head.setEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (presenting) {
            setFullscreen(true)
            head.setEnabled(prefs.getBoolean("gyro", false))
        }
    }

    override fun onDestroy() {
        head.setEnabled(false)
        web.destroy()
        super.onDestroy()
    }

    private fun vibrate(intensity: Double, ms: Long) {
        if (ms <= 0 || intensity <= 0) return
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (v == null || !v.hasVibrator()) return
        val amp = (intensity.coerceIn(0.0, 1.0) * 255).toInt().coerceIn(1, 255)
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 2000), if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Exposed to every page as `window.QuestXR`. Only harmless, emulator-specific calls. */
    inner class QuestBridge {
        @JavascriptInterface
        fun poll(): String = state.toJson()

        @JavascriptInterface
        fun sessionStarted(mode: String) = runOnUiThread { onSessionStarted() }

        @JavascriptInterface
        fun sessionEnded() = runOnUiThread { onSessionEnded() }

        @JavascriptInterface
        fun vibrate(json: String) {
            runCatching {
                val a = JSONArray(json)
                vibrate(a.optDouble(1, 0.0), a.optDouble(2, 0.0).toLong())
            }
        }

        @JavascriptInterface
        fun settings(): String = settingsJson()

        @JavascriptInterface
        fun setSetting(key: String, value: String) = runOnUiThread {
            val e = prefs.edit()
            when (key) {
                "questUserAgent", "gyro", "stereo" -> e.putBoolean(key, value == "true")
                "eyeHeight" -> e.putFloat(key, value.toFloatOrNull()?.coerceIn(0.5f, 2.5f) ?: return@runOnUiThread)
                "fov" -> e.putFloat(key, value.toFloatOrNull()?.coerceIn(40f, 120f) ?: return@runOnUiThread)
                "ipd" -> e.putFloat(key, value.toFloatOrNull()?.coerceIn(0.04f, 0.08f) ?: return@runOnUiThread)
                "renderScale" -> e.putFloat(key, value.toFloatOrNull()?.coerceIn(0.25f, 1f) ?: return@runOnUiThread)
                else -> return@runOnUiThread
            }
            e.apply()
            applySettings()
            controls.stereoOn = state.stereo
            if (key == "questUserAgent") applyUserAgent()
        }
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val HOME_URL = "https://$ASSET_HOST/assets/home/index.html"
        // Sites that only offer VR to headset browsers check for this kind of user agent.
        const val QUEST_UA = "Mozilla/5.0 (X11; Linux x86_64; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "OculusBrowser/36.4.0.0 SamsungBrowser/4.0 Chrome/130.0.6723.102 VR Safari/537.36"
    }
}
