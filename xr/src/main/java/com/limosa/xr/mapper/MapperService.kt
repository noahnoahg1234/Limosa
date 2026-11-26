package com.limosa.xr.mapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * Shows the controller over any app and maps it to touches. Needs "display over other apps" and
 * Shizuku (which runs [InputService] as the shell user to read and inject touches).
 */
class MapperService : Service(), MapperView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var view: MapperView? = null
    private var input: IInputService? = null
    private var pkg = ""
    private var landscape = true
    private var stopping = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, InputService::class.java.name))
            .daemon(false)
            .processNameSuffix("touch")
            .debuggable(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return fail("Shizuku could not start the touch service.")
            val svc = IInputService.Stub.asInterface(binder)
            input = svc
            val err = runCatching { svc.start(touchListener) }.getOrElse { it.message ?: it.toString() }
            if (err != null) fail(err)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            input = null
            if (!stopping) fail("The touch service stopped.")
        }
    }

    private val touchListener = object : ITouchListener.Stub() {
        override fun onFrame(ids: IntArray, xy: FloatArray, count: Int) {
            val ids2 = ids.copyOf(count)
            val xy2 = xy.copyOf(count * 2)
            main.post { frame(ids2, xy2, count) }
        }

        override fun onStopped(reason: String?) {
            main.post { fail(reason ?: "Stopped.") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotice()
        val newPkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        if (view != null) {
            // Already running: switch to the new app's layout.
            if (newPkg.isNotEmpty() && newPkg != pkg) { pkg = newPkg; loadLayout() }
            return START_NOT_STICKY
        }
        pkg = newPkg
        val problem = problem(this)
        if (problem != null) {
            fail(problem)
            return START_NOT_STICKY
        }
        showOverlay()
        Shizuku.bindUserService(args, connection)
        return START_NOT_STICKY
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Controller overlay", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, MapperService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Limosa XR controls are on")
            .setContentText("Tap to stop and give the touchscreen back")
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, 0x40000000 /* FOREGROUND_SERVICE_TYPE_SPECIAL_USE */)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun showOverlay() {
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val v = MapperView(this, MapperLayout.load(this, pkg, landscape), this)
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // Touches injected under this window must reach the app: Android only lets them pass through
        // another app's overlay when it is at most 80% opaque.
        lp.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.title = "Limosa XR controls"
        wm.addView(v, lp)
        view = v
    }

    private fun loadLayout() {
        val v = view ?: return
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        v.releaseAll()
        v.layout = MapperLayout.load(this, pkg, landscape)
        v.invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val l = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (l != landscape) {
            view?.let { if (it.mode == MapperView.Mode.EDIT) MapperLayout.save(this, pkg, landscape, it.layout) }
            loadLayout()
        }
    }

    /** Raw touches are in the panel's natural orientation, 0..1: turn them into this view's pixels. */
    private fun frame(ids: IntArray, norm: FloatArray, count: Int) {
        val v = view ?: return
        val real = Point()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealSize(real)
        val rot = display.rotation
        val nw = if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) real.y else real.x
        val nh = if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) real.x else real.y
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        v.screenOffsetX = loc[0].toFloat()
        v.screenOffsetY = loc[1].toFloat()
        val xy = FloatArray(count * 2)
        for (i in 0 until count) {
            val nx = norm[2 * i]
            val ny = norm[2 * i + 1]
            val (sx, sy) = when (rot) {
                Surface.ROTATION_90 -> ny * nh to (1 - nx) * nw
                Surface.ROTATION_180 -> (1 - nx) * nw to (1 - ny) * nh
                Surface.ROTATION_270 -> (1 - ny) * nh to nx * nw
                else -> nx * nw to ny * nh
            }
            xy[2 * i] = sx - loc[0]
            xy[2 * i + 1] = sy - loc[1]
        }
        v.onFrame(ids, xy, count)
    }

    override fun onInject(ids: IntArray, xy: FloatArray, count: Int) {
        runCatching { input?.inject(ids, xy, count) }
    }

    override fun onPill(id: String) {
        val v = view ?: return
        when (id) {
            "stop" -> stopSelf()
            "hide" -> { v.releaseAll(); v.mode = MapperView.Mode.HIDDEN }
            "show" -> { v.releaseAll(); v.mode = MapperView.Mode.PLAY }
            "edit" -> { v.releaseAll(); v.mode = MapperView.Mode.EDIT }
            "done" -> {
                MapperLayout.save(this, pkg, landscape, v.layout)
                v.releaseAll()
                v.mode = MapperView.Mode.PLAY
            }
            "reset" -> {
                MapperLayout.reset(this, pkg, landscape)
                v.layout = MapperLayout.defaults(landscape)
                v.invalidate()
            }
        }
    }

    private fun fail(msg: String) {
        Toast.makeText(this, "Limosa XR controls: $msg", Toast.LENGTH_LONG).show()
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        view?.let { v ->
            if (v.mode == MapperView.Mode.EDIT) MapperLayout.save(this, pkg, landscape, v.layout)
            runCatching { wm.removeView(v) }
        }
        view = null
        runCatching { input?.stop() }
        input = null
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        private const val ACTION_STOP = "com.limosa.xr.mapper.STOP"
        private const val CHANNEL = "controls"
        private const val NOTIFICATION_ID = 7
        const val SHIZUKU_REQUEST = 41

        /** What still has to be set up, or null when the controls can start. */
        fun problem(ctx: Context): String? = when {
            Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx) -> "Allow Limosa XR to display over other apps."
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Shizuku is not running. Open Shizuku and start it."
            Shizuku.isPreV11() -> "Please update Shizuku."
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Allow Limosa XR in Shizuku."
            else -> null
        }

        fun state(ctx: Context): org.json.JSONObject {
            val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            return org.json.JSONObject().apply {
                put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx))
                put("shizukuInstalled", runCatching { ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess)
                put("shizukuRunning", running)
                put("permission", running && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            }
        }
    }
}
