package com.limosa.kdehome

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * The PC keyboard as an Android keyboard, for every app. Enable it in
 * Settings › System › Keyboard › On-screen keyboard › KDE Home PC Keyboard.
 */
class PcKeyboardService : InputMethodService(), PcKeyboardView.Listener {

    override fun onCreateInputView(): View = PcKeyboardView(this, this, showSwitchKey = true)

    override fun onEvaluateFullscreenMode() = false

    override fun onKey(key: PcKey, mods: Set<Mod>, caps: Boolean) {
        val ic = currentInputConnection ?: return
        if (key.action == PcKey.Action.CAPS) return
        val shortcut = Mod.CTRL in mods || Mod.ALT in mods
        val ch = key.ch
        if (ch != null && !shortcut) {
            val shifted = Mod.SHIFT in mods
            val out = when {
                key.isLetter -> if (shifted != caps) ch.uppercaseChar() else ch
                shifted -> key.shiftCh ?: ch
                else -> ch
            }
            ic.commitText(out.toString(), 1)
            return
        }
        if (key.keyCode == KeyEvent.KEYCODE_ENTER && !shortcut) {
            val info = currentInputEditorInfo
            val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
            val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val multiLine = (info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
                return
            }
        }
        if (key.keyCode == 0) return
        var meta = 0
        for (m in mods) meta = meta or m.meta
        if (caps) meta = meta or KeyEvent.META_CAPS_LOCK_ON
        val now = SystemClock.uptimeMillis()
        // Modifiers go down and up around the key, like a real keyboard (KDE Home and terminals need this).
        for (m in mods) ic.sendKeyEvent(event(now, KeyEvent.ACTION_DOWN, m.keyCode, meta))
        ic.sendKeyEvent(event(now, KeyEvent.ACTION_DOWN, key.keyCode, meta))
        ic.sendKeyEvent(event(now, KeyEvent.ACTION_UP, key.keyCode, meta))
        for (m in mods) ic.sendKeyEvent(event(now, KeyEvent.ACTION_UP, m.keyCode, 0))
    }

    private fun event(time: Long, action: Int, code: Int, meta: Int) = KeyEvent(
        time, time, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
    )

    override fun onHide() = requestHideSelf(0)

    override fun onSwitchKeyboard() {
        // Back to the previous Android keyboard, or the picker if there is none.
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            if (!switchToPreviousInputMethod()) imm.showInputMethodPicker()
        } else {
            imm.showInputMethodPicker()
        }
    }
}
