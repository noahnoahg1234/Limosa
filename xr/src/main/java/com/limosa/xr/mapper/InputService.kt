package com.limosa.xr.mapper

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs in a separate process as the shell user, started by Shizuku (see MapperService).
 *
 * It takes the touchscreen for itself (EVIOCGRAB), so Android stops seeing real touches, and
 * streams the raw fingers to Limosa XR. Limosa XR decides what apps should see: touches on the
 * on-screen controls become mapped touches, all others pass straight through. Those come back
 * through [inject] and are injected as one consistent touch stream.
 *
 * Closing the device (on [stop], on Limosa XR dying, or this process ending) gives the
 * touchscreen back to Android.
 */
class InputService : IInputService.Stub() {
    private var stream: FileInputStream? = null
    private var listener: ITouchListener? = null
    private var deathLink: android.os.IBinder.DeathRecipient? = null
    private val injector by lazy { Injector() }
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastReplyAt = 0L

    init {
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    @Synchronized
    override fun start(l: ITouchListener): String? {
        stop()
        val dev = findTouchscreen() ?: return "No multi-touch touchscreen was found."
        val fis = try {
            FileInputStream(dev.path)
        } catch (t: Throwable) {
            return "Cannot open the touchscreen (${dev.path}): ${t.message}"
        }
        try {
            grab(fis.fd)
        } catch (t: Throwable) {
            runCatching { fis.close() }
            return "Could not take over the touchscreen: ${t.cause?.message ?: t.message}"
        }
        stream = fis
        listener = l
        val death = android.os.IBinder.DeathRecipient { stop() }
        runCatching { l.asBinder().linkToDeath(death, 0) }
        deathLink = death
        lastFrameAt = 0
        lastReplyAt = SystemClock.uptimeMillis()
        Thread({ readLoop(fis, dev, l) }, "touch-reader").apply { isDaemon = true; start() }
        Thread({ watchdog(fis) }, "touch-watchdog").apply { isDaemon = true; start() }
        Log.i(TAG, "grabbed ${dev.path} (${dev.name})")
        return null
    }

    @Synchronized
    override fun stop() {
        runCatching { injector.set(IntArray(0), FloatArray(0), 0) }
        stream?.let { runCatching { it.close() } }  // closing the device releases the grab
        stream = null
        listener?.let { l -> deathLink?.let { runCatching { l.asBinder().unlinkToDeath(it, 0) } } }
        listener = null
        deathLink = null
    }

    override fun inject(ids: IntArray, xy: FloatArray, count: Int) {
        lastReplyAt = SystemClock.uptimeMillis()
        injector.set(ids, xy, count)
    }

    /** If Limosa XR stops answering, give the touchscreen back so the phone stays usable. */
    private fun watchdog(fis: FileInputStream) {
        while (stream === fis) {
            SystemClock.sleep(500)
            val now = SystemClock.uptimeMillis()
            if (lastFrameAt > lastReplyAt && now - lastReplyAt > 2500) {
                Log.w(TAG, "Limosa XR stopped answering; releasing the touchscreen")
                val l = listener
                stop()
                runCatching { l?.onStopped("Limosa XR stopped responding, so the touchscreen was given back.") }
                return
            }
        }
    }

    private fun readLoop(fis: FileInputStream, dev: Touchscreen, l: ITouchListener) {
        val size = if (Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit()) 24 else 16
        val buf = ByteArray(size * 64)
        val parser = MtParser(dev)
        try {
            while (stream === fis) {
                val n = fis.read(buf)
                if (n <= 0) break
                var off = 0
                while (off + size <= n) {
                    val type = u16(buf, off + size - 8)
                    val code = u16(buf, off + size - 6)
                    val value = i32(buf, off + size - 4)
                    off += size
                    if (parser.event(type, code, value)) {
                        lastFrameAt = SystemClock.uptimeMillis()
                        l.onFrame(parser.ids, parser.xy, parser.count)
                    }
                }
            }
        } catch (t: Throwable) {
            if (stream === fis) {
                Log.w(TAG, "touch reader stopped", t)
                stop()
                runCatching { l.onStopped("The touchscreen reader stopped: ${t.message}") }
            }
        }
    }

    private fun findTouchscreen(): Touchscreen? {
        val out = runCatching {
            val p = ProcessBuilder("getevent", "-pl").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrNull() ?: return null
        return Touchscreen.parse(out)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun i32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or (b[o + 3].toInt() shl 24)

    /** ioctl(fd, EVIOCGRAB, non-zero) through the platform's hidden Os.ioctlInt. */
    private fun grab(fd: FileDescriptor) {
        val evIocGrab = 0x40044590
        val candidates = ArrayList<Pair<Method, Any?>>()
        HiddenApiBypass.getDeclaredMethods(android.system.Os::class.java)
            .filterIsInstance<Method>().filter { it.name == "ioctlInt" }.forEach { candidates.add(it to null) }
        runCatching {
            val libcore = Class.forName("libcore.io.Libcore")
            val os = libcore.getDeclaredField("os").apply { isAccessible = true }.get(null)
            HiddenApiBypass.getDeclaredMethods(Class.forName("libcore.io.Os"))
                .filterIsInstance<Method>().filter { it.name == "ioctlInt" }.forEach { candidates.add(it to os) }
        }
        var last: Throwable? = null
        for ((m, target) in candidates) {
            try {
                m.isAccessible = true
                val p = m.parameterTypes
                when (p.size) {
                    2 -> m.invoke(target, fd, evIocGrab)
                    3 -> {
                        val arg = p[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                        m.invoke(target, fd, evIocGrab, arg)
                    }
                    else -> continue
                }
                return
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("ioctl is not available")
    }

    /** Builds touchscreen MotionEvents for the requested fingers and injects them. */
    private class Injector {
        private val manager: Any
        private val injectMethod: Method
        private val deviceId: Int
        private val current = LinkedHashMap<Int, FloatArray>()
        private var downTime = 0L

        init {
            manager = runCatching {
                Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
            }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
            injectMethod = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            deviceId = InputDevice.getDeviceIds().firstOrNull { id ->
                InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true
            } ?: 0
        }

        @Synchronized
        fun set(ids: IntArray, xy: FloatArray, count: Int) {
            val next = LinkedHashMap<Int, FloatArray>()
            for (i in 0 until count) next[ids[i]] = floatArrayOf(xy[2 * i], xy[2 * i + 1])
            // Lift fingers that went away.
            for (id in current.keys.toList()) {
                if (next.containsKey(id)) continue
                val index = current.keys.indexOf(id)
                send(if (current.size == 1) MotionEvent.ACTION_UP else pointerAction(MotionEvent.ACTION_POINTER_UP, index))
                current.remove(id)
            }
            // Move the ones that stayed.
            var moved = false
            for ((id, p) in current) {
                val n = next[id] ?: continue
                if (n[0] != p[0] || n[1] != p[1]) { p[0] = n[0]; p[1] = n[1]; moved = true }
            }
            if (moved) send(MotionEvent.ACTION_MOVE)
            // Put down new ones.
            for ((id, p) in next) {
                if (current.containsKey(id)) continue
                current[id] = p.copyOf()
                if (current.size == 1) {
                    downTime = SystemClock.uptimeMillis()
                    send(MotionEvent.ACTION_DOWN)
                } else {
                    send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, current.size - 1))
                }
            }
        }

        private fun pointerAction(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        private fun send(action: Int) {
            val n = current.size
            if (n == 0) return
            val props = arrayOfNulls<MotionEvent.PointerProperties>(n)
            val coords = arrayOfNulls<MotionEvent.PointerCoords>(n)
            var i = 0
            for ((id, p) in current) {
                props[i] = MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
                coords[i] = MotionEvent.PointerCoords().apply { x = p[0]; y = p[1]; pressure = 1f; size = 0.05f }
                i++
            }
            val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, n, props, coords, 0, 0, 1f, 1f,
                deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                injectMethod.invoke(manager, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
            } catch (t: Throwable) {
                Log.w(TAG, "inject failed", t)
            } finally {
                ev.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "LimosaXR-Touch"
    }
}
