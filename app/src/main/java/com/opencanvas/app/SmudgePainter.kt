package com.opencanvas.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Smudge: picks up the pixels under the brush and drags them along the stroke, working directly
 * on the layer. All the maths lives in SmudgeCore, which carries paint in floating point.
 */
class SmudgePainter(
    private val layerBmp: Bitmap,
    private val baseBmp: Bitmap,
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
        val w = vx1 - vx0
        val h = vy1 - vy0

        java.util.Arrays.fill(region, 0)
        java.util.Arrays.fill(baseRegion, 0)
        layerBmp.getPixels(region, vy0 * s + vx0, s, left + vx0, top + vy0, w, h)
        baseBmp.getPixels(baseRegion, vy0 * s + vx0, s, left + vx0, top + vy0, w, h)

        val changed = core.stamp(
            region, baseRegion, vx0, vy0, vx1, vy1,
            x - left, y - top, diameter,
            settings.hardness, (strength * flow).coerceIn(0f, 1f),
            settings.persistence, settings.spacing / 0.1f, settings.soften
        )
        if (changed) {
            layerBmp.setPixels(region, vy0 * s + vx0, s, left + vx0, top + vy0, w, h)
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
