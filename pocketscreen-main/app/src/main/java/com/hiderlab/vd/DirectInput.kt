package com.hiderlab.vd

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Where typed characters and key presses end up (the hidden display). */
interface InputSink {
    fun text(s: String)
    fun key(action: Int, code: Int, meta: Int)
}

/**
 * The surface that shows the hidden display. It also acts as a text editor for the phone's
 * keyboard, so whatever you type goes straight to the app on the hidden display, and it
 * forwards physical keyboard keys the same way.
 */
class InputSurfaceView(ctx: Context) : SurfaceView(ctx) {

    var sink: InputSink? = null

    /** When false the view tells Android it is not a text field, so no keyboard is requested for it. */
    var editorAllowed = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = editorAllowed

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain, suggestion-free text so the keyboard sends characters immediately.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return DirectConn(this, sink)
    }

    private fun forwardable(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_CAMERA -> false
        else -> true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = sink
        if (s != null && forwardable(keyCode)) {
            s.key(KeyEvent.ACTION_DOWN, keyCode, event.metaState)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val s = sink
        if (s != null && forwardable(keyCode)) {
            s.key(KeyEvent.ACTION_UP, keyCode, event.metaState)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

/**
 * Turns the keyboard's editing calls into plain key events. Composing text (swipe typing,
 * autocorrect) is applied as a diff against what was already sent: the changed tail is
 * deleted with Backspace and the new tail typed.
 */
class DirectConn(view: View, private val sink: InputSink?) : BaseInputConnection(view, false) {

    private var composing = ""

    private fun tap(code: Int) {
        sink?.key(KeyEvent.ACTION_DOWN, code, 0)
        sink?.key(KeyEvent.ACTION_UP, code, 0)
    }

    private fun setComposing(new: String) {
        val max = minOf(composing.length, new.length)
        var common = 0
        while (common < max && composing[common] == new[common]) common++
        repeat(composing.length - common) { tap(KeyEvent.KEYCODE_DEL) }
        if (new.length > common) sink?.text(new.substring(common))
        composing = new
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        setComposing(text?.toString() ?: "")
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        setComposing(text?.toString() ?: "")
        composing = ""
        return true
    }

    override fun finishComposingText(): Boolean {
        composing = ""
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        composing = ""
        repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
        repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP -> {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                    sink?.key(event.action, event.keyCode, event.metaState)
                }
            }
            KeyEvent.ACTION_MULTIPLE -> {
                val chars = event.characters
                if (!chars.isNullOrEmpty()) sink?.text(chars)
            }
        }
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        tap(KeyEvent.KEYCODE_ENTER)
        return true
    }
}
