package com.opencanvas.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var canvas: CanvasView
    private lateinit var store: BrushStore

    private lateinit var layerPanel: LinearLayout
    private lateinit var layerList: LinearLayout
    private lateinit var colorPanel: LinearLayout
    private lateinit var brushPanel: LinearLayout
    private lateinit var brushList: LinearLayout
    private lateinit var picker: ColorPickerView
    private lateinit var recentRow: LinearLayout

    private lateinit var brushBtn: TextView
    private lateinit var smudgeBtn: TextView
    private lateinit var eraseBtn: TextView
    private lateinit var colorBtn: TextView
    private lateinit var layersBtn: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var blendBtn: TextView
    private lateinit var lockBtn: TextView

    private val recent = ArrayList<Int>()
    private val previewCache = HashMap<BrushSpec, Bitmap>()
    private var dragLayer: Layer? = null
    private var dragBefore: LayerProps? = null

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = BrushStore(this)
        canvas = CanvasView(this)
        val prefs = getSharedPreferences("carnival", MODE_PRIVATE)
        canvas.brush = store.find(prefs.getString("brush", "pencil") ?: "pencil") ?: Brushes.PENCIL
        canvas.onBlocked = { msg -> toast(msg) }
        canvas.onColorPicked = { c ->
            canvas.brushColor = c
            if (canvas.tool != Tool.BRUSH) canvas.tool = Tool.BRUSH
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
        buildBrushPanel()
        root.addView(layerPanel)
        root.addView(colorPanel)
        root.addView(brushPanel)
        setContentView(root)

        canvas.onDocChanged = { refreshLayerPanel() }
        refreshLayerPanel()
        refreshRecent()
        refreshBrushList()
        updateToolButtons()
    }

    override fun onBackPressed() {
        if (anyPanelOpen()) {
            hidePanels()
            updateToolButtons()
        } else {
            super.onBackPressed()
        }
    }

    // ---- panels --------------------------------------------------------------

    private fun anyPanelOpen(): Boolean =
        layerPanel.visibility == View.VISIBLE ||
            colorPanel.visibility == View.VISIBLE ||
            brushPanel.visibility == View.VISIBLE

    private fun hidePanels() {
        layerPanel.visibility = View.GONE
        colorPanel.visibility = View.GONE
        brushPanel.visibility = View.GONE
    }

    private fun togglePanel(panel: View) {
        val show = panel.visibility != View.VISIBLE
        hidePanels()
        if (show) panel.visibility = View.VISIBLE
        updateToolButtons()
    }

    // ---- top bar -----------------------------------------------------------

    private fun barParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(dp(3), 0, dp(3), 0)
        return p
    }

    private fun topChip(label: String, onClick: () -> Unit): TextView {
        val t = Ui.chip(this, label, 8, onClick)
        t.textSize = 12f
        return t
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), dp(4), dp(6), dp(4))

        val menu = topChip("Menu") {}
        menu.setOnClickListener { showActionsMenu(menu) }

        brushBtn = topChip("Brush") {
            if (canvas.tool == Tool.BRUSH) {
                togglePanel(brushPanel)
            } else {
                canvas.tool = Tool.BRUSH
                updateToolButtons()
            }
        }
        smudgeBtn = topChip("Smudge") {
            canvas.tool = Tool.SMUDGE
            updateToolButtons()
        }
        eraseBtn = topChip("Erase") {
            canvas.tool = Tool.ERASE
            updateToolButtons()
        }
        layersBtn = topChip("Layers") { togglePanel(layerPanel) }
        colorBtn = topChip("Color") {
            togglePanel(colorPanel)
            if (colorPanel.visibility == View.VISIBLE) picker.setColor(canvas.brushColor)
        }

        bar.addView(menu, barParams())
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(brushBtn, barParams())
        bar.addView(smudgeBtn, barParams())
        bar.addView(eraseBtn, barParams())
        bar.addView(layersBtn, barParams())
        bar.addView(colorBtn, barParams())

        val scroll = HorizontalScrollView(this)
        scroll.isFillViewport = true
        scroll.isHorizontalScrollBarEnabled = false
        scroll.setBackgroundColor(0xCC111111.toInt())
        scroll.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        scroll.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        )
        return scroll
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
            "Perfect shapes: draw a line, arc, circle, oval, rectangle or triangle, then hold still before lifting\n" +
            "Eyedropper: touch and hold without moving\n" +
            "Brushes: tap Brush again to open the brush library; long-press a brush to edit it"
        AlertDialog.Builder(this)
            .setTitle("Gestures")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun savePng() {
        val bmp = canvas.exportBitmap()
        val name = "Carnival_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
        Ui.setActive(this, brushBtn, canvas.tool == Tool.BRUSH)
        Ui.setActive(this, smudgeBtn, canvas.tool == Tool.SMUDGE)
        Ui.setActive(this, eraseBtn, canvas.tool == Tool.ERASE)
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

    // ---- brush library -------------------------------------------------------

    private fun panelParams(widthDp: Int): FrameLayout.LayoutParams {
        val pp = FrameLayout.LayoutParams(
            dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        )
        pp.topMargin = dp(58)
        pp.rightMargin = dp(8)
        return pp
    }

    private fun buildBrushPanel() {
        brushPanel = LinearLayout(this)
        brushPanel.orientation = LinearLayout.VERTICAL
        brushPanel.background = Ui.rounded(Ui.PANEL, dp(12).toFloat())
        brushPanel.setPadding(dp(10), dp(10), dp(10), dp(10))
        brushPanel.visibility = View.GONE
        brushPanel.layoutParams = panelParams(300)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Brushes"
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.chip(this, "+ New") {
            openStudio(canvas.brush.copy(id = store.newId(), name = "New brush", builtin = false), true)
        }, barParams())
        header.addView(Ui.chip(this, "Image tip") { pickTipImage() }, barParams())
        brushPanel.addView(header)

        brushList = LinearLayout(this)
        brushList.orientation = LinearLayout.VERTICAL
        val scroll = MaxHeightScrollView(this, dp(380))
        scroll.addView(brushList)
        val sp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        sp.topMargin = dp(8)
        brushPanel.addView(scroll, sp)
    }

    private fun refreshBrushList() {
        if (!::brushList.isInitialized) return
        brushList.removeAllViews()
        for (spec in store.all()) brushList.addView(makeBrushRow(spec))
    }

    private fun makeBrushRow(spec: BrushSpec): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(4), dp(6), dp(4))
        val bg = if (spec.id == canvas.brush.id) 0xFF2C5AA0.toInt() else 0xFF2A2A2C.toInt()
        row.background = Ui.rounded(bg, dp(8).toFloat())

        val img = ImageView(this)
        val bmp = previewCache.getOrPut(spec) {
            BrushPreview.render(this, spec, dp(150), dp(40), Color.WHITE)
        }
        img.setImageBitmap(bmp)

        val name = TextView(this)
        name.text = if (spec.builtin) spec.name else spec.name + "  *"
        name.setTextColor(Color.WHITE)
        name.textSize = 14f
        val np = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        np.leftMargin = dp(8)

        row.addView(img, LinearLayout.LayoutParams(dp(150), dp(40)))
        row.addView(name, np)
        row.setOnClickListener { selectBrush(spec) }
        row.setOnLongClickListener {
            showBrushMenu(spec, row)
            true
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(3), 0, dp(3))
        row.layoutParams = lp
        return row
    }

    private fun selectBrush(spec: BrushSpec) {
        canvas.brush = spec
        canvas.tool = Tool.BRUSH
        getSharedPreferences("carnival", MODE_PRIVATE).edit().putString("brush", spec.id).apply()
        refreshBrushList()
        updateToolButtons()
    }

    private fun showBrushMenu(spec: BrushSpec, anchor: View) {
        val pm = PopupMenu(this, anchor)
        if (!spec.builtin) pm.menu.add("Edit")
        pm.menu.add("Duplicate")
        if (!spec.builtin) pm.menu.add("Delete")
        pm.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Edit" -> openStudio(spec, false)
                "Duplicate" -> openStudio(
                    spec.copy(id = store.newId(), name = spec.name + " copy", builtin = false), true
                )
                "Delete" -> confirmDelete(spec)
            }
            true
        }
        pm.show()
    }

    private fun confirmDelete(spec: BrushSpec) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"" + spec.name + "\"?")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(spec.id)
                if (canvas.brush.id == spec.id) selectBrush(Brushes.PENCIL) else refreshBrushList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTipImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        startActivityForResult(i, REQ_TIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TIP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val file = store.importTip(uri)
            runOnUiThread {
                if (file == null) {
                    toast("Couldn't read that image")
                } else {
                    val spec = BrushSpec(
                        id = store.newId(), name = "Image brush", tip = TipKind.IMAGE, tipFile = file,
                        spacing = 0.15f, hardness = 1f, flow = 0.9f, sizePressure = 0.5f,
                        smoothing = 0.3f
                    )
                    openStudio(spec, true)
                }
            }
        }.start()
    }

    // ---- brush studio --------------------------------------------------------

    private fun addSlider(
        parent: LinearLayout,
        label: String,
        min: Float,
        max: Float,
        value: Float,
        fmt: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        val tv = TextView(this)
        tv.text = label + "   " + fmt(value)
        val sb = SeekBar(this)
        sb.max = 1000
        sb.progress = (((value - min) / (max - min)) * 1000f).roundToInt().coerceIn(0, 1000)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + (max - min) * progress / 1000f
                tv.text = label + "   " + fmt(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        parent.addView(tv, lp)
        parent.addView(sb)
    }

    private fun pct(v: Float): String = String.format(Locale.US, "%d%%", (v * 100f).roundToInt())

    private fun openStudio(start: BrushSpec, isNew: Boolean) {
        var spec = start
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(8), dp(16), dp(8))

        val preview = ImageView(this)
        preview.setBackgroundColor(0xFF2A2A2C.toInt())
        val pw = dp(280)
        val ph = dp(84)
        fun refreshPreview() {
            preview.setImageBitmap(BrushPreview.render(this, spec, pw, ph, Color.WHITE))
        }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph))

        val nameEdit = EditText(this)
        nameEdit.setText(spec.name)
        nameEdit.setSingleLine(true)
        nameEdit.hint = "Brush name"
        content.addView(nameEdit)

        val tipChips = ArrayList<Pair<TextView, TipKind>>()
        fun updateTipChips() {
            for ((chip, kind) in tipChips) Ui.setActive(this, chip, spec.tip == kind)
        }
        val tipRow = LinearLayout(this)
        tipRow.orientation = LinearLayout.HORIZONTAL
        fun addTip(label: String, kind: TipKind) {
            val chip = Ui.chip(this, label) {
                spec = spec.copy(tip = kind)
                updateTipChips()
                refreshPreview()
            }
            tipChips.add(Pair(chip, kind))
            tipRow.addView(chip, barParams())
        }
        addTip("Round", TipKind.ROUND)
        addTip("Grain", TipKind.GRAIN)
        if (spec.tipFile != null) addTip("Image", TipKind.IMAGE)
        content.addView(tipRow)

        addSlider(content, "Spacing", 0.01f, 1f, spec.spacing, { pct(it) }) {
            spec = spec.copy(spacing = it)
            refreshPreview()
        }
        addSlider(content, "Hardness", 0f, 1f, spec.hardness, { pct(it) }) {
            spec = spec.copy(hardness = it)
            refreshPreview()
        }
        addSlider(content, "Flow", 0.02f, 1f, spec.flow, { pct(it) }) {
            spec = spec.copy(flow = it)
            refreshPreview()
        }
        addSlider(content, "Pressure changes size", 0f, 1f, spec.sizePressure, { pct(it) }) {
            spec = spec.copy(sizePressure = it)
            refreshPreview()
        }
        addSlider(content, "Pressure changes flow", 0f, 1f, spec.flowPressure, { pct(it) }) {
            spec = spec.copy(flowPressure = it)
            refreshPreview()
        }
        addSlider(content, "Size jitter", 0f, 1f, spec.sizeJitter, { pct(it) }) {
            spec = spec.copy(sizeJitter = it)
            refreshPreview()
        }
        addSlider(content, "Angle", 0f, 180f, spec.angle, { it.roundToInt().toString() + "\u00B0" }) {
            spec = spec.copy(angle = it)
            refreshPreview()
        }
        addSlider(content, "Angle jitter", 0f, 1f, spec.angleJitter, { pct(it) }) {
            spec = spec.copy(angleJitter = it)
            refreshPreview()
        }
        addSlider(content, "Roundness", 0.1f, 1f, spec.roundness, { pct(it) }) {
            spec = spec.copy(roundness = it)
            refreshPreview()
        }
        addSlider(content, "Scatter", 0f, 0.6f, spec.scatter, { pct(it) }) {
            spec = spec.copy(scatter = it)
            refreshPreview()
        }
        addSlider(content, "Grain (grain tip)", 0f, 1f, spec.grain, { pct(it) }) {
            spec = spec.copy(grain = it)
            refreshPreview()
        }
        addSlider(content, "Smoothing", 0f, 1f, spec.smoothing, { pct(it) }) {
            spec = spec.copy(smoothing = it)
        }

        val follow = CheckBox(this)
        follow.text = "Tip follows stroke direction"
        follow.isChecked = spec.followStroke
        follow.setOnCheckedChangeListener { _, checked ->
            spec = spec.copy(followStroke = checked)
            refreshPreview()
        }
        content.addView(follow)

        if (spec.tipFile != null) {
            val invert = CheckBox(this)
            invert.text = "Invert image tip"
            invert.isChecked = spec.invertTip
            invert.setOnCheckedChangeListener { _, checked ->
                spec = spec.copy(invertTip = checked)
                refreshPreview()
            }
            content.addView(invert)
        }

        refreshPreview()
        updateTipChips()

        val scroll = ScrollView(this)
        scroll.addView(content)
        AlertDialog.Builder(this)
            .setTitle(if (isNew) "New brush" else "Edit brush")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val nm = nameEdit.text.toString().trim()
                val saved = spec.copy(name = if (nm.isEmpty()) "Custom brush" else nm, builtin = false)
                store.save(saved)
                selectBrush(saved)
                toast("Brush saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- layers panel --------------------------------------------------------

    private fun buildLayerPanel() {
        layerPanel = LinearLayout(this)
        layerPanel.orientation = LinearLayout.VERTICAL
        layerPanel.background = Ui.rounded(Ui.PANEL, dp(12).toFloat())
        layerPanel.setPadding(dp(10), dp(10), dp(10), dp(10))
        layerPanel.visibility = View.GONE
        layerPanel.layoutParams = panelParams(280)

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
        val pctValue = Math.round(a.opacity * 100f)
        opacitySeek.progress = pctValue
        opacityLabel.text = "Layer opacity  $pctValue%"
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
        colorPanel.layoutParams = panelParams(280)

        picker = ColorPickerView(this)
        picker.onColor = { c ->
            canvas.brushColor = c
            if (canvas.tool != Tool.BRUSH) canvas.tool = Tool.BRUSH
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
                if (canvas.tool != Tool.BRUSH) canvas.tool = Tool.BRUSH
                picker.setColor(c)
                updateToolButtons()
            }
            val lp = LinearLayout.LayoutParams(dp(26), dp(26))
            lp.setMargins(dp(2), 0, dp(2), 0)
            recentRow.addView(sw, lp)
        }
    }

    companion object {
        private const val REQ_TIP = 41
    }
}
