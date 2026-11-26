package com.limosa.xr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns the phone's orientation into head orientation, "magic window" style: the virtual
 * camera looks out of the back of the phone. Uses the game rotation vector (gyro + accelerometer,
 * no compass) so it does not jump near magnets. Yaw is recentered on [recenter].
 */
class HeadTracker(context: Context, private val state: XrState) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available get() = sensor != null
    var enabled = false
        private set

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private var yawZero: Float? = null

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on && sensor != null
        if (enabled) {
            yawZero = null
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors.unregisterListener(this)
            synchronized(state) { state.gyro = null }
        }
    }

    fun recenter() {
        yawZero = null
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        val (ax, ay) = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
        // remapped maps screen axes (x right, y up, z towards the user) to world axes
        // (x east, y north, z up). WebXR world: x = east, y = up, z = south (= -north).
        val r = remapped
        val m = floatArrayOf(
            r[0], r[1], r[2],
            r[6], r[7], r[8],
            -r[3], -r[4], -r[5],
        )
        var q = matToQuat(m)
        // Yaw of the camera's forward axis (-z column) around world up.
        val fx = -m[2]
        val fz = -m[8]
        val yaw = atan2(-fx, -fz)
        val zero = yawZero ?: yaw.also { yawZero = it }
        q = XrState.quatMul(XrState.axisQuat(0f, 1f, 0f, -zero), q)
        synchronized(state) { state.gyro = q }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /** Row-major 3x3 rotation matrix to quaternion (x, y, z, w). */
        fun matToQuat(m: FloatArray): FloatArray {
            val t = m[0] + m[4] + m[8]
            val q = if (t > 0) {
                val s = 0.5f / sqrt(t + 1f)
                floatArrayOf((m[7] - m[5]) * s, (m[2] - m[6]) * s, (m[3] - m[1]) * s, 0.25f / s)
            } else if (m[0] > m[4] && m[0] > m[8]) {
                val s = 2f * sqrt(1f + m[0] - m[4] - m[8])
                floatArrayOf(0.25f * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
            } else if (m[4] > m[8]) {
                val s = 2f * sqrt(1f + m[4] - m[0] - m[8])
                floatArrayOf((m[1] + m[3]) / s, 0.25f * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
            } else {
                val s = 2f * sqrt(1f + m[8] - m[0] - m[4])
                floatArrayOf((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25f * s, (m[3] - m[1]) / s)
            }
            val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            return floatArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l)
        }
    }
}
