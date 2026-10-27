package com.opencanvas.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.roundToInt

class Pt(val x: Float, val y: Float, val p: Float)

class Stroke(val color: Int, val size: Float, val opacity: Float, val erase: Boolean) {
    val pts = ArrayList<Pt>()
    val bounds = RectF()

    fun add(pt: Pt) {
        if (pts.isEmpty()) {
            bounds.set(pt.x, pt.y, pt.x, pt.y)
        } else {
            bounds.union(pt.x, pt.y)
        }
        pts.add(pt)
    }

    fun inflatedBounds(): RectF {
        val r = RectF(bounds)
        val m = size / 2f + 2f
        r.inset(-m, -m)
        return r
    }
}

/** Draws strokes. Used both for live drawing (into a scratch bitmap) and for replaying history. */
object StrokeRenderer {
    val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val groupPaint = Paint()
    private val commitPaint = Paint()

    private fun widthFor(s: Stroke, p: Float): Float = s.size * (0.25f + 0.75f * p)

    private fun colorOf(s: Stroke): Int =
        if (s.erase) Color.BLACK else ((s.color and 0x00FFFFFF) or (0xFF shl 24))

    fun alphaOf(s: Stroke): Int = (s.opacity * 255f).roundToInt().coerceIn(0, 255)

    fun drawDot(c: Canvas, s: Stroke, pt: Pt) {
        dotPaint.color = colorOf(s)
        c.drawCircle(pt.x, pt.y, widthFor(s, pt.p) / 2f, dotPaint)
    }

    fun drawSegment(c: Canvas, s: Stroke, a: Pt, b: Pt) {
        linePaint.color = colorOf(s)
        linePaint.strokeWidth = widthFor(s, (a.p + b.p) / 2f)
        c.drawLine(a.x, a.y, b.x, b.y, linePaint)
    }

    private fun drawOpaque(c: Canvas, s: Stroke) {
        drawDot(c, s, s.pts[0])
        for (i in 1 until s.pts.size) {
            drawSegment(c, s, s.pts[i - 1], s.pts[i])
        }
    }

    /** Draws a whole stroke with its opacity applied as one group (no double-darkening at overlaps). */
    fun render(c: Canvas, s: Stroke) {
        if (s.pts.isEmpty()) return
        if (s.opacity >= 0.999f && !s.erase) {
            drawOpaque(c, s)
            return
        }
        groupPaint.alpha = alphaOf(s)
        groupPaint.xfermode = if (s.erase) dstOut else null
        val save = c.saveLayer(s.inflatedBounds(), groupPaint)
        drawOpaque(c, s)
        c.restoreToCount(save)
    }

    /** Paint used to composite the live scratch bitmap into a layer when a stroke ends. */
    fun paintForCommit(s: Stroke): Paint {
        commitPaint.alpha = alphaOf(s)
        commitPaint.xfermode = if (s.erase) dstOut else null
        return commitPaint
    }
}
