package com.opencanvas.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Receives the stamps produced along a stroke. */
interface StampSink {
    /** shade is -1..1: negative = darker, positive = lighter (already scaled by the brush's colour jitter). */
    fun stamp(x: Float, y: Float, diameter: Float, flow: Float, angleDeg: Float, variant: Int, shade: Float)
}

object ColorMath {
    /** Moves a colour toward white (shade > 0) or black (shade < 0). Alpha is kept. */
    fun shade(color: Int, shade: Float): Int {
        if (shade == 0f) return color
        val t = min(abs(shade), 1f) * 0.4f
        val target = if (shade > 0f) 255 else 0
        val r = (((color shr 16) and 0xFF) + (target - ((color shr 16) and 0xFF)) * t + 0.5f).toInt()
        val g = (((color shr 8) and 0xFF) + (target - ((color shr 8) and 0xFF)) * t + 0.5f).toInt()
        val b = ((color and 0xFF) + (target - (color and 0xFF)) * t + 0.5f).toInt()
        return (color and (0xFF shl 24)) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
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
        val n = max(1, spec.count)
        for (i in 0 until n) emitOne(x, y, p, n > 1)
    }

    private fun emitOne(x: Float, y: Float, p: Float, multi: Boolean) {
        val area = diameter(p)
        var d = max(MIN_D, area * spec.tipScale)
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
            if (multi) {
                // spread evenly over a disc, like spray paint
                val r = sqrt(rnd.nextFloat()) * spec.scatter * area
                val th = rnd.nextFloat() * 6.2831855f
                px += r * cos(th)
                py += r * sin(th)
            } else {
                px += rnd.signed() * spec.scatter * area
                py += rnd.signed() * spec.scatter * area
            }
        }
        val v = if (spec.variants > 1) {
            (rnd.nextFloat() * spec.variants).toInt().coerceAtMost(spec.variants - 1)
        } else {
            0
        }
        val shade = if (spec.colorJitter > 0f) spec.colorJitter * rnd.signed() else 0f
        sink.stamp(px, py, d, flow, ang, v, shade)
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

    // ---- shaped tips -----------------------------------------------------------------

    private const val TWO_PI = 6.2831855f

    private inline fun build(size: Int, f: (Float, Float) -> Float): ByteArray {
        val out = ByteArray(size * size)
        val half = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val nx = (x + 0.5f - half) / half
                val ny = (y + 0.5f - half) / half
                out[y * size + x] = (f(nx, ny).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Anti-aliased coverage from a signed distance in tip units (negative = inside). */
    private fun edge(dist: Float, half: Float): Float = (0.5f - dist * half).coerceIn(0f, 1f)

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    fun star(size: Int, points: Int = 5, inner: Float = 0.45f): ByteArray {
        val half = size / 2f
        val outer = 0.92f
        val ri = outer * inner
        val sector = TWO_PI / points
        val halfSec = sector / 2f
        return build(size) { nx, ny ->
            val r = sqrt(nx * nx + ny * ny)
            if (r < 1e-4f) {
                1f
            } else {
                var a = atan2(nx, -ny)
                if (a < 0f) a += TWO_PI
                val sIn = a % sector
                val rA: Float
                val tA: Float
                val rB: Float
                val tB: Float
                if (sIn < halfSec) {
                    rA = outer; tA = 0f; rB = ri; tB = halfSec
                } else {
                    rA = ri; tA = halfSec; rB = outer; tB = sector
                }
                val rb = (rA * rB * sin(tB - tA)) / (rA * sin(tB - sIn) + rB * sin(sIn - tA))
                edge(r - rb, half)
            }
        }
    }

    fun square(size: Int, radius: Float = 0.18f): ByteArray {
        val half = size / 2f
        val b = 0.82f
        return build(size) { nx, ny ->
            val qx = abs(nx) - (b - radius)
            val qy = abs(ny) - (b - radius)
            val d = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - radius
            edge(d, half)
        }
    }

    fun stripes(size: Int, count: Float = 5f): ByteArray {
        val half = size / 2f
        return build(size) { nx, ny ->
            val r = sqrt(nx * nx + ny * ny)
            val disc = edge(r - 0.92f, half)
            val u = (nx + ny) * 0.7071f * count * 0.5f
            val f = u - floor(u)
            val t = abs(f - 0.5f) * 2f
            val bar = ((0.5f - t) / 0.06f + 0.5f).coerceIn(0f, 1f)
            disc * bar
        }
    }

    /** One tapered blade, base at the bottom of the tip, point at the top. */
    private fun blade(
        nx: Float, ny: Float, cx: Float, lean: Float, width: Float, topY: Float, half: Float
    ): Float {
        val bottom = 0.95f
        val t = (bottom - ny) / (bottom - topY)
        if (t < 0f || t > 1.02f) return 0f
        val tc = t.coerceIn(0f, 1f)
        val x0 = cx + lean * tc * tc
        val hw = width * (1f - tc).pow(0.85f) + 0.008f
        return ((hw - abs(nx - x0)) * half + 0.5f).coerceIn(0f, 1f)
    }

    fun grassTuft(size: Int): ByteArray {
        val half = size / 2f
        return build(size) { nx, ny ->
            max(
                blade(nx, ny, -0.30f, -0.35f, 0.11f, -0.55f, half),
                max(
                    blade(nx, ny, 0.0f, 0.12f, 0.13f, -0.95f, half),
                    blade(nx, ny, 0.30f, 0.40f, 0.11f, -0.65f, half)
                )
            )
        }
    }

    fun hair(size: Int): ByteArray {
        val half = size / 2f
        return build(size) { nx, ny -> blade(nx, ny, 0f, 0.25f, 0.032f, -0.95f, half) }
    }

    fun leaf(size: Int): ByteArray {
        val half = size / 2f
        return build(size) { nx, ny ->
            val bottom = 0.92f
            val top = -0.92f
            val t = (bottom - ny) / (bottom - top)
            if (t < 0f || t > 1f) {
                0f
            } else {
                val x0 = 0.16f * sin(t * PI.toFloat())
                val hw = 0.42f * sin(t * PI.toFloat()).pow(0.75f) * (1f - 0.15f * t) + 0.004f
                val cov = ((hw - abs(nx - x0)) * half + 0.5f).coerceIn(0f, 1f)
                val vein = if (t in 0.1f..0.92f) {
                    1f - ((0.02f - abs(nx - x0)) * half + 0.5f).coerceIn(0f, 1f) * 0.5f
                } else {
                    1f
                }
                cov * vein
            }
        }
    }

    fun foliage(size: Int, seed: Long): ByteArray {
        val half = size / 2f
        val rnd = XorShift(seed * 7919L + 13L)
        val n = 17
        val cxs = FloatArray(n)
        val cys = FloatArray(n)
        val crs = FloatArray(n)
        for (i in 0 until n) {
            val a = rnd.nextFloat() * TWO_PI
            val r = sqrt(rnd.nextFloat()) * 0.62f
            cxs[i] = r * cos(a)
            cys[i] = r * sin(a)
            crs[i] = 0.15f + rnd.nextFloat() * 0.15f
        }
        val m = 8
        val hxs = FloatArray(m)
        val hys = FloatArray(m)
        val hrs = FloatArray(m)
        for (i in 0 until m) {
            val a = rnd.nextFloat() * TWO_PI
            val r = sqrt(rnd.nextFloat()) * 0.55f
            hxs[i] = r * cos(a)
            hys[i] = r * sin(a)
            hrs[i] = 0.045f + rnd.nextFloat() * 0.05f
        }
        return build(size) { nx, ny ->
            var cov = 0f
            for (i in 0 until n) {
                cov = max(cov, edge(hypot(nx - cxs[i], ny - cys[i]) - crs[i], half))
            }
            var hole = 0f
            for (i in 0 until m) {
                hole = max(hole, edge(hypot(nx - hxs[i], ny - hys[i]) - hrs[i], half))
            }
            cov * (1f - 0.85f * hole)
        }
    }

    fun pine(size: Int): ByteArray {
        val half = size / 2f
        // three stacked triangles and a trunk
        fun tier(nx: Float, ny: Float, apexY: Float, baseY: Float, maxHw: Float): Float {
            if (ny < apexY - 0.02f || ny > baseY + 0.02f) return 0f
            val t = ((ny - apexY) / (baseY - apexY)).coerceIn(0f, 1f)
            val hw = maxHw * t
            val ex = ((hw - abs(nx)) * half + 0.5f).coerceIn(0f, 1f)
            val ey = min(((ny - apexY) * half + 0.5f).coerceIn(0f, 1f), ((baseY - ny) * half + 0.5f).coerceIn(0f, 1f))
            return ex * ey
        }
        return build(size) { nx, ny ->
            var c = tier(nx, ny, -0.92f, -0.28f, 0.36f)
            c = max(c, tier(nx, ny, -0.58f, 0.14f, 0.52f))
            c = max(c, tier(nx, ny, -0.2f, 0.58f, 0.68f))
            val trunk = ((0.085f - abs(nx)) * half + 0.5f).coerceIn(0f, 1f) *
                (if (ny in 0.5f..0.94f) 1f else 0f)
            max(c, trunk)
        }
    }

    fun cloud(size: Int, seed: Long): ByteArray {
        val half = size / 2f
        val rnd = XorShift(seed * 104729L + 5L)
        val n = 9
        val cxs = FloatArray(n)
        val cys = FloatArray(n)
        val crs = FloatArray(n)
        for (i in 0 until 5) {
            cxs[i] = -0.55f + 0.275f * i + rnd.signed() * 0.04f
            cys[i] = 0.16f + rnd.signed() * 0.05f
            crs[i] = 0.36f + rnd.nextFloat() * 0.07f
        }
        for (i in 5 until n) {
            cxs[i] = -0.36f + 0.36f * (i - 5) + rnd.signed() * 0.05f
            cys[i] = -0.14f + rnd.signed() * 0.07f
            crs[i] = 0.42f + rnd.nextFloat() * 0.09f
        }
        return build(size) { nx, ny ->
            var cov = 0f
            for (i in 0 until n) {
                val d = hypot(nx - cxs[i], ny - cys[i]) / crs[i]
                cov = max(cov, smooth((1f - d) / 0.8f))
            }
            cov
        }
    }

    fun crayon(size: Int, seed: Long): ByteArray {
        val noise = valueNoise(size, seed)
        val out = ByteArray(size * size)
        val half = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val nx = (x + 0.5f - half) / half
                val ny = (y + 0.5f - half) / half
                val i = y * size + x
                val r = sqrt(nx * nx + ny * ny)
                val rough = r + (noise[i] - 0.5f) * 0.3f
                val base = edge(rough - 0.88f, half)
                val fine = hash01(x, y, seed + 5)
                val wax = (0.3f + 1.05f * fine + 0.5f * (noise[i] - 0.5f)).coerceIn(0f, 1f)
                out[i] = (base * wax * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    fun water(size: Int): ByteArray {
        val noise = valueNoise(size, 77L)
        val out = ByteArray(size * size)
        val half = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val nx = (x + 0.5f - half) / half
                val ny = (y + 0.5f - half) / half
                val i = y * size + x
                val r = sqrt(nx * nx + ny * ny)
                // pigment pools at the rim of a wet stroke
                val body = 0.5f + 0.5f * smooth((r - 0.25f) / 0.6f)
                val granulation = 0.82f + 0.3f * noise[i]
                val a = body * granulation * edge(r - 0.95f, half)
                out[i] = (a * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Horizontal streaks, so a tip that follows the stroke leaves bristle marks along it. */
    fun bristle(size: Int, seed: Long): ByteArray {
        val half = size / 2f
        val bands = 20
        val strength = FloatArray(bands)
        val reach = FloatArray(bands)
        val rnd = XorShift(seed * 15485863L + 3L)
        for (b in 0 until bands) {
            val u = rnd.nextFloat()
            strength[b] = if (u < 0.22f) 0f else 0.35f + 0.65f * rnd.nextFloat()
            reach[b] = 0.62f + 0.32f * rnd.nextFloat()
        }
        return build(size) { nx, ny ->
            val u = (ny + 1f) / 2f * bands
            val b = floor(u).toInt().coerceIn(0, bands - 1)
            val f = u - floor(u)
            val inBand = ((0.5f - abs(f - 0.5f) - 0.1f) / 0.1f).coerceIn(0f, 1f)
            val along = edge(abs(nx) - reach[b], half)
            val env = edge(abs(ny) - 0.92f, half)
            strength[b] * inBand * along * env
        }
    }

    fun weave(size: Int, cells: Float = 7f): ByteArray {
        val half = size / 2f
        return build(size) { nx, ny ->
            val r = sqrt(nx * nx + ny * ny)
            val disc = edge(r - 0.92f, half)
            val gx = (nx + 1f) / 2f * cells
            val gy = (ny + 1f) / 2f * cells
            val ix = floor(gx).toInt()
            val iy = floor(gy).toInt()
            val fx = gx - ix
            val fy = gy - iy
            val over = ((ix + iy) and 1) == 0
            val bump = if (over) sin(fy * PI.toFloat()) else sin(fx * PI.toFloat())
            disc * (0.3f + 0.7f * bump)
        }
    }

    /** The 256 px mask for a built-in tip kind. Image tips are loaded from files instead. */
    fun forKind(kind: TipKind, size: Int, hardness: Float, grainAmount: Float, variant: Int): ByteArray {
        val seed = longArrayOf(11L, 23L, 37L)[variant.coerceIn(0, 2)]
        return when (kind) {
            TipKind.ROUND, TipKind.IMAGE -> round(size, hardness)
            TipKind.GRAIN -> grain(size, hardness, grainAmount, seed)
            TipKind.CRAYON -> crayon(size, seed)
            TipKind.WATER -> water(size)
            TipKind.BRISTLE -> bristle(size, seed)
            TipKind.WEAVE -> weave(size, 8f)
            TipKind.STAR -> star(size)
            TipKind.SQUARE -> square(size)
            TipKind.STRIPES -> stripes(size)
            TipKind.BLADE -> grassTuft(size)
            TipKind.HAIR -> hair(size)
            TipKind.LEAF -> leaf(size)
            TipKind.FOLIAGE -> foliage(size, seed)
            TipKind.PINE -> pine(size)
            TipKind.CLOUD -> cloud(size, seed)
        }
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
