package com.limosa.kdehome

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView

/** Modifier keys. Tap once: held for the next key. Tap twice: locked. Tap again: off. */
enum class Mod(val keysym: Int, val keyCode: Int, val meta: Int) {
    SHIFT(0xffe1, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
    CTRL(0xffe3, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
    ALT(0xffe9, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
}

/**
 * One key. [ch]/[shiftCh] are what it types, [keysym] is the X11 key (for KDE) and [keyCode]
 * the Android key (for other apps).
 */
class PcKey(
    val label: String,
    val keysym: Int,
    val keyCode: Int,
    val ch: Char? = null,
    val shiftCh: Char? = null,
    val width: Float = 1f,
    val mod: Mod? = null,
    val repeat: Boolean = false,
    val action: Action = Action.KEY,
) {
    enum class Action { KEY, CAPS, HIDE, SWITCH_KEYBOARD }

    val isLetter get() = ch != null && ch.isLetter()
}

/** A PC-layout keyboard: Esc and F-keys, number row, Tab/Caps/Shift/Ctrl/Alt/❖, arrows. */
@SuppressLint("ViewConstructor")
class PcKeyboardView(
    context: Context,
    private val listener: Listener,
    /** In KDE Home the ⌨ key isn't needed (it switches the Android keyboard). */
    showSwitchKey: Boolean = true,
) : LinearLayout(context) {

    interface Listener {
        /** A key was typed with [mods] held; [caps] is Caps Lock. Repeating keys call this repeatedly. */
        fun onKey(key: PcKey, mods: Set<Mod>, caps: Boolean)
        fun onHide() {}
        fun onSwitchKeyboard() {}
    }

    private enum class Latch { OFF, ONCE, LOCKED }

    private val latches = HashMap<Mod, Latch>()
    private var caps = false
    private val keyViews = ArrayList<Pair<PcKey, TextView>>()
    private val handler = Handler(Looper.getMainLooper())

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        val pad = dp(3)
        setPadding(pad, pad, pad, pad)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rowHeight = dp(if (landscape) 34 else 42)
        for (row in rows(showSwitchKey)) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (key in row) line.addView(keyView(key), LayoutParams(0, rowHeight, key.width))
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        }
        refresh()
    }

    private fun mods(): Set<Mod> = latches.filterValues { it != Latch.OFF }.keys

    @SuppressLint("ClickableViewAccessibility")
    private fun keyView(key: PcKey): TextView {
        val v = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (key.label.length > 3) 10f else if (key.keysym in 0xffbe..0xffc9) 11f else 15f
            isSingleLine = true
            val m = dp(2)
            setPadding(0, 0, 0, 0)
            (layoutParams as? MarginLayoutParams)?.setMargins(m, m, m, m)
        }
        keyViews += key to v
        var repeated = false
        val repeater = object : Runnable {
            override fun run() {
                repeated = true
                type(key)
                handler.postDelayed(this, 45)
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    paint(key, v, pressed = true)
                    repeated = false
                    if (key.repeat) handler.postDelayed(repeater, 400)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(repeater)
                    view.isPressed = false
                    paint(key, v, pressed = false)
                    if (!repeated) tap(key)
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeater)
                    paint(key, v, pressed = false)
                }
            }
            true
        }
        return v
    }

    private fun tap(key: PcKey) {
        when {
            key.mod != null -> {
                latches[key.mod] = when (latches[key.mod] ?: Latch.OFF) {
                    Latch.OFF -> Latch.ONCE
                    Latch.ONCE -> Latch.LOCKED
                    Latch.LOCKED -> Latch.OFF
                }
                refresh()
            }
            key.action == PcKey.Action.CAPS -> {
                caps = !caps
                listener.onKey(key, emptySet(), caps)
                refresh()
            }
            key.action == PcKey.Action.HIDE -> listener.onHide()
            key.action == PcKey.Action.SWITCH_KEYBOARD -> listener.onSwitchKeyboard()
            else -> type(key)
        }
    }

    private fun type(key: PcKey) {
        listener.onKey(key, mods(), caps)
        // One-shot modifiers are released after the next key.
        var changed = false
        for ((m, l) in latches) if (l == Latch.ONCE) { latches[m] = Latch.OFF; changed = true }
        if (changed) refresh()
    }

    private fun refresh() {
        val shift = Mod.SHIFT in mods()
        for ((key, v) in keyViews) {
            v.text = when {
                key.isLetter -> if (shift != caps) key.label.uppercase() else key.label
                shift && key.shiftCh != null -> key.shiftCh.toString()
                else -> key.label
            }
            paint(key, v, pressed = false)
        }
    }

    private fun paint(key: PcKey, v: TextView, pressed: Boolean) {
        val latch = key.mod?.let { latches[it] } ?: Latch.OFF
        val color = when {
            pressed -> PRESSED
            latch == Latch.LOCKED -> LOCKED
            latch == Latch.ONCE || (key.action == PcKey.Action.CAPS && caps) -> ACTIVE
            key.ch != null -> KEY
            else -> SPECIAL
        }
        v.background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(color)
            setStroke(dp(2), BG)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(24, 26, 29)
        private val KEY = Color.rgb(55, 60, 66)
        private val SPECIAL = Color.rgb(40, 44, 49)
        private val PRESSED = Color.rgb(90, 98, 107)
        private val ACTIVE = Color.rgb(29, 153, 243)
        private val LOCKED = Color.rgb(218, 68, 83)

        private fun c(ch: Char, shift: Char?, code: Int) = PcKey(ch.toString(), ch.code, code, ch, shift)
        private fun letter(ch: Char) = PcKey(ch.toString(), ch.code, KeyEvent.KEYCODE_A + (ch - 'a'), ch, ch.uppercaseChar())
        private fun special(label: String, keysym: Int, code: Int, width: Float = 1f, repeat: Boolean = false) =
            PcKey(label, keysym, code, width = width, repeat = repeat)
        private fun mod(label: String, m: Mod, width: Float) = PcKey(label, m.keysym, m.keyCode, width = width, mod = m)

        fun rows(showSwitchKey: Boolean): List<List<PcKey>> {
            val f = (1..12).map { special("F$it", Keysyms.F1 + it - 1, KeyEvent.KEYCODE_F1 + it - 1, 0.9f) }
            val digits = "1234567890".zip("!@#$%^&*()").map { (d, s) ->
                c(d, s, if (d == '0') KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_1 + (d - '1'))
            }
            return listOf(
                listOf(special("Esc", Keysyms.ESCAPE, KeyEvent.KEYCODE_ESCAPE, 1.1f)) + f + listOf(
                    special("Home", Keysyms.HOME, KeyEvent.KEYCODE_MOVE_HOME, 1.1f),
                    special("End", Keysyms.END, KeyEvent.KEYCODE_MOVE_END, 1.1f),
                    special("Del", Keysyms.DELETE, KeyEvent.KEYCODE_FORWARD_DEL, 1.1f, repeat = true),
                ),
                listOf(c('`', '~', KeyEvent.KEYCODE_GRAVE)) + digits + listOf(
                    c('-', '_', KeyEvent.KEYCODE_MINUS),
                    c('=', '+', KeyEvent.KEYCODE_EQUALS),
                    special("⌫", Keysyms.BACKSPACE, KeyEvent.KEYCODE_DEL, 1.6f, repeat = true),
                ),
                listOf(special("Tab", Keysyms.TAB, KeyEvent.KEYCODE_TAB, 1.5f)) + "qwertyuiop".map(::letter) + listOf(
                    c('[', '{', KeyEvent.KEYCODE_LEFT_BRACKET),
                    c(']', '}', KeyEvent.KEYCODE_RIGHT_BRACKET),
                    c('\\', '|', KeyEvent.KEYCODE_BACKSLASH).let { PcKey(it.label, it.keysym, it.keyCode, it.ch, it.shiftCh, 1.1f) },
                ),
                listOf(PcKey("Caps", 0xffe5, KeyEvent.KEYCODE_CAPS_LOCK, width = 1.8f, action = PcKey.Action.CAPS)) +
                    "asdfghjkl".map(::letter) + listOf(
                    c(';', ':', KeyEvent.KEYCODE_SEMICOLON),
                    c('\'', '"', KeyEvent.KEYCODE_APOSTROPHE),
                    special("Enter", Keysyms.RETURN, KeyEvent.KEYCODE_ENTER, 1.8f),
                ),
                listOf(mod("Shift", Mod.SHIFT, 2.2f)) + "zxcvbnm".map(::letter) + listOf(
                    c(',', '<', KeyEvent.KEYCODE_COMMA),
                    c('.', '>', KeyEvent.KEYCODE_PERIOD),
                    c('/', '?', KeyEvent.KEYCODE_SLASH),
                    special("PgUp", Keysyms.PAGE_UP, KeyEvent.KEYCODE_PAGE_UP, 1.1f, repeat = true),
                    special("↑", Keysyms.UP, KeyEvent.KEYCODE_DPAD_UP, repeat = true),
                    special("PgDn", Keysyms.PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 1.1f, repeat = true),
                ),
                listOfNotNull(
                    mod("Ctrl", Mod.CTRL, 1.4f),
                    special("❖", Keysyms.SUPER, KeyEvent.KEYCODE_META_LEFT, 1.1f),
                    mod("Alt", Mod.ALT, 1.3f),
                    PcKey(" ", ' '.code, KeyEvent.KEYCODE_SPACE, ' ', ' ', width = 5.2f, repeat = true),
                    if (showSwitchKey) PcKey("⌨", 0, 0, width = 1.1f, action = PcKey.Action.SWITCH_KEYBOARD) else null,
                    PcKey("▼", 0, 0, width = 1.1f, action = PcKey.Action.HIDE),
                    special("←", Keysyms.LEFT, KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
                    special("↓", Keysyms.DOWN, KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
                    special("→", Keysyms.RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
                ),
            )
        }
    }
}
