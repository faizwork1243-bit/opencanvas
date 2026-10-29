package com.opencanvas.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** User-adjustable smudge behaviour. */
data class SmudgeSettings(
    /** How much paint is carried along: 0 = barely any, 1 = drags it a long way. */
    val persistence: Float = 0.8f,
    /** 0 = very soft edge, 1 = hard edge. */
    val hardness: Float = 0.5f,
    /** Distance between smudge stamps as a fraction of the brush diameter. */
    val spacing: Float = 0.08f,
    val sizePressure: Float = 0.3f,
    val strengthPressure: Float = 0.5f,
    /** Soften mode blends toward the average colour under the brush instead of dragging paint. */
    val soften: Boolean = false
)

/**
 * The smudge maths, kept free of Android types so it can be tested.
 * Paint is carried in full floating-point precision (premultiplied RGBA) and stamps are
 * placed at sub-pixel positions, so repeated stamps never accumulate rounding errors.
 */
class SmudgeCore(private val s: Int) {
    private val carry = FloatArray(s * s * 4)
    private var primed = false

    /**
     * px: s*s straight ARGB pixels of the layer under the brush, modified in place.
     * base: the same region as it was before the stroke began; the carried paint picks up from
     * this, so a smear wears off naturally instead of feeding on its own trail.
     * Only pixels inside [vx0,vx1) x [vy0,vy1) really exist on the layer; the rest are ignored.
     * (cx, cy) is the stamp centre in pixel units of the region. Returns true if pixels changed.
     */
    fun stamp(
        px: IntArray, base: IntArray, vx0: Int, vy0: Int, vx1: Int, vy1: Int,
        cx: Float, cy: Float, diameter: Float,
        hardness: Float, strength: Float, persistence: Float, pickScale: Float, soften: Boolean
    ): Boolean {
        val r = diameter / 2f
        if (!primed) {
            for (j in 0 until s) {
                for (i in 0 until s) {
                    val k = (j * s + i) * 4
                    if (i in vx0 until vx1 && j in vy0 until vy1) {
                        load(base[j * s + i], carry, k)
                    } else {
                        carry[k] = 0f; carry[k + 1] = 0f; carry[k + 2] = 0f; carry[k + 3] = 0f
                    }
                }
            }
            primed = true
            return false
        }

        val x0 = max(vx0, (cx - r - 1f).toInt())
        val x1 = min(vx1, (cx + r + 2f).toInt())
        val y0 = max(vy0, (cy - r - 1f).toInt())
        val y1 = min(vy1, (cy + r + 2f).toInt())
        if (x1 <= x0 || y1 <= y0) return false

        // average colour under the brush (for soften mode)
        var ar = 0f; var ag = 0f; var ab = 0f; var aa = 0f; var wsum = 0f
        val tmpK = FloatArray(4)
        if (soften) {
            for (j in y0 until y1) {
                for (i in x0 until x1) {
                    val cov = coverage(i + 0.5f - cx, j + 0.5f - cy, r, hardness)
                    if (cov <= 0f) continue
                    load(px[j * s + i], tmpK, 0)
                    ar += tmpK[0] * cov; ag += tmpK[1] * cov; ab += tmpK[2] * cov; aa += tmpK[3] * cov
                    wsum += cov
                }
            }
            if (wsum > 0f) {
                ar /= wsum; ag /= wsum; ab /= wsum; aa /= wsum
            }
        }

        var changed = false
        val u = FloatArray(4)
        val b = FloatArray(4)
        for (j in y0 until y1) {
            for (i in x0 until x1) {
                val cov = coverage(i + 0.5f - cx, j + 0.5f - cy, r, hardness)
                if (cov <= 0f) continue
                val k = (j * s + i) * 4
                load(px[j * s + i], u, 0)
                val m = (cov * strength).coerceIn(0f, 1f)
                val sr: Float; val sg: Float; val sb: Float; val sa: Float
                if (soften) {
                    sr = ar; sg = ag; sb = ab; sa = aa
                } else {
                    sr = carry[k]; sg = carry[k + 1]; sb = carry[k + 2]; sa = carry[k + 3]
                }
                val orr = u[0] * (1f - m) + sr * m
                val og = u[1] * (1f - m) + sg * m
                val ob = u[2] * (1f - m) + sb * m
                val oa = u[3] * (1f - m) + sa * m
                val packed = store(orr, og, ob, oa)
                if (packed != px[j * s + i]) changed = true
                px[j * s + i] = packed

                // the carried paint slowly picks up what was originally underneath
                load(base[j * s + i], b, 0)
                val pick = ((1f - persistence) * cov * pickScale).coerceIn(0f, 1f)
                carry[k] += (b[0] - carry[k]) * pick
                carry[k + 1] += (b[1] - carry[k + 1]) * pick
                carry[k + 2] += (b[2] - carry[k + 2]) * pick
                carry[k + 3] += (b[3] - carry[k + 3]) * pick
            }
        }
        return changed
    }

    companion object {
        /** Straight ARGB int to premultiplied floats 0..1. */
        fun load(p: Int, out: FloatArray, k: Int) {
            val a = ((p ushr 24) and 0xFF) / 255f
            out[k] = ((p shr 16) and 0xFF) / 255f * a
            out[k + 1] = ((p shr 8) and 0xFF) / 255f * a
            out[k + 2] = (p and 0xFF) / 255f * a
            out[k + 3] = a
        }

        /** Premultiplied floats back to a straight ARGB int. */
        fun store(r: Float, g: Float, b: Float, a: Float): Int {
            if (a <= 1e-6f) return 0
            val ai = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
            val ri = (r / a * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (g / a * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = (b / a * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        /** Soft round brush coverage at an offset (dx, dy) from the centre, in pixels. */
        fun coverage(dx: Float, dy: Float, r: Float, hardness: Float): Float {
            if (r <= 0f) return 0f
            val d = sqrt(dx * dx + dy * dy) / r
            if (d >= 1f + 1f / r) return 0f
            val h = hardness.coerceIn(0f, 0.999f)
            val a = if (d <= h) {
                1f
            } else {
                val t = ((1f - d) / (1f - h)).coerceIn(0f, 1f)
                t * t * (3f - 2f * t)
            }
            return a * ((1f - d) * r + 0.5f).coerceIn(0f, 1f)
        }
    }
}
