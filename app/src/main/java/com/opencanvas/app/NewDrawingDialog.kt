package com.opencanvas.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "New drawing": name, a preset or custom size, and a note on how many layers it allows. */
object NewDrawingDialog {
    fun show(activity: Activity, onCreate: (name: String, w: Int, h: Int, dpi: Int) -> Unit) {
        val dp = { v: Int -> Ui.dp(activity, v) }

        var w = CanvasSizes.presets[0].width
        var h = CanvasSizes.presets[0].height
        var dpi = CanvasSizes.presets[0].dpi
        var selected = 0   // index into presets, or -1 for custom
        var updating = false

        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), 0)

        val nameBox = EditText(activity)
        nameBox.setSingleLine()
        nameBox.hint = "Name"
        nameBox.setText("Drawing " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date()))
        nameBox.setSelectAllOnFocus(true)
        col.addView(nameBox)

        val info = TextView(activity)
        info.textSize = 13f
        info.setPadding(0, dp(10), 0, dp(6))

        val rows = ArrayList<TextView>()
        val wBox = EditText(activity)
        val hBox = EditText(activity)

        fun refreshInfo() {
            val ok = CanvasSizes.isValid(w, h)
            info.text = if (ok) {
                "$w x $h px  (${CanvasSizes.megapixels(w, h)}) - up to ${CanvasSizes.maxLayers(w, h)} layers"
            } else {
                "Size must be ${CanvasSizes.MIN_SIDE} to ${CanvasSizes.MAX_SIDE} px per side and at most " +
                    "${CanvasSizes.MAX_PIXELS / 1_000_000L}.${(CanvasSizes.MAX_PIXELS / 100_000L) % 10} megapixels"
            }
            info.setTextColor(if (ok) 0xFF444444.toInt() else 0xFFC62828.toInt())
        }

        fun paintRows() {
            for ((i, t) in rows.withIndex()) {
                val on = i == selected
                t.setTextColor(if (on) Color.WHITE else Color.BLACK)
                t.background = Ui.rounded(if (on) Ui.ACCENT else 0xFFEAEAEA.toInt(), dp(8).toFloat())
            }
        }

        fun setBoxes() {
            updating = true
            wBox.setText(w.toString())
            hBox.setText(h.toString())
            updating = false
        }

        val list = LinearLayout(activity)
        list.orientation = LinearLayout.VERTICAL
        for ((i, p) in CanvasSizes.presets.withIndex()) {
            val t = TextView(activity)
            t.text = "${p.label}   ${p.width} x ${p.height}"
            t.textSize = 14f
            t.setPadding(dp(12), dp(10), dp(12), dp(10))
            t.setOnClickListener {
                selected = i
                w = p.width; h = p.height; dpi = p.dpi
                setBoxes(); paintRows(); refreshInfo()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(3), 0, dp(3))
            list.addView(t, lp)
            rows.add(t)
        }

        // custom size
        val customRow = LinearLayout(activity)
        customRow.orientation = LinearLayout.HORIZONTAL
        customRow.gravity = Gravity.CENTER_VERTICAL
        for (b in listOf(wBox, hBox)) {
            b.inputType = InputType.TYPE_CLASS_NUMBER
            b.setSingleLine()
            b.gravity = Gravity.CENTER
        }
        val x = TextView(activity)
        x.text = " x "
        val rotate = Ui.chip(activity, "Rotate") {
            val tmp = w; w = h; h = tmp
            selected = -1
            setBoxes(); paintRows(); refreshInfo()
        }
        customRow.addView(wBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        customRow.addView(x)
        customRow.addView(hBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        customRow.addView(rotate, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(8) })

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (updating) return
                w = wBox.text.toString().toIntOrNull() ?: 0
                h = hBox.text.toString().toIntOrNull() ?: 0
                selected = -1
                dpi = CanvasSizes.DEFAULT_DPI
                paintRows(); refreshInfo()
            }
        }
        wBox.addTextChangedListener(watcher)
        hBox.addTextChangedListener(watcher)

        val label = TextView(activity)
        label.text = "Custom size (px)"
        label.textSize = 12f
        label.setPadding(0, dp(10), 0, 0)

        col.addView(info)
        val scroll = ScrollView(activity)
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        col.addView(label)
        col.addView(customRow)

        setBoxes(); paintRows(); refreshInfo()

        val dlg = AlertDialog.Builder(activity)
            .setTitle("New drawing")
            .setView(col)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!CanvasSizes.isValid(w, h)) {
                    val (cw, ch) = CanvasSizes.clamp(w.coerceAtLeast(1), h.coerceAtLeast(1))
                    w = cw; h = ch
                    selected = -1
                    setBoxes(); paintRows(); refreshInfo()
                    return@setOnClickListener
                }
                val nm = nameBox.text.toString().trim().ifEmpty { "Untitled" }
                dlg.dismiss()
                onCreate(nm, w, h, dpi)
            }
        }
        dlg.show()
    }
}
