package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool { BRUSH, SMUDGE, ERASE }

/**
 * The drawing surface.
 *
 * Gestures:
 * - one finger / stylus draws, two fingers pan + pinch-zoom
 * - quick tap with two fingers = undo, with three fingers = redo
 * - draw, then hold still before lifting = QuickShape (line, arc, circle, ellipse, rectangle, polygon)
 * - touch and hold without moving = eyedropper
 *
 * Brush and eraser strokes are stamped into small live tiles and composited at the brush
 * opacity when the stroke ends. Smudge also works in live tiles. Undo stores the pixels of
 * the touched rectangle before and after, so it works for every kind of stroke.
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The drawing being edited. Set once with setDocument() before the view is used. */
    var doc: Document = Document(1, 1)
        private set

    var tool: Tool = Tool.BRUSH
    var brush: BrushSpec = Brushes.PENCIL
    var brushColor: Int = Color.BLACK
    var brushSize: Float = 12f
    var brushOpacity: Float = 1f

    /** When > 0, a circle showing the brush size is drawn in the middle of the view. */
    var previewSize: Float = 0f

    var onDocChanged: (() -> Unit)? = null
    var onBlocked: ((String) -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val holdSlop = 6f * density

    /** The live stroke, drawn into small tiles so only a few KB change per frame. */
    private var overlay = TileSet(1, 1)

    // the stroke in progress
    private var drawing = false
    private var liveLayer: Layer? = null
    private var painter: BrushPainter? = null
    private var smudger: SmudgePainter? = null
    private var liveOpacity = 1f
    private var liveErase = false
    private var strokeSpec: BrushSpec? = null
    private var strokeTip: Tip? = null
    private var strokeSize = 12f
    private var strokeColor = Color.BLACK
    private var strokeSeed = 0L
    private val pts = ArrayList<Pt>()

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val tmp = FloatArray(2)
    private val vals = FloatArray(9)
    private val docRect = RectF(0f, 0f, 1f, 1f)

    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val livePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val smudgePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val commitPaint = Paint()
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // gesture state
    private val handler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { snapToShape() }
    private val pickRunnable = Runnable { startPicking() }

    private var gesturing = false
    private var fingerPan = false
    private var lastFx = 0f
    private var lastFy = 0f

    private val startX = FloatArray(MAX_POINTERS)
    private val startY = FloatArray(MAX_POINTERS)
    private var maxPointers = 1
    private var gestureDown = 0L
    private var tapCandidate = false

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var moved = false
    private var straight = false
    private var shapeFollows = false

    private var picking = false
    private var pickX = 0f
    private var pickY = 0f
    private var pickColor = Color.BLACK

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                val target = (cur * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val f = target / cur
                matrix.postScale(f, f, detector.focusX, detector.focusY)
                invalidate()
                return true
            }
        }
    )

    init {
        hookDoc(doc)
    }

    private fun hookDoc(d: Document) {
        d.onChanged = {
            invalidate()
            onDocChanged?.invoke()
        }
    }

    /** Switches to another drawing (and its canvas size). */
    fun setDocument(d: Document) {
        cancelStroke()
        doc = d
        hookDoc(d)
        overlay = TileSet(d.width, d.height)
        docRect.set(0f, 0f, d.width.toFloat(), d.height.toFloat())
        fitToView()
        onDocChanged?.invoke()
    }

    // ---- public actions ----------------------------------------------------

    fun fitToView() {
        if (width == 0 || height == 0) return
        val scale = min(width / doc.width.toFloat(), height / doc.height.toFloat()) * 0.95f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (width - doc.width * scale) / 2f,
            (height - doc.height * scale) / 2f
        )
        invalidate()
    }

    /** Flattens the visible layers onto white paper. */
    fun exportBitmap(): Bitmap {
        val out = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        drawDocument(Canvas(out), false)
        return out
    }

    // ---- view plumbing -----------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(holdRunnable)
        handler.removeCallbacks(pickRunnable)
        super.onDetachedFromWindow()
    }

    private fun currentScale(): Float {
        matrix.getValues(vals)
        return vals[Matrix.MSCALE_X]
    }

    private fun drawDocument(canvas: Canvas, withLive: Boolean) {
        val saved = canvas.save()
        canvas.clipRect(docRect)
        canvas.drawRect(docRect, paperPaint)
        val smudging = smudger != null
        for (layer in doc.layers) {
            if (!layer.visible) continue
            layerPaint.alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
            layerPaint.xfermode = layer.blend.xfermode
            val live = withLive && drawing && layer === liveLayer && !overlay.isEmpty
            if (live) {
                val lg = canvas.saveLayer(docRect, layerPaint)
                canvas.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
                if (smudging) {
                    overlay.draw(canvas, smudgePaint)
                } else {
                    livePaint.alpha = (liveOpacity * 255f).roundToInt().coerceIn(0, 255)
                    livePaint.xfermode = if (liveErase) dstOut else null
                    overlay.draw(canvas, livePaint)
                }
                canvas.restoreToCount(lg)
            } else {
                canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
            }
        }
        canvas.restoreToCount(saved)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF1E1E1E.toInt())
        canvas.save()
        canvas.concat(matrix)
        drawDocument(canvas, true)
        canvas.restore()

        if (previewSize > 0f) {
            val r = previewSize * currentScale() / 2f
            drawRing(canvas, width / 2f, height / 2f, r)
        }

        if (picking) {
            val r = 30f * density
            val off = 76f * density
            val cy = if (pickY - off < r) pickY + off else pickY - off
            fillPaint.color = pickColor
            canvas.drawCircle(pickX, cy, r, fillPaint)
            drawRing(canvas, pickX, cy, r)
            drawRing(canvas, pickX, pickY, 7f * density)
        }
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        ringPaint.strokeWidth = 3f * density
        ringPaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, r, ringPaint)
        ringPaint.strokeWidth = 1.5f * density
        ringPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, ringPaint)
    }

    // ---- touch -----------------------------------------------------------------

    private fun recordStart(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        if (id in 0 until MAX_POINTERS) {
            startX[id] = e.getX(index)
            startY[id] = e.getY(index)
        }
    }

    private fun checkTapMovement(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (id !in 0 until MAX_POINTERS) continue
            val tol = slop * (if (maxPointers >= 3) 3f else 2f)
            if (hypot(e.getX(i) - startX[id], e.getY(i) - startY[id]) > tol) {
                tapCandidate = false
            }
        }
    }

    /** Two-finger tap = undo, three-finger tap = redo. */
    private fun finishMultiTouch(e: MotionEvent) {
        val isUp = e.actionMasked == MotionEvent.ACTION_UP
        val limit = if (maxPointers >= 3) TAP3_MS else TAP_MS
        val quick = e.eventTime - gestureDown < limit
        // Some phones cancel three-finger touches for their own system gestures,
        // so a quick cancelled three-finger touch still counts as a tap.
        val tapped = tapCandidate && quick && (isUp || maxPointers >= 3)
        if (tapped) {
            if (maxPointers == 2 && AppSettings.twoFingerUndo) {
                doc.undo()
            } else if (maxPointers == 3 && AppSettings.threeFingerRedo) {
                doc.redo()
            }
            return
        }
        if (maxPointers >= 3 && AppSettings.threeFingerRedo) {
            val why = if (!tapCandidate) {
                "fingers moved too much"
            } else if (!quick) {
                "too slow"
            } else {
                "cancelled by the system"
            }
            onBlocked?.invoke("Three-finger tap not recognised: $why")
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturing = false
                maxPointers = 1
                tapCandidate = true
                gestureDown = e.eventTime
                recordStart(e, 0)
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                anchorX = e.x
                anchorY = e.y
                moved = false
                straight = false
                shapeFollows = false
                picking = false
                handler.removeCallbacks(holdRunnable)
                handler.removeCallbacks(pickRunnable)
                fingerPan = AppSettings.stylusOnly && e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS
                if (fingerPan) {
                    // stylus-only mode: a finger pans and zooms instead of drawing
                    gesturing = true
                    lastFx = e.x
                    lastFy = e.y
                } else {
                    if (AppSettings.eyedropper) {
                        handler.postDelayed(pickRunnable, AppSettings.pickDelayMs.toLong())
                    }
                    beginStroke(e)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(pickRunnable)
                handler.removeCallbacks(holdRunnable)
                picking = false
                maxPointers = max(maxPointers, e.pointerCount)
                recordStart(e, e.actionIndex)
                if (!gesturing) {
                    cancelStroke()
                    gesturing = true
                }
                lastFx = focusX(e, -1)
                lastFy = focusY(e, -1)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                if (gesturing) {
                    checkTapMovement(e)
                    if (e.pointerCount >= 2 || fingerPan) {
                        val fx = focusX(e, -1)
                        val fy = focusY(e, -1)
                        matrix.postTranslate(fx - lastFx, fy - lastFy)
                        lastFx = fx
                        lastFy = fy
                        invalidate()
                    }
                } else if (picking) {
                    updatePick(e.x, e.y)
                } else {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        handler.removeCallbacks(pickRunnable)
                    }
                    if (straight) {
                        if (shapeFollows && drawing) {
                            val d = toDoc(e.x, e.y)
                            rebuildLine(d[0], d[1])
                        }
                    } else {
                        extendStroke(e)
                        if (AppSettings.quickShape && moved &&
                            hypot(e.x - anchorX, e.y - anchorY) > holdSlop
                        ) {
                            anchorX = e.x
                            anchorY = e.y
                            handler.removeCallbacks(holdRunnable)
                            handler.postDelayed(holdRunnable, AppSettings.holdDelayMs.toLong())
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesturing) {
                    lastFx = focusX(e, e.actionIndex)
                    lastFy = focusY(e, e.actionIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(pickRunnable)
                handler.removeCallbacks(holdRunnable)
                if (picking) {
                    endPicking()
                } else if (!gesturing) {
                    finishStroke(e)
                } else {
                    finishMultiTouch(e)
                }
                gesturing = false
                fingerPan = false
            }
        }
        return true
    }

    private fun focusX(e: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sum += e.getX(i)
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    private fun focusY(e: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sum += e.getY(i)
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    // ---- eyedropper ----------------------------------------------------------------

    private fun startPicking() {
        if (gesturing) return
        cancelStroke()
        picking = true
        updatePick(lastX, lastY)
    }

    private fun updatePick(x: Float, y: Float) {
        pickX = x
        pickY = y
        val d = toDoc(x, y)
        val ix = d[0].toInt().coerceIn(0, doc.width - 1)
        val iy = d[1].toInt().coerceIn(0, doc.height - 1)
        pickColor = sampleColor(ix, iy)
        invalidate()
    }

    private fun endPicking() {
        picking = false
        invalidate()
        onColorPicked?.invoke(pickColor)
    }

    /** Renders just one pixel of the flattened document. */
    private fun sampleColor(x: Int, y: Int): Int {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.translate(-x.toFloat(), -y.toFloat())
        drawDocument(c, false)
        val px = b.getPixel(0, 0)
        b.recycle()
        return px
    }

    // ---- stroke handling ---------------------------------------------------

    private fun toDoc(x: Float, y: Float): FloatArray {
        matrix.invert(inverse)
        tmp[0] = x
        tmp[1] = y
        inverse.mapPoints(tmp)
        return tmp
    }

    private fun pressure(e: MotionEvent, h: Int): Float {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return 1f
        val raw = if (h < 0) e.pressure else e.getHistoricalPressure(h)
        return raw.coerceIn(0.05f, 1f)
    }

    private fun beginStroke(e: MotionEvent) {
        val layer = doc.active
        if (!layer.visible) {
            onBlocked?.invoke("This layer is hidden")
            return
        }
        if (layer.locked) {
            onBlocked?.invoke("This layer is locked")
            return
        }
        liveLayer = layer
        val d = toDoc(e.x, e.y)
        val p = pressure(e, -1)
        pts.clear()
        pts.add(Pt(d[0], d[1], p))
        strokeSeed = System.nanoTime()
        strokeSize = brushSize

        if (tool == Tool.SMUDGE) {
            val sp = SmudgePainter(
                layer.bitmap, overlay, AppSettings.smudge(),
                strokeSize, max(brushOpacity, 0.05f), strokeSeed
            )
            smudger = sp
            sp.begin(d[0], d[1], p)
        } else {
            val spec = brush
            strokeSpec = spec
            strokeTip = TipCache.get(context, spec)
            liveErase = tool == Tool.ERASE
            liveOpacity = brushOpacity
            strokeColor = if (liveErase) Color.BLACK else brushColor
            val pa = BrushPainter(TileTarget(overlay), spec, strokeTip!!, strokeSize, strokeColor, strokeSeed, doc.width, doc.height)
            painter = pa
            pa.begin(d[0], d[1], p)
        }
        drawing = true
        invalidate()
    }

    private fun smoothingFactor(): Float {
        val s = if (tool == Tool.SMUDGE) 0.2f else brush.smoothing
        return 1f - 0.88f * s
    }

    private fun extendStroke(e: MotionEvent) {
        if (!drawing) return
        for (h in 0 until e.historySize) {
            smoothAppend(e.getHistoricalX(h), e.getHistoricalY(h), pressure(e, h))
        }
        smoothAppend(e.x, e.y, pressure(e, -1))
        invalidate()
    }

    private fun smoothAppend(x: Float, y: Float, p: Float) {
        val d = toDoc(x, y)
        val last = pts[pts.size - 1]
        val f = smoothingFactor()
        addPoint(last.x + (d[0] - last.x) * f, last.y + (d[1] - last.y) * f, p)
    }

    private fun addPoint(x: Float, y: Float, p: Float) {
        pts.add(Pt(x, y, p))
        painter?.moveTo(x, y, p)
        smudger?.moveTo(x, y, p)
    }

    /** Re-stamps the whole stroke along a new path (used by QuickShape). */
    private fun replay(xs: FloatArray, ys: FloatArray, p: Float) {
        val spec = strokeSpec ?: return
        val tip = strokeTip ?: return
        overlay.clear()
        val pa = BrushPainter(TileTarget(overlay), spec, tip, strokeSize, strokeColor, strokeSeed, doc.width, doc.height)
        pa.begin(xs[0], ys[0], p)
        for (i in 1 until xs.size) pa.moveTo(xs[i], ys[i], p)
        painter = pa
        invalidate()
    }

    private fun rebuildLine(x: Float, y: Float) {
        val first = pts[0]
        replay(floatArrayOf(first.x, x), floatArrayOf(first.y, y), first.p)
    }

    /** QuickShape: after holding still, snap the stroke to a line, arc, circle, ellipse, rectangle or polygon. */
    private fun snapToShape() {
        if (!AppSettings.quickShape || !drawing || straight || gesturing || picking ||
            tool == Tool.SMUDGE || pts.size < 3
        ) {
            return
        }
        val d = toDoc(lastX, lastY)
        val n = pts.size
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        var sum = 0f
        for (i in 0 until n) {
            xs[i] = pts[i].x
            ys[i] = pts[i].y
            sum += pts[i].p
        }
        xs[n] = d[0]
        ys[n] = d[1]
        val res = ShapeFit.recognize(xs, ys)
        straight = true
        shapeFollows = res.kind == ShapeFit.LINE
        if (AppSettings.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (shapeFollows) {
            rebuildLine(d[0], d[1])
        } else {
            replay(res.xs, res.ys, sum / n)
        }
    }

    private fun finishStroke(e: MotionEvent) {
        if (!drawing) return
        val layer = liveLayer ?: return

        // Snap the end of the stroke to where the finger actually lifted.
        val d = toDoc(e.x, e.y)
        if (straight) {
            if (shapeFollows) rebuildLine(d[0], d[1])
        } else {
            val last = pts[pts.size - 1]
            if (abs(d[0] - last.x) + abs(d[1] - last.y) > 0.5f) {
                addPoint(d[0], d[1], last.p)
            }
        }

        val sm = smudger
        val pa = painter
        val r = if (sm != null) sm.dirtyRect() else pa?.dirtyRect()
        val erase = liveErase
        val opacity = liveOpacity
        drawing = false
        liveLayer = null
        straight = false
        shapeFollows = false
        painter = null
        smudger = null

        if (r == null) {
            overlay.clear()
            invalidate()
            return
        }

        // merge the live tiles into the layer, remembering the pixels before and after for undo
        val before = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        if (sm != null) {
            overlay.forEachTouched { tx, ty, bmp ->
                layer.canvas.drawBitmap(
                    bmp, (tx * TileGeom.T).toFloat(), (ty * TileGeom.T).toFloat(), copyPaint
                )
            }
        } else {
            commitPaint.alpha = (opacity * 255f).roundToInt().coerceIn(0, 255)
            commitPaint.xfermode = if (erase) dstOut else null
            overlay.forEachTouched { tx, ty, bmp ->
                layer.canvas.drawBitmap(
                    bmp, (tx * TileGeom.T).toFloat(), (ty * TileGeom.T).toFloat(), commitPaint
                )
            }
        }
        val after = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        overlay.clear()
        layer.dirty = true
        doc.push(PatchCmd(layer, r.left, r.top, before, after))
        doc.changed()
    }

    private fun cancelStroke() {
        if (!drawing) return
        painter = null
        smudger = null
        overlay.clear()
        drawing = false
        liveLayer = null
        straight = false
        shapeFollows = false
        invalidate()
    }

    companion object {
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 32f
        private const val MAX_POINTERS = 16
        private const val PICK_DELAY_MS = 550L
        private const val HOLD_DELAY_MS = 500L
        private const val TAP_MS = 350L
        private const val TAP3_MS = 700L
    }
}
