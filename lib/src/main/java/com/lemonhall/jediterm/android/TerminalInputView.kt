package com.lemonhall.jediterm.android

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class TerminalInputView(context: Context) : View(context) {

    companion object {
        private const val TAG = "JeditermIme"
    }

    var onCommitText: ((String) -> Unit)? = null
    var onKeyEvent: ((KeyEvent) -> Boolean)? = null

    private var composingActive: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE

        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        return object : BaseInputConnection(this, false) {

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingActive = false
                if (!text.isNullOrEmpty()) {
                    val str = text.toString()
                    Log.d(TAG, "commitText len=${str.length} text='${str.take(32)}'")
                    val normalized = str.replace("\r\n", "\r").replace("\n", "\r")
                    onCommitText?.invoke(normalized)
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingActive = !text.isNullOrEmpty()
                Log.d(TAG, "setComposingText active=$composingActive len=${text?.length ?: 0}")
                return true
            }

            override fun finishComposingText(): Boolean {
                composingActive = false
                Log.d(TAG, "finishComposingText")
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                Log.d(TAG, "deleteSurroundingText before=$beforeLength after=$afterLength composing=$composingActive")
                if (composingActive) return true
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        onKeyEvent?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    }
                    return true
                }
                if (afterLength > 0) {
                    repeat(afterLength) {
                        onKeyEvent?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                    }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                if (actionCode == EditorInfo.IME_ACTION_DONE ||
                    actionCode == EditorInfo.IME_ACTION_GO ||
                    actionCode == EditorInfo.IME_ACTION_SEND
                ) {
                    onCommitText?.invoke("\r")
                    return true
                }
                return super.performEditorAction(actionCode)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val handled = onKeyEvent?.invoke(event) ?: false
                    if (handled) return true

                    // Fallback: some IMEs send printable chars as key events
                    // instead of commitText (especially in VISIBLE_PASSWORD mode).
                    // mapKeyEventToTerminalBytes returns null for these, so we
                    // need to extract the unicode char and send it ourselves.
                    val unicodeChar = event.unicodeChar
                    if (unicodeChar != 0) {
                        val ch = unicodeChar.toChar()
                        Log.d(TAG, "sendKeyEvent fallback char='$ch' code=0x${unicodeChar.toString(16)}")
                        onCommitText?.invoke(ch.toString())
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val handled = onKeyEvent?.invoke(event) ?: false
            if (handled) return true

            val unicodeChar = event.unicodeChar
            if (unicodeChar != 0) {
                Log.d(TAG, "onKeyDown fallback char='${unicodeChar.toChar()}' code=0x${unicodeChar.toString(16)}")
                onCommitText?.invoke(unicodeChar.toChar().toString())
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return true
    }
}