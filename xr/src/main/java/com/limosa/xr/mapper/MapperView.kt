package com.limosa.xr.mapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * The controller drawn over another app, and the logic that turns real fingers into the
 * touches that app sees. It never receives touches itself: [onFrame] gets every real finger
 * from the grabbed touchscreen, in this view's coordinates.
 *
 * - Fingers on a control drive it: buttons hold a finger on their target point, the left stick
 *   moves a finger around its target (a game's joystick), the right stick keeps dragging a finger
 *   from its target (a game's camera area).
 * - All other fingers pass straight through to the app.
 * - In edit mode, drag a control's circle to move it, and its crosshair to choose where it touches.
 */
class MapperView(context: Context, var layout: MapperLayout, private val listener: Listener) : View(context) {

    interface Listener {
        fun onPill(id: String)
        /** The complete set of fingers the app should see: ids, xy in screen pixels. */
        fun onInject(ids: IntArray, xy: FloatArray, count: Int)
    }

    enum class Mode { PLAY, HIDDEN, EDIT }

    var mode = Mode.PLAY
        set(v) { field = v; layoutPills(); invalidate() }

    /** Offset of this view on the screen, to turn view coordinates into screen coordinates. */
    var screenOffsetX = 0f
    var screenOffsetY = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val buttonR get() = dp(26f)
    private val stickR get() = dp(56f)
    private val small get() = min(width, height).toFloat()

    private sealed class Role {
        object Pass : Role()
        object None : Role()
        class OnControl(val c: MapperLayout.Control) : Role()
        class DragControl(val c: MapperLayout.Control, val dx: Float, val dy: Float) : Role()
        class DragTarget(val c: MapperLayout.Control, val dx: Float, val dy: Float) : Role()
    }

    private class Finger(var role: Role, var x: Float, var y: Float)
    private val fingers = HashMap<Int, Finger>()

    private class StickState { var vx = 0f; var vy = 0f; var active = false; var camX = 0f; var camY = 0f; var camDown = false; var lift = false }
    private val sticks = HashMap<String, StickState>()
    private var lastTick = 0L

    private class Pill(val id: String, val label: String) { val rect = RectF(); var flash = 0L }
    private var pills = emptyList<Pill>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = Color.argb(200, 255, 210, 120)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(13f)
        setShadowLayer(dp(2f), 0f, 0f, Color.argb(170, 0, 0, 0))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutPills()

    private fun layoutPills() {
        pills = when (mode) {
            Mode.PLAY -> listOf(Pill("edit", "✎ Edit"), Pill("hide", "Hide"), Pill("stop", "✕ Stop"))
            Mode.HIDDEN -> listOf(Pill("show", "Controls"))
            Mode.EDIT -> listOf(Pill("done", "✓ Done"), Pill("reset", "Reset"))
        }
        val h = dp(30f)
        val gap = dp(8f)
        val widths = pills.map { text.measureText(it.label) + dp(22f) }
        var x = (width - widths.sum() - gap * (pills.size - 1)) / 2
        val y = dp(8f)
        pills.forEachIndexed { i, p -> p.rect.set(x, y, x + widths[i], y + h); x += widths[i] + gap }
    }

    private fun cx(c: MapperLayout.Control) = c.cx * width
    private fun cy(c: MapperLayout.Control) = c.cy * height
    private fun tx(c: MapperLayout.Control) = c.tx * width
    private fun ty(c: MapperLayout.Control) = c.ty * height
    private fun radius(c: MapperLayout.Control) = if (c.kind == MapperLayout.Kind.BUTTON) buttonR else stickR

    // ------------------------------------------------------------------ input --

    /** One frame of real fingers: ids and positions in view coordinates. */
    fun onFrame(ids: IntArray, xy: FloatArray, count: Int) {
        val seen = HashSet<Int>()
        for (i in 0 until count) {
            val id = ids[i]
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            seen.add(id)
            val f = fingers[id]
            if (f == null) fingers[id] = Finger(roleFor(x, y), x, y) else { f.x = x; f.y = y }
            fingers[id]?.let { move(it) }
        }
        for (id in fingers.keys.toList()) if (id !in seen) release(fingers.remove(id)!!)
        pushInjection()
        invalidate()
    }

    private fun roleFor(x: Float, y: Float): Role {
        pills.firstOrNull { it.rect.contains(x, y) }?.let { p ->
            p.flash = SystemClock.uptimeMillis()
            post { listener.onPill(p.id) }
            return Role.None
        }
        if (mode == Mode.HIDDEN) return Role.Pass
        if (mode == Mode.EDIT) {
            // Crosshairs win over circles when both are close, so a target can be dragged off its control.
            nearest(x, y, dp(30f), target = true)?.let { return Role.DragTarget(it, tx(it) - x, ty(it) - y) }
            nearest(x, y, null, target = false)?.let { return Role.DragControl(it, cx(it) - x, cy(it) - y) }
            return Role.None
        }
        nearest(x, y, null, target = false)?.let { c ->
            if (c.kind != MapperLayout.Kind.BUTTON) sticks.getOrPut(c.id) { StickState() }.active = true
            return Role.OnControl(c)
        }
        return Role.Pass
    }

    private fun nearest(x: Float, y: Float, maxDist: Float?, target: Boolean): MapperLayout.Control? {
        var best: MapperLayout.Control? = null
        var bestD = Float.MAX_VALUE
        for (c in layout.controls) {
            val d = if (target) hypot(x - tx(c), y - ty(c)) else hypot(x - cx(c), y - cy(c))
            val limit = maxDist ?: (radius(c) * 1.25f)
            if (d <= limit && d < bestD) { best = c; bestD = d }
        }
        return best
    }

    private fun move(f: Finger) {
        when (val r = f.role) {
            is Role.OnControl -> if (r.c.kind != MapperLayout.Kind.BUTTON) {
                val s = sticks.getOrPut(r.c.id) { StickState() }
                var vx = (f.x - cx(r.c)) / stickR
                var vy = (f.y - cy(r.c)) / stickR
                val len = hypot(vx, vy)
                if (len > 1f) { vx /= len; vy /= len }
                if (len < 0.1f) { vx = 0f; vy = 0f }
                s.vx = vx; s.vy = vy
            }
            is Role.DragControl -> {
                r.c.cx = ((f.x + r.dx) / width).coerceIn(0f, 1f)
                r.c.cy = ((f.y + r.dy) / height).coerceIn(0f, 1f)
            }
            is Role.DragTarget -> {
                r.c.tx = ((f.x + r.dx) / width).coerceIn(0f, 1f)
                r.c.ty = ((f.y + r.dy) / height).coerceIn(0f, 1f)
            }
            else -> {}
        }
    }

    private fun release(f: Finger) {
        val r = f.role
        if (r is Role.OnControl) sticks[r.c.id]?.let { it.active = false; it.vx = 0f; it.vy = 0f; it.camDown = false }
    }

    /** Releases every finger, e.g. when switching modes. */
    fun releaseAll() {
        fingers.values.forEach { release(it) }
        fingers.clear()
        pushInjection()
        invalidate()
    }

    private val tick = Runnable { pushInjection() }

    private fun pushInjection() {
        val ids = IntArray(32)
        val xy = FloatArray(64)
        var n = 0
        fun add(id: Int, x: Float, y: Float) {
            if (n >= 32) return
            ids[n] = id
            xy[2 * n] = x + screenOffsetX
            xy[2 * n + 1] = y + screenOffsetY
            n++
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastTick == 0L) 0.016f else ((now - lastTick) / 1000f).coerceIn(0f, 0.05f)
        lastTick = now
        var cameraMoving = false
        if (mode != Mode.EDIT) {
            for ((id, f) in fingers) if (f.role === Role.Pass) add(id and 15, f.x, f.y)
            layout.controls.forEachIndexed { i, c ->
                val vid = 16 + i
                when (c.kind) {
                    MapperLayout.Kind.BUTTON -> if (fingers.values.any { (it.role as? Role.OnControl)?.c === c }) add(vid, tx(c), ty(c))
                    MapperLayout.Kind.JOYSTICK -> {
                        val s = sticks[c.id]
                        if (s != null && s.active) {
                            val r = small * 0.11f
                            add(vid, tx(c) + s.vx * r, ty(c) + s.vy * r)
                        }
                    }
                    MapperLayout.Kind.CAMERA -> {
                        val s = sticks[c.id]
                        if (s != null && s.active && (s.vx != 0f || s.vy != 0f)) {
                            cameraMoving = true
                            if (s.lift) {
                                s.lift = false  // one frame without the finger, then start again
                            } else {
                                if (!s.camDown) { s.camX = tx(c); s.camY = ty(c); s.camDown = true }
                                val speed = small * 1.1f
                                s.camX += s.vx * speed * dt
                                s.camY += s.vy * speed * dt
                                if (hypot(s.camX - tx(c), s.camY - ty(c)) > small * 0.22f) { s.camDown = false; s.lift = true }
                                else add(vid, s.camX, s.camY)
                            }
                        } else if (s != null) {
                            s.camDown = false
                        }
                    }
                }
            }
        }
        listener.onInject(ids, xy, n)
        removeCallbacks(tick)
        if (cameraMoving) postDelayed(tick, 16)
    }

    // ------------------------------------------------------------------ drawing --

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (p in pills) {
            fill.color = Color.argb(if (now - p.flash < 200) 140 else 70, 255, 255, 255)
            stroke.color = Color.argb(160, 255, 255, 255)
            val rr = p.rect.height() / 2
            c.drawRoundRect(p.rect, rr, rr, fill)
            c.drawRoundRect(p.rect, rr, rr, stroke)
            c.drawText(p.label, p.rect.centerX(), p.rect.centerY() + text.textSize * 0.35f, text)
        }
        if (mode == Mode.HIDDEN) return
        val held = fingers.values.mapNotNull { (it.role as? Role.OnControl)?.c }.toSet()
        for (ctl in layout.controls) {
            val x = cx(ctl)
            val y = cy(ctl)
            val r = radius(ctl)
            if (mode == Mode.EDIT) {
                // Where this control touches the app.
                val tX = tx(ctl)
                val tY = ty(ctl)
                if (hypot(tX - x, tY - y) > dp(4f)) c.drawLine(x, y, tX, tY, dashed)
                stroke.color = Color.argb(230, 255, 210, 120)
                c.drawCircle(tX, tY, dp(14f), stroke)
                c.drawLine(tX - dp(20f), tY, tX + dp(20f), tY, stroke)
                c.drawLine(tX, tY - dp(20f), tX, tY + dp(20f), stroke)
                if (ctl.kind == MapperLayout.Kind.JOYSTICK) c.drawCircle(tX, tY, small * 0.11f, dashed)
                if (ctl.kind == MapperLayout.Kind.CAMERA) c.drawCircle(tX, tY, small * 0.22f, dashed)
                c.drawText(ctl.label, tX + dp(22f), tY - dp(8f), text)
            }
            fill.color = Color.argb(if (ctl in held) 140 else 45, 255, 255, 255)
            stroke.color = Color.argb(170, 255, 255, 255)
            c.drawCircle(x, y, r, fill)
            c.drawCircle(x, y, r, stroke)
            if (ctl.kind != MapperLayout.Kind.BUTTON) {
                val s = sticks[ctl.id]
                val kx = x + (s?.vx ?: 0f) * r
                val ky = y + (s?.vy ?: 0f) * r
                fill.color = Color.argb(if (s?.active == true) 150 else 80, 255, 255, 255)
                c.drawCircle(kx, ky, r * 0.42f, fill)
                c.drawText(ctl.label, kx, ky + text.textSize * 0.35f, text)
            } else {
                c.drawText(ctl.label, x, y + text.textSize * 0.35f, text)
            }
        }
        if (mode == Mode.EDIT) {
            c.drawText("Drag a circle to move a control. Drag its crosshair onto the app's own button it should press.",
                width / 2f, height - dp(16f), text)
        }
    }
}
