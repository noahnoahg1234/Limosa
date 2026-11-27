package com.limosa.kdehome

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Android home screen. Until Linux is installed it shows the installer; afterwards it is
 * the KDE Plasma desktop, with a floating button for Android apps and the keyboard.
 */
class HomeActivity : Activity() {

    private lateinit var paths: LinuxPaths
    private lateinit var shortcuts: AndroidShortcuts
    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var vnc: VncView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusButton: Button
    private lateinit var statusLog: TextView
    private lateinit var copyLogButton: Button
    private lateinit var setup: ScrollView
    private lateinit var drawer: AppDrawer
    private lateinit var keys: HorizontalScrollView
    private lateinit var fab: TextView
    private val latchButtons = HashMap<Int, TextView>()

    // Setup screen widgets
    private lateinit var setupStatus: TextView
    private lateinit var setupProgress: ProgressBar
    private lateinit var storageButton: Button
    private lateinit var installButton: Button
    private lateinit var urlField: EditText
    private lateinit var packagesField: EditText
    private lateinit var recommends: CheckBox
    private lateinit var logView: TextView

    private var client: VncClient? = null
    private var resumed = false
    private var autoStarted = false
    private var userStopped = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = LinuxPaths(this)
        shortcuts = AndroidShortcuts(this, paths)
        if (paths.isInstalled && LinuxState.phase == LinuxState.Phase.NOT_INSTALLED) {
            LinuxState.set(LinuxState.Phase.STOPPED, "")
        }
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        LinuxState.addListener(listener)
        render()
    }

    override fun onPause() {
        resumed = false
        LinuxState.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The Home button while we are already in front: close overlays, back to the desktop.
        showDrawer(false)
    }

    @Deprecated("Home screens ignore Back, except to close overlays")
    override fun onBackPressed() {
        when {
            drawer.visibility == View.VISIBLE -> showDrawer(false)
            keys.visibility == View.VISIBLE -> showKeys(false)
        }
    }

    // ---- State -----------------------------------------------------------------------------

    private fun render() {
        val phase = LinuxState.phase
        val installed = paths.isInstalled
        val showSetup = !installed || phase == LinuxState.Phase.INSTALLING
        setup.visibility = if (showSetup) View.VISIBLE else View.GONE
        fab.visibility = if (showSetup) View.GONE else View.VISIBLE
        if (showSetup) {
            renderSetup()
            return
        }
        when (phase) {
            LinuxState.Phase.RUNNING -> {
                if (client == null) connect()
            }
            LinuxState.Phase.STOPPED, LinuxState.Phase.ERROR, LinuxState.Phase.NOT_INSTALLED -> {
                disconnect()
                if (!autoStarted && !userStopped && resumed) {
                    autoStarted = true
                    startSession()
                    return
                }
            }
            else -> Unit
        }
        val connected = client?.bitmap != null && phase == LinuxState.Phase.RUNNING
        status.visibility = if (connected) View.GONE else View.VISIBLE
        statusText.text = when (phase) {
            LinuxState.Phase.STARTING, LinuxState.Phase.RUNNING -> "Starting KDE Plasma…\n${LinuxState.message}"
            else -> LinuxState.message.ifEmpty { "KDE Plasma is not running" }
        }
        val canStart = phase == LinuxState.Phase.STOPPED || phase == LinuxState.Phase.ERROR
        statusButton.visibility = if (canStart) View.VISIBLE else View.GONE
        copyLogButton.visibility = if (canStart) View.VISIBLE else View.GONE
        statusLog.visibility = if (canStart) View.VISIBLE else View.GONE
        if (canStart) statusLog.text = LinuxState.logTail(40)
    }

    private fun renderSetup() {
        val phase = LinuxState.phase
        val installing = phase == LinuxState.Phase.INSTALLING
        val storage = paths.hasStorageAccess()
        storageButton.visibility = if (storage) View.GONE else View.VISIBLE
        installButton.isEnabled = !installing && paths.hasProot
        installButton.text = if (phase == LinuxState.Phase.ERROR) "Retry install" else "Install Debian + KDE Plasma"
        setupStatus.text = when {
            !paths.hasProot -> "This APK was built without proot, so it can't run Linux. Download the APK from the project's Releases page, or build it with scripts/fetch_proot_termux.py."
            installing || phase == LinuxState.Phase.ERROR -> LinuxState.message
            else -> "Ready to install. " + if (storage) "Linux folder: ${paths.linuxFolder.absolutePath}" else "Allow storage access first so Linux gets a folder in internal storage."
        }
        setupProgress.visibility = if (installing) View.VISIBLE else View.GONE
        setupProgress.isIndeterminate = LinuxState.progress < 0
        if (LinuxState.progress >= 0) setupProgress.progress = LinuxState.progress
        logView.text = LinuxState.logTail(40)
    }

    // ---- Linux control ---------------------------------------------------------------------

    private fun startSession() {
        userStopped = false
        root.post {
            val scale = desktopScale()
            val w = ((vnc.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / scale).roundToInt()
            val h = ((vnc.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / scale).roundToInt()
            LinuxService.send(this, LinuxService.ACTION_START) {
                it.putExtra(LinuxService.EXTRA_WIDTH, w)
                it.putExtra(LinuxService.EXTRA_HEIGHT, h)
            }
        }
    }

    private fun stopLinux() {
        userStopped = true
        disconnect()
        LinuxService.send(this, LinuxService.ACTION_STOP)
    }

    private fun restartKde() {
        disconnect()
        LinuxService.send(this, LinuxService.ACTION_STOP)
        userStopped = false
        root.postDelayed({ startSession() }, 1500)
    }

    /** Desktop pixels = screen pixels / scale. Bigger scale = bigger KDE UI. */
    private fun desktopScale(): Float {
        // Fewer desktop pixels = faster drawing; ~0.75 × density keeps text readable on a phone.
        val default = (resources.displayMetrics.density * 0.75f).coerceIn(1f, 3f)
        return prefs.getFloat("scale", (default * 4).roundToInt() / 4f)
    }

    private fun connect() {
        if (client != null) return
        val c = VncClient("127.0.0.1", LinuxService.VNC_PORT, object : VncClient.Listener {
            override fun onConnected(width: Int, height: Int) = runOnUiThread { render() }
            override fun onFramebufferUpdated() {
                vnc.postInvalidateOnAnimation()
                if (status.visibility == View.VISIBLE) runOnUiThread { render() }
            }
            override fun onServerClipboard(text: String) = runOnUiThread {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Linux", text))
            }
            override fun onDisconnected(error: String?) = runOnUiThread {
                if (client != null) {
                    client = null
                    vnc.client = null
                    // KDE still running? Reconnect in a moment.
                    if (LinuxState.phase == LinuxState.Phase.RUNNING) root.postDelayed({ render() }, 1000)
                }
            }
        })
        client = c
        vnc.client = c
        c.start()
    }

    private fun disconnect() {
        val c = client ?: return
        client = null
        vnc.client = null
        c.close()
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun install() {
        if (paths.hasStorageAccess()) paths.linuxFolder.mkdirs()
        LinuxService.send(this, LinuxService.ACTION_INSTALL) {
            it.putExtra(LinuxService.EXTRA_URL, urlField.text.toString())
            it.putExtra(LinuxService.EXTRA_PACKAGES, packagesField.text.toString())
            it.putExtra(LinuxService.EXTRA_RECOMMENDS, recommends.isChecked)
        }
        autoStarted = false
    }

    private fun copyLog() {
        val text = LinuxState.message + "\n" + LinuxState.logTail(200)
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("KDE Home log", text))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun openHomeSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    // ---- Menu ------------------------------------------------------------------------------

    private fun showMenu() {
        PopupMenu(this, fab).apply {
            menu.add("Android apps").setOnMenuItemClickListener { showDrawer(true); true }
            menu.add("Keyboard").setOnMenuItemClickListener { showKeys(true); vnc.showKeyboard(); true }
            menu.add("Paste Android clipboard").setOnMenuItemClickListener {
                val text = getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this@HomeActivity)
                if (text != null) {
                    client?.clipboard(text.toString())
                    vnc.typeText(text)
                }
                true
            }
            menu.add("Update Android shortcuts in KDE").setOnMenuItemClickListener {
                LinuxService.send(this@HomeActivity, LinuxService.ACTION_SYNC)
                Toast.makeText(this@HomeActivity, "KDE menu › Android and the Android Apps folder are being updated", Toast.LENGTH_SHORT).show()
                true
            }
            menu.add("Desktop size…").setOnMenuItemClickListener { chooseScale(); true }
            menu.add("Restart KDE").setOnMenuItemClickListener { restartKde(); true }
            menu.add("Stop Linux").setOnMenuItemClickListener { stopLinux(); true }
            menu.add("Choose default home app").setOnMenuItemClickListener { openHomeSettings(); true }
            menu.add("Android settings").setOnMenuItemClickListener {
                startActivity(Intent(Settings.ACTION_SETTINGS)); true
            }
            show()
        }
    }

    private fun chooseScale() {
        val options = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val current = desktopScale()
        val labels = options.map { s ->
            val w = (resources.displayMetrics.widthPixels / s).roundToInt()
            val h = (resources.displayMetrics.heightPixels / s).roundToInt()
            "×$s  (${w}×$h)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("KDE size (bigger = larger icons and text)")
            .setSingleChoiceItems(labels, options.indexOfFirst { abs(it - current) < 0.01f }) { d, i ->
                prefs.edit().putFloat("scale", options[i]).apply()
                d.dismiss()
                restartKde()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDrawer(show: Boolean) {
        if (show) {
            drawer.refresh()
            vnc.hideKeyboard()
        }
        drawer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showKeys(show: Boolean) {
        keys.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) vnc.hideKeyboard()
    }

    // ---- UI construction -------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Android 15 draws apps edge to edge: keep KDE clear of the status bar, navigation bar and keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vnc = VncView(this)
        content.addView(vnc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keys = buildKeys()
        content.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        statusButton = Button(this).apply {
            text = "Start KDE"
            setOnClickListener { startSession() }
        }
        copyLogButton = Button(this).apply {
            text = "Copy log"
            setOnClickListener { copyLog() }
        }
        statusLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.rgb(170, 220, 170))
            setTextIsSelectable(true)
        }
        status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ProgressBar(this@HomeActivity))
            addView(statusText)
            addView(statusButton)
            addView(copyLogButton)
            addView(ScrollView(this@HomeActivity).apply { addView(statusLog) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setup = buildSetup()
        root.addView(setup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        drawer = AppDrawer(this, shortcuts) { showDrawer(false) }
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fab = buildFab()
        root.addView(fab, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(4)
        })
        vnc.onLatchedChanged = { updateLatches() }
        setContentView(root)
    }

    private fun buildFab(): TextView = TextView(this).apply {
        text = "▦"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(170, 29, 153, 243))
        }
        elevation = dp(6).toFloat()
        contentDescription = "Android apps and options"
        // Tap: menu. Drag: move it up or down out of KDE's way.
        var startY = 0f
        var startTranslation = 0f
        var dragged = false
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startTranslation = v.translationY; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (abs(dy) > dp(8)) dragged = true
                    if (dragged) {
                        val limit = (root.height / 2f - dp(40)).coerceAtLeast(0f)
                        v.translationY = (startTranslation + dy).coerceIn(-limit, limit)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) v.performClick()
            }
            true
        }
        setOnClickListener { showMenu() }
    }

    private fun buildKeys(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(35, 38, 41))
        }
        fun key(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(46)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { action() }
            row.addView(this)
        }
        fun latch(label: String, sym: Int) {
            latchButtons[sym] = key(label) {
                if (!vnc.latched.remove(sym)) vnc.latched.add(sym)
                updateLatches()
            }
        }
        key("⌨") { vnc.showKeyboard() }
        key("Esc") { vnc.tapKeysym(Keysyms.ESCAPE) }
        key("Tab") { vnc.tapKeysym(Keysyms.TAB) }
        latch("Ctrl", Keysyms.CTRL)
        latch("Alt", Keysyms.ALT)
        latch("Shift", Keysyms.SHIFT)
        key("❖") { vnc.tapKeysym(Keysyms.SUPER) }   // opens the KDE launcher
        key("←") { vnc.tapKeysym(Keysyms.LEFT) }
        key("↑") { vnc.tapKeysym(Keysyms.UP) }
        key("↓") { vnc.tapKeysym(Keysyms.DOWN) }
        key("→") { vnc.tapKeysym(Keysyms.RIGHT) }
        key("Home") { vnc.tapKeysym(Keysyms.HOME) }
        key("End") { vnc.tapKeysym(Keysyms.END) }
        key("PgUp") { vnc.tapKeysym(Keysyms.PAGE_UP) }
        key("PgDn") { vnc.tapKeysym(Keysyms.PAGE_DOWN) }
        key("Del") { vnc.tapKeysym(Keysyms.DELETE) }
        for (i in 1..12) key("F$i") { vnc.tapKeysym(Keysyms.F1 + i - 1) }
        key("✕") { showKeys(false) }
        return HorizontalScrollView(this).apply {
            addView(row)
            visibility = View.GONE
        }
    }

    private fun updateLatches() {
        for ((sym, view) in latchButtons) {
            view.setBackgroundColor(if (sym in vnc.latched) Color.rgb(29, 153, 243) else Color.TRANSPARENT)
        }
    }

    private fun buildSetup(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(35, 38, 41))
        }
        fun text(s: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
            col.addView(this)
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
            col.addView(this)
        }
        text("KDE Home", 26f, bold = true)
        text(
            "Installs Debian Linux with the KDE Plasma desktop on this phone and uses KDE as your Android home screen. " +
                "Your Android apps appear in KDE's menu (Android section), in the Android Apps folder on the KDE desktop, " +
                "and behind the ▦ button.",
            color = Color.LTGRAY,
        )
        text(
            "Linux gets the folder Internal storage › Linux (/storage/emulated/0/Linux). It is /home/user/Linux inside Linux, " +
                "and holds the installer download and logs. The Debian system files themselves go in this app's private internal " +
                "storage, because shared storage can't hold Linux permissions and symlinks. Needs about 4 GB free and Wi-Fi.",
            12f, Color.LTGRAY,
        )
        storageButton = button("1. Allow access to internal storage") { requestStorage() }
        text("Debian rootfs URL (leave empty to download the newest automatically)", 12f, Color.LTGRAY)
        urlField = EditText(this).apply {
            hint = "auto (proot-distro Debian)"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            col.addView(this)
        }
        text("Extra packages (optional, any that don't exist are skipped)", 12f, Color.LTGRAY)
        packagesField = EditText(this).apply {
            setText(RootfsInstaller.DEFAULT_EXTRAS)
            setTextColor(Color.WHITE)
            textSize = 13f
            col.addView(this)
        }
        recommends = CheckBox(this).apply {
            text = "Also install recommended packages (more complete KDE, larger download)"
            setTextColor(Color.WHITE)
            col.addView(this)
        }
        installButton = button("Install Debian + KDE Plasma") { install() }
        button("Set as default home screen") { openHomeSettings() }
        setupProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            col.addView(this)
        }
        setupStatus = text("", 14f, Color.WHITE, bold = true)
        if (Build.VERSION.SDK_INT >= 31) {
            text(
                "Android 12+ may kill Linux programs (\"phantom process\" limit). If KDE keeps closing, turn on " +
                    "Developer options › Disable child process restrictions (Android 14+), or run once over adb:\n" +
                    "adb shell device_config set_sync_disabled_for_tests persistent; " +
                    "adb shell device_config put activity_manager max_phantom_processes 2147483647",
                12f, Color.rgb(246, 193, 119),
            )
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.rgb(170, 220, 170))
            setPadding(0, dp(8), 0, 0)
            setTextIsSelectable(true)
            col.addView(this)
        }
        return ScrollView(this).apply { addView(col) }
    }
}
