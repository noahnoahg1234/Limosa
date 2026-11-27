package com.limosa.kdehome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Shows the KDE desktop and turns touch into mouse input:
 * tap = left click, long press = right click, drag = left drag, two-finger drag = scroll wheel.
 * A hardware mouse and keyboard work directly.
 */
class VncView(context: Context) : View(context) {

    var client: VncClient? = null
        set(value) {
            field = value
            buttons = 0
            postInvalidateOnAnimation()
        }

    /** Modifier keysyms latched from the extra-keys bar; applied to the next key, then released. */
    val latched = LinkedHashSet<Int>()
    var onLatchedChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val scrollStep = 24f * resources.displayMetrics.density
    private var buttons = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragging = false
    private var multiTouch = false
    private var longPressed = false
    private var scrollY = 0f
    private val longPress = Runnable {
        if (!moved && !multiTouch) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val (x, y) = toFb(downX, downY)
            client?.pointer(4, x, y)
            client?.pointer(0, x, y)
        }
    }

    private var composing = ""
    private val handledWithLatch = HashSet<Int>()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val c = client ?: return
        synchronized(c.lock) {
            val b = c.bitmap ?: return
            scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
            offX = (width - b.width * scale) / 2f
            offY = (height - b.height * scale) / 2f
            dst.set(offX, offY, offX + b.width * scale, offY + b.height * scale)
            canvas.drawBitmap(b, null, dst, paint)
        }
    }

    private fun toFb(x: Float, y: Float): Pair<Int, Int> {
        val s = if (scale > 0f) scale else 1f
        return ((x - offX) / s).toInt() to ((y - offY) / s).toInt()
    }

    // ---- Touch and mouse ---------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = client ?: return true
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(c, e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                moved = false; dragging = false; multiTouch = false; longPressed = false
                val (x, y) = toFb(e.x, e.y)
                c.pointer(0, x, y)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (dragging) {
                    val (x, y) = toFb(e.getX(0), e.getY(0))
                    c.pointer(0, x, y)
                    dragging = false
                }
                multiTouch = true
                scrollY = averageY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) {
                    val y = averageY(e)
                    var dy = y - scrollY
                    val (fx, fy) = toFb(e.getX(0), e.getY(0))
                    while (abs(dy) >= scrollStep) {
                        // Fingers moving down scroll the content up (like touch scrolling).
                        val wheel = if (dy > 0) 8 else 16
                        c.pointer(wheel, fx, fy)
                        c.pointer(0, fx, fy)
                        dy -= if (dy > 0) scrollStep else -scrollStep
                        scrollY += if (wheel == 8) scrollStep else -scrollStep
                    }
                    return true
                }
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    removeCallbacks(longPress)
                    if (!longPressed) {
                        val (x, y) = toFb(downX, downY)
                        c.pointer(1, x, y)   // press where the finger went down, then drag
                        dragging = true
                    }
                }
                if (dragging) {
                    val (x, y) = toFb(e.x, e.y)
                    c.pointer(1, x, y)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val (x, y) = toFb(e.x, e.y)
                when {
                    multiTouch || longPressed -> Unit
                    dragging -> c.pointer(0, x, y)
                    else -> {
                        val (dx, dy) = toFb(downX, downY)
                        c.pointer(1, dx, dy)
                        c.pointer(0, dx, dy)
                    }
                }
                dragging = false
                requestFocus()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    val (x, y) = toFb(e.x, e.y)
                    c.pointer(0, x, y)
                }
                dragging = false
            }
        }
        return true
    }

    private fun averageY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    private fun mouse(c: VncClient, e: MotionEvent): Boolean {
        var mask = 0
        if (e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or 1
        if (e.buttonState and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or 2
        if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or 4
        buttons = mask
        val (x, y) = toFb(e.x, e.y)
        c.pointer(mask, x, y)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val c = client ?: return super.onGenericMotionEvent(e)
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        val (x, y) = toFb(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> c.pointer(buttons, x, y)
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) {
                    val wheel = if (v > 0) 8 else 16
                    c.pointer(buttons or wheel, x, y)
                    c.pointer(buttons, x, y)
                }
            }
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    // ---- Keyboard ------------------------------------------------------------------------

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Presses and releases a key, with any latched modifiers held around it. */
    fun tapKeysym(sym: Int) {
        val c = client ?: return
        val mods = latched.toList()
        for (m in mods) c.key(m, true)
        c.key(sym, true)
        c.key(sym, false)
        for (m in mods.asReversed()) c.key(m, false)
        if (mods.isNotEmpty()) {
            latched.clear()
            onLatchedChanged?.invoke()
        }
    }

    fun typeText(text: CharSequence) {
        var i = 0
        val s = text.toString()
        while (i < s.length) {
            val cp = s.codePointAt(i)
            tapKeysym(Keysyms.forCodePoint(cp))
            i += Character.charCount(cp)
        }
    }

    private fun backspace(times: Int) = repeat(times) { tapKeysym(Keysyms.BACKSPACE) }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        composing = ""
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                backspace(composing.length)
                composing = ""
                typeText(text)
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // Type the difference so the desktop shows what the keyboard is composing.
                val new = text.toString()
                val common = composing.commonPrefixWith(new).length
                backspace(composing.length - common)
                typeText(new.substring(common))
                composing = new
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                backspace(beforeLength)
                repeat(afterLength) { tapKeysym(Keysyms.DELETE) }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                tapKeysym(Keysyms.RETURN)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = client ?: return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val sym = Keysyms.forKeyEvent(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        if (latched.isNotEmpty() && !Keysyms.isModifier(sym)) {
            tapKeysym(sym)
            handledWithLatch += keyCode
        } else {
            c.key(sym, true)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val c = client ?: return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        if (handledWithLatch.remove(keyCode)) return true
        val sym = Keysyms.forKeyEvent(keyCode, event) ?: return super.onKeyUp(keyCode, event)
        c.key(sym, false)
        return true
    }
}

/** X11 keysyms. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val F1 = 0xffbe
    const val SHIFT = 0xffe1
    const val CTRL = 0xffe3
    const val ALT = 0xffe9
    const val SUPER = 0xffeb

    fun isModifier(sym: Int) = sym in 0xffe1..0xffee

    fun forCodePoint(cp: Int): Int = when {
        cp == '\n'.code || cp == '\r'.code -> RETURN
        cp == '\t'.code -> TAB
        cp == 8 -> BACKSPACE
        cp in 0x20..0x7e || cp in 0xa0..0xff -> cp
        else -> 0x01000000 or cp
    }

    fun forKeyEvent(keyCode: Int, e: KeyEvent): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> 0xff63
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> F1 + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        KeyEvent.KEYCODE_CTRL_LEFT -> CTRL
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        KeyEvent.KEYCODE_ALT_LEFT -> ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        KeyEvent.KEYCODE_META_LEFT -> SUPER
        KeyEvent.KEYCODE_META_RIGHT -> 0xffec
        KeyEvent.KEYCODE_CAPS_LOCK -> 0xffe5
        else -> {
            val strip = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK
            val u = e.getUnicodeChar(e.metaState and strip.inv())
            if (u > 0) forCodePoint(u) else null
        }
    }
}
