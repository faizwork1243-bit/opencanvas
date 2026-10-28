package com.opencanvas.app

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Receives the stamps produced along a stroke. */
interface StampSink {
    fun stamp(x: Float, y: Float, diameter: Float, flow: Float, angleDeg: Float, variant: Int)
}

/** Small deterministic random generator, so a stroke can be replayed identically. */
class XorShift(seed: Long) {
    private var s: Long = if (seed == 0L) 88172645463325252L else seed

    fun nextLong(): Long {
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        return s
    }

    fun nextFloat(): Float = (nextLong() ushr 40).toInt() / 16777216f

    fun signed(): Float = nextFloat() * 2f - 1f
}

/** Walks along a path and drops evenly spaced stamps, applying the brush dynamics. */
class StampPlacer(
    private val spec: BrushSpec,
    private val baseSize: Float,
    private val sink: StampSink,
    seed: Long
) {
    private val rnd = XorShift(seed)
    private var lastX = 0f
    private var lastY = 0f
    private var lastP = 1f
    private var remaining = 0f
    private var dirDeg = 0f
    private var started = false

    var stampCount = 0
        private set

    fun diameter(p: Float): Float =
        max(MIN_D, baseSize * (1f - spec.sizePressure + spec.sizePressure * p))

    private fun step(p: Float): Float = max(MIN_STEP, spec.spacing * diameter(p))

    fun begin(x: Float, y: Float, p: Float) {
        lastX = x
        lastY = y
        lastP = p
        started = true
        emit(x, y, p)
        remaining = step(p)
    }

    fun moveTo(x: Float, y: Float, p: Float) {
        if (!started) {
            begin(x, y, p)
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val len = hypot(dx, dy)
        if (len < 1e-4f) {
            lastP = p
            return
        }
        dirDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        var pos = 0f
        while (len - pos >= remaining) {
            pos += remaining
            val t = pos / len
            val pp = lastP + (p - lastP) * t
            emit(lastX + dx * t, lastY + dy * t, pp)
            remaining = step(pp)
        }
        remaining -= (len - pos)
        lastX = x
        lastY = y
        lastP = p
    }

    private fun emit(x: Float, y: Float, p: Float) {
        var d = diameter(p)
        if (spec.sizeJitter > 0f) {
            d = max(MIN_D, d * (1f + spec.sizeJitter * rnd.signed() * 0.5f))
        }
        val flow = (spec.flow * (1f - spec.flowPressure + spec.flowPressure * p)).coerceIn(0.01f, 1f)
        var ang = spec.angle
        if (spec.followStroke) ang += dirDeg
        if (spec.angleJitter > 0f) ang += spec.angleJitter * 180f * rnd.signed()
        var px = x
        var py = y
        if (spec.scatter > 0f) {
            px += rnd.signed() * spec.scatter * d
            py += rnd.signed() * spec.scatter * d
        }
        val v = if (spec.variants > 1) {
            (rnd.nextFloat() * spec.variants).toInt().coerceAtMost(spec.variants - 1)
        } else {
            0
        }
        sink.stamp(px, py, d, flow, ang, v)
        stampCount++
    }

    companion object {
        const val MIN_D = 1f
        const val MIN_STEP = 0.5f
    }
}

class MipLevel(val size: Int, val data: ByteArray)

/** Builds brush tips as 8-bit alpha masks. */
object TipMath {
    const val BASE = 256

    fun round(size: Int, hardness: Float): ByteArray {
        val out = ByteArray(size * size)
        val half = size / 2f
        val h = hardness.coerceIn(0f, 0.999f)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x + 0.5f - half) / half
                val dy = (y + 0.5f - half) / half
                val d = sqrt(dx * dx + dy * dy)
                var a = if (d <= h) {
                    1f
                } else {
                    val t = ((1f - d) / (1f - h)).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
                a *= ((1f - d) * half + 0.5f).coerceIn(0f, 1f)
                out[y * size + x] = (a * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    private fun hash01(x: Int, y: Int, seed: Long): Float {
        var h = x * 374761393 + y * 668265263 + (seed * 2147483647L).toInt()
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun lattice(x: Int, y: Int, cell: Int, seed: Long): Float {
        val gx = x.toFloat() / cell
        val gy = y.toFloat() / cell
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val fx = gx - x0
        val fy = gy - y0
        val a = hash01(x0, y0, seed)
        val b = hash01(x0 + 1, y0, seed)
        val c = hash01(x0, y0 + 1, seed)
        val d = hash01(x0 + 1, y0 + 1, seed)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Fine, medium and coarse noise mixed, so the grain stays visible when the tip is scaled down. */
    private fun valueNoise(size: Int, seed: Long): FloatArray {
        val out = FloatArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val fine = hash01(x, y, seed)
                val mid = lattice(x, y, 12, seed + 1)
                val coarse = lattice(x, y, 34, seed + 2)
                val n = 0.25f * fine + 0.35f * mid + 0.40f * coarse
                out[y * size + x] = ((n - 0.5f) * 2.2f + 0.5f).coerceIn(0f, 1f)
            }
        }
        return out
    }

    fun grain(size: Int, hardness: Float, amount: Float, seed: Long): ByteArray {
        val base = round(size, hardness)
        val noise = valueNoise(size, seed)
        val amt = amount.coerceIn(0f, 1f)
        for (i in base.indices) {
            val a = (base[i].toInt() and 0xFF) / 255f
            val v = ((1f - amt) + amt * 1.6f * noise[i]).coerceIn(0f, 1f)
            base[i] = (a * v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return base
    }

    fun downsample(src: ByteArray, size: Int): ByteArray {
        val n = size / 2
        val out = ByteArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = (y * 2) * size + x * 2
                val sum = (src[i].toInt() and 0xFF) + (src[i + 1].toInt() and 0xFF) +
                    (src[i + size].toInt() and 0xFF) + (src[i + size + 1].toInt() and 0xFF)
                out[y * n + x] = ((sum + 2) / 4).toByte()
            }
        }
        return out
    }

    /** Largest first: 256, 128, 64 ... down to minSize. */
    fun levels(base: ByteArray, size: Int, minSize: Int = 8): List<MipLevel> {
        val list = ArrayList<MipLevel>()
        var data = base
        var s = size
        list.add(MipLevel(s, data))
        while (s / 2 >= minSize) {
            data = downsample(data, s)
            s /= 2
            list.add(MipLevel(s, data))
        }
        return list
    }

    fun invert(mask: ByteArray): ByteArray {
        val out = ByteArray(mask.size)
        for (i in mask.indices) out[i] = (255 - (mask[i].toInt() and 0xFF)).toByte()
        return out
    }

    /**
     * Turns an imported picture into a square tip mask. Pictures with real transparency use
     * their alpha; opaque pictures treat dark pixels as paint (black shape on white).
     */
    fun maskFromArgb(px: IntArray, w: Int, h: Int, out: Int): ByteArray {
        var transparent = 0
        for (p in px) if ((p ushr 24) < 200) transparent++
        val useAlpha = transparent > px.size * 0.02
        val cov = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) / 255f
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            cov[i] = if (useAlpha) a else (1f - luma) * a
        }
        val res = ByteArray(out * out)
        val fit = (out - 2).toFloat() / max(w, h)
        val ox = (out - w * fit) / 2f
        val oy = (out - h * fit) / 2f
        for (y in 0 until out) {
            for (x in 0 until out) {
                val sx = (x + 0.5f - ox) / fit - 0.5f
                val sy = (y + 0.5f - oy) / fit - 0.5f
                if (sx < -0.5f || sy < -0.5f || sx > w - 0.5f || sy > h - 0.5f) continue
                val v = bilinear(cov, w, h, sx, sy)
                res[y * out + x] = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return res
    }

    private fun bilinear(src: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun at(ix: Int, iy: Int): Float = src[iy.coerceIn(0, h - 1) * w + ix.coerceIn(0, w - 1)]
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) +
            (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
    }
}
