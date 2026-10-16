package com.webrtc_phone.android.ui.stream

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * 本地输入法 → 云手机的文字桥。
 * 包装 InputConnection：只在 IME 落词（commitText）时发送，拼音组合中间态不发送；
 * 退格/回车转为远端按键。本地编辑框内容随时清空，保证每次 commit 都是增量。
 */
class RemoteInputView(context: Context) : EditText(context) {

    interface Listener {
        fun onCommit(text: String)
        fun onKey(keycode: Int)
    }

    var listener: Listener? = null

    init {
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        inputType = InputType.TYPE_CLASS_TEXT
        hint = "在此输入，文字将实时发送到云手机"
        background = null
    }

    /** 物理键盘 / 原始按键事件（不经过 InputConnection）：可打印字符按文本发送，其余按键直通 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ch = event.unicodeChar
            when {
                event.keyCode == KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
                ch != 0 && !event.isCtrlPressed && !Character.isISOControl(ch) -> {
                    listener?.onCommit(String(Character.toChars(ch)))
                    return true
                }
                event.keyCode in FORWARD_KEYS -> {
                    listener?.onKey(event.keyCode)
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP && event.keyCode != KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    listener?.onCommit(text.toString())
                    post { this@RemoteInputView.text?.clear() }
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // 本地编辑框已被清空时，IME 的删除实际上作用于远端
                repeat(beforeLength.coerceAtMost(64)) { listener?.onKey(KeyEvent.KEYCODE_DEL) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        in FORWARD_KEYS -> {
                            listener?.onKey(event.keyCode)
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                listener?.onKey(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    companion object {
        private val FORWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        )
    }
}
