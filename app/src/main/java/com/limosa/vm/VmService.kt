package com.limosa.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.limosa.core.LimosaEngine
import com.limosa.core.vm.VmManager
import java.util.concurrent.Executors

/**
 * Foreground service that owns the [LimosaEngine]: fake storage, the localhost NBD and
 * HTTP servers, the QEMU processes and the idle-pause logic. It lives as long as the app
 * is open or any VM is running.
 */
class VmService : Service() {

    private var idleSignals: IdleSignals? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(buildNotification("Starting…"))
        worker.execute {
            try {
                val qemu = AndroidQemu(this).also { it.extractFirmware() }
                val e = LimosaEngine(
                    dataDir = filesDir,
                    qemu = qemu,
                    staticFiles = { path -> runCatching { assets.open("web/$path").use { it.readBytes() } }.getOrNull() },
                    signaller = { pid, sig ->
                        android.os.Process.sendSignal(pid.toInt(), if (sig == "STOP") SIGSTOP else SIGCONT)
                    },
                    log = { Log.i(TAG, it) },
                )
                e.vms.addListener { main.post { updateNotification() } }
                engine = e
                main.post {
                    idleSignals = IdleSignals(this, e.idle).also { it.register() }
                    updateNotification()
                    readyListeners.forEach { it(e) }
                    readyListeners.clear()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Engine failed to start", t)
                startError = t.message ?: t.toString()
                main.post {
                    updateNotification()
                    readyListeners.forEach { it(null) }
                    readyListeners.clear()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> worker.execute {
                engine?.vms?.runningVms()?.forEach { runCatching { engine?.vms?.pause(it.config.name) } }
            }
            ACTION_RESUME_ALL -> worker.execute {
                engine?.vms?.runningVms()?.forEach { runCatching { engine?.vms?.resume(it.config.name) } }
            }
            ACTION_QUIT -> {
                worker.execute {
                    engine?.close()
                    engine = null
                    main.post { stopSelf() }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        idleSignals?.unregister()
        idleSignals = null
        engine?.let { e -> Thread { e.close() }.start() }
        engine = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Virtual machines", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows running VMs and whether they are auto-paused"
            }
        )
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        val e = engine
        val text = when {
            startError != null -> "Error: $startError"
            e == null -> "Starting…"
            else -> {
                val vms = e.vms.runningVms()
                val running = vms.count { it.state == VmManager.State.RUNNING || it.state == VmManager.State.STARTING }
                val paused = vms.count { it.state == VmManager.State.PAUSED }
                val idle = vms.count { it.pauseReason == VmManager.PauseReason.IDLE }
                when {
                    vms.isEmpty() -> "No VMs running · storage server on 127.0.0.1:${e.nbd.port}"
                    idle > 0 -> "$running running, $paused paused ($idle auto-paused: ${e.idle.idleReason ?: "inactive"})"
                    else -> "$running running, $paused paused"
                }
            }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun action(label: String, act: String, code: Int) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(this, code, Intent(this, VmService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Limosa")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action("Pause all", ACTION_PAUSE_ALL, 1))
            .addAction(action("Resume all", ACTION_RESUME_ALL, 2))
            .addAction(action("Quit", ACTION_QUIT, 3))
            .build()
    }

    companion object {
        private const val TAG = "Limosa"
        private const val CHANNEL = "vms"
        private const val NOTIFICATION_ID = 1
        private const val SIGSTOP = 19
        private const val SIGCONT = 18
        const val ACTION_PAUSE_ALL = "com.limosa.vm.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.limosa.vm.RESUME_ALL"
        const val ACTION_QUIT = "com.limosa.vm.QUIT"

        @Volatile var engine: LimosaEngine? = null
            private set
        @Volatile var startError: String? = null
            private set
        private val readyListeners = ArrayList<(LimosaEngine?) -> Unit>()

        /**
         * Starts the service if needed and calls [onReady] on the main thread once the engine
         * is up, or with null if it failed (see [startError]).
         */
        fun ensureStarted(context: Context, onReady: (LimosaEngine?) -> Unit) {
            engine?.let { onReady(it); return }
            readyListeners.add(onReady)
            startError = null
            context.startForegroundService(Intent(context, VmService::class.java))
        }
    }
}
