package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Draws a stroke onto a canvas by stamping the brush tip along the path. */
class BrushPainter(
    private val canvas: Canvas,
    private val spec: BrushSpec,
    private val tip: Tip,
    size: Float,
    private val color: Int,
    seed: Long
) : StampSink {

    private val placer = StampPlacer(spec, size, this, seed)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private var hasBox = false

    fun begin(x: Float, y: Float, p: Float) = placer.begin(x, y, p)

    fun moveTo(x: Float, y: Float, p: Float) = placer.moveTo(x, y, p)

    /** The area touched so far, clamped to the canvas, or null if nothing landed inside it. */
    fun dirtyRect(): Rect? {
        if (!hasBox) return null
        val r = Rect()
        box.roundOut(r)
        return if (r.intersect(0, 0, Document.SIZE, Document.SIZE)) r else null
    }

    override fun stamp(x: Float, y: Float, diameter: Float, flow: Float, angleDeg: Float, variant: Int, shade: Float) {
        val bmp = tip.pick(variant, diameter)
        val a = (flow * 255f + 0.5f).toInt().coerceIn(1, 255)
        paint.color = (ColorMath.shade(color, shade) and 0x00FFFFFF) or (a shl 24)
        canvas.save()
        canvas.translate(x, y)
        if (angleDeg != 0f) canvas.rotate(angleDeg)
        canvas.scale(diameter / bmp.width, diameter * spec.roundness / bmp.height)
        canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, paint)
        canvas.restore()

        val r = diameter / 2f + 2f
        if (!hasBox) {
            box.set(x - r, y - r, x + r, y + r)
            hasBox = true
        } else {
            box.union(x - r, y - r, x + r, y + r)
        }
    }
}

/** Draws a small sample stroke of a brush, for the brush list and Brush Studio. */
object BrushPreview {
    fun render(context: Context, spec: BrushSpec, w: Int, h: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val painter = BrushPainter(Canvas(bmp), spec, TipCache.get(context, spec), h * 0.42f, color, 7L)
        val n = 60
        val twoPi = (2.0 * PI).toFloat()
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * 0.08f + t * w * 0.84f
            val y = h * 0.5f - sin(t * twoPi) * h * 0.2f
            val p = 0.25f + 0.75f * sin(PI.toFloat() * t).pow(0.7f)
            if (i == 0) painter.begin(x, y, p) else painter.moveTo(x, y, p)
        }
        return bmp
    }
}
