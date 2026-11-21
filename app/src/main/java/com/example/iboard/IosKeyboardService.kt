package com.example.iboard

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

class IosKeyboardService : InputMethodService() {

    private enum class Page { LETTERS, SYMBOLS, EMOJI }
    private enum class Style { LETTER, FUNCTION, ACCENT }

    private var page = Page.LETTERS
    private var shift = false
    private var capsLock = false
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var imeAction = EditorInfo.IME_ACTION_NONE
    private var noEnterAction = false

    private var root: LinearLayout? = null
    private var popup: PopupWindow? = null
    private var shiftView: TextView? = null
    private val letterViews = mutableListOf<Pair<String, TextView>>()
    private val handler = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null

    // warna (diisi di render sesuai mode terang/gelap sistem)
    private var cBg = 0
    private var cKey = 0
    private var cKeyPressed = 0
    private var cFn = 0
    private var cFnPressed = 0
    private var cShadow = 0
    private var cText = 0
    private val cAccent = Color.parseColor("#0A6CFF")
    private val cAccentPressed = Color.parseColor("#0856CC")

    private val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
        "🙂", "😉", "😊", "😍", "🥰", "😘", "😋", "😎",
        "🤔", "😴", "😭", "😡", "🥺", "😱", "🙄", "😬",
        "👍", "👎", "👏", "🙏", "💪", "👋", "🤝", "💖",
        "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "🔥",
        "✨", "🎉", "⭐", "🌈", "☕", "🍕", "🍔", "🍜",
        "⚽", "🎮", "📱", "💻", "🚗", "🏠", "🌙", "🐱",
        "🐶", "🌹"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root = r
        render()
        return r
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        page = Page.LETTERS
        capsLock = false
        val opts = info?.imeOptions ?: 0
        imeAction = opts and EditorInfo.IME_MASK_ACTION
        noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        shift = info != null && info.inputType != 0 &&
            (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        hidePreview()
        stopRepeat()
    }

    // ---------- render ----------

    private fun render() {
        val r = root ?: return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            cBg = Color.parseColor("#1F1F22")
            cKey = Color.parseColor("#5E5E63")
            cKeyPressed = Color.parseColor("#7A7A80")
            cFn = Color.parseColor("#3A3A3E")
            cFnPressed = Color.parseColor("#5E5E63")
            cShadow = Color.parseColor("#0E0E0F")
            cText = Color.WHITE
        } else {
            cBg = Color.parseColor("#D2D4DA")
            cKey = Color.WHITE
            cKeyPressed = Color.parseColor("#B9BCC4")
            cFn = Color.parseColor("#ADB1BB")
            cFnPressed = Color.WHITE
            cShadow = Color.parseColor("#898A8D")
            cText = Color.BLACK
        }
        hidePreview()
        stopRepeat()
        letterViews.clear()
        shiftView = null
        r.removeAllViews()
        r.setBackgroundColor(cBg)
        r.setPadding(dp(3), dp(6), dp(3), dp(8))
        when (page) {
            Page.LETTERS -> buildLetters(r)
            Page.SYMBOLS -> buildSymbols(r)
            Page.EMOJI -> buildEmoji(r)
        }
    }

    private fun goTo(p: Page) {
        page = p
        handler.post { render() }
    }

    // ---------- halaman ----------

    private fun buildLetters(r: LinearLayout) {
        val r1 = row(r)
        "qwertyuiop".forEach { letter(r1, it.toString()) }
        val r2 = row(r, 18)
        "asdfghjkl".forEach { letter(r2, it.toString()) }
        val r3 = row(r)
        shiftView = key(r3, "⇧", 1.5f, Style.FUNCTION, 20f) { onShift() }
        "zxcvbnm".forEach { letter(r3, it.toString()) }
        key(r3, "⌫", 1.5f, Style.FUNCTION, 20f, repeat = true) { backspace() }
        updateShift()
        bottomRow(r, "123") { goTo(Page.SYMBOLS) }
    }

    private fun buildSymbols(r: LinearLayout) {
        val r1 = row(r)
        "1234567890".forEach { sym(r1, it.toString()) }
        val r2 = row(r)
        "-/:;()\$&@\"".forEach { sym(r2, it.toString()) }
        val r3 = row(r)
        spacer(r3, 1.5f)
        ".,?!'".forEach { sym(r3, it.toString(), 1.3f) }
        key(r3, "⌫", 1.5f, Style.FUNCTION, 20f, repeat = true) { backspace() }
        bottomRow(r, "ABC") { goTo(Page.LETTERS) }
    }

    private fun buildEmoji(r: LinearLayout) {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col)
        emojis.chunked(8).forEach { chunk ->
            val rw = row(col)
            chunk.forEach { e ->
                val tv = TextView(this).apply {
                    text = e
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        commit(e)
                    }
                }
                rw.addView(tv, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            for (i in 0 until (8 - chunk.size)) {
                rw.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }
        r.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(224)))
        val b = row(r)
        key(b, "ABC", 1.4f, Style.FUNCTION, 16f) { goTo(Page.LETTERS) }
        key(b, "spasi", 6f, Style.LETTER, 15f) { onSpace() }
        key(b, "⌫", 1.4f, Style.FUNCTION, 20f, repeat = true) { backspace() }
    }

    private fun bottomRow(r: LinearLayout, leftLabel: String, onLeft: () -> Unit) {
        val b = row(r)
        key(b, leftLabel, 1.4f, Style.FUNCTION, 16f) { onLeft() }
        key(b, "☺", 1f, Style.FUNCTION, 20f) { goTo(Page.EMOJI) }
        key(b, "🌐", 1f, Style.FUNCTION, 18f) { switchKeyboard() }
        key(b, "spasi", 4f, Style.LETTER, 15f) { onSpace() }
        key(b, returnLabel(), 1.8f, if (hasAction()) Style.ACCENT else Style.FUNCTION, 16f) { onReturn() }
    }

    // ---------- pembuat view ----------

    private fun row(parent: LinearLayout, sidePad: Int = 0): LinearLayout {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(sidePad), 0, dp(sidePad), 0)
        }
        parent.addView(
            l,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return l
    }

    private fun spacer(p: LinearLayout, w: Float) {
        p.addView(View(this), keyParams(w))
    }

    private fun keyParams(weight: Float) =
        LinearLayout.LayoutParams(0, dp(46), weight).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) }

    private fun letter(parent: LinearLayout, c: String) {
        val tv = key(parent, c, 1f, Style.LETTER, 22f, preview = true) { typeLetter(c) }
        letterViews.add(c to tv)
    }

    private fun sym(p: LinearLayout, c: String, w: Float = 1f) {
        key(p, c, w, Style.LETTER, 22f, preview = true) { commit(c) }
    }

    private fun key(
        parent: LinearLayout,
        label: String,
        weight: Float,
        style: Style = Style.LETTER,
        textSp: Float = 22f,
        preview: Boolean = false,
        repeat: Boolean = false,
        onTap: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.textSize = textSp
        tv.setTextColor(if (style == Style.ACCENT) Color.WHITE else cText)
        tv.background = bgFor(style)
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (preview) showPreview(v, (v as TextView).text.toString())
                    if (repeat) {
                        onTap()
                        startRepeat(onTap)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    hidePreview()
                    if (repeat) stopRepeat() else onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    hidePreview()
                    stopRepeat()
                }
            }
            true
        }
        parent.addView(tv, keyParams(weight))
        return tv
    }

    private fun bgFor(style: Style): Drawable = when (style) {
        Style.LETTER -> keyBg(cKey, cKeyPressed)
        Style.FUNCTION -> keyBg(cFn, cFnPressed)
        Style.ACCENT -> keyBg(cAccent, cAccentPressed)
    }

    private fun keyBg(fill: Int, pressed: Int): Drawable {
        fun shape(c: Int) = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(c)
        }
        fun layer(c: Int) = LayerDrawable(arrayOf<Drawable>(shape(cShadow), shape(c))).apply {
            setLayerInset(1, 0, 0, 0, dp(1))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layer(pressed))
            addState(intArrayOf(), layer(fill))
        }
    }

    // ---------- popup tombol (efek zoom ala iOS) ----------

    private fun showPreview(v: View, label: String) {
        hidePreview()
        val tv = TextView(this).apply {
            text = label
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(cText)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(cKey)
            }
        }
        val w = v.width + dp(18)
        val h = v.height + dp(22)
        val p = PopupWindow(tv, w, h, false)
        p.isTouchable = false
        p.isClippingEnabled = false
        p.elevation = dp(6).toFloat()
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        p.showAtLocation(v, Gravity.NO_GRAVITY, loc[0] - dp(9), loc[1] - h)
        popup = p
    }

    private fun hidePreview() {
        popup?.dismiss()
        popup = null
    }

    // ---------- hapus berulang ----------

    private fun startRepeat(action: () -> Unit) {
        stopRepeat()
        val rn = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 50)
            }
        }
        repeater = rn
        handler.postDelayed(rn, 400)
    }

    private fun stopRepeat() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }

    // ---------- aksi ketik ----------

    private fun commit(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    private fun typeLetter(c: String) {
        commit(if (shift || capsLock) c.uppercase() else c)
        if (shift && !capsLock) {
            shift = false
            updateShift()
        }
    }

    private fun backspace() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        if (capsLock) {
            capsLock = false
            shift = false
        } else if (now - lastShiftTap < 350) {
            capsLock = true
            shift = true
        } else {
            shift = !shift
        }
        lastShiftTap = now
        updateShift()
    }

    private fun updateShift() {
        val on = shift || capsLock
        letterViews.forEach { (c, tv) -> tv.text = if (on) c.uppercase() else c }
        shiftView?.let {
            it.text = if (capsLock) "⇪" else "⇧"
            it.background = if (on) keyBg(cKey, cKeyPressed) else bgFor(Style.FUNCTION)
            it.setTextColor(cText)
        }
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (now - lastSpaceTap < 400 && before.length == 2 &&
            before[1] == ' ' && before[0] != ' ' && before[0] != '.'
        ) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            shift = true
            updateShift()
        } else {
            ic.commitText(" ", 1)
        }
        lastSpaceTap = now
    }

    private fun hasAction(): Boolean = !noEnterAction && (
        imeAction == EditorInfo.IME_ACTION_GO ||
            imeAction == EditorInfo.IME_ACTION_SEARCH ||
            imeAction == EditorInfo.IME_ACTION_SEND ||
            imeAction == EditorInfo.IME_ACTION_NEXT ||
            imeAction == EditorInfo.IME_ACTION_DONE
        )

    private fun returnLabel(): String {
        if (!hasAction()) return "return"
        return when (imeAction) {
            EditorInfo.IME_ACTION_GO -> "buka"
            EditorInfo.IME_ACTION_SEARCH -> "cari"
            EditorInfo.IME_ACTION_SEND -> "kirim"
            EditorInfo.IME_ACTION_NEXT -> "lanjut"
            else -> "selesai"
        }
    }

    private fun onReturn() {
        val ic = currentInputConnection ?: return
        if (hasAction()) ic.performEditorAction(imeAction)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun switchKeyboard() {
        val ok = if (Build.VERSION.SDK_INT >= 28) switchToNextInputMethod(false) else false
        if (!ok) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }
}
