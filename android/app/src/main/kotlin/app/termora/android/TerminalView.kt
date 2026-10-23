package app.termora.android

import android.content.Context
import android.graphics.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import app.termora.*
import app.termora.android.core.MobileSettings
import app.termora.terminal.*
import app.termora.terminal.panel.TerminalWriter
import kotlin.math.*

/** ANSI state lives in the desktop parser; only drawing and Android input live here. */
class TerminalView(
    context: Context,
    val terminal: VisualTerminal,
    val send: (ByteArray) -> Unit,
    val resized: (Int, Int) -> Unit,
    settings: MobileSettings,
    private val encoding: java.nio.charset.Charset = Charsets.UTF_8,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = settings.fontSize * resources.displayMetrics.scaledDensity
        }
    private val cell =
        paint.measureText("M") + settings.letterSpacing * resources.displayMetrics.density
    private val line = ceil(paint.fontSpacing)
    private val baseline = -paint.fontMetrics.ascent
    private var touchY = 0f
    var ctrl = false
    var alt = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(22, 25, 34))
        terminal
            .getTerminalModel()
            .setData(
                DataKey.TerminalWriter,
                object : TerminalWriter {
                    override fun write(request: TerminalWriter.WriteRequest) = send(request.buffer)

                    override fun resize(rows: Int, cols: Int) = resized(rows, cols)

                    override fun getCharset() = encoding
                },
            )
        setOnLongClickListener {
            (context as MainActivity).terminalSelection(this)
            true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val rows = max(5, (h / line).toInt())
        val cols = max(10, (w / cell).toInt())
        terminal.getTerminalModel().resize(rows, cols)
        resized(rows, cols)
    }

    override fun onDraw(canvas: Canvas) {
        val model = terminal.getTerminalModel()
        val palette = model.getColorPalette()
        val doc = terminal.getDocument()
        val scroll = terminal.getScrollingModel().getVerticalScrollOffset()
        for (row in 1..model.getRows()) {
            if (row + scroll > doc.getLineCount()) break
            var col = 0
            for ((text, style, span) in doc.getLine(row + scroll).characters()) {
                if (col >= model.getCols()) break
                var fg =
                    if (style.foreground == 0) 0xffe1e5ed.toInt()
                    else
                        0xff000000.toInt() or
                            (if (style.foreground <= 16) palette.getXTerm256Color(style.foreground)
                            else style.foreground)
                var bg =
                    if (style.background == 0) 0xff161922.toInt()
                    else
                        0xff000000.toInt() or
                            (if (style.background <= 16) palette.getXTerm256Color(style.background)
                            else style.background)
                if (style.inverse) {
                    val c = fg
                    fg = bg
                    bg = c
                }
                val x = col * cell
                val y = (row - 1) * line
                paint.color = bg
                canvas.drawRect(x, y, x + span * cell, y + line, paint)
                paint.color = fg
                paint.isFakeBoldText = style.bold
                paint.textSkewX = if (style.italic) -0.2f else 0f
                paint.alpha = if (style.dim) 150 else 255
                canvas.save()
                canvas.clipRect(x, y, x + span * cell, y + line)
                canvas.drawText(text, x, y + baseline, paint)
                canvas.restore()
                if (style.underline)
                    canvas.drawLine(x, y + line - 2, x + span * cell, y + line - 2, paint)
                if (style.lineThrough)
                    canvas.drawLine(x, y + line / 2, x + span * cell, y + line / 2, paint)
                col += span
            }
        }
        if (model.getData(DataKey.ShowCursor, true) && terminal.getScrollingModel().isStick()) {
            val p = terminal.getCursorModel().getPosition()
            paint.alpha = 180
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            canvas.drawRect((p.x - 1) * cell, (p.y - 1) * line, p.x * cell, p.y * line, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }
    }

    fun key(code: Int) {
        var modifiers = 0
        if (ctrl) modifiers = modifiers or TerminalEvent.CTRL_MASK
        if (alt) modifiers = modifiers or TerminalEvent.ALT_MASK
        val encoded = terminal.getKeyEncoder().encode(TerminalKeyEvent(code, modifiers))
        if (encoded.isNotEmpty()) send(encoded.toByteArray(encoding))
        ctrl = false
        alt = false
    }

    fun text(value: String) {
        val actual =
            if (ctrl && value.length == 1) {
                val c = value.uppercase()[0]
                if (c.code in 64..95) (c.code and 31).toChar().toString() else value
            } else value
        send(((if (alt) "\u001b" else "") + actual).toByteArray(encoding))
        ctrl = false
        alt = false
    }

    fun paste(value: String) {
        val bracket = terminal.getTerminalModel().getData(DataKey.BracketedPasteMode, false)
        text(if (bracket) "\u001b[200~$value\u001b[201~" else value)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        info.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                this@TerminalView.text(text?.toString().orEmpty())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(1024)) { key(KeyCodes.VK_BACK_SPACE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent) = dispatchKeyEvent(event)

            override fun performEditorAction(actionCode: Int): Boolean {
                key(KeyCodes.VK_ENTER)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val vk =
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> KeyCodes.VK_BACK_SPACE
                KeyEvent.KEYCODE_FORWARD_DEL -> KeyCodes.VK_DELETE
                KeyEvent.KEYCODE_ENTER -> KeyCodes.VK_ENTER
                KeyEvent.KEYCODE_TAB -> KeyCodes.VK_TAB
                KeyEvent.KEYCODE_ESCAPE -> KeyCodes.VK_ESCAPE
                KeyEvent.KEYCODE_DPAD_UP -> KeyCodes.VK_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyCodes.VK_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> KeyCodes.VK_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> KeyCodes.VK_RIGHT
                KeyEvent.KEYCODE_MOVE_HOME -> KeyCodes.VK_HOME
                KeyEvent.KEYCODE_MOVE_END -> KeyCodes.VK_END
                KeyEvent.KEYCODE_PAGE_UP -> KeyCodes.VK_PAGE_UP
                KeyEvent.KEYCODE_PAGE_DOWN -> KeyCodes.VK_PAGE_DOWN
                else -> null
            }
        ctrl = event.isCtrlPressed
        alt = event.isAltPressed
        if (vk != null) {
            key(vk)
            return true
        }
        if (event.unicodeChar > 0) {
            text(String(Character.toChars(event.unicodeChar)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchY = event.y
                requestFocus()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = ((touchY - event.y) / line).toInt()
                if (delta != 0) {
                    val s = terminal.getScrollingModel()
                    s.scrollTo(s.getVerticalScrollOffset() + delta)
                    touchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
