package com.limosa.core.idle

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Decides when the phone's OS counts as "inactive" so VMs can be paused to save CPU and
 * battery. The Android side feeds it signals (screen, lock, Doze, battery saver, battery);
 * this class applies the user's [Settings] and calls [onIdle] / [onActive] on transitions.
 */
class IdleController(
    @Volatile var settings: Settings = Settings(),
    private val onIdle: (reason: String) -> Unit,
    private val onActive: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    data class Settings(
        val enabled: Boolean = true,
        /** Pause once the screen has been off this long. Negative disables. */
        val screenOffDelaySec: Int = 30,
        /** Pause while the lock screen is showing, even with the screen on. */
        val pauseWhenLocked: Boolean = false,
        /** Pause when Android enters Doze (device idle). */
        val pauseOnDeviceIdle: Boolean = true,
        /** Pause while Battery Saver is on. */
        val pauseOnPowerSave: Boolean = false,
        /** Pause when the battery is at or below this percent and not charging. 0 disables. */
        val lowBatteryPercent: Int = 10,
    )

    data class Signals(
        val screenOn: Boolean = true,
        val locked: Boolean = false,
        val deviceIdle: Boolean = false,
        val powerSave: Boolean = false,
        val batteryPercent: Int = 100,
        val charging: Boolean = true,
    )

    @Volatile var signals = Signals()
        private set
    @Volatile var idle = false
        private set
    @Volatile var idleReason: String? = null
        private set
    @Volatile private var screenOffSince: Long? = null
    private var scheduler: ScheduledExecutorService? = null

    @Synchronized
    fun update(transform: (Signals) -> Signals) {
        val old = signals
        val new = transform(old)
        if (old.screenOn && !new.screenOn) screenOffSince = clock()
        if (new.screenOn) screenOffSince = null
        signals = new
        evaluate()
    }

    /** Re-checks time-based rules (the screen-off delay). Called periodically by [start]. */
    @Synchronized
    fun evaluate() {
        val reason = inactiveReason()
        if (reason != null && !idle) {
            idle = true
            idleReason = reason
            onIdle(reason)
        } else if (reason == null && idle) {
            idle = false
            idleReason = null
            onActive()
        } else if (reason != null) {
            idleReason = reason
        }
    }

    /** Why the OS is considered inactive right now, or null if it is active. */
    fun inactiveReason(): String? {
        val s = settings
        if (!s.enabled) return null
        val sig = signals
        if (s.pauseOnDeviceIdle && sig.deviceIdle) return "device idle (Doze)"
        if (s.lowBatteryPercent > 0 && !sig.charging && sig.batteryPercent <= s.lowBatteryPercent) return "battery low"
        if (s.pauseOnPowerSave && sig.powerSave) return "battery saver"
        val off = screenOffSince
        if (!sig.screenOn && s.screenOffDelaySec >= 0 && off != null &&
            clock() - off >= s.screenOffDelaySec * 1000L
        ) return "screen off"
        if (s.pauseWhenLocked && sig.locked) return "locked"
        return null
    }

    fun start(periodSec: Long = 5): IdleController {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "idle").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay({ runCatching { evaluate() } }, periodSec, periodSec, TimeUnit.SECONDS) }
        }
        return this
    }

    override fun close() {
        scheduler?.shutdownNow()
        scheduler = null
    }
}
