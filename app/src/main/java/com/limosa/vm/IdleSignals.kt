package com.limosa.vm

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.limosa.core.idle.IdleController

/**
 * Feeds Android's "is the OS active?" signals into [IdleController]: screen on/off,
 * lock screen, Doze (device idle), Battery Saver and battery level.
 */
class IdleSignals(private val context: Context, private val controller: IdleController) : BroadcastReceiver() {

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    // Pausing talks to QEMU over a socket, which must not happen on the main thread.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "idle-signals") }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // These are all protected system broadcasts, so the receiver does not need to be exported.
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
        refresh(sticky)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(this) }
        worker.shutdown()
    }

    override fun onReceive(context: Context, intent: Intent) {
        refresh(if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent else null)
    }

    private fun refresh(battery: Intent?) {
        val screenOn = power.isInteractive
        val locked = keyguard.isKeyguardLocked
        val idle = power.isDeviceIdleMode
        val saver = power.isPowerSaveMode
        worker.execute { apply(screenOn, locked, idle, saver, battery) }
    }

    private fun apply(screenOn: Boolean, locked: Boolean, idle: Boolean, saver: Boolean, battery: Intent?) {
        controller.update { s ->
            var n = s.copy(screenOn = screenOn, locked = locked, deviceIdle = idle, powerSave = saver)
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                if (level >= 0 && scale > 0) n = n.copy(batteryPercent = level * 100 / scale, charging = plugged)
            }
            n
        }
    }
}
