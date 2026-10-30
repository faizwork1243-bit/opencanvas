package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Smudge: picks up the pixels under the brush and drags them along the stroke.
 * The layer itself is not touched while you smudge: the changed area lives in small live tiles
 * (copied from the layer the first time the brush reaches them) and is merged when the stroke ends.
 * All the maths lives in SmudgeCore, which carries paint in floating point.
 */
class SmudgePainter(
    private val layerBmp: Bitmap,
    private val overlay: TileSet,
    private val settings: SmudgeSettings,
    size: Float,
    private val strength: Float,
    seed: Long
) : StampSink {

    private val placer = StampPlacer(
        Brushes.SMUDGE_TIP.copy(
            spacing = settings.spacing,
            hardness = settings.hardness,
            sizePressure = settings.sizePressure,
            flowPressure = settings.strengthPressure
        ),
        size, this, seed
    )
    private val s = ceil(size).toInt() + 2
    private val core = SmudgeCore(s)
    private val region = IntArray(s * s)
    private val baseRegion = IntArray(s * s)
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    private val box = RectF()
    private var hasBox = false

    fun begin(x: Float, y: Float, p: Float) = placer.begin(x, y, p)

    fun moveTo(x: Float, y: Float, p: Float) = placer.moveTo(x, y, p)

    fun dirtyRect(): Rect? {
        if (!hasBox) return null
        val r = Rect()
        box.roundOut(r)
        return if (r.intersect(0, 0, layerBmp.width, layerBmp.height)) r else null
    }

    /** Makes sure every tile under the area holds a copy of the layer's pixels. */
    private fun prepareTiles(x0: Int, y0: Int, x1: Int, y1: Int) {
        TileGeom.forPieces(x0, y0, x1, y1, overlay.cols, overlay.rows) { tx, ty, _, _, _, _ ->
            if (!overlay.isTouched(tx, ty)) {
                val c = overlay.touch(tx, ty)
                c.drawBitmap(layerBmp, -(tx * TileGeom.T).toFloat(), -(ty * TileGeom.T).toFloat(), srcPaint)
            }
        }
    }

    override fun stamp(
        x: Float, y: Float, diameter: Float, flow: Float, angleDeg: Float, variant: Int, shade: Float
    ) {
        val left = Math.round(x - s / 2f)
        val top = Math.round(y - s / 2f)
        val vx0 = max(0, -left)
        val vy0 = max(0, -top)
        val vx1 = min(s, layerBmp.width - left)
        val vy1 = min(s, layerBmp.height - top)
        if (vx1 <= vx0 || vy1 <= vy0) return
        val dx0 = left + vx0
        val dy0 = top + vy0
        val dx1 = left + vx1
        val dy1 = top + vy1

        prepareTiles(dx0, dy0, dx1, dy1)
        java.util.Arrays.fill(region, 0)
        java.util.Arrays.fill(baseRegion, 0)

        // current state of the area (layer plus what this stroke has smudged so far)
        TileGeom.forPieces(dx0, dy0, dx1, dy1, overlay.cols, overlay.rows) { tx, ty, ix0, iy0, ix1, iy1 ->
            overlay.bitmap(tx, ty).getPixels(
                region, (iy0 - top) * s + (ix0 - left), s,
                ix0 - tx * TileGeom.T, iy0 - ty * TileGeom.T, ix1 - ix0, iy1 - iy0
            )
        }
        // the area as it was before the stroke began
        layerBmp.getPixels(baseRegion, vy0 * s + vx0, s, dx0, dy0, vx1 - vx0, vy1 - vy0)

        val changed = core.stamp(
            region, baseRegion, vx0, vy0, vx1, vy1,
            x - left, y - top, diameter,
            settings.hardness, (strength * flow).coerceIn(0f, 1f),
            settings.persistence, settings.spacing / 0.1f, settings.soften
        )
        if (changed) {
            TileGeom.forPieces(dx0, dy0, dx1, dy1, overlay.cols, overlay.rows) { tx, ty, ix0, iy0, ix1, iy1 ->
                overlay.bitmap(tx, ty).setPixels(
                    region, (iy0 - top) * s + (ix0 - left), s,
                    ix0 - tx * TileGeom.T, iy0 - ty * TileGeom.T, ix1 - ix0, iy1 - iy0
                )
            }
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
}
