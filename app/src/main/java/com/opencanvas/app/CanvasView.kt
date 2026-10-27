package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/**
 * Phase 1 drawing surface.
 *
 * - One finger / stylus draws, two fingers pan + pinch-zoom.
 * - Strokes are stored as point lists so undo/redo is a simple re-render.
 *   (Phase 3 replaces this with tile diffs so big canvases stay fast.)
 * - Eraser currently paints white; it becomes a true erase once layers exist.
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Pt(val x: Float, val y: Float, val p: Float)

    private class Stroke(val color: Int, val size: Float) {
        val pts = ArrayList<Pt>()
    }

    var brushColor: Int = Color.BLACK
    var brushSize: Float = 12f
    var eraser: Boolean = false

    private val bitmap = Bitmap.createBitmap(DOC_SIZE, DOC_SIZE, Bitmap.Config.ARGB_8888)
    private val bmpCanvas = Canvas(bitmap)

    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()
    private var current: Stroke? = null

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val tmp = FloatArray(2)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var gesturing = false
    private var lastFx = 0f
    private var lastFy = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                matrix.postScale(
                    detector.scaleFactor, detector.scaleFactor,
                    detector.focusX, detector.focusY
                )
                invalidate()
                return true
            }
        }
    )

    init {
        bmpCanvas.drawColor(Color.WHITE)
    }

    // ---- public actions ----------------------------------------------------

    fun undo() {
        if (strokes.isEmpty()) return
        redoStack.add(strokes.removeAt(strokes.lastIndex))
        rerender()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        strokes.add(redoStack.removeAt(redoStack.lastIndex))
        rerender()
    }

    fun clear() {
        strokes.clear()
        redoStack.clear()
        current = null
        rerender()
    }

    // ---- view plumbing -----------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = min(w / DOC_SIZE.toFloat(), h / DOC_SIZE.toFloat()) * 0.95f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate((w - DOC_SIZE * scale) / 2f, (h - DOC_SIZE * scale) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF303030.toInt())
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
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
                if (!gesturing) endStroke()
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
        val s = Stroke(if (eraser) Color.WHITE else brushColor, brushSize)
        current = s
        val d = toDoc(e.x, e.y)
        val pt = Pt(d[0], d[1], pressure(e, -1))
        s.pts.add(pt)
        drawDot(bmpCanvas, s, pt)
        invalidate()
    }

    private fun extendStroke(e: MotionEvent) {
        val s = current ?: return
        for (h in 0 until e.historySize) {
            addPoint(s, e.getHistoricalX(h), e.getHistoricalY(h), pressure(e, h))
        }
        addPoint(s, e.x, e.y, pressure(e, -1))
        invalidate()
    }

    private fun addPoint(s: Stroke, x: Float, y: Float, p: Float) {
        val d = toDoc(x, y)
        val last = s.pts.lastOrNull() ?: return
        val pt = Pt(
            last.x + (d[0] - last.x) * SMOOTHING,
            last.y + (d[1] - last.y) * SMOOTHING,
            p
        )
        s.pts.add(pt)
        drawSegment(bmpCanvas, s, last, pt)
    }

    private fun endStroke() {
        current?.let {
            strokes.add(it)
            redoStack.clear()
        }
        current = null
    }

    private fun cancelStroke() {
        current = null
        rerender()
    }

    // ---- rendering ---------------------------------------------------------

    private fun widthFor(s: Stroke, p: Float): Float = s.size * (0.25f + 0.75f * p)

    private fun drawDot(c: Canvas, s: Stroke, pt: Pt) {
        dotPaint.color = s.color
        c.drawCircle(pt.x, pt.y, widthFor(s, pt.p) / 2f, dotPaint)
    }

    private fun drawSegment(c: Canvas, s: Stroke, a: Pt, b: Pt) {
        linePaint.color = s.color
        linePaint.strokeWidth = widthFor(s, (a.p + b.p) / 2f)
        c.drawLine(a.x, a.y, b.x, b.y, linePaint)
    }

    private fun rerender() {
        bmpCanvas.drawColor(Color.WHITE)
        for (s in strokes) {
            if (s.pts.isEmpty()) continue
            drawDot(bmpCanvas, s, s.pts[0])
            for (i in 1 until s.pts.size) {
                drawSegment(bmpCanvas, s, s.pts[i - 1], s.pts[i])
            }
        }
        invalidate()
    }

    companion object {
        private const val DOC_SIZE = 2048
        private const val SMOOTHING = 0.55f
    }
}
