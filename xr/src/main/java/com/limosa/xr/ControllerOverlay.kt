package com.limosa.xr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent on-screen controls drawn over the VR view.
 *
 * - One finger on empty space: look around.
 * - Two fingers on empty space: the left finger moves the left hand, the right finger the right hand.
 *   The "Hand" button switches what the fingers do: move (up/down/sideways), depth (forward/back)
 *   or rotate (aim).
 * - Trigger / grip / A B X Y buttons and two thumbsticks, like a pair of touch controllers.
 */
class ControllerOverlay(context: Context, private val state: XrState) : View(context) {

    interface Listener {
        fun onSystemAction(action: String)
    }

    var listener: Listener? = null
    var gyroOn = false
        set(v) { field = v; invalidate() }
    var stereoOn = false
        set(v) { field = v; invalidate() }

    private enum class Mode(val label: String) { MOVE("Hand: Move"), DEPTH("Hand: Depth"), ROTATE("Hand: Aim") }
    private var mode = Mode.MOVE
    private var hidden = false
    private var hintUntil = 0L

    private enum class Kind { HOLD, PILL }

    private class Btn(
        val id: String, var label: String, val kind: Kind,
        val hand: XrState.Hand? = null, val index: Int = -1,
    ) {
        var cx = 0f; var cy = 0f; var r = 0f
        val rect = RectF()
        var pressed = false
        fun hit(x: Float, y: Float): Boolean =
            if (kind == Kind.PILL) rect.contains(x, y) else hypot(x - cx, y - cy) <= r * 1.15f
    }

    private class Stick(val hand: XrState.Hand) {
        var cx = 0f; var cy = 0f; var r = 0f
        var kx = 0f; var ky = 0f
        var active = false
        var downAt = 0L
        var maxTravel = 0f
    }

    private val buttons = listOf(
        Btn("LT", "LT", Kind.HOLD, state.left, 0),
        Btn("LG", "LG", Kind.HOLD, state.left, 1),
        Btn("X", "X", Kind.HOLD, state.left, 4),
        Btn("Y", "Y", Kind.HOLD, state.left, 5),
        Btn("RT", "RT", Kind.HOLD, state.right, 0),
        Btn("RG", "RG", Kind.HOLD, state.right, 1),
        Btn("A", "A", Kind.HOLD, state.right, 4),
        Btn("B", "B", Kind.HOLD, state.right, 5),
        Btn("center", "⟲ Center", Kind.PILL),
        Btn("gyro", "Gyro", Kind.PILL),
        Btn("stereo", "Stereo", Kind.PILL),
        Btn("mode", Mode.MOVE.label, Kind.PILL),
        Btn("hide", "Hide", Kind.PILL),
        Btn("home", "⌂ Home", Kind.PILL),
        Btn("exit", "✕ Exit VR", Kind.PILL),
    )
    private val sticks = listOf(Stick(state.left), Stick(state.right))

    private sealed class Role {
        class Button(val btn: Btn) : Role()
        class StickRole(val stick: Stick) : Role()
        object Look : Role()
        class HandRole(val hand: XrState.Hand) : Role()
        object Ignore : Role()
    }

    private class Pointer(var role: Role, var x: Float, var y: Float)
    private val pointers = HashMap<Int, Pointer>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(14f)
        setShadowLayer(dp(2f), 0f, 0f, Color.argb(160, 0, 0, 0))
    }
    private val small = Paint(text).apply { textSize = dp(12f) }

    init {
        isClickable = true
        isFocusable = false
    }

    fun showHint() {
        hintUntil = SystemClock.uptimeMillis() + 7000
        invalidate()
        postDelayed({ invalidate() }, 7100)
    }

    /** Releases every held control, e.g. when the session ends. */
    fun releaseAll() {
        pointers.keys.toList().forEach { release(it) }
        pointers.clear()
        synchronized(state) {
            for (h in listOf(state.left, state.right)) {
                h.buttons.fill(0f); h.touched.fill(false); h.stickX = 0f; h.stickY = 0f
            }
        }
        buttons.forEach { it.pressed = false }
        sticks.forEach { it.active = false }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutControls(w.toFloat(), h.toFloat())
    }

    private fun layoutControls(w: Float, h: Float) {
        val m = dp(16f)
        val stickR = min(dp(62f), h * 0.17f)
        val trigR = min(dp(32f), h * 0.085f)
        val faceR = min(dp(27f), h * 0.07f)
        fun place(id: String, x: Float, y: Float, r: Float) = buttons.first { it.id == id }.apply { cx = x; cy = y; this.r = r }

        // Shoulders: triggers and grips near the top corners.
        place("LT", m + trigR, dp(64f) + trigR, trigR)
        place("LG", m + trigR, dp(64f) + trigR * 3.4f, trigR)
        place("RT", w - m - trigR, dp(64f) + trigR, trigR)
        place("RG", w - m - trigR, dp(64f) + trigR * 3.4f, trigR)

        // Thumbsticks in the bottom corners, face buttons beside them.
        sticks[0].apply { cx = m + stickR * 1.25f; cy = h - m - stickR * 1.25f; r = stickR; kx = cx; ky = cy }
        sticks[1].apply { cx = w - m - stickR * 1.25f; cy = h - m - stickR * 1.25f; r = stickR; kx = cx; ky = cy }
        val sx = stickR * 2.6f + m
        place("X", sx + faceR, h - m - faceR, faceR)
        place("Y", sx + faceR * 1.9f, h - m - faceR * 3.3f, faceR)
        place("A", w - sx - faceR, h - m - faceR, faceR)
        place("B", w - sx - faceR * 1.9f, h - m - faceR * 3.3f, faceR)

        // System row along the top.
        val pills = buttons.filter { it.kind == Kind.PILL }
        val ph = dp(34f)
        val gap = dp(8f)
        val widths = pills.map { small.measureText(pillLabel(it)) + dp(22f) }
        var total = widths.sum() + gap * (pills.size - 1)
        val maxW = w - 2 * (m + trigR * 2 + gap)
        val shrink = if (total > maxW) maxW / total else 1f
        total *= shrink
        var x = (w - total) / 2
        val y = dp(12f)
        pills.forEachIndexed { i, b ->
            val bw = widths[i] * shrink
            b.rect.set(x, y, x + bw, y + ph)
            x += bw + gap * shrink
        }
    }

    private fun pillLabel(b: Btn) = when (b.id) {
        "gyro" -> if (gyroOn) "Gyro: on" else "Gyro: off"
        "stereo" -> if (stereoOn) "View: 3D" else "View: flat"
        "mode" -> mode.label
        "hide" -> if (hidden) "Show" else "Hide"
        else -> b.label
    }

    // ------------------------------------------------------------------ touch --

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointers.keys.toList().forEach { release(it) }
            }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val pill = buttons.firstOrNull { it.kind == Kind.PILL && it.hit(x, y) && (!hidden || it.id == "hide") }
        if (pill != null) {
            pointers[id] = Pointer(Role.Ignore, x, y)
            pill.pressed = true
            postDelayed({ pill.pressed = false; invalidate() }, 150)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            systemAction(pill.id)
            return
        }
        if (!hidden) {
            val btn = buttons.firstOrNull { it.kind == Kind.HOLD && it.hit(x, y) }
            if (btn != null) {
                pointers[id] = Pointer(Role.Button(btn), x, y)
                btn.pressed = true
                synchronized(state) { btn.hand!!.buttons[btn.index] = 1f; btn.hand.touched[btn.index] = true }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return
            }
            val stick = sticks.firstOrNull { !it.active && hypot(x - it.cx, y - it.cy) <= it.r * 1.3f }
            if (stick != null) {
                stick.active = true
                stick.downAt = SystemClock.uptimeMillis()
                stick.maxTravel = 0f
                pointers[id] = Pointer(Role.StickRole(stick), x, y)
                moveStick(stick, x, y)
                return
            }
        }
        // Free finger. A second free finger turns both into hand controls.
        val lookers = pointers.entries.filter { it.value.role === Role.Look }
        if (lookers.isNotEmpty()) {
            val other = lookers.first()
            val (l, r) = if (other.value.x <= x) other.key to id else id to other.key
            val newP = Pointer(Role.Look, x, y)
            pointers[id] = newP
            pointers[l]!!.role = Role.HandRole(state.left)
            pointers[r]!!.role = Role.HandRole(state.right)
            showHint()
            return
        }
        pointers[id] = Pointer(Role.Look, x, y)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        val dx = x - p.x
        val dy = y - p.y
        p.x = x; p.y = y
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        when (val role = p.role) {
            is Role.StickRole -> moveStick(role.stick, x, y)
            Role.Look -> synchronized(state) {
                state.lookYaw += dx / w * PI.toFloat() * 1.2f
                state.lookPitch = (state.lookPitch + dy / h * PI.toFloat() * 0.8f)
                    .coerceIn(-XrState.MAX_PITCH, XrState.MAX_PITCH)
            }
            is Role.HandRole -> synchronized(state) {
                val hand = role.hand
                val k = 1.4f / w // metres per pixel: the screen width spans about 1.4 m
                when (mode) {
                    Mode.MOVE -> {
                        hand.pos[0] = (hand.pos[0] + dx * k).coerceIn(-1f, 1f)
                        hand.pos[1] = (hand.pos[1] - dy * k).coerceIn(-1.2f, 0.6f)
                    }
                    Mode.DEPTH -> {
                        hand.pos[0] = (hand.pos[0] + dx * k).coerceIn(-1f, 1f)
                        hand.pos[2] = (hand.pos[2] + dy * k).coerceIn(-1.2f, 0.15f)
                    }
                    Mode.ROTATE -> {
                        hand.yaw = (hand.yaw - dx / w * PI.toFloat() * 1.5f).coerceIn(-2.5f, 2.5f)
                        hand.pitch = (hand.pitch - dy / w * PI.toFloat() * 1.5f).coerceIn(-1.5f, 1.5f)
                    }
                }
            }
            else -> {}
        }
    }

    private fun moveStick(s: Stick, x: Float, y: Float) {
        var vx = (x - s.cx) / s.r
        var vy = (y - s.cy) / s.r
        val len = hypot(vx, vy)
        if (len > 1f) { vx /= len; vy /= len }
        s.maxTravel = max(s.maxTravel, min(len, 1f))
        s.kx = s.cx + vx * s.r
        s.ky = s.cy + vy * s.r
        synchronized(state) {
            // Small dead zone so a resting thumb does not drift.
            val dz = 0.12f
            s.hand.stickX = if (len < dz) 0f else vx
            s.hand.stickY = if (len < dz) 0f else vy
            s.hand.touched[3] = true
        }
    }

    private fun release(id: Int) {
        val p = pointers.remove(id) ?: return
        when (val role = p.role) {
            is Role.Button -> {
                role.btn.pressed = false
                synchronized(state) { role.btn.hand!!.buttons[role.btn.index] = 0f; role.btn.hand.touched[role.btn.index] = false }
            }
            is Role.StickRole -> {
                val s = role.stick
                s.active = false
                s.kx = s.cx; s.ky = s.cy
                val tap = SystemClock.uptimeMillis() - s.downAt < 250 && s.maxTravel < 0.25f
                synchronized(state) {
                    s.hand.stickX = 0f; s.hand.stickY = 0f; s.hand.touched[3] = false
                    if (tap) s.hand.buttons[3] = 1f // quick tap = click the stick
                }
                if (tap) postDelayed({ synchronized(state) { s.hand.buttons[3] = 0f } }, 120)
            }
            else -> {}
        }
    }

    private fun systemAction(id: String) {
        when (id) {
            "mode" -> mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]
            "hide" -> hidden = !hidden
            "center" -> synchronized(state) { state.recenter() }
        }
        listener?.onSystemAction(id)
        invalidate()
    }

    // ------------------------------------------------------------------ drawing --

    override fun onDraw(c: Canvas) {
        // System pills.
        for (b in buttons) {
            if (b.kind != Kind.PILL) continue
            if (hidden && b.id != "hide") continue
            val active = b.pressed || (b.id == "gyro" && gyroOn) || (b.id == "stereo" && stereoOn)
            fill.color = Color.argb(if (active) 120 else 55, 255, 255, 255)
            stroke.color = Color.argb(150, 255, 255, 255)
            val rr = b.rect.height() / 2
            c.drawRoundRect(b.rect, rr, rr, fill)
            c.drawRoundRect(b.rect, rr, rr, stroke)
            c.drawText(pillLabel(b), b.rect.centerX(), b.rect.centerY() + small.textSize * 0.35f, small)
        }
        if (!hidden) {
            for (b in buttons) {
                if (b.kind != Kind.HOLD) continue
                fill.color = Color.argb(if (b.pressed) 150 else 45, 255, 255, 255)
                stroke.color = Color.argb(160, 255, 255, 255)
                c.drawCircle(b.cx, b.cy, b.r, fill)
                c.drawCircle(b.cx, b.cy, b.r, stroke)
                c.drawText(b.label, b.cx, b.cy + text.textSize * 0.35f, text)
            }
            for (s in sticks) {
                fill.color = Color.argb(35, 255, 255, 255)
                stroke.color = Color.argb(130, 255, 255, 255)
                c.drawCircle(s.cx, s.cy, s.r, fill)
                c.drawCircle(s.cx, s.cy, s.r, stroke)
                fill.color = Color.argb(if (s.active) 150 else 80, 255, 255, 255)
                c.drawCircle(s.kx, s.ky, s.r * 0.42f, fill)
                c.drawText(if (s.hand.left) "L" else "R", s.kx, s.ky + small.textSize * 0.35f, small)
            }
        }
        // Fingers that are driving a hand.
        for (p in pointers.values) {
            val role = p.role
            if (role is Role.HandRole) {
                fill.color = if (role.hand.left) Color.argb(90, 120, 200, 255) else Color.argb(90, 255, 170, 90)
                c.drawCircle(p.x, p.y, dp(34f), fill)
                c.drawText(if (role.hand.left) "L hand" else "R hand", p.x, p.y - dp(42f), small)
            }
        }
        if (SystemClock.uptimeMillis() < hintUntil) {
            c.drawText("1 finger: look   ·   2 fingers: move hands   ·   \"${mode.label}\" changes what fingers do",
                width / 2f, height - dp(24f), small)
        }
    }
}
