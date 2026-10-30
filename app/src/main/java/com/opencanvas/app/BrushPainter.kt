package com.opencanvas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Something a stamp can be drawn onto: one canvas, or the grid of live tiles. */
interface StampDrawer {
    fun drawOn(c: Canvas, ox: Float, oy: Float)
}

interface StampTarget {
    fun draw(l: Float, t: Float, r: Float, b: Float, drawer: StampDrawer)
}

class CanvasTarget(private val canvas: Canvas) : StampTarget {
    override fun draw(l: Float, t: Float, r: Float, b: Float, drawer: StampDrawer) {
        drawer.drawOn(canvas, 0f, 0f)
    }
}

class TileTarget(private val tiles: TileSet) : StampTarget {
    override fun draw(l: Float, t: Float, r: Float, b: Float, drawer: StampDrawer) {
        TileGeom.forPieces(
            floor(l).toInt(), floor(t).toInt(), ceil(r).toInt(), ceil(b).toInt(),
            tiles.cols, tiles.rows
        ) { tx, ty, _, _, _, _ ->
            drawer.drawOn(tiles.touch(tx, ty), (tx * TileGeom.T).toFloat(), (ty * TileGeom.T).toFloat())
        }
    }
}

/** Draws a stroke by stamping the brush tip along the path. */
class BrushPainter(
    private val target: StampTarget,
    private val spec: BrushSpec,
    private val tip: Tip,
    size: Float,
    private val color: Int,
    seed: Long,
    private val canvasW: Int,
    private val canvasH: Int
) : StampSink, StampDrawer {

    private val placer = StampPlacer(spec, size, this, seed)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private var hasBox = false

    private lateinit var curBmp: Bitmap
    private var curX = 0f
    private var curY = 0f
    private var curD = 1f
    private var curAngle = 0f

    fun begin(x: Float, y: Float, p: Float) = placer.begin(x, y, p)

    fun moveTo(x: Float, y: Float, p: Float) = placer.moveTo(x, y, p)

    /** The area touched so far, clamped to the canvas, or null if nothing landed inside it. */
    fun dirtyRect(): Rect? {
        if (!hasBox) return null
        val r = Rect()
        box.roundOut(r)
        return if (r.intersect(0, 0, canvasW, canvasH)) r else null
    }

    override fun stamp(x: Float, y: Float, diameter: Float, flow: Float, angleDeg: Float, variant: Int, shade: Float) {
        curBmp = tip.pick(variant, diameter)
        curX = x
        curY = y
        curD = diameter
        curAngle = angleDeg
        val a = (flow * 255f + 0.5f).toInt().coerceIn(1, 255)
        paint.color = (ColorMath.shade(color, shade) and 0x00FFFFFF) or (a shl 24)

        val r = diameter / 2f + 2f
        target.draw(x - r, y - r, x + r, y + r, this)

        if (!hasBox) {
            box.set(x - r, y - r, x + r, y + r)
            hasBox = true
        } else {
            box.union(x - r, y - r, x + r, y + r)
        }
    }

    override fun drawOn(c: Canvas, ox: Float, oy: Float) {
        c.save()
        c.translate(curX - ox, curY - oy)
        if (curAngle != 0f) c.rotate(curAngle)
        c.scale(curD / curBmp.width, curD * spec.roundness / curBmp.height)
        c.drawBitmap(curBmp, -curBmp.width / 2f, -curBmp.height / 2f, paint)
        c.restore()
    }
}

/** Draws a small sample stroke of a brush, for the brush list and Brush Studio. */
object BrushPreview {
    fun render(context: Context, spec: BrushSpec, w: Int, h: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val painter = BrushPainter(CanvasTarget(Canvas(bmp)), spec, TipCache.get(context, spec), h * 0.42f, color, 7L, w, h)
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
