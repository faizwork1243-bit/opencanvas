package com.opencanvas.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var canvas: CanvasView
    private lateinit var layerPanel: LinearLayout
    private lateinit var layerList: LinearLayout
    private lateinit var colorPanel: LinearLayout
    private lateinit var picker: ColorPickerView
    private lateinit var recentRow: LinearLayout
    private lateinit var brushBtn: TextView
    private lateinit var eraseBtn: TextView
    private lateinit var colorBtn: TextView
    private lateinit var layersBtn: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var blendBtn: TextView
    private lateinit var lockBtn: TextView

    private val recent = ArrayList<Int>()
    private var dragLayer: Layer? = null
    private var dragBefore: LayerProps? = null

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        canvas = CanvasView(this)
        canvas.onBlocked = { msg -> toast(msg) }
        canvas.onColorPicked = { c ->
            canvas.brushColor = c
            canvas.eraser = false
            picker.setColor(c)
            addRecent(c)
            updateToolButtons()
        }

        recent.addAll(
            listOf(
                Color.BLACK, Color.WHITE, 0xFFE53935.toInt(), 0xFF1E88E5.toInt(),
                0xFF43A047.toInt(), 0xFFFDD835.toInt()
            )
        )

        val root = FrameLayout(this)
        root.addView(
            canvas,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(buildSideControls())
        root.addView(buildTopBar())
        buildLayerPanel()
        buildColorPanel()
        root.addView(layerPanel)
        root.addView(colorPanel)
        setContentView(root)

        canvas.onDocChanged = { refreshLayerPanel() }
        refreshLayerPanel()
        refreshRecent()
        updateToolButtons()
    }

    override fun onBackPressed() {
        if (layerPanel.visibility == View.VISIBLE || colorPanel.visibility == View.VISIBLE) {
            layerPanel.visibility = View.GONE
            colorPanel.visibility = View.GONE
            updateToolButtons()
        } else {
            super.onBackPressed()
        }
    }

    // ---- top bar -----------------------------------------------------------

    private fun barParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(dp(3), 0, dp(3), 0)
        return p
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xCC111111.toInt())
        bar.setPadding(dp(6), dp(4), dp(6), dp(4))

        val actions = Ui.chip(this, "Actions") {}
        actions.setOnClickListener { showActionsMenu(actions) }

        brushBtn = Ui.chip(this, "Brush") {
            canvas.eraser = false
            updateToolButtons()
        }
        eraseBtn = Ui.chip(this, "Erase") {
            canvas.eraser = true
            updateToolButtons()
        }
        layersBtn = Ui.chip(this, "Layers") { toggleLayers() }
        colorBtn = Ui.chip(this, "Color") { toggleColor() }

        bar.addView(actions, barParams())
        val spacer = View(this)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(brushBtn, barParams())
        bar.addView(eraseBtn, barParams())
        bar.addView(layersBtn, barParams())
        bar.addView(colorBtn, barParams())

        bar.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        )
        return bar
    }

    private fun showActionsMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Save PNG")
        pm.menu.add("Clear layer")
        pm.menu.add("Fit to screen")
        pm.menu.add("Gesture guide")
        pm.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Save PNG" -> savePng()
                "Clear layer" -> canvas.doc.clearActive()
                "Fit to screen" -> canvas.fitToView()
                "Gesture guide" -> showGuide()
            }
            true
        }
        pm.show()
    }

    private fun showGuide() {
        val text = "Draw: one finger or stylus\n" +
            "Pan and zoom: two fingers\n" +
            "Undo: tap once with two fingers\n" +
            "Redo: tap once with three fingers\n" +
            "Straight line: draw, then hold still before lifting\n" +
            "Eyedropper: touch and hold without moving"
        AlertDialog.Builder(this)
            .setTitle("Gestures")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun savePng() {
        val bmp = canvas.exportBitmap()
        val name = "OpenCanvas_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val appCtx = applicationContext
        Thread {
            val where = Exporter.savePng(appCtx, bmp, name)
            runOnUiThread {
                toast(if (where != null) "Saved: $where" else "Save failed")
                bmp.recycle()
            }
        }.start()
    }

    private fun updateToolButtons() {
        Ui.setActive(this, brushBtn, !canvas.eraser)
        Ui.setActive(this, eraseBtn, canvas.eraser)
        Ui.setActive(this, layersBtn, layerPanel.visibility == View.VISIBLE)
        val c = canvas.brushColor
        colorBtn.background = Ui.rounded(c or (0xFF shl 24), dp(8).toFloat())
        val lum = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
        colorBtn.setTextColor(if (lum > 150f) Color.BLACK else Color.WHITE)
    }

    // ---- left side: sliders + undo/redo -------------------------------------

    private fun buildSideControls(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val sizeSlider = VerticalSlider(this)
        sizeSlider.value = sqrt((canvas.brushSize - 1f) / 199f)
        sizeSlider.onChange = { v ->
            canvas.brushSize = 1f + v * v * 199f
            canvas.previewSize = canvas.brushSize
            canvas.invalidate()
        }
        sizeSlider.onFinish = {
            canvas.previewSize = 0f
            canvas.invalidate()
        }

        val opacitySlider = VerticalSlider(this)
        opacitySlider.value = canvas.brushOpacity
        opacitySlider.onChange = { v -> canvas.brushOpacity = maxOf(v, 0.02f) }

        val sp = LinearLayout.LayoutParams(dp(34), dp(150))
        sp.setMargins(0, 0, 0, dp(12))
        col.addView(sizeSlider, sp)
        val op = LinearLayout.LayoutParams(dp(34), dp(150))
        op.setMargins(0, 0, 0, dp(12))
        col.addView(opacitySlider, op)

        val undo = Ui.chip(this, "Undo", 4) { canvas.doc.undo() }
        val redo = Ui.chip(this, "Redo", 4) { canvas.doc.redo() }
        undo.textSize = 11f
        redo.textSize = 11f
        val bp = LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT)
        bp.setMargins(0, 0, 0, dp(6))
        col.addView(undo, bp)
        col.addView(redo, bp)

        val lp = FrameLayout.LayoutParams(
            dp(46), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL
        )
        lp.leftMargin = dp(6)
        lp.topMargin = dp(40)
        col.layoutParams = lp
        return col
    }

    // ---- layers panel --------------------------------------------------------

    private fun buildLayerPanel() {
        layerPanel = LinearLayout(this)
        layerPanel.orientation = LinearLayout.VERTICAL
        layerPanel.background = Ui.rounded(Ui.PANEL, dp(12).toFloat())
        layerPanel.setPadding(dp(10), dp(10), dp(10), dp(10))
        layerPanel.visibility = View.GONE
        val pp = FrameLayout.LayoutParams(
            dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        )
        pp.topMargin = dp(58)
        pp.rightMargin = dp(8)
        layerPanel.layoutParams = pp

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Layers"
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.chip(this, "+ New") {
            if (!canvas.doc.addLayer()) toast("Layer limit reached (${Document.MAX_LAYERS})")
        }, barParams())
        header.addView(Ui.chip(this, "Copy") {
            if (!canvas.doc.duplicateActive()) toast("Layer limit reached (${Document.MAX_LAYERS})")
        }, barParams())
        layerPanel.addView(header)

        layerList = LinearLayout(this)
        layerList.orientation = LinearLayout.VERTICAL
        val scroll = MaxHeightScrollView(this, dp(260))
        scroll.addView(layerList)
        val sp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        sp.setMargins(0, dp(8), 0, dp(8))
        layerPanel.addView(scroll, sp)

        opacityLabel = TextView(this)
        opacityLabel.setTextColor(Color.WHITE)
        opacityLabel.textSize = 13f
        layerPanel.addView(opacityLabel)

        opacitySeek = SeekBar(this)
        opacitySeek.max = 100
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val l = canvas.doc.active
                l.opacity = progress / 100f
                opacityLabel.text = "Layer opacity  $progress%"
                canvas.invalidate()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                dragLayer = canvas.doc.active
                dragBefore = canvas.doc.active.props()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val l = dragLayer
                val b = dragBefore
                if (l != null && b != null) canvas.doc.commitProps(l, b)
                dragLayer = null
                dragBefore = null
            }
        })
        layerPanel.addView(opacitySeek)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        blendBtn = Ui.chip(this, "Normal") {}
        blendBtn.setOnClickListener { showBlendMenu() }
        lockBtn = Ui.chip(this, "Lock") {
            val l = canvas.doc.active
            canvas.doc.setProps(l, l.props().copy(locked = !l.locked))
        }
        row1.addView(blendBtn, barParams())
        row1.addView(lockBtn, barParams())
        val rp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rp.topMargin = dp(6)
        layerPanel.addView(row1, rp)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(Ui.chip(this, "Up") { canvas.doc.moveActive(1) }, barParams())
        row2.addView(Ui.chip(this, "Down") { canvas.doc.moveActive(-1) }, barParams())
        row2.addView(Ui.chip(this, "Delete") {
            if (!canvas.doc.deleteActive()) toast("Can't delete the last layer")
        }, barParams())
        layerPanel.addView(row2, rp)
    }

    private fun showBlendMenu() {
        val pm = PopupMenu(this, blendBtn)
        for (b in Blend.values()) pm.menu.add(b.label)
        pm.setOnMenuItemClickListener { item ->
            val chosen = Blend.values().firstOrNull { it.label == item.title.toString() }
            if (chosen != null) {
                val l = canvas.doc.active
                canvas.doc.setProps(l, l.props().copy(blend = chosen))
            }
            true
        }
        pm.show()
    }

    private fun toggleLayers() {
        val show = layerPanel.visibility != View.VISIBLE
        layerPanel.visibility = if (show) View.VISIBLE else View.GONE
        colorPanel.visibility = View.GONE
        updateToolButtons()
    }

    private fun makeRow(layer: Layer): View {
        val doc = canvas.doc
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(4), dp(6), dp(4))
        val bg = if (layer === doc.active) 0xFF2C5AA0.toInt() else 0xFF2A2A2C.toInt()
        row.background = Ui.rounded(bg, dp(8).toFloat())

        val eye = CheckBox(this)
        eye.isChecked = layer.visible
        eye.setOnCheckedChangeListener { _, checked ->
            doc.setProps(layer, layer.props().copy(visible = checked))
        }

        val thumb = LayerThumb(this, layer)
        val tp = LinearLayout.LayoutParams(dp(40), dp(40))
        tp.setMargins(dp(4), 0, dp(8), 0)

        val name = TextView(this)
        name.text = layer.name + (if (layer.locked) "  [locked]" else "")
        name.setTextColor(Color.WHITE)
        name.textSize = 14f
        name.alpha = if (layer.visible) 1f else 0.5f

        row.addView(eye)
        row.addView(thumb, tp)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { doc.select(layer) }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(3), 0, dp(3))
        row.layoutParams = lp
        return row
    }

    private fun refreshLayerPanel() {
        if (!::layerList.isInitialized) return
        val doc = canvas.doc
        layerList.removeAllViews()
        for (i in doc.layers.indices.reversed()) {
            layerList.addView(makeRow(doc.layers[i]))
        }
        val a = doc.active
        val pct = Math.round(a.opacity * 100f)
        opacitySeek.progress = pct
        opacityLabel.text = "Layer opacity  $pct%"
        blendBtn.text = a.blend.label
        lockBtn.text = if (a.locked) "Unlock" else "Lock"
    }

    // ---- color panel -----------------------------------------------------------

    private fun buildColorPanel() {
        colorPanel = LinearLayout(this)
        colorPanel.orientation = LinearLayout.VERTICAL
        colorPanel.background = Ui.rounded(Ui.PANEL, dp(12).toFloat())
        colorPanel.setPadding(dp(10), dp(10), dp(10), dp(10))
        colorPanel.visibility = View.GONE
        val cp = FrameLayout.LayoutParams(
            dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        )
        cp.topMargin = dp(58)
        cp.rightMargin = dp(8)
        colorPanel.layoutParams = cp

        picker = ColorPickerView(this)
        picker.onColor = { c ->
            canvas.brushColor = c
            canvas.eraser = false
            updateToolButtons()
        }
        picker.onFinish = { addRecent(canvas.brushColor) }
        colorPanel.addView(picker, LinearLayout.LayoutParams(dp(260), dp(230)))

        recentRow = LinearLayout(this)
        recentRow.orientation = LinearLayout.HORIZONTAL
        val rp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rp.topMargin = dp(8)
        colorPanel.addView(recentRow, rp)
    }

    private fun toggleColor() {
        val show = colorPanel.visibility != View.VISIBLE
        colorPanel.visibility = if (show) View.VISIBLE else View.GONE
        layerPanel.visibility = View.GONE
        if (show) picker.setColor(canvas.brushColor)
        updateToolButtons()
    }

    private fun addRecent(c: Int) {
        recent.remove(c)
        recent.add(0, c)
        while (recent.size > 8) recent.removeAt(recent.size - 1)
        refreshRecent()
    }

    private fun refreshRecent() {
        recentRow.removeAllViews()
        for (c in recent) {
            val sw = View(this)
            sw.background = Ui.rounded(c or (0xFF shl 24), dp(15).toFloat())
            sw.setOnClickListener {
                canvas.brushColor = c
                canvas.eraser = false
                picker.setColor(c)
                updateToolButtons()
            }
            val lp = LinearLayout.LayoutParams(dp(26), dp(26))
            lp.setMargins(dp(2), 0, dp(2), 0)
            recentRow.addView(sw, lp)
        }
    }
}
