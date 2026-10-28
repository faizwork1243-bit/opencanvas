package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Smudge: picks up the pixels under the brush and drags them along the stroke,
 * working directly on the layer.
 */
class SmudgePainter(
    private val layerBmp: Bitmap,
    private val layerCanvas: Canvas,
    private val tip: Tip,
    size: Float,
    private val strength: Float,
    seed: Long
) : StampSink {

    private val placer = StampPlacer(Brushes.SMUDGE_TIP, size, this, seed)
    private val s = ceil(size).toInt() + 2

    private val under = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    private val carry = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    private val mask = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    private val work = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    private val underCanvas = Canvas(under)
    private val carryCanvas = Canvas(carry)
    private val maskCanvas = Canvas(mask)
    private val workCanvas = Canvas(work)

    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val whitePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
    private val dstInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val depositPaint = Paint()
    private val mixPaint = Paint()

    private val box = RectF()
    private var hasBox = false
    private var primed = false

    fun begin(x: Float, y: Float, p: Float) = placer.begin(x, y, p)

    fun moveTo(x: Float, y: Float, p: Float) = placer.moveTo(x, y, p)

    fun dirtyRect(): Rect? {
        if (!hasBox) return null
        val r = Rect()
        box.roundOut(r)
        return if (r.intersect(0, 0, Document.SIZE, Document.SIZE)) r else null
    }

    override fun stamp(x: Float, y: Float, diameter: Float, flow: Float, angleDeg: Float, variant: Int) {
        val left = Math.round(x - s / 2f)
        val top = Math.round(y - s / 2f)

        // what is under the brush right now
        underCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        underCanvas.drawBitmap(layerBmp, -left.toFloat(), -top.toFloat(), null)

        if (!primed) {
            carryCanvas.drawBitmap(under, 0f, 0f, srcPaint)
            primed = true
        } else {
            // soft round mask for this stamp
            mask.eraseColor(Color.TRANSPARENT)
            val tb = tip.pick(0, diameter)
            maskCanvas.save()
            maskCanvas.translate(s / 2f, s / 2f)
            maskCanvas.scale(diameter / tb.width, diameter / tb.height)
            maskCanvas.drawBitmap(tb, -tb.width / 2f, -tb.height / 2f, whitePaint)
            maskCanvas.restore()

            // the carried paint, cut to the mask, laid down on the layer
            work.eraseColor(Color.TRANSPARENT)
            workCanvas.drawBitmap(carry, 0f, 0f, null)
            workCanvas.drawBitmap(mask, 0f, 0f, dstInPaint)
            depositPaint.alpha = (strength * flow * 255f).roundToInt().coerceIn(0, 255)
            layerCanvas.drawBitmap(work, left.toFloat(), top.toFloat(), depositPaint)

            // the carried paint fades a little and picks up what was underneath
            carryCanvas.drawColor(Color.argb(KEEP_A, 255, 255, 255), PorterDuff.Mode.DST_IN)
            mixPaint.alpha = 255 - KEEP_A
            carryCanvas.drawBitmap(under, 0f, 0f, mixPaint)
        }

        val l = left.toFloat()
        val t = top.toFloat()
        if (!hasBox) {
            box.set(l, t, l + s, t + s)
            hasBox = true
        } else {
            box.union(l, t, l + s, t + s)
        }
    }

    companion object {
        private const val KEEP_A = 215
    }
}
