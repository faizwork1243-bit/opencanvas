package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
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

/**
 * The drawing surface.
 *
 * Gestures:
 * - one finger / stylus draws
 * - two fingers pan + pinch-zoom
 * - quick tap with two fingers = undo, with three fingers = redo
 * - draw, then hold still before lifting = the stroke snaps to a straight line
 * - touch and hold without moving = eyedropper
 *
 * The live stroke is drawn into a scratch bitmap and composited at the brush opacity,
 * so overlapping parts of one stroke never double-darken. When a stroke ends it is baked
 * into the active layer and recorded for undo.
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    val doc = Document()

    var brushColor: Int = Color.BLACK
    var brushSize: Float = 12f
    var brushOpacity: Float = 1f
    var eraser: Boolean = false

    /** When > 0, a circle showing the brush size is drawn in the middle of the view. */
    var previewSize: Float = 0f

    var onDocChanged: (() -> Unit)? = null
    var onBlocked: ((String) -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val holdSlop = 6f * density

    private val scratch = Bitmap.createBitmap(Document.SIZE, Document.SIZE, Bitmap.Config.ARGB_8888)
    private val scratchCanvas = Canvas(scratch)

    private var current: Stroke? = null
    private var liveLayer: Layer? = null

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val tmp = FloatArray(2)
    private val vals = FloatArray(9)
    private val docRect = RectF(0f, 0f, Document.SIZE.toFloat(), Document.SIZE.toFloat())

    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val livePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // gesture state
    private val handler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { snapToShape() }
    private val pickRunnable = Runnable { startPicking() }

    private var gesturing = false
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
        doc.onChanged = {
            invalidate()
            onDocChanged?.invoke()
        }
    }

    // ---- public actions ----------------------------------------------------

    fun fitToView() {
        if (width == 0 || height == 0) return
        val scale = min(width / Document.SIZE.toFloat(), height / Document.SIZE.toFloat()) * 0.95f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (width - Document.SIZE * scale) / 2f,
            (height - Document.SIZE * scale) / 2f
        )
        invalidate()
    }

    /** Flattens the visible layers onto white paper. */
    fun exportBitmap(): Bitmap {
        val out = Bitmap.createBitmap(Document.SIZE, Document.SIZE, Bitmap.Config.ARGB_8888)
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
        val group = canvas.saveLayer(docRect, null)
        canvas.drawRect(docRect, paperPaint)
        val s = current
        for (layer in doc.layers) {
            if (!layer.visible) continue
            layerPaint.alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
            layerPaint.xfermode = layer.blend.xfermode
            if (withLive && s != null && layer === liveLayer) {
                val lg = canvas.saveLayer(docRect, layerPaint)
                canvas.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
                livePaint.alpha = StrokeRenderer.alphaOf(s)
                livePaint.xfermode = if (s.erase) StrokeRenderer.dstOut else null
                canvas.drawBitmap(scratch, 0f, 0f, livePaint)
                canvas.restoreToCount(lg)
            } else {
                canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
            }
        }
        canvas.restoreToCount(group)
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
            if (maxPointers == 2) {
                doc.undo()
            } else if (maxPointers == 3) {
                doc.redo()
            }
            return
        }
        if (maxPointers >= 3) {
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
                handler.postDelayed(pickRunnable, PICK_DELAY_MS)
                beginStroke(e)
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
                    if (e.pointerCount >= 2) {
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
                        val s = current
                        if (s != null && shapeFollows) {
                            val d = toDoc(e.x, e.y)
                            rebuildLine(s, d[0], d[1])
                        }
                    } else {
                        extendStroke(e)
                        if (moved && hypot(e.x - anchorX, e.y - anchorY) > holdSlop) {
                            anchorX = e.x
                            anchorY = e.y
                            handler.removeCallbacks(holdRunnable)
                            handler.postDelayed(holdRunnable, HOLD_DELAY_MS)
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
        val ix = d[0].toInt().coerceIn(0, Document.SIZE - 1)
        val iy = d[1].toInt().coerceIn(0, Document.SIZE - 1)
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
        val s = Stroke(brushColor, brushSize, brushOpacity, eraser)
        current = s
        liveLayer = layer
        val d = toDoc(e.x, e.y)
        val pt = Pt(d[0], d[1], pressure(e, -1))
        s.add(pt)
        StrokeRenderer.drawDot(scratchCanvas, s, pt)
        invalidate()
    }

    private fun extendStroke(e: MotionEvent) {
        val s = current ?: return
        for (h in 0 until e.historySize) {
            smoothAppend(s, e.getHistoricalX(h), e.getHistoricalY(h), pressure(e, h))
        }
        smoothAppend(s, e.x, e.y, pressure(e, -1))
        invalidate()
    }

    private fun smoothAppend(s: Stroke, x: Float, y: Float, p: Float) {
        val d = toDoc(x, y)
        val last = s.pts[s.pts.size - 1]
        appendPoint(
            s,
            last.x + (d[0] - last.x) * SMOOTHING,
            last.y + (d[1] - last.y) * SMOOTHING,
            p
        )
    }

    private fun appendPoint(s: Stroke, x: Float, y: Float, p: Float) {
        val last = s.pts[s.pts.size - 1]
        val pt = Pt(x, y, p)
        s.add(pt)
        StrokeRenderer.drawSegment(scratchCanvas, s, last, pt)
    }

    private fun clearScratch(r: Rect) {
        scratchCanvas.save()
        scratchCanvas.clipRect(r)
        scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        scratchCanvas.restore()
    }

    /** QuickShape: after holding still, snap the stroke to a line, arc, circle, ellipse, rectangle or polygon. */
    private fun snapToShape() {
        val s = current ?: return
        if (straight || gesturing || picking || s.pts.size < 3) return
        val d = toDoc(lastX, lastY)
        val n = s.pts.size
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        for (i in 0 until n) {
            xs[i] = s.pts[i].x
            ys[i] = s.pts[i].y
        }
        xs[n] = d[0]
        ys[n] = d[1]
        val res = ShapeFit.recognize(xs, ys)
        straight = true
        shapeFollows = res.kind == ShapeFit.LINE
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (shapeFollows) {
            rebuildLine(s, d[0], d[1])
        } else {
            rebuildShape(s, res)
        }
    }

    private fun rebuildShape(s: Stroke, res: ShapeResult) {
        val old = Rect()
        s.inflatedBounds().roundOut(old)
        if (old.intersect(0, 0, Document.SIZE, Document.SIZE)) clearScratch(old)
        var sum = 0f
        for (pt in s.pts) sum += pt.p
        val p = sum / s.pts.size
        s.replaceWith(res.xs, res.ys, p)
        StrokeRenderer.drawDot(scratchCanvas, s, s.pts[0])
        for (i in 1 until s.pts.size) {
            StrokeRenderer.drawSegment(scratchCanvas, s, s.pts[i - 1], s.pts[i])
        }
        invalidate()
    }

    private fun rebuildLine(s: Stroke, x: Float, y: Float) {
        val old = Rect()
        s.inflatedBounds().roundOut(old)
        if (old.intersect(0, 0, Document.SIZE, Document.SIZE)) clearScratch(old)
        val first = s.pts[0]
        s.replaceWithLine(first, Pt(x, y, first.p))
        StrokeRenderer.drawDot(scratchCanvas, s, s.pts[0])
        StrokeRenderer.drawSegment(scratchCanvas, s, s.pts[0], s.pts[1])
        invalidate()
    }

    private fun finishStroke(e: MotionEvent) {
        val s = current ?: return
        val layer = liveLayer ?: return

        val d = toDoc(e.x, e.y)
        if (straight) {
            if (shapeFollows) rebuildLine(s, d[0], d[1])
        } else {
            // Snap the end of the stroke to where the finger actually lifted.
            val last = s.pts[s.pts.size - 1]
            if (abs(d[0] - last.x) + abs(d[1] - last.y) > 0.5f) {
                appendPoint(s, d[0], d[1], last.p)
            }
        }

        val r = Rect()
        s.inflatedBounds().roundOut(r)
        current = null
        liveLayer = null
        straight = false
        shapeFollows = false
        if (!r.intersect(0, 0, Document.SIZE, Document.SIZE)) {
            invalidate()
            return
        }
        layer.canvas.drawBitmap(scratch, r, r, StrokeRenderer.paintForCommit(s))
        clearScratch(r)
        layer.strokes.add(s)
        doc.push(StrokeCmd(layer, s))
        doc.changed()
    }

    private fun cancelStroke() {
        val s = current ?: return
        val r = Rect()
        s.inflatedBounds().roundOut(r)
        if (r.intersect(0, 0, Document.SIZE, Document.SIZE)) clearScratch(r)
        current = null
        liveLayer = null
        straight = false
        shapeFollows = false
        invalidate()
    }

    companion object {
        private const val SMOOTHING = 0.55f
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 32f
        private const val MAX_POINTERS = 16
        private const val PICK_DELAY_MS = 550L
        private const val HOLD_DELAY_MS = 500L
        private const val TAP_MS = 350L
        private const val TAP3_MS = 700L
    }
}
