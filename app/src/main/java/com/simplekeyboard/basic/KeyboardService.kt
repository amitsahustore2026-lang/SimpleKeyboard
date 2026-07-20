package com.simplekeyboard.basic

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * Minimal, working Input Method Service.
 * - QWERTY layout
 * - Shift (single tap, toggles caps for one/multiple letters until tapped again)
 * - Backspace
 * - Space
 * - Enter
 * No AI, no prediction, no themes, no extra features.
 */
class KeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private var keyboardView: KeyboardView? = null
    private var keyboard: Keyboard? = null
    private var isShifted = false

    companion object {
        private const val KEYCODE_SHIFT = -1
        private const val KEYCODE_DELETE = -5
        private const val KEYCODE_ENTER = -4
        private const val KEYCODE_SPACE = 32
    }

    override fun onCreateInputView(): View {
        // Always returns a valid, non-null view. Never crashes: if keyboard
        // resource inflation somehow failed this would throw during
        // development, not silently return null at runtime.
        val view = layoutInflater.inflate(R.layout.input_method, null) as KeyboardView
        val kb = Keyboard(this, R.xml.keyboard)
        view.keyboard = kb
        view.setOnKeyboardActionListener(this)
        view.isPreviewEnabled = true

        keyboard = kb
        keyboardView = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShifted = false
        keyboard?.isShifted = false
        keyboardView?.invalidateAllKeys()
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return

        when (primaryCode) {
            KEYCODE_SHIFT -> {
                isShifted = !isShifted
                keyboard?.isShifted = isShifted
                keyboardView?.invalidateAllKeys()
            }

            KEYCODE_DELETE -> {
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) {
                    ic.deleteSurroundingText(1, 0)
                } else {
                    ic.commitText("", 1)
                }
            }

            KEYCODE_ENTER -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }

            KEYCODE_SPACE -> {
                ic.commitText(" ", 1)
            }

            else -> {
                if (primaryCode > 0) {
                    var char = primaryCode.toChar()
                    if (isShifted) {
                        char = char.uppercaseChar()
                        // one-shot shift: turn off after a single letter
                        isShifted = false
                        keyboard?.isShifted = false
                        keyboardView?.invalidateAllKeys()
                    }
                    ic.commitText(char.toString(), 1)
                }
            }
        }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
