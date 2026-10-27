package com.opencanvas.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val canvas = CanvasView(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun button(label: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { action() }
            return b
        }

        val eraserBtn = Button(this)
        eraserBtn.text = "Eraser: off"
        eraserBtn.setOnClickListener {
            canvas.eraser = !canvas.eraser
            eraserBtn.text = if (canvas.eraser) "Eraser: on" else "Eraser: off"
        }

        bar.addView(button("Undo") { canvas.undo() })
        bar.addView(button("Redo") { canvas.redo() })
        bar.addView(button("Clear") { canvas.clear() })
        bar.addView(eraserBtn)

        val colors = intArrayOf(
            Color.BLACK,
            0xFFE53935.toInt(),
            0xFF1E88E5.toInt(),
            0xFF43A047.toInt(),
            0xFFFDD835.toInt()
        )
        for (color in colors) {
            val swatch = View(this)
            swatch.setBackgroundColor(color)
            swatch.setOnClickListener {
                canvas.eraser = false
                eraserBtn.text = "Eraser: off"
                canvas.brushColor = color
            }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.setMargins(dp(6), 0, dp(6), 0)
            bar.addView(swatch, lp)
        }

        val seek = SeekBar(this)
        seek.max = 79
        seek.progress = 11
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                canvas.brushSize = (progress + 1).toFloat()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        bar.addView(seek, LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = HorizontalScrollView(this)
        scroll.setBackgroundColor(0xFFEEEEEE.toInt())
        scroll.addView(bar)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(
            canvas,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
    }
}
