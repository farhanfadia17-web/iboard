package com.example.iboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(t: String, sp: Float = 16f) = TextView(this).apply {
            text = t
            textSize = sp
            setPadding(0, pad / 2, 0, pad / 2)
        }
        col.addView(label("iBoard", 28f))
        col.addView(label("1. Aktifkan iBoard di pengaturan keyboard."))
        col.addView(Button(this).apply {
            text = "Buka pengaturan keyboard"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(label("2. Pilih iBoard sebagai keyboard aktif."))
        col.addView(Button(this).apply {
            text = "Pilih keyboard"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        col.addView(label("3. Coba ketik di sini:"))
        col.addView(EditText(this).apply { hint = "Ketik di sini" })
        setContentView(col)
    }
}
