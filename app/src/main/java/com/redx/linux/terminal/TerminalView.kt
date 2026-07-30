package com.redx.linux.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.max

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 36f
    }

    private val bgPaint = Paint()

    private var charWidth = 0f
    private var charHeight = 0f
    private var charAscent = 0f

    private var emulator: TerminalEmulator? = null
    private var inputCallback: ((String) -> Unit)? = null
    private var exitCallback: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var cursorVisible = true
    private val cursorBlink = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            handler.postDelayed(this, 500)
        }
    }

    // Font size control
    private var fontSize = 36f
        set(value) {
            field = value.coerceIn(20f, 72f)
            paint.textSize = field
            measureChar()
            notifyResize()
            invalidate()
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureChar()
        handler.postDelayed(cursorBlink, 500)
    }

    private fun measureChar() {
        val fm = paint.fontMetrics
        charHeight = fm.descent - fm.ascent
        charAscent = -fm.ascent
        charWidth = paint.measureText("M")
    }

    fun setInputCallback(cb: (String) -> Unit) { inputCallback = cb }
    fun setSessionExitCallback(cb: () -> Unit) { exitCallback = cb }

    fun receiveOutput(data: ByteArray, length: Int = data.size) {
        emulator?.process(data, length)
        post { invalidate() }
    }

    fun receiveOutput(text: String) {
        receiveOutput(text.toByteArray(Charsets.UTF_8))
    }

    fun setEmulator(e: TerminalEmulator) {
        emulator = e
        invalidate()
    }

    fun getEmulator() = emulator

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        notifyResize()
    }

    private fun notifyResize() {
        if (charWidth == 0f || charHeight == 0f) return
        val newCols = max(1, (width / charWidth).toInt())
        val newRows = max(1, (height / charHeight).toInt())
        val em = emulator
        if (em == null) {
            emulator = TerminalEmulator(newRows, newCols)
        } else if (em.rows != newRows || em.cols != newCols) {
            em.resize(newRows, newCols)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: return

        synchronized(em) {
            for (row in 0 until em.rows) {
                val y = row * charHeight
                for (col in 0 until em.cols) {
                    val cell = em.screen[row][col]
                    val x = col * charWidth

                    // Background
                    bgPaint.color = cell.bg
                    canvas.drawRect(x, y, x + charWidth, y + charHeight, bgPaint)

                    // Character
                    if (cell.char != ' ') {
                        paint.color = cell.fg
                        paint.isFakeBoldText = cell.bold
                        canvas.drawText(cell.char.toString(), x, y + charAscent, paint)
                    }

                    // Underline
                    if (cell.underline) {
                        paint.color = cell.fg
                        canvas.drawLine(x, y + charAscent + 2, x + charWidth, y + charAscent + 2, paint)
                    }
                }
            }

            // Cursor
            if (cursorVisible && isFocused) {
                val cx = em.curCol * charWidth
                val cy = em.curRow * charHeight
                bgPaint.color = TerminalEmulator.CURSOR_COLOR
                canvas.drawRect(cx, cy, cx + charWidth, cy + charHeight, bgPaint)
                val cell = em.screen[em.curRow][em.curCol]
                if (cell.char != ' ') {
                    paint.color = TerminalEmulator.DEFAULT_BG
                    canvas.drawText(cell.char.toString(), cx, cy + charAscent, paint)
                }
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection(this)
    }

    override fun onCheckIsTextEditor() = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val str = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            else -> null
        }
        if (str != null) {
            inputCallback?.invoke(str)
            return true
        }
        val ch = event.unicodeChar
        if (ch > 0) {
            // Ctrl+key
            val ctrl = event.isCtrlPressed || (event.metaState and KeyEvent.META_CTRL_ON != 0)
            if (ctrl && ch in 64..95) {
                inputCallback?.invoke((ch - 64).toChar().toString())
                return true
            }
            inputCallback?.invoke(ch.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun increaseFontSize() { fontSize += 2 }
    fun decreaseFontSize() { fontSize -= 2 }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(cursorBlink)
        super.onDetachedFromWindow()
    }

    inner class TerminalInputConnection(view: View) : BaseInputConnection(view, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.toString()?.let { inputCallback?.invoke(it) }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { inputCallback?.invoke("\u007f") }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                return this@TerminalView.onKeyDown(event.keyCode, event)
            }
            return super.sendKeyEvent(event)
        }
    }
}
