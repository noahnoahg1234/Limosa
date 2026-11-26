package com.limosa.xr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The emulated headset and controllers. The touch overlay and the gyro write it on the UI and
 * sensor threads; the page reads it once per frame through [QuestBridge.poll]. Every access is
 * synchronized on the object.
 */
class XrState {
    class Hand(val left: Boolean) {
        // Offset from the head in metres (x right, y up, -z forward) and rotation (pitch, yaw) in radians.
        val pos = defaultPos()
        var pitch = 0f
        var yaw = 0f
        /** xr-standard order: trigger, squeeze, touchpad, thumbstick, A/X, B/Y, thumbrest, then menu (native games only). */
        val buttons = FloatArray(8)
        val touched = BooleanArray(8)
        var stickX = 0f
        var stickY = 0f

        fun defaultPos() = floatArrayOf(if (left) -0.2f else 0.2f, -0.3f, -0.45f)
        fun reset() {
            defaultPos().copyInto(pos)
            pitch = 0f
            yaw = 0f
        }
    }

    val left = Hand(true)
    val right = Hand(false)

    /** Yaw/pitch added by dragging with one finger. */
    var lookYaw = 0f
    var lookPitch = 0f
    /** Orientation from the gyro, already recentered; null when the gyro is off or missing. */
    var gyro: FloatArray? = null

    var eyeHeight = 1.6f
    var stereo = false
    var ipd = 0.063f
    var fovDegrees = 70f
    var renderScale = 1f

    @Synchronized
    fun recenter() {
        lookYaw = 0f
        lookPitch = 0f
        left.reset()
        right.reset()
    }

    /** Head orientation quaternion (x, y, z, w) in WebXR coordinates. */
    @Synchronized
    fun headQuat(): FloatArray {
        val yawQ = axisQuat(0f, 1f, 0f, lookYaw)
        val g = gyro
        val rest = g ?: axisQuat(1f, 0f, 0f, lookPitch)
        return quatMul(yawQ, rest)
    }

    @Synchronized
    fun toJson(): String {
        val q = headQuat()
        val sb = StringBuilder(512)
        sb.append("{\"head\":[").append(q[0]).append(',').append(q[1]).append(',').append(q[2]).append(',').append(q[3]).append(']')
        sb.append(",\"eye\":").append(eyeHeight)
        sb.append(",\"pos\":[0,0,0]")
        sb.append(",\"stereo\":").append(stereo)
        sb.append(",\"ipd\":").append(ipd)
        sb.append(",\"fov\":").append(fovDegrees)
        sb.append(",\"scale\":").append(renderScale)
        hand(sb.append(",\"L\":"), left)
        hand(sb.append(",\"R\":"), right)
        return sb.append('}').toString()
    }

    /**
     * The state for the native OpenXR runtime (see inputFromFloats in runtime.cpp):
     * head quaternion, eye height, stereo, ipd, fov, render scale, then per hand
     * position (3), pitch, yaw, buttons (8), touched (8), stick (2).
     */
    @Synchronized
    fun toFloats(out: FloatArray) {
        val q = headQuat()
        q.copyInto(out, 0)
        out[4] = eyeHeight
        out[5] = if (stereo) 1f else 0f
        out[6] = ipd
        out[7] = fovDegrees
        out[8] = renderScale
        for ((i, h) in listOf(left, right).withIndex()) {
            val b = 9 + i * 23
            h.pos.copyInto(out, b)
            out[b + 3] = h.pitch
            out[b + 4] = h.yaw
            h.buttons.copyInto(out, b + 5)
            for (k in 0 until 8) out[b + 13 + k] = if (h.touched[k]) 1f else 0f
            out[b + 21] = h.stickX
            out[b + 22] = h.stickY
        }
    }

    private fun hand(sb: StringBuilder, h: Hand) {
        sb.append("{\"p\":[").append(h.pos[0]).append(',').append(h.pos[1]).append(',').append(h.pos[2]).append(']')
        sb.append(",\"r\":[").append(h.pitch).append(',').append(h.yaw).append(']')
        sb.append(",\"b\":[")
        h.buttons.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(v) }
        sb.append("],\"t\":[")
        h.touched.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(if (v) 1 else 0) }
        // WebXR thumbstick: +x right, +y down (pulled back).
        sb.append("],\"a\":[").append(h.stickX).append(',').append(h.stickY).append("]}")
    }

    companion object {
        fun axisQuat(x: Float, y: Float, z: Float, angle: Float): FloatArray {
            val s = sin(angle / 2)
            return floatArrayOf(x * s, y * s, z * s, cos(angle / 2))
        }

        fun quatMul(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
        )

        const val FLOATS = 9 + 2 * 23
        const val MAX_PITCH = (PI / 2 * 0.95).toFloat()
    }
}
