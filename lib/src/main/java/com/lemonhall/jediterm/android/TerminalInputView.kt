package com.lemonhall.jediterm.android

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * A custom View whose sole purpose is to receive soft-keyboard (IME) input and forward it to the terminal session.
 *
 * Uses BaseInputConnection(this, true) to enable an internal Editable buffer.
 * This is critical for CJK input methods: during composing (pinyin → candidate → commit),
 * the IME writes intermediate text into the Editable. On commitText / finishComposingText
 * we extract the full text from the Editable, send it to the terminal, then clear the buffer.
 * Without this, multi-byte characters like "我们" get partially lost or garbled.
 */
class TerminalInputView(context: Context) : View(context) {
    companion object {
        private const val TAG = "JeditermIme"
    }

    /** Called when IME commits final text (letters, paste, candidate commit, etc.). */
    var onCommitText: ((String) -> Unit)? = null

    /** Called when IME or hardware sends a key event (Enter/Backspace/arrows/etc.). */
    var onKeyEvent: ((KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        // true = 启用内部 Editable 缓冲区，中文 IME composing 文本会正确累积
        return object : BaseInputConnection(this, true) {

            /** 从 Editable 取出所有文本发送到终端，然后清空 */
            private fun sendEditableToTerminal() {
                val content = editable ?: return
                if (content.isEmpty()) return
                val str = content.toString()
                Log.d(TAG, "sendEditable len=${str.length} text='${str.take(32)}'")
                val normalized = str.replace("\r\n", "\r").replace("\n", "\r")
                onCommitText?.invoke(normalized)
                content.clear()
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendEditableToTerminal()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                sendEditableToTerminal()
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // 委托给 super，让 BaseInputConnection 管理 composing span
                return super.setComposingText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                Log.d(TAG, "deleteSurroundingText before=$beforeLength after=$afterLength")
                // 先让 super 处理内部 Editable
                val result = super.deleteSurroundingText(beforeLength, afterLength)
                // 如果 Editable 里还有 composing 文本，不发删除键
                val content = editable
                if (content != null && content.isNotEmpty()) return result
                // Editable 为空，说明是在已提交文本上删除，发退格键
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        onKeyEvent?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    }
                }
                if (afterLength > 0) {
                    repeat(afterLength) {
                        onKeyEvent?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                    }
                }
                return true
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
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val handled = onKeyEvent?.invoke(event) ?: false
            if (handled) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Consume ACTION_UP to prevent default handling.
        return true
    }
}