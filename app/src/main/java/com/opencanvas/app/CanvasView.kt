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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The drawing surface.
 *
 * - One finger / stylus draws, two fingers pan + pinch-zoom.
 * - The live stroke is drawn into a scratch bitmap and composited at the brush opacity,
 *   so overlapping parts of one stroke never double-darken.
 * - When a stroke ends it is baked into the active layer and recorded for undo.
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

    private var gesturing = false
    private var lastFx = 0f
    private var lastFy = 0f

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
            val cx = width / 2f
            val cy = height / 2f
            val d = resources.displayMetrics.density
            ringPaint.strokeWidth = 3f * d
            ringPaint.color = Color.BLACK
            canvas.drawCircle(cx, cy, r, ringPaint)
            ringPaint.strokeWidth = 1.5f * d
            ringPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturing = false
                beginStroke(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!gesturing) {
                    cancelStroke()
                    gesturing = true
                }
                lastFx = focusX(e, -1)
                lastFy = focusY(e, -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesturing) {
                    if (e.pointerCount >= 2) {
                        val fx = focusX(e, -1)
                        val fy = focusY(e, -1)
                        matrix.postTranslate(fx - lastFx, fy - lastFy)
                        lastFx = fx
                        lastFy = fy
                        invalidate()
                    }
                } else {
                    extendStroke(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesturing) {
                    lastFx = focusX(e, e.actionIndex)
                    lastFy = focusY(e, e.actionIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gesturing) finishStroke(e)
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

    private fun finishStroke(e: MotionEvent) {
        val s = current ?: return
        val layer = liveLayer ?: return

        // Snap the end of the stroke to where the finger actually lifted.
        val d = toDoc(e.x, e.y)
        val last = s.pts[s.pts.size - 1]
        if (abs(d[0] - last.x) + abs(d[1] - last.y) > 0.5f) {
            appendPoint(s, d[0], d[1], last.p)
        }

        val r = Rect()
        s.inflatedBounds().roundOut(r)
        current = null
        liveLayer = null
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
        invalidate()
    }

    companion object {
        private const val SMOOTHING = 0.55f
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 32f
    }
}
