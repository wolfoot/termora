package app.termora.android

import android.content.Context
import android.graphics.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import kotlin.math.*

class DesktopView(context: Context, private val session: MobileSession) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var panning = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var ctrl = false
    private var alt = false
    private val pinch =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val old = scale
                    scale = (scale * detector.scaleFactor).coerceIn(.1f, 8f)
                    offsetX = detector.focusX - (detector.focusX - offsetX) * scale / old
                    offsetY = detector.focusY - (detector.focusY - offsetY) * scale / old
                    invalidate()
                    return true
                }
            },
        )
    private val gesture =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onLongPress(e: MotionEvent) {
                    longPressed = true
                    val (x, y) = point(e)
                    session.desktopPointer(x, y, true, 2)
                    session.desktopPointer(x, y, false, 2)
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val (x, y) = point(e)
                    session.desktopPointer(x, y, true)
                    session.desktopPointer(x, y, false)
                    performClick()
                    return true
                }
            },
        )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    private fun point(e: MotionEvent) =
        ((e.x - offsetX) / scale.coerceAtLeast(.1f))
            .toInt()
            .coerceIn(0, (session.bitmap?.width ?: 1) - 1) to
            ((e.y - offsetY) / scale.coerceAtLeast(.1f))
                .toInt()
                .coerceIn(0, (session.bitmap?.height ?: 1) - 1)

    override fun onDraw(canvas: Canvas) {
        session.drawFrame { b ->
            if (scale == 0f) {
                scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
                offsetX = (width - b.width * scale) / 2
                offsetY = (height - b.height * scale) / 2
            }
            canvas.drawBitmap(
                b,
                null,
                RectF(offsetX, offsetY, offsetX + b.width * scale, offsetY + b.height * scale),
                paint,
            )
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        requestFocus()
        pinch.onTouchEvent(e)
        if (e.pointerCount > 1) {
            if (dragging) {
                val (x, y) = point(e)
                session.desktopPointer(x, y, false)
                dragging = false
            }
            panning = true
            val x = (e.getX(0) + e.getX(1)) / 2
            val y = (e.getY(0) + e.getY(1)) / 2
            if (e.actionMasked == MotionEvent.ACTION_MOVE && !pinch.isInProgress) {
                offsetX += x - previousX
                offsetY += y - previousY
                invalidate()
            }
            previousX = x
            previousY = y
            return true
        }
        if (panning) {
            if (e.actionMasked == MotionEvent.ACTION_UP) panning = false
            return true
        }
        gesture.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.x
            downY = e.y
            longPressed = false
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE && !longPressed) {
            val (x, y) = point(e)
            if (
                !dragging &&
                    hypot(e.x - downX, e.y - downY) > ViewConfiguration.get(context).scaledTouchSlop
            ) {
                dragging = true
                session.desktopPointer(x, y, true)
            }
            session.desktopMove(x, y, if (dragging) 1 else 0)
        }
        if (
            e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (dragging) {
                val (x, y) = point(e)
                session.desktopPointer(x, y, false)
                dragging = false
            }
        }
        return true
    }

    fun keyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                sendText(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(1024)) { special(0xff08, 0x08) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent) = dispatchKeyEvent(event)
        }
    }

    fun special(vnc: Int, rdp: Int) {
        val code = if (session.vnc != null) vnc else rdp
        session.desktopKey(code, true)
        session.desktopKey(code, false)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT ->
                session.desktopKey(if (session.vnc != null) 0xffe1 else 0x10, true)
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT -> {
                ctrl = true
                modifier(true, true)
            }
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT -> {
                alt = true
                modifier(false, true)
            }
            KeyEvent.KEYCODE_ENTER -> special(0xff0d, 0x0d)
            KeyEvent.KEYCODE_DEL -> special(0xff08, 0x08)
            KeyEvent.KEYCODE_TAB -> special(0xff09, 0x09)
            KeyEvent.KEYCODE_ESCAPE -> special(0xff1b, 0x1b)
            KeyEvent.KEYCODE_DPAD_UP -> special(0xff52, 0x26)
            KeyEvent.KEYCODE_DPAD_DOWN -> special(0xff54, 0x28)
            KeyEvent.KEYCODE_DPAD_LEFT -> special(0xff51, 0x25)
            KeyEvent.KEYCODE_DPAD_RIGHT -> special(0xff53, 0x27)
            else -> {
                if (e.unicodeChar <= 0) return super.onKeyDown(code, e)
                sendText(String(Character.toChars(e.unicodeChar)))
            }
        }
        return true
    }

    private fun modifier(control: Boolean, down: Boolean) {
        session.desktopKey(
            if (session.vnc != null) {
                if (control) 0xffe3 else 0xffe9
            } else {
                if (control) 0x11 else 0x12
            },
            down,
        )
    }

    fun toggleModifier(control: Boolean) {
        if (control) {
            ctrl = !ctrl
            modifier(true, ctrl)
        } else {
            alt = !alt
            modifier(false, alt)
        }
    }

    private fun sendText(value: String) {
        if (
            session.vnc == null &&
                (ctrl || alt) &&
                value.length == 1 &&
                (value[0].uppercaseChar() in 'A'..'Z' || value[0] in '0'..'9')
        ) {
            val vk = value.uppercase()[0].code
            session.desktopKey(vk, true)
            session.desktopKey(vk, false)
        } else session.desktopText(value)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            session.desktopKey(if (session.vnc != null) 0xffe1 else 0x10, false)
            return true
        }
        if (code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT) {
            ctrl = false
            modifier(true, false)
            return true
        }
        if (code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT) {
            alt = false
            modifier(false, false)
            return true
        }
        return super.onKeyUp(code, e)
    }

    override fun onDetachedFromWindow() {
        if (ctrl) modifier(true, false)
        if (alt) modifier(false, false)
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
