package com.limosa.kdehome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Keeps Linux alive while other apps are in front: runs the installer and the KDE session
 * (TigerVNC's Xvnc + startplasma-x11 inside proot), and the Android bridge.
 */
class LinuxService : Service() {

    private lateinit var paths: LinuxPaths
    private val prefs by lazy { getSharedPreferences("linux", MODE_PRIVATE) }
    private lateinit var proot: Proot
    private lateinit var shortcuts: AndroidShortcuts
    private lateinit var bridge: AndroidBridge
    @Volatile private var installer: RootfsInstaller? = null
    @Volatile private var session: Process? = null
    private var packageReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        paths = LinuxPaths(this)
        proot = Proot(paths)
        shortcuts = AndroidShortcuts(this, paths)
        bridge = AndroidBridge(this, File(paths.rootfs, "etc/kdehome/bridge-token"))
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Linux desktop", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that Debian and KDE Plasma are running"
            }
        )
        startInForeground(notification("Linux is ready"))
        bridge.start()
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = syncShortcutsAsync()
        }.also {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(it, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(it, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INSTALL -> install(
                RootfsInstaller.Options(
                    rootfsUrl = intent.getStringExtra(EXTRA_URL).orEmpty(),
                    extraPackages = intent.getStringExtra(EXTRA_PACKAGES) ?: RootfsInstaller.DEFAULT_EXTRAS,
                    withRecommends = intent.getBooleanExtra(EXTRA_RECOMMENDS, false),
                )
            )
            ACTION_START -> startSession(intent.getIntExtra(EXTRA_WIDTH, 1280), intent.getIntExtra(EXTRA_HEIGHT, 720))
            ACTION_STOP -> {
                stopSession()
                LinuxState.set(if (paths.isInstalled) LinuxState.Phase.STOPPED else LinuxState.Phase.NOT_INSTALLED, "Linux stopped")
                stopSelf()
            }
            ACTION_SYNC -> syncShortcutsAsync()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        packageReceiver?.let { runCatching { unregisterReceiver(it) } }
        installer?.cancelled = true
        stopSession()
        bridge.stop()
        super.onDestroy()
    }

    // ---- Install -------------------------------------------------------------------------

    private fun install(opts: RootfsInstaller.Options) {
        if (installer != null) return
        val inst = RootfsInstaller(paths, proot, LinuxState::log) { msg, pct ->
            LinuxState.progress(msg, pct)
            updateNotification(msg)
        }
        installer = inst
        LinuxState.set(LinuxState.Phase.INSTALLING, "Starting install…")
        thread(name = "linux-install") {
            try {
                inst.install(opts)
                runCatching { shortcuts.sync() }
                LinuxState.set(LinuxState.Phase.STOPPED, "Installed. Starting KDE…")
                updateNotification("Debian and KDE Plasma installed")
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                LinuxState.log("ERROR: ${e.message}")
                LinuxState.set(LinuxState.Phase.ERROR, "Install failed: ${e.message}")
                updateNotification("Install failed")
            } finally {
                installer = null
            }
        }
    }

    // ---- KDE session -----------------------------------------------------------------------

    private fun startSession(width: Int, height: Int) {
        if (!paths.isInstalled || installer != null) return
        if (session?.isAlive == true) return
        val w = (width.coerceIn(480, 4096) / 2) * 2
        val h = (height.coerceIn(400, 4096) / 2) * 2
        LinuxState.set(LinuxState.Phase.STARTING, "Starting KDE Plasma (${w}×$h)…")
        updateNotification("Starting KDE Plasma")
        val noSeccomp = prefs.getBoolean(PREF_NO_SECCOMP, false)
        thread(name = "kde-session") {
            runCatching { shortcuts.sync() }
            runUpgrades()   // first, so apps it installs are pinned to the desktop below
            runCatching { SessionTuning.apply(paths.rootfs, paths.userHome) }
                .onFailure { LinuxState.log("!!! Could not write KDE settings: ${it.message}") }
            startSystemBus()
            LinuxState.set(LinuxState.Phase.STARTING, "Starting KDE Plasma (${w}×$h)…")
            val started = System.currentTimeMillis()
            val p = try {
                proot.process(sessionScript(w, h), asUser = true, noSeccomp = noSeccomp).start()
            } catch (e: Exception) {
                LinuxState.set(LinuxState.Phase.ERROR, "Could not start Linux: ${e.message}")
                return@thread
            }
            session = p
            thread(name = "kde-ready", isDaemon = true) { waitForDisplay(p) }
            paths.sessionLog.writeText(
                "KDE Home session ${java.util.Date()} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL} · kernel ${System.getProperty("os.version")} · " +
                    "proot seccomp ${if (noSeccomp) "off" else "on"} · ${w}x$h\n"
            )
            var written = 0L
            p.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (written < 4L * 1024 * 1024) {
                        paths.sessionLog.appendText(line + "\n")
                        written += line.length + 1
                    }
                    if (line.startsWith("!!!") || line.contains("error", ignoreCase = true)) LinuxState.log(line)
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            paths.publishLog(paths.sessionLog)
            if (session === p) {
                session = null
                val signal = if (code > 128) code - 128 else 0
                val now = System.currentTimeMillis()
                fun keepLog() = java.io.File(paths.base, "session-previous.log").let {
                    paths.sessionLog.renameTo(it)
                    paths.publishLog(it)
                }
                // proot crashed (abort, segfault, blocked syscall) soon after starting: retry once
                // without seccomp acceleration, and remember that this phone needs it off.
                if (signal in CRASH_SIGNALS && !noSeccomp && now - started < 5 * 60_000) {
                    prefs.edit().putBoolean(PREF_NO_SECCOMP, true).apply()
                    LinuxState.log("!!! KDE stopped with ${exitReason(code)}; retrying with proot seccomp acceleration off")
                    keepLog()
                    startSession(width, height)
                    return@thread
                }
                // Android killed it (SIGKILL): restart, but not in a loop.
                if (signal == 9 && now - lastAutoRestart > 10 * 60_000) {
                    lastAutoRestart = now
                    LinuxState.log("!!! Android killed Linux (SIGKILL); restarting KDE once")
                    keepLog()
                    startSession(width, height)
                    return@thread
                }
                // Put the end of the session log on screen so a crash can be read and copied.
                runCatching { paths.sessionLog.readLines().takeLast(40) }.getOrDefault(emptyList())
                    .forEach { LinuxState.log(it) }
                val hint = if (signal == 9) "\n\nAndroid killed Linux. To stop that: Developer options › Disable child process " +
                    "restrictions (Android 14+), or once over adb: adb shell device_config put activity_manager " +
                    "max_phantom_processes 2147483647 — and set KDE Home's battery use to Unrestricted." else ""
                LinuxState.set(LinuxState.Phase.STOPPED, "KDE stopped: ${exitReason(code)}$hint")
                updateNotification("KDE stopped")
            }
        }
    }

    @Volatile private var lastAutoRestart = 0L

    /** Brings an existing install up to date (new packages etc.) instead of asking for a reinstall. */
    private fun runUpgrades() {
        val marker = java.io.File(paths.base, "upgrade-level")
        val level = runCatching { marker.readText().trim().toInt() }.getOrDefault(1)
        val script = SessionTuning.upgradeScript(level) ?: return
        LinuxState.set(LinuxState.Phase.STARTING, "Updating Linux for this version of KDE Home…")
        updateNotification("Updating Linux")
        try {
            val p = proot.process(script, asUser = false).start()
            p.inputStream.bufferedReader().forEachLine { LinuxState.log(it) }
            if (p.waitFor() == 0) marker.writeText(SessionTuning.UPGRADE_LEVEL.toString())
        } catch (e: Exception) {
            LinuxState.log("!!! Update step failed (${e.message}); it will be tried again next start")
        }
    }

    private fun exitReason(code: Int): String {
        val signals = mapOf(4 to "SIGILL", 6 to "SIGABRT (abort)", 7 to "SIGBUS", 9 to "SIGKILL (killed by Android?)",
            11 to "SIGSEGV (crash)", 15 to "SIGTERM", 31 to "SIGSYS (blocked system call)")
        return if (code > 128) "exit code $code = signal ${code - 128} ${signals[code - 128].orEmpty()}" else "exit code $code"
    }

    private fun waitForDisplay(p: Process) {
        val deadline = System.currentTimeMillis() + 120_000
        while (p.isAlive && System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", VNC_PORT), 500) }
                true
            }.getOrDefault(false)
            if (ok) {
                LinuxState.set(LinuxState.Phase.RUNNING, "KDE Plasma is running")
                updateNotification("KDE Plasma is running")
                return
            }
            Thread.sleep(500)
        }
    }

    /**
     * The system D-Bus (normally started by systemd). Discover/PackageKit, and KDE's device and
     * power services, need it. Runs as fake root in its own proot, beside the desktop.
     */
    @Volatile private var systemBus: Process? = null

    private fun startSystemBus() {
        if (systemBus?.isAlive == true) return
        val script = """
            |[ -x /usr/bin/dbus-daemon ] || exit 0
            |mkdir -p /run/dbus /var/lib/dbus
            |rm -f /run/dbus/pid /run/dbus/system_bus_socket
            |dbus-uuidgen --ensure 2>/dev/null
            |exec dbus-daemon --system --nofork --nopidfile
            |""".trimMargin()
        systemBus = runCatching { proot.process(script, asUser = false).start() }.getOrNull()?.also { p ->
            thread(name = "system-bus-log", isDaemon = true) {
                p.inputStream.bufferedReader().forEachLine { LinuxState.log("dbus: $it") }
            }
        }
        // Give it a moment to create its socket before Plasma looks for it.
        repeat(20) {
            if (java.io.File(paths.rootfs, "run/dbus/system_bus_socket").exists()) return
            Thread.sleep(100)
        }
    }

    private fun stopSession() {
        systemBus?.let { it.destroy(); systemBus = null }
        val p = session ?: return
        session = null
        p.destroy()
        thread(isDaemon = true) {
            Thread.sleep(3000)
            if (p.isAlive) p.destroyForcibly()
        }
    }

    private fun sessionScript(w: Int, h: Int): String = """
        |export DISPLAY=:1 XDG_RUNTIME_DIR=/tmp/runtime-user XDG_SESSION_TYPE=x11
        |export XDG_CURRENT_DESKTOP=KDE KDE_FULL_SESSION=true DESKTOP_SESSION=plasma
        |export KWIN_COMPOSE=N LIBGL_ALWAYS_SOFTWARE=1 QT_X11_NO_MITSHM=1
        |export LANG=C.UTF-8 BROWSER=android-open NO_AT_BRIDGE=1 QT_ACCESSIBILITY=0
        |# Firefox's sandbox needs kernel features proot can't provide.
        |export MOZ_FAKE_NO_SANDBOX=1 MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
        |  MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 MOZ_DISABLE_GPU_SANDBOX=1
        |mkdir -p "${'$'}XDG_RUNTIME_DIR" /tmp/.X11-unix
        |chmod 700 "${'$'}XDG_RUNTIME_DIR"; chmod 1777 /tmp/.X11-unix
        |rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
        |echo ">>> Starting Xvnc ${w}x$h"
        |Xvnc :1 -geometry ${w}x$h -depth 24 -dpi 96 -localhost -rfbport $VNC_PORT \
        |  -SecurityTypes None -AlwaysShared -ac -nolisten tcp &
        |XPID=${'$'}!
        |trap 'kill ${'$'}XPID 2>/dev/null' EXIT
        |for i in ${'$'}(seq 100); do [ -e /tmp/.X11-unix/X1 ] && break; sleep 0.2; done
        |[ -e /tmp/.X11-unix/X1 ] || { echo '!!! Xvnc did not start'; exit 1; }
        |echo '>>> Xvnc is up, starting KDE Plasma'
        |dbus-launch --exit-with-session /usr/local/bin/kdehome-session
        |code=${'$'}?
        |echo "!!! KDE Plasma exited with code ${'$'}code"
        |exit ${'$'}code
        |""".trimMargin()

    // ---- Misc --------------------------------------------------------------------------------

    private fun syncShortcutsAsync() {
        thread(name = "shortcut-sync", isDaemon = true) { runCatching { shortcuts.sync() } }
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private var lastNotified = ""
    private fun updateNotification(text: String) {
        if (text == lastNotified) return
        lastNotified = text
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LinuxService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Debian + KDE Plasma")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop Linux", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "KdeHome"
        private const val CHANNEL = "linux"
        private const val NOTIFICATION_ID = 1
        const val VNC_PORT = 5901
        // v2: the first version also turned it off after Android's SIGKILL, which isn't a proot crash.
        private const val PREF_NO_SECCOMP = "prootNoSeccomp2"
        private val CRASH_SIGNALS = setOf(4, 6, 7, 11, 31)

        const val ACTION_INSTALL = "com.limosa.kdehome.INSTALL"
        const val ACTION_START = "com.limosa.kdehome.START"
        const val ACTION_STOP = "com.limosa.kdehome.STOP"
        const val ACTION_SYNC = "com.limosa.kdehome.SYNC"
        const val EXTRA_URL = "url"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_RECOMMENDS = "recommends"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        fun send(context: Context, action: String, extras: (Intent) -> Unit = {}) {
            val i = Intent(context, LinuxService::class.java).setAction(action)
            extras(i)
            context.startForegroundService(i)
        }
    }
}
