package com.limosa.kdehome

import android.os.Handler
import android.os.Looper

/** Process-wide state of the Linux install and KDE session, observed by the home screen. */
object LinuxState {
    enum class Phase { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, ERROR }

    @Volatile var phase: Phase = Phase.NOT_INSTALLED
        private set
    @Volatile var message: String = ""
        private set
    /** 0..100, or -1 when the current step has no known length. */
    @Volatile var progress: Int = -1
        private set

    private val lines = ArrayDeque<String>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var posted = false

    fun set(phase: Phase, message: String, progress: Int = -1) {
        this.phase = phase
        this.message = message
        this.progress = progress
        changed()
    }

    fun progress(message: String, progress: Int) {
        this.message = message
        this.progress = progress
        changed()
    }

    fun log(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 400) lines.removeFirst()
        }
        changed()
    }

    fun logTail(count: Int): String = synchronized(lines) { lines.toList().takeLast(count).joinToString("\n") }

    /** Listeners run on the main thread. Add and remove them on the main thread. */
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun changed() {
        synchronized(this) {
            if (posted) return
            posted = true
        }
        // Coalesce bursts (apt prints thousands of lines) into at most ~10 UI updates a second.
        main.postDelayed({
            synchronized(this) { posted = false }
            for (l in listeners.toList()) l()
        }, 100)
    }
}
