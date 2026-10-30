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
    val spacing: Float = 0.12f,
    val sizePressure: Float = 0.3f,
    val strengthPressure: Float = 0.5f,
    /** Soften mode blends toward the average colour under the brush instead of dragging paint. */
    val soften: Boolean = false
)

/**
 * The smudge maths, kept free of Android types so it can be tested.
 * Paint is carried in full floating-point precision (premultiplied RGBA) and stamps are
 * placed at sub-pixel positions, so repeated stamps never accumulate rounding errors.
 * Brush coverage comes from a lookup table, which keeps each stamp cheap on a phone.
 */
class SmudgeCore(private val s: Int) {
    private val carry = FloatArray(s * s * 4)
    private var primed = false

    private val lut = FloatArray(LUT_N + 2)
    private var lutR = -1f
    private var lutH = -1f

    private fun buildLut(r: Float, hardness: Float) {
        if (r == lutR && hardness == lutH) return
        lutR = r
        lutH = hardness
        val maxD = 1f + 1f / r
        val maxD2 = maxD * maxD
        for (i in 0..LUT_N) {
            val d = sqrt(i.toFloat() / LUT_N * maxD2)
            lut[i] = coverageAt(d, r, hardness)
        }
        lut[LUT_N + 1] = 0f
    }

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

        buildLut(r, hardness)
        val maxD = 1f + 1f / r
        val maxD2 = maxD * maxD
        val lutScale = LUT_N / maxD2
        val invR2 = 1f / (r * r)

        // average colour under the brush (for soften mode); every other pixel is plenty
        var ar = 0f; var ag = 0f; var ab = 0f; var aa = 0f; var wsum = 0f
        if (soften) {
            var j = y0
            while (j < y1) {
                val dy = j + 0.5f - cy
                var i = x0
                while (i < x1) {
                    val dx = i + 0.5f - cx
                    val d2 = (dx * dx + dy * dy) * invR2
                    if (d2 < maxD2) {
                        val cov = lut[(d2 * lutScale).toInt()]
                        if (cov > 0.004f) {
                            val p = px[j * s + i]
                            val a = LUT8[(p ushr 24) and 0xFF]
                            ar += LUT8[(p shr 16) and 0xFF] * a * cov
                            ag += LUT8[(p shr 8) and 0xFF] * a * cov
                            ab += LUT8[p and 0xFF] * a * cov
                            aa += a * cov
                            wsum += cov
                        }
                    }
                    i += 2
                }
                j += 2
            }
            if (wsum > 0f) {
                ar /= wsum; ag /= wsum; ab /= wsum; aa /= wsum
            }
        }

        var changed = false
        val keepPick = (1f - persistence) * pickScale
        for (j in y0 until y1) {
            val dy = j + 0.5f - cy
            val dy2 = dy * dy
            for (i in x0 until x1) {
                val dx = i + 0.5f - cx
                val d2 = (dx * dx + dy2) * invR2
                if (d2 >= maxD2) continue
                val cov = lut[(d2 * lutScale).toInt()]
                if (cov < 0.004f) continue

                val idx = j * s + i
                val k = idx * 4
                val p = px[idx]
                val ua = LUT8[(p ushr 24) and 0xFF]
                val ur = LUT8[(p shr 16) and 0xFF] * ua
                val ug = LUT8[(p shr 8) and 0xFF] * ua
                val ub = LUT8[p and 0xFF] * ua

                val m = min(cov * strength, 1f)
                val im = 1f - m
                val packed = if (soften) {
                    store(ur * im + ar * m, ug * im + ag * m, ub * im + ab * m, ua * im + aa * m)
                } else {
                    store(
                        ur * im + carry[k] * m, ug * im + carry[k + 1] * m,
                        ub * im + carry[k + 2] * m, ua * im + carry[k + 3] * m
                    )
                }
                if (packed != p) {
                    px[idx] = packed
                    changed = true
                }

                // the carried paint slowly picks up what was originally underneath
                val bp = base[idx]
                val ba = LUT8[(bp ushr 24) and 0xFF]
                val pick = min(keepPick * cov, 1f)
                carry[k] += (LUT8[(bp shr 16) and 0xFF] * ba - carry[k]) * pick
                carry[k + 1] += (LUT8[(bp shr 8) and 0xFF] * ba - carry[k + 1]) * pick
                carry[k + 2] += (LUT8[bp and 0xFF] * ba - carry[k + 2]) * pick
                carry[k + 3] += (ba - carry[k + 3]) * pick
            }
        }
        return changed
    }

    companion object {
        private const val LUT_N = 512
        private val LUT8 = FloatArray(256) { it / 255f }

        /** Straight ARGB int to premultiplied floats 0..1. */
        fun load(p: Int, out: FloatArray, k: Int) {
            val a = LUT8[(p ushr 24) and 0xFF]
            out[k] = LUT8[(p shr 16) and 0xFF] * a
            out[k + 1] = LUT8[(p shr 8) and 0xFF] * a
            out[k + 2] = LUT8[p and 0xFF] * a
            out[k + 3] = a
        }

        /** Premultiplied floats back to a straight ARGB int. */
        fun store(r: Float, g: Float, b: Float, a: Float): Int {
            if (a <= 1e-6f) return 0
            val ai = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
            val inv = 255f / a
            val ri = (r * inv + 0.5f).toInt().coerceIn(0, 255)
            val gi = (g * inv + 0.5f).toInt().coerceIn(0, 255)
            val bi = (b * inv + 0.5f).toInt().coerceIn(0, 255)
            return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        /** Soft round brush coverage at a normalised distance d (1 = the edge), r in pixels. */
        fun coverageAt(d: Float, r: Float, hardness: Float): Float {
            if (r <= 0f) return 0f
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
